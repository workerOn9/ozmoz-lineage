package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.workeron9.ozmoz.lineage.desktop.model.Density as AppDensity
import io.github.workeron9.ozmoz.lineage.desktop.model.FlowKind
import io.github.workeron9.ozmoz.lineage.desktop.model.FlowTransform
import io.github.workeron9.ozmoz.lineage.desktop.model.ThemeMode

/**
 * 设计 token（ADR-0022 D1 / D2 扩明暗两套）。
 *
 * 定位：**实验室仪器**——装饰为零、颜色只编码数据语义、任何「未知」都要看得见。
 * 颜色只给边类型（[FlowKind]，6 种），变换种类（[FlowTransform]，12 种）走**线型**：
 * 颜色 × 线型两个独立通道，不给 12 种颜色（色盲不可辨）。
 * 字体统一系统等宽（不引 JetBrains Mono：字体文件是又一个要登记的依赖）。
 *
 * D2 起 token 是**实例**（浅 / 深各一份），经 [LocalTokens] 提供：画布层在组合期取一次
 * 再带进 draw lambda，非 composable 的绘制代码不需要感知主题。
 */
internal data class OzmlTokens(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val danger: Color,
    val warning: Color,
) {
    /** 边色 = [FlowKind]（六类）；深浅两套各自调饱和度，保证可读。 */
    fun flowColor(kind: FlowKind): Color = when (kind) {
        FlowKind.OUTPUT -> if (isDark) Color(0xFF4FC1E9) else Color(0xFF00677D)
        FlowKind.PREDICATE -> if (isDark) Color(0xFFF7B84B) else Color(0xFF7D5800)
        FlowKind.JOIN_KEY -> if (isDark) Color(0xFFC792EA) else Color(0xFF7B4FA2)
        FlowKind.GROUP_BY -> if (isDark) Color(0xFF9CCC65) else Color(0xFF4A7C2A)
        FlowKind.ORDER_BY -> if (isDark) Color(0xFFF78C6C) else Color(0xFFB84524)
        FlowKind.SOURCE -> if (isDark) Color(0xFF7C8698) else Color(0xFF5A6470)
    }

    /**
     * 线段样式 = [FlowTransform]。线宽与虚线间隔都乘 density：token 是 dp，画布吃 px。
     *
     * 12 种变换归成 4 档粗细 + 6 种虚线段：
     * - 值血缘（DIRECT / EXPRESSION）实线，粗细区分直传与表达式派生；
     * - 聚合 / 窗口 / 分支 / 常量各有虚线段；
     * - 只影响结果集不改变值的（谓词 / 分组 / 排序）统一细虚线，靠**边色**区分；
     * - [FlowTransform.UNKNOWN] 最细的点线：明确说「这条边没推出来」，不藏。
     */
    fun flowStroke(transform: FlowTransform, density: Density): Stroke {
        val scale = density.density
        fun stroke(widthDp: Float, dash: FloatArray? = null): Stroke = Stroke(
            width = widthDp * scale,
            cap = StrokeCap.Round,
            pathEffect = dash?.let { PathEffect.dashPathEffect(it.map { interval -> interval * scale }.toFloatArray()) },
        )
        return when (transform) {
            FlowTransform.DIRECT -> stroke(1.2f)
            FlowTransform.EXPRESSION -> stroke(2.2f)
            FlowTransform.AGGREGATE -> stroke(2.2f, floatArrayOf(9f, 4f))
            FlowTransform.WINDOW -> stroke(2.2f, floatArrayOf(1.5f, 3f))
            FlowTransform.CASE_BRANCH -> stroke(2.2f, floatArrayOf(8f, 3f, 1.5f, 3f))
            FlowTransform.CONSTANT -> stroke(1.2f, floatArrayOf(3f, 3f))
            FlowTransform.JOIN_KEY -> stroke(1.8f, floatArrayOf(6f, 3f))
            FlowTransform.FILTER_PREDICATE -> stroke(1.2f, floatArrayOf(2f, 2.5f))
            FlowTransform.GROUPING -> stroke(1.2f, floatArrayOf(2f, 2.5f))
            FlowTransform.ORDERING -> stroke(1.2f, floatArrayOf(2f, 2.5f))
            FlowTransform.SOURCE -> stroke(1f)
            FlowTransform.UNKNOWN -> stroke(1f, floatArrayOf(1f, 3f))
        }
    }
}

