package io.github.workeron9.ozmoz.lineage.conformance

import io.github.workeron9.ozmoz.lineage.engine.semantics.CteSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.JoinSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.NestedSource
import io.github.workeron9.ozmoz.lineage.engine.semantics.QuerySpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.ScopeSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.SetOperationQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SourceSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.SubquerySource
import io.github.workeron9.ozmoz.lineage.engine.semantics.TableSource
import io.github.workeron9.ozmoz.lineage.engine.semantics.referencedColumns

/**
 * 数一条语义模型里的**列引用总数**——`unknown 率` 的分母（计划 §8：「未解析的列引用 / 总列引用」）。
 *
 * 口径（都走 `engine-api` 的公开面，conformance 不碰任何引擎私有类型）：
 * - 每出现一个 `SqlExpr.Column` 计 1（输出列表 / WHERE / HAVING / GROUP BY / ORDER BY /
 *   JOIN 的 `ON` 与 `USING` / UPDATE 赋值 / 集合运算的 `ORDER BY`）；
 * - 每个 `*`（`SqlExpr.Star`）计 1——它是一次「整体引用」，展开失败正是 unknown 的来源；
 * - CTE 体 / 派生表 / 集合分支 / 括号来源**递归**计入；
 * - **不**计目标列清单（`INSERT INTO t (a, b)` 的 `a` / `b` 是定义不是引用）。
 *
 * 这是「引用视角」的计数，与 [io.github.workeron9.ozmoz.lineage.lineage.LineageBuilder] 产出的
 * 边数**不是一回事**（一条引用可能产出多条边）；报表里两者分开列，不混用。
 */
internal object SemanticRefCounter {

    fun count(statement: SemanticStatement): Int {
        var total = 0
        statement.query?.let { total += countQuery(it) }
        statement.scope?.let { total += countScope(it) }
        for (assignment in statement.assignments) total += countExpr(assignment.value)
        return total
    }

    private fun countQuery(query: QuerySpec): Int {
        var total = 0
        for (cte in query.ctes) total += countCte(cte)
        when (query) {
            is SelectQuery -> total += countScope(query.scope)
            is SetOperationQuery -> {
                for (branch in query.branches) total += countQuery(branch)
                for (expr in query.orderBy) total += countExpr(expr)
            }
        }
        return total
    }

    private fun countCte(cte: CteSpec): Int = countQuery(cte.query)

    private fun countScope(scope: ScopeSpec): Int {
        var total = 0
        for (output in scope.outputs) total += countExpr(output.expr)
        for (filter in scope.filters) total += countExpr(filter)
        for (expr in scope.groupBy) total += countExpr(expr)
        for (expr in scope.having) total += countExpr(expr)
        for (expr in scope.orderBy) total += countExpr(expr)
        for (source in scope.sources) total += countSource(source)
        for (join in scope.joins) total += countJoin(join)
        return total
    }

    private fun countJoin(join: JoinSpec): Int {
        var total = countSource(join.right)
        for (expr in join.on) total += countExpr(expr)
        total += join.using.size // USING (a, b)：每个名字是一次列引用
        return total
    }

    private fun countSource(source: SourceSpec): Int = when (source) {
        is TableSource -> 0 // 表名不是列引用；`t.*` 的 `*` 在表达式侧计
        is SubquerySource -> countQuery(source.query)
        is NestedSource -> countSource(source.from) + source.joins.sumOf { countJoin(it) }
    }

    /** 一个表达式里的列引用数：`referencedColumns()` + `*` 的个数（`*` 不在前者里）。 */
    private fun countExpr(expr: SqlExpr): Int = expr.referencedColumns().size + countStars(expr)

    private fun countStars(expr: SqlExpr): Int = when (expr) {
        is SqlExpr.Star -> 1
        is SqlExpr.Column, is SqlExpr.Literal, is SqlExpr.Unknown -> 0
        is SqlExpr.Function -> expr.args.sumOf { countStars(it) }
        is SqlExpr.BinaryOp -> countStars(expr.left) + countStars(expr.right)
        is SqlExpr.UnaryOp -> countStars(expr.operand)
        is SqlExpr.Case -> buildList {
            expr.operand?.let { add(countStars(it)) }
            for (branch in expr.branches) {
                add(countStars(branch.condition))
                add(countStars(branch.result))
            }
            expr.elseExpr?.let { add(countStars(it)) }
        }.sum()

        is SqlExpr.Window -> buildList {
            add(countStars(expr.function))
            for (e in expr.partitionBy) add(countStars(e))
            for (e in expr.orderBy) add(countStars(e))
        }.sum()
    }
}
