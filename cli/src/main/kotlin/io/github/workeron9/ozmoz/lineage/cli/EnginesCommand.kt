package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.github.workeron9.ozmoz.lineage.server.EngineReport

/**
 * `ozml engines` —— 列出**注册引擎的能力表**（id / 声明能力 / 方言 / 不支持原因）。
 *
 * 用途：① 用户查「这个引擎能不能干这件事」的权威清单（路由靠能力表，不靠试错）；
 * ② **M5 文档站的「工具百科」数据源**——`--format json` 与 `POST /api/engines`
 * **同形**（同一 [EngineReport] 与同一构造器 [EngineReport.from]），页面由它数据驱动渲染，
 * 不手写表格。
 *
 * 引擎注册面与 `ozml lineage` / `ozml serve` 同一份（[LineagePipeline.allEngines]）。
 */
public class EnginesCommand : CliktCommand(name = "engines") {

    private val format: String by option("--format", help = "输出格式：json（默认，与 /api/engines 同形）/ text（人读）")
        .choice("json", "text")
        .default("json")

    override fun help(context: Context): String =
        "列出注册引擎的能力表（声明能力 / 方言 / 不支持原因）；json 与 /api/engines 同形，供文档站数据驱动渲染。"

    override fun run() {
        val reports = LineagePipeline.allEngines().map { EngineReport.from(it) }
        when (format) {
            "text" -> echo(renderText(reports))
            else -> echo(JsonSupport.encodeEngineReports(reports))
        }
    }

    /** 人读格式：每个引擎一段（能力 / 方言 / 不支持原因），顺序稳定。 */
    private fun renderText(reports: List<EngineReport>): String = buildString {
        for ((index, report) in reports.withIndex()) {
            if (index > 0) append('\n')
            append(report.id).append('\n')
            append("  features : ").append(report.features.ifEmpty { listOf("-") }.joinToString(", ")).append('\n')
            append("  dialects : ").append(report.dialects.ifEmpty { listOf("-") }.joinToString(", ")).append('\n')
            if (report.reasons.isNotEmpty()) {
                append("  unsupported:\n")
                for ((feature, reason) in report.reasons) {
                    append("    ").append(feature.padEnd(16)).append(reason).append('\n')
                }
            }
        }
    }
}
