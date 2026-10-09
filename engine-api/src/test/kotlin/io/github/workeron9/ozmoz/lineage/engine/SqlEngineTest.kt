package io.github.workeron9.ozmoz.lineage.engine

import io.github.workeron9.ozmoz.lineage.engine.semantics.ScopeSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    @Test
    fun `默认 analyzeAll 在不支持语义模型时退化为 analyze 的 Unknown`() {
        val engine = StubEngine()
        val all = engine.analyzeAll("SELECT 1")
        assertIs<Resolved.Unknown>(all)
        assertEquals((engine.analyze("SELECT 1") as Resolved.Unknown).reason, all.reason)
    }

    @Test
    fun `默认 analyzeAll 把单语句结果包成单元素列表`() {
        val statement = SemanticStatement(
            kind = StatementKind.SELECT,
            query = SelectQuery(scope = ScopeSpec(kind = ScopeKind.SELECT), raw = "SELECT 1"),
        )
        val engine = object : SqlEngine {
            override val id: String = "stub-single"
            override val capabilities: EngineCapabilities = EngineCapabilities.of(Feature.SEMANTIC_MODEL)
            override fun parse(sql: String, request: ParseRequest): ParseOutcome =
                ParseOutcome(root = AstNode(type = "statement", text = sql))
            override fun analyze(sql: String, request: ParseRequest): Resolved<SemanticStatement> =
                Resolved.Known(statement)
        }

        assertEquals(Resolved.Known(listOf(statement)), engine.analyzeAll("SELECT 1"))
    }
}
