package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailState
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.workeron9.ozmoz.lineage.desktop.EngineGateway
import io.github.workeron9.ozmoz.lineage.desktop.OpenedFile
import io.github.workeron9.ozmoz.lineage.desktop.model.Analysis
import io.github.workeron9.ozmoz.lineage.desktop.model.FlowKind
import io.github.workeron9.ozmoz.lineage.desktop.model.IssueView
import io.github.workeron9.ozmoz.lineage.desktop.model.SeverityView
import io.github.workeron9.ozmoz.lineage.desktop.model.visibleTreeRows

/**
 * 桌面外壳：M3 的 [WideNavigationRail]（可折叠）+ [TopAppBar]，**工作区 / 设置是两个「目的地」**。
 *
 * 分工按 M3 的范式落：侧栏管**应用级**（分析缺省、关于），顶栏管**文档级**（文件名、统计、
 * 打开文件）。引擎与容错因此从顶栏挪进设置——同一个开关只准出现在一个地方。
 *
 * 血缘图 / 归一化树 / 源文本仍是 D1 的自绘仪器层，这一版**不动**：图的可交互水平
 * （平移缩放 / 虚拟化 / 聚簇）是 ADR-0022 D0-② 探针的事，换壳解决不了它。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
public fun App(initial: OpenedFile? = null) {
    DesktopTheme {
        val railExpandedValue = WideNavigationRailValue.Expanded
        var opened by remember { mutableStateOf(initial) }
        var analysis by remember { mutableStateOf<Analysis?>(null) }
        var view by remember { mutableStateOf(View.GRAPH) }
        var collapsedNodes by remember { mutableStateOf(emptySet<String>()) }
        var selectedColumn by remember { mutableStateOf<String?>(null) }
        var selectedFlow by remember { mutableStateOf<Int?>(null) }
        var focusLine by remember { mutableStateOf<Int?>(null) }
        var engineId by remember { mutableStateOf(EngineGateway.engineIds.first()) }
        var tolerant by remember { mutableStateOf(true) }
        var page by remember { mutableStateOf(Page.WORKSPACE) }
        var railExpanded by remember { mutableStateOf(true) }
        val railState = rememberWideNavigationRailState(WideNavigationRailValue.Expanded)
        LaunchedEffect(railExpanded) {
            // 用 expand/collapse（带过渡）而不是 toggle()：这里没有协程作用域，
            // 而 LaunchedEffect 本身就是 suspend 的。
            if (railExpanded) railState.expand() else railState.collapse()
        }
        val railIsExpanded = railState.currentValue == WideNavigationRailValue.Expanded

        // shortcut: 分析在组合线程上同步跑（小文件毫秒级）；超大文件会卡一帧，D2 挪到后台调度。
        LaunchedEffect(opened, engineId, tolerant) {
            analysis = opened?.let { EngineGateway.analyze(it.sql, engineId, tolerant) }
            view = View.GRAPH
            collapsedNodes = emptySet()
            selectedColumn = null
            selectedFlow = null
            focusLine = null
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                WideNavigationRail(
                    state = railState,
                    // 缺省是 Arrangement.Center：桌面侧栏里「目的地」要贴顶，不然窗口一高就飘在中间。
                    arrangement = Arrangement.Top,
                    header = { RailHeader(railIsExpanded) { railExpanded = !railExpanded } },
                ) {
                    for (destination in Page.entries) {
                        WideNavigationRailItem(
                            selected = page == destination,
                            onClick = { page = destination },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                            railExpanded = railIsExpanded,
                        )
                    }
                }
                VerticalDivider()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    opened?.name ?: "未打开文件",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    statLine(analysis),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        actions = {
                            Button(
                                onClick = { opened = EngineGateway.pickSqlFile() },
                            ) { Text("打开 SQL 文件") }
                            Spacer(Modifier.width(12.dp))
                        },
                    )
                    when (page) {
                        Page.WORKSPACE -> Workspace(
                            opened = opened,
                            analysis = analysis,
                            view = view,
                            onView = { view = it },
                            collapsedNodes = collapsedNodes,
                            onToggleNode = { id ->
                                collapsedNodes = if (id in collapsedNodes) collapsedNodes - id else collapsedNodes + id
                            },
                            selectedColumn = selectedColumn,
                            selectedFlow = selectedFlow,
                            onSelectColumn = { column, line ->
                                selectedColumn = column
                                selectedFlow = null
                                focusLine = line
                            },
                            onSelectFlow = { flow, line ->
                                selectedFlow = flow
                                selectedColumn = null
                                focusLine = line
                            },
                            focusLine = focusLine,
                            onFocusLine = { focusLine = it },
                            onOpen = { opened = EngineGateway.pickSqlFile() },
                        )

                        Page.SETTINGS -> SettingsPage(
                            engineId = engineId,
                            tolerant = tolerant,
                            onEngine = { engineId = it },
                            onTolerant = { tolerant = it },
                        )
                    }
                }
            }
        }
    }
}

/** 两个目的地。将来加「对话」这类一等公民时，这里加一项 + 一个 `when` 分支。 */
private enum class Page(val label: String, val icon: ImageVector) {
    WORKSPACE("工作区", Icons.Workspace),
    SETTINGS("设置", Icons.Settings),
}

