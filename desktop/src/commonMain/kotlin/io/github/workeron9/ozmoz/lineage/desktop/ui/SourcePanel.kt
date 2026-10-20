package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 源 SQL 插槽：**只读浏览**（行号 + 当前行高亮 + 联动跳行）和**编辑**两态，
 * 编辑时停手 350ms 自动重跑分析。
 *
 * ADR-0022 D0 还没实测编辑器选型（自写 Compose 文本控件 vs 内嵌 WebView 装 Monaco），
 * 所以这一版只做到「能打字 / 能粘 / 能看」：换实现时只换这个文件，布局与状态都不动。
 */
@Composable
internal fun SourcePanel(state: WorkbenchState, modifier: Modifier) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    val opened = state.opened ?: return
    val sql = opened.sql

    // 停手再分析：每次编辑 +1，key 变了就取消上一次的等待。
    LaunchedEffect(state.sqlRevision) {
        if (state.sqlRevision > 0) {
            delay(350)
            state.runAnalysis()
        }
    }

    Column(modifier.background(tokens.surface)) {
        // 不能复用 PanelHeader：它自带 fillMaxWidth，塞进 Row 会把右侧开关挤出去。
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                if (state.editing) "源 SQL · 编辑" else "源 SQL",
                color = tokens.textFaint,
                fontFamily = FontFamily.Monospace,
                fontSize = density.bodySize,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text(
                if (state.editing) "完成 / 只读" else "编辑 / 粘贴",
                color = tokens.accent,
                fontFamily = FontFamily.Monospace,
                fontSize = density.bodySize,
                modifier = Modifier.clickable { state.editing = !state.editing },
            )
        }
        if (state.editing) {
            BasicTextField(
                value = sql,
                onValueChange = { state.editSql(it) },
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                textStyle = TextStyle(
                    color = tokens.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = density.bodySize,
                    lineHeight = density.bodyLineHeight,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(tokens.accent),
            )
        } else {
            ReadOnlySql(sql = sql, focusLine = state.focusLine)
        }
    }
}

/** 只读态：行号 + 当前行高亮 + 点节点跳过来时滚到那一行。 */
@Composable
private fun ReadOnlySql(sql: String, focusLine: Int?) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    val lines = remember(sql) { sql.split('\n') }
    val listState = rememberLazyListState()

    LaunchedEffect(focusLine) {
        val target = focusLine?.minus(1)?.coerceIn(0, maxOf(lines.lastIndex, 0))
        if (target != null) listState.animateScrollToItem(target)
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        itemsIndexed(lines) { index, text ->
            val focused = focusLine == index + 1
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(if (focused) tokens.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(vertical = if (density.gap > 8.dp) 2.dp else 1.dp),
            ) {
                Text(
                    (index + 1).toString(),
                    modifier = Modifier.width(46.dp).padding(end = 10.dp),
                    color = if (focused) tokens.accent else tokens.textFaint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = density.bodySize,
                    lineHeight = density.bodyLineHeight,
                    textAlign = TextAlign.End,
                )
                Text(
                    text.ifEmpty { " " },
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    color = tokens.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = density.bodySize,
                    lineHeight = density.bodyLineHeight,
                )
            }
        }
    }
}
