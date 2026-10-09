package io.github.workeron9.ozmoz.lineage.lineage

import io.github.workeron9.ozmoz.lineage.engine.semantics.Assignment
import io.github.workeron9.ozmoz.lineage.engine.semantics.CteSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.JoinSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.OutputItem
import io.github.workeron9.ozmoz.lineage.engine.semantics.QuerySpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.ScopeSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.SetOperationQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SourceSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.engine.semantics.SubquerySource
import io.github.workeron9.ozmoz.lineage.engine.semantics.TableSource
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageEdge
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind
import io.github.workeron9.ozmoz.lineage.ir.Span
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TransformKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    sources: List<SourceSpec> = emptyList(),
    joins: List<JoinSpec> = emptyList(),
    filters: List<SqlExpr> = emptyList(),
    outputs: List<OutputItem> = emptyList(),
) = ScopeSpec(kind = kind, sources = sources, joins = joins, filters = filters, outputs = outputs)

private fun select(
    scope: ScopeSpec,
    ctes: List<CteSpec> = emptyList(),
    raw: String = "SELECT ...",
): SelectQuery = SelectQuery(scope = scope, ctes = ctes, raw = raw)

private fun star(qualifier: String? = null) = OutputItem(SqlExpr.Star(qualifier, listOfNotNull(qualifier, "*").joinToString("."), null))

private val span = Span.of("0123456789", 0, 3)

private fun edgeOf(model: LineageModel, fromId: String, toId: String): LineageEdge =
    model.edges.single { it.fromColumn.id == fromId && it.toColumn.id == toId }

class LineageBuilderTest {

    // ——— 规则 C：单输出项分类 ———

    @Test
    fun `单表 SELECT a FROM t 产 DIRECT 边 t点a 到 a`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a")))),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertEquals(1, model.edges.size)
        val edge = edgeOf(model, "t.a", "a")
        assertEquals(EdgeKind.OUTPUT, edge.kind)
        assertEquals(TransformKind.DIRECT, edge.transform)
        assertEquals("a", edge.expression)

