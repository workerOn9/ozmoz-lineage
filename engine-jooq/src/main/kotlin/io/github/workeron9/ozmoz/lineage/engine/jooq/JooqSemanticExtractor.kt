package io.github.workeron9.ozmoz.lineage.engine.jooq

import io.github.workeron9.ozmoz.lineage.engine.semantics.Assignment
import io.github.workeron9.ozmoz.lineage.engine.semantics.CteSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.JoinSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.OutputItem
import io.github.workeron9.ozmoz.lineage.engine.semantics.QuerySpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.ScopeSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.SourceSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.engine.semantics.SubquerySource
import io.github.workeron9.ozmoz.lineage.engine.semantics.TableSource
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import org.jooq.Asterisk
import org.jooq.CommonTableExpression
import org.jooq.Field
import org.jooq.GroupField
import org.jooq.Param
import org.jooq.QualifiedAsterisk
import org.jooq.Query
import org.jooq.SelectFieldOrAsterisk
import org.jooq.SelectQuery as JooqSelect
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.QOM

/**
 * jOOQ **语义模型提取**（QOM → `semantics.SemanticStatement`）——`lineage` 模块的输入。
 *
 * 与 `CalciteSemanticExtractor` / jsqlparser 侧 `SemanticExtractor` **同一契约**，
 * 数据来源是 jOOQ 的 **QOM**（`org.jooq.impl.QOM` 的 `$` 访问器，见 [JooqAstNormalizer]）。
 *
 * **与另两引擎的四点差异（如实声明）**：
 * 1. **无源位置 / 非源文切片**：jOOQ 不保留 token 位置，`span` 一律 null；`raw` 取
 *    `QueryPart.toString()`（jOOQ 自己的渲染），**不是** Lossless 源文切片。
 *    血缘解析只依赖 `name` / `table` / `canonical`，`raw` 是展示字段——影响面已知。
 * 2. **集合运算（UNION / INTERSECT / EXCEPT）不建模**：QOM 不暴露集合运算
 *    （`hasUnions()` 是包内方法、`union` 字段私有、公开接口只有构造方法没有读取入口），
 *    而 `$select()` 在 UNION 上**只给第一个分支**——照它建模会**静默漏掉其余分支**，
 *    这是 Never-wrong 的红线。所以**检测到就拒绝**（[Resolved.Unknown] 带原因），
 *    检测手段见 [looksLikeSetOperation]（失败方向是安全的：宁可拒绝，绝不产错模型）。
 * 3. **CTE 的显式列清单不携带**：`WITH c(a, b) AS …` 的列清单在 QOM 里没有可用的读取口，
 *    `CteSpec.columns` 一律为空（名字与内部查询正常）。
 * 4. **词表覆盖有限的函数 / 算子**：同 [JooqAstNormalizer]，认不出的落 [SqlExpr.Unknown]。
 */
internal class JooqSemanticExtractor(private val source: String) {

    fun extract(query: Query): Resolved<SemanticStatement>? = when {
        query is JooqSelect<*> -> statement(StatementKind.SELECT, query = querySpec(query, ScopeKind.SELECT))
        query is QOM.Insert<*> -> insertStatement(query)
        query is QOM.Update<*> -> updateStatement(query)
        query is QOM.Delete<*> -> deleteStatement(query)
        query is QOM.CreateView<*> -> createViewStatement(query)
        query is QOM.CreateTable -> ctasStatement(query)
        // MERGE / VALUES / DROP / TRUNCATE… 不建模（与 calcite 侧同口径：跳过，不硬塞）
        else -> null
    }

    // ————— 语句 —————

