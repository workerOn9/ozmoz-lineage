package io.github.workeron9.ozmoz.lineage.schema

import io.github.workeron9.ozmoz.lineage.ir.ColumnSchema
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TableSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal fun tableRef(name: String, schema: String? = null): TableRef = TableRef(
    raw = listOfNotNull(schema, name).joinToString("."),
    canonical = listOfNotNull(schema, name).joinToString(".").lowercase(),
    schema = schema,
    name = name,
)

internal fun tableSchema(name: String, schema: String? = null, vararg columns: String): TableSchema =
    TableSchema(
        tableRef(name, schema),
        columns.mapIndexed { i, column -> ColumnSchema(name = column, ordinal = i + 1) },
    )

class StaticSchemaProviderTest {

    private val provider = StaticSchemaProvider(
        tables = listOf(
            tableSchema("T", schema = "public", "id", "name"),
            tableSchema("orders", schema = "public", "id", "amount"),
            tableSchema("standalone", null, "flag"),
        ),
        defaultSearchPath = setOf("public"),
        id = "test-static",
    )

    @Test
    fun `限定名直接命中且大小写不敏感`() {
        val hit = provider.table(tableRef("PUBLIC.t"))
        assertEquals(setOf("id", "name"), hit!!.columns.map { it.name }.toSet())
    }

    @Test
    fun `未限定名按 search path 恰一命中`() {
        assertEquals("orders", provider.table(tableRef("ORDERS"))!!.table.name)
    }

    @Test
    fun `未限定名命中 search path 外或无 schema 的表`() {
        assertEquals("standalone", provider.table(tableRef("standalone"))!!.table.name)
    }

    @Test
    fun `未限定名只有带 schema 且不在 search path 时查不到`() {
        val narrow = StaticSchemaProvider(listOf(tableSchema("t", schema = "app", "id")))
        assertNull(narrow.table(tableRef("t")))
    }

    @Test
    fun `限定名 miss 就是 miss`() {
        assertNull(provider.table(tableRef("t", schema = "missing")))
    }

    @Test
    fun `search 子串大小写不敏感且结果确定`() {
        // "t" 命中名字或限定名含 t 的表（"public.orders" 里没有 t），按 id 排序。
        assertEquals(
            listOf("T", "standalone"),
            provider.search("t").map { it.name },
        )
        assertEquals(listOf("orders"), provider.search("ORD").map { it.name })
        assertEquals(emptyList(), provider.search("zzz"))
    }

    @Test
    fun `折叠后表标识重复构造即拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            StaticSchemaProvider(listOf(tableSchema("S.T"), tableSchema("s.t")))
        }
    }

    @Test
    fun `折叠后同 schema 表名重复构造即拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            StaticSchemaProvider(listOf(tableSchema("t", schema = "S"), tableSchema("T", schema = "s")))
        }
    }
}
