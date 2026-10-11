package io.github.workeron9.ozmoz.lineage.server

import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.ParseOutcome
import io.github.workeron9.ozmoz.lineage.engine.SchemaLookup
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.ValidateRequest
import io.github.workeron9.ozmoz.lineage.engine.calcite.CalciteEngine
import io.github.workeron9.ozmoz.lineage.engine.jooq.JooqEngine
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.map
import io.github.workeron9.ozmoz.lineage.lineage.LineageBuilder
import io.github.workeron9.ozmoz.lineage.schema.DdlFileSchemaProvider
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider

/**
 * `server` 的组合根逻辑——与 `cli` 的 `LineagePipeline`（internal，不跨模块）
 * **同职责的最小镜像**：引擎选择、内联 DDL schema、多语句 → 血缘模型。
 *
 * 与 `cli` 是两个入口层：共享组合逻辑需要独立模块，M4（web 真正落地）时再议；
 * 本镜像刻意只保留 `/api` 实际用到的最小子集（无目录遍历——HTTP 请求没有目录）。
 */
internal object ServerPipeline {

    /** 默认引擎 id；engine 未指明时使用。 */
    public const val DEFAULT_ENGINE: String = JSqlParserEngine.ID

    /** 引擎注册表：id → 实例；未注册返回 null（HTTP 层报 `unknown_engine`）。 */
    public fun engineById(id: String): SqlEngine? = when (id) {
        JSqlParserEngine.ID -> JSqlParserEngine()
        CalciteEngine.ID -> CalciteEngine()
        JooqEngine.ID -> JooqEngine()
        else -> null
    }

    /**
     * 全部已注册引擎（`/api/engines` 的清单，与 CLI 的注册面一致）。
     * 血缘能力各异的引擎在 `/api/lineage` 按能力表自然分流：
     * jooq 无 `SEMANTIC_MODEL`，`analyzeAll` 回落 Resolved.Unknown（错误是数据，200）。
     */
    public fun allEngines(): List<SqlEngine> = listOf(
        JSqlParserEngine(),
        CalciteEngine(),
        JooqEngine(),
    )

    /**
     * 解析内联 DDL（请求体 `schema` 字段）。走**严格**版
     * [DdlFileSchemaProvider.parse]——DDL 是用户显式给出的权威元数据，解析失败
     * 直接抛（HTTP 层转 400 `invalid_schema`），不静默缺表。
     */
    public fun schemaFromInlineDdl(text: String): SchemaProvider =
        DdlFileSchemaProvider.parse(id = "ddl:inline", sqlTexts = arrayOf(text))

    /**
     * 一段 SQL（可含多条语句）→ 一组血缘模型（与 CLI 同一组合方式）。
     * `schema` 未给出时从 SQL 文本顺手收集 `CREATE TABLE`
     * （[DdlFileSchemaProvider.parseTolerant]，与 `ozml lineage` 的目录行为一致）。
     */
    public fun models(
        sql: String,
        engine: SqlEngine,
        dialect: String?,
        schema: SchemaProvider?,
    ): Resolved<List<LineageModel>> {
        // schema 只算一次：显式给定优先，否则从 SQL 文本自动收集（可能为 null）。
        val effective = schema ?: DdlFileSchemaProvider.parseTolerant(id = "ddl:auto", sqlTexts = arrayOf(sql))
        return engine.analyzeAll(sql, ParseRequest(dialect = dialect))
            .map { statements -> statements.map { LineageBuilder.build(it, effective) } }
    }

    /** `engine.parse` 的薄代理（`/api/parse` 用，保持语义模型 ⊥ 归一化树的分工）。 */
    public fun parse(sql: String, engine: SqlEngine, dialect: String?): ParseOutcome =
        engine.parse(sql, ParseRequest(dialect = dialect))

    /**
     * SQL 排版（[Feature.PRETTY_PRINT]，ADR-0014 的 HTTP 出口）。
     *
     * 与 `ozml format` 同一组合方式：走引擎的 `format`（内容来源是引擎自己的规范化
     * 渲染，不重写渲染器）。引擎未申报能力 / 语法错误 → `Resolved.Unknown`，
     * 由 HTTP 层转成 `{sql: null, diagnostics: [format.skipped]}`（形状恒定）。
     */
    public fun format(
        sql: String,
        engine: SqlEngine,
        dialect: String?,
        multiline: Boolean,
    ): Resolved<io.github.workeron9.ozmoz.lineage.engine.FormatOutcome> =
        engine.format(
            io.github.workeron9.ozmoz.lineage.engine.FormatRequest(
                sql = sql,
                dialect = dialect,
                multiline = multiline,
            ),
        )

    /**
     * 结合 schema 做语义校验（[Feature.VALIDATE_SCHEMA]，ADR-0013 的 HTTP 出口）。
     *
     * schema 来源与 `/api/lineage` 同口径：
     * - [schema] 显式给出 → **严格** [DdlFileSchemaProvider.parse]（权威元数据，解析失败抛，
     *   由 HTTP 层转 400 `invalid_schema`）；
     * - 未给出 → 从 [sql] 文本**顺手收集** `CREATE TABLE`（[DdlFileSchemaProvider.parseTolerant]，
     *   与 CLI `ozml validate` 的目录行为一致）；收不到则 `schema = null`，
     *   引擎按 Never-wrong 返回 `Unknown`（校验没跑成）。
     *
     * 装配点用 SAM 转换把 [SchemaProvider] 包成 engine-api 的 [SchemaLookup]——同签名，零胶水。
     */
    public fun validate(
        sql: String,
        engine: SqlEngine,
        dialect: String?,
        schema: SchemaProvider?,
    ): Resolved<List<io.github.workeron9.ozmoz.lineage.ir.Diagnostic>> {
        val effective = schema ?: DdlFileSchemaProvider.parseTolerant(id = "ddl:auto", sqlTexts = arrayOf(sql))
        return engine.validate(
            ValidateRequest(
                sql = sql,
                dialect = dialect,
                schema = effective?.let { provider -> SchemaLookup { ref -> provider.table(ref) } },
            ),
        )
    }
}