    private fun insertStatement(insert: QOM.Insert<*>): Resolved<SemanticStatement> {
        val target = insert.`$into`()?.let { tableRef(it) }
        val targetColumns = insert.`$columns`()?.mapNotNull { columnRef(it) } ?: emptyList()
        // `$select()` 的静态类型是 `Select<?>`（运行时才是 SelectQueryImpl）——安全转换，转不了就如实拒绝
        val select = insert.`$select`() as? JooqSelect<*>
            ?: return Resolved.Unknown("INSERT 没有可建模的查询来源（VALUES / 非 SELECT 源不产列级血缘）")
        val query = querySpec(select, ScopeKind.INSERT)
            ?: return Resolved.Unknown("INSERT 的查询来源暂不可建模")
        return statement(StatementKind.INSERT, target = target, targetColumns = targetColumns, query = query)
    }

    private fun createViewStatement(view: QOM.CreateView<*>): Resolved<SemanticStatement> {
        val target = view.`$view`()?.let { tableRef(it) }
        val targetColumns = view.`$fields`()?.mapNotNull { columnRef(it) } ?: emptyList()
        val body = view.`$query`() as? JooqSelect<*>
            ?: return Resolved.Unknown("CREATE VIEW 的查询体不是 SELECT（暂不可建模）")
        val query = querySpec(body, ScopeKind.CREATE_VIEW)
            ?: return Resolved.Unknown("CREATE VIEW 的查询体暂不可建模")
        return statement(StatementKind.CREATE_VIEW, target = target, targetColumns = targetColumns, query = query)
    }

    private fun ctasStatement(createTable: QOM.CreateTable): Resolved<SemanticStatement>? {
        val select = createTable.`$select`() ?: return null // 纯 DDL（没有 AS SELECT）→ 不建模
        val body = select as? JooqSelect<*>
            ?: return Resolved.Unknown("CREATE TABLE AS 的查询体不是 SELECT（暂不可建模）")
        val target = createTable.`$table`()?.let { tableRef(it) }
        val query = querySpec(body, ScopeKind.CREATE_TABLE_AS)
            ?: return Resolved.Unknown("CREATE TABLE AS 的查询体暂不可建模")
        return statement(StatementKind.CREATE_TABLE_AS, target = target, query = query)
    }

    private fun updateStatement(update: QOM.Update<*>): Resolved<SemanticStatement> {
        val target = update.`$table`()?.let { tableRef(it) }
        val assignments = buildList {
            for ((lhs, rhs) in update.`$set`() ?: emptyMap<Any, Any>()) {
                val column = (lhs as? Field<*>)?.let { columnRef(it) } ?: continue
                add(Assignment(target = column, value = expr(rhs as? org.jooq.QueryPart)))
            }
        }
        val scope = ScopeSpec(
            kind = ScopeKind.UPDATE,
            sources = distinctSources(
                (update.`$from`() ?: emptyList()).mapNotNull { sourceOf(it) } +
                    listOfNotNull(target?.let { TableSource(it) }),
            ),
            filters = update.`$where`()?.let { listOf(expr(it)) } ?: emptyList(),
        )
        return statement(StatementKind.UPDATE, target = target, assignments = assignments, scope = scope)
    }

    private fun deleteStatement(delete: QOM.Delete<*>): Resolved<SemanticStatement> {
        val target = delete.`$from`()?.let { tableRef(it) }
        val scope = ScopeSpec(
            kind = ScopeKind.DELETE,
            sources = distinctSources(
                listOfNotNull(target?.let { TableSource(it) }) +
                    (delete.`$using`() ?: emptyList()).mapNotNull { sourceOf(it) },
            ),
            filters = delete.`$where`()?.let { listOf(expr(it)) } ?: emptyList(),
        )
        return statement(StatementKind.DELETE, target = target, scope = scope)
    }

    // ————— 查询 / 作用域 —————

    /** [SelectQuery] → [QuerySpec]。**集合运算拒绝建模**（见类 KDoc 第 2 点）。 */
    private fun querySpec(select: JooqSelect<*>, kind: ScopeKind): QuerySpec? {
        if (looksLikeSetOperation(select)) {
            return null
        }
        return SelectQuery(
            scope = scopeOf(select, kind),
            ctes = ctesOf(select.`$with`()),
            raw = select.toString(),
            span = null,
        )
    }

