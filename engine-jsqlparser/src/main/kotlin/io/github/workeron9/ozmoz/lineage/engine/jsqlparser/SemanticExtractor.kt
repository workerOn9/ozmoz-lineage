package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

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
import net.sf.jsqlparser.expression.AnalyticExpression
import net.sf.jsqlparser.expression.BinaryExpression
import net.sf.jsqlparser.expression.BooleanValue
import net.sf.jsqlparser.expression.CaseExpression
import net.sf.jsqlparser.expression.DateTimeLiteralExpression
import net.sf.jsqlparser.expression.DateValue
import net.sf.jsqlparser.expression.DoubleValue
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.HexValue
import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.expression.NotExpression
import net.sf.jsqlparser.expression.NullValue
import net.sf.jsqlparser.expression.SignedExpression
import net.sf.jsqlparser.expression.StringValue
import net.sf.jsqlparser.expression.TimeValue
import net.sf.jsqlparser.expression.TimestampValue
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList
import net.sf.jsqlparser.parser.ASTNodeAccess
import net.sf.jsqlparser.parser.Token
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
import net.sf.jsqlparser.statement.select.Values
import net.sf.jsqlparser.statement.select.WithItem
import net.sf.jsqlparser.statement.update.Update

/**
 * 把一条 JSqlParser [Statement] 转换成引擎无关的 [SemanticStatement]。
 *
 * 与 [AstNormalizer]（为可视化 diff 而生的归一化树）不同，这里只保留**血缘需要**的
 * 结构：作用域 / 来源 / 输出列 / 表达式。认不出的部分一律落进 [SqlExpr.Unknown] 或
 * [Diagnostic]（`jsqlparser.semantic_partial`），**绝不猜**（Never-wrong）。
 *
 * 位置与拼写全部走 [JsSqlSpan] 与 [Identifiers]，保证 Lossless。引擎私有类型
 * （`Statement` / `Expression` / `Token` …）只在适配器内部出现。
 */
internal class SemanticExtractor(private val source: String) {

    private val diagnostics: MutableList<Diagnostic> = mutableListOf()

    // ——— 语句层 ———

    fun extract(statement: Statement): Resolved<SemanticStatement> = when (statement) {
        is Values -> unsupported(statement)
        is Select -> selectStatement(statement)
        is Insert -> insertStatement(statement)
        is CreateTable -> if (statement.select != null) ctasStatement(statement) else unsupported(statement)
        is CreateView -> createViewStatement(statement)
        is Update -> updateStatement(statement)
        is Delete -> deleteStatement(statement)
        else -> unsupported(statement)
    }

    private fun selectStatement(select: Select): Resolved<SemanticStatement> =
        finish(StatementKind.SELECT, query = querySpec(select, ScopeKind.SELECT))

    private fun insertStatement(insert: Insert): Resolved<SemanticStatement> {
        val target = insert.table?.let { Identifiers.tableRef(source, it) }
        val targetColumns = insert.columns?.mapNotNull { columnRefOrNull(it) }.orEmpty()
        val query = insert.select?.let { querySpec(it, ScopeKind.INSERT) }
        if (query == null) warn("INSERT 没有可建模的查询来源（如 VALUES）", insert.table)
        return finish(StatementKind.INSERT, target = target, targetColumns = targetColumns, query = query)
    }

    private fun ctasStatement(createTable: CreateTable): Resolved<SemanticStatement> {
        val target = createTable.table?.let { Identifiers.tableRef(source, it) }
        val query = createTable.select?.let { querySpec(it, ScopeKind.CREATE_TABLE_AS) }
        return finish(StatementKind.CREATE_TABLE_AS, target = target, query = query)
    }

    private fun createViewStatement(createView: CreateView): Resolved<SemanticStatement> {
        val target = createView.view?.let { Identifiers.tableRef(source, it) }
        val targetColumns = createView.columnNames?.mapNotNull { columnRefOrNull(it) }.orEmpty()
        val query = createView.select?.let { querySpec(it, ScopeKind.CREATE_VIEW) }
        return finish(StatementKind.CREATE_VIEW, target = target, targetColumns = targetColumns, query = query)
    }

