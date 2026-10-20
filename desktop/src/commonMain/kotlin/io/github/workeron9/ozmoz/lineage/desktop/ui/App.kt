package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.WideNavigationRail

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** 低于这个宽度时顶栏进入 compact（去掉文字标签），侧栏仍由用户自己决定展开 / 收起。 */
private val CompactTopBarBelow = 1024.dp

/** 壳层内容（主题与副作用在外面）：离屏渲染与 UI 测试直接驱动它。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AppContent(state: WorkbenchState, initialRailExpanded: Boolean = true) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullTopBar = maxWidth >= CompactTopBarBelow
        // 展开/收起由用户决定（FlClash 的做法）：窄窗也保留开关，不做强制收起——
        // 强制收起会让「收起态」失去展开的入口。窄窗只把顶栏切到 compact。
        var userExpanded by remember { mutableStateOf(initialRailExpanded) }
        val railExpanded = userExpanded
        // 初值就按目标态：否则第一帧是展开的，窄窗上会闪一下 220dp 的侧栏。
        val railState = rememberWideNavigationRailState(
            if (railExpanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed,
        )
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
                    header = {
                        // 展开 / 收起态都保留这个入口（FlClash 同款）；窄窗也允许手动展开。
                        RailHeader(railIsExpanded) { userExpanded = !userExpanded }
                    },
                ) {
                    for (destination in Page.entries) {
                        RailDestination(
                            destination = destination,
                            selected = state.page == destination,
                            expanded = railIsExpanded,
                            onClick = { state.page = destination },
                        )
                    }
                }
                VerticalDivider()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    OzmlTopBar(state, compact = !fullTopBar)
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
}

@Composable
private fun RailHeader(expanded: Boolean, onToggle: () -> Unit) {
    // **不能** fillMaxWidth：WideNavigationRail 用最宽子项算展开宽度，header 撑满整窗时
    // 侧栏会一路涨到 360dp 上限（D2 首版实测）。限制在展开态最小宽度 220dp。
    //
    // 对齐：展开态和导航项图标同一条竖线（item 的水平内边距是 20dp，48dp 按钮的中心
    // 因此落在 44dp）；收起态整条 96dp，按钮居中。图标也用同一套线性笔触（FlClash 的
    // 参照：顶部是面板图标，不漂在中间）。
    // 宽度必须跟着侧栏状态走：收起态若仍按 220dp 量，图标会被 96dp 的容器裁掉
    // （第一版收起态顶部空白就是这个原因）。
    val headerWidth by animateDpAsState(if (expanded) 220.dp else 96.dp, label = "railHeaderWidth")
    // 与导航项图标同一条竖线：展开态图标中心 ≈44dp（item 水平内边距 20 + 图标半径），
    // 收起态整条 96dp、项居中（图标中心 48）。
    Box(
        Modifier.width(headerWidth).height(48.dp),
        contentAlignment = if (expanded) Alignment.CenterStart else Alignment.Center,
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.padding(start = if (expanded) 23.dp else 0.dp),
        ) {
            Icon(
                Icons.Sidebar,
                contentDescription = if (expanded) "收起侧栏" else "展开侧栏",
            )
        }
    }
}

/**
 * 侧栏目的地：**自绘的整行胶囊**，不是 M3 的 [WideNavigationRailItem]。
 *
 * 原因：M3 的 `WideNavigationRailItem` 指示器只包住图标 + 文字，展开态右侧会留出一大块
 * 空白（用户 2026-10-20 看截图指出「右边太多留白」）；FlClash 那种参照是整行胶囊。
 * 这里保留 M3 的颜色角色（`secondaryContainer` / `onSurfaceVariant`）与语义（selectable + Tab），
 * 只把「指示器宽度」改成随行宽。
 *
 * 宽度：展开态 200dp（rail 用「最宽子项 + 20dp」算总宽 → 220dp，右侧预留的 20dp 用
 * 起始内边距抵消，胶囊左右各留 20dp）；收起态 96dp（rail 固定 96dp），图标居中、不挂文字。
 */
@Composable
private fun RailDestination(
    destination: Page,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalUiDensity.current
    val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
    val indicator = if (selected) colors.secondaryContainer else Color.Transparent
    val itemWidth = if (expanded) 200.dp else 96.dp
    val itemHeight = if (expanded) 52.dp else 44.dp
    // 展开态：item 200dp + rail 预留的 20dp = 220dp 总宽；胶囊从 20dp 起、到 item 末尾，
    // 于是 220dp 里左右各留 20dp。收起态：item 96dp，胶囊 56×40 居中。
    val outerStart = 20.dp
    val outerEnd = if (expanded) 0.dp else 20.dp
    val shape = if (expanded) RoundedCornerShape(24.dp) else RoundedCornerShape(14.dp)

    Box(Modifier.width(itemWidth).height(itemHeight)) {
        Row(
            modifier = Modifier.fillMaxSize()
                .padding(start = outerStart, end = outerEnd, top = 2.dp, bottom = 2.dp)
                .background(indicator, shape)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                .padding(horizontal = if (expanded) 16.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(if (expanded) 22.dp else 24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    destination.icon,
                    contentDescription = destination.label,
                    tint = contentColor,
                    modifier = Modifier.size(if (expanded) 22.dp else 24.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.width(10.dp))
                Text(
                    destination.label,
                    color = contentColor,
                    fontSize = density.bodySize,
                    maxLines = 1,
                )
            }
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
