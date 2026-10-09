package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.lineage.LineageBuilder
import io.github.workeron9.ozmoz.lineage.schema.DdlFileSchemaProvider
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * `ozml lineage` —— 把一条 SQL 解析成**列级血缘模型**（`ir.LineageModel`）。
 *
 * 管线：`SqlEngine.analyze`（引擎无关语义模型）→ `LineageBuilder.build`（列解析 +
 * 三类边 + TransformKind）。引擎不支持语义提取（如 MERGE）时以非零码退出并打印原因；
 * 模型里的 unknown 是**数据**（Never-wrong 的显式未知），照常输出、退出码 0。
 */
public class LineageCommand : CliktCommand(name = "lineage") {

    private val engineId: String by option("--engine", help = "解析引擎（当前仅 jsqlparser）")
        .choice(*SUPPORTED_ENGINES)
        .default(JSqlParserEngine.ID)

    private val dialect: String? by option("--dialect", help = "方言名（jsqlparser 无方言概念，仅记录）")

    private val file: String by option("-f", "--file", help = "SQL 文件路径，`-` 表示从标准输入读取")
        .required()

    private val format: String by option("--format", help = "输出格式：json / edges / summary")
        .choice("json", "edges", "summary")
        .default("json")

    private val schemaPath: String? by option(
        "--schema",
        help = "DDL 文件路径（含 CREATE TABLE），用于物理表 `*` 展开、裸列消歧与 INSERT 目标列对齐",
    )

    override fun help(context: Context): String =
        "解析一条 SQL 并输出列级血缘模型（OUTPUT / PREDICATE / JOIN_KEY 三类边）。"

    override fun run() {
        val sql = readSql(file)
        val engine = engineFor(engineId)
        val semantic = engine.analyze(sql, ParseRequest(dialect = dialect))
        val schema = schemaPath?.let { DdlFileSchemaProvider.fromFiles(id = "ddl", Path(it)) }

        val model = when (semantic) {
            is Resolved.Known -> LineageBuilder.build(semantic.value, schema)
            is Resolved.Unknown -> { // 引擎不支持语义提取（或解析失败）：不猜，非零码退出。
                echo("ERROR semantic_unavailable: ${semantic.reason}", err = true)
                throw ProgramResult(1)
            }
        }

        when (format) {
            "edges" -> echo(renderEdges(model))
            "summary" -> echo(renderSummary(model))
            else -> echo(JsonSupport.encodeLineageModel(model))
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

    private fun engineFor(id: String): SqlEngine = when (id) {
        JSqlParserEngine.ID -> JSqlParserEngine()
        else -> error("未注册的引擎：$id")
    }

    /** 人读格式：每条边一行 `e0  OUTPUT  t.a -> x  [DIRECT]`。 */
    private fun renderEdges(model: LineageModel): String = buildString {
        for (edge in model.edges) {
            append(edge.id).append("  ")
            append(edge.kind).append("  ")
            append(edge.fromColumn.qualifiedName).append(" -> ").append(edge.toColumn.qualifiedName)
            append("  [").append(edge.transform).append("]")
            edge.expression?.let { append("  «").append(it.replace("\n", " ")).append("»") }
            append('\n')
        }
    }

    /** 人读摘要：边按类型计数 + unknown 数。 */
    private fun renderSummary(model: LineageModel): String = buildString {
        val counts = model.edgeCountByKind()
        append("edges: ").append(model.edges.size).append('\n')
        for (kind in EdgeKind.entries) {
            counts[kind]?.let { append("  ").append(kind).append(": ").append(it).append('\n') }
        }
        append("columns: ").append(model.columns.size).append('\n')
        append("scopes: ").append(model.scopes.size).append('\n')
        append("unknowns: ").append(model.unknowns.size).append('\n')
        for (unknown in model.unknowns) {
            append("  - ").append(unknown.reason).append('\n')
        }
    }

    public companion object {
        private val SUPPORTED_ENGINES = arrayOf(JSqlParserEngine.ID)
    }
}
