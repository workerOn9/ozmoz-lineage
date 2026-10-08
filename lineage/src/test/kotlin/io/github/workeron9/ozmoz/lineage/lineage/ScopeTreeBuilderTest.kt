package io.github.workeron9.ozmoz.lineage.lineage

import io.github.workeron9.ozmoz.lineage.engine.semantics.Assignment
import io.github.workeron9.ozmoz.lineage.engine.semantics.CteSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.JoinSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.NestedSource
import io.github.workeron9.ozmoz.lineage.engine.semantics.OutputItem
import io.github.workeron9.ozmoz.lineage.engine.semantics.QuerySpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.ScopeSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.SetOperationQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.engine.semantics.SubquerySource
import io.github.workeron9.ozmoz.lineage.engine.semantics.TableSource
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind
import io.github.workeron9.ozmoz.lineage.ir.Span
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun col(name: String, table: String? = null) = ColumnRef(
    raw = name,
    canonical = listOfNotNull(table, name).joinToString(".").lowercase(),
    name = name,
    table = table,
)

private fun table(name: String, alias: String? = null, schema: String? = null) = TableRef(
    raw = listOfNotNull(schema, name).joinToString("."),
    canonical = listOfNotNull(schema, name).joinToString(".").lowercase(),
    schema = schema,
    name = name,
    alias = alias,
)

private fun selectScope(
    kind: ScopeKind = ScopeKind.SELECT,
    sources: List<io.github.workeron9.ozmoz.lineage.engine.semantics.SourceSpec> = emptyList(),
    joins: List<JoinSpec> = emptyList(),
    outputs: List<OutputItem> = emptyList(),
) = ScopeSpec(kind = kind, sources = sources, joins = joins, outputs = outputs)

private fun select(
    scope: ScopeSpec,
    ctes: List<CteSpec> = emptyList(),
    raw: String = "SELECT ...",
): SelectQuery = SelectQuery(scope = scope, ctes = ctes, raw = raw)

private val span = Span.of("0123456789", 0, 3)

class ScopeTreeBuilderTest {

