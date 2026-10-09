package io.github.workeron9.ozmoz.lineage.graph

import io.github.workeron9.ozmoz.lineage.ir.ColumnNode
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageEdge
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Meta
import io.github.workeron9.ozmoz.lineage.ir.TransformKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LineageGraph] 的单测：**手工构造** [LineageModel]，不依赖 `engine-*` / `lineage`，
 * 只依赖 `ir` 契约。示例用 `t1` / `t2` / `c1` 这类中性名。
 *
 * 重点断言**确定性**（同层按 id 升序、环归一化、等长最短路取字典序先者）。
 */
class LineageGraphTest {

    /** 链 `a -> b -> c` + 分支 `a -> d`：用于上溯 / 下溯 / 同层顺序。 */
    private val chain = LineageGraph.of(
        LineageModel(
            meta = Meta(engineId = "test"),
            columns = listOf(
                ColumnNode(column = col("a", "t1"), scopeId = "s0"),
                ColumnNode(column = col("b", "t1"), scopeId = "s0"),
                ColumnNode(column = col("c", "t1"), scopeId = "s0", isOutput = true),
                ColumnNode(column = col("d", "t1"), scopeId = "s0", isOutput = true),
            ),
            edges = listOf(
                e(0, "t1.a", "t1.b"),
                e(1, "t1.b", "t1.c"),
                e(2, "t1.a", "t1.d"),
            ),
        ),
    )

    /** 含 2 节点互指环 `x <-> y` + 自环 `z -> z`。 */
    private val cyclic = LineageGraph.of(
        LineageModel(
            meta = Meta(engineId = "test"),
            columns = listOf(
                ColumnNode(column = col("x", "t1"), scopeId = "s0"),
                ColumnNode(column = col("y", "t1"), scopeId = "s0"),
                ColumnNode(column = col("z", "t1"), scopeId = "s0"),
            ),
            edges = listOf(
                e(0, "t1.x", "t1.y"),
                e(1, "t1.y", "t1.x"),
                e(2, "t1.z", "t1.z"),
            ),
        ),
    )

    // ——— impact：上溯 / 下溯 / 双向 ———

    @Test
    fun `下溯从 a 出发按层展开 同层按 id 升序`() {
        val result = chain.downstream("t1.a")

        assertEquals("t1.a", result.origin)
        assertFalse(result.truncated)
        // a(0) → b(1) 与 d(1) 同层 → id 升序；b(1) → c(2)。
        assertEquals(
            listOf("t1.a", "t1.b", "t1.d", "t1.c"),
            result.nodes.map { it.id },
        )
        assertEquals(listOf(0, 1, 1, 2), result.nodes.map { it.distance })
        assertEquals(listOf("t1.b", "t1.d", "t1.c"), result.reached)
    }

    @Test
    fun `上溯从 c 出发沿反向走到源头`() {
        val result = chain.upstream("t1.c")

        assertEquals(listOf("t1.c", "t1.b", "t1.a"), result.nodes.map { it.id })
        assertFalse(result.truncated)
    }

    @Test
    fun `双向从 b 出发两侧都能走到`() {
        val result = chain.impact("t1.b", Direction.BOTH)

        // b(0) 的反向前驱 a、正向后继 c 同层（id 升序）；d 需经 a 再走一步（层 2）。
        assertEquals(listOf("t1.b", "t1.a", "t1.c", "t1.d"), result.nodes.map { it.id })
        assertEquals(listOf(0, 1, 1, 2), result.nodes.map { it.distance })
    }

    @Test
    fun `depth 截断时 truncated 为真 且不展开更深层`() {
        val result = chain.downstream("t1.a", depth = 1)

        // 层 1 之后还有 b -> c 未展开 → truncated。
        assertTrue(result.truncated)
        assertEquals(listOf("t1.a", "t1.b", "t1.d"), result.nodes.map { it.id })
        assertTrue(result.nodes.none { it.distance == 2 })
    }

    @Test
    fun `恰好走到边界不标 truncated`() {
        // a 的下游只有一层（b、d 都无出边）→ depth = 1 时恰好走完。
        val model = LineageGraph.of(
            LineageModel(
                meta = Meta(engineId = "test"),
                edges = listOf(e(0, "t1.a", "t1.b")),
            ),
        )

        assertFalse(model.downstream("t1.a", depth = 1).truncated)
    }

    @Test
    fun `未知原点只含自身 不崩`() {
        val result = chain.impact("t1.nope", Direction.DOWNSTREAM)

        assertEquals(1, result.nodes.size)
        assertEquals("t1.nope", result.nodes.single().id)
        assertEquals(0, result.nodes.single().distance)
        assertTrue(result.reached.isEmpty())
    }

    @Test
    fun `isOutput 正确标记输出列`() {
        val result = chain.downstream("t1.a")

        assertTrue(result.nodes.single { it.id == "t1.c" }.isOutput)
        assertFalse(result.nodes.single { it.id == "t1.a" }.isOutput)
    }

    // ——— cycles ———

