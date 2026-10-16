package io.github.workeron9.ozmoz.lineage.cli

import io.github.workeron9.ozmoz.lineage.pipeline.LineagePipeline

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.FormatRequest
import io.github.workeron9.ozmoz.lineage.engine.calcite.CalciteEngine
import io.github.workeron9.ozmoz.lineage.engine.jooq.JooqEngine
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Severity
import java.io.File
import kotlinx.serialization.Serializable

/**
 * `ozml format` —— SQL 排版（[Feature.PRETTY_PRINT]）。
 *
 * 与 `ozml convert` 的**本质区别**：convert 换方言（render），format 只重排（不换方言）。
 * 内容来源是引擎自己的规范化渲染——本项目**不重写渲染器**，只在规范文本上按子句插换行
 * （见 ADR-0014），因此 `parse → format → re-parse` 语义等价。
 *
 * 路由按**能力表**而非 try-catch：引擎没声明 [Feature.PRETTY_PRINT] 直接报错
 * （calcite / jooq 明确不支持排版——它们的 reason 会被带出）。
 *
 * 缺省输出**多行排版**到 stdout；`--json` 输出 `{engine, dialect, sql, diagnostics}`
 * 稳定形状。**有损如实报**：源文本含注释时，渲染器会丢注释，落一条 WARNING
 * （stdout 是 SQL 文本时，诊断走 stderr）。
 */
public class FormatCommand : CliktCommand(name = "format") {

    private val engineId: String by option("--engine", help = "排版引擎（jsqlparser；calcite/jooq 不支持排版）")
        .choice(*SUPPORTED_ENGINES)
        .default(JSqlParserEngine.ID)

    private val dialect: String? by option("--dialect", help = "方言名（jsqlparser 无方言概念，仅记录）")

    private val file: String by option("-f", "--file", help = "SQL 文件路径，`-` 表示从标准输入读取")
        .required()

    private val singleLine: Boolean by option("--single-line", help = "规范化单行输出（关键字大写、空白归一；缺省多行排版）")
        .flag(default = false)

    private val json: Boolean by option("--json", help = "输出 JSON 报告（{engine, dialect, sql, diagnostics}）")
        .flag(default = false)

    override fun help(context: Context): String =
        "排版一条 SQL（子句换行 + 规范化），不换方言；注释不保留时如实告警。"

    override fun run() {
        val sql = readSql(file)
        val engine = LineagePipeline.engine(engineId)

        // 路由按能力表：没声明排版能力，直接给出可解释的错（不靠异常碰运气）。
        val reason = engine.capabilities.reason(Feature.PRETTY_PRINT)
        if (reason != null) {
            throw UsageError("引擎 $engineId 不支持排版：$reason")
        }

        val multi = !singleLine
        val result = engine.format(FormatRequest(sql = sql, dialect = dialect, multiline = multi))
        when (result) {
            is Resolved.Known -> {
                val outcome = result.value
                if (json) {
                    echo(
                        JsonSupport.encodeFormatReport(
                            FormatReport(
                                engine = engine.id,
                                dialect = dialect,
                                sql = outcome.sql,
                                diagnostics = outcome.diagnostics,
                            ),
                        ),
                    )
                } else {
                    echo(outcome.sql)
                }
                // 有损告警（注释丢失）走 stderr，不污染 stdout 的 SQL 文本。
                for (d in outcome.diagnostics) {
                    echo("${d.severity} ${d.code}: ${d.message}", err = true)
                }
            }

            is Resolved.Unknown -> {
                // 排版没跑成（语法错误 / 空输入）：不是程序坏了，是输入不构成一次排版。
                echo(
                    JsonSupport.encodeFormatReport(
                        FormatReport(
                            engine = engine.id,
                            dialect = dialect,
                            sql = null,
                            diagnostics = listOf(
                                Diagnostic(
                                    severity = Severity.ERROR,
                                    code = CODE_FORMAT_SKIPPED,
                                    message = result.reason,
                                    span = result.span,
                                    engineId = engine.id,
                                ),
                            ),
                        ),
                    ),
                    err = true,
                )
                throw ProgramResult(1)
            }
        }
    }

    private fun readSql(path: String): String =
        if (path == "-") {
            System.`in`.bufferedReader().readText()
        } else {
            val f = File(path)
            require(f.isFile) { "找不到 SQL 文件：$path" }
            f.readText()
        }

    public companion object {
        private val SUPPORTED_ENGINES = arrayOf(JSqlParserEngine.ID, CalciteEngine.ID, JooqEngine.ID)

        /** 诊断码：稳定契约（排版没跑成统一用它，与引擎码成镜像）。 */
        public const val CODE_FORMAT_SKIPPED: String = "format.skipped"
    }
}

/**
 * `ozml format --json` 的稳定输出形状（stdout 成功 / stderr 失败都走它）。
 * [sql] 为 null 表示排版没跑成（诊断在 [diagnostics] 里）。
 */
@Serializable
internal data class FormatReport(
    val engine: String,
    val dialect: String? = null,
    val sql: String? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
)
