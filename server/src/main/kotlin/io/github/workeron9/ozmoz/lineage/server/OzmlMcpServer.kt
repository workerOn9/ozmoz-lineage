package io.github.workeron9.ozmoz.lineage.server

import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.RenderRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.calcite.CalciteEngine
import io.github.workeron9.ozmoz.lineage.graph.Direction
import io.github.workeron9.ozmoz.lineage.graph.ImpactResult
import io.github.workeron9.ozmoz.lineage.graph.LineageGraph
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Severity
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.encodeToString

/**
 * `ozml mcp` —— MCP（Model Context Protocol）stdio 服务端：把 CLI / `/api` 既有
 * 能力以**工具（tool）**形态暴露给 AI 助手（Claude Desktop / Cursor 等 MCP host）。
 *
 * 设计约定（与 [`Routes`] 的 HTTP 取舍同一取向）：
 * - **工具面镜像既有契约**，不发明新形状：每个工具的成功响应都与对应 CLI JSON /
 *   `/api` 响应**同形**（同一批 `@Serializable` DTO），`ir` 模型仍是唯一权威契约。
 * - **解析失败不是工具错误**：语法错误 / unknown / 不可达路径是**数据**
 *   （diagnostics / `{unknown: {...}}` / `path = null`），`isError = false`；
 *   仅**请求本身非法**（缺参数 / 未知引擎 / 坏 DDL / 非法枚举）与**请求合法但无法满足**
 *   （0 或 多条可建模语句 / 目标列不在图里）才 `isError = true`，内容为
 *   [ErrorResponse] JSON（HTTP 4xx / 422 的镜像）。
 * - **v1 只做 stdio + sql 现建现查**：`ozml serve` 已经覆盖 HTTP，图库（`graph` 字段）
 *   与导出器（`format` 字段）有意不进工具面，留待真实使用反馈。
 * - **离线红线**：stdio 传输不过网络；工具不读文件系统（SQL 一律走参数文本）。
 *
 * 传输纪律：stdio 的 stdout 是**协议信道**——启动后任何东西都不许写 stdout
 * （`ozml mcp` 只把就绪提示写到 stderr）。
 */
public object OzmlMcpServer {

    /** MCP 服务端点信息的名字（与仓库 / CLI 名一致）。 */
    private const val SERVER_NAME: String = "ozml-mcp"

    private val mapper = McpJsonDefaults.getMapper()

    /**
     * 构建并启动 stdio MCP 服务端，返回关闭句柄（测试用；CLI 用 [blockUntilShutdown]）。
     *
     * 默认绑进程的真实 [System.in] / [System.out]；测试传管道流做进程内真实协议交换。
     * 传输在 build 时即开始读入侧——本函数不阻塞。
     */
    public fun start(
        input: InputStream = System.`in`,
        output: OutputStream = System.out,
    ): AutoCloseable {
        val transport = StdioServerTransportProvider(mapper, input, output)
        val server = McpServer.sync(transport)
            .serverInfo(SERVER_NAME, OzmlLineageServer.version)
            .instructions(
                "ozmoz-lineage（ozml）工具面：SQL 解析 / 列级血缘 / 影响面 / 路径 / " +
                    "schema 语义校验 / SQL 排版 / 方言转换。错误语义：isError=true 且内容为 " +
                    "{error, reason} 表示请求非法或无法满足；isError=false 时语法错误、" +
                    "unknown、不可达路径都是数据（diagnostics / unknown / null），不是失败。",
            )
            .tools(
                tool(TOOLS.ENGINES, ::handleEngines),
                tool(TOOLS.PARSE, ::handleParse),
                tool(TOOLS.LINEAGE, ::handleLineage),
                tool(TOOLS.IMPACT, ::handleImpact),
                tool(TOOLS.PATH, ::handlePath),
                tool(TOOLS.VALIDATE, ::handleValidate),
                tool(TOOLS.FORMAT, ::handleFormat),
                tool(TOOLS.CONVERT, ::handleConvert),
            )
            .build()
        return AutoCloseable { server.closeGracefully() }
    }

