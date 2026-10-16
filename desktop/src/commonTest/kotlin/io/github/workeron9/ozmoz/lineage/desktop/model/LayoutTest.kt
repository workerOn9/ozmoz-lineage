package io.github.workeron9.ozmoz.lineage.desktop.model

import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutTest {

    private fun column(id: String): ColumnView = ColumnView(id = id, qualifiedName = id, isOutput = false)

    private fun flow(from: String, to: String): FlowView =
        FlowView(from = from, to = to, kind = FlowKind.OUTPUT, transform = FlowTransform.DIRECT)

    /** `a -> b -> c` 外加一条 `a -> c` 的谓词边：深度取**最长路径**，不是最短。 */
    @Test
    fun depthIsLongestPath() {
        val placed = layeredLayout(
            listOf(column("a"), column("b"), column("c")),
            listOf(flow("a", "b"), flow("b", "c"), flow("a", "c")),
        ).associateBy { it.column.id }
        assertEquals(0, placed.getValue("a").depth)
        assertEquals(1, placed.getValue("b").depth)
        assertEquals(2, placed.getValue("c").depth)
    }

    /** 同层内按输入顺序排行，不做第二次排序（确定性：同一份 SQL 每次画出来一个样）。 */
    @Test
    fun rowsFollowInputOrder() {
        val placed = layeredLayout(
            listOf(column("a"), column("b"), column("c")),
            listOf(flow("a", "c"), flow("b", "c")),
        ).associateBy { it.column.id }
        // a、b 同在深度 0（都是 c 的上游），行号按输入顺序 0 / 1；c 独占深度 1 的第 0 行
        assertEquals(0, placed.getValue("a").depth)
        assertEquals(0, placed.getValue("b").depth)
        assertEquals(listOf(0, 1), listOf(placed.getValue("a").row, placed.getValue("b").row))
        assertEquals(0, placed.getValue("c").row)
    }

    /** 环不该出现在血缘图里；真出现时也要有个位置，不能递归挂死或丢节点。 */
    @Test
    fun cycleTerminates() {
        val placed = layeredLayout(listOf(column("a"), column("b")), listOf(flow("a", "b"), flow("b", "a")))
        assertEquals(2, placed.size)
    }

    @Test
    fun collapsedSubtreeIsHidden() {
        val tree = TreeNode(
            id = "n0",
            type = "select",
            text = "SELECT a FROM t",
            children = listOf(
                TreeNode(id = "n0/0", type = "column_ref", text = "a"),
                TreeNode(
                    id = "n0/1",
                    type = "from",
                    text = "FROM t",
                    children = listOf(TreeNode(id = "n0/1/0", type = "table", text = "t")),
                ),
            ),
        )
        val all = visibleTreeRows(listOf(tree), emptySet())
        assertEquals(4, all.size)
        assertEquals(2, all.count { it.expandable })

        val collapsed = visibleTreeRows(listOf(tree), setOf("n0/1"))
        assertEquals(3, collapsed.size)
        assertEquals(listOf("n0", "n0/1"), collapsed.filter { it.expandable }.map { it.node.id })
    }

    @Test
    fun pointToSegmentDistance() {
        assertEquals(3f, distanceToSegment(5f, 3f, 0f, 0f, 10f, 0f))
        // 落在线段外：取到最近端点的距离，不按延长线算
        assertEquals(1f, distanceToSegment(-1f, 0f, 0f, 0f, 10f, 0f))
    }
}
