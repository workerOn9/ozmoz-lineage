package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ozml convert` 端到端：成功输出方言化 SQL、门禁拦截不输出半吊子 SQL（非零退出）、
 * 能力表路由（不支持的引擎 / 未注册方言给可解释的错）、`--format json` 结构。
 */
class ConvertCommandTest {

    private val command = ConvertCommand()

    private fun tempSql(content: String): File {
        val f = Files.createTempFile("ozml-convert-test", ".sql").toFile()
        f.writeText(content)
        f.deleteOnExit()
        return f
    }

    @Test
    fun `mysql 转 postgresql 输出方言化 SQL`() {
        val f = tempSql("SELECT id FROM customer ORDER BY id LIMIT 10")
        val result = command.test(
            "--from mysql --to postgresql -f ${f.absolutePath}",
        )
        assertEquals(0, result.statusCode, result.stderr)
        val out = result.stdout.uppercase()
        assertContains(out, "FETCH NEXT")
        assertContains(out, "\"CUSTOMER\"")
    }

    @Test
    fun `mssql 静默丢弃 LIMIT 被门禁拦截 非零退出不输出 SQL`() {
        val f = tempSql("SELECT id FROM customer ORDER BY id LIMIT 10")
        val result = command.test(
            "--from mysql --to mssql -f ${f.absolutePath}",
        )
        assertEquals(1, result.statusCode, result.stderr)
        // stdout 不许有 SQL；诊断走 stderr（hello Clikt 的 err 通道）
        assertTrue(result.stdout.isBlank(), "门禁拦截后不得输出 SQL：${result.stdout}")
        assertContains(result.stderr, "convert.render_unverified")
    }

    @Test
    fun `未注册方言给出带合法值清单的错`() {
        val f = tempSql("SELECT 1")
        val result = command.test(
            "--to ansi -f ${f.absolutePath}",
        )
        assertTrue(result.statusCode != 0, result.stdout)
        // option 前置 require 报错（Clikt UsageError）走 usage 输出
        assertContains(result.stderr, "ansi")
    }

    @Test
    fun `--format json 输出稳定结构`() {
        val f = tempSql("SELECT id FROM customer LIMIT 3")
        val result = command.test(
            "--from mysql --to postgresql --format json -f ${f.absolutePath}",
        )
        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.stdout, "\"engine\": \"calcite\"")
        assertContains(result.stdout, "\"toDialect\": \"postgresql\"")
        assertContains(result.stdout, "\"sql\":")
    }

    @Test
    fun `缺 --to 报错`() {
        val f = tempSql("SELECT 1")
        val result = command.test("-f ${f.absolutePath}")
        assertTrue(result.statusCode != 0, result.stdout)
    }

    @Test
    fun `缺 --file 报错`() {
        val result = command.test("--to postgresql")
        assertTrue(result.statusCode != 0, result.stdout)
    }
}
