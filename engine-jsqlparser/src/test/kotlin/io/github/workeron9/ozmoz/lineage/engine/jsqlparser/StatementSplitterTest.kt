package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [StatementSplitter] 单测：**顶层分号**才是语句边界，引号 / 注释里的分号不是。
 *
 * 切分只服务「整段解析失败后的逐语句重试」，所以判定标准是「切出来的片段文本对不对」，
 * 而不是「解析结果对不对」（后者在 [ErrorTolerantTest] 里端到端验证）。
 */
class StatementSplitterTest {

    private fun texts(sql: String): List<String> =
        StatementSplitter.split(sql).map { sql.substring(it.start, it.endExclusive) }

    @Test
    fun `按顶层分号切分且保留片段原文`() {
        assertEquals(listOf("SELECT 1", " SELECT 2"), texts("SELECT 1; SELECT 2"))
    }

    @Test
    fun `无分号只有一段且有尾随内容`() {
        assertEquals(listOf("SELECT 1"), texts("SELECT 1"))
        // 末段不带分号也算一段。
        assertEquals(listOf("SELECT 1", " SELECT 2"), texts("SELECT 1; SELECT 2"))
    }

    @Test
    fun `空片段与纯空白片段不产出`() {
        assertEquals(emptyList(), texts(";;;   "))
        // 边界分号之间的空白留在片段里（原文切片，不做 trim）。
        assertEquals(listOf(" SELECT 1 "), texts(";; SELECT 1 ;"))
    }

    @Test
    fun `纯注释片段不产出`() {
        assertEquals(emptyList(), texts("-- just a comment"))
        assertEquals(emptyList(), texts("/* block */"))
        assertEquals(listOf("SELECT 1"), texts("SELECT 1; -- trailing"))
        assertEquals(listOf("/* lead */ SELECT 1 /* tail */"), texts("/* lead */ SELECT 1 /* tail */;"))
    }

    @Test
    fun `注释里的分号不是边界`() {
        assertEquals(listOf("SELECT 1 -- a;b\n", " SELECT 2"), texts("SELECT 1 -- a;b\n; SELECT 2"))
        assertEquals(listOf("SELECT 1 /* a;b */", " SELECT 2"), texts("SELECT 1 /* a;b */; SELECT 2"))
        // 块注释按 JSqlParser 口径支持嵌套。
        assertEquals(listOf("SELECT 1 /* a /* b;c */ d */", " SELECT 2"), texts("SELECT 1 /* a /* b;c */ d */; SELECT 2"))
    }

    @Test
    fun `单引号字符串里的分号不是边界`() {
        assertEquals(listOf("SELECT a FROM t WHERE b = 'x;y'", " SELECT 1"), texts("SELECT a FROM t WHERE b = 'x;y'; SELECT 1"))
        // 成对引号转义与反斜杠转义都不提前结束字符串。
        assertEquals(listOf("SELECT 'it''s;ok'", " SELECT 1"), texts("SELECT 'it''s;ok'; SELECT 1"))
        assertEquals(listOf("SELECT 'it\\'s;ok'", " SELECT 1"), texts("SELECT 'it\\'s;ok'; SELECT 1"))
    }

    @Test
    fun `双引号反引号方括号里的分号不是边界`() {
        assertEquals(listOf("SELECT \"a;b\" FROM t", " SELECT 1"), texts("SELECT \"a;b\" FROM t; SELECT 1"))
        assertEquals(listOf("SELECT `a;b` FROM t", " SELECT 1"), texts("SELECT `a;b` FROM t; SELECT 1"))
        assertEquals(listOf("SELECT [a;b] FROM t", " SELECT 1"), texts("SELECT [a;b] FROM t; SELECT 1"))
    }

    @Test
    fun `美元引号字面量里的分号不是边界`() {
        assertEquals(listOf("SELECT \$\$ a;b \$\$", " SELECT 1"), texts("SELECT \$\$ a;b \$\$; SELECT 1"))
        assertEquals(listOf("SELECT \$tag\$ a;b \$tag\$", " SELECT 1"), texts("SELECT \$tag\$ a;b \$tag\$; SELECT 1"))
        // `$1` 是位置参数，不是美元引号开头——它后面的分号仍是边界。
        assertEquals(listOf("SELECT \$1", " SELECT 2"), texts("SELECT \$1; SELECT 2"))
    }
}