    private fun scopeOf(select: JooqSelect<*>, kind: ScopeKind): ScopeSpec {
        val (sources, joins) = flattenFrom(select.`$from`() ?: emptyList())
        return ScopeSpec(
            kind = kind,
            sources = sources,
            joins = joins,
            filters = select.`$where`()?.let { listOf(expr(it)) } ?: emptyList(),
            groupBy = (select.`$groupBy`() ?: emptyList()).map { groupExpr(it) },
            having = select.`$having`()?.let { listOf(expr(it)) } ?: emptyList(),
            orderBy = (select.`$orderBy`() ?: emptyList()).map { orderExpr(it) },
            outputs = (select.`$select`() ?: emptyList()).mapNotNull { outputItem(it) },
        )
    }

    private fun ctesOf(with: QOM.With?): List<CteSpec> =
        (with?.`$commonTableExpressions`() ?: emptyList()).mapNotNull { cte ->
            val name = cte.name?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val body = cte.`$query`() as? JooqSelect<*> ?: return@mapNotNull null
            val query = querySpec(body, ScopeKind.CTE) ?: return@mapNotNull null
            // 显式列清单（`WITH c(a, b) AS …`）在 QOM 里没读取口——如实留空（类 KDoc 第 3 点）。
            CteSpec(name = name, columns = emptyList(), query = query)
        }

    /** `$from()` 是**整棵连接树**（jOOQ 把 `a join b on …` 装在一个 [QOM.JoinTable] 里）——递归拆平。 */
    private fun flattenFrom(from: List<Table<*>>): Pair<List<SourceSpec>, List<JoinSpec>> {
        val sources = mutableListOf<SourceSpec>()
        val joins = mutableListOf<JoinSpec>()
        for (table in from) appendFrom(table, sources, joins)
        return sources to joins
    }

    private fun appendFrom(table: Table<*>, sources: MutableList<SourceSpec>, joins: MutableList<JoinSpec>) {
        if (table is QOM.JoinTable<*, *>) {
            table.`$table1`()?.let { appendFrom(it, sources, joins) }
            val right = table.`$table2`()?.let { sourceOf(it) }
            if (right != null) {
                joins += JoinSpec(
                    type = joinType(table),
                    right = right,
                    on = (table as? QOM.QualifiedJoin<*, *>)?.`$on`()?.let { listOf(expr(it)) } ?: emptyList(),
                )
            }
            return
        }
        sourceOf(table)?.let { sources += it }
    }

    private fun sourceOf(table: Table<*>): SourceSpec? = when {
        // 别名包着派生表（`(SELECT …) AS s`）最常见：先剥别名再看内层。
        table is QOM.TableAlias<*> -> {
            val alias = table.`$alias`()?.toString()
            when (val inner = table.`$table`()) {
                is QOM.DerivedTable<*> -> inner.`$arg1`()?.let { subquerySource(it, alias, table.toString()) }
                null -> null
                // 内层也要过「名字得是合法标识符」这一关（伪表常被别名包着，如 `VALUES (1,2)`）
                else -> nameSegments(inner).lastOrNull()?.let { TableSource(tableRef(inner, alias)) }
            }
        }
        table is QOM.DerivedTable<*> -> table.`$arg1`()?.let { subquerySource(it, null, table.toString()) }
        // 名字必须是合法标识符才当表：jOOQ 会把 `VALUES (1,2)` 这类**没有真实表身份**的东西
        // 包成一个「渲染文本就是整段 SQL」的伪表（本机实测），把它当物理表会凭空造一个来源。
        else -> nameSegments(table).lastOrNull()?.let { TableSource(tableRef(table)) }
    }

    /**
     * 表名的**段**（`schema.name` 拆开）：从 jOOQ 的**渲染文本**取——`Table.getName()` 只给
     * 非限定名（schema 不在其中，3.21 的 `Table` 也没有 `getSchema()`），照它建引用会丢掉
     * `schema.` 前缀，schema 里按限定名收录的表就查不到，该来源**无法被证伪**，裸列全落
     * 「列名无法唯一归属」（本机实测：真实 SQL 92 条 unknown → 修后 1 条）。
     * 段必须是合法标识符，否则视为**伪表**（见 `sourceOf`）。
     */
    private fun nameSegments(table: Table<*>): List<String> =
        table.toString().split('.')
            .map { it.trim().trim('`', '"', '[', ']') }
            .filter { it.isNotBlank() && IDENTIFIER.matches(it) }

