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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * 单窗口骨架：顶栏 + 左（源 SQL 插槽）右（血缘图 / 归一化树）+ 底部（详情 / 诊断）。
 *
 * SQL 源文本栏是**将来会替换的插槽**（编辑器选型还没实测，见 ADR-0022 D0）：
 * 现在只是只读的带行号文本 + 当前行高亮，布局与状态都不依赖它的内部实现。
 */
@Composable
public fun App(initial: OpenedFile? = null) {
    DesktopTheme {
        var opened by remember { mutableStateOf(initial) }
        var analysis by remember { mutableStateOf<Analysis?>(null) }
        var tab by remember { mutableStateOf(Tab.GRAPH) }
        var collapsed by remember { mutableStateOf(emptySet<String>()) }
        var selectedColumn by remember { mutableStateOf<String?>(null) }
        var selectedFlow by remember { mutableStateOf<Int?>(null) }
        var focusLine by remember { mutableStateOf<Int?>(null) }
        var engineId by remember { mutableStateOf(EngineGateway.engineIds.first()) }
        var tolerant by remember { mutableStateOf(true) }

        // shortcut: 分析在组合线程上同步跑（小文件毫秒级）；超大文件会卡一帧，D2 挪到后台调度。
        LaunchedEffect(opened, engineId, tolerant) {
            analysis = opened?.let { EngineGateway.analyze(it.sql, engineId, tolerant) }
            tab = Tab.GRAPH
            collapsed = emptySet()
            selectedColumn = null
            selectedFlow = null
            focusLine = null
        }

        Column(Modifier.fillMaxSize().background(Tokens.Background)) {
            TopBar(
                fileName = opened?.name,
                analysis = analysis,
                engineId = engineId,
                tolerant = tolerant,
                onEngine = { engineId = it },
                onTolerant = { tolerant = it },
                onOpen = { opened = EngineGateway.pickSqlFile() },
            )
            HDivider()

            Row(Modifier.weight(1f)) {
                val sql = opened?.sql
                if (sql == null) {
                    Welcome(Modifier.weight(1f)) { opened = EngineGateway.pickSqlFile() }
                } else {
                    SourcePanel(sql = sql, focusLine = focusLine, modifier = Modifier.weight(0.38f))
                    VDivider()
                    Column(Modifier.weight(0.62f)) {
                        TabBar(tab = tab, analysis = analysis, onSelect = { tab = it })
                        Box(Modifier.weight(1f)) {
                            when (val current = analysis) {
                                null -> Centered("分析中…", Tokens.TextMuted)
                                else -> when (tab) {
                                    Tab.GRAPH -> GraphPanel(
                                        analysis = current,
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
                                    )

                                    Tab.TREE -> TreePanel(
                                        analysis = current,
                                        collapsed = collapsed,
                                        onToggle = { id ->
                                            collapsed = if (id in collapsed) collapsed - id else collapsed + id
                                        },
                                        onFocusLine = { focusLine = it },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HDivider()
            DetailStrip(
                analysis = analysis,
                selectedColumn = selectedColumn,
                selectedFlow = selectedFlow,
                onFocusLine = { focusLine = it },
            )
        }
    }
}

/** 两个视图的切换：**不并排**——1200px 宽下并排会把图和树都压成废件。 */
private enum class Tab { GRAPH, TREE }

@Composable
private fun TopBar(
    fileName: String?,
    analysis: Analysis?,
    engineId: String,
    tolerant: Boolean,
    onEngine: (String) -> Unit,
    onTolerant: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "ozml lineage",
            color = Tokens.Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(10.dp))
        Text("桌面 D1", color = Tokens.TextFaint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))

        if (analysis != null) {
            Stat("列", analysis.columns.size.toString())
            Spacer(Modifier.width(8.dp))
            Stat("边", analysis.flows.size.toString())
            Spacer(Modifier.width(8.dp))
            if (analysis.unknowns.isNotEmpty()) {
                Stat("未知", analysis.unknowns.size.toString(), Tokens.Warning)
                Spacer(Modifier.width(8.dp))
            }
            if (analysis.issues.isNotEmpty()) {
                Stat("诊断", analysis.issues.size.toString(), Tokens.Danger)
                Spacer(Modifier.width(8.dp))
            }
            Stat("引擎", analysis.engineId)
            Spacer(Modifier.width(12.dp))
        }

        EnginePicker(engineId = engineId, onSelect = onEngine)
        Spacer(Modifier.width(6.dp))
        Chip(
            text = if (tolerant) "容错 开" else "容错 关",
            accent = tolerant,
            onClick = { onTolerant(!tolerant) },
        )
        Spacer(Modifier.width(12.dp))

        Text(
            fileName ?: "未打开文件",
            color = if (fileName == null) Tokens.TextFaint else Tokens.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(12.dp))
        Chip("打开 SQL 文件", accent = true, onClick = onOpen)
    }
}

/** 引擎选择题：切了重跑一次分析（注册面与 CLI 同一份 [EngineGateway.engineIds]）。 */
@Composable
private fun EnginePicker(engineId: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip("$engineId ▾", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (id in EngineGateway.engineIds) {
                DropdownMenuItem(
                    text = { Text(id, color = Tokens.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    onClick = {
                        onSelect(id)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = Tokens.TextMuted) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tokens.TextFaint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(value, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun TabBar(tab: Tab, analysis: Analysis?, onSelect: (Tab) -> Unit) {
    val nodeCount = analysis?.let { visibleTreeRows(it.tree, emptySet()).size } ?: 0
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        TabLabel("血缘图", analysis?.flows?.size ?: 0, tab == Tab.GRAPH) { onSelect(Tab.GRAPH) }
        Spacer(Modifier.width(4.dp))
        TabLabel("归一化树", nodeCount, tab == Tab.TREE) { onSelect(Tab.TREE) }
        Spacer(Modifier.weight(1f))
        Legend()
    }
}

@Composable
private fun TabLabel(text: String, count: Int, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (active) Tokens.SurfaceRaised else Tokens.Surface, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = if (active) Tokens.Text else Tokens.TextMuted,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), color = Tokens.TextFaint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

/** 图例：颜色只说明边类型；变换种类（线型）在详情条里按选中项给出。 */
@Composable
private fun Legend() {
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
    Row(Modifier.fillMaxWidth().height(104.dp).background(Tokens.Surface)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp, vertical = 10.dp)
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
                    Text(
                        "列 ${current.columns.size} · 边 ${current.flows.size} · 未知 ${current.unknowns.size}" +
                            " · 诊断 ${current.issues.size} · ${current.elapsedMillis}ms",
                        color = Tokens.TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    current.unknowns.take(3).forEach {
                        Text("未知  $it", color = Tokens.Warning, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
        VDivider()
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

@Composable
private fun Welcome(modifier: Modifier, onOpen: () -> Unit) {
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("打开一个 .sql 文件", color = Tokens.Text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "解析在本机进程内完成，不上传、不连数据库",
            color = Tokens.TextFaint,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(16.dp))
        Chip("打开 SQL 文件", accent = true, onClick = onOpen)
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit, accent: Boolean = false) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (accent) Tokens.Accent.copy(alpha = 0.14f) else Tokens.SurfaceRaised, RoundedCornerShape(4.dp))
            .border(1.dp, if (accent) Tokens.Accent.copy(alpha = 0.5f) else Tokens.Border, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            color = if (accent) Tokens.Accent else Tokens.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
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
        modifier = Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Tokens.TextFaint, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@Composable
internal fun HDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Border))
}

@Composable
internal fun VDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Tokens.Border))
}
