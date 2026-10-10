package io.github.workeron9.ozmoz.lineage.engine.jooq

import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Span
import org.jooq.Query

/**
 * jOOQ **浅归一化树**。
 *
 * jOOQ 3.21 没有公开的 SQL AST（`QueryPart` 内部实现不设 visitor 契约），
 * 这里如实只做**一层**节点：`type` = 语句 / 查询实现类名去 `Impl` 后缀、小写
 *（`SelectImpl` → `select`、`CreateTableImpl` → `createtable`、`MergeImpl` → `merge`…），
 * `text` / `span` 取整条语句的源文切片（Lossless）。
 *
 * 「浅」是**声明出来的事实**而不是偷懒（`Feature.AST_EXPORT` 未申报，原因在能力表）：
 * jsqlparser / calcite 才是树对比的主引擎，jOOQ 定位是方言第二实现。
 */
internal class JooqAstNormalizer(private val source: String) {

    fun normalize(query: Query): AstNode {
        // 实测类名：SelectQueryImpl（select）/ InsertImpl / DeleteImpl / UpdateImpl /
        // CreateTableImpl / CreateViewImpl / MergeImpl——去 `Impl` 后缀、再去 `query` 尾。
        val type = query.javaClass.simpleName.removeSuffix("Impl").removeSuffix("Query").lowercase()
        return AstNode(
            type = type.ifBlank { "unknown_node" },
            text = source.trim(),
            span = sourceSlice(),
            children = emptyList(),
        )
    }

    /** 整条语句的源文切片（trim 掉首尾空白，偏移对应到切片边界）。 */
    private fun sourceSlice(): Span? {
        val text = source.trim()
        if (text.isEmpty()) return null
        val start = source.indexOf(text).coerceAtLeast(0)
        return Span.of(source, start, (start + text.length).coerceAtMost(source.length))
    }
}
