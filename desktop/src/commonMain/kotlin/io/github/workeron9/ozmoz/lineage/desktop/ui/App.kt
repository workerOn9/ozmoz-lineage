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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.workeron9.ozmoz.lineage.desktop.OpenedFile
import io.github.workeron9.ozmoz.lineage.desktop.StoreGateway

/**
 * 桌面外壳（D2）：可折叠侧栏（自绘容器，见 [OzmlRail]）+ 顶栏 + **四个目的地**
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
public fun App(initial: OpenedFile? = null, hiddenTitleBar: Boolean = false) {
    val state = remember { WorkbenchState(initial, StoreGateway.load()) }
    DesktopTheme(mode = state.store.theme) {
        CompositionLocalProvider(LocalUiDensity provides state.store.density) {
            // 文档内容由 openFile / 编辑防抖各自触发分析；这里只管文档级开关（换引擎 / 容错 / schema）。
            LaunchedEffect(state.engineId, state.tolerant, state.activeConnectionId) {
                state.runAnalysis()
            }
            LaunchedEffect(Unit) { state.scanAgents() }
            AppContent(state, hiddenTitleBar = hiddenTitleBar)
        }
    }
}

/** 低于这个宽度时顶栏进入 compact（去掉文字标签），侧栏仍由用户自己决定展开 / 收起。 */
private val CompactTopBarBelow = 1024.dp

/** 壳层内容（主题与副作用在外面）：离屏渲染与 UI 测试直接驱动它。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AppContent(
    state: WorkbenchState,
    initialRailExpanded: Boolean = true,
    hiddenTitleBar: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullTopBar = maxWidth >= CompactTopBarBelow
        var userExpanded by remember { mutableStateOf(initialRailExpanded) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                OzmlRail(
                    expanded = userExpanded,
                    page = state.page,
                    hiddenTitleBar = hiddenTitleBar,
                    onToggle = { userExpanded = !userExpanded },
                    onPage = { state.page = it },
                )
                VerticalDivider()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // 红绿灯条高度（28dp）让出来：顶栏的按钮不要压在标题栏的可拖拽区域上。
                    if (hiddenTitleBar) Spacer(Modifier.height(28.dp))
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

/**
 * 侧栏容器。**不再用 M3 的 [WideNavigationRail]**：它的展开宽度按子项自适应、项指示器
 * 只包内容、收起动画又和内容各自独立——三件事叠在一起会出现「内容已收起、栏还宽着」
 * 的中间帧（用户截图抓到的就是这个）。
 *
 * 这里宽度与内容由**同一个** `animateDpAsState` 驱动：任何一帧的比例都是对的。
 * 颜色、命中语义仍取 M3（`surface` / `secondaryContainer` / `selectable` + `Tab`）。
 */
@Composable
private fun OzmlRail(
    expanded: Boolean,
    page: Page,
    hiddenTitleBar: Boolean,
    onToggle: () -> Unit,
    onPage: (Page) -> Unit,
) {
    val width by animateDpAsState(
        targetValue = if (expanded) 220.dp else 96.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "railWidth",
    )
    Surface(
        modifier = Modifier.width(width).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            if (hiddenTitleBar) {
                // 顶部整行让给红绿灯（用户 2026-10-20 的要求）：开关下移一行，
                // 展开 / 收起态都从这里开始。
                Spacer(Modifier.height(36.dp))
            }
            RailHeader(expanded, onToggle)
            Spacer(Modifier.height(8.dp))
            for (destination in Page.entries) {
                RailDestination(
                    destination = destination,
                    selected = page == destination,
                    expanded = expanded,
                    railWidth = width,
                    onClick = { onPage(destination) },
                )
            }
        }
    }
}

@Composable
private fun RailHeader(expanded: Boolean, onToggle: () -> Unit) {
    // 横向：展开态 20dp 起（与导航项胶囊同一条竖线），收起态居中。
    Box(
        Modifier.fillMaxWidth().height(48.dp),
        contentAlignment = if (expanded) Alignment.CenterStart else Alignment.Center,
    ) {
        IconButton(
            onClick = onToggle,
            // 展开态与项胶囊同一条竖线（20dp）；收起态居中。
            modifier = Modifier.padding(start = if (expanded) 20.dp else 0.dp),
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
    railWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalUiDensity.current
    // FlClash 的选中态：中性灰胶囊 + 左缘一条主色小竖条（不是整块主色容器）。
    val contentColor = if (selected) colors.onSurface else colors.onSurfaceVariant
    val indicator = if (selected) colors.surfaceContainerHighest else Color.Transparent
    // 胶囊始终占「栏宽 - 40dp」：展开态 180dp（左右各 20dp），收起态 56dp（居中）。
    // 宽度跟着容器的动画走，所以收起过程中不会出现「内容先窄、栏还宽」的空档。
    val itemHeight = if (expanded) 52.dp else 44.dp
    val shape = if (expanded) RoundedCornerShape(24.dp) else RoundedCornerShape(16.dp)

    Box(Modifier.width(railWidth).height(itemHeight)) {
        // 选中竖条：贴栏左缘，始终在胶囊之外（FlClash 的视觉锚点）。
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .width(3.dp)
                    .height(24.dp)
                    .background(colors.primary, RoundedCornerShape(2.dp)),
            )
        }
        Row(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 2.dp)
                // clip 必须在 selectable 之前：hover / ripple 是 selectable 的 indication，
                // 不裁的话胶囊外会多出一个方形高亮框（用户 2026-10-20 截图）。
                .clip(shape)
                .background(indicator, shape)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                .padding(horizontal = if (expanded) 16.dp else 0.dp),
            // 收起态图标在胶囊里**居中**（不能在 Row 里靠 Start 排，会偏左）。
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(if (expanded) 20.dp else 24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    destination.icon,
                    contentDescription = destination.label,
                    tint = contentColor,
                    modifier = Modifier.size(if (expanded) 20.dp else 24.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Text(
                    destination.label,
                    color = contentColor,
                    // 与图标同一视觉重量：之前 11sp 配 22dp 图标显得「图标大、文字小」。
                    fontSize = density.titleSize,
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