    @Test
    fun `单表 SELECT 建出根作用域与表来源`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("orders", alias = "o"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("id", "o")))),
                ),
            ),
        )

        val tree = ScopeTreeBuilder.build(stmt)

        assertEquals("s0", tree.rootScopeId)
        assertEquals(1, tree.scopes.size)
        val root = tree.scope("s0")!!
        assertEquals(ScopeKind.SELECT, root.kind)
        assertNull(root.parentId)
        val source = assertIs<ScopeSource.Table>(root.sources.single())
        assertEquals("o", source.alias)
        assertEquals("orders", source.ref.name)
        assertEquals(listOf("id"), root.outputs.map { it.name })
        assertEquals(emptyList(), tree.unknowns)
    }

    @Test
    fun `SELECT a, b AS x FROM t 输出 a 与 x`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(
                        OutputItem(SqlExpr.Column(col("a"))),
                        OutputItem(SqlExpr.Column(col("b")), alias = "x"),
                    ),
                ),
            ),
        )

        val root = ScopeTreeBuilder.build(stmt).scope("s0")!!
        assertEquals(listOf("a", "x"), root.outputs.map { it.name })
        assertEquals(listOf("a", "x"), root.outputs.map { it.canonical })
    }

    @Test
    fun `CTE 被外层来源解析为 Cte 且 scopeId 指向 CTE 作用域`() {
        val cteQuery = select(
            selectScope(
                kind = ScopeKind.CTE,
                sources = listOf(TableSource(table("base"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("v")))),
            ),
            raw = "SELECT v FROM base",
        )
        val outer = select(
            selectScope(
                sources = listOf(TableSource(table("c"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("v")))),
            ),
            ctes = listOf(CteSpec(name = "c", query = cteQuery)),
        )

        val tree = ScopeTreeBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))

        assertEquals("s0", tree.rootScopeId)
        val outerScope = tree.scope("s0")!!
        val cte = assertIs<ScopeSource.Cte>(outerScope.sources.single())
        assertEquals("c", cte.name)
        assertEquals("s1", cte.scopeId)

        val cteScope = tree.scope(cte.scopeId!!)!!
        assertEquals(ScopeKind.CTE, cteScope.kind)
        assertIs<ScopeSource.Table>(cteScope.sources.single())
    }

    @Test
    fun `子查询解析为 Derived 且 parentId 连到外层`() {
        val subQuery: QuerySpec = select(
            selectScope(
                kind = ScopeKind.SUBQUERY,
                sources = listOf(TableSource(table("inner_t"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("x")))),
            ),
        )
        val outer = select(
            selectScope(
                sources = listOf(SubquerySource(query = subQuery, alias = "sub", raw = "(SELECT x FROM inner_t)")),
                outputs = listOf(OutputItem(SqlExpr.Column(col("x", "sub")))),
            ),
        )

        val tree = ScopeTreeBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))

        val outerScope = tree.scope("s0")!!
        val derived = assertIs<ScopeSource.Derived>(outerScope.sources.single())
        assertEquals("sub", derived.alias)
        assertEquals("s1", derived.scopeId)

        val subScope = tree.scope(derived.scopeId)!!
        assertEquals(ScopeKind.SUBQUERY, subScope.kind)
        assertEquals("s0", subScope.parentId)
        assertIs<ScopeSource.Table>(subScope.sources.single())
    }

    @Test
    fun `UNION 各分支为 UNION_BRANCH 且都能从根抵达`() {
        val branch1 = select(
            selectScope(kind = ScopeKind.UNION_BRANCH, sources = listOf(TableSource(table("a")))),
        )
        val branch2 = select(
            selectScope(kind = ScopeKind.UNION_BRANCH, sources = listOf(TableSource(table("b")))),
        )
        val union = SetOperationQuery(
            op = "UNION ALL",
            branches = listOf(branch1, branch2),
            raw = "SELECT ... UNION ALL SELECT ...",
        )

        val tree = ScopeTreeBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = union))

        assertEquals("s0", tree.rootScopeId)
        val container = tree.scope("s0")!!
        assertEquals(ScopeKind.ROOT, container.kind)
        assertNull(container.parentId)

        val branches = tree.scopes.filter { it.kind == ScopeKind.UNION_BRANCH }
        assertEquals(2, branches.size)
        for (b in branches) {
            assertEquals("s0", b.parentId)
            assertTrue(tree.scope(b.parentId!!) != null)
        }
        assertEquals(listOf("s0", "s1", "s2"), tree.scopes.map { it.id })
    }

    @Test
    fun `UPDATE 只建一个作用域 赋值不产生输出`() {
        val stmt = SemanticStatement(
            kind = StatementKind.UPDATE,
            target = table("t"),
            assignments = listOf(Assignment(target = col("a"), value = SqlExpr.Literal("1", null))),
            scope = selectScope(
                kind = ScopeKind.UPDATE,
                sources = listOf(TableSource(table("t"))),
            ),
        )

        val tree = ScopeTreeBuilder.build(stmt)

        assertEquals("s0", tree.rootScopeId)
        assertEquals(1, tree.scopes.size)
        val scope = tree.scope("s0")!!
        assertEquals(ScopeKind.UPDATE, scope.kind)
        assertIs<ScopeSource.Table>(scope.sources.single())
        assertEquals(emptyList(), scope.outputs)
    }

    @Test
    fun `无名输出表达式不猜列名 记 UnknownEntry`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(
                        OutputItem(SqlExpr.Column(col("a"))),
                        OutputItem(
                            SqlExpr.Function("SUM", listOf(SqlExpr.Column(col("b"))), "SUM(b)", null),
                            span = span,
                        ),
                    ),
                ),
            ),
        )

        val tree = ScopeTreeBuilder.build(stmt)

        val root = tree.scope("s0")!!
        assertEquals(listOf("a"), root.outputs.map { it.name })
        val unknown = tree.unknowns.single()
        assertEquals("输出列没有名字", unknown.reason)
        assertEquals(span, unknown.span)
    }

    @Test
    fun `作用域 id 唯一且确定`() {
        val cteQuery = select(
            selectScope(kind = ScopeKind.CTE, sources = listOf(TableSource(table("base")))),
        )
        val outer = select(
            selectScope(
                sources = listOf(
                    TableSource(table("c")),
                    SubquerySource(
                        query = select(selectScope(kind = ScopeKind.SUBQUERY, sources = listOf(TableSource(table("d"))))),
                        alias = "s",
                        raw = "(SELECT * FROM d)",
                    ),
                ),
            ),
            ctes = listOf(CteSpec(name = "c", query = cteQuery)),
        )

        val tree = ScopeTreeBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))

        assertEquals(listOf("s0", "s1", "s2"), tree.scopes.map { it.id })
        assertEquals(tree.scopes.map { it.id }.toSet().size, tree.scopes.size)
        // 再跑一次，id 完全一致。
        val again = ScopeTreeBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))
        assertEquals(tree.scopes.map { it.id }, again.scopes.map { it.id })
    }

    @Test
    fun `只有诊断的语句返回空树并把原因透出`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            diagnostics = listOf(Diagnostic.warning("lineage.unsupported", "暂不支持该语句")),
        )

        val tree = ScopeTreeBuilder.build(stmt)

        assertEquals(emptyList(), tree.scopes)
        assertNull(tree.rootScopeId)
        assertEquals(listOf("暂不支持该语句"), tree.unknowns.map { it.reason })
    }

    @Test
    fun `NestedSource 的 joins 归入当前作用域`() {
        val nested = NestedSource(
            from = TableSource(table("t1")),
            joins = listOf(
                JoinSpec(type = "INNER", right = TableSource(table("t2"))),
            ),
            alias = null,
            raw = "(t1 JOIN t2 ON t1.id = t2.id)",
        )
        val outer = select(
            selectScope(
                sources = listOf(nested),
                outputs = listOf(OutputItem(SqlExpr.Column(col("t1", "t1")))),
            ),
        )

        val tree = ScopeTreeBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))

        val root = tree.scope("s0")!!
        assertEquals(2, root.sources.size)
        assertEquals(listOf("t1", "t2"), root.sources.map { (it as ScopeSource.Table).ref.name })
    }

    @Test
    fun `带 schema 限定的名字不命中同名 CTE`() {
        val cteQuery = select(selectScope(kind = ScopeKind.CTE, sources = listOf(TableSource(table("base")))))
        val outer = select(
            selectScope(sources = listOf(TableSource(table("c", schema = "db")))),
            ctes = listOf(CteSpec(name = "c", query = cteQuery)),
        )

        val tree = ScopeTreeBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))

        assertIs<ScopeSource.Table>(tree.scope("s0")!!.sources.single())
    }
}
