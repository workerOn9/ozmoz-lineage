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

    /**
     * 连接：数据库圆柱。**画成描边**——实心填充时，同样用黑色描边的两道环会被填色盖住，
     * 24dp 下就是一个圆角方块（离屏渲染实测）。
     */
    val Database: ImageVector = ImageVector.Builder(
        name = "Database",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
        ) {
            // 上椭圆
            moveTo(4.8f, 6.2f)
            curveTo(4.8f, 4.43f, 8.02f, 3f, 12f, 3f)
            curveTo(15.98f, 3f, 19.2f, 4.43f, 19.2f, 6.2f)
            curveTo(19.2f, 7.97f, 15.98f, 9.4f, 12f, 9.4f)
            curveTo(8.02f, 9.4f, 4.8f, 7.97f, 4.8f, 6.2f)
            close()
            // 左右侧壁
            moveTo(4.8f, 6.2f)
            lineTo(4.8f, 17.8f)
            moveTo(19.2f, 6.2f)
            lineTo(19.2f, 17.8f)
            // 两道环
            for (cy in listOf(10.6f, 15.0f)) {
                moveTo(4.8f, cy)
                curveTo(4.8f, cy + 1.77f, 8.02f, cy + 3.2f, 12f, cy + 3.2f)
                curveTo(15.98f, cy + 3.2f, 19.2f, cy + 1.77f, 19.2f, cy)
            }
            // 底
            moveTo(4.8f, 17.8f)
            curveTo(4.8f, 19.57f, 8.02f, 21f, 12f, 21f)
            curveTo(15.98f, 21f, 19.2f, 19.57f, 19.2f, 17.8f)
        }
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

    /**
     * 侧栏开关：面板图标（圆角矩形 + 左侧分隔线）。和导航项的线性图标同一套笔触，
     * 不再是漂在 header 中间的尖括号（用户对照 FlClash 后的反馈）。
     */
    val Sidebar: ImageVector = ImageVector.Builder(
        name = "Sidebar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 外框（圆角矩形）
            moveTo(5f, 4f)
            lineTo(19f, 4f)
            curveTo(20.1f, 4f, 21f, 4.9f, 21f, 6f)
            lineTo(21f, 18f)
            curveTo(21f, 19.1f, 20.1f, 20f, 19f, 20f)
            lineTo(5f, 20f)
            curveTo(3.9f, 20f, 3f, 19.1f, 3f, 18f)
            lineTo(3f, 6f)
            curveTo(3f, 4.9f, 3.9f, 4f, 5f, 4f)
            close()
            // 左侧分隔线
            moveTo(9f, 4f)
            lineTo(9f, 20f)
        }
    }.build()

    /** 侧栏折叠：一个尖括号（保留给别处用；侧栏开关已换成 [Sidebar]）。 */
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


