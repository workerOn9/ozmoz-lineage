package io.github.workeron9.ozmoz.lineage.engine.calcite

import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.semantics.QuerySpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SetOperationQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SourceSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.engine.semantics.SubquerySource
import io.github.workeron9.ozmoz.lineage.engine.semantics.TableSource
import io.github.workeron9.ozmoz.lineage.engine.semantics.referencedColumns
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `CalciteSemanticExtractor` / `CalciteEngine.analyze*`：
 * 与 jsqlparser 侧 `SemanticExtractor` 同一契约的对照测试——
 * 语句种类（SELECT / CTE / UNION / INSERT / CTAS / VIEW / UPDATE / DELETE）、
 * 来源（表 / 派生表 / join 展平）、表达式（列 / 字面量 / 函数 / 窗口 / CASE / BETWEEN / IN / `*`）、
 * Never-wrong（MERGE / VALUES / 纯 DDL 跳过，解析失败不抛）。
 */
class CalciteSemanticTest {

    private val engine = CalciteEngine()

    private fun selectScope(sql: String, dialect: String = "ansi") = analyze(sql, dialect).query as SelectQuery

    private fun analyze(sql: String, dialect: String = "ansi") =
        when (val r = engine.analyze(sql, ParseRequest(dialect = dialect))) {
            is Resolved.Known -> r.value
            else -> throw IllegalArgumentException("analyze 应成功：${(r as? Resolved.Unknown)?.reason}")
        }

    // ————— 语句种类 —————

    @Test
    fun `纯 select 是 SELECT 且作用域有来源`() {
        val statement = analyze("SELECT id FROM customer WHERE age > 18")
        assertEquals(StatementKind.SELECT, statement.kind)
        assertEquals(null, statement.target)
        val scope = (statement.query as SelectQuery).scope
        assertEquals(ScopeKind.SELECT, scope.kind)
        assertEquals(1, scope.sources.size)
        val table = scope.sources.filterIsInstance<TableSource>().first().table
        assertEquals("customer", table.name)
        assertEquals(1, scope.filters.size) // WHERE 进 filters
        assertContains(table.canonical, "customer")
    }

    @Test
    fun `三段 CTE 链式引用整链提取`() {
        val query = selectScope(
            "WITH a AS (SELECT id FROM t), b AS (SELECT id FROM a), c AS (SELECT id FROM b) SELECT id FROM c",
        )
        assertEquals(3, query.ctes.size)
        // 链式：b 的体引用 a（CTE 体沿作用域引用，cycle 由 lineage 解析）
        assertEquals("a", query.ctes[0].name)
        assertEquals("c", query.ctes[2].name)
    }

    @Test
    fun `union 分支与 order 上下文`() {
        val statement = analyze(
            "SELECT a FROM t1 UNION ALL SELECT b FROM t2 ORDER BY a",
            dialect = "mysql",
        )
        val query = statement.query as SetOperationQuery
        assertEquals(2, query.branches.size)
        assertEquals("UNION ALL", query.op)
    }

    @Test
    fun `insert select 的 target 与 targetColumns`() {
        val statement = analyze(
            "INSERT INTO dst (a, b) SELECT x FROM src",
        )
        assertEquals(StatementKind.INSERT, statement.kind)
        assertEquals("dst", statement.target?.name)
        assertEquals(listOf("a", "b"), statement.targetColumns.map { it.name })
        val scope = (statement.query as SelectQuery).scope
        assertEquals(ScopeKind.INSERT, scope.kind)
    }

    @Test
    fun `ctas 与 create view 的 query 来源`() {
        val ctas = analyze("CREATE TABLE dst AS SELECT id FROM src")
        assertEquals(StatementKind.CREATE_TABLE_AS, ctas.kind)
        assertEquals("dst", ctas.target?.name)
        assertEquals(1, (ctas.query as SelectQuery).scope.sources.size)

        val view = analyze("CREATE VIEW v (a) AS SELECT id FROM src")
        assertEquals(StatementKind.CREATE_VIEW, view.kind)
        assertEquals("v", view.target?.name)
        assertEquals(listOf("a"), view.targetColumns.map { it.name })
    }

    @Test
    fun `update 与 delete 的作用域与 assignments`() {
        val update = analyze("UPDATE t SET a = a + 1, b = 2 WHERE id = 7")
        assertEquals(StatementKind.UPDATE, statementKind(update))
        assertEquals("t", update.target?.name)
        assertEquals(listOf("a", "b"), update.assignments.map { it.target.name })
        assertEquals(2, update.assignments.size)
        assertTrue(update.scope != null)

        val delete = analyze("DELETE FROM t WHERE id = 1")
        assertEquals(StatementKind.DELETE, delete.kind)
        assertEquals("t", delete.target?.name)
    }

