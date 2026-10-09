package io.github.workeron9.ozmoz.lineage.graph

import io.github.workeron9.ozmoz.lineage.ir.LineageModel

/**
 * 持久化库里的一条记录：血缘模型 + 它的来源（文件 / 语句序号）。
 *
 * [sourceFile] 与 [statementIndex] 是**溯源信息**：一个 SQL 目录里的同一条语句
 * 在多次运行间可能变化，靠它们才能把库里的一条模型指回源文件。纯单文件调用可为 null / 0。
 */
public data class StoredModel(
    val model: LineageModel,
    val sourceFile: String? = null,
    val statementIndex: Int = 0,
)

/**
 * 血缘模型库 SPI —— 计划 §5 架构图里 `graph` 模块的 **Store SPI**（mem / sqlite / …）。
 *
 * ## 存的是模型，不是算好的图
 *
 * 实现持久化的是**每条语句的 [LineageModel]**（`ir` 契约，Lossless：span / unknown /
 * diagnostic 全在），**不是** JGraphT 邻接结构。理由：
 * - `LineageModel` 是 §5.2 冻结的对外契约，前端 / 导出 / 持久化**共用同一份**；
 *   图（[LineageGraph]）是它之上的**视图**，随时可由 `LineageGraph.of(models)` 重建。
 * - 若存算好的节点 / 边，会丢掉 `scopes` / `unknowns` / `diagnostics`——违背 Lossless。
 *
 * 因此 `ozml impact --graph` 的「持久化图」= 库里的一组模型 + 载入时现建的图。
 * 对 M2 的规模（单机、几百个文件）这是正确的取舍；需要**增量查询 / 不下全图**时
 * 再引入归一化的节点 / 边表（那时才需要新 ADR）。
 *
 * ## 实现约定
 *
 * - [save] 是**全量替换**语义：先清空再写入，保证库与本次输入一致（不做增量合并）。
 * - [load] 按**写入顺序**返回，保证确定性（golden / diff 依赖）。
 * - 实现需是 [AutoCloseable]：调用方用 `use {}` 释放连接。
 */
public interface LineageStore : AutoCloseable {

    /** 全量替换：清空现有内容后写入 [models]（顺序保留）。 */
    public fun save(models: List<StoredModel>)

    /** 按写入顺序读出全部记录。 */
    public fun load(): List<StoredModel>

    /** 清空全部记录。 */
    public fun clear()

    /** 记录条数。 */
    public fun count(): Int
}
