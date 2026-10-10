package io.github.workeron9.ozmoz.lineage.engine.calcite

import io.github.workeron9.ozmoz.lineage.engine.semantics.Assignment
import io.github.workeron9.ozmoz.lineage.engine.semantics.CteSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.JoinSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.NestedSource
import io.github.workeron9.ozmoz.lineage.engine.semantics.OutputItem
import io.github.workeron9.ozmoz.lineage.engine.semantics.QuerySpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.ScopeSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.SetOperationQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SourceSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.engine.semantics.SubquerySource
import io.github.workeron9.ozmoz.lineage.engine.semantics.TableSource
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind
import io.github.workeron9.ozmoz.lineage.ir.Span
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import org.apache.calcite.sql.JoinType
import org.apache.calcite.sql.SqlBasicCall
import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlDataTypeSpec
import org.apache.calcite.sql.SqlDelete
import org.apache.calcite.sql.SqlIdentifier
import org.apache.calcite.sql.SqlInsert
import org.apache.calcite.sql.SqlJoin
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlLiteral
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlNodeList
import org.apache.calcite.sql.SqlOrderBy
import org.apache.calcite.sql.SqlSelect
import org.apache.calcite.sql.SqlUpdate
import org.apache.calcite.sql.SqlWindow
import org.apache.calcite.sql.SqlWith
import org.apache.calcite.sql.SqlWithItem
import org.apache.calcite.sql.ddl.SqlCreateTable
import org.apache.calcite.sql.ddl.SqlCreateView
import org.apache.calcite.sql.`fun`.SqlCase

/**
 * Calcite `SqlNode`（语句级）→ 引擎无关 [SemanticStatement]。
 *
 * 与 `engine-jsqlparser` 的 `SemanticExtractor` **同一契约**：只保留血缘需要的
 * 结构（作用域 / 来源 / 输出 / 表达式），认不出的落 `SqlExpr.Unknown` 或
 * `calcite.semantic_partial` 诊断（Never-wrong）；标识符规则同口径——
 * raw 保原始拼写，canonical 去引号 + 小写。
 *
 * Calcite 结构差异（相对 jsqlparser）：
 * - `WITH` 是语句级 [SqlWith] 包裹（不挂在 Select 上），递归体不上报（recurrence 待 ADR）；
 * - `ORDER BY` / `OFFSET` / `FETCH` 是语句级 [SqlOrderBy] 包裹，orderList 下传给
 *   主作用域 / 集合运算；OFFSET/FETCH 值本身不进模型（记 partial 诊断）；
 * - JOIN 是单节点 [SqlJoin]（左链嵌在节点里），须展平成 sources + joins 串；
 * - `SELECT DISTINCT` 等 modifier 不进血缘模型，不记。
 */
internal class CalciteSemanticExtractor(private val source: String) {

    private val diagnostics: MutableList<Diagnostic> = mutableListOf()

    // ——— 语句层 ———

    /**
     * 提取一条语句；**无法建模返回 null**（调用方跳过，不让整句/整脚本失败）——
     * 与 jsqlparser 侧 `unsupported` 等口径，只是用 null 表达「跳过」而非 Unknown。
     * 语句本身合法但种类不支持（MERGE / VALUES / 纯 DDL）都落这里。
     */
    fun extract(node: SqlNode): Resolved<SemanticStatement>? {
        // 语句级 ORDER BY 包裹拆开：orderList 下传主作用域 / 集合运算。
        val (queryNode, orderList) = unwrapOrderBy(node)
        return when (queryNode) {
            is SqlSelect, is SqlWith -> selectStatement(queryNode, orderList)
            is SqlBasicCall -> when {
                queryNode.kind in SET_OP_KINDS -> selectStatement(queryNode, orderList)
                else -> skip(queryNode)
            }
            is SqlInsert -> insertStatement(queryNode)
            is SqlCreateTable ->
                if (queryNode.query != null) ctasStatement(queryNode) else skip(queryNode)
            is SqlCreateView -> createViewStatement(queryNode)
            is SqlUpdate -> updateStatement(queryNode)
            is SqlDelete -> deleteStatement(queryNode)
            else -> skip(queryNode)
        }
    }

