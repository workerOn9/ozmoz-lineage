package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import io.github.workeron9.ozmoz.lineage.engine.AnalyzeOutcome
import io.github.workeron9.ozmoz.lineage.engine.EngineCapabilities
import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.FormatOutcome
import io.github.workeron9.ozmoz.lineage.engine.FormatRequest
import io.github.workeron9.ozmoz.lineage.engine.ParseOutcome
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Severity
import io.github.workeron9.ozmoz.lineage.ir.Span
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
            Feature.MULTI_STATEMENT,
            Feature.ERROR_TOLERANT,
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

    /**
     * 提取**多语句脚本**的语义模型（[Feature.MULTI_STATEMENT]）。
     *
     * 用 `CCJSqlParserUtil.parseStatements` 一次性解析整段脚本，再对**每条语句**单独
     * 提取；无法建模的语句（纯 DDL / `MERGE` / `VALUES` 等）**跳过**，不让整脚本失败。
     * 只有整段脚本**语法解析失败**才返回 [Resolved.Unknown]（带原因与位置）。
     *
     * **整段失败不是终点**：想「坏语句不拖垮整段」用 [analyzeScript]
     * （[Feature.ERROR_TOLERANT]）——它先走本方法的快路径，失败再逐语句重试。
     */
    override fun analyzeAll(sql: String, request: ParseRequest): Resolved<List<SemanticStatement>> {
        if (sql.isBlank()) return Resolved.Unknown("SQL 为空，无可提取内容")

        val statements: List<Statement> = try {
            CCJSqlParserUtil.parseStatements(sql).toList()
        } catch (e: JSQLParserException) {
            return Resolved.Unknown(parseFailureReason(sql, e), parseFailureSpan(sql, e))
        } catch (e: RuntimeException) {
            return Resolved.Unknown("解析失败: ${firstLine(e.message) ?: e.javaClass.simpleName}")
        }

        return Resolved.Known(extractEach(sql, statements))
    }

    /**
     * **逐语句容错**提取多语句脚本的语义模型（[Feature.ERROR_TOLERANT]）。
     *
     * 走两段：
     * 1. **快路径**：整段可解析时与 [analyzeAll] **逐字一致**（零行为漂移）；
     * 2. **慢路径**：整段失败时按顶层 `;` 切分（[StatementSplitter]），逐条独立解析——
     *    坏语句降级为带位置的 [Diagnostic]，好语句照常产出（**丢就说**，不静默丢）。
     *
     * 结论口径与 [analyzeAll] 一致（Never-wrong）：**一条都救不回来**（含单语句坏输入）
     * 仍返回 [Resolved.Unknown]——容错只在「有得有失」时才有意义，全坏就不是容错是掩饰。
     */
    override fun analyzeScript(sql: String, request: ParseRequest): Resolved<AnalyzeOutcome> {
        // 快路径：整段可解析 → 复用 analyzeAll（含 DDL / MERGE 跳过口径）。
        when (val strict = analyzeAll(sql, request)) {
            is Resolved.Known -> return Resolved.Known(AnalyzeOutcome(strict.value))
            // 空白输入：与非容错路径同结论，逐语句重试没有意义。
            is Resolved.Unknown -> if (sql.isBlank()) return strict
        }

        val statements = ArrayList<SemanticStatement>()
        val diagnostics = ArrayList<Diagnostic>()
        for (chunk in StatementSplitter.split(sql)) {
            when (val parsed = parseChunk(sql, chunk)) {
                is ChunkParse.Ok -> when (val extracted = SemanticExtractor(parsed.masked).extract(parsed.statement)) {
                    is Resolved.Known -> statements += extracted.value
                    is Resolved.Unknown -> Unit // 建模不了（DDL / MERGE…）与非容错路径同口径：跳过。
                }

                is ChunkParse.Bad -> diagnostics += Diagnostic(
                    severity = Severity.ERROR,
                    code = CODE_PARSE_ERROR,
                    message = parsed.message,
                    span = parsed.span,
                    engineId = id,
                )
            }
        }

        if (statements.isEmpty()) {
            val first = diagnostics.firstOrNull()
            return Resolved.Unknown(
                reason = first?.let { "解析失败: ${it.message}" } ?: "解析失败",
                span = first?.span,
            )
        }
        return Resolved.Known(AnalyzeOutcome(statements = statements, diagnostics = diagnostics))
    }

    /** 逐条提取（每条用**独立的** extractor：其 diagnostics 是累积的，复用会串味）。 */
    private fun extractEach(sql: String, statements: List<Statement>): List<SemanticStatement> {
        val extracted = ArrayList<SemanticStatement>(statements.size)
        for (statement in statements) {
            when (val result = SemanticExtractor(sql).extract(statement)) {
                is Resolved.Known -> extracted += result.value
                is Resolved.Unknown -> Unit // 跳过无法建模的语句（DDL / MERGE…），Never-wrong 不硬塞。
            }
        }
        return extracted
    }

    /** 单个片段的解析结果：成功时**连它的 masked 文本一起带出**（[SemanticExtractor] 要用它取位置）。 */
    private sealed interface ChunkParse {
        data class Ok(val masked: String, val statement: Statement) : ChunkParse
        data class Bad(val message: String, val span: Span?) : ChunkParse
    }

    /**
     * 解析一个片段：**把片段之前的字符全部遮成空格（保留换行）**再解析，于是片段的
     * token 绝对偏移与整段源文本对齐——[SemanticExtractor] / [JsSqlSpan] 一个字符都不用改
     * 就能给出正确的行列位置（Lossless）。
     *
     * 代价是 O(片段起点) 的字符拷贝；只在「整段解析已失败」的脚本上发生，且每片段一次，
     * 对现实脚本（数百条语句）可忽略。真要放大到极端规模，可换成 char 数组就地擦写 + Reader。
     */
    private fun parseChunk(source: String, chunk: StatementSplitter.Chunk): ChunkParse {
        val masked = maskBefore(source, chunk.start) + source.substring(chunk.start, chunk.endExclusive)
        return try {
            ChunkParse.Ok(masked = masked, statement = CCJSqlParserUtil.parse(masked))
        } catch (e: JSQLParserException) {
            val parseException = e.findParseException()
            ChunkParse.Bad(
                message = parseException?.let { firstLine(it.message) } ?: firstLine(e.message) ?: "解析失败",
                span = parseException?.let { JsSqlSpan.ofParseException(source, it) },
            )
        } catch (e: RuntimeException) {
            ChunkParse.Bad("解析失败: ${firstLine(e.message) ?: e.javaClass.simpleName}", null)
        }
    }

    private fun maskBefore(source: String, endExclusive: Int): String {
        val chars = CharArray(endExclusive)
        for (i in 0 until endExclusive) {
            val c = source[i]
            chars[i] = if (c == '\n' || c == '\r') c else ' '
        }
        return String(chars)
    }

    /**
     * **格式化（排版）**一条 SQL（[Feature.PRETTY_PRINT]）。
     *
     * 内容来源是引擎自己的 [StatementDeParser]（规范化：关键字大写、空白归一）——
     * 本项目**不重写渲染器**，只在规范文本上按子句插换行（[SqlFormatter]），
     * 因此 `parse → format → re-parse` 语义等价，且一个字符都不丢。
     *
     * **有损如实报**（Never-wrong 镜像）：JSqlParser 的词法器把注释当 `SPECIAL_TOKEN`
     * 跳过（实测 2026-10-11），DeParser 因此**必然丢注释**——检测到源文本含注释时，
     * 落一条 [CODE_COMMENT_DROPPED] WARNING（带第一条注释的位置），不静默丢。
     *
     * 解析失败（语法错误）→ `Resolved.Unknown`（给不出就不给，与 [render] 同口径）。
     */
    override fun format(request: FormatRequest): Resolved<FormatOutcome> {
        if (request.sql.isBlank()) return Resolved.Unknown("SQL 为空，无可排版内容")

        val statement: Statement = try {
            CCJSqlParserUtil.parse(request.sql)
        } catch (e: JSQLParserException) {
            return Resolved.Unknown(parseFailureReason(request.sql, e), parseFailureSpan(request.sql, e))
        } catch (e: RuntimeException) {
            return Resolved.Unknown("解析失败: ${firstLine(e.message) ?: e.javaClass.simpleName}")
        }

        val canonical = SqlFormatter.canonical(statement)
        val sql = if (request.multiline) SqlFormatter.format(canonical) else canonical
        val diagnostics = SqlComments.firstCommentSpan(request.sql)?.let { span ->
            listOf(
                Diagnostic.warning(
                    CODE_COMMENT_DROPPED,
                    "重排不保留注释（JSqlParser 渲染器丢弃注释）",
                    span,
                ),
            )
        } ?: emptyList()
        return Resolved.Known(FormatOutcome(sql = sql, diagnostics = diagnostics))
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
                    severity = Severity.ERROR,
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

        /** 诊断码：格式化时源文本含注释——渲染器丢弃注释，如实告警（有损）。 */
        public const val CODE_COMMENT_DROPPED: String = "jsqlparser.comment_dropped"

        private const val MAX_CAUSE_DEPTH = 10

        /** Java 侧友好入口。 */
        @JvmStatic
        public fun create(): JSqlParserEngine = JSqlParserEngine()
    }
}
