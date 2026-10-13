package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

/**
 * SQL 源文本的**字符级扫描原语**——供注释探测（[SqlComments]）与语句切分
 * （[StatementSplitter]）共用。
 *
 * 为什么自己扫：JSqlParser 的词法器把注释当 `SPECIAL_TOKEN` **直接跳过**（实测
 * 2026-10-11），且没有「一条语句在源里到哪结束」的公开 API。凡是要在**不解析**的前提下
 * 尊重引号 / 注释地遍历文本，就得有自己的扫描器。
 *
 * 所有 `skipXxx` 都返回**下一个待扫下标**：闭合符之后（未闭合则到文本末尾）。
 * 不抛异常、不猜——扫不动就停到末尾，交给上层判空 / 报诊断。
 */
internal object SqlScanner {

    /**
     * 跳过一段引号包裹的文本，返回闭合引号之后的下标（未闭合则到末尾）。
     *
     * - 成对引号是转义（`''` / `""` / ` `` `）；
     * - **单引号**额外认反斜杠转义（MySQL / Hive / Spark 的 `'it\'s'`）；双引号 / 反引号
     *   是标识符，按标准口径**不**认反斜杠（PostgreSQL 的 `"a\"` 是标识符 `a\`）。
     */
    fun skipQuoted(source: String, start: Int, quote: Char): Int {
        var i = start + 1
        val n = source.length
        while (i < n) {
            val c = source[i]
            if (c == '\\' && quote == '\'') {
                i += 2
                continue
            }
            if (c == quote) {
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

    /** 跳过 `[ ... ]` 标识符，返回 `]` 之后的下标（`]]` 是转义；未闭合则到末尾）。 */
    fun skipBracket(source: String, start: Int): Int {
        var i = start + 1
        val n = source.length
        while (i < n) {
            if (source[i] == ']') {
                if (i + 1 < n && source[i + 1] == ']') {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return n
    }

    /** 跳过行注释 `-- …`，返回到行尾（不消耗换行；未闭合则到末尾）。 */
    fun skipLineComment(source: String, start: Int): Int {
        var end = start + 2
        val n = source.length
        while (end < n && source[end] != '\n') end++
        return end
    }

    /** 跳过块注释（嵌套感知，与 JSqlParser 口径一致），返回闭合符之后的下标（未闭合则到末尾）。 */
    fun skipBlockComment(source: String, start: Int): Int {
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

    /**
     * 若 `source[start]` 是一个美元引号字面量（PostgreSQL 的 `$$…$$` / `$tag$…$tag$`）的开头，
     * 返回其闭合标签之后的下标；否则返回 null（**不是**美元引号）。
     *
     * 标签按 PostgreSQL 口径：`$` + 可选标签（标识符字符）+ `$`；`$1` 这类位置参数因此
     * 不误判（后面没有第二个 `$`）。未闭合时到文本末尾。
     */
    fun skipDollarQuoted(source: String, start: Int): Int? {
        var i = start + 1
        val n = source.length
        while (i < n && (source[i].isLetterOrDigit() || source[i] == '_')) i++
        if (i >= n || source[i] != '$') return null
        val tag = source.substring(start, i + 1) // 含两端 `$`
        val close = source.indexOf(tag, i + 1)
        return if (close < 0) n else close + tag.length
    }
}