    /**
     * 阻塞当前线程直到进程退出。
     *
     * 客户端断开（stdin EOF）时 SDK 会关掉会话与传输线程，但**不会**结束调用线程——
     * MCP host 退出时本来就会杀掉子进程，所以这里只 join，不额外装退出钩子
     * （与 MCP stdio server 的通行做法一致；Ctrl+C 由 JVM 信号处理终止）。
     */
    public fun blockUntilShutdown(): Unit = Thread.currentThread().join()

    // ———————————————————— 工具名与入参 schema ————————————————————

    /** 工具名与入参 JSON Schema（host 用来做调用面展示与入参校验）。 */
    private object TOOLS {
        const val ENGINES = "ozml_engines"
        const val PARSE = "ozml_parse"
        const val LINEAGE = "ozml_lineage"
        const val IMPACT = "ozml_impact"
        const val PATH = "ozml_path"
        const val VALIDATE = "ozml_validate"
        const val FORMAT = "ozml_format"
        const val CONVERT = "ozml_convert"

        const val SQL = """"sql": {"type": "string", "description": "SQL 文本（单条或多条语句）"}"""
        const val ENGINE = """"engine": {"type": "string", "description": "引擎 id：jsqlparser（默认）/ calcite / jooq"}"""
        const val DIALECT = """"dialect": {"type": "string", "description": "方言名（决定解析 / 渲染的 quoting 配置）"}"""
        const val SCHEMA = """"schema": {"type": "string", "description": "内联 DDL 文本（CREATE TABLE），权威元数据；解析失败视为请求非法"}"""
        const val AGGREGATE =
            """"aggregate": {"type": "boolean", "description": "true 时多语句合并为一张图返回（缺省 false：恰好多条可建模语句视为无法满足）"}"""

        val ENGINES_SCHEMA = """{"type": "object", "properties": {}}"""

        val PARSE_SCHEMA = """{"type": "object", "properties": {$SQL, $ENGINE, $DIALECT}, "required": ["sql"]}"""

        val LINEAGE_SCHEMA =
            """{"type": "object", "properties": {$SQL, $ENGINE, $DIALECT, $SCHEMA, $AGGREGATE}, "required": ["sql"]}"""

        val IMPACT_SCHEMA = """{
            "type": "object",
            "properties": {
                $SQL, $ENGINE, $DIALECT, $SCHEMA,
                "on": {"type": "string", "description": "目标列 id（折叠后，如 store_sales.ss_quantity）"},
                "direction": {"type": "string", "enum": ["upstream", "downstream", "both"], "description": "遍历方向（默认 upstream）"},
                "depth": {"type": "integer", "description": "最大层数；缺省不限"}
            },
            "required": ["sql", "on"]
        }"""

        val PATH_SCHEMA = """{
            "type": "object",
            "properties": {
                $SQL, $ENGINE, $DIALECT, $SCHEMA,
                "from": {"type": "string", "description": "起点列 id"},
                "to": {"type": "string", "description": "终点列 id"},
                "direction": {"type": "string", "enum": ["upstream", "downstream", "both"], "description": "遍历方向（默认 downstream）"}
            },
            "required": ["sql", "from", "to"]
        }"""

        val VALIDATE_SCHEMA =
            """{"type": "object", "properties": {$SQL, $ENGINE, $DIALECT, $SCHEMA}, "required": ["sql"]}"""

        val FORMAT_SCHEMA = """{
            "type": "object",
            "properties": {
                $SQL, $ENGINE, $DIALECT,
                "multiline": {"type": "boolean", "description": "多行排版（默认 true）；false 产规范化单行"}
            },
            "required": ["sql"]
        }"""

        val CONVERT_SCHEMA = """{
            "type": "object",
            "properties": {
                $SQL, $ENGINE, $DIALECT,
                "from": {"type": "string", "description": "源方言（缺省按引擎默认方言解析）"},
                "to": {"type": "string", "description": "目标方言（如 mysql / postgresql / hive / trino / mssql）"}
            },
            "required": ["sql", "to"]
        }"""
    }

