package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.service.AgentDiscovery
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 本地 agent 发现：**设置里存过的绝对路径**必须能绕过 PATH 被扫到。
 *
 * 这条对应真问题：从 Finder 启动的 .app 只有 launchd 的极简 PATH，
 * `/opt/homebrew/bin/opencode` 明明装了，界面却一直「未发现」。
 */
class AgentDiscoveryTest {

    @Test
    fun discoversAgentFromSavedEndpointWithoutItBeingOnPath() {
        val dir = Files.createTempDirectory("ozml-agent-bin")
        val exe = dir.resolve("opencode")
        Files.writeString(
            exe,
            "#!/bin/sh\nif [ \"$1\" = \"models\" ]; then echo 'opencode-go/deepseek-v4.1-flash'; echo 'claude-code/claude-opus-5-5'; else echo 9.9.9; fi\n",
        )
        Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"))
        try {
            val found = AgentDiscovery.discover(knownExecutables = listOf(exe.toString()))
            val opencode = found.firstOrNull { it.id == "opencode" }
            assertNotNull(opencode, "存过绝对路径的 opencode 应该被发现：$found")
            assertEquals(exe.toString(), opencode.executable)
            assertEquals("9.9.9", opencode.version)
            assertEquals(
                listOf("opencode-go/deepseek-v4.1-flash", "claude-code/claude-opus-5-5"),
                opencode.models,
                "模型清单要按行读（不能被 --version 的数字清洗规则弄坏）",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun parsesPiEnabledModelsFromSettings() {
        val json = """
            {"defaultProvider":"xmiles-openai-completions","defaultModel":"deepseek-v4.1-flash",
             "enabledModels":["opencode-go/qwen3.8-flash","xmiles-openai-completions/deepseek-v4.1-flash"]}
        """.trimIndent()
        assertEquals(
            listOf("opencode-go/qwen3.8-flash", "xmiles-openai-completions/deepseek-v4.1-flash"),
            AgentDiscovery.piModels(json),
        )
        assertEquals(emptyList(), AgentDiscovery.piModels(null))
        assertEquals(emptyList(), AgentDiscovery.piModels("not json"))
    }

    /** 子进程 PATH 要含 macOS 上最常见的两个安装目录，否则 agent 的壳脚本找不到 node 等。 */
    @Test
    fun childPathContainsCommonInstallDirs() {
        val path = AgentDiscovery.searchPath()
        assertTrue("/opt/homebrew/bin" in path, path)
        assertTrue("/usr/local/bin" in path, path)
    }
}
