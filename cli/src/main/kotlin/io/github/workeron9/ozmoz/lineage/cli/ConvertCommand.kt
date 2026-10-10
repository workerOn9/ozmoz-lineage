package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.RenderRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.calcite.CalciteEngine
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Severity
import java.io.File
import kotlinx.serialization.Serializable

/**
 * `ozml convert` —— 方言转换（M3）。
 *
 * 走引擎的 **render-verified 门禁**（ADR-0004 / 风险 R3 对策）：
 * 输入 SQL 先按源方言解析，再按目标方言渲染并 re-parse 做等价校验；
 * **不等价就不输出 SQL**， 转 [Resolved.Unknown] 并非零退出——绝不给一句
 * 「看起来能用、语义已变」的 SQL（如 MSSQL 静默丢 `LIMIT`）。
 *
 * 路由按**能力表**而非 try-catch：引擎没声明 [Feature.DIALECT_RENDER] 直接报错；
 * 方言不在能力表 [SqlEngine.getCapabilities] 的方言集里同样直接报错（列出合法值）。
 */
public class ConvertCommand : CliktCommand(name = "convert") {

    private val engineId: String by option("--engine", help = "转换引擎（当前仅 calcite）")
        .choice(*SUPPORTED_ENGINES)
        .default(CalciteEngine.ID)

    private val fromDialect: String? by option("--from", help = "源方言（缺省按引擎默认方言解析）")

    private val toDialect: String by option("--to", help = "目标方言（如 mysql / postgresql / hive / trino / mssql …）")
        .required()

    private val file: String by option("-f", "--file", help = "SQL 文件路径，`-` 表示从标准输入读取")
        .required()

    private val format: String by option("--format", help = "输出格式：sql / json")
        .choice("sql", "json")
        .default("sql")

    override fun help(context: Context): String =
        "把一条 SQL 从源方言转成目标方言（输出前做 parse→render→re-parse 等价校验）。"

    override fun run() {
        val sql = readSql(file)
        val engine = LineagePipeline.engine(engineId)

        // 路由按能力表：没声明 render / 方言未注册，直接给出可解释的错（不靠异常碰运气）。
        val renderReason = engine.capabilities.reason(Feature.DIALECT_RENDER)
        if (renderReason != null) {
            throw UsageError("引擎 $engineId 不支持方言转换：$renderReason")
        }
        if (!toDialectValid(engine)) {
            throw UsageError(
                "引擎 $engineId 不认目标方言 $toDialect" +
                    "（合法值：${engine.capabilities.dialects.sorted().joinToString(", ")}）",
            )
        }

        val result = engine.render(RenderRequest(sql = sql, fromDialect = fromDialect, toDialect = toDialect))
        when (result) {
            is Resolved.Known -> {
                if (format == "json") {
                    echo(
                        JsonSupport.encodeConvertReport(
                            ConvertReport(
                                engine = engine.id,
                                fromDialect = fromDialect,
                                toDialect = toDialect,
                                sql = result.value,
                                diagnostics = emptyList(),
                            ),
                        ),
                    )
                } else {
                    echo(result.value)
                }
            }

            is Resolved.Unknown -> {
                // 门禁拦截：不是程序坏了，是转换结果不可信。非零退出 + 诊断到 stderr。
                echo(
                    JsonSupport.encodeConvertReport(
                        ConvertReport(
                            engine = engine.id,
                            fromDialect = fromDialect,
                            toDialect = toDialect,
                            sql = null,
                            diagnostics = listOf(
                                Diagnostic(
                                    severity = Severity.ERROR,
                                    code = ConvertCommand.CODE_RENDER_UNVERIFIED,
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

    private fun toDialectValid(engine: SqlEngine): Boolean {
        // 与 CalciteDialects.spec 同口径的归一化：小写 + 连字符→下划线。
        val normalized = toDialect.trim().lowercase().replace('-', '_')
        return engine.capabilities.dialects.any { it.trim().lowercase().replace('-', '_') == normalized }
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
        private val SUPPORTED_ENGINES = arrayOf(CalciteEngine.ID)

        /** 诊断码：稳定契约（门禁拦截统一用它，与引擎码 `calcite.render_unverified` 成镜像）。 */
        public const val CODE_RENDER_UNVERIFIED: String = "convert.render_unverified"
    }
}

/**
 * `ozml convert --format json` 的稳定输出形状（stdout 成功 / stderr 失败都走它）。
 */
@Serializable
internal data class ConvertReport(
    val engine: String,
    val fromDialect: String? = null,
    val toDialect: String,
    val sql: String? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
)