    private fun updateStatement(update: Update): Resolved<SemanticStatement> {
        val target = update.table?.let { Identifiers.tableRef(source, it) }
        val assignments = mutableListOf<Assignment>()
        for (set in update.updateSets.orEmpty()) {
            val columns = set.columns.orEmpty()
            val values = set.values.orEmpty()
            if (columns.size != values.size) {
                warn("UPDATE 赋值两侧数量不一致，跳过该组", update.table)
                continue
            }
            for (i in columns.indices) {
                assignments += Assignment(Identifiers.columnRef(source, columns[i]), expr(values[i]))
            }
        }
        val sources = distinctSources(
            buildList {
                update.table?.let { add(TableSource(Identifiers.tableRef(source, it))) }
                update.fromItem?.let { item -> source(item, ScopeKind.SUBQUERY)?.let { add(it) } }
            },
        )
        val joins = buildList {
            update.startJoins?.mapNotNullTo(this) { joinSpec(it) }
            update.joins?.mapNotNullTo(this) { joinSpec(it) }
        }
        val scope = ScopeSpec(
            kind = ScopeKind.UPDATE,
            sources = sources,
            joins = joins,
            filters = update.where?.let { listOf(expr(it)) }.orEmpty(),
        )
        return finish(StatementKind.UPDATE, target = target, assignments = assignments, scope = scope)
    }

    private fun deleteStatement(delete: Delete): Resolved<SemanticStatement> {
        val target = delete.table?.let { Identifiers.tableRef(source, it) }
        val sources = distinctSources(
            buildList {
                delete.table?.let { add(TableSource(Identifiers.tableRef(source, it))) }
                delete.tables.orEmpty().forEach { add(TableSource(Identifiers.tableRef(source, it))) }
            },
        )
        val joins = delete.joins?.mapNotNull { joinSpec(it) }.orEmpty()
        val scope = ScopeSpec(
            kind = ScopeKind.DELETE,
            sources = sources,
            joins = joins,
            filters = delete.where?.let { listOf(expr(it)) }.orEmpty(),
        )
        return finish(StatementKind.DELETE, target = target, scope = scope)
    }

    private fun unsupported(statement: Statement): Resolved<SemanticStatement> =
        Resolved.Unknown("不支持提取语义的语句: ${statement.javaClass.simpleName}")

    // ——— 查询 / 作用域 ———

    /**
     * 把一个 [Select] 映射为 [QuerySpec]。[kind] 是该查询**主作用域**的角色
     * （顶层 SELECT / CTE / UNION 分支 / INSERT / CREATE VIEW …）。
     *
     * 映射不干净时返回 null 并记一条诊断——**不硬塞一个猜测的结构**。
     */
    private fun querySpec(select: Select, kind: ScopeKind): QuerySpec? = when (select) {
        is PlainSelect -> SelectQuery(
            scope = scopeOf(select, kind),
            ctes = ctesOf(select.withItemsList),
            raw = JsSqlSpan.textOf(source, select),
            span = JsSqlSpan.ofNode(source, select),
        )
        is SetOperationList -> SetOperationQuery(
            op = setOpText(select),
            branches = select.selects.orEmpty().mapNotNull { querySpec(it, ScopeKind.UNION_BRANCH) },
            orderBy = select.orderByElements.orEmpty().map { expr(it.expression) },
            ctes = ctesOf(select.withItemsList),
            raw = JsSqlSpan.textOf(source, select),
            span = JsSqlSpan.ofNode(source, select),
        )
        // ParenthesedSelect 只是括号包装（含 LateralSubSelect），透传到内层
        is ParenthesedSelect -> select.select?.let { querySpec(it, kind) }
        is Values -> {
            warn("VALUES 不作为查询来源建模", select)
            null
        }
        else -> {
            warn("不支持的查询体: ${select.javaClass.simpleName}", select)
            null
        }
    }

    private fun scopeOf(plain: PlainSelect, kind: ScopeKind): ScopeSpec {
        val sources = buildList {
            plain.fromItem?.let { item -> source(item, ScopeKind.SUBQUERY)?.let { add(it) } }
        }
        return ScopeSpec(
            kind = kind,
            sources = sources,
            joins = plain.joins?.mapNotNull { joinSpec(it) }.orEmpty(),
            filters = plain.where?.let { listOf(expr(it)) }.orEmpty(),
            groupBy = plain.groupBy?.groupByExpressionList?.map { expr(it) }.orEmpty(),
            having = plain.having?.let { listOf(expr(it)) }.orEmpty(),
            orderBy = plain.orderByElements.orEmpty().map { expr(it.expression) },
            outputs = plain.selectItems.orEmpty().mapNotNull { outputItem(it) },
            span = JsSqlSpan.ofNode(source, plain),
        )
    }

    private fun ctesOf(items: List<WithItem<*>>?): List<CteSpec> {
        if (items.isNullOrEmpty()) return emptyList()
        return items.mapNotNull { item ->
            val name = item.aliasName?.let { Identifiers.unquote(it) }?.takeIf { it.isNotBlank() }
            if (name == null) {
                warn("CTE 缺少名字，跳过", item)
                return@mapNotNull null
            }
            val body = item.select
            if (body == null) {
                warn("CTE `$name` 没有查询体，跳过", item)
                return@mapNotNull null
            }
            val query = querySpec(body, ScopeKind.CTE)
            if (query == null) {
                warn("CTE `$name` 的查询体无法建模，跳过", item)
                return@mapNotNull null
            }
            CteSpec(
                name = name,
                columns = item.withItemList.orEmpty()
                    .mapNotNull { (it.expression as? Column)?.columnName?.let { c -> Identifiers.unquote(c) } },
                query = query,
                span = JsSqlSpan.ofNode(source, item),
            )
        }
    }

