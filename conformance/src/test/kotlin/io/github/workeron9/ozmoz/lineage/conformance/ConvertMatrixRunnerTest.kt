package io.github.workeron9.ozmoz.lineage.conformance

import io.github.workeron9.ozmoz.lineage.engine.calcite.CalciteEngine
import io.github.workeron9.ozmoz.lineage.engine.jooq.JooqEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ConvertMatrixRunner`：方言对 × 语料 × 渲染引擎的判定口径——
 * ① case 方言必须等于 pair.from 才进格；② 引擎不支持的（引擎，对）不出格；
 * ③ 每格走 render-verified 门禁（ok 格带 sql 成品）；④ 输出逐字节确定。
 */
class ConvertMatrixRunnerTest {

    /** 造临时语料（方言 → 文件名 → SQL）。 */
    private fun corpus(vararg entries: Pair<String, List<Pair<String, String>>>): Path {
        val root = Files.createTempDirectory("ozml-convert-corpus-")
        for ((dialect, files) in entries) {
            val dir = root.resolve(dialect)
            Files.createDirectories(dir)
            for ((name, sql) in files) {
                dir.resolve("$name.json").writeText(
                    """{"id":"$dialect-$name","dialect":"$dialect","kind":"SELECT","sql":"${sql.replace("\"", "\\\"")}"}""",
                )
            }
        }
        root.toFile().deleteOnExit()
        return root
    }

    private val engines = listOf(CalciteEngine(), JooqEngine())

    @Test
    fun `ok 格带 sql 成品 失败格如实入格`() {
        val root = corpus(
            "mysql" to listOf(
                "ok" to "SELECT id FROM customer ORDER BY id LIMIT 10",
                "broken" to "SELECT no FROM",
            ),
        )
        val matrix = ConvertMatrixRunner(pairs = listOf(SqlPair("mysql", "postgresql")), commit = "deadbeef")
            .run(CorpusLoader.load(root).entries, engines, root.toString())

        assertEquals(2, matrix.meta.engineCount) // calcite + jooq 都声明 DIALECT_RENDER
        // 引擎接受的（mysql,postgresql）对在 calcite / jooq 都支持 → 两组格
        assertEquals(4, matrix.cells.size)
        val okCell = matrix.cells.first { it.ok }
        assertTrue(okCell.sql!!.lowercase().contains("fetch next"), okCell.sql)
        val failedCell = matrix.cells.first { !it.ok }
        assertEquals(null, failedCell.sql) // 半吊子 SQL 不出炉
        assertTrue(failedCell.reason != null)
        // coverage 一一对上
        val sqlPair = matrix.coverage.first { it.engine == "calcite" }
        assertEquals("mysql", sqlPair.from)
        assertEquals(2, sqlPair.cases)
        assertEquals(1, sqlPair.ok)
        assertEquals(1, sqlPair.failed)
        // meta 可溯源
        assertEquals("deadbeef", matrix.meta.commit)
    }

    @Test
    fun `case 方言不等于 pair from 的不进格`() {
        val root = corpus(
            "trino" to listOf("only" to "SELECT id FROM t"),
        )
        val matrix = ConvertMatrixRunner(pairs = listOf(SqlPair("mysql", "postgresql")))
            .run(CorpusLoader.load(root).entries, engines, root.toString())
        assertEquals(0, matrix.cells.size)
        assertEquals(0, matrix.coverage.size)
    }

    @Test
    fun `引擎不支持的方言对不出格`() {
        val root = corpus(
            "mysql" to listOf("ok" to "SELECT id FROM t"),
        )
        val matrix = ConvertMatrixRunner(pairs = listOf(SqlPair("mysql", "hive"), SqlPair("mysql", "h2")))
            .run(CorpusLoader.load(root).entries, engines, root.toString())
        // calcite 支持 mysql>hive；jooq 不支持 hive、支持 h2——两组各一条格
        assertEquals(2, matrix.cells.size)
        val byEngine = matrix.cells.associateBy { it.engine }
        assertEquals("hive", byEngine.getValue("calcite").toDialect)
        assertEquals("h2", byEngine.getValue("jooq").toDialect)
        // pairCount 按实际跑的组合数
        assertEquals(2, matrix.meta.pairCount)
    }

    @Test
    fun `输出逐字节确定`() {
        val root = corpus(
            "mysql" to listOf(
                "a" to "SELECT id FROM customer LIMIT 1",
                "b" to "SELECT name FROM orders LIMIT 2",
            ),
            "ansi" to listOf("c" to "SELECT 1"),
        )
        val runner = ConvertMatrixRunner(pairs = ConvertMatrixRunner.DEFAULT_PAIRS, commit = "sha")
        val entries = CorpusLoader.load(root).entries
        val first = runner.run(entries, engines, root.toString())
        val second = runner.run(entries, engines, root.toString())
        // generatedAt 必然不同，除 meta 外逐格一致（同顺序同内容）
        assertEquals(first.cells, second.cells)
        assertEquals(first.coverage, second.coverage)
    }

    @Test
    fun `M3 口径为 20 组方言对`() {
        assertEquals(20, ConvertMatrixRunner.DEFAULT_PAIRS.size)
        assertEquals(20, ConvertMatrixRunner.DEFAULT_PAIRS.map { it.asText() }.toSet().size)
    }

    @Test
    fun `SqlPair 解析与拒绝`() {
        assertEquals(SqlPair("mysql", "postgresql"), SqlPair.parse(" mysql > POSTGRESQL "))
        assertEquals(null, SqlPair.parse("mysql"))
        assertEquals(null, SqlPair.parse("mysql>>postgresql"))
        assertEquals(null, SqlPair.parse("mysql>mysql"))
        assertEquals(null, SqlPair.parse(">postgresql"))
    }
}
