package io.github.workeron9.ozmoz.lineage.engine

import io.github.workeron9.ozmoz.lineage.ir.Diagnostic

/**
 * 一次**格式化（排版）**请求——对应 [Feature.PRETTY_PRINT]。
 *
 * [multiline] 为 true（缺省）时产出多行排版；false 时产出**规范化单行**
 * （关键字大写、空白归一）。两者都由引擎的规范化渲染派生，差别只是换行。
 */
public data class FormatRequest(
    val sql: String,
    val dialect: String? = null,
    val multiline: Boolean = true,
)

/**
 * 格式化结果。
 *
 * [sql] 是重排后的 SQL；[diagnostics] 承载**有损**告警——重排可能丢弃源文本里
 * 引擎不保留的信息（如注释），此时如实报一条 WARNING，而不是静默丢
 * （Never-wrong 的镜像：有损就说，不假装无损）。
 */
public data class FormatOutcome(
    val sql: String,
    val diagnostics: List<Diagnostic> = emptyList(),
)