internal val DarkTokens = OzmlTokens(
    isDark = true,
    background = Color(0xFF0F1416),
    surface = Color(0xFF14191B),
    surfaceRaised = Color(0xFF242A2C),
    border = Color(0xFF3F484B),
    text = Color(0xFFDEE3E5),
    textMuted = Color(0xFFBEC8CB),
    textFaint = Color(0xFF899295),
    accent = Color(0xFF4FC1E9),
    danger = Color(0xFFFFB4AB),
    warning = Color(0xFFF7B84B),
)

internal val LightTokens = OzmlTokens(
    isDark = false,
    background = Color(0xFFF5FAFC),
    surface = Color(0xFFEFF4F6),
    surfaceRaised = Color(0xFFFFFFFF),
    border = Color(0xFFBEC8CB),
    text = Color(0xFF171C1E),
    textMuted = Color(0xFF3F484B),
    textFaint = Color(0xFF6F797B),
    accent = Color(0xFF00677D),
    danger = Color(0xFFBA1A1A),
    warning = Color(0xFF7D5800),
)

internal val LocalTokens = staticCompositionLocalOf { DarkTokens }

/** 密度：影响字号 / 行高 / 间距（D2 设计指导 3.1）。 */
internal val LocalUiDensity = staticCompositionLocalOf { AppDensity.COMPACT }

/** 面板内边距：Compact 4–8dp，Comfortable 12–16dp。 */
internal val AppDensity.gap: Dp
    get() = if (this == AppDensity.COMPACT) 6.dp else 14.dp

/** 正文字号：Compact 11sp，Comfortable 13sp（行高按 1.2x / 1.5x 推）。 */
internal val AppDensity.bodySize: TextUnit
    get() = if (this == AppDensity.COMPACT) 11.sp else 13.sp

internal val AppDensity.bodyLineHeight: TextUnit
    get() = if (this == AppDensity.COMPACT) 14.sp else 20.sp

internal val AppDensity.titleSize: TextUnit
    get() = if (this == AppDensity.COMPACT) 13.sp else 15.sp


/** M3 浅色配色（取值见 D2 设计指导 3.1）。 */
private val OzmlLightScheme = lightColorScheme(
    primary = Color(0xFF00677D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB3ECFF),
    onPrimaryContainer = Color(0xFF001F27),
    secondary = Color(0xFF4A6269),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE7EE),
    onSecondaryContainer = Color(0xFF051F24),
    tertiary = Color(0xFF7D5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA8),
    onTertiaryContainer = Color(0xFF271900),
    background = Color(0xFFF5FAFC),
    onBackground = Color(0xFF171C1E),
    surface = Color(0xFFF5FAFC),
    onSurface = Color(0xFF171C1E),
    surfaceVariant = Color(0xFFDBE4E7),
    onSurfaceVariant = Color(0xFF3F484B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF4F6),
    surfaceContainer = Color(0xFFE3EBEE),
    surfaceContainerHigh = Color(0xFFD8E1E4),
    surfaceContainerHighest = Color(0xFFD2DBDE),
    outline = Color(0xFF6F797B),
    outlineVariant = Color(0xFFBEC8CB),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

/** M3 深色配色（取值见 D2 设计指导 3.1）。 */
private val OzmlDarkScheme = darkColorScheme(
    primary = Color(0xFF4FC1E9),
    onPrimary = Color(0xFF003542),
    primaryContainer = Color(0xFF004D60),
    onPrimaryContainer = Color(0xFFB3ECFF),
    secondary = Color(0xFFB1CBD4),
    onSecondary = Color(0xFF1C343A),
    secondaryContainer = Color(0xFF334B51),
    onSecondaryContainer = Color(0xFFCDE7EE),
    tertiary = Color(0xFFF7B84B),
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5D4200),
    onTertiaryContainer = Color(0xFFFFDEA8),
    background = Color(0xFF0F1416),
    onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF0F1416),
    onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3F484B),
    onSurfaceVariant = Color(0xFFBEC8CB),
    surfaceContainerLowest = Color(0xFF0A0F10),
    surfaceContainerLow = Color(0xFF14191B),
    surfaceContainer = Color(0xFF1A2022),
    surfaceContainerHigh = Color(0xFF242A2C),
    surfaceContainerHighest = Color(0xFF2F3537),
    outline = Color(0xFF899295),
    outlineVariant = Color(0xFF3F484B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * 主题入口。`mode = SYSTEM` 时跟随系统深浅；token 与 M3 配色**同源**，
 * 壳层与自绘仪器层不会各走一套色。
 */
@Composable
internal fun DesktopTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalTokens provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(colorScheme = if (dark) OzmlDarkScheme else OzmlLightScheme, content = content)
    }
}
