package io.github.workeron9.ozmoz.lineage.desktop.model

/**
 * 桌面端的**呈现状态**——`commonMain` 的边界。
 *
 * 这里刻意不出现任何引擎侧类型（`LineageModel` / `AstNode` / `Span` / `Diagnostic`）：
 * 引擎模块都是 JVM 模块，`commonMain` 看不见它们；把引擎访问收在
 * [io.github.workeron9.ozmoz.lineage.desktop.EngineGateway] 后面，jvm 侧负责映射
 * （ADR-0022 决定 2 的分层门），将来换一个实现走 HTTP 就能上平板 / 浏览器，UI 不重写。
 *
 * 全套类型**不可变**：UI 只从 [Analysis] 读，不往回写。
 */

/** 一条边**为什么**存在；视觉上由**颜色**编码（一种 kind 一种颜色）。 */
public enum class FlowKind {
    OUTPUT,
    PREDICATE,
    JOIN_KEY,
    GROUP_BY,
    ORDER_BY,
    SOURCE,
}

/** 这一列**怎么**算出来的；视觉上由**线型**编码（不占颜色通道，避免 6×12 种不可辨颜色）。 */
public enum class FlowTransform {
    DIRECT,
    EXPRESSION,
    AGGREGATE,
    WINDOW,
    CASE_BRANCH,
    CONSTANT,
    JOIN_KEY,
    FILTER_PREDICATE,
    GROUPING,
    ORDERING,
    SOURCE,
    UNKNOWN,
}

/** 诊断的严重级别。 */
public enum class SeverityView {
    INFO,
    WARNING,
    ERROR,
}

/**
 * 图里的一个列节点。
 *
 * [line] / [isOutput] 供 UI 定位与着色；[type] / [nullable] 只有权威 schema 收录了
 * 该列时才有值，其余为 null——**不从 SQL 猜类型**。
 */
public data class ColumnView(
    val id: String,
    val qualifiedName: String,
    val isOutput: Boolean,
    val type: String? = null,
    val nullable: Boolean? = null,
    val line: Int? = null,
)

/** 一条列级血缘边。[from] / [to] 是 [ColumnView.id]。 */
public data class FlowView(
    val from: String,
    val to: String,
    val kind: FlowKind,
    val transform: FlowTransform,
    val expression: String? = null,
    val line: Int? = null,
)

/** 归一化树的一个节点。[id] 是树内路径（扁平化与展开态用），[text] 是原始文本。 */
public data class TreeNode(
    val id: String,
    val type: String,
    val text: String,
    val line: Int? = null,
    val children: List<TreeNode> = emptyList(),
)

/** 一条诊断。[line] / [column] 都是 1 起始，供「跳到源」。 */
public data class IssueView(
    val severity: SeverityView,
    val code: String,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
)

/**
 * 一次分析的**全部产出**，UI 只读它。
 *
 * [note] 非空表示「这次没建出模型」（整段解析失败 / 一条可建模语句都没有）——
 * 界面上必须显式说出来，不能让空图冒充「没有问题」。
 */
public data class Analysis(
    val engineId: String,
    /** 本次分析用的连接（schema 来源）；null = 只用 SQL 文本里顺手的 DDL。 */
    val schemaId: String? = null,
    val columns: List<ColumnView> = emptyList(),
    val flows: List<FlowView> = emptyList(),
    val tree: List<TreeNode> = emptyList(),
    val issues: List<IssueView> = emptyList(),
    val unknowns: List<String> = emptyList(),
    val elapsedMillis: Long = 0,
    val note: String? = null,
)
