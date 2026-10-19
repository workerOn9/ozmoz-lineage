package io.github.workeron9.ozmoz.lineage.desktop.service

import io.github.workeron9.ozmoz.lineage.desktop.model.AgentEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 一段 agent 输出 → [AgentEvent]。**纯函数**：两个 provider 的格式都在这里收口，
 * 界面只认事件。不认识的行返回空（provider 会混日志行，不能因此中断）。
 *
 * opencode（`--format json`，每行一个 JSON 事件）：
 * - `{"type":"text","part":{"id":…,"text":…}}`——**整段**发，同 id 覆盖；
 * - `{"type":"tool_use","part":{"tool":…,"state":{"input":…,"output":…,"status":…}}}`；
 * - `{"type":"step_finish","part":{"cost":…,"tokens":{…}}}`。
 *
 * pi（`--mode json`，NDJSON）：
 * - `message_update` 的 `assistantMessageEvent.text_delta` 逐字发（按 `contentIndex` 拼）；
 * - `message_end`（role=assistant）带 `toolCall` 与 `usage`；role=toolResult 带输出；
 * - `tool_execution_end` 带工具输出。
 */
internal object AgentStreamParser {

    fun parseLine(line: String): List<AgentEvent> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val node = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return emptyList()
        return when (node["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> opencodeText(node)
            "tool_use" -> opencodeTool(node)
            "step_finish" -> opencodeUsage(node)
            "error" -> opencodeError(node)
            "message_update" -> piDelta(node)
            "message_end" -> piMessageEnd(node)
            "tool_execution_end" -> piToolEnd(node)
            else -> emptyList()
        }
    }

    private fun opencodeText(node: JsonObject): List<AgentEvent> {
        val part = node["part"]?.jsonObject ?: return emptyList()
        val id = part["id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val text = part["text"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        return listOf(AgentEvent.Text(partId = id, text = text))
    }

    private fun opencodeTool(node: JsonObject): List<AgentEvent> {
        val part = node["part"]?.jsonObject ?: return emptyList()
        val name = part["tool"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val state = part["state"]?.jsonObject ?: return emptyList()
        val input = state["input"]?.toString().orEmpty()
        val output = state["output"]?.jsonPrimitive?.contentOrNull
        val status = state["status"]?.jsonPrimitive?.contentOrNull
        return listOf(AgentEvent.Tool(name = name, input = input, output = output, isError = status == "error"))
    }

    /**
     * opencode 的失败是**一条 JSON 事件**、进程退出码仍是 0（2026-10-19 实测：provider 403）。
     * 不认它就会把「失败」画成「说完了但没内容」。
     */
    private fun opencodeError(node: JsonObject): List<AgentEvent> {
        val error = node["error"]?.jsonObject ?: return emptyList()
        val message = error["message"]?.jsonPrimitive?.contentOrNull
            ?: error["type"]?.jsonPrimitive?.contentOrNull
            ?: "agent 报错"
        return listOf(AgentEvent.Failure(message))
    }

    private fun opencodeUsage(node: JsonObject): List<AgentEvent> {
        val part = node["part"]?.jsonObject ?: return emptyList()
        val cost = part["cost"]?.jsonPrimitive?.doubleOrNull
        val tokens = part["tokens"]?.jsonObject?.get("input")?.jsonPrimitive?.longOrNull
        return listOf(AgentEvent.Usage(costUsd = cost, tokens = tokens))
    }

    private fun piDelta(node: JsonObject): List<AgentEvent> {
        val event = node["assistantMessageEvent"]?.jsonObject ?: return emptyList()
        val index = event["contentIndex"]?.jsonPrimitive?.intOrNull ?: 0
        return when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "text_delta" -> listOf(
                AgentEvent.Text(
                    partId = "pi-text-$index",
                    text = event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    delta = true,
                ),
            )

            "text_end" -> listOf(
                AgentEvent.Text(partId = "pi-text-$index", text = event["content"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            )

            else -> emptyList()
        }
    }

    private fun piMessageEnd(node: JsonObject): List<AgentEvent> {
        val message = node["message"]?.jsonObject ?: return emptyList()
        val role = message["role"]?.jsonPrimitive?.contentOrNull
        if (role == "toolResult") {
            val output = message["content"]?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("")
            val name = message["toolName"]?.jsonPrimitive?.contentOrNull ?: "tool"
            val isError = message["isError"]?.jsonPrimitive?.booleanOrNull == true
            return if (output == null) emptyList() else listOf(AgentEvent.Tool(name, "", output, isError))
        }
        if (role != "assistant") return emptyList()
        val events = ArrayList<AgentEvent>()
        val content = message["content"]?.jsonArray ?: return emptyList()
        for (item in content) {
            val obj = item.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "thinking" -> obj["thinking"]?.jsonPrimitive?.contentOrNull?.let {
                    events += AgentEvent.Thinking(it)
                }

                "toolCall" -> events += AgentEvent.Tool(
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool",
                    input = obj["arguments"]?.toString().orEmpty(),
                    output = null,
                )
            }
        }
        message["usage"]?.jsonObject?.let { usage ->
            events += AgentEvent.Usage(
                costUsd = usage["cost"]?.jsonObject?.get("total")?.jsonPrimitive?.doubleOrNull,
                tokens = usage["totalTokens"]?.jsonPrimitive?.longOrNull,
            )
        }
        return events
    }

    private fun piToolEnd(node: JsonObject): List<AgentEvent> {
        val name = node["toolName"]?.jsonPrimitive?.contentOrNull ?: "tool"
        val result = node["result"]?.jsonObject ?: return emptyList()
        val output = result["content"]?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
        val isError = node["isError"]?.jsonPrimitive?.booleanOrNull == true
        return if (output == null) emptyList() else listOf(AgentEvent.Tool(name, "", output, isError))
    }
}
