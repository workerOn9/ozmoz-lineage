package io.github.workeron9.ozmoz.lineage.engine.calcite

import io.github.workeron9.ozmoz.lineage.ir.AstNode
import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlIdentifier
import org.apache.calcite.sql.JoinType
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlLiteral
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlNodeList
import org.apache.calcite.sql.SqlOrderBy
import org.apache.calcite.sql.SqlSelect
import org.apache.calcite.sql.SqlWith
import org.apache.calcite.sql.SqlWithItem

/**
 * 把 Calcite 的 `SqlNode` 树拍成**归一化树**（`ir.AstNode`），供可视化对比与 JSON 导出。
 *
 * 与 `engine-jsqlparser` 的 `AstNormalizer` 同一约定（不是「统一 AST」）：
 * 节点只有 `type` / `text` / `span` / `children`，类型名是本适配器自定的稳定标识，
 * 与 jsqlparser 侧对齐（`select` / `cte` / `where` / `group_by` / `order_by` / `join` /
 * `identifier` / `literal` …），M4 的并排 diff 才有可比性。
 *
 * 设计取舍：
 * - `text` 优先取**源文本切片**（[CalcitePositions.textOf]，Lossless），
 *   切片拿不到时回落到 `toString()`（该节点已被 Calcite 所有权改造过的场景）。
 * - 通用 `SqlCall` 用 `SqlKind` 名小写做 `type`（`unary_plus`、`greater_than` …）；
 *   认不出的包成 `unknown_node`（Never-wrong：不猜语义）。
 * - `JoinType` / `SqlKind` 附加信息写进节点 **type 后缀**（如 `inner_join`），
 *   保持四元组形状不变，调用方仍只需 diff JSON。
 */
internal class CalciteAstNormalizer(private val source: String) {

    fun normalize(node: SqlNode): AstNode = convert(node)

    // ——— 结构化节点 ———

    private fun select(select: SqlSelect): AstNode {
        val children = mutableListOf<AstNode>()
        select.getFrom()?.let { children += field("from", it) }
        select.getSelectList()?.let { listNode ->
            children += node(
                "select_list",
                listNode,
                listNode.getList().map { convert(it) },
            )
        }
        select.getWhere()?.let { children += field("where", it) }
        select.getGroup()?.let { children += field("group_by", it) }
        select.getHaving()?.let { children += field("having", it) }
        return node("select", select, children)
    }

    private fun with(with: SqlWith): AstNode {
        val children = mutableListOf<AstNode>()
        with.withList.getList().filterIsInstance<SqlWithItem>().forEach { children += withItem(it) }
        children += convert(with.body)
        return node("with", with, children)
    }

    private fun withItem(item: SqlWithItem): AstNode {
        val children = buildList {
            item.name?.let { add(convert(it)) }
            item.columnList?.let { add(convert(it)) }
            item.query?.let { add(convert(it)) }
        }
        return node("cte", item, children)
    }

    private fun orderBy(orderBy: SqlOrderBy): AstNode {
        val children = buildList {
            add(convert(orderBy.query))
            orderBy.orderList?.let { add(convert(it)) }
            orderBy.offset?.let { add(field("offset", it)) }
            orderBy.fetch?.let { add(field("fetch", it)) }
        }
        // SqlOrderBy 自身 pos 不可信（是 fetch 的位置，见 CalcitePositions 的 KDoc quirks），
        // 用部件位置并集取 span 与 text。
        val positions = listOfNotNull(
            orderBy.query.getParserPosition(),
            orderBy.orderList?.getParserPosition(),
            orderBy.offset?.getParserPosition(),
            orderBy.fetch?.getParserPosition(),
        )
        val span = CalcitePositions.spanOf(source, positions)
        return AstNode(
            type = "order_by",
            text = CalcitePositions.textOf(source, span) ?: orderBy.toString(),
            span = span,
            children = children,
        )
    }

    private fun list(nodeList: SqlNodeList): AstNode = node("list", nodeList, nodeList.getList().map { convert(it) })

    // ——— 通用 ———

    private fun convert(node: SqlNode?): AstNode = when (node) {
        null -> AstNode.empty()
        is SqlSelect -> select(node)
        is SqlWith -> with(node)
        is SqlOrderBy -> orderBy(node)
        is SqlIdentifier -> node(identifierType(node), node, emptyList())
        is SqlLiteral -> node("literal", node, emptyList())
        is SqlNodeList -> list(node)
        is SqlCall -> call(node)
        else -> unknown(node)
    }

    /** 通用 `SqlCall`：type = `SqlKind` 名小写（`join` / `union` / `case` / `cast` …）。 */
    private fun call(call: SqlCall): AstNode {
        val children = buildList<AstNode> {
            for (i in 0 until call.operandCount()) {
                call.operand<SqlNode>(i)?.let { add(convert(it)) }
            }
        }
        return node(callKind(call.kind, call), call, children)
    }

    /**
     * `SqlKind` → type：小写 + 保留下划线（`union_all` / `inner_join`）。
     * - join 按 `JoinType` 细分（`inner_join`）；
     * - `OTHER_FUNCTION` / `OTHER_LITERAL`（聚合 / 数组构造等 Calcite 不设专有 kind 的调用）
     *   用**操作符名**小写（`sum` / `coalesce`），可读性更好。
     */
    private fun callKind(kind: SqlKind, call: SqlCall): String = when (kind) {
        SqlKind.JOIN -> (call as? org.apache.calcite.sql.SqlJoin)?.let { joinType(it) } ?: "join"
        SqlKind.OTHER_FUNCTION, SqlKind.OTHER -> call.operator.name.lowercase()
        else -> kind.name.lowercase()
    }

    private fun joinType(join: org.apache.calcite.sql.SqlJoin): String {
        val base = when (join.joinType) {
            JoinType.INNER -> "inner_join"
            JoinType.LEFT -> "left_join"
            JoinType.RIGHT -> "right_join"
            JoinType.FULL -> "full_join"
            JoinType.CROSS -> "cross_join"
            else -> "join"
        }
        return base
    }

    /** 标识符：`SqlIdentifier`（含 `*` 展开与 `a.*`）→ `identifier` / `star`。 */
    private fun identifierType(identifier: SqlIdentifier): String =
        if (identifier.isStar) "star" else "identifier"

    private fun field(type: String, inner: SqlNode): AstNode =
        AstNode(
            type = type,
            text = textOf(inner) ?: "",
            span = CalcitePositions.spanOf(source, inner.getParserPosition()),
            children = listOf(convert(inner)),
        )

    private fun node(type: String, owner: SqlNode, children: List<AstNode>): AstNode =
        AstNode(
            type = type,
            text = textOf(owner) ?: owner.toString(),
            span = CalcitePositions.spanOf(source, owner.getParserPosition()),
            children = children,
        )

    /** 不认识的节点：不猜语义，保留引擎类名与原文（Never-wrong）。 */
    private fun unknown(owner: SqlNode): AstNode =
        AstNode(
            type = "unknown_node",
            text = textOf(owner) ?: owner.toString(),
            span = CalcitePositions.spanOf(source, owner.getParserPosition()),
            children = emptyList(),
        )

    private fun textOf(owner: SqlNode): String? =
        CalcitePositions.textOf(source, owner.getParserPosition())
}
