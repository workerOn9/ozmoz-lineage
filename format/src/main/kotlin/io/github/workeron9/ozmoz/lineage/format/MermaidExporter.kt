package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.LineageEdge
import io.github.workeron9.ozmoz.lineage.ir.LineageModel

/**
 * 把 [LineageModel] 导出成 Mermaid `flowchart LR`，可直接贴进 Markdown。
 *
 * ## 节点
 *
 * 每个 [io.github.workeron9.ozmoz.lineage.ir.ColumnNode] 一个节点：id 用**净化后**的
 * `column.id`（非 `[A-Za-z0-9_]` 一律换成 `_`，再统一加 `n_` 前缀避免数字开头、避免
 * 与 Mermaid 关键字撞车），label 用 `column.qualifiedName`；`isOutput == true` 的
 * label 追加 `(output)`。`SOURCE` 边的 `from` 是表级哨兵（`table = null`），
 * **同样作为一个节点**，label 用哨兵的 `name`。
 *
 * ## 边
 *
 * 同一对 `from → to` 若有多条边，**合并为一条**：label 用 `, ` 连接去重后的
 * `kind/transform` 片段并按字符串排序。选择合并而非「不同箭头」是因为 Mermaid 的
 * 箭头种类有限且语义不含「多条边」的信息，合并后图更小、更稳定，也避免同一对
 * 节点间的多条边互相遮挡；每条边的语义仍完整保留在 label 里。
 *
 * 分隔符**不能**用 `|`——那正是 Mermaid `-->|label|` 的定界符，会让标签被截断。
 *
 * 空模型只产出一行 `flowchart LR`，不崩。
 */
public object MermaidExporter : LineageExporter {

    override fun export(model: LineageModel): String {
        val nodes = LinkedHashMap<String, Node>()
        for (column in model.columns) {
            val ref = column.column
            nodes.putIfAbsent(
                ref.id,
                Node(id = sanitizeId(ref.id), label = nodeLabel(ref, column.isOutput)),
            )
        }
        for (sentinel in GraphProjection.sentinelNodes(model)) {
            nodes.putIfAbsent(sentinel.id, Node(id = sanitizeId(sentinel.id), label = sentinel.name))
        }

        val lines = ArrayList<String>(nodes.size + model.edges.size + 1)
        lines += "flowchart LR"
        for (node in nodes.values) {
            lines += "    ${node.id}[\"${escapeLabel(node.label)}\"]"
        }
        for (line in mergedEdgeLines(model.edges)) {
            lines += "    $line"
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    /** 节点展示名：`qualifiedName`；输出列追加 `(output)`。 */
    private fun nodeLabel(ref: ColumnRef, isOutput: Boolean): String =
        if (isOutput) "${ref.qualifiedName} (output)" else ref.qualifiedName

    /**
     * 同一 `from → to` 合并成一条边：去重后的 `KIND/TRANSFORM` 片段按字符串排序后
     * 用 `, ` 连接（**不能**用 `|`——那是 Mermaid 标签定界符）。边的顺序按模型列表里
     * **首次出现**的次序，保证确定性。
     */
    private fun mergedEdgeLines(edges: List<LineageEdge>): List<String> {
        val order = ArrayList<String>()
        val fragments = LinkedHashMap<String, MutableSet<String>>()
        for (edge in edges) {
            val from = sanitizeId(edge.fromColumn.id)
            val to = sanitizeId(edge.toColumn.id)
            val key = "$from\u0000$to"
            val set = fragments.getOrPut(key) {
                order += key
                sortedSetOf()
            }
            set += "${edge.kind}/${edge.transform}"
        }
        return order.map { key ->
            val (from, to) = key.split("\u0000")
            "$from -->|${fragments.getValue(key).joinToString(", ")}| $to"
        }
    }

    /**
     * Mermaid 节点 id 净化：非 `[A-Za-z0-9_]` → `_`，并加 `n_` 前缀
     * （避免纯数字开头被 Mermaid 当成数字字面量）。
     */
    private fun sanitizeId(raw: String): String {
        val builder = StringBuilder("n_")
        for (ch in raw) {
            val safe = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_'
            builder.append(if (safe) ch else '_')
        }
        return builder.toString()
    }

    /** label 里可能含 `"`，转义后再放进 `["..."]`。 */
    private fun escapeLabel(label: String): String = label.replace("\"", "\\\"")

    /** 一个已净化的节点：id 与展示名。 */
    private data class Node(val id: String, val label: String)
}