/** 两个视图的切换：**不并排**——1200px 宽下并排会把图和树都压成废件。 */
private enum class View { GRAPH, TREE }

@Composable
private fun RailHeader(expanded: Boolean, onToggle: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
        IconButton(onClick = onToggle) {
            Icon(
                Icons.Chevron,
                contentDescription = if (expanded) "收起侧栏" else "展开侧栏",
                modifier = Modifier.rotate(if (expanded) 0f else 180f),
            )
        }
    }
}

/** 顶栏副标题：始终说清「这份结果是谁跑出来的」——空图不许冒充「没问题」。 */
private fun statLine(analysis: Analysis?): String {
    val current = analysis ?: return "未分析"
    return buildString {
        append(current.engineId)
        append(" · 列 ").append(current.columns.size)
        append(" · 边 ").append(current.flows.size)
        append(" · 未知 ").append(current.unknowns.size)
        append(" · 诊断 ").append(current.issues.size)
        append(" · ").append(current.elapsedMillis).append("ms")
    }
}

@Composable
private fun Workspace(
    opened: OpenedFile?,
    analysis: Analysis?,
    view: View,
    onView: (View) -> Unit,
    collapsedNodes: Set<String>,
    onToggleNode: (String) -> Unit,
    selectedColumn: String?,
    selectedFlow: Int?,
    onSelectColumn: (String?, Int?) -> Unit,
    onSelectFlow: (Int?, Int?) -> Unit,
    focusLine: Int?,
    onFocusLine: (Int?) -> Unit,
    onOpen: () -> Unit,
) {
    val sql = opened?.sql
    if (sql == null) {
        Welcome(onOpen)
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            SourcePanel(sql = sql, focusLine = focusLine, modifier = Modifier.weight(0.38f))
            VerticalDivider()
            Column(Modifier.weight(0.62f).fillMaxHeight()) {
                ViewTabs(view = view, analysis = analysis, onSelect = onView)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val current = analysis) {
                        null -> Centered("分析中…", Tokens.TextMuted)
                        else -> when (view) {
                            View.GRAPH -> GraphPanel(
                                analysis = current,
                                selectedColumn = selectedColumn,
                                selectedFlow = selectedFlow,
                                onSelectColumn = onSelectColumn,
                                onSelectFlow = onSelectFlow,
                            )

                            View.TREE -> TreePanel(
                                analysis = current,
                                collapsed = collapsedNodes,
                                onToggle = onToggleNode,
                                onFocusLine = onFocusLine,
                            )
                        }
                    }
                }
            }
        }
        DetailStrip(
            analysis = analysis,
            selectedColumn = selectedColumn,
            selectedFlow = selectedFlow,
            onFocusLine = onFocusLine,
        )
    }
}

/** 视图切换：M3 单选分段控件（D1 是自绘 chip）。 */
@Composable
private fun ViewTabs(view: View, analysis: Analysis?, onSelect: (View) -> Unit) {
    val nodeCount = analysis?.let { visibleTreeRows(it.tree, emptySet()).size } ?: 0
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SingleChoiceSegmentedButtonRow {
            val labels = listOf("血缘图" to (analysis?.flows?.size ?: 0), "归一化树" to nodeCount)
            labels.forEachIndexed { index, (label, count) ->
                val target = if (index == 0) View.GRAPH else View.TREE
                SegmentedButton(
                    selected = view == target,
                    onClick = { onSelect(target) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                ) {
                    Text("$label  $count", fontSize = 12.sp)
                }
            }
        }
    }
}

