package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自绘图标（D2 起 4 个目的地）。**不引 `material-icons-*`**：多一个依赖还要走
 * THIRD-PARTY 登记（ADR-0022「后果与代价」）。画成 [ImageVector] 而不是图片：
 * 跟 `Icon` 的 tint 走，明暗主题不用两份资源。
 */
internal object Icons {

    /** 工作区：三根高低不一的柱子。 */
    val Workspace: ImageVector = ImageVector.Builder(
        name = "Workspace",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        rect(4f, 10f, 8f, 20f)
        rect(10f, 4f, 14f, 20f)
        rect(16f, 13f, 20f, 20f)
    }.build()

    /** 连接：数据库圆柱（上椭圆 + 侧壁 + 两道环）。 */
    val Database: ImageVector = ImageVector.Builder(
        name = "Database",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        ellipse(cx = 12f, cy = 6.2f, rx = 7.2f, ry = 3.2f)
        rect(4.8f, 6.2f, 19.2f, 16.4f)
        ring(cy = 10.6f, rx = 7.2f, ry = 3.2f)
        ring(cy = 14.6f, rx = 7.2f, ry = 3.2f)
    }.build()

    /** Agent：对话气泡（圆角方框 + 左下小尾巴）。 */
    val Agent: ImageVector = ImageVector.Builder(
        name = "Agent",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 5f)
            lineTo(20f, 5f)
            lineTo(20f, 16f)
            lineTo(10f, 16f)
            lineTo(6f, 20f)
            lineTo(6f, 16f)
            lineTo(4f, 16f)
            close()
        }
    }.build()

    /** 设置：三条带滑块的横线。 */
    val Settings: ImageVector = ImageVector.Builder(
        name = "Settings",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        rect(3f, 6f, 21f, 8f)
        rect(14f, 4f, 17f, 10f)
        rect(3f, 11f, 21f, 13f)
        rect(7f, 9f, 10f, 15f)
        rect(3f, 16f, 21f, 18f)
        rect(15f, 14f, 18f, 20f)
    }.build()

    /** 侧栏折叠：一个尖括号（收起态朝左）。 */
    val Chevron: ImageVector = ImageVector.Builder(
        name = "Chevron",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(14.5f, 6f)
            lineTo(9.5f, 12f)
            lineTo(14.5f, 18f)
        }
    }.build()

    /** 发送：右向箭头。 */
    val Send: ImageVector = ImageVector.Builder(
        name = "Send",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4f, 12f)
            lineTo(19f, 12f)
            moveTo(13f, 6f)
            lineTo(19f, 12f)
            lineTo(13f, 18f)
        }
    }.build()

    /** 停止：实心方块。 */
    val Stop: ImageVector = ImageVector.Builder(
        name = "Stop",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        rect(7f, 7f, 17f, 17f)
    }.build()

    /** 新建：加号。 */
    val Plus: ImageVector = ImageVector.Builder(
        name = "Plus",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        rect(11f, 4f, 13f, 20f)
        rect(4f, 11f, 20f, 13f)
    }.build()
}

private fun ImageVector.Builder.rect(x0: Float, y0: Float, x1: Float, y1: Float) {
    path(fill = SolidColor(Color.Black)) {
        moveTo(x0, y0)
        lineTo(x1, y0)
        lineTo(x1, y1)
        lineTo(x0, y1)
        close()
    }
}

private fun ImageVector.Builder.ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
    val k = 0.5523f
    path(fill = SolidColor(Color.Black)) {
        moveTo(cx - rx, cy)
        curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        close()
    }
}

private fun ImageVector.Builder.ring(cy: Float, rx: Float, ry: Float) {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.2f,
    ) {
        moveTo(rx * 2f, cy)
        curveTo(rx * 2f, cy + ry * 0.55f, rx * 1.55f, cy + ry, 12f, cy + ry)
        curveTo(rx * 0.45f, cy + ry, 12f - rx, cy + ry * 0.55f, 12f - rx, cy)
    }
}
