package io.github.workeron9.ozmoz.lineage.engine.semantics

import io.github.workeron9.ozmoz.lineage.engine.EngineCapabilities
import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.ParseOutcome
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private fun col(name: String, table: String? = null) = ColumnRef(
    raw = name,
    canonical = listOfNotNull(table, name).joinToString(".").lowercase(),
    name = name,
    table = table,
)

class SqlExprTest {

    @Test
    fun `referencedColumns 收集嵌套列`() {
        // SUM(a) + b * c  ->  a, b, c
        val expr = SqlExpr.BinaryOp(
            op = "+",
            left = SqlExpr.Function("SUM", listOf(SqlExpr.Column(col("a"))), raw = "SUM(a)", span = null),
            right = SqlExpr.BinaryOp(
                op = "*",
                left = SqlExpr.Column(col("b")),
                right = SqlExpr.Column(col("c")),
                raw = "b * c",
                span = null,
            ),
            raw = "SUM(a) + b * c",
            span = null,
        )
        assertEquals(listOf("a", "b", "c"), expr.referencedColumns().map { it.name })
    }

    @Test
    fun `literal 与 star 不贡献列`() {
        val expr = SqlExpr.BinaryOp(
            op = "+",
            left = SqlExpr.Literal("1", null),
            right = SqlExpr.Star(null, "*", null),
            raw = "1 + *",
            span = null,
        )
        assertEquals(emptyList(), expr.referencedColumns())
    }

    @Test
    fun `case 各分支都收集`() {
        val expr = SqlExpr.Case(
            operand = null,
            branches = listOf(
                SqlExpr.Case.Branch(SqlExpr.Column(col("a")), SqlExpr.Column(col("b"))),
            ),
            elseExpr = SqlExpr.Column(col("c")),
            raw = "CASE WHEN a THEN b ELSE c END",
            span = null,
        )
        assertEquals(listOf("a", "b", "c"), expr.referencedColumns().map { it.name })
    }

    @Test
    fun `Unknown 必须给出原因`() {
        assertFailsWith<IllegalArgumentException> { SqlExpr.Unknown(raw = "x", reason = "", span = null) }
    }
}

class SemanticStatementTest {

    @Test
    fun `StatementKind 至少能表达六种已实现语句`() {
        assertEquals(6, StatementKind.entries.size)
    }

    @Test
    fun `query 与 scope 全空且无诊断时被拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            SemanticStatement(kind = StatementKind.SELECT)
        }
    }

    @Test
    fun `只有诊断时合法（表示暂时无法建作用域）`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            diagnostics = listOf(Diagnostic.warning("lineage.unsupported", "暂不支持该语句")),
        )
        assertNull(stmt.query)
        assertNull(stmt.scope)
    }

    @Test
    fun `OutputItem 的显式列名：别名优先，其次直传列`() {
        val aliased = OutputItem(SqlExpr.Column(col("a")), alias = "x")
        assertEquals("x", aliased.explicitName)
        val plain = OutputItem(SqlExpr.Column(col("a")))
        assertEquals("a", plain.explicitName)
        val expr = OutputItem(SqlExpr.Function("SUM", listOf(SqlExpr.Column(col("a"))), "SUM(a)", null))
        assertNull(expr.explicitName)
    }
}

class AnalyzeDefaultTest {

    private class StubEngine : SqlEngine {
        override val id: String = "stub"
        override val capabilities: EngineCapabilities = EngineCapabilities(
            features = setOf(Feature.PARSE),
            reasons = mapOf(Feature.SEMANTIC_MODEL to "stub 无语义模型"),
        )

        override fun parse(sql: String, request: ParseRequest): ParseOutcome =
            ParseOutcome(root = AstNode(type = "statement", text = sql))
    }

    @Test
    fun `默认 analyze 返回 Unknown 并带原因`() {
        val result = StubEngine().analyze("SELECT 1")
        assertEquals(Resolved.Unknown("stub 无语义模型"), result)
    }
}
