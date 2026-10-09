package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.TransformKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [OpenLineageExporter] 的关键片段断言：确定性占位值、inputs 去重排序、
 * outputs 唯一表推导、以及 [TransformKind] → `type`/`subtype` 映射逐项正确。
 */
class OpenLineageExporterTest {

    private val model = FormatFixtures.fullModel()
    private val root = Json.parseToJsonElement(OpenLineageExporter.export(model)).jsonObject

    private fun primitive(path: String, vararg keys: String): String {
        var node = root
        for (key in keys) node = node.getValue(key).jsonObject
        return node.getValue(path).jsonPrimitive.content
    }

    @Test
    fun `确定性占位值逐项固定`() {
        assertEquals("COMPLETE", primitive("eventType"))
        assertEquals("1970-01-01T00:00:00.000Z", primitive("eventTime"))
        assertEquals("00000000-0000-0000-0000-000000000000", primitive("runId", "run"))
        assertEquals("ozmoz-lineage", primitive("namespace", "job"))
        assertEquals("jsqlparser", primitive("name", "job"))
        assertEquals("https://github.com/workerOn9/ozmoz-lineage", primitive("producer"))
        assertEquals(OpenLineageExporter.COLUMN_LINEAGE_SCHEMA_URL, primitive("schemaURL"))
    }

    @Test
    fun `inputs 去重并按字符串排序`() {
        val names = root.getValue("inputs").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(listOf("customer", "store_sales"), names)
        for (input in root.getValue("inputs").jsonArray) {
            assertEquals("ozmoz-lineage", input.jsonObject.getValue("namespace").jsonPrimitive.content)
        }
    }

    @Test
    fun `唯一输出表时产出 outputs 数据集与 columnLineage facet`() {
        val outputs = root.getValue("outputs").jsonArray
        assertEquals(1, outputs.size)
        val dataset = outputs.single().jsonObject
        assertEquals("sales_summary", dataset.getValue("name").jsonPrimitive.content)
        val facet = dataset.getValue("facets").jsonObject.getValue("columnLineage").jsonObject
        assertEquals(OpenLineageExporter.PRODUCER, facet.getValue("_producer").jsonPrimitive.content)
        assertEquals(OpenLineageExporter.COLUMN_LINEAGE_SCHEMA_URL, facet.getValue("_schemaURL").jsonPrimitive.content)
        val fields = facet.getValue("fields").jsonObject
        // 键 = 输出列名。JOIN_KEY 边的 to（customer.c_customer_sk）是输入列、非输出字段，不入 fields。
        assertEquals(setOf("sold_date", "total_qty"), fields.keys)
    }

    @Test
    fun `推不出唯一输出表时 outputs 为空且不编表名`() {
        val ambiguous = Json.parseToJsonElement(OpenLineageExporter.export(FormatFixtures.ambiguousOutputModel()))
            .jsonObject
        assertTrue(ambiguous.getValue("outputs").jsonArray.isEmpty())
    }

    @Test
    fun `映射表逐项正确`() {
        val fields = root.getValue("outputs").jsonArray.single().jsonObject
            .getValue("facets").jsonObject.getValue("columnLineage").jsonObject
            .getValue("fields").jsonObject

        // total_qty：AGGREGATE→DIRECT/AGGREGATION、EXPRESSION→DIRECT/TRANSFORMATION、
        // WINDOW→INDIRECT/WINDOW、FILTER_PREDICATE→INDIRECT/FILTER、GROUPING→INDIRECT/GROUP_BY
        val totalQty = transformations(fields, "total_qty")
        assertContains(totalQty, Triple("DIRECT", "AGGREGATION", "SUM(ss_quantity)"))
        assertContains(totalQty, Triple("DIRECT", "TRANSFORMATION", "c_customer_sk * 2"))
        assertContains(totalQty, Triple("INDIRECT", "WINDOW", "ROW_NUMBER() OVER (PARTITION BY ss_sold_date_sk)"))
        assertContains(totalQty, Triple("INDIRECT", "FILTER", "c_customer_sk > 0"))
        assertContains(totalQty, Triple("INDIRECT", "GROUP_BY", "ss_sold_date_sk"))

        // sold_date：DIRECT→DIRECT/IDENTITY、CASE_BRANCH→INDIRECT/CONDITIONAL、ORDERING→INDIRECT/SORT
        val soldDate = transformations(fields, "sold_date")
        assertContains(soldDate, Triple("DIRECT", "IDENTITY", "ss_sold_date_sk"))
        assertContains(soldDate, Triple("INDIRECT", "CONDITIONAL", "CASE WHEN c_customer_sk > 0 THEN 1 ELSE 0 END"))
        assertContains(soldDate, Triple("INDIRECT", "SORT", "ss_sold_date_sk"))
    }

