package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.testing.test
import io.github.workeron9.ozmoz.lineage.server.EngineReport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ozml engines` 端到端：能力表 JSON 与 `POST /api/engines` **同形**（同一 `EngineReport`），
 * 文档站「工具百科」由它数据驱动渲染。
 */
class EnginesCommandTest {

    private val command = EnginesCommand()

    @Test
    fun `默认输出 json 且与 api engines 同形`() {
        val result = command.test("")
        assertEquals(0, result.statusCode)

        // 能按 server 的 EngineReport 反序列化 = 形状同源（同一类型 + 同一构造器）。
        val reports = Json.decodeFromString(ListSerializer(EngineReport.serializer()), result.stdout)
        assertEquals(listOf("jsqlparser", "calcite", "jooq"), reports.map { it.id })
    }

    @Test
    fun `jsqlparser 申报逐语句容错且无不支持原因`() {
        val reports = Json.decodeFromString(
            ListSerializer(EngineReport.serializer()),
            command.test("--format json").stdout,
        )
        val jsqlparser = reports.single { it.id == "jsqlparser" }
        assertTrue("ERROR_TOLERANT" in jsqlparser.features)
        assertTrue("SEMANTIC_MODEL" in jsqlparser.features)
        assertEquals(null, jsqlparser.reasons["ERROR_TOLERANT"])
        assertEquals(listOf("ansi"), jsqlparser.dialects)
    }

    @Test
    fun `未申报能力带出可解释原因`() {
        val reports = Json.decodeFromString(
            ListSerializer(EngineReport.serializer()),
            command.test("--format json").stdout,
        )
        val calcite = reports.single { it.id == "calcite" }
        assertTrue("ERROR_TOLERANT" !in calcite.features)
        assertContains(calcite.reasons.getValue("ERROR_TOLERANT"), "不支持")
    }

    @Test
    fun `text 格式输出人读能力表`() {
        val result = command.test("--format text")
        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "jsqlparser")
        assertContains(result.stdout, "features : ")
        assertContains(result.stdout, "dialects : ")
        assertContains(result.stdout, "unsupported:")
        assertContains(result.stdout, "DIALECT_RENDER")
    }
}
