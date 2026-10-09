package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.testing.test
import io.github.workeron9.ozmoz.lineage.graph.SqliteLineageStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ozml lineage --graph` / `ozml impact --graph` 的端到端：
 * **一个 SQL 目录 → 落库全库血缘图 → 从库查询影响面**。
 *
 * 在 `cli`（组合根）验证：目录聚合、多语句、SQLite 往返、跨文件的血缘缝合。
 */
class FullGraphCommandTest {

    private val lineage = LineageCommand()
    private val impact = ImpactCommand()

    /** 造一个目录，写入 `name -> content` 的 `.sql` 文件。 */
    private fun sqlDir(vararg files: Pair<String, String>): Path {
        val dir = Files.createTempDirectory("ozml-graph-")
        for ((name, content) in files) {
            Files.writeString(dir.resolve(name), content)
        }
        return dir
    }

    private fun tempDb(): Path = Files.createTempDirectory("ozml-db-").resolve("lineage.db")

    @Test
    fun `目录聚合落库后 impact 可从库跨文件查询`() {
        val dir = sqlDir(
            "a.sql" to "INSERT INTO dst (x) SELECT a FROM src",
            "b.sql" to "INSERT INTO dst2 (y) SELECT x FROM dst",
        )
        val db = tempDb()

        val build = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()}")
        assertEquals(0, build.statusCode)
        assertContains(build.stdout, "statements: 2")
        assertContains(build.stdout, "nodes:")
        assertTrue(Files.exists(db), "图库文件应已生成")

        // src.a → dst.x → dst2.y：跨两个文件的链在库里缝合起来。
        val result = impact.test("--graph ${db.toAbsolutePath()} --on src.a --direction downstream")
        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "dst.x  [output]")
        assertContains(result.stdout, "dst2.y  [output]")
    }

    @Test
    fun `多语句单文件落库 跳过 DDL`() {
        val dir = sqlDir(
            "schema_and_query.sql" to """
                CREATE TABLE t (a INT, b INT);
                INSERT INTO u (a) SELECT a FROM t
            """.trimIndent(),
        )
        val db = tempDb()

        val build = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()}")

        assertEquals(0, build.statusCode)
        // CREATE TABLE 被跳过，只剩 1 条 INSERT。
        assertContains(build.stdout, "statements: 1")
    }

    @Test
    fun `无 --graph 时多文件默认报错并提示 graph-json`() {
        val dir = sqlDir(
            "a.sql" to "SELECT a FROM t",
            "b.sql" to "SELECT b FROM t",
        )

        val result = lineage.test("-f ${dir.toAbsolutePath()}")

        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "multi_statement_input")
    }

    @Test
    fun `graph-json 输出合并图的稳定形状`() {
        val dir = sqlDir(
            "a.sql" to "INSERT INTO dst (x) SELECT a FROM src",
            "b.sql" to "INSERT INTO dst2 (y) SELECT x FROM dst",
        )

        val json = lineage.test("-f ${dir.toAbsolutePath()} --format graph-json")

        assertEquals(0, json.statusCode)
        assertContains(json.stdout, "\"nodes\"")
        assertContains(json.stdout, "\"edges\"")
        assertContains(json.stdout, "\"label\": \"src.a\"")
        assertContains(json.stdout, "\"kind\": \"OUTPUT\"")
    }

    @Test
    fun `impact 空图库非零退出`() {
        val db = tempDb()
        SqliteLineageStore.open(Path(db.toString())).use { /* 建空库 */ }

        val result = impact.test("--graph ${db.toAbsolutePath()} --on t.a")

        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "empty_graph_store")
    }

    @Test
    fun `impact 无输入时报错`() {
        val result = impact.test("--on t.a")
        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "missing_input")
    }
}
