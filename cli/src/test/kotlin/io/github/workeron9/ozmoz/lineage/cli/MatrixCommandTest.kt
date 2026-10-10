package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.testing.test
import io.github.workeron9.ozmoz.lineage.conformance.CompatMatrix
import io.github.workeron9.ozmoz.lineage.conformance.ConvertMatrix
import io.github.workeron9.ozmoz.lineage.conformance.CorpusLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ozml matrix` 端到端：临时语料（坏文件共存）→ 真实引擎（`engine-jsqlparser`）跑矩阵
 * → stdout JSON / `--out` 落盘两种模式；坏语料不静默（WARN 继续可跑、全坏则非零退出）。
 * 另跑仓库自带的 `conformance/corpus/` 全量语料（200 条级），验证矩阵对真实方言 case 的判定
 *（吃不下就记失败，不粉饰）。
 */
class MatrixCommandTest {

    private val command = MatrixCommand()

    @Test
    fun `端到端跑临时语料 stdout 输出矩阵`() {
        val root = corpus(
            "ansi" to listOf(
                "good.json" to """{"id":"good","dialect":"ansi","features":["basic"],"sql":"SELECT a FROM t"}""",
            ),
            "mysql" to listOf(
                "broken-sql.json" to """{"id":"broken-sql","dialect":"mysql","sql":"SELEC nope FROM ; ;"}""",
                "vendor.json" to """{"id":"vendor","dialect":"mysql","sql":"INSERT INTO counters (k, n) VALUES ('x', 1) ON DUPLICATE KEY UPDATE n = n + 1"}""",
            ),
            "junk" to listOf(
                "broken.json" to """{"id":"ok" 独眼巨人}""",
            ),
        )

        val result = command.test("--corpus ${root.toAbsolutePath()}")

        assertEquals(0, result.statusCode)
        // 坏 JSON 不静默：WARN 点名文件；好 case 照跑。
        assertTrue(result.stderr.contains("WARN junk/broken.json"), result.stderr)
        // 两引擎格（血缘矩阵 = 声明 SEMANTIC_MODEL 的引擎）：2 引擎 × 3 case = 6 cells。
        val matrix = CorpusLoader.jsonBuilder().decodeFromString(CompatMatrix.serializer(), result.stdout)
        // 发生变化的引擎列表（jsqlparser 解析主力 + calcite 方言/语义主力）
        assertEquals(2, matrix.meta.engineCount)
        assertEquals(6, matrix.cells.size)
        assertTrue(matrix.cells.first { it.caseId == "good" }.ok)
        assertTrue(matrix.cells.first { it.caseId == "broken-sql" }.let { !it.ok && it.reason != null })
    }

    @Test
    fun `out 落盘并给出摘要`() {
        val root = corpus(
            "ansi" to listOf(
                "good.json" to """{"id":"good","dialect":"ansi","sql":"SELECT a FROM t"}""",
            ),
        )
        val out = Files.createTempDirectory("ozml-matrix-out-").resolve("compat-matrix.json")

        val result = command.test("--corpus ${root.toAbsolutePath()} --commit deadbeef --out ${out.toAbsolutePath()}")

        assertEquals(0, result.statusCode)
        assertTrue(Files.exists(out))
        assertTrue(result.stdout.contains("cases: 1  engines: 2  ok: 2/2"), result.stdout)

        val matrix = CorpusLoader.jsonBuilder().decodeFromString(CompatMatrix.serializer(), out.readText().trim())
        assertEquals("deadbeef", matrix.meta.commit)
        assertEquals(2, matrix.cells.size) // 血缘矩阵引擎面 = jsqlparser + calcite
        assertTrue(matrix.cells.all { it.ok })
        assertEquals("ansi/good.json", matrix.cells.first().sourcePath)
    }

    @Test
    fun `语料全坏非零退出`() {
        val root = flatCorpus("x.json" to """{"no sql here}""")
        val result = command.test("--corpus ${root.toAbsolutePath()}")
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("corrupt_corpus"))
    }

    @Test
    fun `仓库自带语料端到端全部 case 可跑`() {
        // 用相对仓库根的路径：cli 模块的工作目录就是模块目录。
        val repoCorpus = Path("../conformance/corpus").toAbsolutePath()
        assertTrue(Files.isDirectory(repoCorpus), "仓库自带语料应在 $repoCorpus")

        val result = command.test("--corpus ${repoCorpus.toAbsolutePath()}")
        assertEquals(0, result.statusCode)
        val matrix = CorpusLoader.jsonBuilder().decodeFromString(CompatMatrix.serializer(), result.stdout)

        assertEquals(200, matrix.meta.caseCount)
        assertEquals(matrix.meta.caseCount * matrix.meta.engineCount, matrix.cells.size)
        // 防粉饰：失败格必须如实保留（引擎边界不是要消灭的东西），方言分层也要在。
        assertTrue(matrix.cells.any { !it.ok }, "方言专属语法应有如实记录的失败格")
        assertTrue(matrix.coverage.keys.size >= 8, "方言分层应覆盖 mysql/pg/hive/spark/trino/oracle/tsql/ansi：${matrix.coverage.keys}")
    }

    private fun corpus(vararg entries: Pair<String, List<Pair<String, String>>>): Path {
        val root = Files.createTempDirectory("ozml-matrix-corpus-")
        for ((dirName, files) in entries) {
            val dir = root.resolve(dirName)
            Files.createDirectories(dir)
            for ((name, caseJson) in files) {
                Files.writeString(dir.resolve(name), caseJson)
            }
        }
        return root
    }

    /** 平铺单文件版本（语料全坏 / 单 case 场景）：与目录版不重载（JVM 签名冲突），单独命名。 */
    private fun flatCorpus(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("ozml-matrix-corpus-")
        for ((name, caseJson) in files) {
            Files.writeString(root.resolve(name), caseJson)
        }
        return root
    }

    // ————— ozml matrix --convert（方言转换矩阵，M3 验收项） —————

    @Test
    fun `convert 模式输出方言转换矩阵并带成品 sql`() {
        val root = corpus(
            "mysql" to listOf(
                "ok.json" to """{"id":"ok","dialect":"mysql","kind":"SELECT","sql":"SELECT id FROM customer ORDER BY id LIMIT 10"}""",
                "broken.json" to """{"id":"broken","dialect":"mysql","kind":"SELECT","sql":"SELECT no FROM"}""",
            ),
        )
        val result = command.test("--convert --pairs mysql>postgresql --corpus ${root.toAbsolutePath()}")
        assertEquals(0, result.statusCode, result.stderr)
        // calcite + jooq 都注册了 mysql / postgresql → 每个 2 格（ok + broken）
        val matrix = CorpusLoader.jsonBuilder().decodeFromString(ConvertMatrix.serializer(), result.stdout)
        assertEquals(2, matrix.meta.engineCount)
        assertEquals(2, matrix.meta.pairCount)
        assertEquals(4, matrix.cells.size)
        // 成品 sql 出炉（diff 视图的数据）；失败格无 sql 但有原因
        val okCell = matrix.cells.filter { it.caseId == "ok" }
        assertTrue(okCell.all { it.ok && it.sql != null }, okCell.toString())
        val failed = matrix.cells.filter { it.caseId == "broken" }
        assertTrue(failed.all { !it.ok && it.sql == null && it.reason != null }, failed.toString())
    }

    @Test
    fun `convert 模式缺省用 M3 的 20 组方言对`() {
        val root = corpus(
            "mysql" to listOf("a.json" to """{"id":"a","dialect":"mysql","sql":"SELECT 1"}"""),
        )
        val result = command.test("--convert --corpus ${root.toAbsolutePath()}")
        assertEquals(0, result.statusCode, result.stderr)
        val matrix = CorpusLoader.jsonBuilder().decodeFromString(ConvertMatrix.serializer(), result.stdout)
        // 20 组里 mysql 为源的只有 calcite 能吃 hive/spark/bigquery/oracle/tsql 与
        // calcite/ansi 档；jooq 只吃 postgresql/trino/duckdb/h2 等——合计应大于 jooq 单引擎数。
        assertTrue(matrix.meta.pairCount > 0, "pairCount=${matrix.meta.pairCount}")
        // 有成品格
        assertTrue(matrix.cells.any { it.ok && it.sql != null }, matrix.coverage.toString())
    }

    @Test
    fun `convert 模式不支持的对不出格--语料方言与 pair from 不匹配不进格`() {
        val root = corpus(
            "hive" to listOf("h.json" to """{"id":"h","dialect":"hive","sql":"SELECT 1"}"""),
        )
        val result = command.test("--convert --pairs mysql>postgresql --corpus ${root.toAbsolutePath()}")
        assertEquals(0, result.statusCode, result.stderr)
        val matrix = CorpusLoader.jsonBuilder().decodeFromString(ConvertMatrix.serializer(), result.stdout)
        assertEquals(0, matrix.cells.size)
    }

    @Test
    fun `convert 模式坏 pairs 非零退出`() {
        val root = corpus(
            "mysql" to listOf("a.json" to """{"id":"a","dialect":"mysql","sql":"SELECT 1"}"""),
        )
        val result = command.test("--convert --pairs nope --corpus ${root.toAbsolutePath()}")
        assertTrue(result.statusCode != 0, result.stdout)
        assertContains(result.stderr, "--pairs 格式非法")
    }
}