    /** 工具元数据：描述 + 入参 schema（schema 串由 SDK 的 Jackson 解析成 Map）。 */
    private data class ToolMeta(val description: String, val schemaJson: String)

    private fun toolMeta(name: String): ToolMeta = when (name) {
        TOOLS.ENGINES -> ToolMeta(
            "列出已注册的 SQL 引擎及其能力（features / 方言 / 不支持的原因）。响应与 /api/engines 同形。",
            TOOLS.ENGINES_SCHEMA,
        )
        TOOLS.PARSE -> ToolMeta(
            "把 SQL 解析成归一化树 + 表引用 + 诊断（语法错误进 diagnostics，不是失败）。响应与 /api/parse 同形。",
            TOOLS.PARSE_SCHEMA,
        )
        TOOLS.LINEAGE -> ToolMeta(
            "构建列级血缘模型（六类边 + TransformKind）。单语句返回 LineageModel；aggregate=true 时多语句合并成一张图返回。",
            TOOLS.LINEAGE_SCHEMA,
        )
        TOOLS.IMPACT -> ToolMeta(
            "在 SQL 现建的血缘图上做影响面分析（上溯 / 下溯 / 双向，可限深度）。目标列不在图里是 isError。",
            TOOLS.IMPACT_SCHEMA,
        )
        TOOLS.PATH -> ToolMeta(
            "在 SQL 现建的血缘图上求两列间的最短路径；不可达是数据（path=null），不是失败。",
            TOOLS.PATH_SCHEMA,
        )
        TOOLS.VALIDATE -> ToolMeta(
            "结合 schema（内联 DDL 或从 SQL 顺手收集）做语义校验：未知表 / 未知列 / 列歧义 / 类型不匹配。响应与 /api/validate 同形。",
            TOOLS.VALIDATE_SCHEMA,
        )
        TOOLS.FORMAT -> ToolMeta(
            "SQL 排版（引擎规范化渲染 + 子句换行；注释丢失会如实落在 diagnostics）。响应与 /api/format 同形。",
            TOOLS.FORMAT_SCHEMA,
        )
        TOOLS.CONVERT -> ToolMeta(
            "方言转换（默认 calcite，输出前做 parse→render→re-parse 等价校验，不等价不产出 SQL）。响应与 ozml convert --format json 同形。",
            TOOLS.CONVERT_SCHEMA,
        )
        else -> error("未登记的工具：$name")
    }

    /** 登记一个工具：名字 + 元数据 + 同步处理器。 */
    private fun tool(name: String, handler: (McpSchema.CallToolRequest) -> McpSchema.CallToolResult) =
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(
                toolMeta(name).let { meta ->
                    McpSchema.Tool.builder(name, mapper, meta.schemaJson).description(meta.description).build()
                },
            )
            .callHandler { _, request -> handler(request) }
            .build()

    // ———————————————————— 结果构造 ————————————————————

    /** 成功 / 数据型失败（isError=false）：一段 JSON 文本。 */
    private fun ok(json: String): McpSchema.CallToolResult = McpSchema.CallToolResult.builder()
        .content(listOf(McpSchema.TextContent.builder(json).build()))
        .isError(false)
        .build()

    /** 请求非法 / 无法满足（isError=true）：ErrorResponse JSON（HTTP 4xx / 422 的镜像）。 */
    private fun fail(error: String, reason: String, statementCount: Int? = null): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder()
            .content(
                listOf(
                    McpSchema.TextContent.builder(
                        ServerJson.json.encodeToString(ErrorResponse(error = error, reason = reason, statementCount = statementCount)),
                    ).build(),
                ),
            )
            .isError(true)
            .build()

    // ———————————————————— 入参读取 ————————————————————

    private fun args(request: McpSchema.CallToolRequest): Map<String, Any?> =
        request.arguments() ?: emptyMap()

    private fun requiredSql(a: Map<String, Any?>): String? =
        (a["sql"] as? String)?.takeIf { it.isNotBlank() }

