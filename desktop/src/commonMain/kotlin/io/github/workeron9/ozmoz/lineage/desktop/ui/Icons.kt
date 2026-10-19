package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 三个自绘图标。**不引 `material-icons-*`**：为了三个图标多一个依赖，还要走 THIRD-PARTY
 * 登记（ADR-0022「后果与代价」一节的规矩）。画成 [ImageVector] 而不是图片：跟 `Icon` 的
 * tint 走，将来真做了明暗主题也不用两份资源。
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
