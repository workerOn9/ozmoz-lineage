package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.workeron9.ozmoz.lineage.desktop.OpenedFile
import io.github.workeron9.ozmoz.lineage.desktop.StoreGateway

/**
 * 桌面外壳（D2）：M3 [WideNavigationRail]（可折叠）+ 顶栏 + **四个目的地**
 * WORKSPACE / CONNECTION / AGENT / SETTINGS。
 *
 * 分工：侧栏管应用级路由，顶栏管文档级信息与开关（引擎 / 容错 / 连接指示），
 * 设置页管应用级外观与 Provider。状态的宿主是 [WorkbenchState]，平台副作用全部
 * 收在三个 expect 门面后（引擎 / JDBC / agent 进程）。
 *
 * 血缘图仍是自绘 Canvas：图的可交互水平（平移缩放 / 聚簇 / 虚拟化）是 ADR-0022
 * D0-② 探针的事，不是壳层的事。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
public fun App(initial: OpenedFile? = null) {
    val state = remember { WorkbenchState(initial, StoreGateway.load()) }
    DesktopTheme(mode = state.store.theme) {
        CompositionLocalProvider(LocalUiDensity provides state.store.density) {
            LaunchedEffect(state.opened, state.engineId, state.tolerant, state.activeConnectionId) {
                state.runAnalysis()
            }
            LaunchedEffect(Unit) { state.scanAgents() }
            AppContent(state)
        }
    }
}

/** 壳层内容（主题与副作用在外面）：离屏渲染与 UI 测试直接驱动它。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AppContent(state: WorkbenchState) {
    var railExpanded by remember { mutableStateOf(true) }
    val railState = rememberWideNavigationRailState(WideNavigationRailValue.Expanded)
    LaunchedEffect(railExpanded) {
        if (railExpanded) railState.expand() else railState.collapse()
    }
    val railIsExpanded = railState.currentValue == WideNavigationRailValue.Expanded

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize()) {
            WideNavigationRail(
                state = railState,
                // 缺省是 Arrangement.Center：桌面侧栏里「目的地」要贴顶。
                arrangement = Arrangement.Top,
                header = { RailHeader(railIsExpanded) { railExpanded = !railExpanded } },
            ) {
                for (destination in Page.entries) {
                    WideNavigationRailItem(
                        selected = state.page == destination,
                        onClick = { state.page = destination },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                        railExpanded = railIsExpanded,
                    )
                }
            }
            VerticalDivider()
            Column(Modifier.weight(1f).fillMaxHeight()) {
                OzmlTopBar(state)
                when (state.page) {
                    Page.WORKSPACE -> WorkspacePage(state)
                    Page.CONNECTION -> ConnectionPage(state)
                    Page.AGENT -> AgentPage(state)
                    Page.SETTINGS -> SettingsPage(state)
                }
            }
        }
    }
}

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

/** 居中的一行说明（空态 / 加载态共用）。 */
@Composable
internal fun Centered(text: String, color: Color) {
    val density = LocalUiDensity.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = density.bodySize)
    }
}