    @Test
    fun `2 节点互指环与自环各成一个环 且归一化`() {
        val cycles = cyclic.cycles()

        assertEquals(2, cycles.size)
        // SCC {x, y} 归一化后字典序最小者打头；自环单独一个。
        assertEquals(listOf("t1.x", "t1.y"), cycles[0].nodeIds)
        assertEquals(listOf("t1.z"), cycles[1].nodeIds)
    }

    @Test
    fun `无环图 cycles 为空`() {
        assertTrue(chain.cycles().isEmpty())
    }

    @Test
    fun `同一节点多条自环只算一个环`() {
        val model = LineageGraph.of(
            LineageModel(
                meta = Meta(engineId = "test"),
                edges = listOf(e(0, "t1.a", "t1.a", EdgeKind.PREDICATE), e(1, "t1.a", "t1.a", EdgeKind.SOURCE)),
            ),
        )

        assertEquals(1, model.cycles().size)
    }

    // ——— shortestPath ———

    @Test
    fun `最短路径按边数`() {
        assertEquals(listOf("t1.a", "t1.b", "t1.c"), chain.shortestPath("t1.a", "t1.c"))
    }

    @Test
    fun `from 等于 to 返回单点`() {
        assertEquals(listOf("t1.a"), chain.shortestPath("t1.a", "t1.a"))
    }

    @Test
    fun `不可达返回 null`() {
        assertNull(chain.shortestPath("t1.c", "t1.a"))
    }

    @Test
    fun `等长路径取字典序先者`() {
        // a -> m -> x 与 a -> n -> x 等长：走 m（字典序更小）。
        val model = LineageGraph.of(
            LineageModel(
                meta = Meta(engineId = "test"),
                edges = listOf(
                    e(0, "a", "n"), e(1, "a", "m"), e(2, "n", "x"), e(3, "m", "x"),
                ),
            ),
        )

        assertEquals(listOf("a", "m", "x"), model.shortestPath("a", "x"))
    }

    @Test
    fun `上溯方向的最短路`() {
        assertEquals(listOf("t1.c", "t1.b", "t1.a"), chain.shortestPath("t1.c", "t1.a", Direction.UPSTREAM))
    }

    // ——— 多模型合并 ———

    @Test
    fun `多模型按节点 id 合并 边按四元组去重`() {
        val m1 = LineageModel(
            meta = Meta(engineId = "test"),
            columns = listOf(ColumnNode(column = col("a", "t1"), scopeId = "s0", isOutput = true)),
            edges = listOf(e(0, "t1.b", "t1.a")),
        )
        val m2 = LineageModel(
            meta = Meta(engineId = "test"),
            columns = listOf(ColumnNode(column = col("a", "t1"), scopeId = "s1")),
            edges = listOf(e(0, "t1.b", "t1.a")),
        )

        val merged = LineageGraph.of(listOf(m1, m2))

        assertEquals(1, merged.outgoing("t1.b").size) // 同 (from, to, kind, transform) 合并
        assertTrue(merged.isOutput("t1.a")) // 任一模型标记为输出即生效
        assertEquals(setOf("t1.a", "t1.b"), merged.nodeIds)
    }

    // ——— 确定性 ———

    @Test
    fun `同一图两次查询结果相等`() {
        assertEquals(chain.downstream("t1.a"), chain.downstream("t1.a"))
        assertEquals(chain.cycles(), chain.cycles())
        assertEquals(cyclic.cycles(), cyclic.cycles())
    }

    // ——— 辅助 ———

    /** 表级哨兵 `SOURCE` 边也作为节点参与图算法。 */
    @Test
    fun `表级哨兵节点参与上溯`() {
        val model = LineageGraph.of(
            LineageModel(
                meta = Meta(engineId = "test"),
                columns = listOf(ColumnNode(column = col("x", "t1"), scopeId = "s0", isOutput = true)),
                edges = listOf(e(0, "t1", "t1.x", EdgeKind.SOURCE, TransformKind.SOURCE)),
            ),
        )

        assertEquals(listOf("t1.x", "t1"), model.upstream("t1.x").nodes.map { it.id })
        assertEquals("t1", model.label("t1"))
        assertFalse(model.isOutput("t1"))
    }

    private fun col(name: String, table: String? = null): ColumnRef = ColumnRef(
        raw = if (table == null) name else "$table.$name",
        canonical = (if (table == null) name else "$table.$name").lowercase(),
        name = name,
        table = table,
    )

    /** 从「表.列」或「裸列」/「表级哨兵名」构造端点：无点时 table 为 null（哨兵）。 */
    private fun ref(id: String): ColumnRef {
        val parts = id.split(".")
        return if (parts.size > 1) col(parts.last(), parts.first()) else col(parts.single())
    }

    private fun e(
        index: Int,
        from: String,
        to: String,
        kind: EdgeKind = EdgeKind.OUTPUT,
        transform: TransformKind = TransformKind.DIRECT,
    ): LineageEdge = LineageEdge(
        id = "e$index",
        fromColumn = ref(from),
        toColumn = ref(to),
        kind = kind,
        transform = transform,
        expression = null,
    )
}
