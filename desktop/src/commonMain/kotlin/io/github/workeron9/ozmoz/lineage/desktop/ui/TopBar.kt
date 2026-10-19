package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.workeron9.ozmoz.lineage.desktop.EngineGateway
import io.github.workeron9.ozmoz.lineage.desktop.model.Analysis
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionStatus

/**
 * 顶栏：**文档级**信息与操作（文件名、统计、引擎、容错、打开文件）。
 *
 * D2 起引擎与容错从设置挪回顶栏——它们直接决定当前这份结果，是文档级开关；
 * 设置页因此只留应用级（主题 / 密度 / Provider）。同一个开关只有一个家。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun OzmlTopBar(state: WorkbenchState) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    TopAppBar(
        title = {
            Column {
                Text(
                    titleLine(state),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitleLine(state),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = tokens.textMuted,
                )
            }
        },
        actions = {
            if (state.page == Page.WORKSPACE) {
                EngineSelector(state)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("容错", fontSize = density.bodySize, color = tokens.textMuted)
                    Spacer(Modifier.width(6.dp))
                    Switch(checked = state.tolerant, onCheckedChange = { state.tolerant = it })
                }
                Button(onClick = { state.pickFile() }) { Text("打开 SQL 文件") }
                Spacer(Modifier.width(8.dp))
            }
            ConnectionIndicator(state)
            Spacer(Modifier.width(12.dp))
        },
    )
}

@Composable
private fun EngineSelector(state: WorkbenchState) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { menuOpen = true }) {
            Text(state.engineId, fontFamily = FontFamily.Monospace)
            Text("  ▾")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            for (id in EngineGateway.engineIds) {
                DropdownMenuItem(
                    text = { Text(id, fontFamily = FontFamily.Monospace) },
                    onClick = {
                        state.engineId = id
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/** 连接状态指示器：绿点 = 有一条连接在线；点它去连接页。 */
@Composable
private fun ConnectionIndicator(state: WorkbenchState) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    val online = state.store.connections.filter { state.connectionRuntime(it.id).status == ConnectionStatus.ONLINE }
    val active = state.activeConnectionId?.let { id -> state.store.connections.firstOrNull { it.id == id } }
    val color = when {
        online.isNotEmpty() -> tokens.flowColor(io.github.workeron9.ozmoz.lineage.desktop.model.FlowKind.GROUP_BY)
        state.store.connections.isNotEmpty() -> tokens.textFaint
        else -> tokens.textFaint
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { state.page = Page.CONNECTION }.padding(horizontal = 6.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            active?.name ?: if (state.store.connections.isEmpty()) "无连接" else "${online.size} 条在线",
            fontSize = density.bodySize,
            color = tokens.textMuted,
        )
    }
}

private fun titleLine(state: WorkbenchState): String = when (state.page) {
    Page.WORKSPACE -> state.opened?.name ?: "未打开文件"
    Page.CONNECTION -> "连接"
    Page.AGENT -> "Agent"
    Page.SETTINGS -> "设置"
}

private fun subtitleLine(state: WorkbenchState): String = when (state.page) {
    Page.WORKSPACE -> statLine(state.analysis, state)
    Page.CONNECTION -> "${state.store.connections.size} 条连接 · 只读元数据"
    Page.AGENT -> {
        val provider = state.enabledProviders().firstOrNull { it.id == state.agentProviderId }
        val session = if (provider == null) "未启用 Provider" else provider.name
        val running = if (state.agentBusy) " · 运行中" else ""
        "$session · ${state.activities.size} 条活动$running"
    }

    Page.SETTINGS -> "主题与密度 · Provider 管理"
}

/** 顶栏副标题：始终说清「这份结果是谁跑出来的」——空图不许冒充「没问题」。 */
private fun statLine(analysis: Analysis?, state: WorkbenchState): String {
    val current = analysis ?: return "未分析"
    return buildString {
        append(current.engineId)
        state.activeConnectionId?.let { id ->
            state.store.connections.firstOrNull { it.id == id }?.let { append(" · schema ").append(it.name) }
        }
        append(" · 列 ").append(current.columns.size)
        append(" · 边 ").append(current.flows.size)
        append(" · 未知 ").append(current.unknowns.size)
        append(" · 诊断 ").append(current.issues.size)
        append(" · ").append(current.elapsedMillis).append("ms")
    }
}
