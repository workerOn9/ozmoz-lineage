package io.github.workeron9.ozmoz.lineage.engine.jooq

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
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.impl.DSL
import org.jooq.impl.ParserException
import org.jooq.SQLDialect

/**
 * jOOQ 引擎适配器——**方言转换第二实现**（ADR-0004：Calcite 主力，jOOQ 出差异）。
 *
 * 定位（见计划 §4.2 / 知识库 `04-调研/jOOQ-OSS方言覆盖实测.md`）：
 * - ✅ OSS 12 个可承诺方言 + `DEFAULT`（对齐语料 `ansi`）：**重点补 Calcite 注册表
 *   没有的关系库**（mariadb / sqlite / h2 / hsqldb / derby / firebird / clickhouse /
 *   yugabytedb）；数仓系（Hive / Spark / Snowflake / BigQuery 等）jOOQ OSS
 *   **编译期不存在**，只能由 Calcite 主力承担。
 * - ✅ 与 Calcite 主力同一条 **render-verified 门禁**：jOOQ 在 parse 时就完成
 *   方言翻译，`toString()` 有方言差（`LIMIT` vs `FETCH`）不可比——等价基线用
 *   `DSL.using(SQLDialect.DEFAULT).render(q)` 的**中性渲染**对比两侧
 *   （本机 probe 实测：MYSQL→POSTGRES 的 `LIMIT 10` 两侧中性渲染都是
 *   `fetch next 10 rows only`，等价成立）。
 * - ❌ 无 AST 导出（jOOQ 未公开 SQL AST）；`analyze` 未实现（`Resolved.Unknown`）。
 *
 * 引擎私有类型（`Query` / `DSLContext`）**只在本适配器内部出现**。
 */
public class JooqEngine : SqlEngine {

    override val id: String = ID

    override val capabilities: EngineCapabilities = EngineCapabilities(
        features = setOf(
            Feature.PARSE,
            Feature.DIALECT_PARSE,
            Feature.DIALECT_RENDER,
        ),
        dialects = JooqDialects.ids(),
        reasons = mapOf(
            Feature.SEMANTIC_MODEL to "语义模型暂未实现，血缘由 jsqlparser 引擎承担",
            Feature.MULTI_STATEMENT to "多语句语义提取暂未实现（analyze 不可用）",
            Feature.EXTRACT_TABLES to "表提取暂未实现，血缘由 jsqlparser 引擎承担",
            Feature.VALIDATE_SCHEMA to "不做 schema 语义校验",
            Feature.FIELD_LINEAGE to "字段级血缘由 lineage 模块 + jsqlparser 引擎承担",
            Feature.ERROR_TOLERANT to "jOOQ 解析器不支持误差容忍",
            Feature.AST_EXPORT to "jOOQ 无公开 SQL AST，不产归一化树（解析主力是 jsqlparser）",
            Feature.PRETTY_PRINT to "渲染固定单行风格，未提供排版开关",
        ),
    )

    /**
     * 解析为**归一化树**（[ParseOutcome.root]）。
     *
     * jOOQ 无公开 SQL AST，树是**浅的**：每层语句取**实现类名去 `Impl` 后缀**
     * 当 `type`（`select` / `insert` / `createtable` …），`text` 保源文切片（Lossless）；
     * 树对比主引擎仍是 jsqlparser / calcite。
     */
    override fun parse(sql: String, request: ParseRequest): ParseOutcome {
        if (sql.isBlank()) {
            return failureOutcome(CODE_EMPTY_INPUT, "SQL 为空，无可解析内容", null)
        }

        val sourceId = resolveDialect(request.dialect)
            ?: return failureOutcome(CODE_DIALECT_UNKNOWN, dialectUnknownMessage(request.dialect), null)

        val queries = try {
            queries(sourceId, sql)
        } catch (e: ParserException) {
            return failureOutcome(CODE_PARSE_ERROR, firstLine(e.message) ?: "解析失败", spanOf(sql, e))
        } catch (e: RuntimeException) {
            return failureOutcome(CODE_PARSE_ERROR, firstLine(e.message) ?: e.javaClass.simpleName, null)
        }

        if (queries.isEmpty()) {
            return failureOutcome(CODE_EMPTY_INPUT, "没有可解析的语句", null)
        }

        val normalizer = JooqAstNormalizer(sql)
        val root = if (queries.size == 1) {
            normalizer.normalize(queries.first())
        } else {
            AstNode(
                type = TYPE_STATEMENT_LIST,
                text = "",
                span = null,
                children = queries.map { normalizer.normalize(it) },
            )
        }
        return ParseOutcome(diagnostics = emptyList(), tables = emptyList(), root = root)
    }

