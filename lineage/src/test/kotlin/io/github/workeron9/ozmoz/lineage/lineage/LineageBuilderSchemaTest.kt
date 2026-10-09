package io.github.workeron9.ozmoz.lineage.lineage

import io.github.workeron9.ozmoz.lineage.engine.semantics.OutputItem
import io.github.workeron9.ozmoz.lineage.engine.semantics.ScopeSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.engine.semantics.TableSource
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.ColumnSchema
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TableSchema
import io.github.workeron9.ozmoz.lineage.ir.TransformKind
import io.github.workeron9.ozmoz.lineage.schema.StaticSchemaProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * schema 提供方接入后的列级血缘行为（规则 B / D / A 的 schema 分支）：
 * 物理表 `*` 展开、裸列歧义消解（可证伪）、限定引用可证伪、
 * `INSERT` 未声明目标列按表列定义序对齐。
 */
class LineageBuilderSchemaTest {

    private fun col(name: String, table: String? = null) = ColumnRef(
        raw = name,
        canonical = listOfNotNull(table, name).joinToString(".").lowercase(),
        name = name,
        table = table,
    )

    private fun table(name: String, alias: String? = null) = TableRef(
        raw = name,
        canonical = name.lowercase(),
        name = name,
        alias = alias,
    )

    private fun selectStarFrom(vararg sources: TableRef) = SemanticStatement(
        kind = StatementKind.SELECT,
        query = SelectQuery(
            scope = ScopeSpec(
                kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                sources = sources.map { TableSource(it) },
                outputs = listOf(OutputItem(SqlExpr.Star(null, "*", null))),
            ),
            raw = "SELECT * FROM ...",
        ),
    )

    private fun schemaOf(vararg tables: Pair<String, List<String>>): StaticSchemaProvider =
        StaticSchemaProvider(
            tables.map { (name, columns) ->
                TableSchema(
                    table(name),
                    columns.mapIndexed { i, column -> ColumnSchema(column, ordinal = i + 1) },
                )
            },
            id = "test-schema",
        )

    // ——— 类型补全：ColumnNode.type / nullable ———

