package io.github.workeron9.ozmoz.lineage.server

import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import kotlinx.serialization.Serializable

/**
 * `server` 的 HTTP 契约（[`02-架构/web子项目设计.md` §4] 的落点，知识库私有）。
 *
 * 两条铁律：
 * - `ir` 的 `@Serializable` 模型是**唯一权威契约**——本文件只做**查询镜像与包装**，
 *   不新建语义重复的 DTO；响应里能直接用 `ir` 模型的地方就直接用
 *   （[ParseReportDto] / [LineageQuery] 的响应体）。
 * - `graph` 模块的模型（`ImpactResult` 等）按 `ozml impact --format json` 的
 *   **同一形状**做镜像（与 CLI 的 `ImpactReport` 同约定），HTTP 层不发明新形状。
 *
 * 状态码约定（web 设计稿 §4.1）：
 * - **解析失败不是 HTTP 错误**：`/api/parse` / `/api/lineage` 的语法错误是**数据**
 *   （诊断 / `unknown`），一律 200；仅请求本身非法（缺 `sql`、未知引擎 id、
 *   内联 DDL 解析失败）才 4xx。
 * - 请求合法但**无法满足**（0 条 / 多条可建模语句、目标列不在图里）→ 422。
 */
@Serializable
public data class HealthReport(
    val status: String,
    val version: String,
)

@Serializable
public data class EngineReport(
    val id: String,
    val features: List<String>,
    val dialects: List<String>,
    /** 能力名 → 不支持原因（[Feature] 名）。 */
    val reasons: Map<String, String>,
)

/** `POST /api/parse` 请求。 */
@Serializable
public data class ParseQuery(
    val sql: String,
    /** 引擎 id（默认 `jsqlparser`）；未注册值 → 400。 */
    val engine: String? = null,
    /** 方言名（仅记录，jsqlparser 无方言概念）。 */
    val dialect: String? = null,
)

/**
 * `POST /api/parse` 响应——与 `ozml parse --format json` **同形**
 * （{root, tables, diagnostics}），HTTP 层不发明第二个形状。
 */
@Serializable
public data class ParseReportDto(
    val root: AstNode,
    val tables: List<TableRef>,
    val diagnostics: List<Diagnostic>,
)

/** `POST /api/lineage` 请求。 */
@Serializable
public data class LineageQuery(
    val sql: String,
    val engine: String? = null,
    val dialect: String? = null,
    /** 内联 DDL 文本（CREATE TABLE）；给出时**优先**（权威元数据，解析失败 → 400）。 */
    val schema: String? = null,
    /**
     * 导出格式：`format` 模块注册表 id（与 CLI `--format` 同名：mermaid / dot /
     * cypher / openlineage / ozmoz-json）；null 或 "json" → 默认的 [io.github.workeron9.ozmoz.lineage.ir.LineageModel]
     * JSON 响应。未注册 id → 400（请求本身非法，与 `unknown_engine` 同语义）。
     * 不用 `Accept` 协商：openlineage / ozmoz-json / 默认 json 的 mime 都是
     * application/json，按 mime 区分有歧义；导出成功走注册表声明的 mime。
     */
    val format: String? = null,
    /**
     * **opt-in 聚合模式**（2026-10-10 拍板，终结「多语句 422 待议」）：
     * true 时不论语句数，统一返回**合并图**响应（[GraphReportDto]，与 CLI
     * `ozml lineage --format graph-json` 同形）；缺省 false 保持既有单语句契约
     * （多条可建模语句 → 422 `multi_statement_input`），老客户端零影响。
     * 与非 json 的 [format] 互斥 → 400 `aggregate_with_format`（导出器吃单模型）。
     */
    val aggregate: Boolean = false,
)

/**
 * `POST /api/lineage` 在**整段语法解析失败**时的 200 响应——错误是数据
 * （[Resolved.Unknown] 的 reason + span），不是 4xx。
 */
@Serializable
public data class UnknownReport(
    val unknown: Resolved.Unknown,
)

