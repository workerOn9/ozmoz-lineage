package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.ColumnNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** [CypherExporter] 的关键片段断言：`MERGE` 节点、关系类型、字面量转义。 */
class CypherExporterTest {

    private val model = FormatFixtures.fullModel()
    private val lines = CypherExporter.export(model).lines()

    @Test
    fun `列节点用 Column 标签 表级哨兵用 Table 标签`() {
        assertContains(lines, "MERGE (n0:Column {id: 'customer.c_customer_sk'}) SET n0.name = 'customer.c_customer_sk'")
        assertContains(lines, "MERGE (n6:Table {id: 'store_sales'}) SET n6.name = 'store_sales'")
    }

    @Test
    fun `关系用 EdgeKind 名与 transform 属性`() {
        assertContains(
            lines,
            "MERGE (n1)-[:OUTPUT {transform: 'AGGREGATE'}]->(n4)",
        )
        assertContains(
            lines,
            "MERGE (n3)-[:JOIN_KEY {transform: 'JOIN_KEY'}]->(n0)",
        )
        assertContains(
            lines,
            "MERGE (n6)-[:SOURCE {transform: 'SOURCE'}]->(n4)",
        )
    }

    @Test
    fun `末尾换行且每条语句一行`() {
        val text = CypherExporter.export(model)
        assertEquals(true, text.endsWith("\n"))
        // 每条 MERGE 都自成一行，不留空行（trimEnd 去掉结尾换行）
        val statements = text.trimEnd('\n').lines()
        assertEquals(statements.size, statements.count { it.startsWith("MERGE ") })
        // 节点数（6 列 + 2 哨兵）+ 边数（11）= 19 条语句
        assertEquals(19, statements.size)
    }

    @Test
    fun `单引号与反斜杠被转义`() {
        val quoted = FormatFixtures.fullModel().copy(
            columns = listOf(
                ColumnNode(
                    column = FormatFixtures.col("o'brien", "t\\1"),
                    isOutput = true,
                ),
            ),
            edges = emptyList(),
        )
        val text = CypherExporter.export(quoted)
        assertContains(text, "id: 't\\\\1.o\\'brien'")
    }

    @Test
    fun `空模型不崩`() {
        assertEquals("", CypherExporter.export(FormatFixtures.emptyModel()))
    }

    @Test
    fun `同一模型导出两次逐字节相同`() {
        assertEquals(CypherExporter.export(model), CypherExporter.export(model))
    }
}
