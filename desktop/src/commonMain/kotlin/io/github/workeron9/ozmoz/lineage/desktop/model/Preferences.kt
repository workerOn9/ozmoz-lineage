package io.github.workeron9.ozmoz.lineage.desktop.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** 主题模式。缺省跟随系统（D1 是固定深色）。 */
@Serializable
public enum class ThemeMode(public val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** 密度：开发者自己用 Compact，截图演示用 Comfortable（D2 设计指导 §3.1）。 */
@Serializable
public enum class Density(public val label: String) {
    COMPACT("紧凑"),
    COMFORTABLE("舒适"),
}

/**
 * 本地设置与连接 / Provider 配置。**不含任何密码或 API Key**——
 * 凭据存储是 ADR-0022 决定 5 的 D3 事项，这里不假装有加密。
 */
@Serializable
public data class DesktopStoreState(
    val connections: List<ConnectionProfile> = emptyList(),
    val providers: List<ProviderConfig> = emptyList(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val density: Density = Density.COMPACT,
)

/** 一次 schema 快照：表清单 + 每张表的列（键 = [DbTable.qualifiedName]）。 */
public data class SchemaSnapshot(
    val tables: List<DbTable> = emptyList(),
    val columns: Map<String, List<DbColumn>> = emptyMap(),
)

/** Agent 流式事件。UI 按 [partId] 替换同一条文本（opencode 整段发、pi 逐字发，两种都收）。 */
public sealed interface AgentEvent {
    /**
     * 一段可见文本。[delta] = true 表示这是增量（pi 的 text_delta，要拼接到同 [partId] 上）；
     * false 表示整段替换（opencode 的 text part / pi 的 text_end）。
     */
    public data class Text(val partId: String, val text: String, val delta: Boolean = false) : AgentEvent

    public data class Tool(
        val name: String,
        val input: String,
        val output: String?,
        val isError: Boolean = false,
    ) : AgentEvent

    public data class Thinking(val text: String) : AgentEvent

    public data class Usage(val costUsd: Double?, val tokens: Long?) : AgentEvent

    public data class Failure(val message: String) : AgentEvent

    /** 进程正常结束（成功路径）。由网关在读到 EOF 后补发，不是 provider 的输出。 */
    public data object Completed : AgentEvent
}

/**
 * 会话 id：opencode 要求 `ses` 前缀（实测不带前缀直接报
 * `Expected a string starting with "ses"`），pi 接受任意 UUID。
 * 由前端生成而不是解析输出，重连后仍能接着同一个会话。
 */
public fun newAgentSessionId(providerId: String): String {
    val suffix = buildString {
        repeat(24) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }
    return if (providerId == "opencode") "ses_$suffix" else suffix
}
