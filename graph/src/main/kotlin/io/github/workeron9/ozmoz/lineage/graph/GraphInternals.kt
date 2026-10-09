package io.github.workeron9.ozmoz.lineage.graph

import org.jgrapht.Graphs
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector
import org.jgrapht.graph.DefaultDirectedGraph
import org.jgrapht.graph.DefaultEdge

/**
 * JGraphT 支撑的**内部**有向图存储——实现细节，**不对外暴露**。
 *
 * 公开契约文件 `LineageGraph.kt` 里不出现任何 JGraphT 类型（连 import 都不许有），
 * 图算法库只在本文件里出现。这样「换掉 JGraphT」只需改这一处，公共签名零影响。
 *
 * 节点 = 列 id（String）；平行边在 DefaultDirectedGraph 里会被折叠成一条
 * （`addEdge` 返回 null），这对可达性 / 强连通 / 最短路**无影响**——它们只关心
 * 「谁连谁」。真正的边（含 id / kind / transform）由 `LineageGraph.edges` 持有。
 */
internal class GraphInternals(
    nodeIds: Collection<String>,
    edges: List<GraphEdge>,
) {

    private val graph: DefaultDirectedGraph<String, DefaultEdge> =
        DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge::class.java)

    init {
        for (id in nodeIds) graph.addVertex(id)
        for (edge in edges) {
            // 防御：端点若不在 nodeIds 里也补进来，避免 addEdge 抛 IllegalArgumentException。
            graph.addVertex(edge.from)
            graph.addVertex(edge.to)
            graph.addEdge(edge.from, edge.to)
        }
    }

    /** 直接后继（出边终点）。未知节点返回空集。 */
    fun successors(id: String): Set<String> =
        if (graph.containsVertex(id)) Graphs.successorListOf(graph, id).toSet() else emptySet()

    /** 直接前驱（入边起点）。未知节点返回空集。 */
    fun predecessors(id: String): Set<String> =
        if (graph.containsVertex(id)) Graphs.predecessorListOf(graph, id).toSet() else emptySet()

    /** 强连通分量（含单点分量）。成员集合顺序不保证——调用方必须自行排序。 */
    fun stronglyConnectedSets(): List<Set<String>> =
        KosarajuStrongConnectivityInspector(graph).stronglyConnectedSets()
}