/** 图例：颜色只说明边类型；变换种类（线型）在详情条里按选中项给出。挂在图的表头上（不是分段控件那一行——放那儿会被窗口宽度挤掉）。 */
@Composable
internal fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (kind in FlowKind.entries) {
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .width(7.dp)
                    .height(7.dp)
                    .background(Tokens.flowColor(kind), RoundedCornerShape(2.dp)),
            )
            Text(
                kind.name.lowercase(),
                color = Tokens.TextFaint,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}

@Composable
private fun DetailStrip(
    analysis: Analysis?,
    selectedColumn: String?,
    selectedFlow: Int?,
    onFocusLine: (Int?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(104.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val current = analysis
            val flow = current?.flows?.getOrNull(selectedFlow ?: -1)
            val column = current?.columns?.firstOrNull { it.id == selectedColumn }
            when {
                current == null -> Text("未分析", color = Tokens.TextMuted, fontSize = 12.sp)
                flow != null -> {
                    Text(
                        "边 ${flow.kind.name} · ${flow.transform.name}",
                        color = Tokens.flowColor(flow.kind),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    Text(
                        "${flow.from}  →  ${flow.to}",
                        color = Tokens.Text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    flow.expression?.let {
                        Text("表达式  $it", color = Tokens.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }

                column != null -> {
                    Text(
                        column.qualifiedName,
                        color = Tokens.Accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    Text(
                        buildString {
                            append("类型  ").append(column.type ?: "未收录")
                            if (column.isOutput) append("   · 输出列")
                            append("   · 可空  ").append(column.nullable?.toString() ?: "未收录")
                        },
                        color = Tokens.TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }

                else -> {
                    current.note?.let {
                        Text(it, color = Tokens.Danger, fontSize = 12.sp)
                    }
                    // 列/边/诊断的计数在顶栏副标题里已经有了，这里只说顶栏说不下的：未知。
                    current.unknowns.take(2).forEach {
                        Text("未知  $it", color = Tokens.Warning, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    if (current.unknowns.isEmpty()) {
                        Text(
                            "点图上的节点或边看详情；颜色 = 边类型，线型 = 变换种类。",
                            color = Tokens.TextFaint,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        VerticalDivider()
        IssuesCell(analysis?.issues ?: emptyList(), Modifier.width(380.dp).fillMaxHeight(), onFocusLine)
    }
}

/**
 * 诊断条：逐条可点，点了把源文本栏滚到那一行。**空的时候写「无诊断」**——
 * 不把「没跑成」与「跑了没问题」画成一个样子。
 */
@Composable
private fun IssuesCell(issues: List<IssueView>, modifier: Modifier, onFocusLine: (Int?) -> Unit) {
    if (issues.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("无诊断", color = Tokens.TextFaint, fontSize = 11.sp)
        }
        return
    }
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(issues) { issue ->
            val color = when (issue.severity) {
                SeverityView.ERROR -> Tokens.Danger
                SeverityView.WARNING -> Tokens.Warning
                SeverityView.INFO -> Tokens.TextMuted
            }
            Column(
                modifier = Modifier
                    .clickable { onFocusLine(issue.line) }
                    .border(1.dp, Tokens.Border, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    buildString {
                        issue.line?.let { append("L").append(it).append(' ') }
                        append(issue.severity.name)
                        append("  ").append(issue.code)
                    },
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                Text(
                    issue.message.take(70),
                    color = Tokens.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** 空态：M3 Card，说清「解析在哪跑」与「怎么带文件启动」。 */
@Composable
private fun Welcome(onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.width(440.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("打开一个 .sql 文件", style = MaterialTheme.typography.titleMedium)
                Text(
                    "解析在本机进程内完成，不上传、不连数据库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "./gradlew :desktop:run --args=/path/q.sql",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpen) { Text("打开 SQL 文件") }
            }
        }
    }
}

@Composable
internal fun Centered(text: String, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
internal fun PanelHeader(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = Tokens.TextFaint,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}
