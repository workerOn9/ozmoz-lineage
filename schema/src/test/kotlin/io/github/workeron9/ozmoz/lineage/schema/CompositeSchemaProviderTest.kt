package io.github.workeron9.ozmoz.lineage.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompositeSchemaProviderTest {

    private val first = StaticSchemaProvider(listOf(tableSchema("a", null, "x")), id = "first")
    private val second = StaticSchemaProvider(
        listOf(tableSchema("b", null, "y")),
        defaultSearchPath = setOf("public"),
        id = "second",
    )

    @Test
    fun `table 按声明顺序首个非空命中`() {
        val both = CompositeSchemaProvider(first, second)
        assertEquals("a", both.table(tableRef("a"))!!.table.name)
        assertEquals("b", both.table(tableRef("b"))!!.table.name)
    }

    @Test
    fun `前置提供方优先于后置`() {
        val shadow = StaticSchemaProvider(listOf(tableSchema("b", null, "shadowed")), id = "shadow")
        val composite = CompositeSchemaProvider(shadow, second)
        assertEquals("shadowed", composite.table(tableRef("b"))!!.columns.single().name)
    }

    @Test
    fun `全部 miss 返回 null`() {
        assertNull(CompositeSchemaProvider(first, second).table(tableRef("zzz")))
    }

    @Test
    fun `search 按表 id 去重拼接`() {
        val dup = StaticSchemaProvider(listOf(tableSchema("aa", null, "x")), id = "dup")
        assertEquals(
            listOf("a", "aa"),
            CompositeSchemaProvider(first, dup).search("a").map { it.name },
        )
    }

    @Test
    fun `defaultSearchPath 为各提供方并集保持先出现顺序`() {
        val third = StaticSchemaProvider(
            listOf(tableSchema("c")),
            defaultSearchPath = setOf("analytics"),
            id = "third",
        )
        assertEquals(
            listOf("public", "analytics"),
            CompositeSchemaProvider(second, third).defaultSearchPath.toList(),
        )
    }

    @Test
    fun `isVolatile 任一易变即为 true`() {
        val volatile = object : SchemaProvider {
            override val id: String get() = "volatile"
            override val isVolatile: Boolean get() = true
            override fun table(ref: io.github.workeron9.ozmoz.lineage.ir.TableRef) = null
            override fun search(fuzzy: String) = emptyList<io.github.workeron9.ozmoz.lineage.ir.TableRef>()
        }
        assertTrue(CompositeSchemaProvider(first, volatile).isVolatile)
        assertFalse(CompositeSchemaProvider(first, second).isVolatile)
    }

    @Test
    fun `id 默认为各提供方 id 拼接`() {
        assertEquals("first+second", CompositeSchemaProvider(first, second).id)
    }

    @Test
    fun `空组合构造即拒绝`() {
        assertFailsWith<IllegalArgumentException> { CompositeSchemaProvider(emptyList()) }
    }
}
