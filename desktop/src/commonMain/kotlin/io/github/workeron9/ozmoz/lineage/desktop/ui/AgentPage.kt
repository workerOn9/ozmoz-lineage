package io.github.workeron9.ozmoz.lineage.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.workeron9.ozmoz.lineage.desktop.AgentGateway
import io.github.workeron9.ozmoz.lineage.desktop.OpenedFile
import io.github.workeron9.ozmoz.lineage.desktop.model.Activity
import io.github.workeron9.ozmoz.lineage.desktop.model.ActivityKind
import io.github.workeron9.ozmoz.lineage.desktop.model.DiscoveredAgent
import io.github.workeron9.ozmoz.lineage.desktop.model.InputMode
import io.github.workeron9.ozmoz.lineage.desktop.model.ProviderConfig

/**
 * AGENT 目的地：长期会话空间。
 *
 * **不内置 Agent**：这里只做「工作台」，真正干活的模型与工具在用户本机已配好的
 * opencode / pi 里（D2 设计指导 §4.4）。没有可用 Provider 时显示引导卡片（空态不撒谎）。
 */
@Composable
internal fun AgentPage(state: WorkbenchState) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    Column(Modifier.fillMaxSize()) {
        AgentHeader(state)
        if (state.enabledProviders().isEmpty()) {
            ProviderOnboarding(state)
        } else {
            AgentChat(state = state, context = null, modifier = Modifier.weight(1f))
        }
        state.agentError?.let { message ->
            Text(
                message,
                color = tokens.danger,
                fontSize = density.bodySize,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Agent 目的地的头部：Provider 选择、模型、上下文用量、新会话、停止。 */
@Composable
private fun AgentHeader(state: WorkbenchState) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    var menuOpen by remember { mutableStateOf(false) }
    val providers = state.enabledProviders()
    val current = providers.firstOrNull { it.id == state.agentProviderId }
    val agent = state.agents.firstOrNull { it.id == current?.id }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { menuOpen = true }, enabled = providers.isNotEmpty()) {
                Text(current?.name ?: "未选择 Agent", fontSize = density.titleSize)
                if (current != null && agent == null) Text(" 未发现", color = tokens.warning, fontSize = density.bodySize)
                Text("  ▾", color = tokens.textFaint)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (provider in providers) {
                    DropdownMenuItem(
                        text = { Text(provider.name) },
                        onClick = {
                            state.agentProviderId = provider.id
                            menuOpen = false
                        },
                    )
                }
            }
        }
        if (current != null) {
            ModelPicker(state, current, agent?.model)
        }
        agent?.let {
            Text(
                "· ${it.skills.size} 个 skills",
                color = tokens.textMuted,
                fontSize = density.bodySize,
            )
        }
        Spacer(Modifier.weight(1f))
        state.usageLine?.let {
            Text(it, color = tokens.textFaint, fontFamily = FontFamily.Monospace, fontSize = density.bodySize)
            Spacer(Modifier.width(12.dp))
        }
        if (state.agentBusy) {
            IconButton(onClick = { state.cancelAgent() }) {
                Icon(Icons.Stop, contentDescription = "停止", tint = tokens.danger)
            }
        }
        TextButton(onClick = { state.newAgentSession() }) { Text("新会话") }
    }
}

/**
 * 对话流 + 命令输入。[context] 非空时（工作区的 Agent Tab）每条消息都带上当前 SQL。
 */
@Composable
internal fun AgentChat(
    state: WorkbenchState,
    context: OpenedFile?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    Column(modifier) {
        val listState = rememberLazyListState()
        LaunchedEffect(state.activities.size) {
            if (state.activities.isNotEmpty()) listState.animateScrollToItem(state.activities.lastIndex)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.activities.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (context == null) "和本机 Agent 对话" else "就这份 SQL 问 Agent",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (context == null) {
                            "上面的输入框里描述需求；Agent 用它自己的模型与工具执行。"
                        } else {
                            "每条消息都会附上当前文件 ${context.name}；回答不会写回你的文件。"
                        },
                        color = tokens.textMuted,
                        fontSize = density.bodySize,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                ) {
                    items(state.activities.size, key = { state.activities[it].id }) { index ->
                        ActivityItem(state.activities[index]) { state.toggleActivity(state.activities[index].id) }
                    }
                }
            }
        }
        CommandInput(state = state, context = context)
    }
}

