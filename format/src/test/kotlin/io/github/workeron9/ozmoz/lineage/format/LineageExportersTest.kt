package io.github.workeron9.ozmoz.lineage.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 注册表与导出器 SPI 的契约：5 个 id 可查（大小写不敏感），每个导出器都确定性。 */
class LineageExportersTest {

    private val expectedIds = listOf("openlineage", "mermaid", "dot", "cypher", "ozmoz-json")

    @Test
    fun `ids 稳定且齐全`() {
        assertEquals(expectedIds, LineageExporters.ids)
    }

    @Test
    fun `byId 大小写不敏感且能查到全部 5 个`() {
        for (id in expectedIds) {
            assertNotNull(LineageExporters.byId(id), "应能查到 $id")
            assertNotNull(LineageExporters.byId(id.uppercase()), "应能查到 ${id.uppercase()}")
            assertEquals(id, LineageExporters.byId(id)?.id)
        }
    }

    @Test
    fun `未注册 id 返回 null`() {
        assertNull(LineageExporters.byId("no-such-format"))
    }

    @Test
    fun `每个导出器对同一模型都确定性`() {
        val model = FormatFixtures.fullModel()
        for (info in LineageExporters.all) {
            assertEquals(
                info.exporter.export(model),
                info.exporter.export(model),
                "${info.id} 应确定性",
            )
        }
    }

    @Test
    fun `每个导出器都非空输出且 mime 有值`() {
        val model = FormatFixtures.fullModel()
        for (info in LineageExporters.all) {
            assertTrue(info.exporter.export(model).isNotEmpty(), "${info.id} 输出不应为空")
            assertTrue(info.mime.isNotBlank(), "${info.id} mime 不应为空")
        }
    }
}
