package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.Analysis
import io.github.workeron9.ozmoz.lineage.desktop.model.ColumnView
import io.github.workeron9.ozmoz.lineage.desktop.model.FlowKind
import io.github.workeron9.ozmoz.lineage.desktop.model.FlowTransform
import io.github.workeron9.ozmoz.lineage.desktop.model.FlowView
import io.github.workeron9.ozmoz.lineage.desktop.model.IssueView
import io.github.workeron9.ozmoz.lineage.desktop.model.SeverityView
import io.github.workeron9.ozmoz.lineage.desktop.model.TreeNode
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.ColumnNode
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Severity
import io.github.workeron9.ozmoz.lineage.ir.TransformKind
import io.github.workeron9.ozmoz.lineage.pipeline.LineagePipeline
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Paths

/**
 * [EngineGateway] 的 JVM 实现：**进程内直调**编排层（ADR-0022 决定 3）——
 * 不内置 `ozml serve`、不走 HTTP；引擎 / JDBC 永远在 JVM 内。
 *
 * 与 CLI 同口径：schema 从 SQL 文本**顺手收集** `CREATE TABLE`，走
 * [LineagePipeline.models] 逐语句容错——一段 SQL 里一条语句坏了，其余照常出图，
 * 坏的那条进诊断（Never-wrong 的镜像：丢就说）。这里只把 ir 类型映射成
 * `commonMain` 的呈现状态；引擎私有类型到本文件为止。
 */
public actual object EngineGateway {

    public actual val engineIds: List<String>
        get() = LineagePipeline.ENGINE_IDS

    public actual fun analyze(sql: String, engineId: String, tolerant: Boolean): Analysis {
        val engine = LineagePipeline.engineById(engineId)
            ?: return Analysis(engineId = engineId, note = "未注册的引擎：$engineId")

        val started = System.nanoTime()
        val parseOutcome = LineagePipeline.parse(sql, engine, dialect = null)
        val schema = LineagePipeline.autoSchema(listOf(sql))

        val columns = LinkedHashMap<String, ColumnView>()
        val flows = ArrayList<FlowView>()
        val issues = ArrayList<IssueView>()
        val unknowns = LinkedHashSet<String>()
        var note: String? = null

        when (val built = LineagePipeline.models(sql, engine, dialect = null, schema = schema, tolerant = tolerant)) {
            is Resolved.Known -> {
                for (model in built.value.models) {
                    for (node in model.columns) merge(columns, toColumnView(node))
                    for (edge in model.edges) {
                        merge(columns, toColumnView(edge.fromColumn))
                        merge(columns, toColumnView(edge.toColumn))
                        flows += FlowView(
                            from = edge.fromColumn.id,
                            to = edge.toColumn.id,
                            kind = toFlowKind(edge.kind),
                            transform = toTransform(edge.transform),
                            expression = edge.expression,
                            line = edge.span?.start?.line,
                        )
                    }
                    unknowns += model.unknowns.map { it.reason }
                }
                built.value.diagnostics.forEach { issues += toIssueView(it) }
            }

            is Resolved.Unknown -> {
                note = built.reason
                parseOutcome.diagnostics.forEach { issues += toIssueView(it) }
            }
        }

        return Analysis(
            engineId = engine.id,
            columns = columns.values.toList(),
            flows = flows,
            tree = listOf(toTreeNode(parseOutcome.root, "root")),
            issues = issues,
            unknowns = unknowns.toList(),
            elapsedMillis = (System.nanoTime() - started) / 1_000_000,
            note = note,
        )
    }

    public actual fun pickSqlFile(): OpenedFile? {
        if (GraphicsEnvironment.isHeadless()) return null
        val dialog = FileDialog(null as Frame?, "打开 SQL 文件", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".sql", ignoreCase = true) }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val directory = dialog.directory ?: return null
        return readFile(Paths.get(directory, name).toString())
    }

    /** 同一列在多个语句里出现时按 id 合并，`isOutput` 取或——一个是输出列就算输出列。 */
    private fun merge(columns: MutableMap<String, ColumnView>, view: ColumnView) {
        val existing = columns[view.id]
        columns[view.id] = if (existing == null) view else existing.copy(isOutput = existing.isOutput || view.isOutput)
    }
}

/** 读一个 SQL 文件；读不了返回 null（UI 不因一个坏文件崩）。 */
internal fun readFile(path: String): OpenedFile? =
    runCatching {
        val file = Paths.get(path)
        OpenedFile(name = file.fileName.toString(), sql = Files.readString(file))
    }.getOrNull()

/** 归一化树 → 呈现树；[id] 是树内路径（展开态与 LazyColumn 的 key 用）。 */
private fun toTreeNode(node: AstNode, id: String): TreeNode = TreeNode(
    id = id,
    type = node.type,
    text = node.text,
    line = node.span?.start?.line,
    children = node.children.mapIndexed { index, child -> toTreeNode(child, "$id/$index") },
)

private fun toColumnView(node: ColumnNode): ColumnView = ColumnView(
    id = node.column.id,
    qualifiedName = node.column.qualifiedName,
    isOutput = node.isOutput,
    type = node.type,
    nullable = node.nullable,
    line = node.column.span?.start?.line,
)

/** 只在边里出现、不在 `columns` 里的端点：兜一个最小节点，别让它从图上消失。 */
private fun toColumnView(ref: ColumnRef): ColumnView = ColumnView(
    id = ref.id,
    qualifiedName = ref.qualifiedName,
    isOutput = false,
    line = ref.span?.start?.line,
)

private fun toIssueView(diagnostic: Diagnostic): IssueView = IssueView(
    severity = when (diagnostic.severity) {
        Severity.INFO -> SeverityView.INFO
        Severity.WARNING -> SeverityView.WARNING
        Severity.ERROR -> SeverityView.ERROR
    },
    code = diagnostic.code,
    message = diagnostic.message,
    line = diagnostic.span?.start?.line,
    column = diagnostic.span?.start?.column,
)

/** 穷尽映射：ir 以后加了边类型 / 变换种类，这里编译不过——正是想要的提示。 */
private fun toFlowKind(kind: EdgeKind): FlowKind = when (kind) {
    EdgeKind.OUTPUT -> FlowKind.OUTPUT
    EdgeKind.PREDICATE -> FlowKind.PREDICATE
    EdgeKind.JOIN_KEY -> FlowKind.JOIN_KEY
    EdgeKind.GROUP_BY -> FlowKind.GROUP_BY
    EdgeKind.ORDER_BY -> FlowKind.ORDER_BY
    EdgeKind.SOURCE -> FlowKind.SOURCE
}

private fun toTransform(transform: TransformKind): FlowTransform = when (transform) {
    TransformKind.DIRECT -> FlowTransform.DIRECT
    TransformKind.EXPRESSION -> FlowTransform.EXPRESSION
    TransformKind.AGGREGATE -> FlowTransform.AGGREGATE
    TransformKind.WINDOW -> FlowTransform.WINDOW
    TransformKind.CASE_BRANCH -> FlowTransform.CASE_BRANCH
    TransformKind.CONSTANT -> FlowTransform.CONSTANT
    TransformKind.JOIN_KEY -> FlowTransform.JOIN_KEY
    TransformKind.FILTER_PREDICATE -> FlowTransform.FILTER_PREDICATE
    TransformKind.UNKNOWN -> FlowTransform.UNKNOWN
    TransformKind.GROUPING -> FlowTransform.GROUPING
    TransformKind.ORDERING -> FlowTransform.ORDERING
    TransformKind.SOURCE -> FlowTransform.SOURCE
}
