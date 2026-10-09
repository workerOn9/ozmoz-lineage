package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageModel

/**
 * 导出器共用的**模型投影**：把 [LineageModel] 里隐含的结构按确定性规则抽出来。
 *
 * 模型本身没有「输入数据集 / 输出数据集」字段——那是 OpenLineage 的表述，
 * 不是本项目的契约。投影只做**可从模型确知**的推导，推不出就返回 null / 空
 * （Never-wrong：宁可缺，也不编一个看起来合理的名字）。
 *
 * 全部函数都只用 `LinkedHashMap` / `sorted`，不依赖 `HashMap` 遍历序，保证逐字节确定性。
 */
internal object GraphProjection {

    /**
     * 表级哨兵节点：`SOURCE` 边的 `from` 是「表 / CTE 整体」而非某一列
     * （`table = null`，`name` 即表的限定名）。按**首次出现顺序**去重。
     */
    fun sentinelNodes(model: LineageModel): List<ColumnRef> {
        val seen = LinkedHashMap<String, ColumnRef>()
        for (edge in model.edges) {
            if (edge.kind != EdgeKind.SOURCE) continue
            seen.putIfAbsent(edge.fromColumn.id, edge.fromColumn)
        }
        return seen.values.toList()
    }

    /**
     * 非 `SOURCE` 边的 `from` 表名（`fromColumn.table`，即表的限定名），去重后**升序排序**。
     *
     * `SOURCE` 边不参与——它的 `from` 没有 `table`，表名在 `name` 里，
     * 由 [sentinelNodes] 另行提供，避免把两种来源混在一处。
     */
    fun inputTableNames(model: LineageModel): List<String> =
        model.edges.asSequence()
            .filter { it.kind != EdgeKind.SOURCE }
            .mapNotNull { it.fromColumn.table }
            .distinct()
            .sorted()
            .toList()

    /**
     * **唯一**输出表名：`isOutput == true` 的列的 `table` 去重后**恰好一个非空值** → 该值。
     *
     * 0 个（全是 null）或多个不同表名 → null。此时调用方不得编造表名
     * （例如把 `outputs` 留空），否则就是在猜。
     */
    fun uniqueOutputTable(model: LineageModel): String? =
        model.columns.asSequence()
            .filter { it.isOutput }
            .mapNotNull { it.column.table }
            .distinct()
            .singleOrNull()
}