    private fun subquerySource(inner: Query, alias: String?, raw: String): SourceSpec? {
        val query = (inner as? JooqSelect<*>)?.let { querySpec(it, ScopeKind.SUBQUERY) } ?: return null
        return SubquerySource(query = query, alias = alias, raw = raw)
    }

    // ————— 输出列 / 表达式 —————

    private fun outputItem(item: SelectFieldOrAsterisk): OutputItem? = when (item) {
        is Asterisk -> OutputItem(expr = SqlExpr.Star(qualifier = null, raw = item.toString(), span = null))
        is QualifiedAsterisk -> OutputItem(
            expr = SqlExpr.Star(qualifier = item.`$table`()?.name?.toString(), raw = item.toString(), span = null),
        )
        is Field<*> -> OutputItem(expr = expr(item), alias = aliasOf(item))
        else -> OutputItem(expr = SqlExpr.Unknown(item.toString(), "未支持的 SELECT 项", null))
    }

    /** 显式别名（`AS x`）——只有包着 [QOM.FieldAlias] 才算；函数自带的 `getName()` 不是别名。 */
    private fun aliasOf(field: Field<*>): String? =
        (field as? QOM.FieldAlias<*>)?.`$alias`()?.toString()

    private fun groupExpr(group: GroupField): SqlExpr =
        (group as? Field<*>)?.let { expr(it) } ?: SqlExpr.Unknown(group.toString(), "GROUP BY 项暂不参与列推导", null)

    private fun orderExpr(sort: org.jooq.SortField<*>): SqlExpr =
        sort.`$field`()?.let { expr(it) } ?: SqlExpr.Unknown(sort.toString(), "ORDER BY 项暂不参与列推导", null)