    @Test
    fun `物理表端点的列节点补全类型与可空性 裸输出列留 null`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a")))),
                ),
                raw = "SELECT a FROM t",
            ),
        )
        val schema = StaticSchemaProvider(
            listOf(
                TableSchema(
                    table("t"),
                    listOf(
                        ColumnSchema("a", type = "INT", nullable = false, ordinal = 1),
                        ColumnSchema("b", type = "DECIMAL (7, 2)", nullable = true, ordinal = 2),
                    ),
                ),
            ),
            id = "test-schema",
        )

        val model = LineageBuilder.build(stmt, schema)

        val physical = model.columns.single { it.column.id == "t.a" }
        assertEquals("INT", physical.type)
        assertEquals(false, physical.nullable)
        // 裸输出列没有表身份 → 查不到 → 留 null（Never-wrong：不猜类型）。
        val bare = model.columns.single { it.column.id == "a" }
        assertNull(bare.type)
        assertNull(bare.nullable)
    }

    @Test
    fun `无 schema 时列节点类型一律为 null`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a")))),
                ),
                raw = "SELECT a FROM t",
            ),
        )

        val model = LineageBuilder.build(stmt)

        assertTrue(model.columns.all { it.type == null && it.nullable == null })
    }

    // ——— 规则 D：物理表 `*` 展开 ———

    @Test
    fun `物理表星号按 schema 列清单展开为 DIRECT 边`() {
        val model = LineageBuilder.build(selectStarFrom(table("t")), schemaOf("t" to listOf("a", "b")))

        // 2 条 OUTPUT（星号展开）+ 2 条 SOURCE（t -> a、t -> b）。
        assertEquals(4, model.edges.size)
        val outputs = model.edges.filter { it.kind == EdgeKind.OUTPUT }
        assertTrue(outputs.all { it.transform == TransformKind.DIRECT })
        assertEquals(listOf("t.a", "t.b"), outputs.map { it.fromColumn.qualifiedName })
        assertEquals(listOf("a", "b"), outputs.map { it.toColumn.qualifiedName })
        assertEquals("*", outputs[0].expression) // 星号原文（Lossless）
        assertTrue(model.unknowns.none { it.reason.contains("SchemaProvider") })
    }

    @Test
    fun `限定星号按 schema 展开且别名限定生效`() {
        val model = LineageBuilder.build(
            selectStarFrom(table("s", alias = "src"), table("u", alias = "dim")),
            schemaOf("s" to listOf("x"), "u" to listOf("y")),
        )

        // 无限定 `*` 按来源顺序展开两张物理表；另有 4 条 SOURCE（s / u 各自 -> x、y）。
        assertEquals(6, model.edges.size)
        val outputs = model.edges.filter { it.kind == EdgeKind.OUTPUT }
        assertEquals(listOf("s.x", "u.y"), outputs.map { it.fromColumn.qualifiedName })
        assertEquals(4, model.edges.count { it.kind == EdgeKind.SOURCE })
    }

    @Test
    fun `schema 未收录该物理表 记未收录措辞 unknown 且不产边`() {
        val model = LineageBuilder.build(selectStarFrom(table("missing")), schemaOf("t" to listOf("a")))

        assertTrue(model.edges.isEmpty())
        assertTrue(model.unknowns.any { it.reason.contains("不在 schema 提供方 test-schema 中") })
    }

    @Test
    fun `CTE 内的物理表星号也能展开`() {
        val cte = io.github.workeron9.ozmoz.lineage.engine.semantics.CteSpec(
            name = "c",
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                    sources = listOf(TableSource(table("t"))),
                    outputs = listOf(OutputItem(SqlExpr.Star(null, "*", null))),
                ),
                raw = "SELECT * FROM t",
            ),
        )
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                    sources = listOf(TableSource(table("c"))),
                    outputs = listOf(OutputItem(SqlExpr.Star(null, "*", null))),
                ),
                ctes = listOf(cte),
                raw = "WITH c AS (...) SELECT * FROM c",
            ),
        )

        val model = LineageBuilder.build(stmt, schemaOf("t" to listOf("a")))

        // 链：t.a -> c.a（CTE 体星号）、c.a -> a（顶层星号）；
        // 另有 2 条 SOURCE（t -> c.a、c -> a）。
        // 不断言边的先后顺序（那是作用域 id 分配顺序的实现细节）。
        assertEquals(4, model.edges.size)
        val outputs = model.edges.filter { it.kind == EdgeKind.OUTPUT }
        val byFrom = outputs.associateBy { it.fromColumn.qualifiedName }
        assertEquals("c.a", byFrom.getValue("t.a").toColumn.qualifiedName)
        assertEquals("a", byFrom.getValue("c.a").toColumn.qualifiedName)
        assertEquals(2, model.edges.count { it.kind == EdgeKind.SOURCE })
    }

    // ——— 规则 B：裸列歧义消解（schema 可证伪） ———

    @Test
    fun `裸列名只在一张物理表存在时消解歧义`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                    sources = listOf(TableSource(table("t1")), TableSource(table("t2"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("c")))),
                ),
                raw = "SELECT c FROM t1, t2",
            ),
        )

        val ambiguous = LineageBuilder.build(stmt) // 无 schema：两物理来源恒为候选 → 歧义
        // 裸列歧义 → 无 OUTPUT 边，但两个物理来源仍各发 1 条 SOURCE 边（t1 -> c、t2 -> c）。
        assertTrue(ambiguous.edges.none { it.kind == EdgeKind.OUTPUT })
        assertTrue(ambiguous.unknowns.any { it.reason.contains("无法唯一归属") })

        val resolved = LineageBuilder.build(stmt, schemaOf("t1" to listOf("a"), "t2" to listOf("b", "c")))
        val output = resolved.edges.single { it.kind == EdgeKind.OUTPUT }
        assertEquals("t2.c", output.fromColumn.qualifiedName)
        assertTrue(resolved.unknowns.none { it.reason.contains("无法唯一归属") })
    }

    @Test
    fun `两张物理表都有该列时 schema 也不猜`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                    sources = listOf(TableSource(table("t1")), TableSource(table("t2"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("id")))),
                ),
                raw = "SELECT id FROM t1, t2",
            ),
        )

        val model = LineageBuilder.build(stmt, schemaOf("t1" to listOf("id"), "t2" to listOf("id")))
        // 两张表都有该列 → 不猜，无 OUTPUT 边；SOURCE 边仍照发。
        assertTrue(model.edges.none { it.kind == EdgeKind.OUTPUT })
        assertTrue(model.unknowns.any { it.reason.contains("无法唯一归属") })
    }

    @Test
    fun `schema 收录的表没有该列 裸列名零候选记没有列`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                    sources = listOf(TableSource(table("t1"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("zzz")))),
                ),
                raw = "SELECT zzz FROM t1",
            ),
        )

        val model = LineageBuilder.build(stmt, schemaOf("t1" to listOf("a")))
        // 裸列零候选 → 无 OUTPUT 边；物理来源仍发 1 条 SOURCE 边（t1 -> zzz）。
        assertTrue(model.edges.none { it.kind == EdgeKind.OUTPUT })
        assertTrue(model.unknowns.any { it.reason == "来源没有列: zzz" })
    }

    @Test
    fun `限定引用 schema 可证伪 收录表没有该列记 unknown 不产边`() {
        val stmt = SemanticStatement(
            kind = StatementKind.SELECT,
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                    sources = listOf(TableSource(table("t1"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("zzz", table = "t1")))),
                ),
                raw = "SELECT t1.zzz FROM t1",
            ),
        )

        val model = LineageBuilder.build(stmt, schemaOf("t1" to listOf("a")))
        // 限定引用被 schema 证伪 → 无 OUTPUT 边；物理来源仍发 1 条 SOURCE 边（t1 -> zzz）。
        assertTrue(model.edges.none { it.kind == EdgeKind.OUTPUT })
        assertTrue(model.unknowns.any { it.reason == "来源 t1 没有列 zzz" })

        // 对照：schema 未收录 t1 → 无法证伪，沿用「唯一物理来源直接归属」。
        val unknownTable = LineageBuilder.build(stmt, schemaOf("other" to listOf("a")))
        assertEquals(1, unknownTable.edges.count { it.kind == EdgeKind.OUTPUT })
    }

    // ——— 规则 A：INSERT 未声明目标列按表列定义序对齐 ———

    @Test
    fun `INSERT 未声明目标列 按 schema 表列序对齐产边`() {
        val stmt = SemanticStatement(
            kind = StatementKind.INSERT,
            target = table("tgt"),
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                    sources = listOf(TableSource(table("src"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a"))), OutputItem(SqlExpr.Column(col("b")))),
                ),
                raw = "INSERT INTO tgt SELECT a, b FROM src",
            ),
        )

        val model = LineageBuilder.build(stmt, schemaOf("src" to listOf("a", "b"), "tgt" to listOf("x", "y")))

        // 2 条 OUTPUT（src -> tgt.x / tgt.y）+ 2 条 SOURCE（src -> tgt.x / tgt.y）。
        assertEquals(4, model.edges.size)
        val outputs = model.edges.filter { it.kind == EdgeKind.OUTPUT }
        assertEquals(listOf("src.a", "src.b"), outputs.map { it.fromColumn.qualifiedName })
        assertEquals(listOf("tgt.x", "tgt.y"), outputs.map { it.toColumn.qualifiedName })
        assertTrue(model.unknowns.none { it.reason.contains("未声明目标列") })
    }

    @Test
    fun `INSERT 未声明目标列 表列数与输出数不一致记 Unknown 不发边`() {
        val stmt = SemanticStatement(
            kind = StatementKind.INSERT,
            target = table("tgt"),
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                    sources = listOf(TableSource(table("src"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a")))),
                ),
                raw = "INSERT INTO tgt SELECT a FROM src",
            ),
        )

        val model = LineageBuilder.build(stmt, schemaOf("tgt" to listOf("x", "y")))
        assertTrue(model.edges.isEmpty())
        assertTrue(model.unknowns.any { it.reason == "INSERT 目标列数与查询输出数不一致" })
    }

    @Test
    fun `INSERT 未声明目标列 schema 未收录目标表 沿用旧 unknown`() {
        val stmt = SemanticStatement(
            kind = StatementKind.INSERT,
            target = table("tgt"),
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = io.github.workeron9.ozmoz.lineage.ir.ScopeKind.SELECT,
                    sources = listOf(TableSource(table("src"))),
                    outputs = listOf(OutputItem(SqlExpr.Column(col("a")))),
                ),
                raw = "INSERT INTO tgt SELECT a FROM src",
            ),
        )

        val model = LineageBuilder.build(stmt, schemaOf("src" to listOf("a")))
        assertTrue(model.edges.isEmpty())
        assertTrue(model.unknowns.any { it.reason == "INSERT 未声明目标列，需要 SchemaProvider" })
    }

    // ——— 溯源 ———

    @Test
    fun `传入 schema 时 meta 记录其 id 不传则为 null`() {
        val stmt = selectStarFrom(table("t"))
        assertEquals("test-schema", LineageBuilder.build(stmt, schemaOf("t" to listOf("a"))).meta.schemaSnapshotId)
        assertNull(LineageBuilder.build(stmt).meta.schemaSnapshotId)
    }
}
