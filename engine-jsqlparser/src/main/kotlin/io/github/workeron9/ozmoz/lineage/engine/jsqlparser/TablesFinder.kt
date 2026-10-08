package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import io.github.workeron9.ozmoz.lineage.ir.TableRef
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.merge.Merge
import net.sf.jsqlparser.statement.select.FromItem
import net.sf.jsqlparser.statement.select.Join
import net.sf.jsqlparser.statement.select.ParenthesedFromItem
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SetOperationList
import net.sf.jsqlparser.statement.select.WithItem
import net.sf.jsqlparser.statement.update.Update
import net.sf.jsqlparser.schema.Table

/**
 * 表引用提取：**保留位置与出现顺序**，按限定名折叠去重。
 *
 * 不用 `TablesNamesFinder`：它只返回 `List<String>`，丢位置、丢别名，无法满足 Lossless。
 * 这里自己走一遍树，得到的 [TableRef] 带 span 与别名，直接喂给表级血缘。
 */
internal object TablesFinder {

    fun find(statement: Statement, source: String): List<TableRef> {
        val collected = mutableListOf<TableRef>()
        collect(statement, source, collected)
        // 按 canonical 去重，保留首次出现（含位置）
        return collected.distinctBy { it.canonical }
    }

    private fun collect(statement: Statement, source: String, out: MutableList<TableRef>) {
        when (statement) {
            is Select -> collectSelect(statement, source, out)
            is Insert -> {
                statement.table?.let { out += Identifiers.tableRef(source, it) }
                statement.withItemsList?.forEach { collectWithItem(it, source, out) }
                statement.select?.let { collectSelect(it, source, out) }
            }
            is CreateTable -> {
                statement.table?.let { out += Identifiers.tableRef(source, it) }
                statement.select?.let { collectSelect(it, source, out) }
            }
            is CreateView -> {
                // 视图名是「产出」而非「来源」，不计入 tables；其 select 的来源计入
                statement.select?.let { collectSelect(it, source, out) }
            }
            is Update -> {
                statement.table?.let { out += Identifiers.tableRef(source, it) }
                statement.startJoins?.forEach { collectJoin(it, source, out) }
                statement.fromItem?.let { collectFromItem(it, source, out) }
                statement.joins?.forEach { collectJoin(it, source, out) }
            }
            is Delete -> {
                statement.tables?.forEach { out += Identifiers.tableRef(source, it) }
                statement.table?.let { out += Identifiers.tableRef(source, it) }
                statement.joins?.forEach { collectJoin(it, source, out) }
            }
            is Merge -> {
                statement.table?.let { out += Identifiers.tableRef(source, it) }
                // getFromItem() 覆盖 USING table 与 USING (select)，getUsingTable() 已弃用
                statement.fromItem?.let { collectFromItem(it, source, out) }
            }
            else -> Unit
        }
    }

    private fun collectSelect(select: Select, source: String, out: MutableList<TableRef>) {
        select.withItemsList?.forEach { collectWithItem(it, source, out) }
        when (select) {
            is PlainSelect -> {
                select.fromItem?.let { collectFromItem(it, source, out) }
                select.joins?.forEach { collectJoin(it, source, out) }
            }
            is SetOperationList -> select.selects?.forEach { collectSelect(it, source, out) }
            is ParenthesedSelect -> select.select?.let { collectSelect(it, source, out) }
            else -> Unit
        }
    }

    private fun collectWithItem(item: WithItem<*>, source: String, out: MutableList<TableRef>) {
        item.select?.let { collectSelect(it, source, out) }
    }

    private fun collectFromItem(item: FromItem, source: String, out: MutableList<TableRef>) {
        when (item) {
            is Table -> out += Identifiers.tableRef(source, item)
            is ParenthesedSelect -> item.select?.let { collectSelect(it, source, out) }
            is ParenthesedFromItem -> {
                item.fromItem?.let { collectFromItem(it, source, out) }
                item.joins?.forEach { collectJoin(it, source, out) }
            }
            else -> Unit
        }
    }

    private fun collectJoin(join: Join, source: String, out: MutableList<TableRef>) {
        join.rightItem?.let { collectFromItem(it, source, out) }
    }
}