    @Test
    fun `inputFields 按表名列名排序且同字段多变换合并`() {
        val fields = root.getValue("outputs").jsonArray.single().jsonObject
            .getValue("facets").jsonObject.getValue("columnLineage").jsonObject
            .getValue("fields").jsonObject

        val totalQty = fields.getValue("total_qty").jsonObject.getValue("inputFields").jsonArray
        val keys = totalQty.map { field ->
            val obj = field.jsonObject
            obj.getValue("name").jsonPrimitive.content + "." + obj.getValue("field").jsonPrimitive.content
        }
        assertEquals(listOf("customer.c_customer_sk", "store_sales.ss_quantity", "store_sales.ss_sold_date_sk"), keys)

        // store_sales.ss_quantity 同时被 AGGREGATE 与 WINDOW 引用 → 合并进同一 inputField
        val ssQuantity = totalQty.single {
            it.jsonObject.getValue("field").jsonPrimitive.content == "ss_quantity"
        }.jsonObject
        assertEquals(2, ssQuantity.getValue("transformations").jsonArray.size)
        for (transformation in ssQuantity.getValue("transformations").jsonArray) {
            val obj = transformation.jsonObject
            assertEquals("false", obj.getValue("masking").jsonPrimitive.content)
        }
    }

    @Test
    fun `SOURCE 边不写进 columnLineage`() {
        val encoded = OpenLineageExporter.export(model)
        val fields = root.getValue("outputs").jsonArray.single().jsonObject
            .getValue("facets").jsonObject.getValue("columnLineage").jsonObject
            .getValue("fields").jsonObject
        val totalQty = fields.getValue("total_qty").jsonObject.getValue("inputFields").jsonArray
        // 哨兵没有列名，不应出现 field 为表名的输入项
        assertTrue(totalQty.none { it.jsonObject.getValue("field").jsonPrimitive.content == "store_sales" })
        assertContains(encoded, "\"subtype\"")
    }

    @Test
    fun `of 用真实上下文覆盖占位值`() {
        val exporter = OpenLineageExporter.of(
            eventTime = "2026-02-03T04:05:06.000Z",
            runId = "11111111-1111-1111-1111-111111111111",
            namespace = "prod",
            jobName = "etl.daily",
        )
        val custom = Json.parseToJsonElement(exporter.export(model)).jsonObject
        assertEquals("2026-02-03T04:05:06.000Z", custom.getValue("eventTime").jsonPrimitive.content)
        assertEquals(
            "11111111-1111-1111-1111-111111111111",
            custom.getValue("run").jsonObject.getValue("runId").jsonPrimitive.content,
        )
        assertEquals("prod", custom.getValue("job").jsonObject.getValue("namespace").jsonPrimitive.content)
        assertEquals("etl.daily", custom.getValue("job").jsonObject.getValue("name").jsonPrimitive.content)
        // 结构不变，仍是合法 OpenLineage 事件
        assertEquals("COMPLETE", custom.getValue("eventType").jsonPrimitive.content)
    }

    @Test
    fun `同一模型导出两次逐字节相同`() {
        assertEquals(OpenLineageExporter.export(model), OpenLineageExporter.export(model))
    }

    @Test
    fun `空模型不崩且 outputs 为空`() {
        val empty = Json.parseToJsonElement(OpenLineageExporter.export(FormatFixtures.emptyModel())).jsonObject
        assertTrue(empty.getValue("inputs").jsonArray.isEmpty())
        assertTrue(empty.getValue("outputs").jsonArray.isEmpty())
        assertEquals("ozmoz-lineage", empty.getValue("job").jsonObject.getValue("name").jsonPrimitive.content)
    }

    /** 从某个输出列的 `inputFields` 里抽出 `(type, subtype, description)` 三元组。 */
    private fun transformations(fields: JsonObject, output: String): List<Triple<String, String, String>> =
        fields.getValue(output).jsonObject.getValue("inputFields").jsonArray.flatMap { field ->
            field.jsonObject.getValue("transformations").jsonArray.map { transformation ->
                val obj = transformation.jsonObject
                Triple(
                    obj.getValue("type").jsonPrimitive.content,
                    obj.getValue("subtype").jsonPrimitive.content,
                    obj.getValue("description").jsonPrimitive.content,
                )
            }
        }
}
