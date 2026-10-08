package io.github.workeron9.ozmoz.lineage.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SpanTest {

    @Test
    fun `of 计算行列`() {
        val source = "SELECT a\nFROM t"
        // "a" 在第 1 行第 8 列（1 起始）
        val span = Span.of(source, 7, 8)
        assertEquals(Position(7, 1, 8), span.start)
        assertEquals(Position(8, 1, 9), span.end)
        assertEquals(1, span.length)
    }

    @Test
    fun `换行后列重新计数`() {
        val source = "SELECT a\nFROM t"
        // "FROM" 从第 2 行第 1 列开始（offset 9）
        val span = Span.of(source, 9, 13)
        assertEquals(2, span.start.line)
        assertEquals(1, span.start.column)
        assertEquals("FROM", source.substring(span.start.offset, span.end.offset))
    }

    @Test
    fun `零长度 span 合法`() {
        val span = Span.of("abc", 1, 1)
        assertEquals(0, span.length)
    }

    @Test
    fun `越界抛异常`() {
        assertFailsWith<IllegalArgumentException> { Span.of("abc", 0, 4) }
        assertFailsWith<IllegalArgumentException> { Span.of("abc", -1, 1) }
    }

    @Test
    fun `Position 不变量`() {
        assertFailsWith<IllegalArgumentException> { Position(-1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { Position(0, 0, 1) }
        assertFailsWith<IllegalArgumentException> { Position(0, 1, 0) }
    }

    @Test
    fun `Span 不允许 end 早于 start`() {
        assertFailsWith<IllegalArgumentException> {
            Span(Position(5, 1, 6), Position(2, 1, 3))
        }
    }
}

class ResolvedTest {

    @Test
    fun `Known 取值 Unknown 返回 null`() {
        val known: Resolved<Int> = Resolved.Known(42)
        val unknown: Resolved<Int> = Resolved.Unknown("没有 schema")
        assertEquals(42, known.valueOrNull())
        assertNull(unknown.valueOrNull())
    }

    @Test
    fun `map 保留 Unknown 的原因`() {
        val unknown: Resolved<Int> = Resolved.Unknown("歧义列 id", null)
        val mapped = unknown.map { it * 2 }
        assertEquals(Resolved.Unknown("歧义列 id", null), mapped)
    }

    @Test
    fun `flatMap 短路未知`() {
        val unknown: Resolved<Int> = Resolved.Unknown("缺 schema")
        var called = false
        val result = unknown.flatMap { called = true; Resolved.Known(it) }
        assertEquals(Resolved.Unknown("缺 schema"), result)
        assertEquals(false, called)
    }

    @Test
    fun `valueOrElse 不伪装未知`() {
        val unknown: Resolved<Int> = Resolved.Unknown("缺 schema")
        val fallback = unknown.valueOrElse { -1 }
        assertEquals(-1, fallback)
    }

    @Test
    fun `Unknown 必须有原因`() {
        assertFailsWith<IllegalArgumentException> { Resolved.unknown<Int>("") }
    }
}

class LineageModelTest {

    private fun column(name: String, table: String = "t") =
        ColumnRef(raw = name, canonical = name.lowercase(), name = name, table = table)

    @Test
    fun `边 id 必须唯一`() {
        val edge = LineageEdge(
            id = "e1",
            fromColumn = column("a"),
            toColumn = column("b"),
            kind = EdgeKind.OUTPUT,
            transform = TransformKind.DIRECT,
        )
        assertFailsWith<IllegalArgumentException> {
            LineageModel(edges = listOf(edge, edge.copy(fromColumn = column("c"))))
        }
    }

    @Test
    fun `scope id 必须唯一`() {
        val scope = ScopeNode(id = "s1", kind = ScopeKind.SELECT)
        assertFailsWith<IllegalArgumentException> {
            LineageModel(scopes = listOf(scope, scope.copy(kind = ScopeKind.CTE)))
        }
    }

    @Test
    fun `列节点 id 必须唯一`() {
        val node = ColumnNode(column = column("a"))
        assertFailsWith<IllegalArgumentException> {
            LineageModel(columns = listOf(node, node))
        }
    }

    @Test
    fun `按边类型计数`() {
        val out = LineageEdge(
            "e1", column("a"), column("b"), EdgeKind.OUTPUT, TransformKind.DIRECT,
        )
        val pred = LineageEdge(
            "e2", column("a"), column("b"), EdgeKind.PREDICATE, TransformKind.FILTER_PREDICATE,
        )
        val model = LineageModel(edges = listOf(out, pred))
        assertEquals(mapOf(EdgeKind.OUTPUT to 1, EdgeKind.PREDICATE to 1), model.edgeCountByKind())
    }

    @Test
    fun `空模型可用`() {
        assertEquals(emptyMap(), LineageModel().edgeCountByKind())
    }
}

class TableSchemaTest {

    private val table = TableRef(
        raw = "store_sales",
        canonical = "store_sales",
        name = "store_sales",
        schema = "public",
    )

    @Test
    fun `列名大小写不敏感查找`() {
        val schema = TableSchema.of(
            table,
            ColumnSchema("ss_quantity", type = "INT"),
            ColumnSchema("SS_NET_PROFIT", type = "DECIMAL"),
        )
        assertEquals("INT", schema.column("ss_quantity")?.type)
        assertEquals("DECIMAL", schema.column("ss_net_profit")?.type)
        assertNull(schema.column("missing"))
    }

    @Test
    fun `重复列名（折叠后）被拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            TableSchema.of(table, ColumnSchema("a"), ColumnSchema("A"))
        }
    }

    @Test
    fun `限定名与 id`() {
        assertEquals("public.store_sales", table.qualifiedName)
        assertEquals("public.store_sales", table.id)
    }
}

class AstNodeTest {

    @Test
    fun `flatten 深度优先`() {
        val tree = AstNode(
            type = "select",
            text = "SELECT a",
            children = listOf(
                AstNode(type = "column_ref", text = "a"),
                AstNode(
                    type = "from",
                    text = "FROM t",
                    children = listOf(AstNode(type = "table_ref", text = "t")),
                ),
            ),
        )
        val types = tree.flatten().map { it.type }.toList()
        assertEquals(listOf("select", "column_ref", "from", "table_ref"), types)
    }

    @Test
    fun `type 不能为空`() {
        assertFailsWith<IllegalArgumentException> { AstNode(type = "", text = "x") }
    }
}