    private fun skip(node: SqlNode): Resolved<SemanticStatement>? = null

    /** 语句级 `ORDER BY … [OFFSET …] [FETCH …]` 拆包；OFFSET / FETCH 的值暂不进模型。 */
    private fun unwrapOrderBy(node: SqlNode): Pair<SqlNode, List<SqlExpr>> = when (node) {
        is SqlOrderBy -> {
            val orderList = node.orderList?.getList()?.map { expr(it) }.orEmpty()
            node.offset?.let { warn("ORDER BY 之上的 OFFSET 暂不进血缘模型", it) }
            node.fetch?.let { warn("ORDER BY 之上的 FETCH 暂不进血缘模型", it) }
            node.query to orderList
        }
        else -> node to emptyList()
    }

    private fun selectStatement(node: SqlNode, orderList: List<SqlExpr>): Resolved<SemanticStatement> =
        finish(StatementKind.SELECT, query = queryOf(node, ScopeKind.SELECT, orderList))

    private fun insertStatement(insert: SqlInsert): Resolved<SemanticStatement> {
        val target = tableRef(insert.getTargetTable())
        val targetColumns = identifiers(insert.getTargetColumnList())
            .mapNotNull { (raw, id) -> columnRef(id, raw) }
        val query = queryOf(insert.getSource(), ScopeKind.INSERT)
        if (query == null) warn("INSERT 没有可建模的查询来源（如 VALUES）", insert)
        return finish(StatementKind.INSERT, target = target, targetColumns = targetColumns, query = query)
    }

    private fun ctasStatement(createTable: SqlCreateTable): Resolved<SemanticStatement> {
        val target = tableRef(createTable.name)
        val query = queryOf(createTable.query, ScopeKind.CREATE_TABLE_AS)
        return finish(StatementKind.CREATE_TABLE_AS, target = target, query = query)
    }

    private fun createViewStatement(createView: SqlCreateView): Resolved<SemanticStatement> {
        val target = tableRef(createView.name)
        val targetColumns = identifiers(createView.columnList)
            .mapNotNull { (raw, id) -> columnRef(id, raw) }
        val query = queryOf(createView.query, ScopeKind.CREATE_VIEW)
        return finish(StatementKind.CREATE_VIEW, target = target, targetColumns = targetColumns, query = query)
    }

    private fun updateStatement(update: SqlUpdate): Resolved<SemanticStatement> {
        val target = tableRef(update.getTargetTable())
        val columns = identifiers(update.getTargetColumnList())
        val values = update.getSourceExpressionList()?.getList().orEmpty()
        if (columns.size != values.size) {
            warn("UPDATE 赋值两侧数量不一致，跳过该组", update)
        }
        val assignments = buildList {
            if (columns.size == values.size) {
                for (i in columns.indices) {
                    add(Assignment(columnRef(columns[i].second, columns[i].first)!!, expr(values[i])))
                }
            }
        }
        val scope = ScopeSpec(
            kind = ScopeKind.UPDATE,
            sources = distinctSources(
                listOfNotNull(
                    update.getSourceSelect()?.let { sql -> sourceOf(sql) },
                    update.getTargetTable()?.let { TableSource(tableRef(it)) },
                ),
            ),
            filters = listOfNotNull(update.getCondition()).map { expr(it) },
            span = spanOf(update),
        )
        return finish(StatementKind.UPDATE, target = target, assignments = assignments, scope = scope)
    }

    private fun deleteStatement(delete: SqlDelete): Resolved<SemanticStatement> {
        val target = tableRef(delete.getTargetTable())
        val scope = ScopeSpec(
            kind = ScopeKind.DELETE,
            sources = distinctSources(
                listOfNotNull(
                    delete.getTargetTable()?.let { TableSource(tableRef(it)) },
                ),
            ),
            filters = listOfNotNull(delete.getCondition()).map { expr(it) },
            span = spanOf(delete),
        )
        return finish(StatementKind.DELETE, target = target, scope = scope)
    }

