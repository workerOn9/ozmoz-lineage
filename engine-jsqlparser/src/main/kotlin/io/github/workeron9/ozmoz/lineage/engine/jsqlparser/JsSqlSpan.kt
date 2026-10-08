package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import io.github.workeron9.ozmoz.lineage.ir.Span
import net.sf.jsqlparser.parser.ASTNodeAccess
import net.sf.jsqlparser.parser.Node
import net.sf.jsqlparser.parser.ParseException
import net.sf.jsqlparser.parser.Token

/**
 * 从 JSqlParser 的节点上取**真实源位置**（Lossless）。
 *
 * 实测（2026-10-08，JSqlParser 5.4）：`Node.jjtGetFirstToken()/jjtGetLastToken()` 上的
 * `Token.absoluteBegin/absoluteEnd` 是 **1 起始、末尾开区间**，与源文本下标相差 1；
 * 转成 `ir` 的半开区间 `[start, end)` 需各减 1。
 *
 * 取不到位置时返回 null——**不猜**（Never-wrong）。
 */
internal object JsSqlSpan {

    /** 由 0 起始的半开区间构造 [Span]，行列信息交给 [Span.of] 现算。 */
    fun of(source: String, beginOffset: Int, endOffset: Int): Span? {
        if (beginOffset < 0 || endOffset < beginOffset || endOffset > source.length) return null
        return runCatching { Span.of(source, beginOffset, endOffset) }.getOrNull()
    }

    /** 从任意可能带 AST 节点的对象上取 span；取不到返回 null。 */
    fun ofNode(source: String, node: Any?): Span? {
        val astNode = (node as? ASTNodeAccess)?.astNode ?: return null
        return ofTokens(source, astNode)
    }

    private fun ofTokens(source: String, node: Node): Span? {
        val first: Token = node.jjtGetFirstToken() ?: return null
        val last: Token = node.jjtGetLastToken() ?: return null
        // absoluteBegin/absoluteEnd 为 1 起始、末尾开区间
        return of(source, first.absoluteBegin - 1, last.absoluteEnd - 1)
    }

    /** 从解析异常的 token 上取 span，用于把失败位置报给调用方。 */
    fun ofParseException(source: String, e: ParseException): Span? {
        val token = e.currentToken ?: return null
        // 失败 token 的 image 可能为 null（EOF 等），absolute* 仍可用
        val begin = token.absoluteBegin
        val end = token.absoluteEnd
        if (begin <= 0) return null
        return of(source, begin - 1, end - 1)
    }

    /** 该对象的原始文本切片；拿不到 span 时回落到引擎自身的渲染。 */
    fun textOf(source: String, node: Any): String {
        val span = ofNode(source, node)
        return if (span != null) {
            source.substring(span.start.offset, span.end.offset)
        } else {
            node.toString()
        }
    }
}
