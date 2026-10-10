package io.github.workeron9.ozmoz.lineage.engine.calcite

import io.github.workeron9.ozmoz.lineage.ir.Position
import io.github.workeron9.ozmoz.lineage.ir.Span
import org.apache.calcite.runtime.CalciteContextException
import org.apache.calcite.sql.parser.SqlParserPos

/**
 * Calcite **行 / 列 → 偏移量** 的换算工具。
 *
 * `SqlParserPos` 只有行 / 列（1 起），没有字符偏移量；`ir.Span` 要偏移量。
 * 这里按源文本预建行起点索引，把 (line, col) 换算回偏移量供 `text` 切片（Lossless）。
 *
 * **一个实测确认的 Calcite 怪癖**：`SqlOrderBy` 节点自身的
 * `getParserPosition()` 是 **fetch / offset 的位置**，不是 ORDER 关键字
 * （2026-10-10 probe：`SELECT a FROM t ORDER BY a LIMIT 10` → pos=(1,34)=`10`）。
 * 复合节点要用部件位置的并集（[spanOf] 多位置版）。
 */
internal object CalcitePositions {

    /** 每一行（1 起）的起始偏移量。 */
    private fun lineStarts(source: String): IntArray {
        val starts = ArrayList<Int>()
        var offset = 0
        starts.add(0)
        for (ch in source) {
            if (ch == '\n') starts.add(offset + 1)
            offset++
        }
        return starts.toIntArray()
    }

    /** (line, col) → 偏移量；越界 / 非法行（如 `SqlParserPos.ZERO` 的 0 行）返回 null。 */
    public fun offsetOf(starts: IntArray, line: Int, column: Int): Int? {
        if (line < 1 || line > starts.size) return null
        val lineStart = starts[line - 1]
        val offset = lineStart + (column - 1)
        // 下一行起点即本行长度上限；末行没有下一行，允许到数组长度由调用方夹紧。
        val lineEnd = if (line < starts.size) starts[line] else Int.MAX_VALUE
        return if (offset >= lineStart && offset <= lineEnd) offset else null
    }

    /**
     * `SqlParserPos` → [Span]。位置为 0（未记录）或越界时返回 null——
     * span 给不出就空着（[Position]/[Span] 的偏移量必须真实）。
     *
     * 实测（Calcite 1.42.0）：end line / col 指向**最后一个字符**（闭区间），
     * 而 `ir.Span` 是半开区间——换算时右边界 +1。标识符切片由此天然带上引号
     *（`` `store` `` / `[a]`），Lossless。
     */
    public fun spanOf(source: String, pos: SqlParserPos?): Span? {
        if (pos == null) return null
        if (pos.lineNum <= 0 || pos.endLineNum <= 0) return null
        val starts = lineStarts(source)
        val start = offsetOf(starts, pos.lineNum, pos.columnNum) ?: return null
        val endExcl = (offsetOf(starts, pos.endLineNum, pos.endColumnNum) ?: return null) + 1
        val end = endExcl.coerceAtMost(source.length)
        if (end < start) return null
        return Span(
            start = Position(offset = start, line = pos.lineNum, column = pos.columnNum),
            end = Position(offset = end, line = pos.endLineNum, column = pos.endColumnNum + 1),
        )
    }

    /** 源文本切片（方案同 jsqlparser 的 `textOf`）：拿不到 / 退化为空时返回 null。 */
    public fun textOf(source: String, pos: SqlParserPos?): String? {
        val span = spanOf(source, pos) ?: return null
        val text = source.substring(span.start.offset, span.end.offset).trim()
        return text.takeIf { it.isNotEmpty() }
    }

    /** 源文本切片（同一口径）：span 无效时返回 null。 */
    public fun textOf(source: String, span: Span?): String? {
        if (span == null) return null
        if (span.start.offset < 0 || span.end.offset > source.length) return null
        val text = source.substring(span.start.offset, span.end.offset).trim()
        return text.takeIf { it.isNotEmpty() }
    }

    /**
     * 多个 `SqlParserPos` 的**并集 span**（SqlOrderBy 这类复合节点自身 pos
     * 不可信，用部件位置求并）。无有效位置时返回 null。
     */
    public fun spanOf(source: String, positions: List<SqlParserPos?>): Span? {
        val valid = positions.filterNotNull().filter { it.lineNum > 0 }
        if (valid.isEmpty()) return null
        return spanOf(source, valid.reduce { a, b -> a.plus(b) })
    }

    /**
     * `CalciteContextException` 的位置（**解析与校验共用**）：`getPosLine` 等
     * 在异常本体上（`SqlParseException` 则直接 `getPos()`）。
     */
    public fun posOf(e: CalciteContextException): SqlParserPos? {
        val line = e.posLine
        val column = e.posColumn
        if (line <= 0 || column <= 0) return null
        return SqlParserPos(line, column, e.endPosLine, e.endPosColumn)
    }
}
