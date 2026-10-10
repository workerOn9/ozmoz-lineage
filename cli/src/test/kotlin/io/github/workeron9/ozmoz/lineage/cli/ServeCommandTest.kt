package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.github.workeron9.ozmoz.lineage.server.OzmlLineageServer
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * `ozml serve` 接线测试：
 * - 子命令已注册（`--help` 可见）；
 * - 真实 HTTP 冒烟：`OzmlLineageServer.start(wait=false)`（即 `ServeCommand.run`
 *   调用的同一路径）起的 Netty 真在接受请求并回 `/api/health`。
 *
 * 端点的详细行为断言在 `server` 模块（ktor test host，21 例）；这里只验 CLI 侧组合。
 */
class ServeCommandTest {

    @Test
    fun `serve 子命令已注册且 help 可见`() {
        val result = OzmlCommand().subcommands(ParseCommand(), LineageCommand(), ImpactCommand(), ServeCommand())
            .test("--help")

        assertContains(result.stdout, "serve")
    }

    @Test
    fun `真实 HTTP 冒烟 health 端点`() {
        // 先占一个端口再释放：常规冒烟做法，端口极小概率被其它进程抢走，失败重跑即可。
        val port = ServerSocket(0).use { it.localPort }
        val server = OzmlLineageServer.start(port = port, wait = false)
        try {
            val client = HttpClient.newHttpClient()
            val response = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())
            assertContains(response.body(), "\"status\":\"ok\"")
        } finally {
            server.close()
        }
    }
}
