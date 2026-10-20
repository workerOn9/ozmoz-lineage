package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.AgentEvent
import io.github.workeron9.ozmoz.lineage.desktop.model.ProviderConfig
import io.github.workeron9.ozmoz.lineage.desktop.model.ProviderKind
import io.github.workeron9.ozmoz.lineage.desktop.model.newAgentSessionId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * 真实本地 agent 的**手动联调**（不进 CI；会真的调模型、花 token）：
 * 发现 → 起一次会话 → 收到文本事件。
 *
 * 跑法：`./gradlew :desktop:jvmTest --tests "*AgentLiveCheck*" -PozmlManualChecks=true`
 */
class AgentLiveCheck {

    private val enabled = System.getProperty("ozml.manual.checks") == "true"

    @Test
    fun discoverFindsLocalAgents() {
        if (!enabled) return
        val latch = CountDownLatch(1)
        var found = emptyList<io.github.workeron9.ozmoz.lineage.desktop.model.DiscoveredAgent>()
        AgentGateway.discover {
            found = it
            latch.countDown()
        }
        check(latch.await(30, TimeUnit.SECONDS)) { "发现超时" }
        found.forEach {
            println("discovered: ${it.id} ${it.version} model=${it.model} skills=${it.skills.size} exe=${it.executable}")
        }
        check(found.isNotEmpty()) { "本机应至少发现一个本地 agent（opencode / pi）" }
    }

    @Test
    fun streamsOneTurnFromDiscoveredAgent() {
        if (!enabled) return
        val providerId = System.getProperty("ozml.live.provider")?.takeIf { it.isNotBlank() } ?: return
        val prompt = System.getProperty("ozml.live.prompt") ?: "Reply with exactly: OK"
        val session = newAgentSessionId(providerId)
        val texts = StringBuilder()
        var failure: String? = null
        val done = CountDownLatch(1)
        AgentGateway.start(
            ProviderConfig(id = providerId, name = providerId, kind = ProviderKind.NATIVE),
            session,
            prompt,
        ) { event ->
            when (event) {
                // 与 UI 同口径：delta 追加，整段替换则覆盖。
                is AgentEvent.Text -> if (event.delta) texts.append(event.text) else texts.clear().append(event.text)
                is AgentEvent.Failure -> failure = event.message
                AgentEvent.Completed -> done.countDown()
                else -> Unit
            }
        }
        check(done.await(120, TimeUnit.SECONDS)) { "会话超时；已收到：$texts" }
        check(failure == null) { "失败：$failure" }
        check(texts.isNotBlank()) { "应收到非空回答" }
        println("live reply from $providerId: ${texts.take(200)}")
    }
}
