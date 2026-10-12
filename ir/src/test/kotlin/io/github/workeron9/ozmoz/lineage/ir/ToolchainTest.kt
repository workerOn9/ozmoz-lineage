package io.github.workeron9.ozmoz.lineage.ir

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 工具链守门：真实运行的 JDK 大版本必须与 version catalog 钉的 `jdk` 一致。
 *
 * 期望值由根构建注入（`ozmoz.toolchain.jdk` 系统属性），本测试不硬编码数字——
 * catalog 升级时这里自动跟着走，它抓的是「工具链与 catalog 漂移」
 * （比如 CI 镜像装错版本、本机 PATH 上的 JDK 悄悄换了）。
 */
class ToolchainTest {
    @Test
    fun runsOnCatalogJdk() {
        assertEquals(
            System.getProperty("ozmoz.toolchain.jdk")?.toInt(),
            Runtime.version().feature(),
        )
    }
}
