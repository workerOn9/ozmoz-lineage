package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.workeron9.ozmoz.lineage.desktop.model.Density
import io.github.workeron9.ozmoz.lineage.desktop.model.ThemeMode

/**
 * 设置页：**应用级**——外观（明暗 / 密度）、Provider 管理、关于。
 *
 * 引擎与容错**不在这里**：它们直接决定当前文档的分析结果，是顶栏的文档级开关
 * （同一个开关只有一个家；ADR-0023 的「分工」口径在 D2 升级为「顶栏管文档、设置管应用」）。
 */
@Composable
internal fun SettingsPage(state: WorkbenchState) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("外观", style = MaterialTheme.typography.titleSmall)
        Card {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("主题", style = MaterialTheme.typography.titleSmall)
                Text(
                    "缺省跟随系统；两套配色都按 Material 3 规范取值，数据语义色在深浅下保持可辨。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.store.theme == mode,
                            onClick = { state.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                            icon = {},
                        ) { Text(mode.label, fontSize = density.bodySize) }
                    }
                }
                HorizontalDivider()
                Text("密度", style = MaterialTheme.typography.titleSmall)
                Text(
                    "紧凑给日常使用，舒适给演示与截图；密度只影响字号与行高，不改信息结构。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow {
                    Density.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = state.store.density == option,
                            onClick = { state.setDensity(option) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = Density.entries.size),
                            icon = {},
                        ) { Text(option.label, fontSize = density.bodySize) }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("Agent Provider", style = MaterialTheme.typography.titleSmall)
        Card {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "ozmoz-lineage 不内置模型；这里管理它接入的本机 agent（复用它们的模型、Key 与 skill）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.scanning) {
                    Text("正在扫描…", color = tokens.textMuted, fontSize = density.bodySize)
                }
                for (agent in state.agents) {
                    val config = state.store.providers.firstOrNull { it.id == agent.id }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(agent.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(
                                    agent.version?.let { "v$it" },
                                    agent.model,
                                    "${agent.skills.size} 个 skills",
                                ).joinToString(" · "),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                fontSize = density.bodySize,
                            )
                        }
                        Switch(
                            checked = config?.enabled == true,
                            onCheckedChange = { enabled ->
                                when {
                                    enabled -> state.enableProvider(agent)
                                    config != null -> state.disableProvider(config)
                                }
                            },
                        )
                    }
                }
                if (state.agents.isEmpty() && !state.scanning) {
                    Text("未发现 opencode / pi。", color = tokens.textMuted, fontSize = density.bodySize)
                }
                // 设置里存过、这次没扫到：把路径说出来，别让用户对着「未发现」反复点重扫。
                val stale = state.store.providers.filter { config -> state.agents.none { it.id == config.id } }
                if (stale.isNotEmpty() && !state.scanning) {
                    Text(
                        "设置里存过的 Provider 这次没扫到：" +
                            stale.joinToString("、") { "${it.name}（${it.endpoint.ifBlank { "未记录路径" }}）" },
                        color = tokens.warning,
                        fontSize = density.bodySize,
                    )
                }
                TextButton(onClick = { state.scanAgents() }) { Text("重新扫描") }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("关于", style = MaterialTheme.typography.titleSmall)
        Card {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ozml lineage 桌面", style = MaterialTheme.typography.titleMedium)
                Text(
                    "解析在本机进程内完成：不上传、不连数据库（连库只读元数据）。桌面是完整功能的入口，公网 Web 只做只读演示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "血缘可用引擎：" + io.github.workeron9.ozmoz.lineage.desktop.EngineGateway.engineIds.joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "设置与连接存在 ~/.ozml-lineage/desktop.json（不含密码）；JDBC 驱动放 ~/.ozml-lineage/drivers。",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 面板小标题（仪器层共用）。 */
@Composable
internal fun PanelHeader(text: String) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    Row(
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = tokens.textFaint,
            fontFamily = FontFamily.Monospace,
            fontSize = density.bodySize,
        )
    }
}