/**
 * `POST /api/lineage` 在 [LineageQuery.aggregate]（opt-in 聚合模式）下的响应——
 * 与 CLI `ozml lineage --format graph-json` **同一形状**（{nodes, edges}），
 * 多语句合并为一张图（[io.github.workeron9.ozmoz.lineage.graph.LineageGraph.of]）。
 */
@Serializable
public data class GraphReportDto(
    val nodes: List<GraphNodeDto>,
    val edges: List<GraphEdgeDto>,
) {
    @Serializable
    public data class GraphNodeDto(
        val id: String,
        val label: String,
        val isOutput: Boolean,
    )

    @Serializable
    public data class GraphEdgeDto(
        val id: String,
        val from: String,
        val to: String,
        val kind: String,
        val transform: String,
    )

    public companion object {
        @JvmStatic
        public fun from(graph: io.github.workeron9.ozmoz.lineage.graph.LineageGraph): GraphReportDto = GraphReportDto(
            // nodeIds 保持首次出现序（确定性）；边取 LineageGraph.edges（已稳定排序）。
            nodes = graph.nodeIds.map {
                GraphNodeDto(id = it, label = graph.label(it), isOutput = graph.isOutput(it))
            },
            edges = graph.edges.map { edge ->
                GraphEdgeDto(
                    id = edge.id,
                    from = edge.from,
                    to = edge.to,
                    kind = edge.kind.name,
                    transform = edge.transform.name,
                )
            },
        )
    }
}

/**
 * `POST /api/impact` 的 JSON 形状——与 `ozml impact --format json` **同形**。
 * 请求侧用 [ImpactQuery]（sql 现建图 + 查询参数），响应侧沿用 ImpactReport。
 */
@Serializable
public data class ImpactQuery(
    val sql: String,
    val engine: String? = null,
    val dialect: String? = null,
    val schema: String? = null,
    /** 目标列 id（折叠后，如 `store_sales.ss_quantity`）；不在图里 → 422。 */
    val on: String,
    /** `upstream`（默认）/ `downstream` / `both`；其余值 → 400。 */
    val direction: String? = null,
    /** 最大层数；null 不限。负数 → 400。 */
    val depth: Int? = null,
)

@Serializable
public data class ImpactReportDto(
    val origin: String,
    val direction: String,
    val depth: Int? = null,
    val truncated: Boolean,
    val nodes: List<ImpactNodeDto>,
) {
    @Serializable
    public data class ImpactNodeDto(
        val id: String,
        val label: String,
        val distance: Int,
        val isOutput: Boolean,
    )
}

/** `POST /api/path` 请求。 */
@Serializable
public data class PathQuery(
    val sql: String,
    val engine: String? = null,
    val dialect: String? = null,
    val schema: String? = null,
    /** 起点列 id；不在图里 → 422。 */
    val from: String,
    /** 终点列 id；不在图里 → 422。 */
    val to: String,
    /** `upstream` / `downstream`（默认）/ `both`；其余值 → 400。 */
    val direction: String? = null,
)

/**
 * `POST /api/path` 响应。[path] 为 null 表示**不可达**（查询成立、结果为空，
 * 是数据不是错误，给 200）；`from`/`to` 不在图里才是 422。
 */
@Serializable
public data class PathReportDto(
    val from: String,
    val to: String,
    val direction: String,
    val path: List<PathNodeDto>?,
) {
    @Serializable
    public data class PathNodeDto(
        val id: String,
        val label: String,
    )
}

/** 4xx / 422 的统一错误体。 */
@Serializable
public data class ErrorResponse(
    /** 机器可判的短码：`invalid_request` / `unknown_engine` / `unknown_format` / `invalid_schema` / `invalid_direction` / `invalid_depth` / `no_modelable_statement` / `multi_statement_input` / `aggregate_with_format` / `column_not_found`。 */
    val error: String,
    /** 人读原因。 */
    val reason: String? = null,
    /** `multi_statement_input` 时的语句数。 */
    val statementCount: Int? = null,
)
