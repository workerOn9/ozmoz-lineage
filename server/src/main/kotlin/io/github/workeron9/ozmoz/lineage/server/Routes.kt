package io.github.workeron9.ozmoz.lineage.server

import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.format.LineageExporters
import io.github.workeron9.ozmoz.lineage.graph.Direction
import io.github.workeron9.ozmoz.lineage.graph.LineageGraph
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import io.github.workeron9.ozmoz.lineage.graph.ImpactResult

/**
 * Ktor 服务器模块：`/api` 系列端点（`02-架构/web子项目设计.md` §4.1 的落点）。
 *
 * 设计约定：
 * - **解析失败不是 HTTP 错误**：「解析不了 / 查不到」是**数据**（诊断 / unknown /
 *   null 路径），给 200；仅**请求本身非法**（缺 `sql`、未知引擎 id、内联 DDL 解析
 *   失败、非法 direction / depth、aggregate 与非 json format 互斥）才 400；请求合法但
 *   **无法满足**（0 / 多条可建模语句、目标列不在图里）才 422（多条有 `aggregate=true`
 *   的 opt-in 合并出口，2026-10-10 拍板，缺省不变）。
 * - 图算法的权威实现只在 `graph` 模块（[LineageGraph]）；`cli` 与本模块都消费它，
 *   不在各自层里重写遍历。
 * - 离线红线：本模块**不代理任何外部服务**；schema 只接受请求体内的内联 DDL 文本。
 *
 * [webRoot] 非空时追加 SPA 静态托管（见文件尾部的 [staticWeb]）：
 * `/api` 路由优先匹配，其余 GET 先找实体文件，找不到回落 `index.html`。
 */
