package io.github.workeron9.ozmoz.lineage.ir

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

/**
 * 契约的序列化往返：`ir` 的 DTO 必须能被 kotlinx.serialization 稳定编解码——
 * CLI（JSON 输出）与将来的 HTTP（Ktor content negotiation）共用这一份契约。
 */
class SerializationTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `AstNode 往返`() {
        val node = AstNode(
            type = "select",
            text = "SELECT a",
            span = Span.of("SELECT a", 0, 8),
            children = listOf(AstNode(type = "column", text = "a")),
        )
        val encoded = json.encodeToString(AstNode.serializer(), node)
        assertEquals(node, json.decodeFromString(AstNode.serializer(), encoded))
    }

    @Test
    fun `Span 与 Position 往返`() {
        val span = Span(Position(7, 1, 8), Position(8, 1, 9))
        val encoded = json.encodeToString(Span.serializer(), span)
        assertEquals(span, json.decodeFromString(Span.serializer(), encoded))
    }

    @Test
    fun `TableRef 往返并保留 raw 与 canonical`() {
        val ref = TableRef(
            raw = "`my_db`.`MyTable`",
            canonical = "my_db.mytable",
            schema = "my_db",
            name = "MyTable",
            alias = "t",
        )
        val encoded = json.encodeToString(TableRef.serializer(), ref)
        assertContains(encoded, "MyTable")
        assertContains(encoded, "my_db.mytable")
        assertEquals(ref, json.decodeFromString(TableRef.serializer(), encoded))
    }

    @Test
    fun `LineageModel 往返`() {
        val model = LineageModel(
            meta = Meta(engineId = "jsqlparser", dialect = "ansi"),
            edges = listOf(
                LineageEdge(
                    id = "e1",
                    fromColumn = ColumnRef("a", "a", "a", table = "t"),
                    toColumn = ColumnRef("b", "b", "b", table = "u"),
                    kind = EdgeKind.OUTPUT,
                    transform = TransformKind.DIRECT,
                ),
            ),
            unknowns = listOf(Resolved.Unknown("缺 schema", null)),
        )
        val encoded = json.encodeToString(LineageModel.serializer(), model)
        assertEquals(model, json.decodeFromString(LineageModel.serializer(), encoded))
    }

    @Test
    fun `Diagnostic 往返`() {
        val d = Diagnostic(Severity.ERROR, "parse.error", "坏了", Span.of("abc", 0, 1), "jsqlparser")
        val encoded = json.encodeToString(Diagnostic.serializer(), d)
        assertEquals(d, json.decodeFromString(Diagnostic.serializer(), encoded))
    }

    @Test
    fun `TableSchema 往返`() {
        val schema = TableSchema.of(
            TableRef("store_sales", "store_sales", name = "store_sales"),
            ColumnSchema("ss_quantity", type = "INT"),
        )
        val encoded = json.encodeToString(TableSchema.serializer(), schema)
        assertEquals(schema, json.decodeFromString(TableSchema.serializer(), encoded))
    }
}