    // ——— 查询 / 作用域 ———

    /**
     * [SqlNode] → [QuerySpec]；映射不出来给 null 并记 `partial` 诊断（不硬塞猜测结构）。
     *
     * [orderList] 在语句级 `ORDER BY` 下传时非 null：给 [ScopeSpec.orderBy]
     * （SelectQuery）或 [SetOperationQuery.orderBy]。
     */
    private fun queryOf(node: SqlNode?, kind: ScopeKind, orderList: List<SqlExpr>? = null): QuerySpec? = when (node) {
        null -> {
            warn("该语句没有可建模的查询体", null)
            null
        }
        is SqlSelect -> SelectQuery(
            scope = scopeOf(node, kind, orderList),
            ctes = emptyList(),
            raw = textOf(node) ?: "",
            span = spanOf(node),
        )
        is SqlWith -> {
            val ctes = ctesOf(node.withList.getList().filterIsInstance<SqlWithItem>().toList())
            when (val body = queryOf(node.body, kind, orderList)) {
                is SelectQuery -> body.copy(ctes = ctes)
                is SetOperationQuery -> body.copy(ctes = ctes)
                else -> body
            }
        }
        is SqlBasicCall ->
            if (node.kind in SET_OP_KINDS) setOpQuery(node, orderList)
            else {
                warn("不支持的查询体: ${node.operator.name}", node)
                null
            }
        else -> {
            warn("不支持的查询体: ${node.javaClass.simpleName}", node)
            null
        }
    }

    private fun setOpQuery(call: SqlBasicCall, orderList: List<SqlExpr>?): QuerySpec? {
        // Calcite 1.42 没有 UNION_ALL kind：`UNION ALL` 是 kind=UNION + SqlSetOperator.isAll。
        val op = when (call.kind) {
            SqlKind.UNION ->
                if ((call.operator as? org.apache.calcite.sql.SqlSetOperator)?.isAll == true) "UNION ALL" else "UNION"
            else -> call.kind.name.uppercase().replace('_', ' ')
        }
        val branches = call.operandList.mapNotNull { operand -> queryOf(operand, ScopeKind.UNION_BRANCH) }
        if (branches.isEmpty()) {
            warn("集合运算没有可建模的分支", call)
            return null
        }
        return SetOperationQuery(
            op = op,
            branches = branches,
            orderBy = orderList.orEmpty(),
            raw = textOf(call) ?: "",
            span = spanOf(call),
        )
    }

    private fun scopeOf(select: SqlSelect, kind: ScopeKind, orderList: List<SqlExpr>? = null): ScopeSpec {
        val (sources, joins) = flattenFrom(select.getFrom())
        return ScopeSpec(
            kind = kind,
            sources = sources,
            joins = joins,
            filters = listOfNotNull(select.getWhere()).map { expr(it) },
            groupBy = select.getGroup()?.getList()?.map { expr(it) }.orEmpty(),
            having = listOfNotNull(select.getHaving()).map { expr(it) },
            orderBy = orderList.orEmpty(),
            outputs = select.getSelectList()?.getList()?.filterNotNull()?.mapNotNull { outputItem(it) }.orEmpty(),
            span = spanOf(select),
        )
    }

    private fun ctesOf(items: List<SqlWithItem>): List<CteSpec> = items.mapNotNull { item ->
        val name = item.name?.getSimple()?.takeIf { it.isNotBlank() } ?: run {
            warn("CTE 缺少名字，跳过", item)
            return@mapNotNull null
        }
        val body = queryOf(item.query, ScopeKind.CTE)
        if (body == null) {
            warn("CTE `$name` 的查询体无法建模，跳过", item)
            return@mapNotNull null
        }
        CteSpec(
            name = name,
            columns = item.columnList?.getList()
                ?.mapNotNull { (it as? SqlIdentifier)?.getSimple() }.orEmpty(),
            query = body,
            span = spanOf(item),
        )
    }