    private fun expr(part: org.jooq.QueryPart?): SqlExpr {
        if (part == null) return unknown("", "引擎未给出该表达式")
        val raw = part.toString()
        return when {
            // 别名只是包一层：真正的表达式在内层（别名由 outputItem 取）
            part is QOM.FieldAlias<*> -> part.`$field`()?.let { expr(it) } ?: unknown(raw, "别名缺少内层表达式")
            // 字面量 / 绑定值：jOOQ 的 `Val` 实现 `Param`
            part is Param<*> -> SqlExpr.Literal(raw, null)
            part is TableField<*, *> -> columnRef(part)?.let { SqlExpr.Column(it) } ?: unknown(raw, "标识符缺失列名")
            part is QOM.Cast<*> -> castExpr(part, raw)
            part is QOM.CaseSearched<*> -> caseExpr(part, null, part.`$when`(), part.`$else`(), raw)
            part is QOM.CaseSimple<*, *> -> caseExpr(part, part.`$value`(), part.`$when`(), part.`$else`(), raw)
            // 函数：各自声明了具名访问器（QOM 没有统一的「函数」入口）
            part is QOM.Sum -> function("sum", part, listOfNotNull(part.`$field`()), raw)
            part is QOM.Count -> function("count", part, listOfNotNull(part.`$field`()), raw)
            part is QOM.Round<*> -> function("round", part, listOfNotNull(part.`$value`(), part.`$decimals`()), raw)
            part is QOM.Substring -> function(
                "substring", part,
                listOfNotNull(part.`$string`(), part.`$startingPosition`(), part.`$length`()), raw,
            )
            part is QOM.Nullif<*> -> function("nullif", part, listOfNotNull(part.`$value`(), part.`$other`()), raw)
            part is QOM.ToChar -> function("to_char", part, listOfNotNull(part.`$value`(), part.`$formatMask`()), raw)
            part is QOM.Mod<*> -> function("mod", part, listOfNotNull(part.`$dividend`(), part.`$divisor`()), raw)
            part is QOM.Function<*> -> function(part.name ?: "function", part, part.`$args`() ?: emptyList(), raw)
            // niladic 日期时间（`QOM.CurrentDate` 等是 UEmpty 的 Field）：无参函数，不贡献列
            part is QOM.CurrentDate<*> -> function("current_date", part, emptyList(), raw)
            part is QOM.CurrentTimestamp<*> -> function("current_timestamp", part, emptyList(), raw)
            // 算子（`$argN()` 继承自包内可见的算子接口，经具体接口可见——见 JooqAstNormalizer KDoc）
            part is QOM.Not -> SqlExpr.UnaryOp("NOT", expr(part.`$condition`() as? org.jooq.QueryPart), raw, null)
            part is QOM.Eq<*> -> binary("=", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Ne<*> -> binary("<>", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Lt<*> -> binary("<", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Le<*> -> binary("<=", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Gt<*> -> binary(">", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Ge<*> -> binary(">=", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Add<*> -> binary("+", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Sub<*> -> binary("-", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Mul<*> -> binary("*", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Div<*> -> binary("/", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.And -> binary("AND", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Or -> binary("OR", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Neg<*> -> SqlExpr.UnaryOp("-", expr(part.`$arg1`() as? org.jooq.QueryPart), raw, null)
            part is QOM.Coalesce<*> -> function("coalesce", part, partsOf(part.`$arg1`()), raw)
            part is QOM.Concat -> function("concat", part, partsOf(part.`$arg1`()), raw)
            part is QOM.In<*> -> binary("IN", part.`$arg1`(), part.`$arg2`(), raw)
            part is QOM.Between<*> -> betweenExpr(part, raw)
            // 认不出的：不猜语义（Never-wrong），保留渲染文本与类名
            else -> unknown(raw, "未支持的表达式类型: ${part.javaClass.simpleName}")
        }
    }

    private fun castExpr(cast: QOM.Cast<*>, raw: String): SqlExpr = SqlExpr.Function(
        name = "cast",
        args = buildList {
            cast.`$field`()?.let { add(expr(it)) }
            // 目标类型不参与列推导（与 calcite 侧同口径）
            cast.`$dataType`()?.let { add(unknown(it.toString(), "CAST 目标类型不参与列推导")) }
        },
        raw = raw,
        span = null,
    )

    private fun caseExpr(
        part: org.jooq.QueryPart,
        value: Any?,
        whens: List<QOM.Tuple2<*, *>>?,
        elsePart: Any?,
        raw: String,
    ): SqlExpr = SqlExpr.Case(
        operand = (value as? org.jooq.QueryPart)?.let { expr(it) },
        branches = (whens ?: emptyList()).map { pair ->
            SqlExpr.Case.Branch(
                condition = expr(pair.`$1`() as? org.jooq.QueryPart),
                result = expr(pair.`$2`() as? org.jooq.QueryPart),
            )
        },
        elseExpr = (elsePart as? org.jooq.QueryPart)?.let { expr(it) },
        raw = raw,
        span = null,
    )

    /** Calcite 把 `BETWEEN` 包成 `BinaryOp(BETWEEN, expr, AND(lo, hi))`——这里同口径，保住上下界里的列。 */
    private fun betweenExpr(between: QOM.Between<*>, raw: String): SqlExpr {
        val value = expr(between.`$arg1`() as? org.jooq.QueryPart)
        val lo = expr(between.`$arg2`() as? org.jooq.QueryPart)
        val hi = expr(between.`$arg3`() as? org.jooq.QueryPart)
        return SqlExpr.BinaryOp("BETWEEN", value, SqlExpr.BinaryOp("AND", lo, hi, raw, null), raw, null)
    }

    private fun binary(op: String, a: Any?, b: Any?, raw: String): SqlExpr =
        SqlExpr.BinaryOp(op, expr(a as? org.jooq.QueryPart), expr(b as? org.jooq.QueryPart), raw, null)

    private fun function(name: String, part: org.jooq.QueryPart, args: List<Any?>, raw: String): SqlExpr =
        SqlExpr.Function(name = name, args = args.filterNotNull().map { expr(it as? org.jooq.QueryPart) }, raw = raw, span = null)

    private fun partsOf(value: Any?): List<Any?> = when (value) {
        null -> emptyList()
        // `List` 必须先判：jOOQ 的 `QueryPartListView` 同时实现 `QueryPart` 与 `List`
        is List<*> -> value
        else -> listOf(value)
    }

    private fun unknown(raw: String, reason: String): SqlExpr = SqlExpr.Unknown(raw, reason, null)

    // ————— 引用 —————

    private fun tableRef(table: Table<*>, alias: String? = null): TableRef {
        val raw = table.toString()
        val segments = nameSegments(table)
        val name = segments.lastOrNull() ?: raw
        val schema = segments.dropLast(1).lastOrNull()
        return TableRef(
            raw = raw,
            canonical = segments.joinToString(".").lowercase().ifBlank { name.lowercase() },
            schema = schema,
            name = name,
            alias = alias,
        )
    }

    /** [TableField] → [ColumnRef]（`getTable()` 在裸列上是 null，与 jsqlparser 的裸列同形）。 */
    private fun columnRef(field: Field<*>): ColumnRef? {
        val name = field.name?.trim().orEmpty()
        if (name.isEmpty()) return null
        val table = (field as? TableField<*, *>)?.table?.name?.toString()?.trim()?.ifEmpty { null }
        return ColumnRef(
            raw = field.qualifiedName?.toString() ?: name,
            canonical = listOfNotNull(table, name).joinToString(".").lowercase(),
            name = name,
            table = table,
        )
    }

    private fun distinctSources(sources: List<SourceSpec>): List<SourceSpec> {
        val seen = mutableSetOf<String>()
        return sources.filter { seen.add(it.raw + "|" + it.alias) }
    }

    private fun joinType(join: QOM.JoinTable<*, *>): String = when {
        join is QOM.LeftJoin<*> -> "LEFT OUTER"
        join is QOM.RightJoin<*> -> "RIGHT OUTER"
        join is QOM.FullJoin<*> -> "FULL OUTER"
        join is QOM.CrossJoin<*> -> "CROSS"
        else -> "INNER"
    }

    /**
     * 集合运算的**检测**。QOM 拿不到（见类 KDoc 第 2 点），只能看 jOOQ 的**渲染文本**。
     *
     * 失败方向是安全的：多判 = 拒绝建模（用户看到原因），少判才危险。jOOQ 渲染集合运算
     * 必然带 `union` / `intersect` / `except` 关键字，所以「少判」实际不会发生；反过来
     * 文本里恰好出现这些词（字符串字面量 / 引号标识符）会**多判**——代价只是这一条不建模。
     */
    private fun looksLikeSetOperation(select: JooqSelect<*>): Boolean =
        SET_OPERATION.containsMatchIn(select.toString())

    private fun statement(
        kind: StatementKind,
        target: TableRef? = null,
        targetColumns: List<ColumnRef> = emptyList(),
        assignments: List<Assignment> = emptyList(),
        query: QuerySpec? = null,
        scope: ScopeSpec? = null,
    ): Resolved<SemanticStatement> {
        if (query == null && scope == null) {
            return Resolved.Unknown("该语句没有可建模的查询体")
        }
        return Resolved.Known(
            SemanticStatement(
                kind = kind,
                target = target,
                targetColumns = targetColumns,
                assignments = assignments,
                query = query,
                scope = scope,
            ),
        )
    }

    private companion object {
        /** 集合运算关键字（渲染文本）——见 [looksLikeSetOperation]。 */
        private val SET_OPERATION = Regex("(?i)\\b(union|intersect|except)\\b")

        /** 合法标识符（表名的准入门槛：伪表的名字是整段渲染文本，形状就不对）。 */
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_$]*")
    }
}
