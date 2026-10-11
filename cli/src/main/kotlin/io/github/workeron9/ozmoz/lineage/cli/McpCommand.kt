package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import io.github.workeron9.ozmoz.lineage.server.OzmlMcpServer

/**
 * `ozml mcp` —— MCP（Model Context Protocol）stdio 服务端（计划 §0 的 `mcp` 子命令）。
 *
 * 把解析 / 血缘 / 影响面 / 路径 / 校验 / 排版 / 转换七个 CLI 能力以 MCP 工具形态
 * 暴露给 AI 助手（Claude Desktop / Cursor 等）：`ozml_engines` / `ozml_parse` /
 * `ozml_lineage` / `ozml_impact` / `ozml_path` / `ozml_validate` / `ozml_format` /
 * `ozml_convert`（见 `server` 模块 `OzmlMcpServer` 的 KDoc）。
 *
 * **stdio 纪律**：stdout 是协议信道，启动后任何东西都不写 stdout——就绪提示走
 * stderr；服务端本体在 `server` 模块（与 Ktor 入口同层），本类只负责生命周期。
 */
public class McpCommand : CliktCommand(name = "mcp") {

    override fun help(context: Context): String =
        "启动 MCP server（stdio）：把 ozml 能力作为工具暴露给 AI 助手（Ctrl+C 停止）。"

    override fun run() {
        echo("ozml mcp  MCP server 运行于 stdio（协议走 stdout；Ctrl+C 停止）", err = true)
        OzmlMcpServer.start().use { OzmlMcpServer.blockUntilShutdown() }
    }
}
