package io.github.workeron9.ozmoz.lineage.engine.jooq

import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Span
import org.jooq.Query
import org.jooq.Param
import org.jooq.QueryPart
import org.jooq.SelectQuery
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.QOM

/**
 * jOOQ **QOM 归一化树**。
 *
 * jOOQ 没有「公开 SQL AST」，但有一套实验性的 **Query Object Model**
 *（`org.jooq.impl.QOM`，`$` 前缀访问器）——解析出来的 `QueryPart` 实现了那些 QOM 接口，
 * 结构可以读出来。本文件把 QOM 拍成与另两引擎同形状的 `ir.AstNode`，取代原先的
 * 「一层节点浅树」（2026-10-14 用户反馈：AST 对比里 jooq 一侧只有 1 个节点）。
 *
 * **与 jsqlparser / calcite 侧的三点不同**（如实声明，不假装对齐）：
 * 1. **无源位置**：jOOQ 不保留 token 位置 ⇒ 子节点 `span = null`；只有根节点给整条语句的
 *    切片（「定位到 SQL」至少能落到语句上）。
 * 2. **`text` 是渲染文本**：取 `QueryPart.toString()`（jOOQ 自己的 SQL 渲染），不是源文切片
 *    ——另两引擎是 Lossless 切片，这里做不到。
 * 3. **词表按形状归类**：QOM 把函数与算子建成一组接口，本映射按**元数**给
 *    `unary_op` / `binary_op` / `ternary_op`，函数给 `function`；认不出的落 `unknown_node`
 *    （Never-wrong：不猜语义，但保留渲染文本）。
 *
 * 两点工程事实（本机 probe 实测，别当 hack 看）：
 * - **`$` 方法在 Kotlin 里只能用反引号调**（``part.`$arg1`()``）——JVM 命名与 Kotlin
 *   标识符规则的冲突；
 * - `QOM.UOperator1/2/3` 这三个**泛型基接口是包内可见**（类文件是 public，但 `InnerClasses`
 *   属性里不是），Kotlin 连 `is` 都写不了；而**它们的 `$argN()` 经可访问的具体接口
 *   （`QOM.Eq` / `QOM.Add` …）继承下来是可调的**——所以算子只能逐个列具体接口。
 */
internal class JooqAstNormalizer(private val source: String) {

    fun normalize(query: Query): AstNode = convert(query, statementSlice())

    // ————— 顶层语句 —————

    private fun select(select: SelectQuery<*>): AstNode {
        val children = mutableListOf<AstNode>()
        select.`$with`()?.let { children += withClause(it) }
        select.`$from`()?.takeIf { it.isNotEmpty() }?.let { children += container("from", it) }
        select.`$select`()?.let { children += container("select_list", it) }
        select.`$where`()?.let { children += wrap("where", it) }
        select.`$groupBy`()?.takeIf { it.isNotEmpty() }?.let { children += container("group_by", it) }
        select.`$having`()?.let { children += wrap("having", it) }
        select.`$orderBy`()?.takeIf { it.isNotEmpty() }?.let { children += container("order_by", it) }
        select.`$limit`()?.let { children += wrap("limit", it) }
        select.`$offset`()?.let { children += wrap("offset", it) }
        return AstNode(type = "select", text = select.toString(), span = statementSlice(), children = children)
    }

    /**
     * WITH 子句：CTE 逐个进 `cte` 节点。CTE 内部查询**结构不展开**——`CommonTableExpression`
     * 的 QOM 访问器在 OSS 里给不出可用的部分分解，展开只能猜；如实留成渲染文本（Never-wrong）。
     */
    private fun withClause(with: QOM.With): AstNode {
        val ctes = with.`$commonTableExpressions`()?.map { cte ->
            AstNode(type = "cte", text = cte.toString(), span = null, children = emptyList())
        } ?: emptyList()
        return AstNode(type = "with", text = with.toString(), span = null, children = ctes)
    }

    // ————— 表与连接 —————

    private fun joinTable(join: QOM.JoinTable<*, *>): AstNode {
        val children = buildList {
            join.`$table1`()?.let { add(convert(it)) }
            join.`$table2`()?.let { add(convert(it)) }
            (join as? QOM.QualifiedJoin<*, *>)?.`$on`()?.let { add(wrap("on", it)) }
        }
        return AstNode(type = joinType(join), text = join.toString(), span = null, children = children)
    }

    /** 连接类型进 `type`（`left_join` / `inner_join` …），与 calcite 侧命名口径一致。 */
    private fun joinType(join: QOM.JoinTable<*, *>): String = when {
        join is QOM.LeftJoin<*> -> "left_join"
        join is QOM.RightJoin<*> -> "right_join"
        join is QOM.FullJoin<*> -> "full_join"
        join is QOM.CrossJoin<*> -> "cross_join"
        else -> "join"
    }

    private fun tableAlias(alias: QOM.TableAlias<*>): AstNode = AstNode(
        type = "alias",
        text = alias.`$alias`()?.toString() ?: "",
        span = null,
        children = listOfNotNull(alias.`$table`()?.let { convert(it) }),
    )