public fun Application.serverModule(webRoot: java.nio.file.Path? = null) {
    install(ContentNegotiation) {
        json(ServerJson.json)
    }
    routing {
        get("/api/health") {
            call.respond(HealthReport(status = "ok", version = OzmlLineageServer.version))
        }

        get("/api/engines") {
            call.respond(
                ServerPipeline.allEngines().map { engine ->
                    EngineReport(
                        id = engine.id,
                        features = Feature.entries.filter { engine.capabilities.supports(it) }.map { it.name },
                        dialects = engine.capabilities.dialects.toList(),
                        reasons = engine.capabilities.reasons.mapKeys { (feature, _) -> feature.name },
                    )
                },
            )
        }

        post("/api/parse") {
            val query = call.receiveQuery<ParseQuery>() ?: return@post call.badRequest("invalid_request", bodyShape("sql"))
            val engine = ServerPipeline.engineById(query.engine ?: ServerPipeline.DEFAULT_ENGINE)
                ?: return@post call.badRequest("unknown_engine", "未注册的引擎：${query.engine}")
            // 解析失败（诊断 ERROR）不是 HTTP 错误——错误进 diagnostics，仍是 200。
            val outcome = ServerPipeline.parse(query.sql, engine, query.dialect)
            call.respond(ParseReportDto(outcome.root, outcome.tables, outcome.diagnostics))
        }

        post("/api/lineage") {
            val query = call.receiveQuery<LineageQuery>() ?: return@post call.badRequest("invalid_request", bodyShape("sql"))
            // 导出格式按 id 查 format 模块注册表（与 CLI --format 取值同名）；显式 "json"
            // 与缺省同义（LineageModel 本体响应）。未注册 id → 400，在建图**之前**短路。
            val exporter = query.format?.let { requested ->
                if (requested.equals("json", ignoreCase = true)) {
                    null
                } else {
                    LineageExporters.byId(requested)
                        ?: return@post call.badRequest(
                            error = "unknown_format",
                            reason = "未注册的导出格式：$requested（可用：${LineageExporters.ids.joinToString("、")}）",
                        )
                }
            }
            // aggregate（opt-in 聚合）与非 json 的 format 导出互斥（导出器吃单模型）→ 400
            // 在建图**之前**短路（与 unknown_format / unknown_engine 同层）。
            if (query.aggregate && exporter != null) {
                return@post call.badRequest(
                    error = "aggregate_with_format",
                    reason = "aggregate=true 返回合并图 JSON，与非 json 的 format 导出互斥（format 与缺省同义时可用）",
                )
            }
            val engine = ServerPipeline.engineById(query.engine ?: ServerPipeline.DEFAULT_ENGINE)
                ?: return@post call.badRequest("unknown_engine", "未注册的引擎：${query.engine}")
            val schema = query.schema?.let {
                try {
                    ServerPipeline.schemaFromInlineDdl(it)
                } catch (e: Exception) {
                    return@post call.badRequest("invalid_schema", e.message ?: e.toString())
                }
            }
            when (val built = ServerPipeline.models(query.sql, engine, query.dialect, schema)) {
                // 整段语法解析失败：错误是数据（reason + span），200，不是 4xx。
                is Resolved.Unknown -> call.respond(UnknownReport(unknown = built))
                is Resolved.Known -> when {
                    // opt-in 聚合（2026-10-10 拍板）：不论语句数统一回合并图，
                    // 与 CLI `ozml lineage --format graph-json` 同形；0 条仍 422。
                    query.aggregate && built.value.isNotEmpty() ->
                        call.respond(GraphReportDto.from(LineageGraph.of(built.value)))
                    built.value.isEmpty() -> call.respondUnprocessableEntity(
                        error = "no_modelable_statement",
                        reason = "未从输入提取到可建模的语句（可能只含 DDL / MERGE 等不产出列级血缘的语句）",
                    )
                    built.value.size == 1 -> {
                        val model = built.value.single()
                        if (exporter == null) {
                            call.respond(model)
                        } else {
                            // 导出成功按注册表声明的 mime 回文本；错误路径（unknown / 4xx）
                            // 仍是 JSON，形状不随 format 变。
                            call.respondText(exporter.exporter.export(model), ContentType.parse(exporter.mime))
                        }
                    }
                    else -> call.respondUnprocessableEntity(
                        error = "multi_statement_input",
                        reason = "HTTP 层缺省只接受一条可建模语句（多语句用 aggregate=true 拿合并图）或用 CLI " +
                            "（ozml lineage -f <目录> --graph <db>）落库后查询",
                        statementCount = built.value.size,
                    )
                }
            }
        }

        post("/api/impact") {
            val query = call.receiveQuery<ImpactQuery>()
                ?: return@post call.badRequest("invalid_request", bodyShape("sql", "on"))
            val direction = parseDirection(query.direction, Direction.UPSTREAM)
                ?: return@post call.badRequest("invalid_direction", "direction 必须是 upstream / downstream / both")
            if ((query.depth ?: 0) < 0) return@post call.badRequest("invalid_depth", "depth 不能为负数")
            if (query.engine != null && ServerPipeline.engineById(query.engine) == null) {
                return@post call.badRequest("unknown_engine", "未注册的引擎：${query.engine}")
            }
            val graph = call.graphOf(query.engine) { engine, schema ->
                modelsToGraph(query.sql, engine, query.dialect, schema)
            } ?: return@post
            val origin = query.on.lowercase()
            if (origin !in graph.nodeIds) {
                call.respondUnprocessableEntity(
                    error = "column_not_found",
                    reason = "图里没有这一列：${query.on}（可用 /api/lineage 或 CLI graph-json 看全图列 id）",
                )
                return@post
            }
            val result = graph.impact(origin, direction, query.depth)
            call.respond(graph.impactReport(result))
        }

        post("/api/path") {
            val query = call.receiveQuery<PathQuery>()
                ?: return@post call.badRequest("invalid_request", bodyShape("sql", "from", "to"))
            val direction = parseDirection(query.direction, Direction.DOWNSTREAM)
                ?: return@post call.badRequest("invalid_direction", "direction 必须是 upstream / downstream / both")
            if (query.engine != null && ServerPipeline.engineById(query.engine) == null) {
                return@post call.badRequest("unknown_engine", "未注册的引擎：${query.engine}")
            }
            val graph = call.graphOf(query.engine) { engine, schema ->
                modelsToGraph(query.sql, engine, query.dialect, schema)
            } ?: return@post
            val from = query.from.lowercase()
            val to = query.to.lowercase()
            val missing = listOf(query.from, query.to).filter { it.lowercase() !in graph.nodeIds }
            if (missing.isNotEmpty()) {
                call.respondUnprocessableEntity(
                    error = "column_not_found",
                    reason = "图里没有这些列：${missing.joinToString(", ")}",
                )
                return@post
            }
            // 不可达是「查询成立、结果为空」：path=null 的 200，不是 4xx。
            val path = graph.shortestPath(from, to, direction)
            call.respond(
                PathReportDto(
                    from = query.from,
                    to = query.to,
                    direction = direction.name,
                    path = path?.map { PathReportDto.PathNodeDto(id = it, label = graph.label(it)) },
                ),
            )
        }

        // SPA 静态托管（仅 --web-root 给出时注册）：`/api` 路由优先，尾卡兜底只接非 /api。
        if (webRoot != null) {
            staticWeb(webRoot)
        }
    }
}

/**
 * `web/` dist 的静态托管 + SPA 回落（web 设计稿 §6.4）。
 *
 * - 匹配顺序在 `/api` 路由**之后**：Ktor 路由按特异性选路，显式 `/api` 段优先于尾卡，
 *   所以已知 `/api` 路径不会落到本兜底；但未知的 `/api/...` 仍会被尾卡接住——显式回 JSON 404，
 *   **不回 index.html**（前端按错误体分支，而不是拿到 HTML 解析炸）。
 * - 路径穿越防护：候选文件必须落在 [webRoot] 规范化后的目录树内。
 * - 未知非 `/api` 路径（SPA 前端路由）回落 `index.html`；index.html 缺失才 404。
 */
