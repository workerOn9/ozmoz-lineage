package io.github.workeron9.ozmoz.lineage.format

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** [MermaidExporter] 的关键片段断言：首行、节点净化 id、边 label 与合并策略。 */
class MermaidExporterTest {

    private val model = FormatFixtures.fullModel()
    private val lines = MermaidExporter.export(model).lines()

    @Test
    fun `首行是 flowchart LR`() {
        assertEquals("flowchart LR", lines.first())
    }

    @Test
    fun `节点 id 净化并加前缀 label 用限定名`() {
        // store_sales.total_qty 的 column.id = "sales_summary.total_qty"
        assertContains(lines, "    n_sales_summary_total_qty[\"sales_summary.total_qty (output)\"]")
        assertContains(lines, "    n_store_sales_ss_quantity[\"store_sales.ss_quantity\"]")
    }

    @Test
    fun `SOURCE 哨兵同样作为节点`() {
        assertContains(lines, "    n_store_sales[\"store_sales\"]")
        assertContains(lines, "    n_customer[\"customer\"]")
    }

    @Test
    fun `边 label 用 KIND TRANSFORM`() {
        // total_qty 有 AGGREGATE / EXPRESSION / WINDOW / FILTER / GROUP_BY，来自 store_sales.ss_quantity 的
        // AGGREGATE 与 WINDOW 合并成一条，label 按字符串排序后用 ", " 连接（| 是 Mermaid 标签定界符，不可用）。
        assertContains(
            lines,
            "    n_store_sales_ss_quantity -->|OUTPUT/AGGREGATE, OUTPUT/WINDOW| n_sales_summary_total_qty",
        )
        // sold_date 同时被 OUTPUT/DIRECT 与 ORDER_BY/ORDERING 引用 → 合并成一条，按字符串排序
        assertContains(
            lines,
            "    n_store_sales_ss_sold_date_sk -->|ORDER_BY/ORDERING, OUTPUT/DIRECT| n_sales_summary_sold_date",
        )
        assertContains(lines, "    n_store_sales -->|SOURCE/SOURCE| n_sales_summary_total_qty")
    }

    @Test
    fun `同一对节点多条边合并为一条`() {
        // 指向 total_qty 的边来自 store_sales.ss_quantity（AGGREGATE + WINDOW 两条）合并成一条
        assertEquals(1, lines.count { it.startsWith("    n_store_sales_ss_quantity -->") })
    }

    @Test
    fun `空模型只产出一行`() {
        assertEquals("flowchart LR", MermaidExporter.export(FormatFixtures.emptyModel()).trimEnd())
    }

    @Test
    fun `同一模型导出两次逐字节相同`() {
        assertEquals(MermaidExporter.export(model), MermaidExporter.export(model))
    }
}
