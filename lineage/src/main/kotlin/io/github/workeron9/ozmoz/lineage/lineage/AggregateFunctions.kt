package io.github.workeron9.ozmoz.lineage.lineage

import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.referencedColumns
import io.github.workeron9.ozmoz.lineage.ir.TransformKind

/**
 * 聚合函数注册表（规则 C）——**策划清单**（curated list），不是猜测。
 *
 * 只有折叠（小写）后命中本清单的函数名才标 [TransformKind.AGGREGATE]；
 * 未收录的函数名一律按普通函数分类（[TransformKind.EXPRESSION] /
 * [TransformKind.CONSTANT]）——**这不是猜它不是聚合**，而是「是否聚合」
 * 只能由这份显式清单决定：宁可少标，也不给未经确认的语义。
 */
internal object AggregateFunctions {

    private val names = setOf(
        "sum", "count", "avg", "min", "max",
        "stddev", "stddev_pop", "stddev_samp",
        "variance", "var_pop", "var_samp",
        "median", "bool_and", "bool_or", "every", "any_value",
        "array_agg", "string_agg", "group_concat", "listagg",
    )

    /** 函数名（折叠比较）是否在策划清单里。 */
    fun contains(name: String): Boolean = name.lowercase() in names
}

/**
 * 规则 C：输出槽（或 UPDATE 赋值）表达式的 [TransformKind] 分类。
 *
 * - [SqlExpr.Column] → [TransformKind.DIRECT]
 * - [SqlExpr.Function] → 名字在 [AggregateFunctions] → [TransformKind.AGGREGATE]
 *   （含 `COUNT(*)`：聚合与上游无关，`*` 不给上游）；不在清单且无列引用 →
 *   [TransformKind.CONSTANT]；否则 [TransformKind.EXPRESSION]
 * - [SqlExpr.Window] → [TransformKind.WINDOW]（上游 = 函数实参 + partitionBy +
 *   orderBy 的全部列引用，即 [SqlExpr.referencedColumns]）
 * - [SqlExpr.Case] → 有列引用 [TransformKind.CASE_BRANCH]，否则 [TransformKind.CONSTANT]
 * - [SqlExpr.Literal] → [TransformKind.CONSTANT]（无边）
 * - [SqlExpr.BinaryOp] / [SqlExpr.UnaryOp] → 有列引用 [TransformKind.EXPRESSION]，
 *   否则 [TransformKind.CONSTANT]
 * - [SqlExpr.Star] → 特殊：输出项顶层的 `*` 在建槽前已被规则 D 展开，不会走到这里；
 *   出现在其他位置（如 UPDATE 赋值）的星号显式标 [TransformKind.UNKNOWN]，不猜。
 * - [SqlExpr.Unknown] → [TransformKind.UNKNOWN]（无边，原因由建槽处记进 unknowns）
 */
internal fun classifyTransform(expr: SqlExpr): TransformKind = when (expr) {
    is SqlExpr.Column -> TransformKind.DIRECT

    is SqlExpr.Function -> when {
        AggregateFunctions.contains(expr.name) -> TransformKind.AGGREGATE
        expr.referencedColumns().isEmpty() -> TransformKind.CONSTANT
        else -> TransformKind.EXPRESSION
    }

    is SqlExpr.Window -> TransformKind.WINDOW

    is SqlExpr.Case ->
        if (expr.referencedColumns().isEmpty()) TransformKind.CONSTANT else TransformKind.CASE_BRANCH

    is SqlExpr.Literal -> TransformKind.CONSTANT

    is SqlExpr.BinaryOp ->
        if (expr.referencedColumns().isEmpty()) TransformKind.CONSTANT else TransformKind.EXPRESSION

    is SqlExpr.UnaryOp ->
        if (expr.referencedColumns().isEmpty()) TransformKind.CONSTANT else TransformKind.EXPRESSION

    is SqlExpr.Star -> TransformKind.UNKNOWN

    is SqlExpr.Unknown -> TransformKind.UNKNOWN
}
