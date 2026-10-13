package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import io.github.workeron9.ozmoz.lineage.ir.Span

/**
 * SQL 源文本里的**注释探测**——用于格式化时如实报告「注释不会被保留」。
 *
 * 为什么自己扫：JSqlParser 的词法器把注释当 `SPECIAL_TOKEN` **直接跳过**
 * （实测 2026-10-11：`CCJSqlParserTokenManager` 对行注释与块注释都不产出任何 token，
 * `Statement` 上也没有注释 getter）。DeParser 因此**必然丢注释**——探测只能回原文。
 *
 * 扫描**尊重引号**：单引号字符串（含成对引号 / 反斜杠转义）、双引号 / 反引号标识符、
 * 方括号标识符、美元引号字面量内部的行注释 / 块注释起始符不算注释。块注释按 JSqlParser
 * 口径支持**嵌套**。引号 / 注释的扫描原语由 [SqlScanner] 提供（与 [StatementSplitter] 共用）。
 */
internal object SqlComments {

    /** 源文本里**第一条**注释的位置；没有注释返回 null。**不猜**。 */
    fun firstCommentSpan(source: String): Span? {
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                c == '\'' || c == '"' || c == '`' -> i = SqlScanner.skipQuoted(source, i, c)
                c == '[' -> i = SqlScanner.skipBracket(source, i)
                c == '$' -> i = SqlScanner.skipDollarQuoted(source, i) ?: (i + 1)
                c == '-' && i + 1 < n && source[i + 1] == '-' -> {
                    val end = SqlScanner.skipLineComment(source, i)
                    return Span.of(source, i, end)
                }

                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    val end = SqlScanner.skipBlockComment(source, i)
                    return Span.of(source, i, end)
                }

                else -> i++
            }
        }
        return null
    }

    /** 是否存在注释。 */
    fun hasComment(source: String): Boolean = firstCommentSpan(source) != null
}
