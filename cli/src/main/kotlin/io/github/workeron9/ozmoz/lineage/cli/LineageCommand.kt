package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.format.LineageExporters
import io.github.workeron9.ozmoz.lineage.graph.LineageGraph
import io.github.workeron9.ozmoz.lineage.graph.SqliteLineageStore
import io.github.workeron9.ozmoz.lineage.graph.StoredModel
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider
import kotlin.io.path.Path

/**
 * `ozml lineage` —— 把 SQL（**单文件 / 目录 / stdin**，可含多条语句）解析成**列级血缘**。
 *
 * 管线：`SqlEngine.analyzeAll`（多语句语义模型）→ `LineageBuilder.build`（列解析 +
 * 六类边 + TransformKind）→ 单个 `ir.LineageModel` 或合并的 `graph.LineageGraph`。
 *
 * 三种典型用法：
 * - `ozml lineage -f q.sql --format json`：单条语句，按 [format] 输出（不落库）。
 * - `ozml lineage -f sql/ --graph ./lineage.db`：吃下一个 SQL 目录，**落库**成全库血缘图。
 * - `ozml lineage -f sql/ --format summary|graph-json`：不落库，直接看合并后的图。
 *
 * 整段语法解析失败（[Resolved.Unknown]）→ 非零退出并打印原因；输入里**没有任何**
 * 可建模语句（如只含 DDL / `MERGE`）→ 同样非零退出（Never-wrong：不假装成功）。
 */
public class LineageCommand : CliktCommand(name = "lineage") {

    private val engineId: String by option("--engine", help = "解析引擎（当前仅 jsqlparser）")
        .choice(*SUPPORTED_ENGINES)
        .default(JSqlParserEngine.ID)

    private val dialect: String? by option("--dialect", help = "方言名（jsqlparser 无方言概念，仅记录）")

    private val file: String by option(
        "-f",
        "--file",
        help = "SQL 文件或目录路径（目录递归取 *.sql），`-` 表示从标准输入读取",
    )
        .required()

    private val format: String by option(
        "--format",
        help = "输出格式：json / edges / summary / graph-json / openlineage / mermaid / dot / cypher（--graph 落库时忽略）",
    )
        .choice("json", "edges", "summary", "graph-json", "openlineage", "mermaid", "dot", "cypher")
        .default("json")

    private val schemaPath: String? by option(
        "--schema",
        help = "DDL 文件或目录（含 CREATE TABLE），用于物理表 `*` 展开、裸列消歧与 INSERT 目标列对齐；" +
            "未给出时自动从输入文本收集 CREATE TABLE（显式给出则只认它）",
    )

    private val graphPath: String? by option(
        "--graph",
        help = "把合并后的全库血缘图落库到该 SQLite 文件（如 ./lineage.db）；给了就只落库不按 --format 输出",
    )

    override fun help(context: Context): String =
        "解析一条或多条 SQL 并输出列级血缘（OUTPUT / PREDICATE / JOIN_KEY / GROUP_BY / ORDER_BY / SOURCE 六类边）；" +
            "支持目录聚合与 --graph 落库。"

    override fun run() {
        val engine = LineagePipeline.engine(engineId)
        val inputs = LineagePipeline.readInputs(file)
        // 显式 --schema 永远优先；未给出时从输入文本顺手收集 CREATE TABLE（可能为 null）。
        val schema = schemaPath?.let { LineagePipeline.schema(it) } ?: LineagePipeline.autoSchema(inputs)
        val stored = collect(engine, schema, inputs)

        if (stored.isEmpty()) {
            echo(
                "ERROR no_modelable_statement: 未从输入提取到可建模的语句（可能只含 DDL / MERGE 等不产出列级血缘的语句）",
                err = true,
            )
            throw ProgramResult(1)
        }

        val graphOut = graphPath
        if (graphOut != null) {
            SqliteLineageStore.open(Path(graphOut)).use { it.save(stored) }
            echo(renderStoreSummary(stored))
            return
        }

        if (stored.size == 1) {
            renderSingle(stored.single().model)
            return
        }

        // 多语句 / 多文件：合并成一张图再输出（json 单模型形状不适用）。
        val graph = LineageGraph.of(stored.map { it.model })
        when (format) {
            "summary" -> echo(renderGraphSummary(graph, stored))
            "graph-json" -> echo(JsonSupport.encodeGraph(graph))
            else -> {
                echo(
                    "ERROR multi_statement_input: 输入含 ${stored.size} 条语句/多个文件；" +
                        "请用 --graph <db> 落库，或 --format summary|graph-json",
                    err = true,
                )
                throw ProgramResult(1)
            }
        }
    }

    /** 逐文件（文件 / 目录 / stdin）读取并建模型；保留来源文件与语句序号。 */
    private fun collect(engine: SqlEngine, schema: SchemaProvider?, inputs: List<Pair<String?, String>>): List<StoredModel> {
        val single = inputs.size == 1
        val result = ArrayList<StoredModel>()
        for ((source, text) in inputs) {
            when (val models = LineagePipeline.models(text, engine, dialect, schema)) {
                is Resolved.Known -> models.value.forEachIndexed { index, model ->
                    result += StoredModel(model = model, sourceFile = source, statementIndex = index)
                }
                is Resolved.Unknown -> {
                    // 单文件：整段语法错误直接失败（保持旧行为）；目录：跳过坏文件并提醒。
                    if (single) {
                        echo("ERROR semantic_unavailable: ${models.reason}", err = true)
                        throw ProgramResult(1)
                    }
                    echo("WARN ${source ?: "<stdin>"}: ${models.reason}", err = true)
                }
            }
        }
        return result
    }

    private fun renderSingle(model: LineageModel) {
        when (format) {
            "edges" -> echo(renderEdges(model))
            "summary" -> echo(renderSummary(model))
            "graph-json" -> echo(JsonSupport.encodeGraph(LineageGraph.of(model)))
            "json" -> echo(JsonSupport.encodeLineageModel(model))
            // 其余交给 format 模块的导出器注册表（id 与 --format 取值同名）。
            else -> {
                val exporter = LineageExporters.byId(format)
                if (exporter == null) {
                    echo("ERROR unknown_format: $format", err = true)
                    throw ProgramResult(1)
                }
                echo(exporter.exporter.export(model))
            }
        }
    }

    /** `--graph` 落库后的人读摘要。 */
    private fun renderStoreSummary(stored: List<StoredModel>): String {
        val graph = LineageGraph.of(stored.map { it.model })
        return buildString {
            append("files: ").append(stored.mapNotNull { it.sourceFile }.distinct().size).append('\n')
            append("statements: ").append(stored.size).append('\n')
            append("nodes: ").append(graph.nodeIds.size).append('\n')
            append("edges: ").append(graph.edges.size).append('\n')
            graphPath?.let { append("graph: ").append(it).append('\n') }
        }
    }

    /** 多文件 / 多语句的人读摘要（合并图）。 */
    private fun renderGraphSummary(graph: LineageGraph, stored: List<StoredModel>): String = buildString {
        append("files: ").append(stored.mapNotNull { it.sourceFile }.distinct().size).append('\n')
        append("statements: ").append(stored.size).append('\n')
        append("nodes: ").append(graph.nodeIds.size).append('\n')
        append("edges: ").append(graph.edges.size).append('\n')
        val counts = graph.edges.groupingBy { it.kind }.eachCount()
        for (kind in EdgeKind.entries) {
            counts[kind]?.let { append("  ").append(kind).append(": ").append(it).append('\n') }
        }
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
