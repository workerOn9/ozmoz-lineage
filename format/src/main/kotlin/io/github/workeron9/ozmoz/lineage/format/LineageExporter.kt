package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.LineageModel

/**
 * 血缘模型的**导出器 SPI**（计划 §5.2 冻结契约）。
 *
 * 只负责「模型 → 文本」这一件事；**标识（id）与 MIME 由注册表提供**
 * （[LineageExporterInfo] / [LineageExporters]），调用方（CLI / Ktor）按 id 查表，
 * 新增一种导出格式不需要改动调用方。
 *
 * 实现必须**确定性**：同一模型永远导出同一文本——这是 golden 测试与 diff 的前提。
 * 实现**不得**消费引擎私有类型：入参只有 [LineageModel]。
 */
public fun interface LineageExporter {
    public fun export(model: LineageModel): String
}

/**
 * 注册表里的一项：id / MIME / 一句话说明 + 实现。
 *
 * [id] 是 CLI `--format` 与 HTTP `Accept` 协商用的稳定标识（如 `mermaid`）；
 * [mime] 用于 HTTP 响应头与内容协商。
 */
public data class LineageExporterInfo(
    public val id: String,
    public val mime: String,
    public val description: String,
    public val exporter: LineageExporter,
) {
    init {
        require(id.isNotBlank()) { "LineageExporterInfo.id 不能为空" }
        require(mime.isNotBlank()) { "LineageExporterInfo.mime 不能为空" }
    }
}