    private fun strArg(a: Map<String, Any?>, name: String): String? = a[name] as? String

    private fun boolArg(a: Map<String, Any?>, name: String, default: Boolean): Boolean =
        (a[name] as? Boolean) ?: default

    private fun intArg(a: Map<String, Any?>, name: String): Int? = (a[name] as? Number)?.toInt()

    /**
     * 引擎解析：未注册的 id 返回 null（调用方产出 `unknown_engine` 失败结果，与
     * HTTP 400 同语义）。[defaultId] 缺省引擎与 CLI 各命令一致（parse / lineage /
     * impact / path 是 jsqlparser；validate / convert 是 calcite）。
     */
    private fun engineOrFail(a: Map<String, Any?>, defaultId: String): SqlEngine? =
        ServerPipeline.engineById(strArg(a, "engine") ?: defaultId)

    // ———————————————————— 各工具实现（语义镜像 Routes.kt，sql-only 子集） ————————————————————

    private fun handleEngines(request: McpSchema.CallToolRequest): McpSchema.CallToolResult {
        val reports = ServerPipeline.allEngines().map { engine ->
            EngineReport(
                id = engine.id,
                features = Feature.entries.filter { engine.capabilities.supports(it) }.map { it.name },
                dialects = engine.capabilities.dialects.toList(),
                reasons = engine.capabilities.reasons.mapKeys { (feature, _) -> feature.name },
            )
        }
        return ok(ServerJson.json.encodeToString(ServerJson.listOf(EngineReport.serializer()), reports))
    }

    private fun handleParse(request: McpSchema.CallToolRequest): McpSchema.CallToolResult {
        val a = args(request)
        val sql = requiredSql(a) ?: return fail("invalid_request", "缺少非空 sql 参数")
        val engine = engineOrFail(a, ServerPipeline.DEFAULT_ENGINE)
            ?: return fail("unknown_engine", "未注册的引擎：${strArg(a, "engine")}")
        val outcome = ServerPipeline.parse(sql, engine, strArg(a, "dialect"))
        return ok(ServerJson.json.encodeToString(ParseReportDto(outcome.root, outcome.tables, outcome.diagnostics)))
    }

    private fun handleLineage(request: McpSchema.CallToolRequest): McpSchema.CallToolResult {
        val a = args(request)
        val sql = requiredSql(a) ?: return fail("invalid_request", "缺少非空 sql 参数")
        val engine = engineOrFail(a, ServerPipeline.DEFAULT_ENGINE)
            ?: return fail("unknown_engine", "未注册的引擎：${strArg(a, "engine")}")
        val schema = when (val s = inlineSchema(a)) {
            is SchemaOutcome.Ok -> s.provider
            is SchemaOutcome.Invalid -> return fail("invalid_schema", "内联 DDL 解析失败：${s.message}")
        }
        return when (val built = ServerPipeline.models(sql, engine, strArg(a, "dialect"), schema)) {
            // 整段语法解析失败：错误是数据（reason + span），isError=false（与 /api/lineage 同取舍）。
            is Resolved.Unknown -> ok(ServerJson.json.encodeToString(UnknownReport(unknown = built)))
            is Resolved.Known -> when {
                // opt-in 聚合（与 /api/lineage 的 aggregate=true 同义）：多语句合并图。
                boolArg(a, "aggregate", false) && built.value.isNotEmpty() ->
                    ok(ServerJson.json.encodeToString(GraphReportDto.from(LineageGraph.of(built.value))))
                built.value.isEmpty() ->
                    fail("no_modelable_statement", "未从输入提取到可建模的语句（可能只含 DDL / MERGE 等不产出列级血缘的语句）")
                built.value.size == 1 -> ok(ServerJson.json.encodeToString(built.value.single()))
                else -> fail(
                    "multi_statement_input",
                    "输入含多条可建模语句：传 aggregate=true 拿合并图，或拆开逐条调用",
                    built.value.size,
                )
            }
        }
    }