private fun Route.staticWeb(webRoot: java.nio.file.Path) {
    val root = webRoot.toAbsolutePath().normalize()
    get("{static...}") {
        if (call.request.path().startsWith("/api/")) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(error = "not_found", reason = "未知端点：${call.request.path()}"),
            )
            return@get
        }
        val rel = call.parameters.getAll("static")?.joinToString("/") ?: ""
        val candidate = root.resolve(rel).normalize()
        val file = candidate
            .takeIf { it.startsWith(root) && java.nio.file.Files.isRegularFile(it) }
            ?.toFile()
        when {
            file != null -> call.respondFile(file)
            root.resolve("index.html").toFile().isFile -> call.respondFile(root.resolve("index.html").toFile())
            else -> call.respond(HttpStatusCode.NotFound)
        }
    }
}

/** 请求体形状提示（同一模板，各端点只差必填字段名）。 */
private fun bodyShape(vararg fields: String): String =
    "请求体必须是包含 ${fields.joinToString("、")} 的 JSON（其余字段可选）"

/**
 * 解析 direction 字符串；未给用 [default]，给了但不认识返回 null（HTTP 层 400）。
 * 大小写不敏感（与 CLI 的 choice 提示一致的欺骗面最小化——这里明确列出可接受值）。
 */
internal fun parseDirection(raw: String?, default: Direction): Direction? =
    when (raw?.lowercase()) {
        null -> default
        "upstream" -> Direction.UPSTREAM
        "downstream" -> Direction.DOWNSTREAM
        "both" -> Direction.BOTH
        else -> null
    }

/**
 * 一段 SQL → 合并血缘图（现建现查，与 `ozml impact` 的 `-f` 行为一致）。
 * 多条可建模语句自然合并成一张图；0 条或整段语法错误抛
 * [NoModelableStatement]（由 [ApplicationCall.graphOf] 统一转成 422 / 200）。
 */
internal fun modelsToGraph(
    sql: String,
    engine: SqlEngine,
    dialect: String?,
    schema: SchemaProvider?,
): LineageGraph {
    val built = ServerPipeline.models(sql, engine, dialect, schema)
    val models: List<LineageModel> = when (built) {
        // 语法错误：同样当数据返回 200 unknown（与 /api/lineage 的取舍一致）。
        is Resolved.Unknown -> throw SemanticUnavailable(built.reason)
        is Resolved.Known -> {
            if (built.value.isEmpty()) throw NoModelableStatement() else built.value
        }
    }
    return LineageGraph.of(models)
}

/** SQL 全部语法不可解析（整段失败）——数据化的失败：200 + unknown。 */
internal class SemanticUnavailable(public val reason: String) : Exception(reason)

/** SQL 可解析但没有任何可建模语句——请求无法满足：422。 */
internal class NoModelableStatement : Exception()

/**
 * 建图 + 统一错误翻译：建图失败（语法错误 / 无可建模语句）时直接写错误响应并返回
 * null，调用方拿到 null 就 `return@post`。
 */
private suspend fun ApplicationCall.graphOf(
    engineId: String?,
    build: (engine: SqlEngine, schema: SchemaProvider?) -> LineageGraph,
): LineageGraph? {
    val engine = ServerPipeline.engineById(engineId ?: ServerPipeline.DEFAULT_ENGINE)!!
    return try {
        build(engine, null)
    } catch (e: SemanticUnavailable) {
        respond(UnknownReport(unknown = Resolved.Unknown(reason = e.reason)))
        null
    } catch (e: NoModelableStatement) {
        respondUnprocessableEntity(
            error = "no_modelable_statement",
            reason = "未从输入提取到可建模的语句（可能只含 DDL / MERGE 等不产出列级血缘的语句）",
        )
        null
    }
}

/** 请求体反序列化：坏 JSON / 缺必填字段 → null（调用方据此给 `invalid_request`）。 */
private suspend inline fun <reified T : Any> ApplicationCall.receiveQuery(): T? =
    try {
        receive<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/** 400：请求本身非法（缺字段 / 未知引擎 / 坏 DDL / 非法枚举值）。 */
private suspend fun ApplicationCall.badRequest(
    error: String,
    reason: String? = null,
    statementCount: Int? = null,
): Unit = respond(HttpStatusCode.BadRequest, ErrorResponse(error, reason, statementCount))

/** 422：请求合法但无法满足（0 / 多条语句、目标列不在图里）。 */
private suspend fun ApplicationCall.respondUnprocessableEntity(
    error: String,
    reason: String? = null,
    statementCount: Int? = null,
): Unit = respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(error, reason, statementCount))

/** `ImpactResult` → 与 `ozml impact --format json` 同形的响应体。 */
private fun LineageGraph.impactReport(result: ImpactResult): ImpactReportDto = ImpactReportDto(
    origin = result.origin,
    direction = result.direction.name,
    depth = result.depth,
    truncated = result.truncated,
    nodes = result.nodes.map {
        ImpactReportDto.ImpactNodeDto(
            id = it.id,
            label = label(it.id),
            distance = it.distance,
            isOutput = it.isOutput,
        )
    },
)
