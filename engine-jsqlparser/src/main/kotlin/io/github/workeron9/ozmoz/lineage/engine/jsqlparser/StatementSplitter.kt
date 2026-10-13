package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

/**
 * 把一段 SQL 脚本按**顶层分号**切成语句片段——只给「整段解析失败」后的逐语句
 * 重试（[JSqlParserEngine.analyzeScript]）用。
 *
 * 为什么要自己的切分：JSqlParser 的 `parseStatements` 遇到任意一条坏语句就整段抛异常，
 * 且没有「一条语句在源里到哪结束」的公开 API。切分因此只能**在不解析的前提下**做，
 * 判定规则是「分号在引号 / 注释之外才算语句边界」——引号与注释的扫描交给 [SqlScanner]。
 *
 * **保守优先**：切分只影响「本来就要失败的脚本」能救回多少；切错的下场是该片段的
 * 解析诊断多一条（Never-wrong：不猜对错，只如实报）。纯空白 / 纯注释片段**不产出片段**，
 * 免得给尾随注释凭空造一条诊断。
 */
internal object StatementSplitter {

    /** 一个语句片段在源文本里的半开区间 `[start, endExclusive)`（**不含**分隔分号）。 */
    data class Chunk(val start: Int, val endExclusive: Int)

    /** 按顶层分号切分；返回的片段均非纯空白 / 纯注释，顺序与源文本一致。 */
    fun split(source: String): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        val n = source.length
        var start = 0
        var i = 0
        while (i < n) {
            val c = source[i]
            when {
                c == '\'' || c == '"' || c == '`' -> i = SqlScanner.skipQuoted(source, i, c)
                c == '[' -> i = SqlScanner.skipBracket(source, i)
                c == '$' -> i = SqlScanner.skipDollarQuoted(source, i) ?: (i + 1)
                c == '-' && i + 1 < n && source[i + 1] == '-' -> i = SqlScanner.skipLineComment(source, i)
                c == '/' && i + 1 < n && source[i + 1] == '*' -> i = SqlScanner.skipBlockComment(source, i)
                c == ';' -> {
                    add(chunks, source, start, i)
                    start = i + 1
                    i++
                }

                else -> i++
            }
        }
        add(chunks, source, start, n)
        return chunks
    }

    private fun add(chunks: MutableList<Chunk>, source: String, start: Int, endExclusive: Int) {
        if (endExclusive <= start) return
        if (isBlankIgnoringComments(source, start, endExclusive)) return
        chunks += Chunk(start, endExclusive)
    }

    /** `[start, endExclusive)` 是否只含空白与注释（引号内的字符算内容，不算空白）。 */
    private fun isBlankIgnoringComments(source: String, start: Int, endExclusive: Int): Boolean {
        var i = start
        while (i < endExclusive) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && i + 1 < endExclusive && source[i + 1] == '-' ->
                    i = SqlScanner.skipLineComment(source, i)

                c == '/' && i + 1 < endExclusive && source[i + 1] == '*' ->
                    i = SqlScanner.skipBlockComment(source, i)

                else -> return false
            }
        }
        return true
    }
}
