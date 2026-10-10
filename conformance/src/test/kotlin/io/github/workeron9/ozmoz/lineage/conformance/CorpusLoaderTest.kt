package io.github.workeron9.ozmoz.lineage.conformance

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 语料加载器：坏文件 / 字段缺失 / 重复 id 的 Never-wrong 处理。 */
class CorpusLoaderTest {

    @Test
    fun `加载合法语料 路径归一化可溯源`() {
        val root = tune {
            file("mysql/basic.json", """{"id":"mysql-basic","dialect":"mysql","sql":"SELECT 1 FROM t"}""")
            file("ansi/old.json", """{"id":"ansi-old","dialect":"ansi","kind":"DDL","features":["x"],"sql":"CREATE TABLE t (a INT)"}""")
        }
        val loaded = CorpusLoader.load(root)

        assertEquals(emptyList(), loaded.problems)
        assertEquals(2, loaded.entries.size)
        val paths = loaded.entries.map { it.path }
        // 排序确定性：ansi 在前（relative path 排序）。
        assertEquals(listOf("ansi/old.json", "mysql/basic.json"), paths)
        assertTrue(loaded.entries.all { it.path.contains('/') })
    }

    @Test
    fun `kind 缺省 SELECT 字段缺失计问题`() {
        val root = tune {
            file("a.json", """{"id":"has-all","dialect":"ansi","sql":"SELECT 1"}""")
            file("b.json", """{"id":"missing-sql","dialect":"ansi"}""")
            file("c.json", """{"dialect":"ansi","sql":"SELECT 1"}""")
            file("d.json", """{"id":"bad-kind","dialect":"ansi","kind":"TRANSACTION","sql":"SELECT 1"}""")
        }
        val loaded = CorpusLoader.load(root)

        assertEquals(1, loaded.entries.size)
        assertEquals(CaseKind.SELECT, loaded.entries.single().case.kind)
        assertTrue(loaded.hasProblems())
        assertEquals(3, loaded.problems.size)
        assertTrue(loaded.problems.any { it.startsWith("b.json") && it.contains("JSON") })
        assertTrue(loaded.problems.any { it.startsWith("c.json") })
        assertTrue(loaded.problems.any { it.startsWith("d.json") })
    }

    @Test
    fun `重复 id 计问题但先出现的保留`() {
        val root = tune {
            file("first.json", """{"id":"dup","dialect":"ansi","sql":"SELECT 1"}""")
            file("second.json", """{"id":"dup","dialect":"ansi","sql":"SELECT 2"}""")
        }
        val loaded = CorpusLoader.load(root)

        assertEquals("first.json", loaded.entries.single().path)
        assertTrue(loaded.problems.single().contains("second.json"))
    }

    @Test
    fun `不存在与非 json 目录都算语料损坏`() {
        val missing = CorpusLoader.load(Files.createTempDirectory("ozml-empty-").resolve("nope/nope"))
        assertTrue(missing.entries.isEmpty() && missing.problems.size == 1)

        val noJson = CorpusLoader.load(Files.createTempDirectory("ozml-empty-"))
        assertTrue(noJson.entries.isEmpty())
        assertTrue(noJson.problems.single().contains("没有 *.json"))
    }

    private fun tune(block: Builder.() -> Unit): Path {
        val root = Files.createTempDirectory("ozml-corpus-")
        Builder(root).block()
        return root
    }

    private class Builder(private val root: Path) {
        fun file(name: String, content: String) {
            val path = root.resolve(name)
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, content)
        }
    }
}
