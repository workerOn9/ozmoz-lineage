package io.github.workeron9.ozmoz.lineage.server

import java.nio.file.Path

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * `ozml serve` 的启动入口——单进程本地 HTTP 服务（`02-架构/web子项目设计.md` §4）。
 *
 * 定位：**本地单用户工具**，不是应用服务器——不做鉴权、不持久化、
 * 默认只绑本机回环（`DEFAULT_HOST`）；把 SQL 或元数据送出本机违背项目定位
 * （离线优先），所以默认绑定不可被局域网访问。
 *
 * `web/` 静态托管（`--web-root <dir>`）：SPA dist 挂到 `/`，未知非 `/api` 路径
 * 回落 `index.html`（web 设计稿 §6.4 形态 1）；不给 `--web-root` 则只承载 `/api`。
 */
public object OzmlLineageServer {

    /** web 设计稿 §4.4 的默认端口（与 CLI 帮助一致）。 */
    public const val DEFAULT_PORT: Int = 8765

    /** 默认只绑本机回环：本地工具不主动暴露到局域网。 */
    public const val DEFAULT_HOST: String = "127.0.0.1"

    /**
     * 版本号：jar 清单里的 `Implementation-Version`（`server/build.gradle.kts` 注入）；
     * 从源码类路径跑（测试 / 开发）没有清单信息，回落 `"dev"`。
     */
    public val version: String
        get() = javaClass.`package`?.implementationVersion ?: "dev"

    /**
     * 启动服务器并返回关闭句柄（`AutoCloseable`，不泄漏具体引擎类型——换引擎
     * 只改本文件）。[wait] = true 时阻塞直到进程退出（CLI 用法）；测试传 false 后
     * 自行 `close()` 停止。
     *
     * [webRoot] 非空时把该目录作为静态资源托管到 `/`（SPA：未知非 `/api` 路径
     * 回落 index.html），与 `/api` 共存于同一端口（web 设计稿 §6.4 形态 1）。
     */
    public fun start(
        port: Int = DEFAULT_PORT,
        host: String = DEFAULT_HOST,
        webRoot: Path? = null,
        wait: Boolean = true,
    ): AutoCloseable {
        val server = embeddedServer(Netty, port = port, host = host) { serverModule(webRoot) }
        server.start(wait = wait)
        return AutoCloseable { server.stop(1000, 1000) }
    }
}