    private fun handleImpact(request: McpSchema.CallToolRequest): McpSchema.CallToolResult {
        val a = args(request)
        val sql = requiredSql(a) ?: return fail("invalid_request", "缺少非空 sql 参数")
        val on = strArg(a, "on") ?: return fail("invalid_request", "缺少 on 参数（目标列 id）")
        val direction = parseDirection(strArg(a, "direction"), Direction.UPSTREAM)
            ?: return fail("invalid_direction", "direction 必须是 upstream / downstream / both")
        val depth = intArg(a, "depth")
        if (depth != null && depth < 0) return fail("invalid_depth", "depth 不能为负数")
        val engine = engineOrFail(a, ServerPipeline.DEFAULT_ENGINE)
            ?: return fail("unknown_engine", "未注册的引擎：${strArg(a, "engine")}")
        val schema = when (val s = inlineSchema(a)) {
            is SchemaOutcome.Ok -> s.provider
            is SchemaOutcome.Invalid -> return fail("invalid_schema", "内联 DDL 解析失败：${s.message}")
        }
        return when (val graph = graphOrFail(sql, engine, strArg(a, "dialect"), schema)) {
            is GraphOutcome.Failed -> graph.result
            is GraphOutcome.Built -> {
                val origin = on.lowercase()
                if (origin !in graph.value.nodeIds) {
                    fail("column_not_found", "图里没有这一列：$on（用 ozml_lineage 看全图列 id）")
                } else {
                    val result = graph.value.impact(origin, direction, depth)
                    ok(ServerJson.json.encodeToString(impactReport(graph.value, result)))
                }
            }
        }
    }