    private fun derivedTable(table: QOM.DerivedTable<*>): AstNode {
        val inner = table.`$arg1`() as? QueryPart
        return AstNode(
            type = "derived_table",
            text = table.toString(),
            span = null,
            children = listOfNotNull(inner?.let { convert(it) }),
        )
    }

    // ————— 表达式 —————

    private fun fieldAlias(alias: QOM.FieldAlias<*>): AstNode = AstNode(
        type = "alias",
        text = alias.`$alias`()?.toString() ?: "",
        span = null,
        children = listOfNotNull(alias.`$field`()?.let { convert(it) }),
    )

    private fun castNode(cast: QOM.Cast<*>): AstNode = AstNode(
        type = "cast",
        text = cast.toString(),
        span = null,
        children = buildList {
            cast.`$field`()?.let { add(convert(it)) }
            // 目标类型不参与列推导（与 calcite 侧同口径）。
            cast.`$dataType`()?.let { add(AstNode(type = "unknown_node", text = it.toString(), span = null)) }
        },
    )

    private fun caseNode(
        case: QueryPart,
        value: Any?,
        whens: List<QOM.Tuple2<*, *>>?,
        elsePart: Any?,
    ): AstNode {
        val children = mutableListOf<AstNode>()
        value?.let { children += asParts(it).map { p -> convert(p) } }
        whens?.forEach { pair ->
            children += AstNode(
                type = "when",
                text = pair.toString(),
                span = null,
                children = listOfNotNull(asParts(pair.`$1`()), asParts(pair.`$2`())).flatten().map { convert(it) },
            )
        }
        elsePart?.let {
            children += AstNode(type = "else", text = it.toString(), span = null, children = asParts(it).map { p -> convert(p) })
        }
        return AstNode(type = "case", text = case.toString(), span = null, children = children)
    }

    private fun function(name: String, part: QueryPart, args: List<QueryPart>): AstNode =
        AstNode(type = "function", text = "$name(${args.joinToString(", ") { it.toString() }})", span = null, children = args.map { convert(it) })

    private fun operator(part: QueryPart, type: String, args: List<QueryPart>): AstNode =
        AstNode(type = type, text = part.toString(), span = null, children = args.map { convert(it) })

    // ————— 通用 —————

    private fun convert(part: QueryPart?, span: Span? = null): AstNode = when {
        part == null -> AstNode.empty()
        part is SelectQuery<*> -> select(part)
        // ——— 非 SELECT 语句：各自也有 QOM 访问器（拿不到就退化成整体渲染文本） ———
        part is QOM.Insert<*> -> statement(
            "insert", part,
            listOfNotNull(part.`$with`(), part.`$into`(), part.`$columns`(), part.`$select`() ?: part.`$values`(), part.`$onConflict`()),
        )
        part is QOM.Update<*> -> statement(
            "update", part,
            listOfNotNull(part.`$with`(), part.`$table`(), part.`$set`(), part.`$from`(), part.`$where`(), part.`$orderBy`(), part.`$limit`()),
        )
        part is QOM.Delete<*> -> statement(
            "delete", part,
            listOfNotNull(part.`$with`(), part.`$from`(), part.`$using`(), part.`$where`(), part.`$orderBy`(), part.`$limit`()),
        )
        part is QOM.Merge<*> -> statement(
            "merge", part,
            listOfNotNull(part.`$with`(), part.`$into`(), part.`$using`(), part.`$on`(), part.`$whenMatched`(), part.`$whenNotMatched`()),
        )
        part is QOM.CreateTable -> statement(
            "create_table", part,
            listOfNotNull(part.`$table`(), part.`$tableElements`(), part.`$select`()),
        )
        part is QOM.CreateView<*> -> statement(
            "create_view", part,
            listOfNotNull(part.`$view`(), part.`$fields`(), part.`$query`()),
        )
        part is QOM.With -> withClause(part)
        // 具体派生 / 别名 / 连接必须排在通用 Table 之前（它们都实现 Table）。
        part is QOM.JoinTable<*, *> -> joinTable(part)
        part is QOM.DerivedTable<*> -> derivedTable(part)
        part is QOM.TableAlias<*> -> tableAlias(part)
        part is QOM.FieldAlias<*> -> fieldAlias(part)
        part is QOM.Cast<*> -> castNode(part)
        part is QOM.CaseSearched<*> -> caseNode(part, null, part.`$when`(), part.`$else`())
        part is QOM.CaseSimple<*, *> -> caseNode(part, part.`$value`(), part.`$when`(), part.`$else`())
        // ——— 函数：各自声明了具名访问器 ———
        part is QOM.Sum -> function("sum", part, listOfNotNull(part.`$field`()))
        part is QOM.Count -> function("count", part, listOfNotNull(part.`$field`()))
        part is QOM.Round<*> -> function("round", part, listOfNotNull(part.`$value`(), part.`$decimals`()))
        part is QOM.Substring -> function("substring", part, listOfNotNull(part.`$string`(), part.`$startingPosition`(), part.`$length`()))
        part is QOM.Nullif<*> -> function("nullif", part, listOfNotNull(part.`$value`(), part.`$other`()))
        part is QOM.ToChar -> function("to_char", part, listOfNotNull(part.`$value`(), part.`$formatMask`()))
        part is QOM.Mod<*> -> function("mod", part, listOfNotNull(part.`$dividend`(), part.`$divisor`()))
        // QOM.Function/$args 是通用入口（部分函数实现它）。
        part is QOM.Function<*> -> function(partName(part), part, part.`$args`()?.map { it } ?: emptyList())
        // ——— 算子：$argN() 继承自包内可见的算子接口，经具体接口可见 ———
        part is QOM.Not -> operator(part, "unary_op", asParts(part.`$condition`()))
        part is QOM.Like -> operator(part, "ternary_op", asParts(part.`$value`()) + asParts(part.`$pattern`()) + asParts(part.`$escape`()))
        part is QOM.Eq<*> -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Ne<*> -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Lt<*> -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Le<*> -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Gt<*> -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Ge<*> -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Add<*> -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Sub<*> -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Mul<*> -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Div<*> -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.And -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Or -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Neg<*> -> unary(part, part.`$arg1`())
        part is QOM.Coalesce<*> -> variadic("coalesce", part, part.`$arg1`())
        part is QOM.Concat -> variadic("concat", part, part.`$arg1`())
        part is QOM.In<*> -> binary(part, part.`$arg1`(), part.`$arg2`())
        part is QOM.Between<*> -> ternary(part, part.`$arg1`(), part.`$arg2`(), part.`$arg3`())
        // ——— 叶子 ———
        // `Param` = 字面量 / 绑定值（jOOQ 的 `Val` 实现它）——QOM 没有 `Val` 接口，按 Param 认。
        part is Param<*> -> AstNode(type = "literal", text = part.toString(), span = span, children = emptyList())
        part is TableField<*, *> -> AstNode(type = "column", text = part.toString(), span = span, children = emptyList())
        part is Table<*> -> AstNode(type = "table", text = part.toString(), span = span, children = emptyList())
        // 顶层语句兜底：没逐个映射的语句种类（DROP / TRUNCATE …）仍按实现类名给类型，
        // 不要退化成 unknown_node（旧浅树的行为）。
        part is Query -> AstNode(type = partName(part), text = part.toString(), span = span, children = emptyList())
        // 认不出的：不猜语义，保留渲染文本（Never-wrong）。
        else -> AstNode(type = "unknown_node", text = part.toString(), span = span, children = emptyList())
    }