    // ——— FROM / JOIN ———

    /**
     * [SqlJoin] 在 Calcite 里是**单节点**（左链嵌在 left/right 里），展平成
     * jsqlparser 侧的形状：`sources` = 最左来源，`joins` = 右侧链路依次排开。
     */
    private fun flattenFrom(node: SqlNode?): Pair<List<SourceSpec>, List<JoinSpec>> {
        if (node == null) return emptyList<SourceSpec>() to emptyList<JoinSpec>()
        val sources = ArrayList<SourceSpec>()
        val joins = ArrayList<JoinSpec>()
        appendFrom(node, sources, joins)
        return sources to joins
    }

    private fun appendFrom(node: SqlNode, sources: MutableList<SourceSpec>, joins: MutableList<JoinSpec>) {
        when (node) {
            is SqlJoin -> {
                appendFrom(node.getLeft(), sources, joins)
                val right = sourceOf(node.getRight()) ?: run {
                    warn("JOIN 右侧无法建模", node)
                    return
                }
                joins += JoinSpec(
                    type = joinType(node.getJoinType()),
                    right = right,
                    on = onConditions(node),
                    using = usingColumns(node),
                    span = spanOf(node),
                )
            }
            else -> {
                val source = sourceOf(node)
                if (source == null) warn("FROM 项无法建模", node) else sources.add(source)
            }
        }
    }

    private fun sourceOf(node: SqlNode): SourceSpec? = when {
        node is SqlIdentifier -> TableSource(tableRef(node))
        node is SqlSelect -> SubquerySource(
            query = queryOf(node, ScopeKind.SUBQUERY) ?: return null,
            alias = null,
            raw = textOf(node) ?: "",
            span = spanOf(node),
        )
        node is SqlWith -> SubquerySource(
            query = queryOf(node, ScopeKind.SUBQUERY) ?: return null,
            alias = null,
            raw = textOf(node) ?: "",
            span = spanOf(node),
        )
        node is SqlBasicCall && node.kind == SqlKind.AS -> when (val inner = node.operand<SqlNode>(0)) {
            is SqlIdentifier -> TableSource(tableRef(inner, sqlAlias(node)))
            is SqlSelect -> SubquerySource(
                query = queryOf(inner, ScopeKind.SUBQUERY) ?: return null,
                alias = sqlAlias(node),
                raw = textOf(node) ?: "",
                span = spanOf(node),
            )
            is SqlWith -> SubquerySource(
                query = queryOf(inner, ScopeKind.SUBQUERY) ?: return null,
                alias = sqlAlias(node),
                raw = textOf(node) ?: "",
                span = spanOf(node),
            )
            is SqlJoin -> {
                // `(t1 JOIN t2 …) AS x`：括号包装——递归展平进主作用域，别名记进 NestedSource。
                val (sources, joins) = flattenFrom(inner)
                if (sources.size != 1) {
                    warn("括号 FROM 展平异常（来源数 ${sources.size}）", node)
                    return null
                }
                NestedSource(
                    from = sources.first(),
                    joins = joins,
                    alias = sqlAlias(node),
                    raw = textOf(node) ?: "",
                    span = spanOf(node),
                )
            }
            else -> {
                warn("不支持的 FROM 项: ${node.operator.name}", node)
                null
            }
        }
        else -> {
            warn("不支持的 FROM 项: ${node.javaClass.simpleName}", node)
            null
        }
    }

    private fun joinType(joinType: JoinType): String = when (joinType) {
        JoinType.LEFT -> "LEFT OUTER"
        JoinType.RIGHT -> "RIGHT OUTER"
        JoinType.FULL -> "FULL OUTER"
        else -> joinType.name
    }

    private fun onConditions(join: SqlJoin): List<SqlExpr> {
        if (join.getConditionType() == org.apache.calcite.sql.JoinConditionType.USING) return emptyList()
        return listOfNotNull(join.getCondition()).map { expr(it) }
    }

