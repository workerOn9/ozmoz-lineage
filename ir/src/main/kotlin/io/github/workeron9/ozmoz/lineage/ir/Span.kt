package io.github.workeron9.ozmoz.lineage.ir

import kotlinx.serialization.Serializable

/**
 * 源文本中的一个位置。
 *
 * - [offset] 是 0 起始的 UTF-16 码元偏移，与 Java `String` 的下标一致。
 * - [line] / [column] 都是 1 起始。
 *
 * 这些坐标用于把诊断、边、AST 节点映射回原始 SQL（Lossless 原则）。
 */
@Serializable
public data class Position(
    val offset: Int,
    val line: Int,
    val column: Int,
) {
    init {
        require(offset >= 0) { "offset 必须 >= 0，实际 $offset" }
        require(line >= 1) { "line 必须 >= 1，实际 $line" }
        require(column >= 1) { "column 必须 >= 1，实际 $column" }
    }
}

/**
 * 半开区间 `[start, end)`，指向源文本的一段。
 *
 * 为 null 表示**位置未知**：调用方不得把 null 当作「从 0 开始」，也不得凭空补一个位置。
 */
@Serializable
public data class Span(
    val start: Position,
    val end: Position,
) {
    init {
        require(end.offset >= start.offset) {
            "end.offset (${end.offset}) 必须 >= start.offset (${start.offset})"
        }
    }

    /** 该区间覆盖的码元数。 */
    public val length: Int get() = end.offset - start.offset

    public companion object {
        /**
         * 由起止偏移构造，行列信息由 [source] 现算。
         *
         * [source] 是整段 SQL 文本；[startOffset] / [endOffset] 为 0 起始、半开区间。
         */
        @JvmStatic
        public fun of(source: String, startOffset: Int, endOffset: Int): Span {
            require(startOffset in 0..source.length) { "startOffset 越界：$startOffset" }
            require(endOffset in startOffset..source.length) { "endOffset 越界：$endOffset" }
            return Span(positionAt(source, startOffset), positionAt(source, endOffset))
        }

        private fun positionAt(source: String, offset: Int): Position {
            var line = 1
            var column = 1
            var i = 0
            while (i < offset) {
                if (source[i] == '\n') {
                    line++
                    column = 1
                } else {
                    column++
                }
                i++
            }
            return Position(offset, line, column)
        }
    }
}
