package io.github.workeron9.ozmoz.lineage.engine.calcite

import io.github.workeron9.ozmoz.lineage.engine.EngineCapabilities
import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.ParseOutcome
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.RenderRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Severity
import io.github.workeron9.ozmoz.lineage.ir.Span
import org.apache.calcite.config.Lex
import org.apache.calcite.runtime.CalciteContextException
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.dialect.CalciteSqlDialect
import org.apache.calcite.sql.parser.SqlParseException
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.parser.SqlParserPos

/**
 * Apache Calcite 引擎适配器——**方言转换 + 语义校验主力**（ADR-0004）。
 *
 * 定位（见计划 §4.2）：
 * - ✅ **方言渲染**：`SqlDialect.toSqlString`，覆盖 jsqlparser 缺失的数仓系
 *   方言（Hive / Spark / Snowflake / BigQuery / Trino / DuckDB …）。
 * - ✅ **按方言配解析器**：反引号 / 方括号 / 双引号由 [CalciteDialects] 注册表统一管理。
 * - ✅ **render-verified 门禁**（风险 R3 的对策，抽测证明可自动化）：
 *   输出前跑 `parse → render → re-parse(按目标方言配) → canon 等价`；
 *   **不等价时不输出**，返回带原因的 [Resolved.Unknown]（含 MSSQL 静默丢弃
 *   `LIMIT`/`OFFSET` 这类「输出能跑、语义变了」的最危险降级）。
 * - ❌ 语义模型暂不做（血缘主力是 jsqlparser）；`analyze` 走默认实现返回
 *   `Resolved.Unknown`（Never-wrong）。
 *
 * 引擎私有类型（`SqlNode` / `SqlParserPos` / `Lex`）**只在本适配器内部出现**，
 * 不进入 `ir` / `engine-api` 的公共签名。
 */
public class CalciteEngine : SqlEngine {

    override val id: String = ID

    override val capabilities: EngineCapabilities = EngineCapabilities(
        features = setOf(
            Feature.PARSE,
            Feature.DIALECT_PARSE,
            Feature.DIALECT_RENDER,
            Feature.AST_EXPORT,
        ),
        // 只列 [CalciteDialects] 注册（= 抽测实测 / 有意保有）的方言，不预支承诺。
        dialects = CalciteDialects.IDS,
        reasons = mapOf(
            Feature.SEMANTIC_MODEL to "语义模型暂未实现，血缘由 jsqlparser 引擎承担",
            Feature.MULTI_STATEMENT to "多语句语义提取暂未实现（analyze 不可用）",
            Feature.EXTRACT_TABLES to "表提取暂未实现，血缘由 jsqlparser 引擎承担",
            Feature.VALIDATE_SCHEMA to "schema 语义校验未实现（M3 校验增强再评估）",
            Feature.FIELD_LINEAGE to "字段级血缘由 lineage 模块 + jsqlparser 引擎承担",
            Feature.ERROR_TOLERANT to "Calcite 解析器不支持误差容忍",
            Feature.PRETTY_PRINT to "unparse 固定单行缩进风格，未提供排版开关",
        ),
    )

    /**
     * 解析为**归一化树**（[ParseOutcome.root]）。
     *
     * 用 [SqlParser.parseStmtList] 解析语句列表（单语句等价于列表里的一条）：
     * - 单条 → 直接造该语句的归一化树（`ozml parse` 输出形状与 jsqlparser 引擎一致）；
     * - 多条 → 根节点 `statement_list`（Calcite 分号切分，天然多语句）。
     */
    override fun parse(sql: String, request: ParseRequest): ParseOutcome {
        if (sql.isBlank()) {
            return ParseOutcome(
                diagnostics = listOf(
                    Diagnostic(
                        severity = Severity.ERROR,
                        code = CODE_EMPTY_INPUT,
                        message = "SQL 为空，无可解析内容",
                        engineId = id,
                    ),
                ),
                root = AstNode.empty(),
            )
        }

        val spec = resolveDialect(request.dialect)
            ?: return ParseOutcome(
                diagnostics = listOf(
                    Diagnostic(
                        severity = Severity.ERROR,
                        code = CODE_DIALECT_UNKNOWN,
                        message = dialectUnknownMessage(request.dialect),
                        engineId = id,
                    ),
                ),
                root = AstNode.empty(),
            )

        val statements = try {
            parseStatementList(sql, spec.lex)
        } catch (e: SqlParseException) {
            return parseFailure(sql, e.pos, firstLine(e.message))
        } catch (e: CalciteContextException) {
            return parseFailure(sql, posOf(e), firstLine(e.message))
        } catch (e: RuntimeException) {
            return parseFailure(sql, null, firstLine(e.message) ?: e.javaClass.simpleName)
        }

        if (statements.isEmpty()) {
            return ParseOutcome(
                diagnostics = listOf(
                    Diagnostic(
                        severity = Severity.ERROR,
                        code = CODE_EMPTY_INPUT,
                        message = "没有可解析的语句",
                        engineId = id,
                    ),
                ),
                root = AstNode.empty(),
            )
        }

        val normalizer = CalciteAstNormalizer(sql)
        val root = if (statements.size == 1) {
            normalizer.normalize(statements.first())
        } else {
            AstNode(
                type = TYPE_STATEMENT_LIST,
                text = "",
                span = null,
                children = statements.map { normalizer.normalize(it) },
            )
        }
        return ParseOutcome(
            diagnostics = emptyList(),
            tables = emptyList(), // 表提取由 jsqlparser 主力承担（能力表如实声明，不假装给）
            root = root,
        )
    }

