package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.LineageModel

/**
 * 把 [LineageModel] 导出成 Graphviz `digraph lineage { ... }`。
 *
 * ## 布局
 *
 * `rankdir=LR;` 让血缘从左（上游）流向右（下游），与 Mermaid 的 `flowchart LR` 一致。
 *
 * ## 节点
 *
 * `"<id>" [label="<qualifiedName>", shape=<box|ellipse>]`：`isOutput == true` 的列
 * 用 `box`，其余用 `ellipse`；`SOURCE` 边的表级哨兵用 `shape=cylinder`（约定俗成的
 * 数据存储形状）。节点顺序 = [LineageModel.columns] 列表顺序，其后追加哨兵节点
 * （按首次出现顺序）——**不排序**，保持模型给定次序即确定性。
 *
 * ## 边
 *
 * `"<from>" -> "<to>" [label="<KIND/TRANSFORM>"];`，顺序同 [LineageModel.edges] 列表。
 * 同一对节点间的多条边**不合并**：DOT 能正确渲染平行边，且保留每条边的
 * `kind/transform` 比合并更忠实（与 Mermaid 的策略不同，那里受箭头语法限制）。
 *
 * label 里的 `"` 与 `\` 按 DOT 规则转义。
 */
public object DotExporter : LineageExporter {

    override fun export(model: LineageModel): String {
        val lines = ArrayList<String>(model.columns.size + model.edges.size + 3)
        lines += "digraph lineage {"
        lines += "    rankdir=LR;"
        for (column in model.columns) {
            val shape = if (column.isOutput) "box" else "ellipse"
            lines += "    \"${escape(column.column.id)}\" " +
                "[label=\"${escape(column.column.qualifiedName)}\", shape=$shape];"
        }
        for (sentinel in GraphProjection.sentinelNodes(model)) {
            lines += "    \"${escape(sentinel.id)}\" [label=\"${escape(sentinel.name)}\", shape=cylinder];"
        }
        for (edge in model.edges) {
            val label = "${edge.kind}/${edge.transform}"
            lines += "    \"${escape(edge.fromColumn.id)}\" -> \"${escape(edge.toColumn.id)}\" " +
                "[label=\"${escape(label)}\"];"
        }
        lines += "}"
        return lines.joinToString("\n", postfix = "\n")
    }

    /** DOT 字符串字面量转义：先转义 `\`，再转义 `"`，顺序不能反。 */
    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")
}
