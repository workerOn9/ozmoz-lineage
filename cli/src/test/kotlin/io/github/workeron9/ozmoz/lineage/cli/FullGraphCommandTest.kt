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

    @Test
    fun `impact 现建图也自动收集 DDL`() {
        // 目录里 DDL 与查询同源：无须 --schema，SELECT * 即展开，src.a 可作为上溯起点。
        // INSERT 目标列（x, y）与展开后的查询列数一致，才不会触发「目标列数不一致」的 unknown。
        val dir = sqlDir(
            "schema.sql" to "CREATE TABLE src (a INT, b INT)",
            "query.sql" to "INSERT INTO dst (x, y) SELECT * FROM src",
        )

        val result = impact.test("-f ${dir.toAbsolutePath()} --on src.a --direction downstream")

        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "dst.x  [output]")
    }

    // ————— --incremental 增量落库 —————

    @Test
    fun `增量首轮等于全量 第二轮全跳过`() {
        val dir = sqlDir(
            "a.sql" to "INSERT INTO dst (x) SELECT a FROM src",
            "b.sql" to "INSERT INTO dst2 (y) SELECT x FROM dst",
        )
        val db = tempDb()

        val first = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")
        assertEquals(0, first.statusCode)
        assertContains(first.stdout, "reused_files: 0")
        assertContains(first.stdout, "parsed_files: 2")
        assertContains(first.stdout, "statements: 2")

        val db1 = loadTags(db)

        val second = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")
        assertEquals(0, second.statusCode)
        assertContains(second.stdout, "reused_files: 2")
        assertContains(second.stdout, "parsed_files: 0")
        assertContains(second.stdout, "removed_files: 0")
        // 复用 = 库里原样保留（Lossless 往返）。
        assertEquals(db1, loadTags(db))
    }

    @Test
    fun `增量只重解析改动的文件 其余原样保留`() {
        val dir = sqlDir(
            "a.sql" to "INSERT INTO dst (x) SELECT a FROM src",
            "b.sql" to "INSERT INTO dst2 (y) SELECT x FROM dst",
        )
        val db = tempDb()
        lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")
        val before = loadTags(db)

        Files.writeString(dir.resolve("b.sql"), "INSERT INTO dst2 (y) SELECT x FROM dst  -- 内容变了")
        val run2 = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")

        assertEquals(0, run2.statusCode)
        assertContains(run2.stdout, "reused_files: 1")
        assertContains(run2.stdout, "parsed_files: 1")
        assertContains(run2.stdout, "statements: 2")
        // 未变化的 a.sql 模型原样；b.sql 重解析（新 lineage id 仍 e0，列相同）。
        val after = loadTags(db)
        assertEquals(before.getValue("a.sql"), after.getValue("a.sql"))
        assertEquals("dst2.y", after.getValue("b.sql"))
    }

    @Test
    fun `增量从库里删除输入中已消失的文件`() {
        val dir = sqlDir(
            "a.sql" to "INSERT INTO dst (x) SELECT a FROM src",
            "b.sql" to "INSERT INTO dst2 (y) SELECT x FROM dst",
        )
        val db = tempDb()
        lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")

        Files.delete(dir.resolve("b.sql"))
        val run = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")

        assertEquals(0, run.statusCode)
        assertContains(run.stdout, "removed_files: 1")
        assertContains(run.stdout, "statements: 1")
        assertEquals(listOf("a.sql"), loadTags(db).keys.toList())
    }

    @Test
    fun `增量解析失败的文件保留旧血缘 好文件照常更新`() {
        val dir = sqlDir(
            "a.sql" to "INSERT INTO dst (x) SELECT a FROM src",
            "b.sql" to "INSERT INTO dst2 (y) SELECT x FROM dst",
        )
        val db = tempDb()
        lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")

        Files.writeString(dir.resolve("b.sql"), "SELEC broken FROM ; ;")
        val run = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")

        assertEquals(0, run.statusCode)
        assertContains(run.stdout, "failed_files: 1")
        assertContains(run.stdout, "statements: 2")
        val tags = loadTags(db)
        assertEquals("dst2.y", tags.getValue("b.sql"), "坏文件保留旧血缘（过期但可用）")
    }

    @Test
    fun `增量 schema 或引擎变化使全体指纹失效`() {
        val dir = sqlDir(
            "query.sql" to "INSERT INTO dst (x, y) SELECT * FROM src",
            "schema.sql" to "CREATE TABLE src (a INT, b INT)",
        )
        val db = tempDb()
        lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")

        // DDL 文件改动 → auto schema 变 → 全部文件重解析。
        Files.writeString(dir.resolve("schema.sql"), "CREATE TABLE src (a INT, b INT, c INT)")
        val run = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")

        assertEquals(0, run.statusCode)
        assertContains(run.stdout, "reused_files: 0")
        assertContains(run.stdout, "parsed_files: 2")
    }

    @Test
    fun `incremental 不带 graph 与 stdin 都被拒绝`() {
        val dir = sqlDir("a.sql" to "SELECT a FROM t")

        val noGraph = lineage.test("-f ${dir.toAbsolutePath()} --incremental")
        assertEquals(1, noGraph.statusCode)
        assertContains(noGraph.stderr, "incremental_requires_graph")

        val stdin = lineage.test("-f - --graph ${tempDb().toAbsolutePath()} --incremental")
        assertEquals(1, stdin.statusCode)
        assertContains(stdin.stderr, "incremental_requires_source")
    }

    @Test
    fun `全量跑法不带指纹也不受增量影响`() {
        val dir = sqlDir("a.sql" to "SELECT a FROM t")
        val db = tempDb()

        val build = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()}")
        assertEquals(0, build.statusCode)

        // 非 --incremental 的落库不写指纹：fingerprints 为空，增量首轮当全量处理。
        SqliteLineageStore.open(db).use {
            assertEquals(emptyMap<String, String>(), it.fingerprints())
        }
        val inc = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")
        assertEquals(0, inc.statusCode)
        assertContains(inc.stdout, "parsed_files: 1")
    }

    // ————— --no-source-edges —————

    @Test
    fun `开关作用于 graph 落库`() {
        val dir = sqlDir("a.sql" to "INSERT INTO dst (x) SELECT a FROM src")
        val db = tempDb()

        val full = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()}")
        assertEquals(0, full.statusCode)
        // 1 OUTPUT + 1 SOURCE。
        assertContains(full.stdout, "edges: 2")
        SqliteLineageStore.open(db).use { loaded ->
            val model = loaded.load().single().model
            assertEquals(2, model.edges.size)
        }

        val trimmed = lineage.test(
            "-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --no-source-edges",
        )
        assertEquals(0, trimmed.statusCode)
        assertContains(trimmed.stdout, "edges: 1")
        SqliteLineageStore.open(db).use { loaded ->
            val model = loaded.load().single().model
            assertEquals(1, model.edges.size)
            assertTrue(model.edges.none { it.kind == io.github.workeron9.ozmoz.lineage.ir.EdgeKind.SOURCE })
        }

        // 库里没有 SOURCE 边，impact 仍可沿值血缘查下游。
        val downstream = impact.test("--graph ${db.toAbsolutePath()} --on src.a --direction downstream")
        assertEquals(0, downstream.statusCode)
        assertContains(downstream.stdout, "dst.x  [output]")
    }

    @Test
    fun `开关变化使增量指纹失效`() {
        val dir = sqlDir("a.sql" to "INSERT INTO dst (x) SELECT a FROM src")
        val db = tempDb()

        lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")
        val first = loadTags(db)

        val trimmed = lineage.test(
            "-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental --no-source-edges",
        )
        assertEquals(0, trimmed.statusCode)
        // 开关进上下文摘要 → 指纹全变 → 重解析（否则跳过会让旧 SOURCE 边留在库里）。
        assertContains(trimmed.stdout, "reused_files: 0")
        assertContains(trimmed.stdout, "parsed_files: 1")
        SqliteLineageStore.open(db).use { loaded ->
            val model = loaded.load().single().model
            assertTrue(model.edges.none { it.kind == io.github.workeron9.ozmoz.lineage.ir.EdgeKind.SOURCE })
            assertEquals(loadTags(db), first)
        }

        // 关回去 → 再重解析，边恢复。
        val restored = lineage.test("-f ${dir.toAbsolutePath()} --graph ${db.toAbsolutePath()} --incremental")
        assertEquals(0, restored.statusCode)
        assertContains(restored.stdout, "reused_files: 0")
        SqliteLineageStore.open(db).use { loaded ->
            val model = loaded.load().single().model
            assertEquals(2, model.edges.size)
        }
    }

    /** 载入库并抽「来源文件 → 模型指纹特征」做断言（键取文件名，值取 OUTPUT 边的目标列）。 */
    private fun loadTags(db: Path): Map<String, String> =
        SqliteLineageStore.open(db).use { store ->
            store.load().associate {
                val name = it.sourceFile!!.substringAfterLast('/')
                name to (it.model.edges.firstOrNull { edge ->
                    edge.kind == io.github.workeron9.ozmoz.lineage.ir.EdgeKind.OUTPUT
                }?.toColumn?.qualifiedName ?: "<none>")
            }
        }
}
