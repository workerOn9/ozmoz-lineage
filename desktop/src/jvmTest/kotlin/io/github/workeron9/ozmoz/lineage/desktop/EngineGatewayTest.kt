package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.SeverityView
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 骨架的**真实验证**：进程内经 `:pipeline` 直调引擎 → 呈现状态。
 * 界面起不起得来要人看，但「打开一个 SQL 文件能不能出树与图」必须在这里说得清。
 */
class EngineGatewayTest {

    private val engineId = EngineGateway.engineIds.first()

    @Test
    fun buildsTreeAndColumnLineage() {
        val analysis = EngineGateway.analyze(
            """
            CREATE TABLE orders (id INT, amount DECIMAL(10, 2));
            SELECT id, amount * 2 AS doubled FROM orders;
            """.trimIndent(),
            engineId = engineId,
            tolerant = true,
        )

        assertNull(analysis.note, "可建模的 SQL 不该报「没建出模型」")
        assertTrue(analysis.flows.isNotEmpty(), "应该有列级血缘边")
        assertTrue(analysis.flows.any { it.kind.name == "OUTPUT" }, "应该有 OUTPUT 边：${analysis.flows.map { it.kind }}")
        assertTrue(analysis.columns.any { it.isOutput }, "应该有输出列")
        assertTrue(analysis.columns.any { it.type != null }, "CREATE TABLE 里的类型应该被 schema 收录")
        assertTrue(analysis.tree.isNotEmpty(), "应该有归一化树")
        assertNotNull(analysis.tree.first().children.firstOrNull(), "根节点下应该挂着语句")
    }

    /** 坏语句不拖垮好语句：好语句出图，坏语句进诊断（带行号）——这是 D1「不空白」的底线。 */
    @Test
    fun badStatementBecomesDiagnosticNotBlankScreen() {
        val analysis = EngineGateway.analyze(
            "SELECT a FROM t;\nSELECT * FROM WHERE x = 1",
            engineId = engineId,
            tolerant = true,
        )

        assertTrue(analysis.columns.isNotEmpty(), "好语句照常出图")
        assertTrue(
            analysis.issues.any { it.severity == SeverityView.ERROR },
            "坏语句要留下 ERROR 诊断，不能静默丢：${analysis.issues}",
        )
        assertNull(analysis.note, "容错模式下仍应有部分模型产出")
    }

    /** 严格模式整段语法错误：不抛异常，落在 [io.github.workeron9.ozmoz.lineage.desktop.model.Analysis.note] 上。 */
    @Test
    fun strictSyntaxErrorBecomesNoteNotException() {
        val analysis = EngineGateway.analyze("SELECT * FROM WHERE x = 1", engineId = engineId, tolerant = false)

        assertNotNull(analysis.note, "严格模式下解析失败要显式说出来")
        assertTrue(analysis.flows.isEmpty())
        assertTrue(analysis.issues.isNotEmpty(), "失败的解析要留下诊断")
    }
}
