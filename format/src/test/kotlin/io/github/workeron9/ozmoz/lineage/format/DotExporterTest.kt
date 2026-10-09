package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.ColumnNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** [DotExporter] 的关键片段断言：`digraph` 骨架、`rankdir`、节点 shape、边 label。 */
class DotExporterTest {

    private val model = FormatFixtures.fullModel()
    private val text = DotExporter.export(model)

    @Test
    fun `骨架与 rankdir`() {
        assertContains(text, "digraph lineage {")
        assertContains(text, "rankdir=LR;")
        assertContains(text, "}")
    }

    @Test
    fun `输出列用 box 非输出列用 ellipse`() {
        assertContains(text, "\"sales_summary.total_qty\" [label=\"sales_summary.total_qty\", shape=box];")
        assertContains(text, "\"store_sales.ss_quantity\" [label=\"store_sales.ss_quantity\", shape=ellipse];")
    }

    @Test
    fun `表级哨兵用 cylinder`() {
        assertContains(text, "\"store_sales\" [label=\"store_sales\", shape=cylinder];")
    }

    @Test
    fun `边 label 用 KIND TRANSFORM 且顺序按模型列表`() {
        assertContains(
            text,
            "\"store_sales.ss_quantity\" -> \"sales_summary.total_qty\" [label=\"OUTPUT/AGGREGATE\"];",
        )
        assertContains(
            text,
            "\"store_sales.ss_sold_date_sk\" -> \"sales_summary.sold_date\" [label=\"ORDER_BY/ORDERING\"];",
        )
        // 第一条边就是模型里的 e0（AGGREGATE），节点顺序也照 columns 列表
        val firstEdge = text.lines().first { it.contains("->") }
        assertContains(firstEdge, "OUTPUT/AGGREGATE")
    }

    @Test
    fun `同一对节点多条边不合并`() {
        // store_sales.ss_quantity → sales_summary.total_qty 有 AGGREGATE 与 WINDOW 两条，DOT 保留平行边
        val parallel = text.lines().filter {
            it.contains("\"store_sales.ss_quantity\" -> \"sales_summary.total_qty\"")
        }
        assertEquals(2, parallel.size)
    }

    @Test
    fun `label 里的引号与反斜杠被转义`() {
        val quoted = FormatFixtures.fullModel().copy(
            columns = listOf(
                ColumnNode(
                    column = FormatFixtures.col("we\"ird", "t\\1"),
                    isOutput = true,
                ),
            ),
            edges = emptyList(),
        )
        val escaped = DotExporter.export(quoted)
        assertContains(escaped, "\"t\\\\1.we\\\"ird\" [label=\"t\\\\1.we\\\"ird\", shape=box];")
    }

    @Test
    fun `空模型不崩`() {
        val empty = DotExporter.export(FormatFixtures.emptyModel())
        assertContains(empty, "digraph lineage {")
        assertContains(empty, "rankdir=LR;")
    }

    @Test
    fun `同一模型导出两次逐字节相同`() {
        assertEquals(DotExporter.export(model), DotExporter.export(model))
    }
}
