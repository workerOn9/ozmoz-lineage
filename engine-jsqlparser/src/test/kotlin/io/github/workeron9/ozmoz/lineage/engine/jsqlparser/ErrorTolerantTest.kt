package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import io.github.workeron9.ozmoz.lineage.engine.AnalyzeOutcome
import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 逐语句容错（[Feature.ERROR_TOLERANT] / [JSqlParserEngine.analyzeScript]）端到端。
 *
 * 关键口径：
 * - 整段可解析时与 [JSqlParserEngine.analyzeAll] **逐字一致**（快路径零行为漂移）；
 * - 有坏语句时好语句照常产出、坏语句落带位置的诊断；
 * - **一条都救不回来**仍返回 `Unknown`（容错不是掩饰）。
 */
class ErrorTolerantTest {

    private val engine = JSqlParserEngine()

    private fun script(sql: String): Resolved<AnalyzeOutcome> = engine.analyzeScript(sql)

    private fun known(sql: String): AnalyzeOutcome {
        val result = script(sql)
        assertIs<Resolved.Known<AnalyzeOutcome>>(result)
        return result.value
    }

    @Test
    fun `声明 ERROR_TOLERANT 能力且无不支持原因`() {
        assertTrue(engine.capabilities.supports(Feature.ERROR_TOLERANT))
        assertNull(engine.capabilities.reason(Feature.ERROR_TOLERANT))
    }

    @Test
    fun `整段可解析时与 analyzeAll 逐字一致且无诊断`() {
        val sql = "CREATE TABLE t (a INT, b INT); SELECT a FROM t; INSERT INTO u (a) SELECT a FROM t"

        val all = assertIs<Resolved.Known<List<io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement>>>(
            engine.analyzeAll(sql),
        )
        val outcome = known(sql)

        assertEquals(all.value, outcome.statements)
        assertTrue(outcome.diagnostics.isEmpty())
    }

    @Test
    fun `坏语句降级为诊断 好语句照常产出`() {
        // 第 2 条在 WHERE 处语法错误：第 1 条仍应建模。
        val sql = "SELECT a FROM t;\nSELECT * FROM WHERE x = 1"

        val outcome = known(sql)

        assertEquals(listOf(StatementKind.SELECT), outcome.statements.map { it.kind })
        val diagnostic = outcome.diagnostics.single()
        assertEquals(JSqlParserEngine.CODE_PARSE_ERROR, diagnostic.code)
        assertEquals(Severity.ERROR, diagnostic.severity)
        assertEquals(JSqlParserEngine.ID, diagnostic.engineId)
        val span = assertNotNull(diagnostic.span)
        // 位置落在**坏语句**里（不是整段开头），且行号对得上（坏语句在第 2 行）。
        assertTrue(span.start.offset > sql.indexOf(';'), "span=${span.start.offset} 应落在坏语句里")
        assertEquals(2, span.start.line)
    }

    @Test
    fun `坏语句在前的后续好语句 span 仍对齐原文本`() {
        // 遮罩前缀方案的核心：后面片段的 raw / span 必须指回**整段源文本**的正确位置。
        val sql = "SELECT * FROM WHERE x = 1;\nSELECT b AS z FROM u"

        val outcome = known(sql)

        val statement = outcome.statements.single()
        val query = assertIs<SelectQuery>(statement.query)
        val column = assertIs<SqlExpr.Column>(query.scope.outputs.single().expr)
        assertEquals("b", column.raw)
        val span = assertNotNull(column.span)
        assertEquals("b", sql.substring(span.start.offset, span.end.offset))
        assertEquals(2, span.start.line)
    }

    @Test
    fun `字符串里的分号不误切`() {
        val sql = "SELECT a FROM t WHERE b = 'x;y';\nSELECT * FROM WHERE x = 1"

        val outcome = known(sql)

        // 若切分误把字符串里的分号当边界，第一条也会坏掉 → statements 为空。
        assertEquals(listOf(StatementKind.SELECT), outcome.statements.map { it.kind })
        assertEquals(1, outcome.diagnostics.size)
    }

    @Test
    fun `纯注释尾随片段不凭空造诊断`() {
        val sql = "SELECT a FROM t;\nSELECT * FROM WHERE x = 1;\n-- done"

        val outcome = known(sql)

        assertEquals(1, outcome.statements.size)
        assertEquals(1, outcome.diagnostics.size)
    }

    @Test
    fun `一条都救不回来仍返回 Unknown`() {
        assertIs<Resolved.Unknown>(script("SELECT * FROM WHERE x = 1; SELECT * FROM WHERE y = 2"))
    }

    @Test
    fun `单语句坏输入与非容错同结论`() {
        val sql = "SELECT * FROM WHERE x = 1"
        val result = script(sql)
        assertIs<Resolved.Unknown>(result)
        assertTrue(result.reason.startsWith("解析失败"))
    }

    @Test
    fun `空白输入 Unknown`() {
        val result = script("   ")
        assertIs<Resolved.Unknown>(result)
        assertEquals("SQL 为空，无可提取内容", result.reason)
    }
}
