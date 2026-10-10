package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.graph.Direction
import io.github.workeron9.ozmoz.lineage.graph.ImpactResult
import io.github.workeron9.ozmoz.lineage.graph.LineageGraph
import io.github.workeron9.ozmoz.lineage.graph.SqliteLineageStore
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider
import kotlin.io.path.Path

/**
 * `ozml impact` —— 在血缘图上做**上溯 / 下溯 / 影响面**查询。
 *
 * 图有两个来源，二选一：
 * - `--graph ./lineage.db`：读 `ozml lineage --graph` 落下的全库血缘图（**首选**，适合全库）；
 * - `-f q.sql`（文件 / 目录 / stdin）：现建现查（适合单文件调试，行为同旧版）。
 *
 * 图的权威算法只在 `graph` 模块（[LineageGraph]），CLI 不重写遍历。目标列不在图里时
 * 以非零码退出并打印原因（Never-wrong：不猜一个相近列）。
 */
public class ImpactCommand : CliktCommand(name = "impact") {

    private val engineId: String by option("--engine", help = "解析引擎（当前仅 jsqlparser，仅 -f 现建现查时用）")
        .choice(*SUPPORTED_ENGINES)
        .default(JSqlParserEngine.ID)

    private val dialect: String? by option("--dialect", help = "方言名（jsqlparser 无方言概念，仅记录）")

    private val file: String? by option(
        "-f",
        "--file",
        help = "SQL 文件或目录路径（目录递归取 *.sql），`-` 表示标准输入；与 --graph 二选一",
    )

    private val graphPath: String? by option(
        "--graph",
        help = "已落库的全库血缘图 SQLite 文件（ozml lineage --graph 的产物）；与 -f 二选一",
    )

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
        help = "DDL 文件或目录（含 CREATE TABLE），仅 -f 现建现查时用；未给出时自动从输入文本收集 CREATE TABLE",
    )

    override fun help(context: Context): String =
        "在血缘图上查询一列的上溯 / 下溯 / 影响面（图算法由 graph 模块提供）；图来自 --graph 库或 -f 现建。"

    override fun run() {
        val graph = when {
            graphPath != null -> loadGraph(graphPath!!)
            file != null -> buildGraph(file!!)
            else -> {
                echo("ERROR missing_input: 需要 -f <file|dir> 或 --graph <db> 之一", err = true)
                throw ProgramResult(1)
            }
        }

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

    /** 从 SQLite 图库载入全部模型并现建图。 */
    private fun loadGraph(path: String): LineageGraph {
        val models = SqliteLineageStore.open(Path(path)).use { store ->
            store.load().map { it.model }
        }
        if (models.isEmpty()) {
            echo("ERROR empty_graph_store: 图库 $path 里没有任何语句（先跑 ozml lineage --graph）", err = true)
            throw ProgramResult(1)
        }
        return LineageGraph.of(models)
    }

    /** 从文件 / 目录现建图（不落库）。 */
    private fun buildGraph(path: String): LineageGraph {
        val engine = LineagePipeline.engine(engineId)
        val inputs = LineagePipeline.readInputs(path)
        // 显式 --schema 永远优先；未给出时从输入文本顺手收集 CREATE TABLE（可能为 null）。
        val schema: SchemaProvider? = schemaPath?.let { LineagePipeline.schema(it) } ?: LineagePipeline.autoSchema(inputs)
        val single = inputs.size == 1
        val models = ArrayList<LineageModel>()
        for ((source, text) in inputs) {
            when (val built = LineagePipeline.models(text, engine, dialect, schema)) {
                is Resolved.Known -> models += built.value
                is Resolved.Unknown -> {
                    if (single) {
                        echo("ERROR semantic_unavailable: ${built.reason}", err = true)
                        throw ProgramResult(1)
                    }
                    echo("WARN ${source ?: "<stdin>"}: ${built.reason}", err = true)
                }
            }
        }
        if (models.isEmpty()) {
            echo("ERROR no_modelable_statement: 未从输入提取到可建模的语句", err = true)
            throw ProgramResult(1)
        }
        return LineageGraph.of(models)
    }

    /** 人读格式：每行 `距离  列名  [output]`，末尾给一行汇总。 */
    private fun renderImpact(result: ImpactResult, graph: LineageGraph): String =
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

    public companion object {
        private val SUPPORTED_ENGINES = arrayOf(JSqlParserEngine.ID)
    }
}
