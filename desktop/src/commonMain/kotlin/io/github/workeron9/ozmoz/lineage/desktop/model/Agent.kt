package io.github.workeron9.ozmoz.lineage.desktop.model

import kotlinx.serialization.Serializable

/**
 * Provider 的种类（D2 设计指导 §4.1）。
 *
 * - [NATIVE]：复用用户电脑上已装好的 agent（opencode / pi），经其 CLI 的进程通信；
 * - [MCP]：MCP Server（D2 只登记配置，协议接入留 D3）；
 * - [DIRECT_API]：直接打 OpenAI 兼容 API（D2 只登记配置）。
 */
@Serializable
public enum class ProviderKind { NATIVE, MCP, DIRECT_API }

/** 发现到的本地 agent。**只读探测结果**，不替用户启用。 */
public data class DiscoveredAgent(
    val id: String,
    val name: String,
    val executable: String,
    val version: String?,
    val model: String?,
    val skills: List<String>,
    /** 探测失败的原因（装是装了，但 --version 或配置读不出来）。 */
    val note: String? = null,
    /** 这个 agent 可选的模型 id（opencode 的 `models` 输出 / pi 的 enabledModels）。空 = 枚举不到。 */
    val models: List<String> = emptyList(),
)

/**
 * Provider 配置。[id] 对 [ProviderKind.NATIVE] 等于 [DiscoveredAgent.id]，
 * 其余由用户命名。**不存 API Key**（D2 不做凭据，D3 走 ADR-0022 决定 5）。
 */
@Serializable
public data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val enabled: Boolean = true,
    /** NATIVE 用：可执行文件名；DIRECT_API 用：base URL。 */
    val endpoint: String = "",
    /** DIRECT_API / MCP 的模型或工具名。 */
    val model: String = "",
)

/**
 * 会话里的一条活动（D2 设计指导 3.4）。
 *
 * `thinking` 默认收起；`tool` 是可展开的工具调用，`toolResult` 是它的输出。
 */
public data class Activity(
    val id: Long,
    val kind: ActivityKind,
    val text: String,
    val title: String = "",
    val durationMillis: Long? = null,
    val expanded: Boolean = false,
    val isError: Boolean = false,
)

public enum class ActivityKind { USER_MESSAGE, AGENT_MESSAGE, THINKING, TOOL_CALL, TOOL_RESULT, ERROR }

/** 命令输入框的模式（`/` 命令之外的三个语义）。 */
public enum class InputMode(public val label: String) {
    QUERY("问血缘"),
    GENERATE("写 SQL"),
    REVIEW("审 SQL"),
}

/** 一个会话。D2 只在内存里保留当前会话；历史落盘留 D3。 */
public data class AgentSession(
    val id: String,
    val providerId: String?,
    val title: String,
    val activities: List<Activity> = emptyList(),
    val busy: Boolean = false,
)
