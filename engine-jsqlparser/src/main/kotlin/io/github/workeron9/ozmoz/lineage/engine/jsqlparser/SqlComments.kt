package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import io.github.workeron9.ozmoz.lineage.ir.Span

/**
 * SQL 源文本里的**注释探测**——用于格式化时如实报告「注释不会被保留」。
 *
 * 为什么自己扫：JSqlParser 的词法器把注释当 `SPECIAL_TOKEN` **直接跳过**
 * （实测 2026-10-11：`CCJSqlParserTokenManager` 对行注释与块注释都不产出任何 token，
 * `Statement` 上也没有注释 getter）。DeParser 因此**必然丢注释**——探测只能回原文。
 *
 * 扫描**尊重引号**：单引号字符串（含成对引号转义）、双引号 / 反引号标识符、方括号标识符
 * 内部的行注释 / 块注释起始符不算注释。块注释按 JSqlParser 口径支持**嵌套**。
 */
internal object SqlComments {

    /** 源文本里**第一条**注释的位置；没有注释返回 null。**不猜**。 */
    fun firstCommentSpan(source: String): Span? {
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                c == '\'' || c == '"' || c == '`' -> i = skipQuoted(source, i, c)
                c == '[' -> i = skipBracket(source, i)
                c == '-' && i + 1 < n && source[i + 1] == '-' -> {
                    var end = i + 2
                    while (end < n && source[end] != '\n') end++
                    return Span.of(source, i, end)
                }
                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    val end = skipBlockComment(source, i)
                    return Span.of(source, i, end)
                }
                else -> i++
            }
        }
        return null
    }

    /** 是否存在注释。 */
    fun hasComment(source: String): Boolean = firstCommentSpan(source) != null

    /** 跳过一段引号包裹的文本，返回闭合引号之后的下标（未闭合则到末尾）。 */
    private fun skipQuoted(source: String, start: Int, quote: Char): Int {
        var i = start + 1
        val n = source.length
        while (i < n) {
            if (source[i] == quote) {
                // 成对引号是转义（'' / "" / ``），继续
                if (i + 1 < n && source[i + 1] == quote) {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return n
    }

    /** 跳过 `[ ... ]` 标识符，返回 `]` 之后的下标（未闭合则到末尾）。 */
    private fun skipBracket(source: String, start: Int): Int {
        var i = start + 1
        val n = source.length
        while (i < n && source[i] != ']') i++
        return if (i < n) i + 1 else n
    }

    /** 跳过块注释（嵌套感知），返回闭合块注释符之后的下标（未闭合则到末尾）。 */
    private fun skipBlockComment(source: String, start: Int): Int {
        var i = start + 2
        var depth = 1
        val n = source.length
        while (i < n && depth > 0) {
            when {
                source[i] == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    depth++
                    i += 2
                }
                source[i] == '*' && i + 1 < n && source[i + 1] == '/' -> {
                    depth--
                    i += 2
                }
                else -> i++
            }
        }
        return i
    }
}
