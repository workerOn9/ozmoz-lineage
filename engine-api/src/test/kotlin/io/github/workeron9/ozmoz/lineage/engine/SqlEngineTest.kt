package io.github.workeron9.ozmoz.lineage.engine

import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineCapabilitiesTest {

    @Test
    fun `支持与原因`() {
        val caps = EngineCapabilities(
            features = setOf(Feature.PARSE, Feature.EXTRACT_TABLES),
            dialects = setOf("ansi"),
            reasons = mapOf(Feature.DIALECT_RENDER to "无方言渲染"),
        )
        assertTrue(caps.supports(Feature.PARSE))
        assertFalse(caps.supports(Feature.DIALECT_RENDER))
        assertEquals("无方言渲染", caps.reason(Feature.DIALECT_RENDER))
        assertNull(caps.reason(Feature.PARSE))
    }

    @Test
    fun `未声明原因时 reason 为 null`() {
        val caps = EngineCapabilities.of(Feature.PARSE)
        assertFalse(caps.supports(Feature.FIELD_LINEAGE))
        assertNull(caps.reason(Feature.FIELD_LINEAGE))
    }
}

class SqlEngineTest {

    /** 一个只实现必选能力的最小引擎，用来验证默认方法的行为。 */
    private class StubEngine : SqlEngine {
        override val id: String = "stub"
        override val capabilities: EngineCapabilities = EngineCapabilities(
            features = setOf(Feature.PARSE),
            reasons = mapOf(Feature.DIALECT_RENDER to "stub 不渲染"),
        )

        override fun parse(sql: String, request: ParseRequest): ParseOutcome =
            ParseOutcome(root = AstNode(type = "statement", text = sql))
    }

    @Test
    fun `默认 render 返回 Unknown 并带原因`() {
        val engine = StubEngine()
        val result = engine.render(RenderRequest(sql = "SELECT 1", toDialect = "postgresql"))
        assertEquals(Resolved.Unknown("stub 不渲染"), result)
    }

    @Test
    fun `parse 返回归一化树`() {
        val engine = StubEngine()
        val outcome = engine.parse("SELECT 1")
        assertEquals("statement", outcome.root.type)
        assertEquals("SELECT 1", outcome.root.text)
        assertFalse(outcome.hasErrors)
    }

    @Test
    fun `有 ERROR 诊断时 hasErrors 为真`() {
        val outcome = ParseOutcome(
            diagnostics = listOf(Diagnostic.error("parse.unexpected_token", "多余的分号")),
            root = AstNode.empty(),
        )
        assertTrue(outcome.hasErrors)
    }
}
