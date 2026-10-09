package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.graph.Direction
import io.github.workeron9.ozmoz.lineage.graph.LineageGraph
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.lineage.LineageBuilder
import io.github.workeron9.ozmoz.lineage.schema.DdlFileSchemaProvider
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * `ozml impact` —— 在血缘图上做**上溯 / 下溯 / 影响面**查询。
 *
 * 管线与 [LineageCommand] 一致（`SqlEngine.analyze` → `LineageBuilder.build`），
 * 之后交给 `graph` 模块的 [LineageGraph] 做遍历——**图算法的权威实现只在 `graph`**，
 * CLI 不重写遍历。
 *
 * 目标列不在图里时以非零码退出并打印原因（Never-wrong：不猜一个相近列）。
 */
public class ImpactCommand : CliktCommand(name = "impact") {

    private val engineId: String by option("--engine", help = "解析引擎（当前仅 jsqlparser）")
        .choice(*SUPPORTED_ENGINES)
        .default(JSqlParserEngine.ID)

    private val dialect: String? by option("--dialect", help = "方言名（jsqlparser 无方言概念，仅记录）")

    private val file: String by option("-f", "--file", help = "SQL 文件路径，`-` 表示从标准输入读取")
        .required()

    private val on: String by option("--on", help = "目标列 id（折叠后，如 store_sales.ss_quantity）")
        .required()

    private val direction: String by option(
        "--direction",
        help = "遍历方向：upstream（上溯，默认）/ downstream（下溯 / 影响面）/ both（双向）",
    )
        .choice("upstream", "downstream", "both")
        .default("upstream")

    private val depth: Int? by option("--depth", help = "最大层数（默认不限）")
        .int()

    private val format: String by option("--format", help = "输出格式：text（默认）/ json")
        .choice("text", "json")
        .default("text")

    private val schemaPath: String? by option(
        "--schema",
        help = "DDL 文件路径（含 CREATE TABLE），用于物理表 `*` 展开、裸列消歧与 INSERT 目标列对齐",
    )

    override fun help(context: Context): String =
        "在血缘图上查询一列的上溯 / 下溯 / 影响面（图算法由 graph 模块提供）。"

    override fun run() {
        val sql = readSql(file)
        val engine = engineFor(engineId)
        val semantic = engine.analyze(sql, ParseRequest(dialect = dialect))
        val schema = schemaPath?.let { DdlFileSchemaProvider.fromFiles(id = "ddl", Path(it)) }

        val model = when (semantic) {
            is Resolved.Known -> LineageBuilder.build(semantic.value, schema)
            is Resolved.Unknown -> {
                echo("ERROR semantic_unavailable: ${semantic.reason}", err = true)
                throw ProgramResult(1)
            }
        }

        val graph = LineageGraph.of(model)
        val target = on.lowercase()
        if (target !in graph.nodeIds) {
            echo("ERROR column_not_found: $on（图里没有这一列；可用 --format json 看全图）", err = true)
            throw ProgramResult(1)
        }

        val dir = when (direction) {
            "downstream" -> Direction.DOWNSTREAM
            "both" -> Direction.BOTH
            else -> Direction.UPSTREAM
        }
        val result = graph.impact(target, dir, depth)

        when (format) {
            "json" -> echo(JsonSupport.encodeImpactResult(result) { graph.label(it) })
            else -> echo(renderImpact(result, graph))
        }
    }

    /** 人读格式：每行 `距离  列名  [output]`，末尾给一行汇总。 */
    private fun renderImpact(result: io.github.workeron9.ozmoz.lineage.graph.ImpactResult, graph: LineageGraph): String =
        buildString {
            append("# ").append(result.origin).append("  ")
            append(result.direction).append("  depth=").append(result.depth ?: "unlimited")
            if (result.truncated) append("  (truncated)")
            append('\n')
            for (node in result.nodes) {
                append("  ").append(node.distance).append("  ")
                append(graph.label(node.id))
                if (node.isOutput) append("  [output]")
                append('\n')
            }
            append("reached: ").append(result.reached.size).append('\n')
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

    public companion object {
        private val SUPPORTED_ENGINES = arrayOf(JSqlParserEngine.ID)
    }
}