    /**
     * **方言渲染**（[Feature.DIALECT_RENDER]），带 render-verified 门禁：
     *
     * 1. 按**源方言**解析输入（源方言缺省用 [CalciteDialects.DEFAULT_DIALECT]）；
     * 2. 按**目标方言**逐语句 `toSqlString` 渲染；
     * 3. 把渲染结果按**目标方言**的解析器配置 re-parse；
     * 4. canon（小写 + 折叠空白）等价校验：等价 → 输出；不等价 → **不输出**，
     *    返回 `Resolved.Unknown(reason)`，reason 里带 base 与 rendered 全文（可诊断）。
     *
     * 证据（知识库 `04-调研/Calcite-unparse保真度抽测.md`）：`AnsiSqlDialect` re-parse 0/20、
     * `MssqlSqlDialect` 静默丢弃 `LIMIT`——都是这套门禁能自动抓到的形态。
     */
    override fun render(request: RenderRequest): Resolved<String> {
        if (request.sql.isBlank()) return Resolved.Unknown("SQL 为空，无可转换内容")

        val fromId = request.fromDialect ?: CalciteDialects.DEFAULT_DIALECT
        val fromSpec = CalciteDialects.spec(fromId)
            ?: return Resolved.Unknown(dialectUnknownMessage(fromId))
        val toId = request.toDialect
            ?: return Resolved.Unknown("缺少目标方言（toDialect 未给出）")
        val toSpec = CalciteDialects.spec(toId)
            ?: return Resolved.Unknown(dialectUnknownMessage(toId))

        val base = try {
            parseStatementList(request.sql, fromSpec.lex)
        } catch (e: SqlParseException) {
            return Resolved.Unknown(renderFailReason("源 SQL 解析失败", e), spanOf(request.sql, e.pos))
        } catch (e: CalciteContextException) {
            return Resolved.Unknown(renderFailReason("源 SQL 解析失败", e), spanOf(request.sql, posOf(e)))
        } catch (e: RuntimeException) {
            return Resolved.Unknown(renderFailReason("源 SQL 解析失败", e))
        }
        if (base.isEmpty()) return Resolved.Unknown("没有可转换的语句")

        val rendered: String = try {
            base.joinToString(STATEMENT_SEPARATOR) { it.toSqlString(toSpec.dialect).getSql() }
        } catch (e: Exception) {
            return Resolved.Unknown(
                renderFailReason("无法按目标方言 ${toSpec.id} 渲染", e),
            )
        }

        val reparsed = try {
            parseStatementList(rendered, toSpec.lex)
        } catch (e: SqlParseException) {
            return Resolved.Unknown(
                renderFailReason("渲染结果按目标方言 ${toSpec.id} 读不回", e),
            )
        } catch (e: CalciteContextException) {
            return Resolved.Unknown(
                renderFailReason("渲染结果按目标方言 ${toSpec.id} 读不回", e),
            )
        } catch (e: RuntimeException) {
            return Resolved.Unknown(
                renderFailReason("渲染结果按目标方言 ${toSpec.id} 读不回", e),
            )
        }
        if (reparsed.isEmpty()) {
            return Resolved.Unknown(renderFailReason("渲染结果按目标方言 ${toSpec.id} 读不回（解析结果为空）", null))
        }

        val baseCanon = canon(base)
        val renderedCanon = canon(reparsed)
        if (baseCanon != renderedCanon) {
            return Resolved.Unknown(
                unverifiedReason(base = baseCanon, rendered = renderedCanon),
            )
        }
        return Resolved.Known(rendered)
    }