        val output = model.columns.single { it.column.id == "a" }
        assertTrue(output.isOutput)
        assertEquals("s0", output.scopeId)
        val upstream = model.columns.single { it.column.id == "t.a" }
        assertFalse(upstream.isOutput)
        assertEquals("s0", upstream.scopeId)
    }

    @Test
    fun `SELECT a 加 b AS x 产 2 条 EXPRESSION 边`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(
                        OutputItem(
                            SqlExpr.BinaryOp("+", SqlExpr.Column(col("a")), SqlExpr.Column(col("b")), raw = "a + b", span = span),
                            alias = "x",
                        ),
                    ),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertEquals(2, model.edges.size)
        for (from in listOf("t.a", "t.b")) {
            val edge = edgeOf(model, from, "x")
            assertEquals(EdgeKind.OUTPUT, edge.kind)
            assertEquals(TransformKind.EXPRESSION, edge.transform)
            assertEquals("a + b", edge.expression)
            assertEquals(span, edge.span)
        }
    }

    @Test
    fun `SUM 产 AGGREGATE 边 COUNT 星号 无上游不产边`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(
                        OutputItem(SqlExpr.Function("SUM", listOf(SqlExpr.Column(col("b"))), raw = "SUM(b)", span = null), alias = "s"),
                        OutputItem(SqlExpr.Function("COUNT", listOf(SqlExpr.Star(null, "*", null)), raw = "COUNT(*)", span = null), alias = "c"),
                    ),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        val sumEdge = edgeOf(model, "t.b", "s")
        assertEquals(TransformKind.AGGREGATE, sumEdge.transform)
        assertEquals("SUM(b)", sumEdge.expression)

        // COUNT(*)：聚合与上游无关，`*` 不给上游 → 无 OUTPUT 边，但列节点仍在。
        assertTrue(model.edges.none { it.toColumn.id == "c" })
        val node = model.columns.single { it.column.id == "c" }
        assertTrue(node.isOutput)
    }

    @Test
    fun `窗口函数产 WINDOW 边 上游含 partition 与 order 列`() {
        val window = SqlExpr.Window(
            function = SqlExpr.Function("ROW_NUMBER", emptyList(), raw = "ROW_NUMBER()", span = null),
            partitionBy = listOf(SqlExpr.Column(col("d"))),
            orderBy = listOf(SqlExpr.Column(col("o"))),
            raw = "ROW_NUMBER() OVER (PARTITION BY d ORDER BY o)",
            span = span,
        )
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(OutputItem(window, alias = "rn")),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertEquals(2, model.edges.size)
        for (from in listOf("t.d", "t.o")) {
            val edge = edgeOf(model, from, "rn")
            assertEquals(TransformKind.WINDOW, edge.transform)
            assertEquals("ROW_NUMBER() OVER (PARTITION BY d ORDER BY o)", edge.expression)
        }
    }

    @Test
    fun `CASE 表达式产 CASE_BRANCH 边 上游含条件与各分支`() {
        val case = SqlExpr.Case(
            operand = null,
            branches = listOf(
                SqlExpr.Case.Branch(
                    condition = SqlExpr.BinaryOp(">", SqlExpr.Column(col("a")), SqlExpr.Literal("0", null), raw = "a > 0", span = null),
                    result = SqlExpr.Column(col("b")),
                ),
            ),
            elseExpr = SqlExpr.Column(col("c")),
            raw = "CASE WHEN a > 0 THEN b ELSE c END",
            span = null,
        )
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(OutputItem(case, alias = "x")),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertEquals(3, model.edges.size)
        for (from in listOf("t.a", "t.b", "t.c")) {
            val edge = edgeOf(model, from, "x")
            assertEquals(TransformKind.CASE_BRANCH, edge.transform)
            assertEquals("CASE WHEN a > 0 THEN b ELSE c END", edge.expression)
        }
    }

    @Test
    fun `字面量输出是 CONSTANT 无边 但列节点存在`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(OutputItem(SqlExpr.Literal("1", null), alias = "x")),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertEquals(0, model.edges.size)
        val node = model.columns.single { it.column.id == "x" }
        assertTrue(node.isOutput)
        assertEquals("s0", node.scopeId)
    }

    // ——— 规则 A：CTE / 派生 / 显式列名 ———

    @Test
    fun `CTE 链产两条 DIRECT 边成链 t点a 到 c点x 到 x`() {
        val cteBody = select(
            selectScope(
                kind = ScopeKind.CTE,
                sources = listOf(TableSource(table("t"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("a")), alias = "x")),
            ),
            raw = "SELECT a AS x FROM t",
        )
        val outer = select(
            selectScope(
                sources = listOf(TableSource(table("c"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("x")))),
            ),
            ctes = listOf(CteSpec(name = "c", query = cteBody)),
        )

        val model = LineageBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))

        assertEquals(2, model.edges.size)
        for (edge in model.edges) {
            assertEquals(TransformKind.DIRECT, edge.transform)
        }
        edgeOf(model, "t.a", "c.x")
        edgeOf(model, "c.x", "x")

        val node = model.columns.single { it.column.id == "c.x" }
        assertTrue(node.isOutput)
        assertEquals("s1", node.scopeId)

        val outerScope = model.scopes.single { it.id == "s0" }
        assertEquals(listOf("c"), outerScope.sources.map { it.id })
        val cteScope = model.scopes.single { it.id == "s1" }
        assertEquals(listOf("c.x"), cteScope.outputs.map { it.id })
    }

    @Test
    fun `CTE 显式列名逐位命名 失配退回显式名`() {
        // 对齐：WITH c(p) AS (SELECT a + b FROM t) SELECT p FROM c
        val alignedCteBody = select(
            selectScope(
                kind = ScopeKind.CTE,
                sources = listOf(TableSource(table("t"))),
                outputs = listOf(
                    OutputItem(SqlExpr.BinaryOp("+", SqlExpr.Column(col("a")), SqlExpr.Column(col("b")), raw = "a + b", span = null)),
                ),
            ),
        )
        val alignedOuter = select(
            selectScope(sources = listOf(TableSource(table("c"))), outputs = listOf(OutputItem(SqlExpr.Column(col("p"))))),
            ctes = listOf(CteSpec(name = "c", columns = listOf("p"), query = alignedCteBody)),
        )
        val alignedModel = LineageBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = alignedOuter))

        assertEquals(2, alignedModel.edges.count { it.toColumn.id == "c.p" })
        val expression = edgeOf(alignedModel, "t.a", "c.p")
        assertEquals(TransformKind.EXPRESSION, expression.transform)
        assertEquals("c.p", expression.toColumn.canonical)
        assertEquals("c", expression.toColumn.table)
        assertEquals("a + b", expression.expression)
        edgeOf(alignedModel, "t.b", "c.p")
        edgeOf(alignedModel, "c.p", "p")

        // 失配：WITH c(p, q) AS (SELECT a + b AS s FROM t) SELECT s FROM c → 记 Unknown 并退回显式名
        val mismatchBody = select(
            selectScope(
                kind = ScopeKind.CTE,
                sources = listOf(TableSource(table("t"))),
                outputs = listOf(
                    OutputItem(SqlExpr.BinaryOp("+", SqlExpr.Column(col("a")), SqlExpr.Column(col("b")), raw = "a + b", span = null), alias = "s"),
                ),
            ),
        )
        val mismatch = select(
            selectScope(sources = listOf(TableSource(table("c"))), outputs = listOf(OutputItem(SqlExpr.Column(col("s"))))),
            ctes = listOf(CteSpec(name = "c", columns = listOf("p", "q"), query = mismatchBody)),
        )
        val mismatchModel = LineageBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = mismatch))

        assertTrue(mismatchModel.unknowns.any { it.reason == "CTE 显式列数与输出数不一致" })
        // 退回显式名 s：链路走 c.s，而不是猜 p / q 的位置。
        edgeOf(mismatchModel, "t.a", "c.s")
        edgeOf(mismatchModel, "t.b", "c.s")
        edgeOf(mismatchModel, "c.s", "s")
        assertTrue(mismatchModel.edges.none { it.toColumn.id == "c.p" || it.toColumn.id == "c.q" })
    }

    @Test
    fun `派生表星号展开成链 t点a 到 d点x 到 x`() {
        val sub: QuerySpec = select(
            selectScope(
                kind = ScopeKind.SUBQUERY,
                sources = listOf(TableSource(table("t"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("a")), alias = "x")),
            ),
        )
        val outer = select(
            selectScope(
                sources = listOf(SubquerySource(query = sub, alias = "d", raw = "(SELECT a AS x FROM t)")),
                outputs = listOf(star()),
            ),
        )

        val model = LineageBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))

        assertEquals(2, model.edges.size)
        edgeOf(model, "t.a", "d.x")
        val expansion = edgeOf(model, "d.x", "x")
        assertEquals(TransformKind.DIRECT, expansion.transform)
        assertEquals("*", expansion.expression)
    }

    // ——— 规则 D：`*` 展开 ———

    @Test
    fun `SELECT 星 FROM CTE 展开成 DIRECT 边`() {
        val cteBody = select(
            selectScope(
                kind = ScopeKind.CTE,
                sources = listOf(TableSource(table("base"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("a")))),
            ),
        )
        val outer = select(
            selectScope(sources = listOf(TableSource(table("c"))), outputs = listOf(star())),
            ctes = listOf(CteSpec(name = "c", query = cteBody)),
        )

        val model = LineageBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))

        val edge = edgeOf(model, "c.a", "a")
        assertEquals(TransformKind.DIRECT, edge.transform)
        assertEquals("*", edge.expression)
    }

    @Test
    fun `SELECT 星 FROM 物理表 记 SchemaProvider 未知且无 OUTPUT 边`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(selectScope(sources = listOf(TableSource(table("t"))), outputs = listOf(star()))),
        )

        val model = LineageBuilder.build(stmt)

        val physical = model.unknowns.single { it.reason.contains("SchemaProvider") }
        assertContains(physical.reason, "物理表 t")
        assertTrue(model.edges.none { it.kind == EdgeKind.OUTPUT })
    }

    @Test
    fun `物理表与 CTE 混查 CTE 展开 物理表记未知`() {
        val cteBody = select(
            selectScope(
                kind = ScopeKind.CTE,
                sources = listOf(TableSource(table("base"))),
                outputs = listOf(
                    OutputItem(SqlExpr.Column(col("a")), alias = "x"),
                    OutputItem(SqlExpr.Column(col("id"))),
                ),
            ),
        )
        val outer = select(
            selectScope(
                sources = listOf(TableSource(table("t"))),
                joins = listOf(
                    JoinSpec(
                        type = "INNER",
                        right = TableSource(table("c")),
                        on = listOf(
                            SqlExpr.BinaryOp("=", SqlExpr.Column(col("id", "t")), SqlExpr.Column(col("id", "c")), raw = "t.id = c.id", span = null),
                        ),
                    ),
                ),
                outputs = listOf(star()),
            ),
            ctes = listOf(CteSpec(name = "c", query = cteBody)),
        )

        val model = LineageBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))

        assertContains(model.unknowns.single { it.reason.contains("SchemaProvider") }.reason, "物理表 t")
        assertEquals(TransformKind.DIRECT, edgeOf(model, "c.x", "x").transform)
        assertEquals(TransformKind.DIRECT, edgeOf(model, "c.id", "id").transform)
        assertEquals(EdgeKind.JOIN_KEY, edgeOf(model, "t.id", "c.id").kind)
    }

    @Test
    fun `限定星号 CTE 命中展开 限定名未命中记 Unknown`() {
        val cteBody = select(
            selectScope(
                kind = ScopeKind.CTE,
                sources = listOf(TableSource(table("base"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("a")), alias = "x")),
            ),
        )

        fun outerWith(qualifier: String): SemanticStatement = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(sources = listOf(TableSource(table("c"))), outputs = listOf(star(qualifier))),
                ctes = listOf(CteSpec(name = "c", query = cteBody)),
            ),
        )

        val hit = LineageBuilder.build(outerWith("c"))
        val edge = edgeOf(hit, "c.x", "x")
        assertEquals(TransformKind.DIRECT, edge.transform)
        assertEquals("c.*", edge.expression)

        val miss = LineageBuilder.build(outerWith("z"))
        assertTrue(miss.unknowns.any { it.reason == "限定名未命中来源: z" })
        // 星号未展开：外层没有以裸名 x 为消费位的 OUTPUT 边（CTE 体自己的边不受影响）。
        assertTrue(miss.edges.none { it.toColumn.id == "x" })
    }

    // ——— 规则 A：写入目标（INSERT / CREATE） ———

    @Test
    fun `INSERT 目标列逐位对齐`() {
        val stmt = SemanticStatement(
            kind = StatementKind.INSERT,
            target = table("tgt"),
            targetColumns = listOf(col("x"), col("y")),
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("src"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a"))), OutputItem(SqlExpr.Column(col("b")))),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertEquals(2, model.edges.size)
        assertEquals(listOf("e0", "e1"), model.edges.map { it.id })
        val first = edgeOf(model, "src.a", "tgt.x")
        assertEquals(EdgeKind.OUTPUT, first.kind)
        assertEquals(TransformKind.DIRECT, first.transform)
        assertEquals("tgt.x", first.toColumn.canonical)
        assertEquals("tgt", first.toColumn.table)
        edgeOf(model, "src.b", "tgt.y")
    }

    @Test
    fun `INSERT 目标列数不一致 记 Unknown 不发 OUTPUT 边`() {
        val stmt = SemanticStatement(
            kind = StatementKind.INSERT,
            target = table("tgt"),
            targetColumns = listOf(col("x"), col("y"), col("z")),
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("src"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a"))), OutputItem(SqlExpr.Column(col("b")))),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertTrue(model.unknowns.any { it.reason == "INSERT 目标列数与查询输出数不一致" })
        assertTrue(model.edges.isEmpty())
    }

    @Test
    fun `INSERT 未声明目标列 记 Unknown 不发 OUTPUT 边`() {
        val stmt = SemanticStatement(
            kind = StatementKind.INSERT,
            target = table("tgt"),
            query = select(
                selectScope(sources = listOf(TableSource(table("src"))), outputs = listOf(OutputItem(SqlExpr.Column(col("a"))))),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertTrue(model.unknowns.any { it.reason == "INSERT 未声明目标列，需要 SchemaProvider" })
        assertTrue(model.edges.isEmpty())
    }

    @Test
    fun `INSERT 顶层集合运算 每个分支逐位对齐目标列`() {
        val branch1 = select(
            selectScope(
                kind = ScopeKind.UNION_BRANCH,
                sources = listOf(TableSource(table("src1"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("a"))), OutputItem(SqlExpr.Column(col("b")))),
            ),
        )
        val branch2 = select(
            selectScope(
                kind = ScopeKind.UNION_BRANCH,
                sources = listOf(TableSource(table("src2"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("c"))), OutputItem(SqlExpr.Column(col("d")))),
            ),
        )
        val union = SetOperationQuery(
            op = "UNION",
            branches = listOf(branch1, branch2),
            raw = "SELECT a, b FROM src1 UNION SELECT c, d FROM src2",
        )
        val stmt = SemanticStatement(
            kind = StatementKind.INSERT,
            target = table("tgt"),
            targetColumns = listOf(col("x"), col("y")),
            query = union,
        )

        val model = LineageBuilder.build(stmt)

        assertEquals(4, model.edges.size)
        edgeOf(model, "src1.a", "tgt.x")
        edgeOf(model, "src1.b", "tgt.y")
        edgeOf(model, "src2.c", "tgt.x")
        edgeOf(model, "src2.d", "tgt.y")
        // 两个分支作用域的 outputs 都逐位对齐了 targetColumns。
        val branches = model.scopes.filter { it.kind == ScopeKind.UNION_BRANCH }
        assertEquals(2, branches.size)
        for (branch in branches) {
            assertEquals(listOf("tgt.x", "tgt.y"), branch.outputs.map { it.id })
        }
    }

    @Test
    fun `CTE 覆盖 UNION 两分支位对齐 两条边都进 c点a`() {
        val branch1 = select(
            selectScope(
                kind = ScopeKind.UNION_BRANCH,
                sources = listOf(TableSource(table("t1"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("a")))),
            ),
        )
        val branch2 = select(
            selectScope(
                kind = ScopeKind.UNION_BRANCH,
                sources = listOf(TableSource(table("t2"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("b")))),
            ),
        )
        val cteBody: QuerySpec = SetOperationQuery(
            op = "UNION",
            branches = listOf(branch1, branch2),
            raw = "SELECT a FROM t1 UNION SELECT b FROM t2",
        )
        val outer = select(
            selectScope(sources = listOf(TableSource(table("c"))), outputs = listOf(OutputItem(SqlExpr.Column(col("a"))))),
            ctes = listOf(CteSpec(name = "c", query = cteBody)),
        )

        val model = LineageBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))

        assertEquals(2, model.edges.count { it.toColumn.id == "c.a" })
        edgeOf(model, "t1.a", "c.a")
        edgeOf(model, "t2.b", "c.a")
        edgeOf(model, "c.a", "a")
    }

    @Test
    fun `CREATE VIEW 目标限定消费位 无名输出跳过`() {
        val view = SemanticStatement(
            kind = StatementKind.CREATE_VIEW,
            target = table("v"),
            query = select(selectScope(sources = listOf(TableSource(table("t"))), outputs = listOf(OutputItem(SqlExpr.Column(col("a")))))),
        )
        val viewModel = LineageBuilder.build(view)

        val edge = edgeOf(viewModel, "t.a", "v.a")
        assertEquals("v", edge.toColumn.table)
        assertEquals("v.a", edge.toColumn.canonical)

        // 无名输出跳过（「输出列没有名字」由 ScopeTreeBuilder 记，这里不重复记）。
        val unnamed = SemanticStatement(
            kind = StatementKind.CREATE_VIEW,
            target = table("v"),
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(OutputItem(SqlExpr.BinaryOp("+", SqlExpr.Column(col("a")), SqlExpr.Column(col("b")), raw = "a + b", span = null))),
                ),
            ),
        )
        val unnamedModel = LineageBuilder.build(unnamed)

        assertTrue(unnamedModel.edges.isEmpty())
        assertTrue(unnamedModel.unknowns.any { it.reason == "输出列没有名字" })

        // CTAS 带目标列 → 同 INSERT 逐位对齐。
        val ctas = SemanticStatement(
            kind = StatementKind.CREATE_TABLE_AS,
            target = table("tgt2"),
            targetColumns = listOf(col("x")),
            query = select(selectScope(sources = listOf(TableSource(table("src"))), outputs = listOf(OutputItem(SqlExpr.Column(col("a")))))),
        )
        val ctasModel = LineageBuilder.build(ctas)

        assertEquals("tgt2.x", edgeOf(ctasModel, "src.a", "tgt2.x").toColumn.id)
    }

    // ——— 规则 A：UPDATE / DELETE ———

    @Test
    fun `UPDATE 用赋值作输出槽 产 OUTPUT 与 PREDICATE 边`() {
        // 等价于 UPDATE tgt SET x = src.y FROM src WHERE src.y > 0：
        // 适配器把目标表与 FROM 来源都放进 sources，赋值与 WHERE 走 assignments / filters。
        val stmt = SemanticStatement(
            kind = StatementKind.UPDATE,
            target = table("tgt"),
            assignments = listOf(Assignment(target = col("x"), value = SqlExpr.Column(col("y", "src")))),
            scope = selectScope(
                kind = ScopeKind.UPDATE,
                sources = listOf(TableSource(table("tgt")), TableSource(table("src"))),
                filters = listOf(
                    SqlExpr.BinaryOp(">", SqlExpr.Column(col("y", "src")), SqlExpr.Literal("0", null), raw = "src.y > 0", span = span),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        val outputEdge = model.edges.single { it.kind == EdgeKind.OUTPUT }
        assertEquals("src.y", outputEdge.fromColumn.id)
        assertEquals("tgt.x", outputEdge.toColumn.id)
        assertEquals(TransformKind.DIRECT, outputEdge.transform)
        assertEquals("tgt.x", outputEdge.toColumn.canonical)

        val predicateEdge = model.edges.single { it.kind == EdgeKind.PREDICATE }
        assertEquals("src.y", predicateEdge.fromColumn.id)
        assertEquals("tgt.x", predicateEdge.toColumn.id)
        assertEquals(TransformKind.FILTER_PREDICATE, predicateEdge.transform)
        assertEquals("src.y > 0", predicateEdge.expression)
        assertEquals(span, predicateEdge.span)

        // 作用域 outputs = assignment refs。
        val scopeNode = model.scopes.single()
        assertEquals(ScopeKind.UPDATE, scopeNode.kind)
        assertEquals(listOf("tgt.x"), scopeNode.outputs.map { it.id })
        val node = model.columns.single { it.column.id == "tgt.x" }
        assertTrue(node.isOutput)
    }

    @Test
    fun `DELETE 无输出 不产任何边 不崩`() {
        val stmt = SemanticStatement(
            kind = StatementKind.DELETE,
            target = table("t"),
            scope = selectScope(
                kind = ScopeKind.DELETE,
                sources = listOf(TableSource(table("t"))),
                filters = listOf(SqlExpr.BinaryOp("=", SqlExpr.Column(col("a")), SqlExpr.Literal("1", null), raw = "a = 1", span = null)),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertEquals(1, model.scopes.size)
        assertEquals(ScopeKind.DELETE, model.scopes.single().kind)
        assertTrue(model.scopes.single().outputs.isEmpty())
        // 作用域无输出列 → 不发 PREDICATE 边；WHERE 引用可解析，不新增 unknown。
        assertTrue(model.edges.isEmpty())
        assertTrue(model.columns.isEmpty())
        assertTrue(model.unknowns.isEmpty())
    }

    // ——— 规则 E：JOIN_KEY ———

    @Test
    fun `ON 等值条件产 JOIN_KEY 边与 2 条 OUTPUT 边`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("x"))),
                    joins = listOf(
                        JoinSpec(
                            type = "INNER",
                            right = TableSource(table("y")),
                            on = listOf(
                                SqlExpr.BinaryOp("=", SqlExpr.Column(col("id", "x")), SqlExpr.Column(col("id", "y")), raw = "x.id = y.id", span = null),
                            ),
                            span = span,
                        ),
                    ),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a", "x"))), OutputItem(SqlExpr.Column(col("b", "y")))),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertEquals(3, model.edges.size)
        val joinEdge = model.edges.single { it.kind == EdgeKind.JOIN_KEY }
        assertEquals("x.id", joinEdge.fromColumn.id)
        assertEquals("y.id", joinEdge.toColumn.id)
        assertEquals(TransformKind.JOIN_KEY, joinEdge.transform)
        assertNull(joinEdge.expression)
        assertEquals(span, joinEdge.span)

        assertEquals(2, model.edges.count { it.kind == EdgeKind.OUTPUT })
        edgeOf(model, "x.a", "a")
        edgeOf(model, "y.b", "b")
    }

    @Test
    fun `USING 列产 JOIN_KEY 边`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("x"))),
                    joins = listOf(
                        JoinSpec(type = "INNER", right = TableSource(table("y")), using = listOf("id"), span = span),
                    ),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a", "x")))),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        val joinEdge = model.edges.single { it.kind == EdgeKind.JOIN_KEY }
        assertEquals("x.id", joinEdge.fromColumn.id)
        assertEquals("y.id", joinEdge.toColumn.id)
        assertNull(joinEdge.expression)
        assertEquals(span, joinEdge.span)
        edgeOf(model, "x.a", "a")
    }

    @Test
    fun `带 schema 与别名的 JOIN 端点表身份用限定名`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t1", alias = "a", schema = "public"))),
                    joins = listOf(
                        JoinSpec(
                            type = "INNER",
                            right = TableSource(table("t2", alias = "b")),
                            on = listOf(
                                SqlExpr.BinaryOp("=", SqlExpr.Column(col("id", "a")), SqlExpr.Column(col("id", "b")), raw = "a.id = b.id", span = null),
                            ),
                        ),
                    ),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("v", "a")))),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        val joinEdge = model.edges.single { it.kind == EdgeKind.JOIN_KEY }
        assertEquals("public.t1.id", joinEdge.fromColumn.id)
        assertEquals("t2.id", joinEdge.toColumn.id)
        edgeOf(model, "public.t1.v", "v")
    }

    @Test
    fun `ON 非等值条件的列引用按 PREDICATE 处理`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t"))),
                    joins = listOf(
                        JoinSpec(
                            type = "INNER",
                            right = TableSource(table("t2")),
                            on = listOf(
                                SqlExpr.BinaryOp(">", SqlExpr.Column(col("a", "t")), SqlExpr.Column(col("b", "t2")), raw = "t.a > t2.b", span = span),
                            ),
                        ),
                    ),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a", "t")), alias = "x")),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertEquals(3, model.edges.size)
        assertTrue(model.edges.none { it.kind == EdgeKind.JOIN_KEY })
        val predicates = model.edges.filter { it.kind == EdgeKind.PREDICATE }
        assertEquals(setOf("t.a", "t2.b"), predicates.map { it.fromColumn.id }.toSet())
        for (predicate in predicates) {
            assertEquals("x", predicate.toColumn.id)
            assertEquals("t.a > t2.b", predicate.expression)
            assertEquals(span, predicate.span)
        }
    }

    // ——— 规则 B：裸列名归属 ———

    @Test
    fun `两个来源都有该列时裸名记无法唯一归属 唯一物理表则归属该表`() {
        val cte1 = select(
            selectScope(
                kind = ScopeKind.CTE,
                sources = listOf(TableSource(table("t1"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("id")))),
            ),
        )
        val cte2 = select(
            selectScope(
                kind = ScopeKind.CTE,
                sources = listOf(TableSource(table("t2"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("id")))),
            ),
        )
        val outer = select(
            selectScope(
                sources = listOf(TableSource(table("c1")), TableSource(table("c2"))),
                outputs = listOf(OutputItem(SqlExpr.Column(col("id")))),
            ),
            ctes = listOf(CteSpec(name = "c1", query = cte1), CteSpec(name = "c2", query = cte2)),
        )

        val model = LineageBuilder.build(SemanticStatement(kind = StatementKind.SELECT, query = outer))

        assertTrue(model.unknowns.any { it.reason == "列名无法唯一归属: id" })
        assertTrue(model.edges.none { it.kind == EdgeKind.OUTPUT && it.toColumn.id == "id" })

        // 唯一来源是物理表：无法证伪，但来源唯一 → 归属该表。
        val sole = LineageBuilder.build(
            SemanticStatement(
                kind = StatementKind.SELECT,
                query = select(
                    selectScope(sources = listOf(TableSource(table("t"))), outputs = listOf(OutputItem(SqlExpr.Column(col("a"))))),
                ),
            ),
        )
        assertEquals("t.a", sole.edges.single().fromColumn.id)
    }

    @Test
    fun `WHERE 引用解析失败 记 Unknown 不发 PREDICATE 边`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = select(
                selectScope(
                    sources = listOf(TableSource(table("t"))),
                    filters = listOf(SqlExpr.BinaryOp("=", SqlExpr.Column(col("q", "z")), SqlExpr.Literal("1", null), raw = "z.q = 1", span = span)),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a")), alias = "x")),
                ),
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertTrue(model.unknowns.any { it.reason == "限定名未命中来源: z" })
        assertEquals(1, model.edges.size)
        assertEquals(EdgeKind.OUTPUT, model.edges.single().kind)
    }

    // ——— 规则 F / G：组装与确定性 ———

    @Test
    fun `只有诊断的语句产空模型 诊断透传`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            diagnostics = listOf(Diagnostic.warning("parse.failed", "解析失败")),
        )

        val model = LineageBuilder.build(stmt)

        assertTrue(model.scopes.isEmpty())
        assertTrue(model.edges.isEmpty())
        assertTrue(model.columns.isEmpty())
        assertEquals(listOf("解析失败"), model.unknowns.map { it.reason })
        assertEquals(1, model.diagnostics.size)
        assertEquals("解析失败", model.diagnostics.single().message)
    }

    @Test
    fun `同一输入两次构建产出相等模型`() {
        fun statement(): SemanticStatement {
            val cteBody = select(
                selectScope(
                    kind = ScopeKind.CTE,
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a")), alias = "x")),
                ),
                raw = "SELECT a AS x FROM t",
            )
            return SemanticStatement(
                kind = StatementKind.SELECT,
                query = select(
                    selectScope(
                        sources = listOf(TableSource(table("c"))),
                        filters = listOf(SqlExpr.BinaryOp(">", SqlExpr.Column(col("x")), SqlExpr.Literal("0", null), raw = "x > 0", span = null)),
                        outputs = listOf(OutputItem(SqlExpr.Column(col("x")), alias = "y")),
                    ),
                    ctes = listOf(CteSpec(name = "c", query = cteBody)),
                ),
            )
        }

        val first = LineageBuilder.build(statement())
        val second = LineageBuilder.build(statement())

        assertEquals(first, second)
        // 边 id 按分配顺序严格递增。
        assertEquals(first.edges.map { it.id }, first.edges.indices.map { "e$it" })
    }
}
