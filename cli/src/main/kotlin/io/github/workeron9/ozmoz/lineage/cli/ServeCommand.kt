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
        help = "监听端口（默认 ${OzmlLineageServer.DEFAULT_PORT}）",
    )
        .int()
        .default(OzmlLineageServer.DEFAULT_PORT)

    override fun help(context: Context): String =
        "启动本地 HTTP 服务（/api/health、/api/parse、/api/lineage、/api/impact、/api/path）。"

    override fun run() {
        echo("ozml serve  http://$host:$port  （Ctrl+C 停止）")
        // wait = true：阻塞到进程退出；Ctrl+C 由 JVM/调用方终止（Netty 注册了关闭钩子）。
        OzmlLineageServer.start(port = port, host = host, wait = true)
    }
}