    // ————— 解析 —————

    /** 统一用 no-arg `parseStmtList()`（抽测 E5：`SqlParser.create(sql, cfg)` + 无参方法，quoting 生效）。 */
    private fun parseStatementList(sql: String, lex: Lex): List<SqlNode> {
        val parser = SqlParser.create(sql, SqlParser.config().withLex(lex))
        return parser.parseStmtList().getList().filterNotNull()
    }

    private fun parseFailure(sql: String, pos: SqlParserPos?, message: String?): ParseOutcome = ParseOutcome(
        diagnostics = listOf(
            Diagnostic(
                severity = Severity.ERROR,
                code = CODE_PARSE_ERROR,
                message = message ?: "解析失败",
                span = spanOf(sql, pos),
                engineId = id,
            ),
        ),
        root = AstNode.empty(),
    )

    // ————— 渲染校验 —————

    /** 等价基线 / 复验读回的 canon：折叠空白 + 小写（抽测 E2 的判定口径）。 */
    private fun canon(nodes: List<SqlNode>): String =
        nodes.joinToString(STATEMENT_SEPARATOR) { it.toString() }
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase()

    /** gate 拦截的原因：诊断码开头 + base / rendered 全文（可诊断对比）。 */
    private fun unverifiedReason(base: String, rendered: String): String =
        "$CODE_RENDER_UNVERIFIED: 渲染结果与源 SQL 不等价，按 ADR-0004 不输出。" +
            "base=$base rendered=$rendered"

    private fun renderFailReason(prefix: String, e: Exception?): String {
        val message = e?.let { firstLine(it.message) } ?: "未知原因"
        return "$RENDER_FAIL_PREFIX: $prefix: $message"
    }

    // ————— 方言 / 诊断 —————

    /**
     * 方言解析：**给了却未注册才是错**（报 `dialect_unknown`），没给才落默认档。
     * 之前用 `?:` 链会是「未注册静默回退默认」——Never-wrong 不允许。
     */
    private fun resolveDialect(dialect: String?): CalciteDialects.Spec? =
        if (dialect == null) {
            CalciteDialects.spec(CalciteDialects.DEFAULT_DIALECT)
        } else {
            CalciteDialects.spec(dialect)
        }

    private fun dialectUnknownMessage(dialect: String?): String =
        "$CODE_DIALECT_UNKNOWN: 未注册方言 ${dialect ?: "<null>"}（已注册：" +
            CalciteDialects.IDS.sorted().joinToString(", ") + "）"

    private fun spanOf(source: String, pos: SqlParserPos?): Span? = CalcitePositions.spanOf(source, pos)

    /**
     * `CalciteContextException` 的位置：`getPosLine` 等在异常本体上
     * （`SqlParseException` 则直接 `getPos()`）。
     */
    private fun posOf(e: CalciteContextException): SqlParserPos? {
        val line = e.posLine
        val column = e.posColumn
        if (line <= 0 || column <= 0) return null
        return SqlParserPos(line, column, e.endPosLine, e.endPosColumn)
    }

    private fun firstLine(message: String?): String? =
        message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }

    public companion object {
        public const val ID: String = "calcite"

        /** 诊断码：稳定契约，调用方按它分支，不要按 message 文案分支。 */
        public const val CODE_PARSE_ERROR: String = "calcite.parse_error"
        public const val CODE_EMPTY_INPUT: String = "calcite.empty_input"
        public const val CODE_DIALECT_UNKNOWN: String = "calcite.dialect_unknown"
        public const val CODE_RENDER_UNVERIFIED: String = "calcite.render_unverified"
        public const val RENDER_FAIL_PREFIX: String = "calcite.render_failed"

        private const val TYPE_STATEMENT_LIST: String = "statement_list"

        /** 多语句的拼接分隔符（解析侧 `;` 切分，渲染侧用分隔符回拼）。 */
        private const val STATEMENT_SEPARATOR: String = ";\n"

        private val WHITESPACE: Regex = Regex("\\s+")

        /** Java 侧友好入口。 */
        @JvmStatic
        public fun create(): CalciteEngine = CalciteEngine()
    }
}
