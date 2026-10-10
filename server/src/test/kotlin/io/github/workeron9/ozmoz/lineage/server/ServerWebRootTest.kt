package io.github.workeron9.ozmoz.lineage.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `--web-root` 静态托管测试：真实 Ktor 路由，临时目录当 dist。
 *
 * 契约（web 设计稿 §6.4）：
 * - `/` 与实体文件正常返回；
 * - 未知**非 /api** 路径回落 `index.html`（SPA 前端路由）；
 * - 未知 `/api/...` **不回 index.html**，给 JSON 404（前端按错误体分支）；
 * - 路径穿越出不了 webRoot。
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ServerWebRootTest {

    private fun withWebRoot(block: (Path) -> Unit) {
        val dir = createTempDirectory("ozml-webroot-test")
        try {
            dir.resolve("index.html").writeText("<html><body>SPA</body></html>")
            dir.resolve("app.js").writeText("console.log('ozml')")
            Files.createDirectories(dir.resolve("assets"))
            dir.resolve("assets").resolve("logo.txt").writeText("logo")
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun get(path: String, webRoot: Path): Pair<Int, String> = kotlinx.coroutines.runBlocking {
        var status = -1
        var text = ""
        testApplication {
            application { serverModule(webRoot) }
            val response = client.get(path)
            status = response.status.value
            text = response.bodyAsText()
        }
        status to text
    }

    @Test
    fun `根路径与实体文件正常返回`() = withWebRoot { root ->
        val (s1, b1) = get("/", root)
        assertEquals(HttpStatusCode.OK.value, s1)
        assertTrue(b1.contains("SPA"))

        val (s2, b2) = get("/app.js", root)
        assertEquals(HttpStatusCode.OK.value, s2)
        assertTrue(b2.contains("ozml"))

        val (s3, b3) = get("/assets/logo.txt", root)
        assertEquals(HttpStatusCode.OK.value, s3)
        assertTrue(b3.contains("logo"))
    }

    @Test
    fun `未知非 api 路径回落 index html`() = withWebRoot { root ->
        val (status, body) = get("/some/spa/route", root)
        assertEquals(HttpStatusCode.OK.value, status)
        assertTrue(body.contains("SPA"))
    }

    @Test
    fun `未知 api 路径回 JSON 404 而非 index html`() = withWebRoot { root ->
        val (status, body) = get("/api/nonexistent", root)
        assertEquals(HttpStatusCode.NotFound.value, status)
        assertTrue(body.contains("\"error\":\"not_found\""), body)
    }

    @Test
    fun `路径穿越出不了 webRoot`() = withWebRoot { root ->
        // 上级目录放一个秘密文件；各种编码的 ../ 都不应读到它。
        val outside = root.parent.resolve("secret-test.txt")
        outside.writeText("top-secret")
        try {
            val (status, body) = get("/%2e%2e/secret-test.txt", root)
            assertTrue(body != "top-secret", "路径穿越读到了 webRoot 外的文件")
            assertEquals(HttpStatusCode.OK.value, status) // 回落 index.html 或 404 均可，只是不能泄露
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `api 路由不受 webRoot 影响`() = withWebRoot { root ->
        val (status, body) = get("/api/health", root)
        assertEquals(HttpStatusCode.OK.value, status)
        assertTrue(body.contains("\"status\":\"ok\""), body)
    }
}
