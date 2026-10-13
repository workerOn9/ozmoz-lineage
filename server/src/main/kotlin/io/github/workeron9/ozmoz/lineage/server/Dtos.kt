package io.github.workeron9.ozmoz.lineage.server

import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.calcite.CalciteEngine
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
) {
    public companion object {
        /**
         * 从一个引擎的能力声明构造报告——**形状与 `/api/engines` 单一来源**：
         * HTTP 路由与 `ozml engines` 都调这里，避免两处各写一遍导致漂移。
         */
        @JvmStatic
        public fun from(engine: io.github.workeron9.ozmoz.lineage.engine.SqlEngine): EngineReport = EngineReport(
            id = engine.id,
            features = Feature.entries.filter { engine.capabilities.supports(it) }.map { it.name },
            dialects = engine.capabilities.dialects.toList(),
            reasons = engine.capabilities.reasons.mapKeys { (feature, _) -> feature.name },
        )
    }
}

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
    /**
     * 现建现查的 SQL；与 [graph] **二选一**（本字段原有契约不变：给 sql 即走老路径）。
     */
    val sql: String? = null,
    /**
     * **已落库的图库名**（ADR-0015），与 [sql] 二选一：给出时返回**整库合并图**
     * （[GraphReportDto]，与 `aggregate=true` **同形**）——这是 Web UI「图库浏览」的数据出口。
     * 只传库名（相对 `--graph-root` 白名单根解析）；未开启该能力 → 400 `unsupported_capability`，
     * 库名非法 → 400 `invalid_graph_ref`，库里没有 → 422 `graph_not_found`。
     * 该模式下 [format] / [engine] / [dialect] / [schema] / [aggregate] **均不参与**。
     */
    val graph: String? = null,
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
    /**
     * 现建现查的 SQL（与 [graph] **二选一**，ADR-0015）。
     * 缺省 null：图来自 [graph]（已落库的全库血缘图）。
     */
    val sql: String? = null,
    /**
     * **已落库的图库名**（`ozml lineage --graph` 的产物，ADR-0015）——与 [sql] 二选一。
     * 只传**库名**（如 `reports.db`），相对 `ozml serve --graph-root` 的白名单根解析；
     * 绝对路径 / 逃逸 / 非 `.db` → 400 `invalid_graph_ref`，库里没有 → 422 `graph_not_found`。
     * 该模式下 [engine] / [dialect] / [schema] **被忽略**（库里的模型是落库时的产物）。
     * 两者都给 / 都不给 → 400 `invalid_request`。未配置 `--graph-root` 时用 [graph] → 400 `unsupported_capability`。
     */
    val graph: String? = null,
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
    /** 现建现查的 SQL（与 [graph] **二选一**，ADR-0015）；缺省 null 表示图来自 [graph]。 */
    val sql: String? = null,
    /**
     * 已落库的图库名（与 [sql] 二选一，语义同 [ImpactQuery.graph]）：
     * 只传库名，相对白名单根解析；非法引用 → 400 `invalid_graph_ref`，
     * 库里没有 → 422 `graph_not_found`；该模式下 [engine] / [dialect] / [schema] 被忽略。
     */
    val graph: String? = null,
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

/**
 * `POST /api/validate` 请求（结合 schema 做语义校验，ADR-0013 的 HTTP 出口）。
 *
 * [schema] 与 `/api/lineage` 同口径：给出时是**内联 DDL 文本**（CREATE TABLE），
 * 解析失败 → 400；未给出时**从 [sql] 文本顺手收集** `CREATE TABLE`
 * （[io.github.workeron9.ozmoz.lineage.schema.DdlFileSchemaProvider.parseTolerant]）。
 * 两条路都收不到 schema 时，校验**没跑成**——仍 200，[ValidateReportDto.diagnostics]
 * 里落一条 `validate.skipped`（Never-wrong：没有校验目标就如实说，不猜）。
 */
@Serializable
public data class ValidateQuery(
    val sql: String,
    /** 引擎 id（默认 `calcite`——唯一申报 `VALIDATE_SCHEMA` 的引擎）；未注册 → 400，不支持校验 → 400。 */
    val engine: String? = null,
    /** 方言名（决定解析器的 quoting 配置）。 */
    val dialect: String? = null,
    /** 内联 DDL 文本（CREATE TABLE）；给出时**优先**（权威元数据，解析失败 → 400）。 */
    val schema: String? = null,
)

/**
 * `POST /api/validate` 响应——与 `ozml validate` 的 stdout JSON **同形**
 * （{engine, dialect, diagnostics}），HTTP 层不发明第二个形状。
 *
 * **形状恒定**：不论校验「跑成」（[io.github.workeron9.ozmoz.lineage.ir.Resolved.Known]，
 * diagnostics 可空可含 ERROR）还是「没跑成」（`Unknown`），响应都是本形状——
 * 后者落一条 [CODE_VALIDATE_SKIPPED] ERROR 诊断（与 CLI 的 stderr 口径一字不差）。
 * 这与 `/api/lineage` 的 `{unknown: {...}}` 取舍不同，是有意的：validate 的产物
 * **本身就是诊断流**（ADR-0013），「没跑成」也是一种诊断。
 */
