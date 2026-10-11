package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.util.deparser.StatementDeParser

/**
 * **多行排版**：把引擎的**规范化单行**渲染（[StatementDeParser]）按子句换行。
 *
 * 设计取舍（ADR-0014）：
 * - **内容单一来源**：SQL 文本一律来自引擎自己的 DeParser——本项目**不重写渲染器**，
 *   只在其产物上**插换行**。因此不存在「格式化器漏掉某个子句」的风险（一个字符都不丢），
 *   也不会与 `ozml convert` 的渲染产生第二套 SQL 理解。
 * - **换行按子句关键字**：`SELECT` / `FROM` / `WHERE` / `GROUP BY` / `ORDER BY` /
 *   `HAVING` / `LIMIT` / `OFFSET` / `FETCH` / `VALUES` / 集合运算 / `JOIN` 家族前换行。
 * - **上下文感知**：只在**子查询**里换行。函数参数里的 `FROM`（如 `EXTRACT(YEAR FROM d)`、
 *   `SUBSTRING(name FROM 1 FOR 3)`、`TRIM(BOTH FROM name)`）所在括号是**不透明**的，
 *   整段原样保留——这是纯文本切分会误伤、而结构化换行能避开的关键点。
 * - **引号感知**：字符串字面量 / 带引号标识符 / `[方括号]` 内的关键字不触发换行。
 *
 * 不追求「一行一个关键字」的完整 AST 缩进（那需要第二套渲染器，违背单一来源）；
 * 目标是**可读的子句级排版**，且保证 `parse → format → re-parse` 语义等价。
 */
internal object SqlFormatter {

    private const val INDENT = "  "

    /** 子句首关键字（自身即可触发换行）。 */
    private val CLAUSE_KEYWORDS: Set<String> = setOf(
        "SELECT", "FROM", "WHERE", "HAVING", "LIMIT", "OFFSET", "FETCH", "VALUES",
        "UNION", "INTERSECT", "EXCEPT", "MINUS", "JOIN",
    )

    /** 需要看第二个词的两字子句首（`GROUP BY` / `ORDER BY`）。 */
    private val TWO_WORD_CLAUSE: Set<String> = setOf("GROUP", "ORDER")

    /** JOIN 家族里可作连接前缀的首词（`LEFT JOIN` / `FULL OUTER JOIN` …）。 */
    private val JOIN_PREFIX: Set<String> = setOf("LEFT", "RIGHT", "INNER", "FULL", "CROSS")

    /** 子查询起始关键字：`(` 后紧跟它 → 该括号是子查询（允许内部换行）。 */
    private val SUBQUERY_START: Set<String> = setOf("SELECT", "WITH", "VALUES")

    /**
     * 把一段规范化 SQL（单行）重排为多行。
     *
     * 输入必须是**引擎渲染出的规范文本**（关键字已大写、空白已归一）；本函数只做换行。
     */
    fun format(canonical: String): String {
        val out = StringBuilder(canonical.length + canonical.length / 4)
        // 上下文栈：true = 子查询（内部可换行），false = 不透明括号（函数参数等，不换行）。
        // 栈底为顶层语句上下文（可换行）。
        val context = ArrayDeque<Boolean>().apply { addLast(true) }
        var depth = 0
        var i = 0
        val n = canonical.length

        while (i < n) {
            val c = canonical[i]
            when {
                c == '\'' || c == '"' || c == '`' -> i = copyQuoted(canonical, i, out)
                c == '[' -> i = copyBracket(canonical, i, out)
                c == '(' -> {
                    out.append(c)
                    i++
                    var j = i
                    while (j < n && canonical[j].isWhitespace()) j++
                    val next = peekWord(canonical, j)
                    context.addLast(next in SUBQUERY_START)
                    depth++
                }
                c == ')' -> {
                    val wasSubquery = context.removeLast()
                    depth--
                    // 子查询闭合括号另起一行（且外层也是可换行上下文时）
                    if (wasSubquery && context.last() && out.isNotEmpty() && out.last() != '\n') {
                        out.append('\n').append(indent(depth))
                    }
                    out.append(c)
                    i++
                }
                isWordChar(c) -> {
                    val start = i
                    while (i < n && isWordChar(canonical[i])) i++
                    val word = canonical.substring(start, i)
                    if (context.last() && isClauseStart(canonical, i, word)) {
                        if (out.isNotEmpty() && out.last() != '\n') out.append('\n').append(indent(depth))
                    }
                    out.append(word)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return trimTrailingSpaces(out)
    }

    /** 引擎规范化渲染：`StatementDeParser` 产出的单行文本（内容单一来源）。 */
    fun canonical(statement: Statement): String {
        val builder = StringBuilder()
        statement.accept(StatementDeParser(builder))
        return builder.toString().trim()
    }

    /**
     * [word]（其后可能跟空白）是否为一个**子句首**：
     * - 单关键字子句（`FROM` / `WHERE` …）；
     * - 两字子句（`GROUP BY` / `ORDER BY`）；
     * - JOIN 前缀（`LEFT JOIN` / `FULL OUTER` …）。
     */
    private fun isClauseStart(source: String, afterWord: Int, word: String): Boolean {
        val upper = word.uppercase()
        if (upper in CLAUSE_KEYWORDS) return true
        if (upper in TWO_WORD_CLAUSE) {
            val next = peekWord(source, skipWhitespace(source, afterWord))
            return next == "BY"
        }
        if (upper in JOIN_PREFIX) {
            val next = peekWord(source, skipWhitespace(source, afterWord))
            return next == "JOIN" || next == "OUTER"
        }
        return false
    }

    private fun skipWhitespace(source: String, from: Int): Int {
        var i = from
        while (i < source.length && source[i].isWhitespace()) i++
        return i
    }

    private fun peekWord(source: String, from: Int): String {
        var i = from
        val start = i
        while (i < source.length && isWordChar(source[i])) i++
        return source.substring(start, i).uppercase()
    }

    private fun copyQuoted(source: String, start: Int, out: StringBuilder): Int {
        val quote = source[start]
        out.append(quote)
        var i = start + 1
        val n = source.length
        while (i < n) {
            val c = source[i]
            out.append(c)
            if (c == quote) {
                // 成对引号是转义（'' / "" / ``），继续
                if (i + 1 < n && source[i + 1] == quote) {
                    out.append(source[i + 1])
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return n
    }

    private fun copyBracket(source: String, start: Int, out: StringBuilder): Int {
        out.append('[')
        var i = start + 1
        val n = source.length
        while (i < n && source[i] != ']') {
            out.append(source[i])
            i++
        }
        if (i < n) {
            out.append(']')
            return i + 1
        }
        return n
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun indent(depth: Int): String = INDENT.repeat(depth)

    private fun trimTrailingSpaces(text: StringBuilder): String =
        text.toString().lineSequence().joinToString("\n") { it.trimEnd() }
}
