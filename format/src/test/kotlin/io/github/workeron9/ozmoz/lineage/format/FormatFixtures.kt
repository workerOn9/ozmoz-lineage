package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.ColumnNode
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageEdge
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Meta
import io.github.workeron9.ozmoz.lineage.ir.TransformKind

/**
 * 测试用的**手工构造**血缘模型——不依赖 `engine-*` / `lineage` 任何模块，
 * 只依赖 `ir` 契约。示例一律用 `store_sales` / `customer` / `sales_summary`
 * 这类中性名，不引用任何真实业务表。
 */
internal object FormatFixtures {

    /** 列引用：`table` 为空表示表级哨兵（`SOURCE` 边的 from）。 */
    fun col(name: String, table: String? = null): ColumnRef = ColumnRef(
        raw = if (table == null) name else "$table.$name",
        canonical = (if (table == null) name else "$table.$name").lowercase(),
        name = name,
        table = table,
    )

    /**
     * 覆盖全部 9 种有边变换的模型：
     *
     * - 输出表唯一（`sales_summary`），因此 OpenLineage 会产出一个 `outputs` 数据集。
     * - 输入表 `store_sales` / `customer` 各有一条 `SOURCE` 哨兵边。
     * - 边包含 OUTPUT/DIRECT、OUTPUT/EXPRESSION、OUTPUT/AGGREGATE、OUTPUT/WINDOW、
     *   OUTPUT/CASE_BRANCH、PREDICATE/FILTER、JOIN_KEY、GROUP_BY、ORDER_BY、SOURCE。
     */
    fun fullModel(): LineageModel = LineageModel(
        meta = Meta(engineId = "jsqlparser", dialect = "ansi"),
        columns = listOf(
            ColumnNode(column = col("c_customer_sk", "customer"), scopeId = "s0"),
            ColumnNode(column = col("ss_quantity", "store_sales"), scopeId = "s0"),
            ColumnNode(column = col("ss_sold_date_sk", "store_sales"), scopeId = "s0"),
            ColumnNode(column = col("ss_customer_sk", "store_sales"), scopeId = "s0"),
            ColumnNode(column = col("total_qty", "sales_summary"), scopeId = "s0", isOutput = true),
            ColumnNode(column = col("sold_date", "sales_summary"), scopeId = "s0", isOutput = true),
        ),
        edges = listOf(
            edge(0, col("ss_quantity", "store_sales"), col("total_qty", "sales_summary"),
                EdgeKind.OUTPUT, TransformKind.AGGREGATE, "SUM(ss_quantity)"),
            edge(1, col("ss_sold_date_sk", "store_sales"), col("sold_date", "sales_summary"),
                EdgeKind.OUTPUT, TransformKind.DIRECT, "ss_sold_date_sk"),
            edge(2, col("c_customer_sk", "customer"), col("total_qty", "sales_summary"),
                EdgeKind.OUTPUT, TransformKind.EXPRESSION, "c_customer_sk * 2"),
            edge(3, col("ss_quantity", "store_sales"), col("total_qty", "sales_summary"),
                EdgeKind.OUTPUT, TransformKind.WINDOW, "ROW_NUMBER() OVER (PARTITION BY ss_sold_date_sk)"),
            edge(4, col("c_customer_sk", "customer"), col("sold_date", "sales_summary"),
                EdgeKind.OUTPUT, TransformKind.CASE_BRANCH, "CASE WHEN c_customer_sk > 0 THEN 1 ELSE 0 END"),
            edge(5, col("c_customer_sk", "customer"), col("total_qty", "sales_summary"),
                EdgeKind.PREDICATE, TransformKind.FILTER_PREDICATE, "c_customer_sk > 0"),
            edge(6, col("ss_customer_sk", "store_sales"), col("c_customer_sk", "customer"),
                EdgeKind.JOIN_KEY, TransformKind.JOIN_KEY, "store_sales.ss_customer_sk = customer.c_customer_sk"),
            edge(7, col("ss_sold_date_sk", "store_sales"), col("total_qty", "sales_summary"),
                EdgeKind.GROUP_BY, TransformKind.GROUPING, "ss_sold_date_sk"),
            edge(8, col("ss_sold_date_sk", "store_sales"), col("sold_date", "sales_summary"),
                EdgeKind.ORDER_BY, TransformKind.ORDERING, "ss_sold_date_sk"),
            edge(9, col("store_sales"), col("total_qty", "sales_summary"),
                EdgeKind.SOURCE, TransformKind.SOURCE, null),
            edge(10, col("customer"), col("sold_date", "sales_summary"),
                EdgeKind.SOURCE, TransformKind.SOURCE, null),
        ),
    )

    /**
     * 输出列落在**两张不同表**上 → 推不出唯一输出表。
     * 用于断言 OpenLineage 此时不产 `outputs` 数据集（不编表名）。
     */
    fun ambiguousOutputModel(): LineageModel = LineageModel(
        meta = Meta(engineId = "jsqlparser"),
        columns = listOf(
            ColumnNode(column = col("ss_quantity", "store_sales"), scopeId = "s0"),
            ColumnNode(column = col("a", "out_one"), scopeId = "s0", isOutput = true),
            ColumnNode(column = col("b", "out_two"), scopeId = "s0", isOutput = true),
        ),
        edges = listOf(
            edge(0, col("ss_quantity", "store_sales"), col("a", "out_one"),
                EdgeKind.OUTPUT, TransformKind.DIRECT, "ss_quantity"),
            edge(1, col("ss_quantity", "store_sales"), col("b", "out_two"),
                EdgeKind.OUTPUT, TransformKind.DIRECT, "ss_quantity"),
        ),
    )

    /** 空模型：只有元信息，无边无列——导出器不得崩。 */
    fun emptyModel(): LineageModel = LineageModel()

    private fun edge(
        index: Int,
        from: ColumnRef,
        to: ColumnRef,
        kind: EdgeKind,
        transform: TransformKind,
        expression: String?,
    ): LineageEdge = LineageEdge(
        id = "e$index",
        fromColumn = from,
        toColumn = to,
        kind = kind,
        transform = transform,
        expression = expression,
    )
}
