package io.github.workeron9.ozmoz.lineage.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ozml upgrade` 的测试：纯函数直接断言；完整升级流程用**真实本地 HTTP 服务**
 * 驱动（不做 mock 网络），覆盖下载 → 校验 → 解压 → 落盘 → 切 current。
 */
class UpgradeCommandTest {

    // ── 纯函数 ──────────────────────────────────────────────────────────────

    @Test
    fun `platformId 按 os 与 arch 归一`() {
        assertEquals("macos-arm64", SelfUpdater.platformId("Mac OS X", "aarch64"))
        assertEquals("macos-x64", SelfUpdater.platformId("darwin", "x86_64"))
        assertEquals("linux-x64", SelfUpdater.platformId("Linux", "amd64"))
        assertEquals("windows-arm64", SelfUpdater.platformId("Windows 11", "ARM64"))
    }

    @Test
    fun `assetName 与 normalizeVersion`() {
        assertEquals("ozml-0.2.0-macos-arm64.zip", SelfUpdater.assetName("0.2.0", "macos-arm64"))
        assertEquals("0.2.0", SelfUpdater.normalizeVersion("v0.2.0"))
        assertEquals("0.2.0", SelfUpdater.normalizeVersion(" 0.2.0 "))
        assertEquals("0.1.0-SNAPSHOT", SelfUpdater.normalizeVersion("0.1.0-SNAPSHOT"))
    }

    @Test
    fun `parseChecksums 兼容两种格式`() {
        val text = """
            abc123def  ozml-0.2.0-linux-x64.zip
            FED321 *ozml-0.2.0-macos-arm64.tar.gz
            # 注释行会被忽略（无文件名，跳过）

        """.trimIndent()
        val map = SelfUpdater.parseChecksums(text)
        assertEquals("abc123def", map["ozml-0.2.0-linux-x64.zip"])
        assertEquals("fed321", map["ozml-0.2.0-macos-arm64.tar.gz"])
    }

    @Test
    fun `sha256 对已知内容稳定`() {
        val f = Files.createTempFile("ozml-sha", ".txt")
        try {
            Files.writeString(f, "abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SelfUpdater.sha256(f),
            )
        } finally {
            Files.deleteIfExists(f)
        }
    }

    // ── 解压 ────────────────────────────────────────────────────────────────

    @Test
    fun `unzipStripTop 剥掉顶层目录并给 bin 加可执行位`() {
        val dir = Files.createTempDirectory("ozml-unzip")
        try {
            val zip = dir.resolve("a.zip")
            writeZip(zip, mapOf("ozml/bin/ozml" to "#!/bin/sh\n", "ozml/lib/x.jar" to "jar"))
            val out = dir.resolve("out")
            Files.createDirectories(out)
            SelfUpdater.unzipStripTop(zip, out)

            assertTrue(out.resolve("bin/ozml").exists())
            assertTrue(out.resolve("lib/x.jar").exists())
            assertFalse(out.resolve("ozml").exists())
            assertTrue(Files.isExecutable(out.resolve("bin/ozml")))
        } finally {
            deleteTree(dir)
        }
    }

    @Test
    fun `unzipStripTop 给 runtime bin 下的文件也加可执行位`() {
        val dir = Files.createTempDirectory("ozml-unzip-rt")
        try {
            val zip = dir.resolve("a.zip")
            writeZip(zip, mapOf("ozml/runtime/bin/java" to "fake-java"))
            val out = dir.resolve("out")
            Files.createDirectories(out)
            SelfUpdater.unzipStripTop(zip, out)
            assertTrue(Files.isExecutable(out.resolve("runtime/bin/java")))
        } finally {
            deleteTree(dir)
        }
    }

    @Test
    fun `unzipStripTop 拒绝路径逃逸条目`() {
        val dir = Files.createTempDirectory("ozml-slip")
        try {
            val zip = dir.resolve("evil.zip")
            writeZip(zip, mapOf("ozml/../escape.txt" to "x"))
            val out = dir.resolve("out")
            Files.createDirectories(out)
            assertFailsWith<IllegalStateException> { SelfUpdater.unzipStripTop(zip, out) }
        } finally {
            deleteTree(dir)
        }
    }

    // ── 完整升级流程（本地 HTTP 服务）────────────────────────────────────────

    @Test
    fun `install 下载校验解压落盘`() {
        val payload = mapOf(
            "ozml/bin/ozml" to "#!/bin/sh\necho hi\n",
            "ozml/lib/ozml.jar" to "jar-bytes",
            "ozml/runtime/bin/java" to "fake-java",
        )
        withServer(payload, version = "9.9.9") { base ->
            val root = Files.createTempDirectory("ozml-root")
            try {
                val updater = newUpdater(base)
                val installed = updater.install(root, "9.9.9")

                assertEquals(root.resolve("versions/9.9.9"), installed)
                assertTrue(installed.resolve("bin/ozml").exists())
                assertEquals("jar-bytes", installed.resolve("lib/ozml.jar").readText())
                assertTrue(Files.isExecutable(installed.resolve("bin/ozml")))
                assertTrue(Files.isExecutable(installed.resolve("runtime/bin/java")))

                RootLayout.writeCurrent(root, "9.9.9")
                assertEquals("9.9.9", RootLayout.currentVersion(root))
            } finally {
                deleteTree(root)
            }
        }
    }

    @Test
    fun `install 校验失败时中止且不留安装`() {
        val payload = mapOf("ozml/bin/ozml" to "x")
        withServer(payload, version = "9.9.9", corruptChecksum = true) { base ->
            val root = Files.createTempDirectory("ozml-root")
            try {
                val updater = newUpdater(base)
                assertFailsWith<IllegalStateException> { updater.install(root, "9.9.9") }
                assertFalse(root.resolve("versions/9.9.9").exists())
            } finally {
                deleteTree(root)
            }
        }
    }

    @Test
    fun `ensureShim 写出的 shim 指向 current`() {
        val root = Files.createTempDirectory("ozml-shim")
        val binDir = Files.createTempDirectory("ozml-bindir")
        try {
            Files.writeString(root.resolve("bin-dir"), "$binDir\n")
            RootLayout.writeCurrent(root, "9.9.9")
            RootLayout.ensureShim(root)

            val shim = binDir.resolve("ozml")
            assertTrue(shim.exists())
            assertTrue(Files.isExecutable(shim))
            val text = shim.readText()
            assertTrue(text.contains("current"))
            assertTrue(text.contains("versions/\$v/bin/ozml"))
        } finally {
            deleteTree(root)
            deleteTree(binDir)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun newUpdater(base: String): SelfUpdater = SelfUpdater(
        releaseBaseUrl = "$base/releases",
        apiBaseUrl = "$base/api",
        token = null,
        platform = "test-x64",
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
    )

    /** 起一个真实 HTTP 服务，暴露 `/api/releases/latest` 与 `/releases/download/v<版本>/…`。 */
    private fun withServer(
        payload: Map<String, String>,
        version: String,
        corruptChecksum: Boolean = false,
        block: (base: String) -> Unit,
    ) {
        val zipBytes = zipBytes(payload)
        val sha = sha256Hex(zipBytes)
        val checksum = if (corruptChecksum) "deadbeef" else sha
        val asset = SelfUpdater.assetName(version, "test-x64")

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/releases/latest") { exchange ->
            respond(exchange, 200, """{"tag_name":"v$version"}""")
        }
        server.createContext("/releases/download/v$version/$asset") { exchange ->
            respond(exchange, 200, zipBytes, "application/zip")
        }
        server.createContext("/releases/download/v$version/checksums.txt") { exchange ->
            respond(exchange, 200, "$checksum  $asset\n")
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) =
        respond(exchange, status, body.toByteArray(Charsets.UTF_8), "text/plain")

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray, contentType: String) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun zipBytes(entries: Map<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun writeZip(target: Path, entries: Map<String, String>) {
        Files.newOutputStream(target).use { out ->
            ZipOutputStream(out).use { zip ->
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun deleteTree(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
