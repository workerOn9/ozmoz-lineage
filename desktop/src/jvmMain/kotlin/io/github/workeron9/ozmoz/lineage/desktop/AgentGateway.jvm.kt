package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.AgentEvent
import io.github.workeron9.ozmoz.lineage.desktop.model.DiscoveredAgent
import io.github.workeron9.ozmoz.lineage.desktop.model.ProviderConfig
import io.github.workeron9.ozmoz.lineage.desktop.service.AgentDiscovery
import io.github.workeron9.ozmoz.lineage.desktop.service.NativeAgentProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * [AgentGateway] 的 JVM 实现：设备发现与 agent 进程都在后台线程；
 * 取消 = 杀进程（`destroyForcibly`，幂等）。
 */
public actual object AgentGateway {

    public actual fun discover(knownExecutables: List<String>, onResult: (List<DiscoveredAgent>) -> Unit) {
        thread(isDaemon = true, name = "ozml-agent-discovery") {
            onResult(AgentDiscovery.discover(knownExecutables))
        }
    }

    public actual fun models(provider: ProviderConfig, onResult: (List<String>) -> Unit) {
        thread(isDaemon = true, name = "ozml-agent-models-${provider.id}") {
            onResult(AgentDiscovery.models(provider.id, provider.endpoint))
        }
    }

    public actual fun start(
        provider: ProviderConfig,
        sessionId: String,
        prompt: String,
        onEvent: (AgentEvent) -> Unit,
    ): AgentRun {
        val processRef = AtomicReference<Process?>(null)
        val cancelled = AtomicBoolean(false)
        fun emit(event: AgentEvent) {
            if (!cancelled.get()) onEvent(event)
        }
        val worker = thread(isDaemon = true, name = "ozml-agent-${provider.id}") {
            val process = NativeAgentProvider.launch(provider, sessionId, prompt) { emit(AgentEvent.Failure(it)) }
                ?: return@thread
            processRef.set(process)
            if (cancelled.get()) {
                process.destroyForcibly()
                return@thread
            }
            var failed = false
            NativeAgentProvider.consume(process) { event ->
                if (event is AgentEvent.Failure) failed = true
                emit(event)
            }
            processRef.set(null)
            if (!failed) emit(AgentEvent.Completed)
        }
        return object : AgentRun {
            override fun cancel() {
                cancelled.set(true)
                processRef.getAndSet(null)?.destroyForcibly()
                worker.interrupt()
            }
        }
    }
}
