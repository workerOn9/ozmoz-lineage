package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.workeron9.ozmoz.lineage.server.OzmlLineageServer

/**
 * `ozml serve` —— 启动本地 HTTP 服务（Ktor / Netty），暴露 `/api` 系列端点
 * （health / engines / parse / lineage / impact / path），供 Web UI 与脚本调用。
 *
 * 与 `parse` / `lineage` / `impact` 同一组合根：`server` 模块提供路由与 HTTP 契约，
 * CLI 只负责参数解析与服务生命周期。服务是**本地单用户**工具：默认只绑回环地址。
 */
public class ServeCommand : CliktCommand(name = "serve") {

    private val host: String by option(
        "--host",
        help = "监听地址（默认本机回环，本地工具不对外暴露）",
    )
        .default(OzmlLineageServer.DEFAULT_HOST)

    private val port: Int by option(
        "--port",
        help = "监听端口（默认 ${OzmlLineageServer.DEFAULT_PORT}；容器平台可用 PORT 环境变量覆盖）",
    )
        .int()
        .default(resolvePort(System.getenv("PORT"), OzmlLineageServer.DEFAULT_PORT))

    private val webRoot: String? by option(
        "--web-root",
        help = "静态托管 web/ 构建产物目录（SPA：未知非 /api 路径回落 index.html）",
    )

    private val graphRoot: String? by option(
        "--graph-root",
        help = "图库白名单根目录（ADR-0015）：开启 /api/graphs 与 /api/impact、/api/path 的 graph 字段，" +
            "只允许浏览该目录下的 *.db；**不给则图库能力整体关闭**（公开部署有意不开）",
    )

    override fun help(context: Context): String =
        "启动本地 HTTP 服务（/api/health、/api/parse、/api/lineage、/api/impact、/api/path）。"

    override fun run() {
        val root = webRoot?.let { java.nio.file.Path.of(it) }
        val graphs = graphRoot?.let { java.nio.file.Path.of(it) }
        echo("ozml serve  http://${host}:${port}  （Ctrl+C 停止）")
        if (graphs != null) echo("图库浏览已开启：${graphRoot}（/api/graphs）")
        // wait = true：阻塞到进程退出；Ctrl+C 由 JVM/调用方终止（Netty 注册了关闭钩子）。
        OzmlLineageServer.start(port = port, host = host, webRoot = root, graphRoot = graphs, wait = true)
    }
}

/**
 * 解析容器平台注入的 `PORT` 环境变量（Vercel / Fly 等均按 12-factor 约定注入）：
 * 合法端口（1–65535）用之，缺省 / 非数字 / 越界一律回落 [fallback]，不让坏环境变量把服务起崩。
 */
internal fun resolvePort(envPort: String?, fallback: Int): Int =
    envPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: fallback
