package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ozml uninstall` 的端到端：只删受管安装（`install.sh` / `ozml upgrade` 的布局），
 * 默认先展示再确认，`--dry-run` 只预览，指向非受管目录时拒绝。
 *
 * 注意 Clikt 有两个 `test` 重载——`test(argv: String, stdin: String, …)` 与
 * `test(vararg argv: String, …)`；两参位置调用会被绑到前者（`--root` 变 argv、路径变
 * stdin）。故一律经 [exec] 用 spread 走 vararg 重载，路径带空格也安全。
 */
class UninstallCommandTest {

    @Test
    fun `dry-run 只预览不改动`() {
        withInstall { root, binDir ->
            val result = cmd().exec("--root", root.toString(), "--dry-run")
            assertEquals(0, result.statusCode)
            assertContains(result.stdout, "将卸载 ozml")
            assertContains(result.stdout, "0.1.0")
            assertContains(result.stdout, "--dry-run：未做任何改动")
            assertTrue(root.exists(), "dry-run 不应删除安装根")
            assertTrue(binDir.resolve("ozml").exists(), "dry-run 不应删除启动器")
        }
    }

    @Test
    fun `yes 直接删除安装根与启动器`() {
        withInstall { root, binDir ->
            val result = cmd().exec("--root", root.toString(), "--yes")
            assertEquals(0, result.statusCode)
            assertContains(result.stdout, "已卸载")
            assertFalse(root.exists(), "安装根应被删除")
            assertFalse(binDir.resolve("ozml").exists(), "启动器应被删除")
            assertTrue(binDir.exists(), "bin 目录本身应保留（可能放别的工具）")
        }
    }

    @Test
    fun `默认确认被拒则不改动`() {
        withInstall { root, binDir ->
            val result = cmd { false }.exec("--root", root.toString())
            assertEquals(0, result.statusCode)
            assertContains(result.stdout, "已取消")
            assertTrue(root.exists())
            assertTrue(binDir.resolve("ozml").exists())
        }
    }

    @Test
    fun `确认同意则删除`() {
        withInstall { root, binDir ->
            val result = cmd { true }.exec("--root", root.toString())
            assertEquals(0, result.statusCode)
            assertContains(result.stdout, "已卸载")
            assertFalse(root.exists())
            assertFalse(binDir.resolve("ozml").exists())
        }
    }

    @Test
    fun `已装版本都列出来`() {
        withInstall(versions = listOf("0.0.9", "0.1.0")) { root, _ ->
            val result = cmd().exec("--root", root.toString(), "--dry-run")
            assertContains(result.stdout, "0.0.9, 0.1.0")
        }
    }

    @Test
    fun `未安装时友好退出不报错`() {
        val missing = Files.createTempDirectory("ozml-none").resolve("nope")
        try {
            val result = cmd().exec("--root", missing.toString(), "--yes")
            assertEquals(0, result.statusCode)
            assertContains(result.stdout, "未找到 ozml 安装")
        } finally {
            deleteTree(missing.parent)
        }
    }

    @Test
    fun `非受管目录拒绝删除`() {
        val dir = Files.createTempDirectory("ozml-unmanaged")
        try {
            Files.writeString(dir.resolve("important.txt"), "别删我")
            val result = cmd().exec("--root", dir.toString(), "--yes")
            assertEquals(1, result.statusCode)
            assertContains(result.stderr, "拒绝卸载")
            assertTrue(dir.resolve("important.txt").exists(), "不该动非受管目录")
        } finally {
            deleteTree(dir)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun cmd(confirm: (String) -> Boolean = { false }): UninstallCommand = UninstallCommand(confirm)

    /** 走 Clikt 的 vararg `test` 重载（spread 强制），避免位置参数被绑到 `stdin`。 */
    private fun UninstallCommand.exec(vararg args: String) = test(*args)

    private fun withInstall(
        versions: List<String> = listOf("0.1.0"),
        block: (root: Path, binDir: Path) -> Unit,
    ) {
        val base = Files.createTempDirectory("ozml-uninstall")
        val root = base.resolve("root")
        val binDir = base.resolve("bin")
        try {
            for (v in versions) {
                Files.createDirectories(root.resolve("versions/$v/bin"))
                Files.writeString(root.resolve("versions/$v/bin/ozml"), "#!/bin/sh\n")
                Files.createDirectories(root.resolve("versions/$v/lib"))
                Files.writeString(root.resolve("versions/$v/lib/x.jar"), "x")
            }
            Files.writeString(root.resolve("current"), "${versions.last()}\n")
            Files.writeString(root.resolve("bin-dir"), "$binDir\n")
            Files.createDirectories(binDir)
            Files.writeString(binDir.resolve("ozml"), "#!/bin/sh\n")
            block(root, binDir)
        } finally {
            deleteTree(base)
        }
    }

    private fun deleteTree(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