    // ——— FROM / JOIN ———

    private fun source(item: FromItem, subqueryKind: ScopeKind): SourceSpec? = when (item) {
        is Table -> TableSource(Identifiers.tableRef(source, item))
        is ParenthesedFromItem -> {
            val from = item.fromItem?.let { source(it, subqueryKind) }
            if (from == null) {
                warn("括号 FROM 项缺少内部来源", item)
                null
            } else {
                NestedSource(
                    from = from,
                    joins = item.joins?.mapNotNull { joinSpec(it) }.orEmpty(),
                    alias = item.alias?.name?.let { Identifiers.unquote(it) },
                    raw = JsSqlSpan.textOf(source, item),
                    span = JsSqlSpan.ofNode(source, item),
                )
            }
        }
        is ParenthesedSelect -> {
            val query = item.select?.let { querySpec(it, subqueryKind) }
            if (query == null) {
                null
            } else {
                SubquerySource(
                    query = query,
                    alias = item.alias?.name?.let { Identifiers.unquote(it) },
                    raw = JsSqlSpan.textOf(source, item),
                    span = JsSqlSpan.ofNode(source, item),
                )
            }
        }
        is Values -> {
            warn("FROM VALUES 暂不支持", item)
            null
        }
        else -> {
            warn("不支持的 FROM 项: ${item.javaClass.simpleName}", item)
            null
        }
    }

    private fun joinSpec(join: Join): JoinSpec? {
        val right = join.fromItem?.let { source(it, ScopeKind.SUBQUERY) } ?: return null
        return JoinSpec(
            type = joinType(join),
            right = right,
            on = join.onExpressions.orEmpty().map { expr(it) },
            using = join.usingColumns.orEmpty().map { Identifiers.unquote(it.columnName) },
            span = JsSqlSpan.ofNode(source, join),
        )
    }

    /** JOIN 类型原文，取自 JSqlParser 的标志位；裸 `JOIN` 记为 `INNER`（其语义即内连接）。 */
    private fun joinType(join: Join): String {
        val parts = mutableListOf<String>()
        if (join.isNatural) parts += "NATURAL"
        when {
            join.isLeft -> parts += "LEFT"
            join.isRight -> parts += "RIGHT"
            join.isFull -> parts += "FULL"
            join.isCross -> parts += "CROSS"
            join.isSemi -> parts += "SEMI"
            join.isStraight -> parts += "STRAIGHT_JOIN"
            join.isApply -> parts += "APPLY"
        }
        if (join.isOuter) parts += "OUTER"
        if (parts.isEmpty()) parts += if (join.isSimple) "CROSS" else "INNER"
        return parts.joinToString(" ")
    }

    private fun outputItem(item: SelectItem<*>): OutputItem? {
        val expression = item.expression ?: return null
        return OutputItem(
            expr = expr(expression),
            alias = item.alias?.name?.let { Identifiers.unquote(it) },
            span = JsSqlSpan.ofNode(source, item),
        )
    }

    // ——— 表达式 ———

