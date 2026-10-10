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
import io.github.workeron9.ozmoz.lineage.engine.SchemaLookup
import io.github.workeron9.ozmoz.lineage.engine.ValidateRequest
import io.github.workeron9.ozmoz.lineage.engine.calcite.CalciteEngine
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Severity
import java.io.File
import kotlinx.serialization.Serializable

/**
 * `ozml validate` —— 结合 schema 做语义校验（ADR-0013）。
 *
 * 走引擎的 [Feature.VALIDATE_SCHEMA]（calcite `SqlValidator`）：
 * 未知表 / 未知列 / 列歧义 / 类型不匹配给权威诊断。schema 来自
 * `--schema`（DDL 文件 / 目录，与 `ozml lineage --schema` 同一口径）——
 * **必填**：没有校验目标就没有 validate。
 *
 * 路由按**能力表**而非 try-catch：引擎没声明校验能力直接报错（与 convert 同惯例）。
 * 诊断走 stdout JSON；有 ERROR 级诊断（含解析失败——语法错误是可背书的诊断）
 * 非零退出，便于 CI 与脚本判断。
 */
public class ValidateCommand : CliktCommand(name = "validate") {

    private val engineId: String by option("--engine", help = "校验引擎（calcite）")
        .choice(CalciteEngine.ID)
        .default(CalciteEngine.ID)

    private val dialect: String? by option("--dialect", help = "方言名（决定解析器的 quoting 配置）")

    private val file: String by option("-f", "--file", help = "SQL 文件路径，`-` 表示从标准输入读取")
        .required()

    private val schemaPath: String by option("--schema", help = "schema 来源：单个 DDL 文件或目录（取其下全部 *.sql）")
        .required()

    override fun help(context: Context): String =
        "结合 schema 做语义校验（未知表 / 未知列 / 列歧义 / 类型不匹配）。"

    override fun run() {
        val sql = readSql(file)
        val engine = LineagePipeline.engine(engineId)

        // 路由按能力表：没声明校验能力，直接给出可解释的错（不靠异常碰运气）。
        val reason = engine.capabilities.reason(Feature.VALIDATE_SCHEMA)
        if (reason != null) {
            throw UsageError("引擎 $engineId 不支持 schema 校验：$reason")
        }

        val provider = LineagePipeline.schema(schemaPath)
        // SAM 转换装配：SchemaProvider → engine-api 的 SchemaLookup（同签名，零胶水，ADR-0013）。
        val result = engine.validate(
            ValidateRequest(
                sql = sql,
                dialect = dialect,
                schema = SchemaLookup { ref -> provider.table(ref) },
            ),
        )
        when (result) {
            is Resolved.Known -> {
                val diagnostics = result.value
                echo(
                    JsonSupport.encodeValidateReport(
                        ValidateReport(engine = engine.id, dialect = dialect, diagnostics = diagnostics),
                    ),
                )
                if (diagnostics.any { it.severity == Severity.ERROR }) {
                    for (d in diagnostics) {
                        echo("${d.severity} ${d.code}: ${d.message}", err = true)
                    }
                    throw ProgramResult(1)
                }
            }

            is Resolved.Unknown -> {
                // 校验没跑成：不是程序坏了，是输入不构成一次校验。非零退出 + 诊断到 stderr。
                echo(
                    JsonSupport.encodeValidateReport(
                        ValidateReport(
                            engine = engine.id,
                            dialect = dialect,
                            diagnostics = listOf(
                                Diagnostic(
                                    severity = Severity.ERROR,
                                    code = CODE_VALIDATE_SKIPPED,
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
        /** 诊断码：稳定契约（校验没跑成统一用它，与引擎码 `calcite.validate_error` 成镜像）。 */
        public const val CODE_VALIDATE_SKIPPED: String = "validate.skipped"
    }
}

/**
 * `ozml validate --format json` 的稳定输出形状（stdout 成功 / stderr 失败都走它）。
 */
@Serializable
internal data class ValidateReport(
    val engine: String,
    val dialect: String? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
)