    // ————— 来源 / join 展平 —————

    @Test
    fun `join 展平成 sources + joins`() {
        val scope = selectScope(
            "SELECT c.id, o.total FROM customer c LEFT JOIN orders o ON o.cid = c.id",
        ).scope
        val sources = scope.sources
        assertEquals(1, sources.size) // 左链挂 scope，orders 在 joins 里
        val join = scope.joins.first()
        assertContains(join.type, "LEFT")
        val right = join.right as TableSource
        assertEquals("orders", right.table.name)
        val on = join.on.first()
        assertContains(on.referencedColumns().map { it.id }, "o.cid")
    }

    @Test
    fun `派生表与别名`() {
        val scope = selectScope(
            "SELECT s.id FROM (SELECT id FROM customer) AS s WHERE s.id > 0",
        ).scope
        val source = scope.sources.filterIsInstance<SubquerySource>().first()
        assertEquals("s", source.alias)
        assertEquals(ScopeKind.SUBQUERY, (source.query as SelectQuery).scope.kind)
    }

    // ————— 表达式 —————

    @Test
    fun `star 与 qualifier`() {
        val scope = selectScope("SELECT *, u.* FROM users u").scope
        assertEquals(SqlExpr.Star::class, scope.outputs[0].expr::class)
        assertEquals(SqlExpr.Star::class, scope.outputs[1].expr::class)
        assertEquals("u", (scope.outputs[1].expr as SqlExpr.Star).qualifier)
    }

    @Test
    fun `聚合 窗口 case between in 表达式形状`() {
        val scope = selectScope(
            "SELECT SUM(x) AS s, ROW_NUMBER() OVER (PARTITION BY y ORDER BY x) AS rn FROM t GROUP BY y",
        ).scope
        assertContains(scope.outputs[0].expr::class.simpleName!!, "Function")
        val window = scope.outputs[1].expr as SqlExpr.Window
        assertEquals(1, window.partitionBy.size)
        assertEquals(1, window.orderBy.size)
        assertEquals(1, scope.groupBy.size)

        val exprScope = selectScope(
            "SELECT CASE WHEN x > 0 THEN a ELSE b END FROM t WHERE id BETWEEN 1 AND 10 OR k IN (1, 2)",
        ).scope
        assertEquals(SqlExpr.Case::class, exprScope.outputs[0].expr::class)
        val columnIds = exprScope.filters.flatMap { it.referencedColumns() }.map { it.id }
        // 裸列不带表限定（与 jsqlparser 侧同口径；歧义由 lineage 解析）。
        assertContains(columnIds.toSet(), "id") // BETWEEN 上下界的列引用不断链
        assertContains(columnIds.toSet(), "k") // IN 列表（Function(list)）的列收集
    }

    @Test
    fun `cast 保持列引用`() {
        val scope = selectScope("SELECT CAST(x AS BIGINT) AS xi FROM t").scope
        val refs = scope.outputs[0].expr.referencedColumns().map { it.id }
        assertContains(refs.toSet(), "x")
    }

    // ————— Never-wrong —————

    @Test
    fun `merge values 纯 ddl 是不可建模的 Unknown`() {
        for (sql in listOf("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = s.b", "VALUES (1, 2)", "CREATE TABLE t (id INT)")) {
            val result = engine.analyze(sql, ParseRequest(dialect = "ansi"))
            assertTrue(result is Resolved.Unknown, "$sql 应不可建模：${result::class.simpleName}")
        }
    }

    @Test
    fun `analyze 只收单条 多语句给可解释错`() {
        val result = engine.analyze("SELECT 1; SELECT 2;", ParseRequest(dialect = "ansi"))
        assertTrue(result is Resolved.Unknown)
        assertContains((result as Resolved.Unknown).reason, "analyzeAll")
    }

    @Test
    fun `analyzeAll 多语句跳过纯 ddl`() {
        val result = engine.analyzeAll(
            "SELECT 1; CREATE TABLE t (id INT); DELETE FROM t WHERE id = 1;",
            ParseRequest(dialect = "ansi"),
        )
        assertTrue(result is Resolved.Known)
        // DELETE 建模了、CREATE TABLE 是纯 DDL 跳过（1 条）
        assertEquals(2, (result as Resolved.Known).value.size)
    }

    @Test
    fun `analyzeAll 解析失败不抛异常`() {
        val result = engine.analyzeAll("SELECT FROM WHERE", ParseRequest(dialect = "mysql"))
        assertTrue(result is Resolved.Unknown)
        assertContains((result as Resolved.Unknown).reason, "解析失败")
    }

    private fun statementKind(value: io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement) = value.kind
}
