package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.workeron9.ozmoz.lineage.desktop.model.Analysis
import io.github.workeron9.ozmoz.lineage.desktop.model.PlacedColumn
import io.github.workeron9.ozmoz.lineage.desktop.model.distanceToSegment
import io.github.workeron9.ozmoz.lineage.desktop.model.layeredLayout

private val NodeWidth = 176.dp
private val NodeHeight = 28.dp
private val ColumnGap = 64.dp
private val RowGap = 8.dp
private val GraphPadding = 16.dp
private val EdgeHitThreshold = 6.dp
private val LabelStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Tokens.Text)

/**
 * 血缘图：**Canvas 自绘**（Compose 侧没有 React Flow 的等价物）。
 *
 * 编码：颜色 = 边类型，线型 = 变换种类（见 [Tokens]）；方向一律左 → 右（`depth` 即第几跳）。
 *
 * shortcut: D1 不做平移缩放与聚簇——内容画满后靠**双向滚动**看，点选做命中测试。
 * 节点/边到几百个时滚动与重绘都会变钝；D2 再上视口裁剪 + 按 scope 聚簇。
 */
@Composable
internal fun GraphPanel(
    analysis: Analysis,
    selectedColumn: String?,
    selectedFlow: Int?,
    onSelectColumn: (String?, Int?) -> Unit,
    onSelectFlow: (Int?, Int?) -> Unit,
) {
    val placed = remember(analysis.columns, analysis.flows) { layeredLayout(analysis.columns, analysis.flows) }
    if (placed.isEmpty()) {
        Centered(
            analysis.note ?: "这段 SQL 没有可建模的列级血缘（可能只含 DDL / MERGE 这类不产出列级血缘的语句）",
            if (analysis.note != null) Tokens.Danger else Tokens.TextMuted,
        )
        return
    }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val nodeWidth = with(density) { NodeWidth.toPx() }
    val nodeHeight = with(density) { NodeHeight.toPx() }
    val gapX = with(density) { ColumnGap.toPx() }
    val gapY = with(density) { RowGap.toPx() }
    val padding = with(density) { GraphPadding.toPx() }
    val maxDepth = placed.maxOf { it.depth }
    val maxRow = placed.maxOf { it.row }
    val contentWidth = padding * 2 + (maxDepth + 1) * nodeWidth + maxDepth * gapX
    val contentHeight = padding * 2 + (maxRow + 1) * nodeHeight + maxRow * gapY

    val boxes = remember(placed, density) {
        placed.associate { it.column.id to boxOf(it, nodeWidth, nodeHeight, gapX, gapY, padding) }
    }
    val labels = remember(placed, density) {
        placed.associate { placedColumn ->
            placedColumn.column.id to measurer.measure(
                text = placedColumn.column.qualifiedName,
                style = LabelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (nodeWidth - 14 * density.density).toInt()),
            )
        }
    }

    Column(Modifier.fillMaxSize().background(Tokens.Surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelHeader("血缘图 · ${placed.size} 列 / ${analysis.flows.size} 边 · 点节点或边看详情")
            Spacer(Modifier.weight(1f))
            Legend()
        }
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            Canvas(
                modifier = Modifier
                    .requiredSize(
                        with(density) { contentWidth.toDp() },
                        with(density) { contentHeight.toDp() },
                    )
                    .pointerInput(boxes, analysis.flows, selectedFlow) {
                        detectTapGestures { offset ->
                            val hitNode = placed.firstOrNull { boxes[it.column.id]?.contains(offset) == true }
                            if (hitNode != null) {
                                onSelectColumn(hitNode.column.id, hitNode.column.line)
                                return@detectTapGestures
                            }
                            var bestIndex = -1
                            var bestDistance = Float.MAX_VALUE
                            analysis.flows.forEachIndexed { index, flow ->
                                val from = boxes[flow.from] ?: return@forEachIndexed
                                val to = boxes[flow.to] ?: return@forEachIndexed
                                val points = edgePoints(from, to)
                                for (i in 0 until points.lastIndex) {
                                    val distance = distanceToSegment(
                                        offset.x,
                                        offset.y,
                                        points[i].x,
                                        points[i].y,
                                        points[i + 1].x,
                                        points[i + 1].y,
                                    )
                                    if (distance < bestDistance) {
                                        bestDistance = distance
                                        bestIndex = index
                                    }
                                }
                            }
                            val threshold = EdgeHitThreshold.toPx()
                            if (bestIndex >= 0 && bestDistance <= threshold) {
                                onSelectFlow(bestIndex, analysis.flows[bestIndex].line)
                            } else {
                                onSelectColumn(null, null)
                            }
                        }
                    },
            ) {
                val corner = CornerRadius(3.dp.toPx())

                // 先边后节点：节点盖住线头，省掉一轮裁剪。
                analysis.flows.forEachIndexed { index, flow ->
                    val from = boxes[flow.from] ?: return@forEachIndexed
                    val to = boxes[flow.to] ?: return@forEachIndexed
                    val points = edgePoints(from, to)
                    val dimmed = selectedFlow != null && selectedFlow != index
                    val color = Tokens.flowColor(flow.kind).copy(alpha = if (dimmed) 0.18f else 0.85f)
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1..points.lastIndex) lineTo(points[i].x, points[i].y)
                    }
                    drawPath(path, color, style = Tokens.flowStroke(flow.transform, density))
                    if (!dimmed) drawArrow(points.last(), color)
                }

                placed.forEach { placedColumn ->
                    val box = boxes.getValue(placedColumn.column.id)
                    val selected = placedColumn.column.id == selectedColumn
                    drawRoundRect(Tokens.SurfaceRaised, box.topLeft, box.size, corner)
                    if (placedColumn.column.isOutput) {
                        drawRoundRect(
                            color = Tokens.Accent.copy(alpha = 0.85f),
                            topLeft = box.topLeft,
                            size = Size(3.dp.toPx(), box.height),
                            cornerRadius = corner,
                        )
                    }
                    drawRoundRect(
                        color = if (selected) Tokens.Accent else Tokens.Border,
                        topLeft = box.topLeft,
                        size = box.size,
                        cornerRadius = corner,
                        style = Stroke(if (selected) 1.6f * density.density else 1f),
                    )
                    labels[placedColumn.column.id]?.let { layout ->
                        drawText(
                            layout,
                            topLeft = Offset(box.left + 8.dp.toPx(), box.top + (box.height - layout.size.height) / 2f),
                        )
                    }
                }
            }
        }
    }
}

private fun boxOf(
    placed: PlacedColumn,
    nodeWidth: Float,
    nodeHeight: Float,
    gapX: Float,
    gapY: Float,
    padding: Float,
): Rect = Rect(
    offset = Offset(padding + placed.depth * (nodeWidth + gapX), padding + placed.row * (nodeHeight + gapY)),
    size = Size(nodeWidth, nodeHeight),
)

/** 正交折线：从起点右侧拉到中线，竖走到目标行，再横进目标左侧。 */
private fun edgePoints(from: Rect, to: Rect): List<Offset> {
    val start = Offset(from.right, from.center.y)
    val end = Offset(to.left, to.center.y)
    val midX = (start.x + end.x) / 2f
    return listOf(start, Offset(midX, start.y), Offset(midX, end.y), end)
}

private fun DrawScope.drawArrow(tip: Offset, color: Color) {
    val size = 4.dp.toPx()
    val width = 1.dp.toPx()
    drawLine(color, Offset(tip.x - size, tip.y - size * 0.8f), tip, strokeWidth = width)
    drawLine(color, Offset(tip.x - size, tip.y + size * 0.8f), tip, strokeWidth = width)
}
