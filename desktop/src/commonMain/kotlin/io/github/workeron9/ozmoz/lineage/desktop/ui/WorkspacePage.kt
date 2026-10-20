package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.workeron9.ozmoz.lineage.desktop.model.Analysis
import io.github.workeron9.ozmoz.lineage.desktop.model.FlowKind
import io.github.workeron9.ozmoz.lineage.desktop.model.IssueView
import io.github.workeron9.ozmoz.lineage.desktop.model.SeverityView
import io.github.workeron9.ozmoz.lineage.desktop.model.visibleTreeRows

/**
 * 工作区：源 SQL + 三个视图（血缘图 / 归一化树 / Agent 对话）+ 底部诊断条。
 *
 * Agent 对话是工作区里的一个 Tab（而不是唯一入口）：它的上下文锚定在
 * 当前打开的 SQL 上，脱离文件的对话在 AGENT 目的地里。
 */
@Composable
internal fun WorkspacePage(state: WorkbenchState) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    val opened = state.opened
    if (opened == null) {
        Welcome(onNew = { state.newDocument() }, onOpen = { state.pickFile() })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            SourcePanel(state = state, modifier = Modifier.weight(0.38f))
            VerticalDivider()
            Column(Modifier.weight(0.62f).fillMaxHeight()) {
                WorkspaceTabs(state)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state.workspaceTab) {
                        WorkspaceTab.GRAPH, WorkspaceTab.TREE -> when (val current = state.analysis) {
                            null -> Centered(
                                if (opened.sql.isBlank()) "粘贴或输入 SQL，停手后自动分析" else "分析中…",
                                tokens.textMuted,
                            )
                            else -> when (state.workspaceTab) {
                                WorkspaceTab.GRAPH -> GraphPanel(
                                    analysis = current,
                                    selectedColumn = state.selectedColumn,
                                    selectedFlow = state.selectedFlow,
                                    onSelectColumn = { column, line ->
                                        state.selectedColumn = column
                                        state.selectedFlow = null
                                        state.focusLine = line
                                    },
                                    onSelectFlow = { flow, line ->
                                        state.selectedFlow = flow
                                        state.selectedColumn = null
                                        state.focusLine = line
                                    },
                                )

                                else -> TreePanel(
                                    analysis = current,
                                    collapsed = state.collapsedNodes,
                                    onToggle = { id ->
                                        state.collapsedNodes = if (id in state.collapsedNodes) {
                                            state.collapsedNodes - id
                                        } else {
                                            state.collapsedNodes + id
                                        }
                                    },
                                    onFocusLine = { state.focusLine = it },
                                )
                            }
                        }

                        WorkspaceTab.AGENT -> AgentChat(
                            state = state,
                            context = opened,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        if (state.workspaceTab != WorkspaceTab.AGENT) {
            DetailStrip(
                analysis = state.analysis,
                selectedColumn = state.selectedColumn,
                selectedFlow = state.selectedFlow,
                onFocusLine = { state.focusLine = it },
            )
        } else {
            // Agent 对话占满高度；诊断条仍在 AGENT 目的地的顶栏副标题里可见。
            Box(Modifier.height(density.gap))
        }
    }
}

/** 视图切换：M3 单选分段控件；计数挂在标签上（D1 口径）。 */
@Composable
private fun WorkspaceTabs(state: WorkbenchState) {
    val analysis = state.analysis
    val nodeCount = analysis?.let { visibleTreeRows(it.tree, emptySet()).size } ?: 0
    val labels = listOf(
        WorkspaceTab.GRAPH to (analysis?.flows?.size ?: 0),
        WorkspaceTab.TREE to nodeCount,
        WorkspaceTab.AGENT to state.activities.size,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // fillMaxWidth：不给宽度时三个按钮按最小宽度量，文字会被裁掉（离屏渲染实测）。
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, (tab, count) ->
                SegmentedButton(
                    selected = state.workspaceTab == tab,
                    onClick = { state.workspaceTab = tab },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                    // 不带勾选图标：图标 + 默认内边距会把「Agent 对话」挤掉半截（离屏渲染实测）。
                    icon = {},
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (count > 0) "${tab.label}  $count" else tab.label,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** 图例：颜色只说明边类型；变换种类（线型）在详情条里按选中项给出。 */
@Composable
internal fun Legend() {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (kind in FlowKind.entries) {
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .width(7.dp)
                    .height(7.dp)
                    .background(tokens.flowColor(kind), RoundedCornerShape(2.dp)),
            )
            Text(
                kind.name.lowercase(),
                color = tokens.textFaint,
                fontFamily = FontFamily.Monospace,
                fontSize = density.bodySize,
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
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    Row(
        Modifier.fillMaxWidth().height(if (density.gap > 8.dp) 116.dp else 104.dp)
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
                current == null -> Text("未分析", color = tokens.textMuted, fontSize = density.bodySize)
                flow != null -> {
                    Text(
                        "边 ${flow.kind.name} · ${flow.transform.name}",
                        color = tokens.flowColor(flow.kind),
                        fontFamily = FontFamily.Monospace,
                        fontSize = density.bodySize,
                    )
                    Text(
                        "${flow.from}  →  ${flow.to}",
                        color = tokens.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = density.bodySize,
                    )
                    flow.expression?.let {
                        Text(
                            "表达式  $it",
                            color = tokens.textMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = density.bodySize,
                        )
                    }
                }

                column != null -> {
                    Text(
                        column.qualifiedName,
                        color = tokens.accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = density.bodySize,
                    )
                    Text(
                        buildString {
                            append("类型  ").append(column.type ?: "未收录")
                            if (column.isOutput) append("   · 输出列")
                            append("   · 可空  ").append(column.nullable?.toString() ?: "未收录")
                        },
                        color = tokens.textMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = density.bodySize,
                    )
                }

                else -> {
                    current.note?.let { Text(it, color = tokens.danger, fontSize = density.bodySize) }
                    current.unknowns.take(2).forEach {
                        Text("未知  $it", color = tokens.warning, fontFamily = FontFamily.Monospace, fontSize = density.bodySize)
                    }
                    if (current.unknowns.isEmpty()) {
                        Text(
                            "点图上的节点或边看详情；颜色 = 边类型，线型 = 变换种类。",
                            color = tokens.textFaint,
                            fontSize = density.bodySize,
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
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    if (issues.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("无诊断", color = tokens.textFaint, fontSize = density.bodySize)
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
                SeverityView.ERROR -> tokens.danger
                SeverityView.WARNING -> tokens.warning
                SeverityView.INFO -> tokens.textMuted
            }
            Column(
                modifier = Modifier
                    .clickable { onFocusLine(issue.line) }
                    .border(1.dp, tokens.border, RoundedCornerShape(4.dp))
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
                    fontSize = density.bodySize,
                )
                Text(
                    issue.message.take(70),
                    color = tokens.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = density.bodySize,
                )
            }
        }
    }
}

/** 空态：M3 Card，说清「解析在哪跑」与两条入口（直接粘 SQL / 打开文件）。 */
@Composable
private fun Welcome(onNew: () -> Unit, onOpen: () -> Unit) {
    val tokens = LocalTokens.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.width(440.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("输入或打开 SQL", style = MaterialTheme.typography.titleMedium)
                Text(
                    "解析在本机进程内完成，不上传；连库与对话也只走本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "./gradlew :desktop:run --args=/path/q.sql",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = tokens.textMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onNew) { Text("新建 / 粘贴 SQL") }
                    TextButton(onClick = onOpen) { Text("打开 .sql 文件") }
                }
            }
        }
    }
}
