package io.github.workeron9.ozmoz.lineage.graph

import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.TransformKind

/**
 * 遍历方向。
 *
 * - [UPSTREAM] 上溯：这一列的值**从哪来**（沿边的 from ← to 反向走）。
 * - [DOWNSTREAM] 下溯 / 影响面：这一列**变了会波及谁**（沿边的 from → to 正向走）。
 * - [BOTH] 双向：两侧都走（用于「这一列牵扯到哪些列」的探查）。
 */
public enum class Direction { UPSTREAM, DOWNSTREAM, BOTH }

/**
 * 图里的一条边——由 [io.github.workeron9.ozmoz.lineage.ir.LineageEdge] 归一化而来。
 *
 * 端点用**列 id**（`ColumnRef.id`，折叠后的稳定标识）表示；`SOURCE` 边的 `from`
 * 是表级哨兵（其 id 就是表限定名），同样作为图中的一个节点。
 */
public data class GraphEdge(
    val id: String,
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val transform: TransformKind,
)

/** 影响面 / 可达性结果里的一个节点。 */
public data class ImpactNode(
    /** 列 id（或表级哨兵 id）。 */
    val id: String,
    /** 距原点（含原点自身为 0）的边数。 */
    val distance: Int,
    /** 是否输出列（`ColumnNode.isOutput`）；表级哨兵为 false。 */
    val isOutput: Boolean,
)

/**
 * 一次影响面 / 可达性查询的结果。
 *
 * [nodes] 按**层序（BFS）**排列：同层内按 [ImpactNode.id] 升序——保证确定性。
 * [truncated] 表示是否因为 [depth] 限制而截断了更深层（有未展开的邻居）。
 */
public data class ImpactResult(
    val origin: String,
    val direction: Direction,
    /** 限制的层数；null 表示不限层。 */
    val depth: Int?,
    val nodes: List<ImpactNode>,
    val truncated: Boolean,
) {
    /** 除原点外的可达节点 id（按层序）。 */
    public val reached: List<String> get() = nodes.filter { it.distance > 0 }.map { it.id }
}

/**
 * 一个**环**（有向环）。
 *
 * 本项目用强连通分量（SCC）刻画环：规模 > 1 的 SCC 每个是一个环（成员之间存在
 * 互相可达的路径），另有自环（`a → a`）。[nodeIds] 已**归一化**：循环右移使字典序
 * 最小的 id 打头，保证同一环只有一种表示（确定性）。
 */
public data class Cycle(val nodeIds: List<String>)

/**
 * 血缘**图**——在 [LineageModel] 之上做图算法（上溯 / 下溯 / 影响面 / 环检测 / 最短路径）。
 *
 * 图算法的**权威实现只此一处**：`cli` / `format` / 将来的 `server`（Web UI）都消费它，
 * 不在各自的层里重写遍历——否则同一张图在不同出口给出不同答案。
 *
 * ## 节点与边的身份
 *
 * 节点 = 列的 `ColumnRef.id`（折叠后稳定标识）；表级哨兵（`SOURCE` 边的 from）
 * 也作为节点参与。**多模型合并**（[of] 的 `Iterable` 重载，用于一个 SQL 目录）时按 id
 * 合并同名列——但**跨模型的裸列名（`a`）与限定名（`t.a`）不会自动缝合**：模型里没有
 * 足够信息判断它们是不是同一列（Never-wrong：不猜）。这是已知限制，写在 KDoc 里。
 *
 * ## 确定性
 *
 * 同一输入永远产出同一结果：BFS 同层按 id 升序、环按归一化后的表示排序、
 * 最短路径在等长时按字典序取先者。golden 测试与 UI diff 都依赖这一点。
 */