    private fun usingColumns(join: SqlJoin): List<String> {
        if (join.getConditionType() != org.apache.calcite.sql.JoinConditionType.USING) return emptyList()
        return (join.getCondition() as? SqlNodeList)?.getList()
            ?.mapNotNull { (it as? SqlIdentifier)?.getSimple() }.orEmpty()
    }

    // ——— SELECT 列表 ———

    private fun outputItem(node: SqlNode): OutputItem? {
        val span = spanOf(node)
        if (node is SqlBasicCall && node.kind == SqlKind.AS) {
            val inner = node.operand<SqlNode>(0) ?: return null
            return OutputItem(expr = expr(inner), alias = sqlAlias(node), span = span)
        }
        return OutputItem(expr = expr(node), alias = null, span = span)
    }

    // ——— 表达式 ———

    private fun expr(node: SqlNode?): SqlExpr {
        if (node == null) return SqlExpr.Unknown(raw = "", reason = "引擎未给出该表达式", span = null)
        val raw = textOf(node) ?: node.toString()
        val span = spanOf(node)
        return when (node) {
            is SqlCase -> caseExpr(node, raw, span)
            is SqlIdentifier -> identifierExpr(node, raw, span)
            is SqlLiteral -> SqlExpr.Literal(raw, span)
            is SqlCall -> callExpr(node, raw, span)
            is SqlDataTypeSpec -> SqlExpr.Unknown(raw, "类型规格不参与列推导", span)
            is SqlWindow -> SqlExpr.Unknown(raw, "裸窗口规范不参与列推导", span)
            else -> SqlExpr.Unknown(raw, "未支持的表达式类型: ${node.javaClass.simpleName}", span)
        }
    }

    /** `SqlIdentifier` → 列 / `*`；`t.*` 的 `t` 落 qualifier。 */
    private fun identifierExpr(identifier: SqlIdentifier, raw: String, span: Span?): SqlExpr {
        if (identifier.isStar) {
            val names = identifier.names
            return SqlExpr.Star(
                qualifier = if (names.size > 1) names.getOrNull(names.size - 2) else null,
                raw = raw,
                span = span,
            )
        }
        val ref = columnRef(identifier, raw)
            ?: return SqlExpr.Unknown(raw, "标识符缺失列名", span)
        return SqlExpr.Column(ref)
    }

