package io.github.workeron9.ozmoz.lineage.engine.semantics

import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind
import io.github.workeron9.ozmoz.lineage.ir.Span
import io.github.workeron9.ozmoz.lineage.ir.TableRef

/**
 * 语句种类——`lineage` 的**分派键**。
 *
 * 只列出**已实现语义提取**的种类。提取不了的语句不在这里硬塞一个枚举值，
 * 而是让引擎返回 `Resolved.Unknown(reason)`（Never-wrong）。
 */
public enum class StatementKind {
    SELECT,
    INSERT,
    CREATE_VIEW,
    CREATE_TABLE_AS,
    UPDATE,
    DELETE,
}

/**
 * 引擎无关的**语句语义模型**——`lineage` 模块的输入。
 *
 * 它回答的是「这条语句把哪些列从哪些来源算到了哪些输出」，因此比归一化树
 * （`ir.AstNode`，为可视化 diff 而生）**更结构化、更少**：只保留血缘需要的部分，
 * 认不出的一律落进 [SqlExpr.Unknown] 或 [diagnostics]，**绝不猜**。
 *
 * 引擎私有类型（JSqlParser `Statement`、Calcite `SqlNode` …）不得出现在这里。
 *
 * [query] 与 [scope] 恰好有一个非空（[diagnostics] 非空时可都为空，表示该语句
 * 暂时无法建出作用域，原因写在诊断里）。
 */
public data class SemanticStatement(
    val kind: StatementKind,
    /** 写入目标（INSERT / CTAS / CREATE VIEW / UPDATE / DELETE）；纯 SELECT 为 null。 */
    val target: TableRef? = null,
    /** 目标列清单（`INSERT INTO t (a, b)` / `CREATE VIEW v (a, b)`）；无则为空。 */
    val targetColumns: List<ColumnRef> = emptyList(),
    /** `UPDATE t SET a = expr` 的赋值清单。 */
    val assignments: List<Assignment> = emptyList(),
    /** 产出数据的查询（SELECT 系语句）。 */
    val query: QuerySpec? = null,
    /** 无 SELECT 产出时（UPDATE / DELETE）的操作作用域。 */
    val scope: ScopeSpec? = null,
    /** 语义提取过程中的提示——**非致命**，不影响已提取到的部分。 */
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    init {
        require(query != null || scope != null || diagnostics.isNotEmpty()) {
            "SemanticStatement 至少要给出 query / scope / diagnostics 之一"
        }
    }
}

/** `UPDATE t SET a = expr` 里的一次赋值。 */
public data class Assignment(
    val target: ColumnRef,
    val value: SqlExpr,
)

/**
 * 一个查询——要么是一个 SELECT（[SelectQuery]），要么是一组集合运算（[SetOperationQuery]）。
 *
 * [ctes] 是查询级 `WITH` 定义（SQL 语法里 `WITH` 挂在查询上，不挂在单个 SELECT 上）。
 */
public sealed interface QuerySpec {
    val ctes: List<CteSpec>
    val raw: String
    val span: Span?
}

/** 单个 SELECT。 */
public data class SelectQuery(
    val scope: ScopeSpec,
    override val ctes: List<CteSpec> = emptyList(),
    override val raw: String,
    override val span: Span? = null,
) : QuerySpec

/** 集合运算：`UNION` / `UNION ALL` / `INTERSECT` / `EXCEPT`。 */
public data class SetOperationQuery(
    /** 运算符原文（如 `UNION ALL`）。 */
    val op: String,
    val branches: List<QuerySpec>,
    /** 集合运算结果上的 `ORDER BY`。 */
    val orderBy: List<SqlExpr> = emptyList(),
    override val ctes: List<CteSpec> = emptyList(),
    override val raw: String,
    override val span: Span? = null,
) : QuerySpec

/** 一个 `WITH` 定义（CTE）。 */
public data class CteSpec(
    val name: String,
    /** 显式列名清单（`WITH c(a, b) AS …`）；无则为空。 */
    val columns: List<String> = emptyList(),
    val query: QuerySpec,
    val span: Span? = null,
) {
    init {
        require(name.isNotBlank()) { "CteSpec.name 不能为空" }
    }
}

/**
 * 一个**作用域**：一个 SELECT 的独立命名空间。
 *
 * 作用域树就是由这些节点通过 `lineage` 模块的嵌套关系（子查询 / CTE / UNION 分支）拼出来的。
 * [sources] 与 [joins] 一起构成 FROM 子句；[outputs] 是 SELECT 列表。
 */
public data class ScopeSpec(
    val kind: ScopeKind,
    val sources: List<SourceSpec> = emptyList(),
    val joins: List<JoinSpec> = emptyList(),
    /** `WHERE` 条件。 */
    val filters: List<SqlExpr> = emptyList(),
    val groupBy: List<SqlExpr> = emptyList(),
    val having: List<SqlExpr> = emptyList(),
    val orderBy: List<SqlExpr> = emptyList(),
    /** SELECT 列表。 */
    val outputs: List<OutputItem> = emptyList(),
    val span: Span? = null,
)

/** FROM 子句里的一个来源。 */
public sealed interface SourceSpec {
    val alias: String?
    val raw: String
    val span: Span?
}

/** 物理表或 CTE 引用（CTE 引用也是一张「表名」，由 `lineage` 做名字解析时区分）。 */
public data class TableSource(val table: TableRef) : SourceSpec {
    override val alias: String? get() = table.alias
    override val raw: String get() = table.raw
    override val span: Span? get() = table.span
}

/** 派生表（`FROM (SELECT …) AS sub`）。 */
public data class SubquerySource(
    val query: QuerySpec,
    override val alias: String?,
    override val raw: String,
    override val span: Span? = null,
) : SourceSpec

/** 带括号的 FROM 项（`FROM (t1 JOIN t2 ON …)`）。 */
public data class NestedSource(
    val from: SourceSpec,
    val joins: List<JoinSpec> = emptyList(),
    override val alias: String?,
    override val raw: String,
    override val span: Span? = null,
) : SourceSpec

/** 一次 JOIN。 */
public data class JoinSpec(
    /** JOIN 类型原文（`INNER` / `LEFT` / `CROSS` …）。 */
    val type: String,
    val right: SourceSpec,
    /** `ON` 条件。 */
    val on: List<SqlExpr> = emptyList(),
    /** `USING (a, b)` 的列名。 */
    val using: List<String> = emptyList(),
    val span: Span? = null,
)

/** SELECT 列表里的一项。 */
public data class OutputItem(
    val expr: SqlExpr,
    val alias: String? = null,
    val span: Span? = null,
) {
    /** 输出的**显式**列名：别名优先；否则直传列用其列名；推不出则为 null（不猜）。 */
    public val explicitName: String?
        get() = alias ?: (expr as? SqlExpr.Column)?.ref?.name
}
