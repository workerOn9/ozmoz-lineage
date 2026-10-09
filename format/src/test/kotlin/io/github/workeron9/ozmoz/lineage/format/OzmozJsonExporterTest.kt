package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** [OzmozJsonExporter]：形状与 CLI `--format json` 一致，且能往返反序列化回等值模型。 */
class OzmozJsonExporterTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val model = FormatFixtures.fullModel()

    @Test
    fun `能反序列化回等值模型`() {
        val text = OzmozJsonExporter.export(model)
        assertEquals(model, json.decodeFromString(LineageModel.serializer(), text))
    }

    @Test
    fun `prettyPrint 且含关键字段`() {
        val text = OzmozJsonExporter.export(model)
        assertEquals(true, text.contains("\n"))
        assertEquals(true, text.contains("\"edges\""))
        assertEquals(true, text.contains("\"columns\""))
        assertEquals(true, text.contains("\"jsqlparser\""))
    }

    @Test
    fun `空模型往返`() {
        val empty = FormatFixtures.emptyModel()
        assertEquals(empty, json.decodeFromString(LineageModel.serializer(), OzmozJsonExporter.export(empty)))
    }

    @Test
    fun `同一模型导出两次逐字节相同`() {
        assertEquals(OzmozJsonExporter.export(model), OzmozJsonExporter.export(model))
    }
}