/** 一条活动：用户消息 / Agent 消息 / 思考 / 工具调用 / 工具结果 / 错误。 */
@Composable
private fun ActivityItem(activity: Activity, onToggle: () -> Unit) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    val containerColor = when {
        activity.kind == ActivityKind.USER_MESSAGE -> MaterialTheme.colorScheme.primaryContainer
        activity.kind == ActivityKind.ERROR -> MaterialTheme.colorScheme.errorContainer
        activity.kind == ActivityKind.TOOL_CALL || activity.kind == ActivityKind.TOOL_RESULT ->
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)

        activity.kind == ActivityKind.THINKING -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val collapsible = activity.text.length > 400 || activity.kind == ActivityKind.THINKING
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        Column(
            Modifier.padding(10.dp).let { if (collapsible) it.clickable { onToggle() } else it },
        ) {
            val label = when (activity.kind) {
                ActivityKind.USER_MESSAGE -> "你"
                ActivityKind.AGENT_MESSAGE -> "Agent"
                ActivityKind.THINKING -> "思考过程"
                ActivityKind.TOOL_CALL -> "工具 · ${activity.title.ifBlank { "call" }}"
                ActivityKind.TOOL_RESULT -> "结果 · ${activity.title.ifBlank { "tool" }}"
                ActivityKind.ERROR -> "出错"
            }
            Text(label, color = tokens.textFaint, fontFamily = FontFamily.Monospace, fontSize = density.bodySize)
            Spacer(Modifier.height(4.dp))
            val shown = when {
                activity.kind == ActivityKind.THINKING && !activity.expanded -> "（收起，点击展开）"
                !activity.expanded && activity.text.length > 400 -> activity.text.take(400) + " …"
                else -> activity.text
            }
            Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    shown.ifBlank { "（空）" },
                    color = if (activity.isError) tokens.danger else tokens.text,
                    fontFamily = if (activity.kind == ActivityKind.TOOL_CALL || activity.kind == ActivityKind.TOOL_RESULT) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                    fontSize = density.bodySize,
                    lineHeight = density.bodyLineHeight,
                )
            }
        }
    }
}

/**
 * 命令输入：模式（问血缘 / 写 SQL / 审 SQL）+ 连接提及 `@` + 技能 `$` + 命令 `/`。
 * ⌘Enter 发送（Enter 换行——SQL 是多行的）。
 */
@Composable
private fun CommandInput(state: WorkbenchState, context: OpenedFile?) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(InputMode.QUERY) }
    var mentionMenu by remember { mutableStateOf(false) }
    var skillMenu by remember { mutableStateOf(false) }
    val skills = remember(state.agents, state.agentProviderId) {
        state.agents.firstOrNull { it.id == state.agentProviderId }?.skills.orEmpty().take(12)
    }

    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (option in InputMode.entries) {
                    FilterChip(
                        selected = mode == option,
                        onClick = { mode = option },
                        label = { Text(option.label, fontSize = density.bodySize) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box {
                    AssistChip(onClick = { mentionMenu = true }, label = { Text("@ 库") })
                    DropdownMenu(expanded = mentionMenu, onDismissRequest = { mentionMenu = false }) {
                        if (state.store.connections.isEmpty()) {
                            DropdownMenuItem(text = { Text("还没有连接") }, onClick = { mentionMenu = false })
                        }
                        for (connection in state.store.connections) {
                            DropdownMenuItem(
                                text = { Text(connection.name) },
                                onClick = {
                                    text += (if (text.isNotBlank() && !text.endsWith(" ")) " " else "") + "@${connection.name} "
                                    mentionMenu = false
                                },
                            )
                        }
                    }
                }
                Box {
                    AssistChip(
                        onClick = { skillMenu = true },
                        label = { Text("\$ 技能") },
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    DropdownMenu(expanded = skillMenu, onDismissRequest = { skillMenu = false }) {
                        if (skills.isEmpty()) {
                            DropdownMenuItem(text = { Text("该 Agent 没有枚举到 skills") }, onClick = { skillMenu = false })
                        }
                        for (skill in skills) {
                            DropdownMenuItem(
                                text = { Text(skill) },
                                onClick = {
                                    text += (if (text.isNotBlank() && !text.endsWith(" ")) " " else "") + "\$$skill "
                                    skillMenu = false
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && (event.isMetaPressed || event.isCtrlPressed)) {
                            submit(state, text, context, mode)
                            text = ""
                            true
                        } else {
                            false
                        }
                    },
                    placeholder = { Text("描述需求，或粘一段 SQL…（⌘Enter 发送）", fontSize = density.bodySize) },
                    minLines = 2,
                    maxLines = 8,
                )
                if (state.agentBusy) {
                    IconButton(onClick = { state.cancelAgent() }) {
                        Icon(Icons.Stop, contentDescription = "停止", tint = tokens.danger)
                    }
                } else {
                    IconButton(
                        onClick = {
                            submit(state, text, context, mode)
                            text = ""
                        },
                        enabled = text.isNotBlank(),
                    ) {
                        Icon(Icons.Send, contentDescription = "发送", tint = tokens.accent)
                    }
                }
            }
        }
    }
}

private fun submit(state: WorkbenchState, text: String, context: OpenedFile?, mode: InputMode) {
    if (text.isBlank()) return
    val handled = when {
        text.startsWith("/clear") -> {
            state.newAgentSession()
            true
        }

        text.startsWith("/new") -> {
            state.newAgentSession()
            true
        }

        else -> false
    }
    if (handled) return
    val prompt = when (mode) {
        InputMode.QUERY, InputMode.REVIEW -> "${state.modeInstruction(mode)}\n\n$text"
        InputMode.GENERATE -> "${state.modeInstruction(mode)}\n\n$text"
    }
    state.sendAgent(prompt, context)
}

/**
 * Provider 空态引导：扫描结果卡片 + 「手动配置」的如实说明。
 * 没有本地 agent 时不画假按钮（D2 只接本地 agent；直接 API / MCP 在后续版本）。
 */
@Composable
private fun ProviderOnboarding(state: WorkbenchState) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Agent 工作台", style = MaterialTheme.typography.titleMedium)
        Text(
            "ozmoz-lineage 不内置模型；它接入你本机已经配好的 agent，复用它们的模型、Key 与 skill。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = density.bodySize,
        )
        if (state.scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text("正在扫描本地 Agent…", color = tokens.textMuted, fontSize = density.bodySize)
            }
        } else if (state.agents.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("没有发现可用的本地 Agent", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "把 opencode 或 pi 装好并在 PATH 里可用，重启应用即可被发现。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = density.bodySize,
                    )
                    Text(
                        "手动配置（直接 API / MCP Server）在后续版本接入。",
                        color = tokens.textFaint,
                        fontSize = density.bodySize,
                    )
                }
            }
        } else {
            Text("发现以下已安装的 Agent（点击启用）：", color = tokens.textMuted, fontSize = density.bodySize)
            for (agent in state.agents) {
                AgentCard(agent) { state.enableProvider(agent) }
            }
        }
        TextButton(onClick = { state.scanAgents() }) { Text("重新扫描") }
    }
}