public class LineageGraph private constructor(
    private val graphEdges: List<GraphEdge>,
    /** 列 id → 列引用（用于展示名）；含表级哨兵。 */
    public val columns: Map<String, ColumnRef>,
    /** 输出列 id 集合。 */
    private val outputIds: Set<String>,
) {

    /** JGraphT 支撑的邻接结构（实现细节，不出现在公共签名里）。 */
    private val internals: GraphInternals = GraphInternals(columns.keys, graphEdges)

    /** 全部节点 id（列 + 表级哨兵），稳定顺序（首次出现序）。 */
    public val nodeIds: Set<String> get() = columns.keys

    /** 全部边，稳定顺序（按边 id、from、to、kind 排序）。 */
    public val edges: List<GraphEdge>
        get() = graphEdges.sortedWith(compareBy({ it.id }, { it.from }, { it.to }, { it.kind.name }))

    /** 该节点的展示名（列引用原文）；未知节点返回其 id。 */
    public fun label(columnId: String): String = columns[columnId]?.qualifiedName ?: columnId

    /** 该节点是否输出列。 */
    public fun isOutput(columnId: String): Boolean = columnId in outputIds

    /** 出边（按边 id 升序）。 */
    public fun outgoing(columnId: String): List<GraphEdge> =
        graphEdges.filter { it.from == columnId }.sortedBy { it.id }

    /** 入边（按边 id 升序）。 */
    public fun incoming(columnId: String): List<GraphEdge> =
        graphEdges.filter { it.to == columnId }.sortedBy { it.id }

    /**
     * 从 [origin] 出发按 [direction] 做**广度优先**可达性 / 影响面，[depth] 限制层数（null 不限）。
     *
     * - [Direction.DOWNSTREAM]：沿 `from → to` 正向走（我改了会波及谁）。
     * - [Direction.UPSTREAM]：沿 `to → from` 反向走（我的值从哪来）。
     * - [Direction.BOTH]：两个方向都走（同层合并去重）。
     * - 未知 [origin] → 只含原点自身（distance 0）的结果，不崩。
     */
    public fun impact(origin: String, direction: Direction = Direction.UPSTREAM, depth: Int? = null): ImpactResult {
        val nodes = mutableListOf(ImpactNode(origin, 0, isOutput(origin)))
        val visited = LinkedHashSet<String>()
        visited += origin
        // 用有序集合承载每一层：同层节点按 id 升序展开，不依赖 HashSet 遍历序。
        var frontier: java.util.SortedSet<String> = sortedSetOf(origin)
        var distance = 0
        var truncated = false
        while (frontier.isNotEmpty()) {
            if (depth != null && distance >= depth) {
                // 已到层数上限：还有未展开的邻居 → 截断（否则只是「恰好走到边界」）。
                truncated = frontier.any { n -> neighbors(n, direction).any { it !in visited } }
                break
            }
            val next = sortedSetOf<String>()
            for (n in frontier) {
                for (m in neighbors(n, direction)) {
                    if (visited.add(m)) next.add(m)
                }
            }
            if (next.isEmpty()) break
            distance++
            for (n in next) nodes += ImpactNode(n, distance, isOutput(n))
            frontier = next
        }
        return ImpactResult(origin, direction, depth, nodes, truncated)
    }

    /** 影响面 = 下溯（我改了会波及谁）。 */
    public fun downstream(origin: String, depth: Int? = null): ImpactResult =
        impact(origin, Direction.DOWNSTREAM, depth)

    /** 上溯（我的值从哪来）。 */
    public fun upstream(origin: String, depth: Int? = null): ImpactResult =
        impact(origin, Direction.UPSTREAM, depth)

    /**
     * 全部有向环（SCC 规模 > 1 的每个分量 + 全部自环），按 [Cycle.nodeIds] 的字符串表示排序。
     * 无环返回空列表。
     */
    public fun cycles(): List<Cycle> {
        val result = mutableListOf<Cycle>()
        for (scc in internals.stronglyConnectedSets()) {
            if (scc.size > 1) result += Cycle(normalizeCycle(scc))
        }
        // 自环（a → a）：SCC 里是单点分量，需单独补上。同一节点多条自环只算一个环。
        val selfLoopNodes = graphEdges.asSequence()
            .filter { it.from == it.to }
            .map { it.from }
            .distinct()
            .sorted()
            .toList()
        for (id in selfLoopNodes) result += Cycle(listOf(id))
        return result.sortedBy { it.nodeIds.toString() }
    }

    /**
     * 两列之间的**最短路径**（按边数）；不可达返回 null。[from] == [to] 返回 `[from]`。
     * 等长路径取字典序先者（确定性）。[direction] 决定沿哪个方向走（默认下溯）。
     */
    public fun shortestPath(
        from: String,
        to: String,
        direction: Direction = Direction.DOWNSTREAM,
    ): List<String>? {
        if (from == to) return listOf(from)
        // BFS 逐层推进；每层对同一节点收集所有候选路径，取字典序最小者。
        // 因为到某节点的所有最短路径等长，前缀字典序最小者必能支配更长的后缀比较。
        val best = HashMap<String, List<String>>()
        best[from] = listOf(from)
        val visited = HashSet<String>()
        visited += from
        var frontier: List<String> = listOf(from)
        while (frontier.isNotEmpty()) {
            val candidates = LinkedHashMap<String, MutableList<List<String>>>()
            for (u in frontier) {
                val prefix = best.getValue(u)
                for (v in neighbors(u, direction)) {
                    if (v in visited) continue
                    candidates.getOrPut(v) { mutableListOf() } += prefix + v
                }
            }
            if (candidates.isEmpty()) break
            for ((v, paths) in candidates) {
                best[v] = paths.reduce { acc, p -> if (lexLess(p, acc)) p else acc }
                visited += v
            }
            best[to]?.let { return it }
            frontier = candidates.keys.toList()
        }
        return null
    }

    /** 按 [direction] 取 [id] 的邻居（不保证顺序；调用方自行排序）。 */
    private fun neighbors(id: String, direction: Direction): Collection<String> = when (direction) {
        Direction.DOWNSTREAM -> internals.successors(id)
        Direction.UPSTREAM -> internals.predecessors(id)
        Direction.BOTH -> internals.successors(id) + internals.predecessors(id)
    }

    /** 环归一化：成员按 id 升序，最小者自然打头（循环右移量为 0）。 */
    private fun normalizeCycle(members: Collection<String>): List<String> = members.sorted()

    /** 列表字典序比较（[a] 严格小于 [b] 时为 true）。 */
    private fun lexLess(a: List<String>, b: List<String>): Boolean {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c < 0
        }
        return a.size < b.size
    }

    public companion object {

        /** 单模型建图。 */
        @JvmStatic
        public fun of(model: LineageModel): LineageGraph = build(listOf(model), dedupeEdges = false)

        /**
         * 多模型建图（一个 SQL 目录）：按节点 id 合并；边按 (from, to, kind, transform)
         * 去重后保留**首次出现**的边 id。**不跨模型缝合裸列名与限定名**（见类 KDoc）。
         */
        @JvmStatic
        public fun of(models: Iterable<LineageModel>): LineageGraph = build(models, dedupeEdges = true)

        private fun build(models: Iterable<LineageModel>, dedupeEdges: Boolean): LineageGraph {
            val columns = LinkedHashMap<String, ColumnRef>()
            val outputIds = LinkedHashSet<String>()
            val edges = mutableListOf<GraphEdge>()
            val seenEdgeKeys = HashSet<EdgeKey>()
            for (model in models) {
                // 先登记声明的列节点（首次出现者保留其列引用），再登记边端点（补齐表级哨兵）。
                for (node in model.columns) {
                    columns.putIfAbsent(node.column.id, node.column)
                    if (node.isOutput) outputIds += node.column.id
                }
                for (edge in model.edges) {
                    columns.putIfAbsent(edge.fromColumn.id, edge.fromColumn)
                    columns.putIfAbsent(edge.toColumn.id, edge.toColumn)
                    if (dedupeEdges) {
                        val key = EdgeKey(
                            edge.fromColumn.id,
                            edge.toColumn.id,
                            edge.kind,
                            edge.transform,
                        )
                        if (!seenEdgeKeys.add(key)) continue
                    }
                    edges += GraphEdge(
                        edge.id,
                        edge.fromColumn.id,
                        edge.toColumn.id,
                        edge.kind,
                        edge.transform,
                    )
                }
            }
            return LineageGraph(edges, columns, outputIds)
        }
    }
}

/** 多模型边的去重键（不含 id：首次出现者保留自己的 id）。 */
private data class EdgeKey(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val transform: TransformKind,
)
