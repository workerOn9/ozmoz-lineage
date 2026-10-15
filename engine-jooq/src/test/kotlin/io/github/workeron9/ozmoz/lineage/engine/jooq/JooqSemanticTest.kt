package io.github.workeron9.ozmoz.lineage.engine.jooq

import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.semantics.QuerySpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
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
 * `JooqSemanticExtractor` / `JooqEngine.analyze*`：QOM → `SemanticStatement`。
 *
 * 契约与 calcite / jsqlparser 侧一致；与另两引擎的差异（无 span、`raw` 是渲染文本、
 * 集合运算不建模、CTE 列清单不带）在 `JooqSemanticExtractor` 的 KDoc 里声明。
 */
class JooqSemanticTest {

    private val engine = JooqEngine()

    private fun analyze(sql: String, dialect: String = "postgres") =
        when (val r = engine.analyze(sql, ParseRequest(dialect = dialect))) {
            is Resolved.Known -> r.value
            else -> throw IllegalArgumentException("analyze 应成功：${(r as? Resolved.Unknown)?.reason}")
        }

    private fun selectScope(sql: String, dialect: String = "postgres") =
        (analyze(sql, dialect).query as SelectQuery).scope

    // ————— 能力表 —————
    @Test
    @Suppress("NonAsciiCharacters")
    fun `能力表申报语义模型与多语句`() {
        assertTrue(engine.capabilities.supports(Feature.SEMANTIC_MODEL))
        assertTrue(engine.capabilities.reason(Feature.SEMANTIC_MODEL) == null)
        assertTrue(engine.capabilities.supports(Feature.MULTI_STATEMENT))
        assertTrue(engine.capabilities.reason(Feature.MULTI_STATEMENT) == null)
    }

    // ————— SELECT 结构 —————
    @Test
    fun `select 的来源 输出 过滤 分组 排序 都进作用域`() {
        val scope = selectScope(
            """
            SELECT c.name, SUM(o.amount) AS total
            FROM orders o
            JOIN customers c ON c.id = o.order_id
            WHERE o.status = 'paid'
            GROUP BY c.name
            HAVING SUM(o.amount) > 10
            ORDER BY c.name
            """.trimIndent(),
        )
        assertEquals(ScopeKind.SELECT, scope.kind)
        assertEquals(listOf("orders"), scope.sources.filterIsInstance<TableSource>().map { it.table.name })
        assertEquals("customers", scope.joins.single().right.let { (it as TableSource).table.name })
        assertEquals("INNER", scope.joins.single().type)
        assertEquals(listOf("name", "total"), scope.outputs.map { it.explicitName })
        assertTrue(scope.filters.isNotEmpty())
        assertTrue(scope.groupBy.isNotEmpty())
        assertTrue(scope.having.isNotEmpty())
        assertTrue(scope.orderBy.isNotEmpty())
        // 输出列的列引用（血缘的原料）
        assertEquals(listOf("c.name"), scope.outputs[0].expr.referencedColumns().map { it.qualifiedName })
    }

    @Test
    fun `左连接派生表是 SubquerySource 且内部可递归`() {
        val scope = selectScope(
            """
            SELECT q.a, u.b
            FROM (SELECT x AS a FROM t) AS q
            LEFT JOIN u ON u.id = q.a
            """.trimIndent(),
        )
        assertEquals("LEFT OUTER", scope.joins.single().type)
        // jOOQ 把左表放 `$table1()`、右表放 `$table2()`：派生表在 sources，join 的右侧是 u
        val derived = scope.sources.single() as SubquerySource
        assertEquals("q", derived.alias)
        val inner = (derived.query as SelectQuery).scope
        assertEquals(listOf("t"), inner.sources.filterIsInstance<TableSource>().map { it.table.name })
        assertEquals("u", (scope.joins.single().right as TableSource).table.name)
    }

    @Test
    fun `CTE 进 query ctes 且带内部查询`() {
        val query = analyze("WITH paid AS (SELECT a FROM t) SELECT a FROM paid").query as SelectQuery
        val cte = query.ctes.single()
        assertEquals("paid", cte.name)
        // 显式列清单在 QOM 里没有读取口——如实为空（KDoc 第 3 点）
        assertEquals(emptyList(), cte.columns)
        assertEquals("t", ((cte.query as SelectQuery).scope.sources.single() as TableSource).table.name)
    }

    @Test
    fun `限定名带 schema 前缀 裸列不带表`() {
        val scope = selectScope("SELECT v.col, bare FROM sch.tbl v")
        val (qualified, bare) = scope.outputs.map { it.expr }
        assertEquals("v", (qualified as SqlExpr.Column).ref.table)
        assertEquals(null, (bare as SqlExpr.Column).ref.table)
        val source = scope.sources.single() as TableSource
        assertEquals("sch", source.table.schema)
        assertEquals("tbl", source.table.name)
        assertEquals("sch.tbl", source.table.canonical)
    }

    @Test
    fun `星号 与 t星号 都记为 Star`() {
        val scope = selectScope("SELECT * FROM t")
        assertEquals(SqlExpr.Star(qualifier = null, raw = "*", span = null), scope.outputs.single().expr)
        val qualified = selectScope("SELECT t.* FROM t").outputs.single().expr as SqlExpr.Star
        assertEquals("t", qualified.qualifier)
    }

