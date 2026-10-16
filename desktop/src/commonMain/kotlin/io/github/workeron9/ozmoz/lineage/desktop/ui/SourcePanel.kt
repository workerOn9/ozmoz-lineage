package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 源 SQL 的**只读插槽**：带行号 + 只读文本 + 当前行高亮。
 *
 * ADR-0022 D0 还没实测编辑器选型（自写 Compose 文本控件 vs 内嵌 WebView 装 Monaco），
 * 所以这一版故意什么都不做：能看、能滚到某一行（联动用）就够了。
 * 换实现时只换这个文件，[App] 的布局与状态都不动。
 */
@Composable
internal fun SourcePanel(sql: String, focusLine: Int?, modifier: Modifier) {
    val lines = remember(sql) { sql.split('\n') }
    val listState = rememberLazyListState()

    LaunchedEffect(focusLine) {
        val target = focusLine?.minus(1)?.coerceIn(0, maxOf(lines.lastIndex, 0))
        if (target != null) listState.animateScrollToItem(target)
    }

    Column(modifier.background(Tokens.Surface)) {
        PanelHeader("源 SQL · 只读插槽")
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(lines) { index, text ->
                val focused = focusLine == index + 1
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(if (focused) Tokens.Accent.copy(alpha = 0.12f) else Color.Transparent)
                        .padding(vertical = 1.dp),
                ) {
                    Text(
                        (index + 1).toString(),
                        modifier = Modifier.width(46.dp).padding(end = 10.dp),
                        color = if (focused) Tokens.Accent else Tokens.TextFaint,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        textAlign = TextAlign.End,
                    )
                    Text(
                        text.ifEmpty { " " },
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        color = Tokens.Text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}
