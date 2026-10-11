package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ozml format` 端到端：多行排版按子句换行、`--single-line` 规范单行、
 * `--json` 稳定形状、注释丢失落 WARNING、语法错误非零退出、能力表路由。
 */
class FormatCommandTest {

    private val command = FormatCommand()

    private fun tempFile(prefix: String, content: String): File {
        val f = Files.createTempFile(prefix, ".sql").toFile()
        f.writeText(content)
        f.deleteOnExit()
        return f
    }

    @Test
    fun `多行排版按子句换行`() {
        val sql = tempFile("ozml-format", "select t.a, u.b from t join u on t.id=u.id where t.x=1 group by t.a,u.b order by t.a desc limit 10")
        val result = command.test("-f ${sql.absolutePath}")
        assertEquals(0, result.statusCode, result.stderr)
        val lines = result.stdout.trim().lines()
        assertEquals("SELECT t.a, u.b", lines[0])
        assertTrue(lines.contains("FROM t"))
        assertTrue(lines.contains("JOIN u ON t.id = u.id"))
        assertTrue(lines.contains("WHERE t.x = 1"))
        assertTrue(lines.contains("GROUP BY t.a, u.b"))
        assertTrue(lines.contains("ORDER BY t.a DESC"))
        assertTrue(lines.contains("LIMIT 10"))
    }

    @Test
    fun `--single-line 产规范化单行`() {
        val sql = tempFile("ozml-format", "select a,b from t where x=1")
        val result = command.test("-f ${sql.absolutePath} --single-line")
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("SELECT a, b FROM t WHERE x = 1", result.stdout.trim())
    }

    @Test
    fun `--json 输出稳定形状`() {
        val sql = tempFile("ozml-format", "select a from t")
        val result = command.test("-f ${sql.absolutePath} --json")
        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.stdout, "\"engine\": \"jsqlparser\"")
        assertContains(result.stdout, "\"sql\":")
        assertContains(result.stdout, "\"diagnostics\": []")
    }

    @Test
    fun `注释丢失落 WARNING 且 SQL 仍输出`() {
        val sql = tempFile("ozml-format", "select a /* keep me */ from t")
        val result = command.test("-f ${sql.absolutePath}")
        assertEquals(0, result.statusCode, result.stderr)
        // stdout 是干净 SQL（注释被丢），WARNING 走 stderr
        assertEquals("SELECT a\nFROM t", result.stdout.trim())
        assertContains(result.stderr, "jsqlparser.comment_dropped")
    }

    @Test
    fun `语法错误非零退出`() {
        val sql = tempFile("ozml-format", "select from where")
        val result = command.test("-f ${sql.absolutePath}")
        assertEquals(1, result.statusCode, result.stdout)
        assertContains(result.stderr, FormatCommand.CODE_FORMAT_SKIPPED)
    }

    @Test
    fun `SQL 为空非零退出`() {
        val sql = tempFile("ozml-format", "")
        val result = command.test("-f ${sql.absolutePath}")
        assertEquals(1, result.statusCode, result.stdout)
        assertContains(result.stderr, FormatCommand.CODE_FORMAT_SKIPPED)
    }

    @Test
    fun `不支持的引擎给可解释的错`() {
        val sql = tempFile("ozml-format", "select a from t")
        val result = command.test("--engine jooq -f ${sql.absolutePath}")
        assertTrue(result.statusCode != 0, result.stdout)
        assertContains(result.stderr, "jooq")
        assertContains(result.stderr, "不支持排版")
    }
}
