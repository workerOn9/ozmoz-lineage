package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.AgentEvent
import io.github.workeron9.ozmoz.lineage.desktop.model.DiscoveredAgent
import io.github.workeron9.ozmoz.lineage.desktop.model.ProviderConfig

/**
 * 本地 agent 的访问门面（D2 设计指导 §4.4）：**复用用户机器上已配好的 agent**，
 * 不内置模型与 Key。jvm 侧是子进程 + NDJSON；将来换后端（如 MCP）只换 `actual`。
 *
 * 两个回调都在**后台线程**调用；写 Compose 快照状态是安全的，UI 侧不必自己切换线程。
 */
public expect object AgentGateway {

    /** 扫 PATH + 读配置；**只读**，不写用户配置、不联网。 */
    public fun discover(onResult: (List<DiscoveredAgent>) -> Unit)

    /** 起一次会话；返回的句柄可取消（杀进程）。[prompt] 的组装由调用方负责。 */
    public fun start(
        provider: ProviderConfig,
        sessionId: String,
        prompt: String,
        onEvent: (AgentEvent) -> Unit,
    ): AgentRun
}

/** 一次进行中的 agent 会话。 */
public interface AgentRun {
    /** 取消：杀进程（幂等）。 */
    public fun cancel()
}
