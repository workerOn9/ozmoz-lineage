package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.workeron9.ozmoz.lineage.desktop.model.Analysis
import io.github.workeron9.ozmoz.lineage.desktop.model.TreeRow
import io.github.workeron9.ozmoz.lineage.desktop.model.visibleTreeRows

/**
 * 归一化树：`LazyColumn` 拍平渲染（树没有跨节点连线，白拿虚拟化），不用 Canvas 自绘。
 * 点一行 → 源 SQL 滚到该节点所在行（D1 的联动只做到这一层；选区反查与 diff 留 D2）。
 */
@Composable
internal fun TreePanel(
    analysis: Analysis,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    onFocusLine: (Int?) -> Unit,
) {
    val tokens = LocalTokens.current
    val rows = remember(analysis.tree, collapsed) { visibleTreeRows(analysis.tree, collapsed) }

    if (rows.isEmpty()) {
        Centered(analysis.note ?: "这段 SQL 没有归一化树", if (analysis.note != null) tokens.danger else tokens.textMuted)
        return
    }

    Column(Modifier.fillMaxSize().background(tokens.surface)) {
        PanelHeader("归一化树 · ${rows.size} 节点")
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows.size, key = { rows[it].node.id }) { index ->
                TreeLine(rows[index], collapsed, onToggle, onFocusLine)
            }
        }
    }
}

@Composable
private fun TreeLine(
    row: TreeRow,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    onFocusLine: (Int?) -> Unit,
) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onFocusLine(row.node.line) }
            .padding(
                start = (6 + row.depth * 14).dp,
                end = 10.dp,
                top = if (density.gap > 8.dp) 4.dp else 3.dp,
                bottom = if (density.gap > 8.dp) 4.dp else 3.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(16.dp)
                .clickable(enabled = row.expandable) { onToggle(row.node.id) },
        ) {
            if (row.expandable) {
                Text(
                    if (row.node.id in collapsed) "▸" else "▾",
                    color = tokens.textMuted,
                    fontSize = density.bodySize,
                )
            }
        }
        Text(row.node.type, color = tokens.accent, fontFamily = FontFamily.Monospace, fontSize = density.bodySize)
        Spacer(Modifier.width(8.dp))
        Text(
            row.node.text.replace('\n', ' ').take(400),
            modifier = Modifier.weight(1f),
            color = if (row.node.text.isBlank()) tokens.textFaint else tokens.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = density.bodySize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        row.node.line?.let {
            Text("L$it", color = tokens.textFaint, fontFamily = FontFamily.Monospace, fontSize = density.bodySize)
        }
    }
}
