package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ozml validate` 端到端：合法 SQL 通过（exit 0 + JSON diagnostics 空）、
 * 校验发现的问题给 ERROR 诊断（exit 1）、没跑成给可解释的错、
 * `--schema` 必填、能力表路由。
 */
class ValidateCommandTest {

    private val command = ValidateCommand()

    private fun tempFile(prefix: String, content: String): File {
        val f = Files.createTempFile(prefix, ".sql").toFile()
        f.writeText(content)
        f.deleteOnExit()
        return f
    }

    @Test
    fun `合法 SQL 通过校验 exit 0 且 JSON diagnostics 为空`() {
        val schema = tempFile("ozml-validate-schema", "CREATE TABLE orders (id INT, amount DECIMAL(10,2));")
        val sql = tempFile("ozml-validate-sql", "SELECT id FROM orders WHERE amount > 1")
        val result = command.test(
            "--schema ${schema.absolutePath} -f ${sql.absolutePath}",
        )
        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.stdout, "\"engine\": \"calcite\"")
        assertContains(result.stdout, "\"diagnostics\": []")
    }

    @Test
    fun `未知列给 ERROR 诊断 非零退出`() {
        val schema = tempFile("ozml-validate-schema", "CREATE TABLE orders (id INT);")
        val sql = tempFile("ozml-validate-sql", "SELECT foo FROM orders")
        val result = command.test(
            "--schema ${schema.absolutePath} -f ${sql.absolutePath}",
        )
        assertEquals(1, result.statusCode, result.stderr)
        // stdout JSON 是可解析的产物（诊断在里面），stderr 有同口径摘要
        assertContains(result.stdout, "calcite.validate_error")
        assertContains(result.stderr, "calcite.validate_error")
    }

    @Test
    fun `未知表给 ERROR 诊断 非零退出`() {
        val schema = tempFile("ozml-validate-schema", "CREATE TABLE orders (id INT);")
        val sql = tempFile("ozml-validate-sql", "SELECT * FROM nope")
        val result = command.test(
            "--schema ${schema.absolutePath} -f ${sql.absolutePath}",
        )
        assertEquals(1, result.statusCode, result.stderr)
        assertContains(result.stdout, "nope")
    }

    @Test
    fun `缺 --schema 参数报错`() {
        val sql = tempFile("ozml-validate-sql", "SELECT 1")
        val result = command.test("-f ${sql.absolutePath}")
        assertTrue(result.statusCode != 0, result.stdout)
        assertContains(result.stderr, "--schema")
    }

    @Test
    fun `不支持的引擎给可解释的错`() {
        val schema = tempFile("ozml-validate-schema", "CREATE TABLE orders (id INT);")
        val sql = tempFile("ozml-validate-sql", "SELECT id FROM orders")
        val result = command.test(
            "--engine jooq --schema ${schema.absolutePath} -f ${sql.absolutePath}",
        )
        assertTrue(result.statusCode != 0, result.stdout)
        assertContains(result.stderr, "jooq")
    }

    @Test
    fun `SQL 为空 校验没跑成 非零退出`() {
        val schema = tempFile("ozml-validate-schema", "CREATE TABLE orders (id INT);")
        val sql = tempFile("ozml-validate-sql", "")
        val result = command.test(
            "--schema ${schema.absolutePath} -f ${sql.absolutePath}",
        )
        assertEquals(1, result.statusCode, result.stderr)
        assertContains(result.stderr, ValidateCommand.CODE_VALIDATE_SKIPPED)
    }
}