    /** 非 SELECT 语句：`type` + 渲染文本 + 拿得到的部件（拿不到的就不假装有）。 */
    private fun statement(type: String, part: QueryPart, values: List<Any?>): AstNode = AstNode(
        type = type,
        text = part.toString(),
        span = null,
        children = values.flatMap { asParts(it) }.map { convert(it) },
    )

    private fun partName(part: QueryPart): String =
        part.javaClass.simpleName.removeSuffix("Impl").removeSuffix("Query").lowercase()

    private fun binary(part: QueryPart, a: Any?, b: Any?): AstNode =
        operator(part, "binary_op", asParts(a) + asParts(b))

    private fun ternary(part: QueryPart, a: Any?, b: Any?, c: Any?): AstNode =
        operator(part, "ternary_op", asParts(a) + asParts(b) + asParts(c))

    private fun unary(part: QueryPart, a: Any?): AstNode =
        operator(part, "unary_op", asParts(a))

    /** 变参函数：`$arg1()` 在这里是一个 `List<Field>`（UOperator1 的载荷）。 */
    private fun variadic(name: String, part: QueryPart, args: Any?): AstNode =
        function(name, part, asParts(args))

    /** QOM 访问器返回 `Any`（载荷可能是单个 `QueryPart`，也可能是 `List<QueryPart>`）。 */
    private fun asParts(value: Any?): List<QueryPart> = when (value) {
        null -> emptyList()
        // `List` 必须先判：jOOQ 的 `QueryPartListView` **同时**实现 `QueryPart` 与 `List`，
        // 先判 `QueryPart` 会把整个参数列表当成一个节点（本机实测 50 个节点因此误落 unknown）。
        is List<*> -> value.filterIsInstance<QueryPart>()
        is QueryPart -> listOf(value)
        else -> emptyList()
    }

    private fun container(type: String, parts: List<QueryPart>): AstNode = AstNode(
        type = type,
        text = parts.joinToString(", ") { it.toString() },
        span = null,
        children = parts.map { convert(it) },
    )

    private fun wrap(type: String, part: QueryPart): AstNode =
        AstNode(type = type, text = part.toString(), span = null, children = listOf(convert(part)))

    /** 整条语句的源文切片（trim 掉首尾空白，偏移对应到切片边界）。 */
    private fun statementSlice(): Span? {
        val text = source.trim()
        if (text.isEmpty()) return null
        val start = source.indexOf(text).coerceAtLeast(0)
        return Span.of(source, start, (start + text.length).coerceAtMost(source.length))
    }
}
