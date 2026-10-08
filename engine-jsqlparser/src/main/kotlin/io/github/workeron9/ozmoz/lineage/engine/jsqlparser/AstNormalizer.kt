package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import io.github.workeron9.ozmoz.lineage.ir.AstNode
import net.sf.jsqlparser.expression.Alias
import net.sf.jsqlparser.expression.AnalyticExpression
import net.sf.jsqlparser.expression.BinaryExpression
import net.sf.jsqlparser.expression.CaseExpression
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.Parenthesis
import net.sf.jsqlparser.expression.SignedExpression
import net.sf.jsqlparser.expression.WhenClause
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.AllColumns
import net.sf.jsqlparser.statement.select.AllTableColumns
import net.sf.jsqlparser.statement.select.FromItem
import net.sf.jsqlparser.statement.select.Join
import net.sf.jsqlparser.statement.select.ParenthesedFromItem
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SelectItem
import net.sf.jsqlparser.statement.select.SetOperationList
import net.sf.jsqlparser.statement.select.WithItem
import net.sf.jsqlparser.statement.update.Update

/**
 * 把 JSqlParser 的树拍成**归一化树**（`ir.AstNode`），供可视化对比与 JSON 导出。
 *
 * 这不是「统一 AST」：节点只有 `type` / `text` / `span` / `children`，类型名是本适配器
 * 自定的稳定标识。其它引擎的 normalizer 各写各的，对比模块只 diff 两个 JSON 树。
 *
 * 设计取舍：
 * - `text` 一律取**源文本切片**（Lossless），拿不到 span 才回落到 `toString()`。
 * - 不认识的节点不丢：包成 `unknown_node`，把引擎的 `toString()` 留作 `text`，
 *   并把引擎类名放进 `type` 后缀，便于排查（Never-wrong：不猜它是什么）。
 */
internal class AstNormalizer(private val source: String) {

    fun normalize(statement: Statement): AstNode = when (statement) {
        is Select -> select(statement)
        is Insert -> insert(statement)
        is CreateTable -> createTable(statement)
        is CreateView -> createView(statement)
        is Update -> update(statement)
        is Delete -> delete(statement)
        else -> unknown(statement)
    }

    // ——— 语句层 ———

    private fun select(select: Select): AstNode {
        val children = mutableListOf<AstNode>()
        select.withItemsList?.forEach { children += withItem(it) }
        val body = when (select) {
            is PlainSelect -> plainSelect(select)
            is SetOperationList -> setOperationList(select)
            is ParenthesedSelect -> select.select?.let { select(it) } ?: unknown(select)
            else -> unknown(select)
        }
        children += body
        return node("select", select, children)
    }

    private fun withItem(item: WithItem<*>): AstNode {
        val children = mutableListOf<AstNode>()
        item.select?.let { children += select(it) }
        return node("cte", item, children)
    }

    private fun plainSelect(ps: PlainSelect): AstNode {
        val children = mutableListOf<AstNode>()
        ps.fromItem?.let { children += fromItem(it) }
        ps.joins?.forEach { children += join(it) }
        ps.selectItems?.forEach { children += selectItem(it) }
        ps.where?.let { children += node("where", it, listOf(expression(it))) }
        ps.groupBy?.groupByExpressionList?.expressions?.forEach {
            children += node("group_by", it, listOf(expression(it)))
        }
        ps.having?.let { children += node("having", it, listOf(expression(it))) }
        ps.orderByElements?.forEach {
            children += node("order_by", it, listOf(expression(it.expression)))
        }
        return node("plain_select", ps, children)
    }

    private fun setOperationList(sol: SetOperationList): AstNode {
        val children = mutableListOf<AstNode>()
        sol.selects?.forEach { children += select(it) }
        return node("set_operation", sol, children)
    }

    private fun insert(insert: Insert): AstNode {
        val children = mutableListOf<AstNode>()
        insert.withItemsList?.forEach { children += withItem(it) }
        insert.table?.let { children += node("target_table", it, emptyList()) }
        insert.columns?.expressions?.forEach {
            children += node("target_column", it, emptyList())
        }
        insert.select?.let { children += select(it) }
        return node("insert", insert, children)
    }

    private fun createTable(ct: CreateTable): AstNode {
        val children = mutableListOf<AstNode>()
        ct.table?.let { children += node("target_table", it, emptyList()) }
        ct.select?.let { children += select(it) }
        return node("create_table", ct, children)
    }

    private fun createView(cv: CreateView): AstNode {
        val children = mutableListOf<AstNode>()
        cv.view?.let { children += node("target_view", it, emptyList()) }
        cv.select?.let { children += select(it) }
        return node("create_view", cv, children)
    }

    private fun update(update: Update): AstNode {
        val children = mutableListOf<AstNode>()
        update.table?.let { children += node("target_table", it, emptyList()) }
        update.startJoins?.forEach { children += join(it) }
        update.fromItem?.let { children += fromItem(it) }
        update.joins?.forEach { children += join(it) }
        update.where?.let { children += node("where", it, listOf(expression(it))) }
        return node("update", update, children)
    }