    /**
     * 通用 `SqlCall`：函数 / 二元 / 一元 / 窗口；其余 Unknown。
     * 操作符名保原始拼写（`SUM` / `>` 等），与 jsqlparser 侧同口径。
     */
    private fun callExpr(call: SqlCall, raw: String, span: Span?): SqlExpr {
        val operands = call.operandList
        val operatorName = call.operator.name
        return when (call.kind) {
            SqlKind.OVER -> windowExpr(call, raw, span)
            SqlKind.CASE -> (call as? SqlCase)?.let { caseExpr(it, raw, span) }
                ?: SqlExpr.Unknown(raw, "CASE 结构不完整", span)
            SqlKind.AS -> operands.getOrNull(0)?.let { expr(it) }
                ?: SqlExpr.Unknown(raw, "AS 调用缺少目标表达式", span)
            SqlKind.CAST, SqlKind.SAFE_CAST, SqlKind.CONVERT, SqlKind.CONVERT_ORACLE ->
                SqlExpr.Function(
                    name = "cast",
                    args = buildList {
                        operands.getOrNull(0)?.let { add(expr(it)) }
                        operands.getOrNull(1)?.let { add(SqlExpr.Unknown(rawOf(it), "CAST 目标类型不参与列推导", spanOf(it))) }
                    },
                    raw = raw,
                    span = span,
                )
            SqlKind.AND, SqlKind.OR, SqlKind.EQUALS, SqlKind.NOT_EQUALS,
            SqlKind.LESS_THAN, SqlKind.LESS_THAN_OR_EQUAL, SqlKind.GREATER_THAN,
            SqlKind.GREATER_THAN_OR_EQUAL, SqlKind.PLUS, SqlKind.MINUS, SqlKind.TIMES,
            SqlKind.DIVIDE, SqlKind.MOD, SqlKind.IS_DISTINCT_FROM,
            SqlKind.IS_NOT_DISTINCT_FROM, SqlKind.LIKE, SqlKind.RLIKE,
            SqlKind.IN, SqlKind.NOT_IN ->
                SqlExpr.BinaryOp(
                    op = operatorName,
                    left = expr(operands.getOrNull(0)),
                    right = rightSideOf(call, operands.getOrNull(1)),
                    raw = raw,
                    span = span,
                )
            SqlKind.BETWEEN -> {
                // Calcite：operand [expr, lo, hi]。包成 BinaryOp(BETWEEN, expr, AND(lo, hi))
                // 保住上下界里的列引用（jsqlparser 侧同是可收集的二元形状）。
                SqlExpr.BinaryOp(
                    op = "BETWEEN",
                    left = expr(operands.getOrNull(0)),
                    right = SqlExpr.BinaryOp(
                        op = "AND",
                        left = expr(operands.getOrNull(1)),
                        right = expr(operands.getOrNull(2)),
                        raw = raw,
                        span = span,
                    ),
                    raw = raw,
                    span = span,
                )
            }
            SqlKind.IS_NULL, SqlKind.IS_NOT_NULL, SqlKind.IS_TRUE, SqlKind.IS_FALSE,
            SqlKind.IS_NOT_TRUE, SqlKind.IS_NOT_FALSE, SqlKind.IS_UNKNOWN,
            SqlKind.NOT, SqlKind.PLUS_PREFIX, SqlKind.MINUS_PREFIX, SqlKind.EXISTS ->
                SqlExpr.UnaryOp(
                    op = operatorName.uppercase(),
                    operand = when (val inner = operands.getOrNull(0)) {
                        is SqlSelect -> SqlExpr.Unknown(raw, "子查询暂不进入血缘列推导", spanOf(inner))
                        else -> expr(inner)
                    },
                    raw = raw,
                    span = span,
                )
            else -> SqlExpr.Function(
                name = operatorName,
                args = operands.map { expr(it) },
                raw = raw,
                span = span,
            )
        }
    }

    /** 二元右侧：`IN` / `NOT IN` 的右侧是列表（元素各带列）——包成 `list` 函数保住列收集。 */
    private fun rightSideOf(call: SqlCall, node: SqlNode?): SqlExpr =
        when (node) {
            is SqlNodeList -> SqlExpr.Function(
                name = "list",
                args = node.getList().map { expr(it) },
                raw = textOf(node) ?: "",
                span = spanOf(node),
            )
            else -> expr(node)
        }

    private fun caseExpr(node: SqlCase, raw: String, span: Span?): SqlExpr = SqlExpr.Case(
        operand = node.getValueOperand()?.let { expr(it) },
        branches = node.getWhenOperands().getList().zip(node.getThenOperands().getList())
            .map { (whenNode, thenNode) -> SqlExpr.Case.Branch(expr(whenNode), expr(thenNode)) },
        elseExpr = node.getElseOperand()?.let { expr(it) },
        raw = raw,
        span = span,
    )

    private fun windowExpr(call: SqlCall, raw: String, span: Span?): SqlExpr {
        val functionNode = call.operand<SqlNode>(0)
        val windowNode = call.operandList.getOrNull(1) as? SqlWindow
        return SqlExpr.Window(
            function = expr(functionNode),
            partitionBy = windowNode?.getPartitionList()?.getList()?.map { expr(it) }.orEmpty(),
            orderBy = windowNode?.getOrderList()?.getList()?.map { expr(it) }.orEmpty(),
            raw = raw,
            span = span,
        )
    }

    // ——— 标识符 / 引用 ———

