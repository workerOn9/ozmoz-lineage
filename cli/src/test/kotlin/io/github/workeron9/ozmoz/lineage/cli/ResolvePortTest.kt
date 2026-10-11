package io.github.workeron9.ozmoz.lineage.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/** `ozml serve` 的 `PORT` 环境变量解析（容器平台注入，Vercel 部署适配）。 */
class ResolvePortTest {

    @Test
    fun `合法端口被采用`() {
        assertEquals(8080, resolvePort("8080", 8765))
        assertEquals(1, resolvePort("1", 8765))
        assertEquals(65535, resolvePort("65535", 8765))
    }

    @Test
    fun `缺省或非数字回落默认端口`() {
        assertEquals(8765, resolvePort(null, 8765))
        assertEquals(8765, resolvePort("", 8765))
        assertEquals(8765, resolvePort("abc", 8765))
        assertEquals(8765, resolvePort("80x", 8765))
    }

    @Test
    fun `越界端口回落默认端口`() {
        assertEquals(8765, resolvePort("0", 8765))
        assertEquals(8765, resolvePort("65536", 8765))
        assertEquals(8765, resolvePort("-1", 8765))
    }
}