    private fun delete(delete: Delete): AstNode {
        val children = mutableListOf<AstNode>()
        delete.table?.let { children += node("target_table", it, emptyList()) }
        delete.joins?.forEach { children += join(it) }
        delete.where?.let { children += node("where", it, listOf(expression(it))) }
        return node("delete", delete, children)
    }

    // ——— FROM / JOIN ———

    private fun fromItem(item: FromItem): AstNode = when (item) {
        is Table -> node("table", item, emptyList())
        is ParenthesedSelect -> node("derived_table", item, listOfNotNull(item.select?.let { select(it) }))
        is ParenthesedFromItem -> {
            val children = mutableListOf<AstNode>()
            item.fromItem?.let { children += fromItem(it) }
            item.joins?.forEach { children += join(it) }
            node("parenthesed_from", item, children)
        }
        else -> unknown(item)
    }

    private fun join(join: Join): AstNode {
        val children = mutableListOf<AstNode>()
        join.rightItem?.let { children += fromItem(it) }
        join.onExpressions?.forEach { children += node("on", it, listOf(expression(it))) }
        join.usingColumns?.forEach { children += node("using_column", it, emptyList()) }
        return node("join", join, children)
    }

    // ——— SELECT 项 ———

    private fun selectItem(item: SelectItem<*>): AstNode {
        val children = mutableListOf<AstNode>()
        item.expression?.let { children += expression(it) }
        item.alias?.let { children += alias(it) }
        return node("select_item", item, children)
    }

    private fun alias(alias: Alias): AstNode = node("alias", alias, emptyList())

    // ——— 表达式 ———

    private fun expression(expr: Expression?): AstNode = when (expr) {
        null -> AstNode.empty()
        is Column -> node("column", expr, emptyList())
        is Table -> node("table", expr, emptyList())
        is AllColumns -> node("all_columns", expr, emptyList())
        is AllTableColumns -> node("all_table_columns", expr, emptyList())
        is AnalyticExpression -> analytic(expr)
        is Function -> function(expr)
        is BinaryExpression -> node(
            "binary_op",
            expr,
            listOf(expression(expr.leftExpression), expression(expr.rightExpression)),
        )
        is Parenthesis -> node("parenthesis", expr, listOf(expression(expr.expression)))
        is SignedExpression -> node("signed", expr, listOf(expression(expr.expression)))
        is CaseExpression -> caseExpression(expr)
        is WhenClause -> whenClause(expr)
        is ExpressionList<*> -> node("expression_list", expr, expr.expressions.map { expression(it) })
        // 字面量（LongValue / StringValue / DateValue …）：叶子，text 即原文
        else -> node("literal", expr, emptyList())
    }

    private fun function(fn: Function): AstNode {
        val children = mutableListOf<AstNode>()
        fn.parameters?.expressions?.forEach { children += expression(it) }
        return node("function", fn, children)
    }

    /**
     * 窗口函数（`SUM(a) OVER (PARTITION BY b)`）。
     *
     * 实测（2026-10-08，JSqlParser 5.4）：窗口函数是 `AnalyticExpression`，
     * **不是** `Function`——不单独处理会被归成 `literal`（测试抓到过）。
     */
    private fun analytic(ae: AnalyticExpression): AstNode {
        val children = mutableListOf<AstNode>()
        ae.expression?.let { children += expression(it) }
        ae.partitionExpressionList?.expressions?.forEach {
            children += node("partition_by", it, listOf(expression(it)))
        }
        ae.orderByElements?.forEach {
            children += node("order_by", it, listOf(expression(it.expression)))
        }
        return node("window_function", ae, children)
    }

    private fun caseExpression(ce: CaseExpression): AstNode {
        val children = mutableListOf<AstNode>()
        ce.switchExpression?.let { children += expression(it) }
        ce.whenClauses?.forEach { children += whenClause(it) }
        ce.elseExpression?.let { children += node("else", it, listOf(expression(it))) }
        return node("case", ce, children)
    }

    private fun whenClause(wc: WhenClause): AstNode = node(
        "when",
        wc,
        listOf(expression(wc.whenExpression), expression(wc.thenExpression)),
    )

    // ——— 通用 ———

    private fun node(type: String, owner: Any, children: List<AstNode>): AstNode =
        AstNode(
            type = type,
            text = JsSqlSpan.textOf(source, owner),
            span = JsSqlSpan.ofNode(source, owner),
            children = children,
        )

    /** 不认识的节点：不猜语义，保留引擎类名与原文（Never-wrong）。 */
    private fun unknown(owner: Any): AstNode =
        AstNode(
            type = "unknown_node",
            text = JsSqlSpan.textOf(source, owner),
            span = JsSqlSpan.ofNode(source, owner),
            children = emptyList(),
        )
}