    /**
     * **方言渲染**（[Feature.DIALECT_RENDER]），与 Calcite 主力同一条 render-verified 门禁：
     *
     * 1. 按**源方言**解析（缺省 [JooqDialects.DEFAULT_DIALECT]；jOOQ parse 时已完成
     *    方言翻译，解析树带源方言族）；
     * 2. 换**目标方言**的 DSLContext 渲染（渲染即翻译）；
     * 3. 渲染结果按**目标方言** re-parse；
     * 4. **中性基线对比**：两侧都用 `SQLDialect.DEFAULT` 渲染，canon（小写 + 折叠空白）
     *    等价才输出；**不等价就不输出**（ADR-0004）。
     */
    override fun render(request: RenderRequest): Resolved<String> {
        if (request.sql.isBlank()) return Resolved.Unknown("SQL 为空，无可转换内容")

        val fromId = request.fromDialect ?: JooqDialects.DEFAULT_DIALECT
        val fromIdRes = resolveDialect(fromId)
            ?: return Resolved.Unknown(dialectUnknownMessage(fromId))
        val toId = request.toDialect
            ?: return Resolved.Unknown("缺少目标方言（toDialect 未给出）")
        val targetContext = JooqDialects.context(toId)
            ?: return Resolved.Unknown(dialectUnknownMessage(toId))

        val base = try {
            queries(fromIdRes, request.sql)
        } catch (e: ParserException) {
            return Resolved.Unknown(renderFailReason("源 SQL 解析失败", e), spanOf(request.sql, e))
        } catch (e: RuntimeException) {
            return Resolved.Unknown(renderFailReason("源 SQL 解析失败", e))
        }
        if (base.isEmpty()) return Resolved.Unknown("没有可转换的语句")

        val rendered: List<String> = try {
            base.map { targetContext.render(it) }
        } catch (e: RuntimeException) {
            return Resolved.Unknown(renderFailReason("无法按目标方言 $toId 渲染", e))
        }
        if (rendered.any { it.isBlank() }) {
            return Resolved.Unknown(renderFailReason("目标方言 $toId 渲染出空语句", null))
        }

        val reparsed = try {
            rendered.map { targetContext.parser().parseQuery(it) }
        } catch (e: ParserException) {
            return Resolved.Unknown(renderFailReason("渲染结果按目标方言 $toId 读不回", e))
        } catch (e: RuntimeException) {
            return Resolved.Unknown(renderFailReason("渲染结果按目标方言 $toId 读不回", e))
        }

        val baseNeutral = try {
            base.map { neutralContext.render(it) }
        } catch (e: RuntimeException) {
            return Resolved.Unknown(renderFailReason("无法取等价基线（中性渲染失败）", e))
        }
        val reparsedNeutral = try {
            reparsed.map { neutralContext.render(it) }
        } catch (e: RuntimeException) {
            return Resolved.Unknown(renderFailReason("无法取等价基线（中性渲染失败）", e))
        }

        val baseCanon = baseNeutral.joinToString(STATEMENT_SEPARATOR)
        val renderedCanon = reparsedNeutral.joinToString(STATEMENT_SEPARATOR)
        if (canon(baseCanon) != canon(renderedCanon)) {
            return Resolved.Unknown(unverifiedReason(base = baseCanon, rendered = renderedCanon))
        }
        return Resolved.Known(rendered.joinToString(STATEMENT_SEPARATOR))
    }

