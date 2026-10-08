package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SetOperationQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.engine.semantics.SubquerySource
import io.github.workeron9.ozmoz.lineage.engine.semantics.TableSource
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticExtractorTest {

    private val engine = JSqlParserEngine()

    private fun known(sql: String): io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement {
        val result = engine.analyze(sql)
        assertIs<Resolved.Known<io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement>>(result)
        return result.value
    }

    private fun selectScope(sql: String): io.github.workeron9.ozmoz.lineage.engine.semantics.ScopeSpec {
        val query = assertIs<SelectQuery>(known(sql).query)
        return query.scope
    }

    // ——— 能力声明 ———

    @Test
    fun `声明 SEMANTIC_MODEL 能力`() {
        assertTrue(engine.capabilities.supports(Feature.SEMANTIC_MODEL))
    }

    // ——— SELECT ———

    @Test
    fun `SELECT 聚合别名与各子句`() {
        val stmt = known("SELECT a, SUM(b) AS s FROM t WHERE c > 1 GROUP BY a ORDER BY s")
        assertEquals(StatementKind.SELECT, stmt.kind)
        val query = assertIs<SelectQuery>(stmt.query)
        val scope = query.scope
        assertEquals(ScopeKind.SELECT, scope.kind)

        val source = assertIs<TableSource>(scope.sources.single())
        assertEquals("t", source.table.name)

        assertEquals(2, scope.outputs.size)
        val first = scope.outputs[0]
        assertEquals("a", assertIs<SqlExpr.Column>(first.expr).ref.name)
        val second = scope.outputs[1]
        val fn = assertIs<SqlExpr.Function>(second.expr)
        assertEquals("SUM", fn.name)
        assertEquals("b", assertIs<SqlExpr.Column>(fn.args.single()).ref.name)
        assertEquals("s", second.alias)

        assertTrue(scope.filters.isNotEmpty())
        assertTrue(scope.groupBy.isNotEmpty())
        assertTrue(scope.orderBy.isNotEmpty())
    }

    @Test
    fun `SELECT 星号映射为 Star`() {
        val scope = selectScope("SELECT * FROM t")
        val star = assertIs<SqlExpr.Star>(scope.outputs.single().expr)
        assertEquals(null, star.qualifier)
        assertEquals("*", star.raw)
    }

    @Test
    fun `SELECT t 点星号保留限定名`() {
        val scope = selectScope("SELECT t.* FROM t")
        val star = assertIs<SqlExpr.Star>(scope.outputs.single().expr)
        assertEquals("t", star.qualifier)
    }

    @Test
    fun `WHERE 过滤条件映射为表达式`() {
        val scope = selectScope("SELECT a FROM t WHERE c > 1")
        val filter = assertIs<SqlExpr.BinaryOp>(scope.filters.single())
        assertEquals(">", filter.op)
        assertEquals("c", assertIs<SqlExpr.Column>(filter.left).ref.name)
        assertEquals("1", assertIs<SqlExpr.Literal>(filter.right).raw)
    }

    // ——— CTE ———

    @Test
    fun `CTE 挂到顶层查询且外层来源是 TableSource`() {
        val query = assertIs<SelectQuery>(known("WITH c AS (SELECT a FROM s) SELECT a FROM c").query)
        val cte = query.ctes.single()
        assertEquals("c", cte.name)
        assertIs<SelectQuery>(cte.query)
        assertEquals(ScopeKind.CTE, (cte.query as SelectQuery).scope.kind)

        // CTE-vs-物理表的解析是 lineage 模块的事，这里如实报告标识符
        val outer = assertIs<TableSource>(query.scope.sources.single())
        assertEquals("c", outer.table.name)
    }

    @Test
    fun `CTE 显式列名被保留`() {
        val query = assertIs<SelectQuery>(known("WITH c (x, y) AS (SELECT a FROM s) SELECT x FROM c").query)
        assertEquals(listOf("x", "y"), query.ctes.single().columns)
    }

    // ——— 子查询 ———

    @Test
    fun `FROM 子查询映射为 SubquerySource`() {
        val query = assertIs<SelectQuery>(known("SELECT x FROM (SELECT a AS x FROM inner_t) sub").query)
        val sub = assertIs<SubquerySource>(query.scope.sources.single())
        assertEquals("sub", sub.alias)
        val inner = assertIs<SelectQuery>(sub.query)
        assertEquals(ScopeKind.SUBQUERY, inner.scope.kind)
        assertEquals("inner_t", assertIs<TableSource>(inner.scope.sources.single()).table.name)
    }

    // ——— 集合运算 ———

    @Test
    fun `UNION ALL 映射为 SetOperationQuery 两个分支`() {
        val query = assertIs<SetOperationQuery>(known("SELECT a FROM s UNION ALL SELECT b FROM t").query)
        assertContains(query.op, "UNION")
        assertEquals(2, query.branches.size)
        val branch = assertIs<SelectQuery>(query.branches[0])
        assertEquals(ScopeKind.UNION_BRANCH, branch.scope.kind)
    }

    // ——— JOIN ———

    @Test
    fun `JOIN 带类型与 ON 条件`() {
        val scope = selectScope("SELECT s.a FROM store_sales s JOIN customer c ON s.id = c.id")
        val join = scope.joins.single()
        assertTrue(join.type.isNotBlank())
        val right = assertIs<TableSource>(join.right)
        assertEquals("customer", right.table.name)
        assertTrue(join.on.isNotEmpty())
    }

    @Test
    fun `LEFT JOIN 类型含 LEFT`() {
        val scope = selectScope("SELECT s.a FROM store_sales s LEFT JOIN customer c ON s.id = c.id")
        assertContains(scope.joins.single().type, "LEFT")
    }

    // ——— 窗口 ———

    @Test
    fun `窗口函数映射为 Window`() {
        val scope = selectScope("SELECT SUM(a) OVER (PARTITION BY b) FROM t")
        val window = assertIs<SqlExpr.Window>(scope.outputs.single().expr)
        assertTrue(window.partitionBy.isNotEmpty())
        val fn = assertIs<SqlExpr.Function>(window.function)
        assertEquals("SUM", fn.name)
        assertEquals("a", assertIs<SqlExpr.Column>(fn.args.single()).ref.name)
    }

    // ——— CASE ———

    @Test
    fun `CASE 映射为分支与 ELSE`() {
        val scope = selectScope("SELECT CASE WHEN a > 0 THEN 'p' ELSE 'n' END AS sign FROM t")
        val case = assertIs<SqlExpr.Case>(scope.outputs.single().expr)
        assertEquals(1, case.branches.size)
        assertNotNull(case.elseExpr)
        assertEquals(null, case.operand)
    }

    // ——— INSERT ———

    @Test
    fun `INSERT SELECT 映射目标列与查询`() {
        val stmt = known("INSERT INTO t (a, b) SELECT x, y FROM s")
        assertEquals(StatementKind.INSERT, stmt.kind)
        assertEquals("t", assertNotNull(stmt.target).name)
        assertEquals(listOf("a", "b"), stmt.targetColumns.map { it.name })
        val query = assertIs<SelectQuery>(stmt.query)
        assertEquals("s", assertIs<TableSource>(query.scope.sources.single()).table.name)
    }

    // ——— CTAS / CREATE VIEW ———

    @Test
    fun `CREATE TABLE AS 映射`() {
        val stmt = known("CREATE TABLE t AS SELECT a FROM s")
        assertEquals(StatementKind.CREATE_TABLE_AS, stmt.kind)
        assertEquals("t", assertNotNull(stmt.target).name)
        assertIs<SelectQuery>(stmt.query)
    }

    @Test
    fun `CREATE VIEW 映射目标列`() {
        val stmt = known("CREATE VIEW v (a, b) AS SELECT x, y FROM s")
        assertEquals(StatementKind.CREATE_VIEW, stmt.kind)
        assertEquals("v", assertNotNull(stmt.target).name)
        assertEquals(listOf("a", "b"), stmt.targetColumns.map { it.name })
        assertIs<SelectQuery>(stmt.query)
    }

    // ——— UPDATE / DELETE ———

    @Test
    fun `UPDATE 映射赋值与过滤`() {
        val stmt = known("UPDATE t SET a = 1 WHERE b = 2")
        assertEquals(StatementKind.UPDATE, stmt.kind)
        assertEquals("t", assertNotNull(stmt.target).name)
        val assignment = stmt.assignments.single()
        assertEquals("a", assignment.target.name)
        assertEquals("1", assertIs<SqlExpr.Literal>(assignment.value).raw)
        val scope = assertNotNull(stmt.scope)
        assertEquals(ScopeKind.UPDATE, scope.kind)
        assertTrue(scope.filters.isNotEmpty())
        assertEquals("t", assertIs<TableSource>(scope.sources.single()).table.name)
    }

    @Test
    fun `DELETE 映射过滤`() {
        val stmt = known("DELETE FROM t WHERE a = 1")
        assertEquals(StatementKind.DELETE, stmt.kind)
        assertEquals("t", assertNotNull(stmt.target).name)
        val scope = assertNotNull(stmt.scope)
        assertEquals(ScopeKind.DELETE, scope.kind)
        assertTrue(scope.filters.isNotEmpty())
    }

    // ——— Never-wrong ———

    @Test
    fun `解析失败返回 Unknown 而非抛异常`() {
        val result = engine.analyze("SELECT * FROM")
        assertIs<Resolved.Unknown>(result)
    }

    @Test
    fun `空输入返回 Unknown`() {
        assertIs<Resolved.Unknown>(engine.analyze("   "))
    }

    @Test
    fun `不支持的语句种类返回 Unknown`() {
        val result = engine.analyze("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = 1")
        assertIs<Resolved.Unknown>(result)
        assertContains(result.reason, "不支持提取语义的语句")
    }

    @Test
    fun `未知子表达式落进 Unknown 而非猜测`() {
        // `a IN (1, 2)` 的 IN 谓词本适配器不建模为已知表达式，应是 Unknown 而非猜一个形状
        val scope = selectScope("SELECT a FROM t WHERE a IN (1, 2)")
        val filter = scope.filters.single()
        assertIs<SqlExpr.Unknown>(filter)
        assertTrue(filter.reason.isNotBlank())
    }

    // ——— Lossless ———

    @Test
    fun `Column 的 raw 与 span 精确到源文本`() {
        val sql = "SELECT   ss_quantity   FROM store_sales"
        val scope = selectScope(sql)
        val column = assertIs<SqlExpr.Column>(scope.outputs.single().expr)
        assertEquals("ss_quantity", column.raw)
        val span = assertNotNull(column.span)
        assertEquals("ss_quantity", sql.substring(span.start.offset, span.end.offset))
        assertEquals(1, span.start.line)
        assertEquals(10, span.start.column)
    }
}