@Serializable
public data class ValidateReportDto(
    val engine: String,
    val dialect: String? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    public companion object {
        /** 默认校验引擎：calcite——唯一申报 `VALIDATE_SCHEMA` 的引擎（ADR-0013）。 */
        public const val DEFAULT_ENGINE: String = CalciteEngine.ID

        /**
         * 诊断码：校验**没跑成**时统一用它（引擎未给 schema / SQL 为空 / 方言未注册）。
         * 与 CLI `ValidateCommand.CODE_VALIDATE_SKIPPED` **同一字符串契约**——
         * `cli` 与 `server` 是两个入口层（不可互相依赖），此处按既有惯例镜像常量。
         */
        public const val CODE_VALIDATE_SKIPPED: String = "validate.skipped"
    }
}

/**
 * `POST /api/format` 请求（SQL 排版，ADR-0014 的 HTTP 出口）。
 *
 * [schema] **不参与**（排版不换方言、不依赖 schema）；[engine] 缺省 `jsqlparser`。
 * [multiline] 缺省 true（多行子句换行）；false 产规范化单行。
 */
@Serializable
public data class FormatQuery(
    val sql: String,
    /** 引擎 id（默认 `jsqlparser`——唯一申报 `PRETTY_PRINT` 的引擎）；未注册 → 400，不支持排版 → 400。 */
    val engine: String? = null,
    /** 方言名（jsqlparser 无方言概念，仅记录）。 */
    val dialect: String? = null,
    /** 多行排版（缺省 true）；false 产规范化单行。 */
    val multiline: Boolean = true,
)

/**
 * `POST /api/format` 响应——与 `ozml format --json` **同形**
 * （{engine, dialect, sql, diagnostics}），HTTP 层不发明第二个形状。
 *
 * **形状恒定**：不论排版「跑成」（`Resolved.Known`）还是「没跑成」（`Resolved.Unknown`），
 * 响应都是本形状——后者 [sql] 为 null，[diagnostics] 含 `format.skipped` ERROR。
 * 这与 `/api/lineage` 的 `{unknown: {...}}` 取舍不同，是有意的：format 的产物
 * 是一段 SQL 文本 + 有损告警，「没跑成」也是一种诊断。
 */
@Serializable
public data class FormatReportDto(
    val engine: String,
    val dialect: String? = null,
    val sql: String? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    public companion object {
        /** 默认排版引擎：jsqlparser——唯一申报 `PRETTY_PRINT` 的引擎（ADR-0014）。 */
        public const val DEFAULT_ENGINE: String = "jsqlparser"

        /**
         * 诊断码：排版**没跑成**时统一用它（引擎未申报能力 / SQL 为空 / 语法错误）。
         * 与 CLI `FormatCommand.CODE_FORMAT_SKIPPED` **同一字符串契约**——
         * `cli` 与 `server` 是两个入口层（不可互相依赖），此处按既有惯例镜像常量。
         */
        public const val CODE_FORMAT_SKIPPED: String = "format.skipped"
    }
}

/**
 * `ozml mcp` 的 `ozml_convert` 工具响应——与 CLI `ozml convert --format json` 的
 * ConvertReport **同形**（{engine, fromDialect, toDialect, sql, diagnostics}），
 * 按「HTTP 层不发明第二个形状」的惯例镜像。
 *
 * **形状恒定**：转换「跑成」（门禁通过）与「没跑成」（render-verified 门禁拦截）
 * 都回本形状——后者 [sql] 为 null，[diagnostics] 含 [CODE_RENDER_UNVERIFIED] ERROR。
 */
@Serializable
public data class ConvertReportDto(
    val engine: String,
    val fromDialect: String? = null,
    val toDialect: String,
    val sql: String? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    public companion object {
        /**
         * 诊断码：转换被门禁拦截时统一用它。
         * 与 CLI `ConvertCommand.CODE_RENDER_UNVERIFIED` **同一字符串契约**——
         * `cli` 与 `server` 是两个入口层（不可互相依赖），此处按既有惯例镜像常量。
         */
        public const val CODE_RENDER_UNVERIFIED: String = "convert.render_unverified"
    }
}

/** 4xx / 422 的统一错误体。 */
@Serializable
public data class ErrorResponse(
    /** 机器可判的短码：`invalid_request` / `unknown_engine` / `unknown_format` / `unsupported_capability` / `invalid_schema` / `invalid_direction` / `invalid_depth` / `no_modelable_statement` / `multi_statement_input` / `aggregate_with_format` / `column_not_found` / `invalid_graph_ref` / `graph_not_found`。 */
    val error: String,
    /** 人读原因。 */
    val reason: String? = null,
    /** `multi_statement_input` 时的语句数。 */
    val statementCount: Int? = null,
)

/**
 * `POST /api/graphs` 响应（ADR-0015）——白名单根下可浏览的图库清单。
 *
 * 只有配置了 `ozml serve --graph-root <dir>` 才有内容；未配置时该端点整体
 * 返回 400 `unsupported_capability`（能力表路由，与 `/api/validate` 同惯例）。
 * [GraphEntryDto.modelCount] 走 `SqliteLineageStore.count()`——**只数不载图**，
 * 避免列个清单就把全库模型反序列化一遍。
 */
@Serializable
public data class GraphsReportDto(
    val graphs: List<GraphEntryDto> = emptyList(),
) {
    @Serializable
    public data class GraphEntryDto(
        /** 库名（相对白名单根；直接回填到 `/api/impact` 的 `graph` 字段即可）。 */
        val name: String,
        /** 库里可建模语句数（`lineage_model` 行数）。 */
        val modelCount: Int,
    )
}