/**
 * 模型选择：本机 agent 自己的模型清单（opencode `models` / pi `enabledModels`），
 * 选中的 id 落进 ProviderConfig.model，发消息时作为 `--model` 传给子进程。
 *
 * 为什么要有它：opencode 不带 `--model` 会落到它自己的默认额度（实测报
 * 「free tier can only be used from within OpenCode」），用户必须能自己挑模型。
 */
@Composable
private fun ModelPicker(state: WorkbenchState, provider: ProviderConfig, defaultModel: String?) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    var open by remember { mutableStateOf(false) }
    var fetched by remember(provider.id) { mutableStateOf<List<String>?>(null) }
    var loading by remember(provider.id) { mutableStateOf(false) }
    val models = fetched ?: state.providerModels(provider.id)
    val label = provider.model.ifBlank {
        defaultModel?.takeIf { it.isNotBlank() }?.let { "默认（$it）" } ?: "默认"
    }

    // 打开时现取一次：发现阶段的清单可能是空的（opencode 的 models 会偶发失败），
    // 而且用户也可能刚在 agent 侧加过模型。
    LaunchedEffect(open) {
        if (open) {
            loading = true
            AgentGateway.models(provider) { read ->
                fetched = read
                loading = false
            }
        }
    }

    TextButton(onClick = { open = true }) {
        Text("模型 · $label", fontFamily = FontFamily.Monospace, fontSize = density.bodySize)
        Text("  ▾", color = tokens.textFaint, fontSize = density.bodySize)
    }
    if (!open) return
    var filter by remember { mutableStateOf("") }
    val shown = remember(models, filter) {
        if (filter.isBlank()) models else models.filter { it.contains(filter, ignoreCase = true) }
    }
    Dialog(onDismissRequest = { open = false }) {
        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 4.dp) {
            Column(Modifier.width(520.dp).height(480.dp).padding(16.dp)) {
                Text("选择 ${provider.name} 的模型", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("筛选（共 ${models.size} 个）", fontSize = density.bodySize) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (loading && models.isEmpty()) {
                    Text("正在读取模型清单…", color = tokens.textMuted, fontSize = density.bodySize)
                }
                if (!loading && models.isEmpty()) {
                    Text(
                        "没有读到模型：在终端跑一次 `${provider.id} models` 看看输出；装好/登录后重开这个下拉。",
                        color = tokens.warning,
                        fontSize = density.bodySize,
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        PickRow("默认（跟随 agent 自己的配置）", provider.model.isBlank()) {
                            state.selectModel(provider.id, "")
                            open = false
                        }
                    }
                    items(shown.size) { index ->
                        val model = shown[index]
                        PickRow(model, model == provider.model) {
                            state.selectModel(provider.id, model)
                            open = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    Text(
        text,
        color = if (selected) tokens.accent else tokens.text,
        fontFamily = FontFamily.Monospace,
        fontSize = density.bodySize,
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

@Composable
private fun AgentCard(agent: DiscoveredAgent, onEnable: () -> Unit) {
    val tokens = LocalTokens.current
    val density = LocalUiDensity.current
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(agent.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        agent.version?.let { append("v$it") }
                        agent.model?.takeIf { it.isNotBlank() }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
                        }
                        if (isNotEmpty()) append(" · ")
                        append("${agent.skills.size} 个 skills")
                    },
                    color = tokens.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = density.bodySize,
                )
                Text(agent.executable, color = tokens.textFaint, fontFamily = FontFamily.Monospace, fontSize = density.bodySize)
                agent.note?.let { Text(it, color = tokens.warning, fontSize = density.bodySize) }
            }
            Button(onClick = onEnable) { Text("启用") }
        }
    }
}
