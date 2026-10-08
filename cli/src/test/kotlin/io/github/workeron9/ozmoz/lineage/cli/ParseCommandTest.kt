package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import java.io.File
import java.nio.file.Files

class ParseCommandTest {

    private fun tempSql(content: String): File {
        val f = Files.createTempFile("ozml-test", ".sql").toFile()
        f.writeText(content)
        f.deleteOnExit()
        return f
    }

    private val command = ParseCommand()

    @Test
    fun `json 输出含归一化树与表引用`() {
        val f = tempSql("SELECT ss_quantity FROM store_sales")
        val result = command.test("--file ${f.absolutePath} --format json")
        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "\"type\": \"select\"")
        assertContains(result.stdout, "\"type\": \"plain_select\"")
        assertContains(result.stdout, "\"canonical\": \"store_sales\"")
    }

    @Test
    fun `tree 输出可读`() {
        val f = tempSql("SELECT a FROM t")
        val result = command.test("--file ${f.absolutePath} --format tree")
        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "select")
        assertContains(result.stdout, "plain_select")
        assertContains(result.stdout, "table")
    }

    @Test
    fun `ast 输出是单个归一化树`() {
        val f = tempSql("SELECT a FROM t")
        val result = command.test("--file ${f.absolutePath} --format ast")
        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "\"type\": \"select\"")
    }

    @Test
    fun `解析失败以非零码退出并打印诊断`() {
        val f = tempSql("SELECT * FROM")
        val result = command.test("--file ${f.absolutePath} --format json")
        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "jsqlparser.parse_error")
    }

    @Test
    fun `--engine 校验取值`() {
        val f = tempSql("SELECT 1")
        val result = command.test("--engine nope --file ${f.absolutePath}")
        // 非法 choice → 用法错误（非 0）
        assert(result.statusCode != 0) { "非法引擎应报错，实际 ${result.statusCode}" }
    }

    @Test
    fun `--format 校验取值`() {
        val f = tempSql("SELECT 1")
        val result = command.test("--file ${f.absolutePath} --format yaml")
        assert(result.statusCode != 0) { "非法格式应报错，实际 ${result.statusCode}" }
    }

    @Test
    fun `--file 缺失时报错`() {
        val result = command.test("--format json")
        assert(result.statusCode != 0) { "缺少 --file 应报错" }
    }
}