    // ————— 解析 —————

    /** `Parser.parse(String)` 支持多语句；单语句等价于列表里的一条。 */
    private fun queries(dialect: String, sql: String): List<Query> =
        JooqDialects.context(dialect)!!.parser().parse(sql).queryStream().toList()

    /**
     * 方言解析：**给了却未注册才是错**（上层报 `dialect_unknown`），没给才落缺省
     * `DEFAULT`（`ansi`）——Never-wrong 不允许「未注册静默回退默认」。
     */
    private fun resolveDialect(dialect: String?): String? =
        if (dialect == null) {
            JooqDialects.DEFAULT_DIALECT
        } else {
            dialect.trim().lowercase().replace('-', '_').takeIf { JooqDialects.dialect(it) != null }
        }

    private fun failureOutcome(code: String, message: String, span: Span?): ParseOutcome = ParseOutcome(
        diagnostics = listOf(
            Diagnostic(
                severity = Severity.ERROR,
                code = code,
                message = message,
                span = span,
                engineId = id,
            ),
        ),
        root = AstNode.empty(),
    )

    // ————— 渲染校验 —————

    /** `ParserException.position()`（0 基字符偏移，可等于串长=EOF）→ [Span]；越界返回 null。 */
    private fun spanOf(source: String, e: ParserException): Span? {
        val pos = e.position()
        if (pos < 0 || pos > source.length) return null
        return Span.of(source, pos, (pos + 1).coerceAtMost(source.length))
    }

    /** canon：折叠空白 + 小写（与 Calcite 门禁同一口径）。 */
    private fun canon(text: String): String =
        text.replace(WHITESPACE, " ").trim().lowercase()

    private fun unverifiedReason(base: String, rendered: String): String =
        "$CODE_RENDER_UNVERIFIED: 渲染结果与源 SQL 不等价，按 ADR-0004 不输出。" +
            "base=$base rendered=$rendered"

    private fun renderFailReason(prefix: String, e: Exception?): String {
        val message = e?.let { firstLine(it.message) } ?: "未知原因"
        return "$RENDER_FAIL_PREFIX: $prefix: $message"
    }

    // ————— 诊断 / 方言 —————

    private fun dialectUnknownMessage(dialect: String?): String =
        "$CODE_DIALECT_UNKNOWN: 未注册方言 ${dialect ?: "<null>"}（已注册：" +
            JooqDialects.ids().sorted().joinToString(", ") + "）"

    private fun firstLine(message: String?): String? =
        message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }

    public companion object {
        public const val ID: String = "jooq"

        /** 诊断码：稳定契约，调用方按它分支，不要按 message 文案分支。 */
        public const val CODE_PARSE_ERROR: String = "jooq.parse_error"
        public const val CODE_EMPTY_INPUT: String = "jooq.empty_input"
        public const val CODE_DIALECT_UNKNOWN: String = "jooq.dialect_unknown"
        public const val CODE_RENDER_UNVERIFIED: String = "jooq.render_unverified"
        public const val RENDER_FAIL_PREFIX: String = "jooq.render_failed"

        private const val TYPE_STATEMENT_LIST: String = "statement_list"

        /** 多语句的拼接分隔符（与 Calcite 引擎同口径）。 */
        private const val STATEMENT_SEPARATOR: String = ";\n"

        private val WHITESPACE: Regex = Regex("\\s+")

        /** 中性渲染的 DSLContext（`SQLDialect.DEFAULT`）——等价基线，每 JVM 一个。 */
        private val neutralContext: DSLContext = DSL.using(SQLDialect.DEFAULT)

        init {
            // jOOQ 启动 banner / tip 走 java.util.logging（stderr）；工具输出必须静音。
            System.setProperty("org.jooq.no-logo", "true")
            System.setProperty("org.jooq.no-tips", "true")
        }

        /** Java 侧友好入口。 */
        @JvmStatic
        public fun create(): JooqEngine = JooqEngine()
    }
}
