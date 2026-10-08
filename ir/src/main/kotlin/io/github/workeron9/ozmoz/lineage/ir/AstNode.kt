package io.github.workeron9.ozmoz.lineage.ir

import kotlinx.serialization.Serializable

/**
 * **归一化树**——为可视化对比而生，不是「统一 AST」。
 *
 * 四个引擎各自的语法树互不可比（JSqlParser `Statement`、Calcite `SqlNode`/`RelNode`、
 * ANTLR `ParseTree`、jOOQ `QueryPart`）。这里只保留四件事：
 *
 * - [type]：归一化的节点类型名（各引擎的 normalizer 负责映射，如 `select` / `join` / `column_ref`）。
 * - [text]：该节点对应的**原始文本**（Lossless：不重排版、不改大小写、不丢引号）。
 * - [span]：在源文本中的精确位置；引擎给不出时为 null。
 * - [children]：子节点，顺序即源码顺序。
 *
 * 用它做 diff 只需要 diff 两个 JSON 树，对比模块无需理解任何引擎。
 */
@Serializable
public data class AstNode(
    val type: String,
    val text: String,
    val span: Span? = null,
    val children: List<AstNode> = emptyList(),
) {
    init {
        require(type.isNotBlank()) { "AstNode.type 不能为空" }
    }

    /** 深度优先展平，便于遍历与统计。 */
    public fun flatten(): Sequence<AstNode> = sequence {
        yield(this@AstNode)
        for (child in children) {
            yieldAll(child.flatten())
        }
    }

    public companion object {
        /** 语法树中没有内容可挂时的占位节点，语义上等同于「此处为空」。 */
        @JvmStatic
        public fun empty(): AstNode = AstNode(type = "empty", text = "")
    }
}
