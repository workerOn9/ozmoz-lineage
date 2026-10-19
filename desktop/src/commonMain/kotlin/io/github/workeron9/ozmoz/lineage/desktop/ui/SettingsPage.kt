package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.workeron9.ozmoz.lineage.desktop.EngineGateway
import io.github.workeron9.ozmoz.lineage.desktop.model.FlowKind

/**
 * 设置页：**分析缺省** + 关于。引擎与容错只在这里（工作区顶栏不再放同一个开关），
 * 改动即重跑分析 —— `App` 的 `LaunchedEffect` 以这两个值为键。
 */
@Composable
internal fun SettingsPage(
    engineId: String,
    tolerant: Boolean,
    onEngine: (String) -> Unit,
    onTolerant: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("分析", style = MaterialTheme.typography.titleSmall)

        Card {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("引擎", style = MaterialTheme.typography.titleSmall)
                Text(
                    "血缘可用引擎，与 CLI / ozml serve 同一份注册面。改了立刻重跑当前文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow {
                    val ids = EngineGateway.engineIds
                    ids.forEachIndexed { index, id ->
                        SegmentedButton(
                            selected = engineId == id,
                            onClick = { onEngine(id) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ids.size),
                        ) {
                            Text(id, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("容错", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "一条语句坏了，其余语句照常出图，坏的那条进诊断条 —— 不整段判死。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Switch(checked = tolerant, onCheckedChange = onTolerant)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("关于", style = MaterialTheme.typography.titleSmall)

        Card {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ozml lineage 桌面", style = MaterialTheme.typography.titleMedium)
                Text(
                    "解析在本机进程内完成：不上传、不连数据库。桌面是完整功能的入口，公网 Web 只做只读演示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "血缘可用引擎：" + EngineGateway.engineIds.joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("血缘图怎么读", style = MaterialTheme.typography.labelLarge)
                Text(
                    "颜色 = 边类型：" + FlowKind.entries.joinToString(" / ") { it.name.lowercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "线型 = 变换种类（12 种归成 4 档粗细 + 6 种虚线段）。点节点或边，详情条给出具体的类型与表达式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