    private fun handlePath(request: McpSchema.CallToolRequest): McpSchema.CallToolResult {
        val a = args(request)
        val sql = requiredSql(a) ?: return fail("invalid_request", "缺少非空 sql 参数")
        val from = strArg(a, "from") ?: return fail("invalid_request", "缺少 from 参数（起点列 id）")
        val to = strArg(a, "to") ?: return fail("invalid_request", "缺少 to 参数（终点列 id）")
        val direction = parseDirection(strArg(a, "direction"), Direction.DOWNSTREAM)
            ?: return fail("invalid_direction", "direction 必须是 upstream / downstream / both")
        val engine = engineOrFail(a, ServerPipeline.DEFAULT_ENGINE)
            ?: return fail("unknown_engine", "未注册的引擎：${strArg(a, "engine")}")
        val schema = when (val s = inlineSchema(a)) {
            is SchemaOutcome.Ok -> s.provider
            is SchemaOutcome.Invalid -> return fail("invalid_schema", "内联 DDL 解析失败：${s.message}")
        }
        return when (val graph = graphOrFail(sql, engine, strArg(a, "dialect"), schema)) {
            is GraphOutcome.Failed -> graph.result
            is GraphOutcome.Built -> {
                val missing = listOf(from, to).filter { it.lowercase() !in graph.value.nodeIds }
                if (missing.isNotEmpty()) {
                    fail("column_not_found", "图里没有这些列：${missing.joinToString(", ")}")
                } else {
                    // 不可达是「查询成立、结果为空」：path=null 的数据，不是失败。
                    val path = graph.value.shortestPath(from.lowercase(), to.lowercase(), direction)
                    ok(
                        ServerJson.json.encodeToString(
                            PathReportDto(
                                from = from,
                                to = to,
                                direction = direction.name,
                                path = path?.map { PathReportDto.PathNodeDto(id = it, label = graph.value.label(it)) },
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun handleValidate(request: McpSchema.CallToolRequest): McpSchema.CallToolResult {
        val a = args(request)
        val sql = requiredSql(a) ?: return fail("invalid_request", "缺少非空 sql 参数")
        val engine = engineOrFail(a, ValidateReportDto.DEFAULT_ENGINE)
            ?: return fail("unknown_engine", "未注册的引擎：${strArg(a, "engine")}")
        // 能力表路由：引擎没申报 VALIDATE_SCHEMA 直接给可解释的失败（不靠异常碰运气）。
        engine.capabilities.reason(Feature.VALIDATE_SCHEMA)?.let { reason ->
            return fail("unsupported_capability", "引擎 ${engine.id} 不支持 schema 校验：$reason")
        }
        val schema = when (val s = inlineSchema(a)) {
            is SchemaOutcome.Ok -> s.provider
            is SchemaOutcome.Invalid -> return fail("invalid_schema", "内联 DDL 解析失败：${s.message}")
        }
        // 形状恒定：跑成（Known）与没跑成（Unknown）都回 ValidateReportDto；
        // 后者落一条 validate.skipped ERROR（validate 的产物本身就是诊断流）。
        val diagnostics = when (val result = ServerPipeline.validate(sql, engine, strArg(a, "dialect"), schema)) {
            is Resolved.Known -> result.value
            is Resolved.Unknown -> listOf(
                Diagnostic(
                    severity = Severity.ERROR,
                    code = ValidateReportDto.CODE_VALIDATE_SKIPPED,
                    message = result.reason,
                    span = result.span,
                    engineId = engine.id,
                ),
            )
        }
        return ok(
            ServerJson.json.encodeToString(
                ValidateReportDto(engine = engine.id, dialect = strArg(a, "dialect"), diagnostics = diagnostics),
            ),
        )
    }

    private fun handleFormat(request: McpSchema.CallToolRequest): McpSchema.CallToolResult {
        val a = args(request)
        val sql = requiredSql(a) ?: return fail("invalid_request", "缺少非空 sql 参数")
        val engine = engineOrFail(a, FormatReportDto.DEFAULT_ENGINE)
            ?: return fail("unknown_engine", "未注册的引擎：${strArg(a, "engine")}")
        // 能力表路由：引擎没申报 PRETTY_PRINT 直接给可解释的失败。
        engine.capabilities.reason(Feature.PRETTY_PRINT)?.let { reason ->
            return fail("unsupported_capability", "引擎 ${engine.id} 不支持排版：$reason")
        }
        val multiline = boolArg(a, "multiline", true)
        // 形状恒定：跑成（Known）与没跑成（Unknown）都回 FormatReportDto；后者 sql=null。
        val report = when (val result = ServerPipeline.format(sql, engine, strArg(a, "dialect"), multiline)) {
            is Resolved.Known -> FormatReportDto(
                engine = engine.id,
                dialect = strArg(a, "dialect"),
                sql = result.value.sql,
                diagnostics = result.value.diagnostics,
            )
            is Resolved.Unknown -> FormatReportDto(
                engine = engine.id,
                dialect = strArg(a, "dialect"),
                sql = null,
                diagnostics = listOf(
                    Diagnostic(
                        severity = Severity.ERROR,
                        code = FormatReportDto.CODE_FORMAT_SKIPPED,
                        message = result.reason,
                        span = result.span,
                        engineId = engine.id,
                    ),
                ),
            )
        }
        return ok(ServerJson.json.encodeToString(report))
    }

    private fun handleConvert(request: McpSchema.CallToolRequest): McpSchema.CallToolResult {
        val a = args(request)
        val sql = requiredSql(a) ?: return fail("invalid_request", "缺少非空 sql 参数")
        val toDialect = strArg(a, "to") ?: return fail("invalid_request", "缺少 to 参数（目标方言）")
        // 转换只有 calcite / jooq 两个渲染引擎；默认 calcite（与 CLI 同缺省）。
        val engine = engineOrFail(a, CalciteEngine.ID)
            ?: return fail("unknown_engine", "未注册的引擎：${strArg(a, "engine")}")
        engine.capabilities.reason(Feature.DIALECT_RENDER)?.let { reason ->
            return fail("unsupported_capability", "引擎 ${engine.id} 不支持方言转换：$reason")
        }
        if (!dialectKnown(engine, toDialect)) {
            return fail(
                "invalid_request",
                "引擎 ${engine.id} 不认目标方言 $toDialect" +
                    "（合法值：${engine.capabilities.dialects.sorted().joinToString(", ")}）",
            )
        }
        // render-verified 门禁（ADR-0004）：不等价就不产出 SQL，如实落诊断。
        val report = when (
            val result = engine.render(
                RenderRequest(sql = sql, fromDialect = strArg(a, "from"), toDialect = toDialect),
            )
        ) {
            is Resolved.Known -> ConvertReportDto(
                engine = engine.id,
                fromDialect = strArg(a, "from"),
                toDialect = toDialect,
                sql = result.value,
            )
            is Resolved.Unknown -> ConvertReportDto(
                engine = engine.id,
                fromDialect = strArg(a, "from"),
                toDialect = toDialect,
                sql = null,
                diagnostics = listOf(
                    Diagnostic(
                        severity = Severity.ERROR,
                        code = ConvertReportDto.CODE_RENDER_UNVERIFIED,
                        message = result.reason,
                        span = result.span,
                        engineId = engine.id,
                    ),
                ),
            )
        }
        return ok(ServerJson.json.encodeToString(report))
    }

    // ———————————————————— 共用辅助 ————————————————————

    /** 内联 DDL schema 的解析结果：未给（Ok(null)）/ 给了且成功 / 给了但解析失败。 */
    private sealed interface SchemaOutcome {
        val provider: SchemaProvider?

        data class Ok(override val provider: SchemaProvider?) : SchemaOutcome

        data class Invalid(val message: String) : SchemaOutcome {
            override val provider: SchemaProvider? get() = null
        }
    }

    private fun inlineSchema(a: Map<String, Any?>): SchemaOutcome {
        val text = strArg(a, "schema") ?: return SchemaOutcome.Ok(null)
        return try {
            SchemaOutcome.Ok(ServerPipeline.schemaFromInlineDdl(text))
        } catch (e: Exception) {
            SchemaOutcome.Invalid(e.message ?: e.toString())
        }
    }

    /** 建图结果：图建起来了，或已经把失败结果写好了（调用方直接透传）。 */
    private sealed interface GraphOutcome {
        val value: LineageGraph

        data class Built(override val value: LineageGraph) : GraphOutcome

        data class Failed(val result: McpSchema.CallToolResult) : GraphOutcome {
            override val value: LineageGraph get() = error("失败结果没有图")
        }
    }

    /**
     * SQL 现建图（与 [modelsToGraph] 同口径）：整段语法错误是数据（unknown 响应），
     * 没有任何可建模语句是无法满足——两种失败都已在 [GraphOutcome.Failed] 里写好结果。
     */
    private fun graphOrFail(
        sql: String,
        engine: SqlEngine,
        dialect: String?,
        schema: SchemaProvider?,
    ): GraphOutcome = try {
        GraphOutcome.Built(modelsToGraph(sql, engine, dialect, schema))
    } catch (e: SemanticUnavailable) {
        GraphOutcome.Failed(ok(ServerJson.json.encodeToString(UnknownReport(unknown = Resolved.Unknown(reason = e.reason)))))
    } catch (e: NoModelableStatement) {
        GraphOutcome.Failed(
            fail(
                "no_modelable_statement",
                "未从输入提取到可建模的语句（可能只含 DDL / MERGE 等不产出列级血缘的语句）",
            ),
        )
    }

    /** 与 CLI `ConvertCommand.toDialectValid` 同口径的方言归一化比对。 */
    private fun dialectKnown(engine: SqlEngine, toDialect: String): Boolean {
        val normalized = toDialect.trim().lowercase().replace('-', '_')
        return engine.capabilities.dialects.any { it.trim().lowercase().replace('-', '_') == normalized }
    }

    /** ImpactResult → 与 `ozml impact --format json` / ImpactReportDto 同形。 */
    private fun impactReport(graph: LineageGraph, result: ImpactResult): ImpactReportDto = ImpactReportDto(
        origin = result.origin,
        direction = result.direction.name,
        depth = result.depth,
        truncated = result.truncated,
        nodes = result.nodes.map {
            ImpactReportDto.ImpactNodeDto(
                id = it.id,
                label = graph.label(it.id),
                distance = it.distance,
                isOutput = it.isOutput,
            )
        },
    )
}
