package io.github.workeron9.ozmoz.lineage.desktop.service

import io.github.workeron9.ozmoz.lineage.desktop.model.AgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * agent 输出 → 事件的解析（D2 新增的非平凡逻辑）。
 *
 * 样例取自 2026-10-19 对 `opencode 2.0.29` / `pi 1.3.0` 的真实输出（逐字保留结构，
 * 业务标识已换成中性示例）——两个 provider 的格式都不许悄悄漂移。
 */
class AgentStreamParserTest {

    @Test
    fun opencodeTextPartIsWholeReplacement() {
        val line = """{"type":"text","timestamp":1,"sessionID":"ses_x",""" +
            """"part":{"id":"prt_1","sessionID":"ses_x","type":"text","text":"OK"}}"""
        val events = AgentStreamParser.parseLine(line)
        assertEquals(1, events.size)
        val text = events.single() as AgentEvent.Text
        assertEquals("prt_1", text.partId)
        assertEquals("OK", text.text)
        assertEquals(false, text.delta)
    }

    @Test
    fun opencodeToolUseCarriesInputAndOutput() {
        val line = """{"type":"tool_use","part":{"type":"tool","tool":"shell",""" +
            """"state":{"status":"completed","input":{"command":"echo hi"},"output":"hi\n"}}}"""
        val tool = AgentStreamParser.parseLine(line).single() as AgentEvent.Tool
        assertEquals("shell", tool.name)
        assertTrue(tool.input.contains("echo hi"))
        assertEquals("hi\n", tool.output)
        assertEquals(false, tool.isError)
    }

    @Test
    fun opencodeStepFinishCarriesUsage() {
        val line = """{"type":"step_finish","part":{"type":"step-finish",""" +
            """"cost":0.0001543,"tokens":{"input":243,"output":41}}}"""
        val usage = AgentStreamParser.parseLine(line).single() as AgentEvent.Usage
        assertEquals(0.0001543, usage.costUsd!!, 1e-9)
        assertEquals(243L, usage.tokens)
    }

    @Test
    fun piTextDeltasAreIncremental() {
        val delta = """{"type":"message_update","assistantMessageEvent":""" +
            """{"type":"text_delta","contentIndex":1,"delta":"Rows"}}"""
        val text = AgentStreamParser.parseLine(delta).single() as AgentEvent.Text
        assertEquals("pi-text-1", text.partId)
        assertEquals("Rows", text.text)
        assertTrue(text.delta, "pi 的 text_delta 必须标为增量，否则 UI 只会留下最后一个字")
    }

    @Test
    fun piAssistantMessageCarriesThinkingToolCallsAndUsage() {
        val line = """{"type":"message_end","message":{"role":"assistant","content":[""" +
            """{"type":"thinking","thinking":"hmm"},""" +
            """{"type":"toolCall","id":"call_1","name":"bash","arguments":{"command":"ls"}}],""" +
            """"usage":{"totalTokens":1688,"cost":{"total":0.0}}}}"""
        val events = AgentStreamParser.parseLine(line)
        assertTrue(events.any { it is AgentEvent.Thinking && it.text == "hmm" })
        val tool = events.filterIsInstance<AgentEvent.Tool>().single()
        assertEquals("bash", tool.name)
        assertTrue(tool.input.contains("ls"))
        assertEquals(null, tool.output, "工具调用先到、输出后到——两步是两条活动")
        val usage = events.filterIsInstance<AgentEvent.Usage>().single()
        assertEquals(1688L, usage.tokens)
    }

    @Test
    fun piToolExecutionEndCarriesOutput() {
        val line = """{"type":"tool_execution_end","toolCallId":"call_1","toolName":"bash",""" +
            """"result":{"content":[{"type":"text","text":"hi\n"}]},"isError":false,"durationMs":7}"""
        val tool = AgentStreamParser.parseLine(line).single() as AgentEvent.Tool
        assertEquals("bash", tool.name)
        assertEquals("hi\n", tool.output)
        assertEquals(false, tool.isError)
    }

    /** 实测：provider 403 时 opencode 发一条 error 事件，退出码 0——必须报失败。 */
    @Test
    fun opencodeErrorEventBecomesFailure() {
        val line = """
            {"type":"error","sessionID":"ses_x",
             "error":{"type":"provider.auth",
                      "message":"AI Gateway requires a valid credit card on file to service requests."}}
        """.trimIndent()
        val failure = AgentStreamParser.parseLine(line).single() as AgentEvent.Failure
        assertTrue(failure.message.contains("credit card"), failure.message)
    }

    @Test
    fun nonJsonLinesAreIgnoredNotFatal() {
        // pi 会把 "Warning: No models match pattern ..." 混进 stdout——不能因此中断。
        assertEquals(emptyList(), AgentStreamParser.parseLine("Warning: No models match pattern \"a/b\""))
        assertEquals(emptyList(), AgentStreamParser.parseLine(""))
        assertEquals(emptyList(), AgentStreamParser.parseLine("{ not json"))
        assertEquals(emptyList(), AgentStreamParser.parseLine("""{"type":"unknown_future_event"}"""))
    }
}
