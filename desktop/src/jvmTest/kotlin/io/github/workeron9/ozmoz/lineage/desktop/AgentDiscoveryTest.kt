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
        Files.writeString(exe, "#!/bin/sh\necho 9.9.9\n")
        Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"))
        try {
            val found = AgentDiscovery.discover(knownExecutables = listOf(exe.toString()))
            val opencode = found.firstOrNull { it.id == "opencode" }
            assertNotNull(opencode, "存过绝对路径的 opencode 应该被发现：$found")
            assertEquals(exe.toString(), opencode.executable)
            assertEquals("9.9.9", opencode.version)
            assertTrue("9.9.9" in opencode.version.orEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 子进程 PATH 要含 macOS 上最常见的两个安装目录，否则 agent 的壳脚本找不到 node 等。 */
    @Test
    fun childPathContainsCommonInstallDirs() {
        val path = AgentDiscovery.searchPath()
        assertTrue("/opt/homebrew/bin" in path, path)
        assertTrue("/usr/local/bin" in path, path)
    }
}