    /** `SqlIdentifier` → [TableRef]；`names` 由解析器给出（已去引号），段序 catalog / schema / name。 */
    private fun tableRef(node: SqlNode?, alias: String? = null): TableRef {
        val identifier = node as? SqlIdentifier
        val names = identifier?.names?.filter { it.isNotEmpty() }.orEmpty()
        val name = names.lastOrNull().orEmpty().ifBlank { "_" }
        val schema = names.getOrNull(names.size - 2)
        val catalog = names.getOrNull(names.size - 3)
        return TableRef(
            raw = textOf(node) ?: names.joinToString("."),
            canonical = names.joinToString(".").lowercase(),
            catalog = catalog,
            schema = schema,
            name = name,
            alias = alias,
            span = spanOf(node),
        )
    }

    /** `SqlIdentifier`（1~2 段）→ [ColumnRef]（canonical = 去引号 + 小写，jsqlparser 侧同口径）。 */
    private fun columnRef(identifier: SqlIdentifier, raw: String): ColumnRef? {
        val names = identifier.names.filter { it.isNotEmpty() }
        val name = names.lastOrNull() ?: return null
        val table = names.getOrNull(names.size - 2)
        return ColumnRef(
            raw = raw,
            canonical = listOfNotNull(table, name).joinToString(".").lowercase(),
            name = name,
            table = table,
            span = textSpan(identifier),
        )
    }

    /** 目标列 / `UPDATE SET` / `CREATE VIEW` 显式列清单：标识符序列（原文切片, 节点）。 */
    private fun identifiers(list: SqlNodeList?): List<Pair<String, SqlIdentifier>> = list
        ?.getList()?.filterIsInstance<SqlIdentifier>()
        ?.map { id -> (textOf(id) ?: id.getSimple()) to id }.orEmpty()

    /** `AS` 调用里的别名（第二个操作数）；不合法记诊断返回 null。 */
    private fun sqlAlias(call: SqlCall): String? =
        when (val operand = call.operand<SqlNode>(1)) {
            is SqlIdentifier -> operand.getSimple()
            is SqlLiteral -> operand.toString().trim('"', '`', '\'')
            else -> {
                warn("别名不是标识符，跳过", operand)
                null
            }
        }

    // ——— 工具 ———

    /** 按 canonical 去重，保留首次出现；非表来源（派生表等）原样保留（jsqlparser 侧同口径）。 */
    private fun distinctSources(sources: List<SourceSpec>): List<SourceSpec> {
        val seen = mutableSetOf<String>()
        return sources.filter { source ->
            val key = (source as? TableSource)?.table?.canonical ?: source.raw
            seen.add(key)
        }
    }

    private fun rawOf(node: SqlNode): String = textOf(node) ?: node.toString()

    private fun spanOf(node: SqlNode?): Span? = CalcitePositions.spanOf(source, node?.getParserPosition())

    private fun textSpan(node: SqlNode?): Span? = spanOf(node)

    private fun textOf(node: SqlNode?): String? = CalcitePositions.textOf(source, node?.getParserPosition())

    private fun warn(message: String, node: SqlNode?) {
        diagnostics += Diagnostic.warning(CODE_SEMANTIC_PARTIAL, message, spanOf(node))
    }

    private fun finish(
        kind: StatementKind,
        target: io.github.workeron9.ozmoz.lineage.ir.TableRef? = null,
        targetColumns: List<ColumnRef> = emptyList(),
        assignments: List<Assignment> = emptyList(),
        query: QuerySpec? = null,
        scope: ScopeSpec? = null,
    ): Resolved<SemanticStatement> {
        if (query == null && scope == null && diagnostics.isEmpty()) {
            warn("无法从该语句提取查询或作用域", null)
        }
        return Resolved.Known(
            SemanticStatement(
                kind = kind,
                target = target,
                targetColumns = targetColumns,
                assignments = assignments,
                query = query,
                scope = scope,
                diagnostics = diagnostics.toList(),
            ),
        )
    }

    internal companion object {
        /** 诊断码：语义提取只做到部分——稳定契约，与 jsqlparser 侧 `semantic_partial` 镜像。 */
        const val CODE_SEMANTIC_PARTIAL: String = "calcite.semantic_partial"

        private val SET_OP_KINDS =
            setOf(SqlKind.UNION, SqlKind.INTERSECT, SqlKind.EXCEPT)
    }
}