    /** 表达式形状：聚合 / cast / case / 算子 / 字面量（血缘只依赖列引用与结构，不依赖措辞）。 */
    @Test
    fun `表达式形状覆盖聚合 cast case 算子 字面量`() {
        val scope = selectScope(
            "SELECT CAST(SUM(o.amount) AS NUMERIC(10,2)) AS t, " +
                "CASE WHEN o.amount > 1 THEN 'a' ELSE 'b' END AS c, " +
                "COALESCE(o.amount, 0) AS k FROM orders o WHERE o.amount <= 5 AND o.id IS NOT NULL",
        )
        val total = scope.outputs[0].expr
        assertTrue(total is SqlExpr.Function && total.name == "cast", "cast 应保留列引用：$total")
        assertContains(total.referencedColumns().map { it.qualifiedName }, "o.amount")
        val case = scope.outputs[1].expr
        assertTrue(case is SqlExpr.Case, "应为 Case：$case")
        assertContains(case.referencedColumns().map { it.qualifiedName }, "o.amount")
        val coalesce = scope.outputs[2].expr
        assertEquals(listOf("o.amount"), coalesce.referencedColumns().map { it.qualifiedName })
        assertEquals(1, scope.filters.size)
    }

    // ————— 非 SELECT 语句 —————
    @Test
    fun `insert select 的目标与来源`() {
        val statement = analyze("INSERT INTO t (a, b) SELECT x, y FROM u")
        assertEquals(StatementKind.INSERT, statement.kind)
        assertEquals("t", statement.target?.name)
        assertEquals(listOf("a", "b"), statement.targetColumns.map { it.name })
        assertEquals("u", ((statement.query as SelectQuery).scope.sources.single() as TableSource).table.name)
    }

    @Test
    fun `create view 的显式列清单与查询体`() {
        val statement = analyze("CREATE VIEW v (x, y) AS SELECT a, b FROM t")
        assertEquals(StatementKind.CREATE_VIEW, statement.kind)
        assertEquals("v", statement.target?.name)
        assertEquals(listOf("x", "y"), statement.targetColumns.map { it.name })
        assertTrue(statement.query is SelectQuery)
    }

    @Test
    fun `create table as 与 update delete`() {
        assertEquals(StatementKind.CREATE_TABLE_AS, analyze("CREATE TABLE t2 AS SELECT a FROM t").kind)

        val update = analyze("UPDATE t SET a = 1, b = x WHERE c = 2")
        assertEquals(StatementKind.UPDATE, update.kind)
        assertEquals(listOf("a", "b"), update.assignments.map { it.target.name })
        assertTrue(update.assignments[1].value is SqlExpr.Column)

        val delete = analyze("DELETE FROM t WHERE a = 1")
        assertEquals(StatementKind.DELETE, delete.kind)
        assertEquals("t", delete.target?.name)
        assertTrue(delete.scope?.filters?.isNotEmpty() == true)
    }

    // ————— Never-wrong —————
    @Test
    fun `集合运算拒绝建模而不是漏分支`() {
        // QOM 的 `$select()` 在 UNION 上只给第一个分支——照它建模会静默漏掉其余分支。
        for (sql in listOf(
            "SELECT a FROM t UNION SELECT b FROM u",
            "SELECT a FROM t UNION ALL SELECT b FROM u",
            "SELECT a FROM t INTERSECT SELECT b FROM u",
        )) {
            val result = engine.analyze(sql, ParseRequest(dialect = "postgres"))
            assertTrue(result is Resolved.Unknown, "$sql 应被拒绝：${result::class.simpleName}")
        }
    }

    @Test
    fun `不可建模的种类跳过而不是硬塞`() {
        for (sql in listOf("MERGE INTO t USING s ON t.a = s.a WHEN MATCHED THEN UPDATE SET t.b = s.b", "CREATE TABLE t (a INT)")) {
            assertTrue(engine.analyze(sql, ParseRequest(dialect = "postgres")) is Resolved.Unknown, sql)
        }
        // 例外：jOOQ 把 `VALUES (1,2)` 解析成**无来源的 SELECT**（与 calcite / jsqlparser 不同）——
        // 模型是「两个常量输出、无来源」，不产血缘边，如实建模而不是硬判成 Unknown。
        val values = engine.analyze("VALUES (1, 2)", ParseRequest(dialect = "postgres"))
        assertTrue(values is Resolved.Known, "$values")
        assertEquals(emptyList(), ((values as Resolved.Known).value.query as SelectQuery).scope.sources)
    }

    @Test
    fun `认不出的表达式落 Unknown 且不猜列`() {
        // `CURRENT_DATE` 在 jOOQ 里是 niladic 函数（UEmpty 的 Field）——不产列引用
        val scope = selectScope("SELECT CURRENT_DATE AS d, x FROM t")
        assertEquals(emptyList(), scope.outputs[0].expr.referencedColumns())
        assertEquals(listOf("x"), scope.outputs[1].expr.referencedColumns().map { it.name })
    }

    @Test
    fun `analyzeAll 逐条提取并跳过不可建模的`() {
        val result = engine.analyzeAll(
            "SELECT a FROM t; INSERT INTO u (a) SELECT a FROM t; CREATE TABLE z (a INT);",
            ParseRequest(dialect = "postgres"),
        )
        assertTrue(result is Resolved.Known, "$result")
        assertEquals(
            listOf(StatementKind.SELECT, StatementKind.INSERT),
            (result as Resolved.Known).value.map { it.kind },
        )
    }

    @Test
    fun `analyze 只收单条 多语句给可解释错`() {
        val result = engine.analyze("SELECT 1; SELECT 2;", ParseRequest(dialect = "postgres"))
        assertTrue(result is Resolved.Unknown)
        assertContains((result as Resolved.Unknown).reason, "analyzeAll")
    }
}
