package io.github.workeron9.ozmoz.lineage.cli

import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.lineage.ScopeSource
import io.github.workeron9.ozmoz.lineage.lineage.ScopeTreeBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 端到端：真实引擎（`engine-jsqlparser`）产出语义模型 → `lineage` 建作用域树。
 *
 * 这一层在 `cli`（组合根）验证，避免 `lineage` 反向依赖任何引擎适配器
 * （依赖铁律：`lineage` 只依赖 `ir` + `engine-api` + `schema` SPI）。
 */
class LineagePipelineTest {

    private val engine = JSqlParserEngine()

    @Test
    fun `单表 SELECT 端到端建出表来源与输出列`() {
        val semantic = assertIs<Resolved.Known<*>>(engine.analyze("SELECT ss_quantity, ss_net_profit AS profit FROM store_sales"))
        val tree = ScopeTreeBuilder.build(semantic.value as io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement)

        val root = assertNotNull(tree.rootScopeId?.let { tree.scope(it) })
        val source = assertIs<ScopeSource.Table>(root.sources.single())
        assertEquals("store_sales", source.ref.name)
        assertEquals(listOf("ss_quantity", "profit"), root.outputs.map { it.name })
    }

    @Test
    fun `CTE 端到端被解析为 Cte 而非物理表`() {
        val semantic = assertIs<Resolved.Known<*>>(
            engine.analyze("WITH c AS (SELECT a FROM s) SELECT a FROM c"),
        )
        val tree = ScopeTreeBuilder.build(semantic.value as io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement)

        val root = assertNotNull(tree.rootScopeId?.let { tree.scope(it) })
        val cte = assertIs<ScopeSource.Cte>(root.sources.single())
        assertEquals("c", cte.name)
        val cteScope = assertNotNull(cte.scopeId?.let { tree.scope(it) })
        assertIs<ScopeSource.Table>(cteScope.sources.single())
    }

    @Test
    fun `JOIN 端到端两侧来源都进作用域`() {
        val semantic = assertIs<Resolved.Known<*>>(
            engine.analyze("SELECT s.a FROM store_sales s JOIN customer c ON s.id = c.id"),
        )
        val tree = ScopeTreeBuilder.build(semantic.value as io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement)

        val root = assertNotNull(tree.rootScopeId?.let { tree.scope(it) })
        val names = root.sources.map { (it as ScopeSource.Table).ref.name }
        assertEquals(listOf("store_sales", "customer"), names)
    }

    @Test
    fun `不支持语义提取的语句端到端不抛异常`() {
        // MERGE 不建模为 SemanticStatement：引擎返回 Unknown，组合层不会崩。
        assertIs<Resolved.Unknown>(
            engine.analyze("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = 1"),
        )
    }

    @Test
    fun `星号输出端到端不发明列名`() {
        val semantic = assertIs<Resolved.Known<*>>(engine.analyze("SELECT * FROM store_sales"))
        val tree = ScopeTreeBuilder.build(semantic.value as io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement)

        val root = assertNotNull(tree.rootScopeId?.let { tree.scope(it) })
        // `*` 在作用域树层**不发明列名**；名字由 lineage 展开阶段决定（CTE/派生推导
        // 或 schema 列清单），展开失败时的 unknown 也在那一层记（见 LineageCommandTest）。
        assertTrue(root.outputs.isEmpty())
        assertTrue(tree.unknowns.isEmpty())
    }
}
