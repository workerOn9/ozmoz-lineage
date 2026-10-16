package io.github.workeron9.ozmoz.lineage.desktop.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 布局后的一个列节点：`depth` 是「第几跳」（列），`row` 是同层内的序号（行）。 */
public data class PlacedColumn(val column: ColumnView, val depth: Int, val row: Int)

/**
 * 血缘图的**分层布局**：`depth` = 从最上游算起的最长路径长度。
 *
 * shortcut: 分层用**全部**边算深度（含 PREDICATE / JOIN_KEY 等不传递值的边），
 * 所以谓词列也会影响列位置；D2 图变大时再按值血缘分层 + 把注释边单独成层。
 *
 * 环在血缘图里不该出现；真出现时按深度 0 兜底返回，不递归下去（画图不该把进程挂死）。
 */
public fun layeredLayout(columns: List<ColumnView>, flows: List<FlowView>): List<PlacedColumn> {
    val predecessors = HashMap<String, MutableList<String>>()
    for (flow in flows) predecessors.getOrPut(flow.to) { mutableListOf() }.add(flow.from)

    val depths = HashMap<String, Int>()
    val visiting = HashSet<String>()

    fun depthOf(id: String): Int {
        depths[id]?.let { return it }
        if (!visiting.add(id)) return 0
        val depth = 1 + (predecessors[id]?.maxOfOrNull { depthOf(it) } ?: -1)
        visiting.remove(id)
        depths[id] = depth
        return depth
    }

    val nextRow = HashMap<Int, Int>()
    return columns.map { column ->
        val depth = depthOf(column.id)
        val row = nextRow.getOrDefault(depth, 0)
        nextRow[depth] = row + 1
        PlacedColumn(column, depth, row)
    }
}

/** 拍平后的一行树（[LazyColumn] 的 item）。 */
public data class TreeRow(val node: TreeNode, val depth: Int, val expandable: Boolean)

/** 按展开态拍平树：`collapsed` 里的节点其子树不出现在结果里。 */
public fun visibleTreeRows(roots: List<TreeNode>, collapsed: Set<String>): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()

    fun walk(node: TreeNode, depth: Int) {
        rows += TreeRow(node, depth, node.children.isNotEmpty())
        if (node.id in collapsed) return
        for (child in node.children) walk(child, depth + 1)
    }

    for (root in roots) walk(root, 0)
    return rows
}

/** 点到线段的最短距离（边命中测试用：点距 < 阈值就认为点中了这条边）。 */
public fun distanceToSegment(
    pointX: Float,
    pointY: Float,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
): Float {
    val dx = endX - startX
    val dy = endY - startY
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return sqrt((pointX - startX) * (pointX - startX) + (pointY - startY) * (pointY - startY))
    val t = max(0f, min(1f, ((pointX - startX) * dx + (pointY - startY) * dy) / lengthSquared))
    val projectionX = startX + t * dx
    val projectionY = startY + t * dy
    return sqrt((pointX - projectionX) * (pointX - projectionX) + (pointY - projectionY) * (pointY - projectionY))
}
