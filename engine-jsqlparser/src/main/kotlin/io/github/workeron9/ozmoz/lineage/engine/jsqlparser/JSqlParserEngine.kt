package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import io.github.workeron9.ozmoz.lineage.engine.EngineCapabilities
import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.ParseOutcome
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.parser.ParseException
import net.sf.jsqlparser.statement.Statement

/**
 * JSqlParser 引擎适配器——**解析主力**。
 *
 * 定位（见计划 §4.2）：
 * - ✅ 轻量、快、纯 Java、**零传递依赖**（实测 2026-10-08：`jsqlparser:5.4` 无 compile 依赖）。
 * - ✅ 表名 / 列名提取、查询模型。
 * - ❌ **没有方言概念**：`toString()` 是原样回写，不是方言转换。因此 [capabilities] 里
 *   **不声明** [Feature.DIALECT_RENDER]，`render` 走默认实现返回 `Resolved.Unknown`。
 * - ❌ v1 不做字段级血缘与语义校验（由 `lineage` 模块与其它引擎承担）。
 *
 * 引擎私有类型（`Statement` / `Node` / `Token`）**只在本适配器内部出现**，
 * 不进入 `ir` / `engine-api` 的公共签名。
 */
public class JSqlParserEngine : SqlEngine {

    override val id: String = ID

    override val capabilities: EngineCapabilities = EngineCapabilities(
        features = setOf(
            Feature.PARSE,
            Feature.EXTRACT_TABLES,
            Feature.SEMANTIC_MODEL,
            Feature.PRETTY_PRINT,
            Feature.AST_EXPORT,
        ),
        // JSqlParser 无方言概念：这里列的是「能按 ANSI/通用语法解析」，不是方言承诺。
        dialects = setOf("ansi"),
        reasons = mapOf(
            Feature.DIALECT_PARSE to "无方言概念，按通用语法解析",
            Feature.DIALECT_RENDER to "DeParser 是原样回写，不做方言转换",
            Feature.VALIDATE_SCHEMA to "不做 schema 语义校验",
            Feature.FIELD_LINEAGE to "字段级血缘由 lineage 模块承担",
            Feature.ERROR_TOLERANT to "v1 不支持编辑中不完整 SQL",
        ),
    )

    override fun parse(sql: String, request: ParseRequest): ParseOutcome {
        if (sql.isBlank()) {
            return ParseOutcome(
                diagnostics = listOf(
                    Diagnostic.error(CODE_EMPTY_INPUT, "SQL 为空，无可解析内容"),
                ),
                root = AstNode.empty(),
            )
        }

        val statement: Statement = try {
            CCJSqlParserUtil.parse(sql)
        } catch (e: JSQLParserException) {
            return failure(sql, e)
        }

        val normalizer = AstNormalizer(sql)
        val root = normalizer.normalize(statement)
        return ParseOutcome(
            diagnostics = emptyList(),
            tables = TablesFinder.find(statement, sql),
            root = root,
        )
    }

    /**
     * 提取**引擎无关的语句语义模型**（[SemanticStatement]）。
     *
     * 与 [parse] 一致：解析或提取失败**不抛异常**——解析失败返回 `Resolved.Unknown`，
     * 不支持语义提取的语句种类（如 `MERGE`）也返回 `Resolved.Unknown`（Never-wrong）。
     * 认不出的子表达式落进 `SqlExpr.Unknown`，提取不全时附 `jsqlparser.semantic_partial` 诊断。
     */
    override fun analyze(sql: String, request: ParseRequest): Resolved<SemanticStatement> {
        if (sql.isBlank()) return Resolved.Unknown("SQL 为空，无可提取内容")

        val statement: Statement = try {
            CCJSqlParserUtil.parse(sql)
        } catch (e: JSQLParserException) {
            return Resolved.Unknown(parseFailureReason(sql, e), parseFailureSpan(sql, e))
        } catch (e: RuntimeException) {
            // 解析器内部偶发运行期异常也不得外抛（Never-wrong）。
            return Resolved.Unknown("解析失败: ${firstLine(e.message) ?: e.javaClass.simpleName}")
        }

        return SemanticExtractor(sql).extract(statement)
    }

    /** 解析失败的机器可读原因，复用 [parse] 的位置提取逻辑。 */
    private fun parseFailureReason(sql: String, e: JSQLParserException): String {
        val message = e.findParseException()?.let { firstLine(it.message) }
            ?: firstLine(e.message)
            ?: "解析失败"
        return "解析失败: $message"
    }

    private fun parseFailureSpan(sql: String, e: JSQLParserException) =
        e.findParseException()?.let { JsSqlSpan.ofParseException(sql, it) }

    /** 解析失败：**不抛异常**，返回带 ERROR 诊断与位置的 [ParseOutcome]（Never-wrong / Lossless）。 */
    private fun failure(sql: String, e: JSQLParserException): ParseOutcome {
        val parseException = e.findParseException()
        val span = parseException?.let { JsSqlSpan.ofParseException(sql, it) }
        val message = parseException?.let { firstLine(it.message) }
            ?: firstLine(e.message)
            ?: "解析失败"
        return ParseOutcome(
            diagnostics = listOf(
                Diagnostic(
                    severity = io.github.workeron9.ozmoz.lineage.ir.Severity.ERROR,
                    code = CODE_PARSE_ERROR,
                    message = message,
                    span = span,
                    engineId = id,
                ),
            ),
            root = AstNode.empty(),
        )
    }

    /** JSqlParser 把 `ParseException` 埋在多层 cause 里（实测：第 4 层）。 */
    private fun JSQLParserException.findParseException(): ParseException? {
        var cause: Throwable? = this
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (cause is ParseException) return cause
            cause = cause.cause
            depth++
        }
        return null
    }

    private fun firstLine(message: String?): String? =
        message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }

    public companion object {
        public const val ID: String = "jsqlparser"

        /** 诊断码：稳定契约，调用方按它分支，不要按 message 文案分支。 */
        public const val CODE_PARSE_ERROR: String = "jsqlparser.parse_error"
        public const val CODE_EMPTY_INPUT: String = "jsqlparser.empty_input"

        private const val MAX_CAUSE_DEPTH = 10

        /** Java 侧友好入口。 */
        @JvmStatic
        public fun create(): JSqlParserEngine = JSqlParserEngine()
    }
}
