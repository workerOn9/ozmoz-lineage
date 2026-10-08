package io.github.workeron9.ozmoz.lineage.engine.semantics

import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.Span

/**
 * 引擎无关的**表达式语义模型**——为血缘而生，**不是**「统一 AST」。
 *
 * 与 `ir.AstNode`（归一化树，为可视化 diff 而生）的区别：
 * - 归一化树保留引擎给出的**全部**节点形状，供并排对比；这里只保留**血缘需要**的表达式结构。
 * - 认不出的表达式一律落进 [Unknown] 并带原因，**绝不猜**（Never-wrong）。
 * - 每个节点都带 [raw]：源文本的原始切片，保证 Lossless。
 *
 * 它是 `engine-*` 适配器的**产出**、`lineage` 模块的**输入**。引擎私有类型
 * （JSqlParser `Expression`、Calcite `RexNode` …）不得出现在这里。
 */
public sealed interface SqlExpr {

    /** 该表达式在源文本中的原始切片（Lossless：不改大小写、不重排版、不丢引号）。 */
    public val raw: String

    /** 源位置；引擎给不出时为 null——不补位置。 */
    public val span: Span?

    /** 一个列引用（可能带表限定）。 */
    public data class Column(public val ref: ColumnRef) : SqlExpr {
        override val raw: String get() = ref.raw
        override val span: Span? get() = ref.span
    }

    /** 字面量（数字 / 字符串 / 日期 / NULL / 布尔…）。语义上无上游。 */
    public data class Literal(override val raw: String, override val span: Span?) : SqlExpr

    /** 函数调用（含聚合函数）。`COUNT(*)` 的 `*` 会作为 [Star] 出现在 [args] 里。 */
    public data class Function(
        public val name: String,
        public val args: List<SqlExpr>,
        override val raw: String,
        override val span: Span?,
    ) : SqlExpr

    /** 二元运算（算术 / 比较 / 逻辑 / 连接）。 */
    public data class BinaryOp(
        public val op: String,
        public val left: SqlExpr,
        public val right: SqlExpr,
        override val raw: String,
        override val span: Span?,
    ) : SqlExpr

    /** 一元运算（`NOT` / 负号 / 正号）。 */
    public data class UnaryOp(
        public val op: String,
        public val operand: SqlExpr,
        override val raw: String,
        override val span: Span?,
    ) : SqlExpr

    /** `CASE [operand] WHEN cond THEN result … [ELSE elseExpr] END`。 */
    public data class Case(
        public val operand: SqlExpr?,
        public val branches: List<Branch>,
        public val elseExpr: SqlExpr?,
        override val raw: String,
        override val span: Span?,
    ) : SqlExpr {
        public data class Branch(public val condition: SqlExpr, public val result: SqlExpr)
    }

    /** 窗口函数：`function OVER (PARTITION BY … ORDER BY …)`。 */
    public data class Window(
        public val function: SqlExpr,
        public val partitionBy: List<SqlExpr>,
        public val orderBy: List<SqlExpr>,
        override val raw: String,
        override val span: Span?,
    ) : SqlExpr

    /**
     * `*` 或 `t.*`：对来源的**整体引用**。
     *
     * 展开成具体列需要 schema（Never-wrong：没有 schema 就不展开，标为 unknown）。
     * [qualifier] 是表限定（`t.*` 的 `t`），`*` 时为 null。
     */
    public data class Star(
        public val qualifier: String?,
        override val raw: String,
        override val span: Span?,
    ) : SqlExpr

    /**
     * 无法归类的表达式：**不猜**，保留原文与原因。
     *
     * 出现它不表示失败——只表示这条表达式不参与（或暂不参与）血缘推导，
     * 且这一事实是**显式**的，不会在传播中悄悄丢失。
     */
    public data class Unknown(
        override val raw: String,
        public val reason: String,
        override val span: Span?,
    ) : SqlExpr {
        init {
            require(reason.isNotBlank()) { "SqlExpr.Unknown 必须给出非空的 reason" }
        }
    }
}

/**
 * 收集该表达式里出现的**所有列引用**（按出现顺序，可重复）。
 *
 * 供血缘做「这个输出列引用了哪些上游列」的第一步；[SqlExpr.Unknown] 与
 * [SqlExpr.Star] 不贡献列（前者未知，后者需 schema 才能展开）。
 */
public fun SqlExpr.referencedColumns(): List<ColumnRef> = when (this) {
    is SqlExpr.Column -> listOf(ref)
    is SqlExpr.Literal -> emptyList()
    is SqlExpr.Star -> emptyList()
    is SqlExpr.Unknown -> emptyList()
    is SqlExpr.Function -> args.flatMap { it.referencedColumns() }
    is SqlExpr.BinaryOp -> left.referencedColumns() + right.referencedColumns()
    is SqlExpr.UnaryOp -> operand.referencedColumns()
    is SqlExpr.Case -> buildList {
        operand?.let { addAll(it.referencedColumns()) }
        for (branch in branches) {
            addAll(branch.condition.referencedColumns())
            addAll(branch.result.referencedColumns())
        }
        elseExpr?.let { addAll(it.referencedColumns()) }
    }
    is SqlExpr.Window -> buildList {
        addAll(function.referencedColumns())
        for (e in partitionBy) addAll(e.referencedColumns())
        for (e in orderBy) addAll(e.referencedColumns())
    }
}
