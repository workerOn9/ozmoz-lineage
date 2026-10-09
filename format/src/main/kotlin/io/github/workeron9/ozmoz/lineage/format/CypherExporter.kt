package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageModel

/**
 * 把 [LineageModel] 导出成 Neo4j **Cypher 建图语句**，可直接 `:source` 进 `cypher-shell`。
 *
 * ## 节点
 *
 * `MERGE (n0:Column {id: '...'}) SET n0.name = '...'`：列节点用 `Column` 标签，
 * `SOURCE` 边的表级哨兵用 `Table` 标签。所有值都是**参数化字面量**（单引号字符串，
 * `'` 与 `\` 已转义），**不拼接标识符**——避免注入，也便于复制到浏览器。
 *
 * 变量名 `n0` / `n1` / … 按节点**出现顺序**分配（列在前、哨兵随后，顺序同模型列表），
 * 因此同一模型产出的文本逐字节稳定。
 *
 * ## 关系
 *
 * `MERGE (n0)-[:OUTPUT {transform: 'DIRECT'}]->(n1)`：关系类型用 [EdgeKind] 名，
 * 变换种类放进 `transform` 属性。端点引用上面已 `MERGE` 的变量，**不会**重复建节点。
 * 语句顺序同 [LineageModel.edges] 列表，每条语句一行，末尾换行。
 */
public object CypherExporter : LineageExporter {

    /** 列节点标签。 */
    public const val COLUMN_LABEL: String = "Column"

    /** 表级哨兵节点标签。 */
    public const val TABLE_LABEL: String = "Table"

    override fun export(model: LineageModel): String {
        val nodes = LinkedHashMap<String, NodeSpec>()
        for (column in model.columns) {
            nodes.putIfAbsent(column.column.id, NodeSpec(COLUMN_LABEL, column.column.qualifiedName))
        }
        for (sentinel in GraphProjection.sentinelNodes(model)) {
            nodes.putIfAbsent(sentinel.id, NodeSpec(TABLE_LABEL, sentinel.name))
        }
        // 兜底：边端点若未登记为节点（模型允许边指向未列入 columns 的列），
        // 仍分配变量并按边类型判定标签——不丢信息，也不重复建同一节点。
        for (edge in model.edges) {
            registerEndpoint(nodes, edge.fromColumn, edge.kind == EdgeKind.SOURCE)
            registerEndpoint(nodes, edge.toColumn, false)
        }

        val variables = LinkedHashMap<String, String>()
        var counter = 0
        for (id in nodes.keys) {
            variables[id] = "n${counter++}"
        }

        val lines = ArrayList<String>(nodes.size + model.edges.size)
        for ((id, spec) in nodes) {
            val variable = variables.getValue(id)
            lines += "MERGE ($variable:${spec.label} {id: '${literal(id)}'}) " +
                "SET $variable.name = '${literal(spec.name)}'"
        }
        for (edge in model.edges) {
            val from = variables.getValue(edge.fromColumn.id)
            val to = variables.getValue(edge.toColumn.id)
            lines += "MERGE ($from)-[:${edge.kind} {transform: '${literal(edge.transform.name)}'}]->($to)"
        }
        if (lines.isEmpty()) return ""
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun registerEndpoint(nodes: MutableMap<String, NodeSpec>, ref: ColumnRef, isSentinel: Boolean) {
        if (nodes.containsKey(ref.id)) return
        nodes[ref.id] = if (isSentinel) NodeSpec(TABLE_LABEL, ref.name) else NodeSpec(COLUMN_LABEL, ref.qualifiedName)
    }

    /** Cypher 单引号字符串转义：先 `\` 后 `'`，顺序不能反。 */
    private fun literal(text: String): String = text.replace("\\", "\\\\").replace("'", "\\'")

    /** 一个节点：标签 + `name` 属性值。 */
    private data class NodeSpec(val label: String, val name: String)
}
