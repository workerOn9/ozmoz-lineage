package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import io.github.workeron9.ozmoz.lineage.desktop.model.FlowKind
import io.github.workeron9.ozmoz.lineage.desktop.model.FlowTransform

/**
 * 设计 token（ADR-0022 D1）。
 *
 * 定位：**深色实验室仪器**——装饰为零、颜色只编码数据语义、任何「未知」都要看得见。
 * 颜色只给边类型（[FlowKind]，6 种），变换种类（[FlowTransform]，12 种）走**线型**：
 * 颜色 × 线型两个独立通道，不给 12 种颜色（色盲不可辨）。
 * 字体统一系统等宽（不引 JetBrains Mono：字体文件是又一个要登记的依赖，D1 不值当）。
 */
internal object Tokens {

    val Background = Color(0xFF16181D)
    val Surface = Color(0xFF1E2128)
    val SurfaceRaised = Color(0xFF262A33)
    val Border = Color(0xFF343945)
    val Text = Color(0xFFE6E8EE)
    val TextMuted = Color(0xFF9AA1B0)
    val TextFaint = Color(0xFF6B7280)
    val Accent = Color(0xFF4FC1E9)
    val Danger = Color(0xFFF78C6C)
    val Warning = Color(0xFFF7B84B)

    /** 边色 = [FlowKind]（六类）。 */
    fun flowColor(kind: FlowKind): Color = when (kind) {
        FlowKind.OUTPUT -> Color(0xFF4FC1E9)
        FlowKind.PREDICATE -> Color(0xFFF7B84B)
        FlowKind.JOIN_KEY -> Color(0xFFC792EA)
        FlowKind.GROUP_BY -> Color(0xFF9CCC65)
        FlowKind.ORDER_BY -> Color(0xFFF78C6C)
        FlowKind.SOURCE -> Color(0xFF7C8698)
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

@Composable
internal fun DesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Tokens.Background,
            surface = Tokens.Surface,
            surfaceVariant = Tokens.SurfaceRaised,
            onSurface = Tokens.Text,
            onSurfaceVariant = Tokens.TextMuted,
            outline = Tokens.Border,
            primary = Tokens.Accent,
            error = Tokens.Danger,
        ),
        content = content,
    )
}