    private fun expr(expression: Expression?): SqlExpr {
        if (expression == null) return SqlExpr.Unknown(raw = "", reason = "引擎未给出该表达式", span = null)
        val raw = JsSqlSpan.textOf(source, expression)
        val span = JsSqlSpan.ofNode(source, expression)
        return when (expression) {
            is Column -> SqlExpr.Column(Identifiers.columnRef(source, expression))
            // AllTableColumns 是 AllColumns 的子类，必须先判
            is AllTableColumns -> SqlExpr.Star(
                qualifier = expression.table?.name?.let { Identifiers.unquote(it) },
                raw = raw,
                span = span,
            )
            is AllColumns -> {
                if (!expression.exceptColumns.isNullOrEmpty()) {
                    warn("`* EXCEPT` 暂不展开，按整体 `*` 处理", expression)
                }
                SqlExpr.Star(qualifier = null, raw = raw, span = span)
            }
            // 窗口函数是 AnalyticExpression，不是 Function
            is AnalyticExpression -> window(expression, raw, span)
            is Function -> SqlExpr.Function(
                name = expression.name ?: "",
                args = expression.parameters?.map { expr(it) }.orEmpty(),
                raw = raw,
                span = span,
            )
            is CaseExpression -> SqlExpr.Case(
                operand = expression.switchExpression?.let { expr(it) },
                branches = expression.whenClauses.orEmpty().map {
                    SqlExpr.Case.Branch(expr(it.whenExpression), expr(it.thenExpression))
                },
                elseExpr = expression.elseExpression?.let { expr(it) },
                raw = raw,
                span = span,
            )
            is BinaryExpression -> SqlExpr.BinaryOp(
                op = expression.stringExpression ?: "?",
                left = expr(expression.leftExpression),
                right = expr(expression.rightExpression),
                raw = raw,
                span = span,
            )
            is SignedExpression -> SqlExpr.UnaryOp(
                op = expression.sign.toString(),
                operand = expr(expression.expression),
                raw = raw,
                span = span,
            )
            is NotExpression -> SqlExpr.UnaryOp(
                op = "NOT",
                operand = expr(expression.expression),
                raw = raw,
                span = span,
            )
            // `(a + b)` 解析为单元素 ParenthesedExpressionList；没有括号节点，故透传到内层
            is ParenthesedExpressionList<*> -> {
                if (expression.size == 1) {
                    expr(expression[0])
                } else {
                    SqlExpr.Unknown(raw, "括号内不是单个表达式", span)
                }
            }
            is LongValue, is DoubleValue, is StringValue, is NullValue, is BooleanValue,
            is DateValue, is TimeValue, is TimestampValue, is HexValue, is DateTimeLiteralExpression,
            -> SqlExpr.Literal(raw, span)
            else -> SqlExpr.Unknown(raw, "未支持的表达式类型: ${expression.javaClass.simpleName}", span)
        }
    }

    private fun window(analytic: AnalyticExpression, raw: String, span: Span?): SqlExpr.Window {
        val functionSpan = analyticFunctionSpan(analytic)
        val functionRaw = functionSpan?.let { source.substring(it.start.offset, it.end.offset) } ?: raw
        val args = buildList {
            analytic.expression?.let { add(expr(it)) }
            analytic.offset?.let { add(expr(it)) }
            analytic.defaultValue?.let { add(expr(it)) }
        }
        return SqlExpr.Window(
            function = SqlExpr.Function(
                name = analytic.name ?: "",
                args = args,
                raw = functionRaw,
                span = functionSpan,
            ),
            partitionBy = analytic.partitionExpressionList?.map { expr(it) }.orEmpty(),
            orderBy = analytic.orderByElements.orEmpty().map { expr(it.expression) },
            raw = raw,
            span = span,
        )
    }

    /**
     * 窗口函数里 `function` 那一段（`OVER` 之前）的 span。
     *
     * [AnalyticExpression] 的 span 覆盖整段 `SUM(a) OVER (…)`；为了让 `Window.function`
     * 的 raw 只含函数调用本身，这里从节点首 token 走到 `OVER` 之前一个 token 为止。
     * 取不到时返回 null，调用方回落到整段原文（不猜）。
     */
    private fun analyticFunctionSpan(analytic: AnalyticExpression): Span? {
        val node = (analytic as? ASTNodeAccess)?.astNode ?: return null
        val first = node.jjtGetFirstToken() ?: return null
        var token: Token? = first
        var last: Token? = null
        while (token != null) {
            val image = token.image
            if (image != null && image.equals("OVER", ignoreCase = true)) break
            last = token
            token = token.next
        }
        val end = last ?: return null
        return JsSqlSpan.of(source, first.absoluteBegin - 1, end.absoluteEnd - 1)
    }

    // ——— 工具 ———

    private fun columnRefOrNull(expression: Expression?): ColumnRef? =
        (expression as? Column)?.let { Identifiers.columnRef(source, it) }

    /** 按 canonical 去重，保留首次出现（含位置）；非表来源（派生表等）原样保留。 */
    private fun distinctSources(sources: List<SourceSpec>): List<SourceSpec> {
        val seen = mutableSetOf<String>()
        return sources.filter { source ->
            val key = (source as? TableSource)?.table?.canonical ?: source.raw
            seen.add(key)
        }
    }

    private fun setOpText(select: SetOperationList): String {
        val ops = select.operations.orEmpty().map { it.toString() }.filter { it.isNotBlank() }
        return if (ops.isEmpty()) "SET" else ops.joinToString(" ")
    }

    private fun warn(message: String, node: Any?) {
        val span = node?.let { JsSqlSpan.ofNode(source, it) }
        diagnostics += Diagnostic.warning(CODE_SEMANTIC_PARTIAL, message, span)
    }

    private fun finish(
        kind: StatementKind,
        target: TableRef? = null,
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
        /** 诊断码：语义提取只做到部分——稳定契约，调用方按它分支，不要按文案分支。 */
        const val CODE_SEMANTIC_PARTIAL: String = "jsqlparser.semantic_partial"
    }
}
