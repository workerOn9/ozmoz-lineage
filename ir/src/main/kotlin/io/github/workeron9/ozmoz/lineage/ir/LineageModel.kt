package io.github.workeron9.ozmoz.lineage.ir

/**
 * 边的类型：一条列血缘边**为什么**存在。
 *
 * 分这三类（外加来源与分组/排序）是为了回答不同的问题：
 * - [OUTPUT]：输出列的值从哪来（值血缘）。
 * - [PREDICATE]：过滤条件引用了哪些列（只影响结果集，不影响输出值）。
 * - [JOIN_KEY]：连接键等价传递（`a.id = b.id` 让两侧的行集合相互约束）。
 * - [GROUP_BY] / [ORDER_BY]：分组与排序引用。
 * - [SOURCE]：表/子查询作为数据来源被整体引用（表级血缘的边）。
 */
public enum class EdgeKind {
    OUTPUT,
    PREDICATE,
    JOIN_KEY,
    GROUP_BY,
    ORDER_BY,
    SOURCE,
}

/**
 * 变换的种类：把「这一列是怎么算出来的」从布尔标记升级为有语义的枚举。
 *
 * 这是让血缘**有用**而不只是**有**的关键——下游可以据此判断「改上游会不会改变输出」
 * 「这条边是否携带条件」「样本集合是怎么被切出来的」。
 */
public enum class TransformKind {
    /** `a -> a`：直传。 */
    DIRECT,

    /** `a + b -> c`：表达式派生。 */
    EXPRESSION,

    /** `SUM(a) -> c`：聚合。 */
    AGGREGATE,

    /** `ROW_NUMBER() OVER (...)`：窗口函数。 */
    WINDOW,

    /** `CASE WHEN ... THEN ...`：条件分支（多个上游 + 条件）。 */
    CASE_BRANCH,

    /** 字面量 / `now()`：语义上无上游。 */
    CONSTANT,

    /** `a.id = b.id`：连接键传播。 */
    JOIN_KEY,

    /** `WHERE col > 0`：只影响结果集，不改变输出值。 */
    FILTER_PREDICATE,

    /** 显式放弃推断，配合 [Resolved.Unknown]。 */
    UNKNOWN,
}

/**
 * 一条列级血缘边。
 *
 * [confidence] 只有两种取值：**1.0**（可从权威来源确知）或通过 [Resolved.Unknown]
 * 表达的显式未知。**不存在 0.7 这种中间猜测**——这正是 Never-wrong 原则。
 */
public data class LineageEdge(
    val id: String,
    val fromColumn: ColumnRef,
    val toColumn: ColumnRef,
    val kind: EdgeKind,
    val transform: TransformKind,
    val expression: String? = null,
    val span: Span? = null,
) {
    init {
        require(id.isNotBlank()) { "LineageEdge.id 不能为空" }
    }
}

/** 作用域树里的一个作用域：一个 SELECT（或 CTE / UNION 分支）的独立命名空间。 */
public data class ScopeNode(
    val id: String,
    val kind: ScopeKind,
    val sources: List<TableRef> = emptyList(),
    val outputs: List<ColumnRef> = emptyList(),
    val parentId: String? = null,
    val span: Span? = null,
) {
    init {
        require(id.isNotBlank()) { "ScopeNode.id 不能为空" }
    }
}

/** 作用域的种类。 */
public enum class ScopeKind {
    SELECT,
    CTE,
    SUBQUERY,
    UNION_BRANCH,
    INSERT,
    UPDATE,
    DELETE,
    MERGE,
    CREATE_VIEW,
    CREATE_TABLE_AS,
    ROOT,
}

/** 血缘图里的一个列节点。 */
public data class ColumnNode(
    val column: ColumnRef,
    val scopeId: String? = null,
    val isOutput: Boolean = false,
)

/** 一次分析运行的元信息，用于溯源与复现。 */
public data class Meta(
    val engineId: String? = null,
    val dialect: String? = null,
    val schemaSnapshotId: String? = null,
    val toolVersion: String? = null,
    val elapsedMillis: Long? = null,
)

/**
 * 引擎无关的血缘模型——对外稳定契约。前端、导出、持久化都消费它。
 *
 * [unknowns] 是显式的未知集合，**公开可统计**（对应质量指标里的 unknown 率）。
 * 它不会被悄悄丢弃，也不会被猜测值填补。
 */
public data class LineageModel(
    val meta: Meta = Meta(),
    val scopes: List<ScopeNode> = emptyList(),
    val columns: List<ColumnNode> = emptyList(),
    val edges: List<LineageEdge> = emptyList(),
    val unknowns: List<Resolved.Unknown> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    init {
        val scopeIds = scopes.map { it.id }
        require(scopeIds.size == scopeIds.toSet().size) { "ScopeNode.id 必须唯一" }

        val edgeIds = edges.map { it.id }
        require(edgeIds.size == edgeIds.toSet().size) { "LineageEdge.id 必须唯一" }

        val columnIds = columns.map { it.column.id }
        require(columnIds.size == columnIds.toSet().size) { "ColumnNode.column.id 必须唯一" }
    }

    /** 图中出现的所有边类型计数，便于快速核对。 */
    public fun edgeCountByKind(): Map<EdgeKind, Int> = edges.groupingBy { it.kind }.eachCount()
}
