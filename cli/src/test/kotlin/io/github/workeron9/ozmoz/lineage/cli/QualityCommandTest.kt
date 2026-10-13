package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.testing.test
import io.github.workeron9.ozmoz.lineage.conformance.CorpusLoader
import io.github.workeron9.ozmoz.lineage.conformance.QualityReport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `ozml quality` 端到端：临时语料（含标注与未标注、含坏文件）→ 真实引擎（`engine-jsqlparser`）
 * → stdout JSON / `--out` 落盘。口径细节由 `QualityRunnerTest` 钉住，这里验出口。
 */
class QualityCommandTest {

    private val command = QualityCommand()

    @Test
    fun `端到端产出可解析的报表且公布标注覆盖`() {
        val root = corpus(
            "ansi" to listOf(
                // 已标注：期望与引擎产出一致（1 OUTPUT + 1 SOURCE）。
                "annotated.json" to """
                    {"id":"annotated","dialect":"ansi","features":["basic"],"sql":"SELECT a FROM t",
                     "lineage":{"edges":[
                       {"from":"t.a","to":"a","kind":"OUTPUT"},
                       {"from":"t","to":"a","kind":"SOURCE"}]}}
                """.trimIndent(),
                // 未标注：只进解析率与 unknown 率。
                "plain.json" to """{"id":"plain","dialect":"ansi","sql":"SELECT a FROM t"}""",
            ),
            "junk" to listOf("broken.json" to """{"id":"ok" 独眼巨人}"""),
        )

        val result = command.test("--corpus ${root.toAbsolutePath()} --commit testsha")

        assertEquals(0, result.statusCode)
        assertTrue(result.stderr.contains("WARN junk/broken.json"), result.stderr)

        val report = CorpusLoader.jsonBuilder().decodeFromString(QualityReport.serializer(), result.stdout)
        assertEquals("testsha", report.meta.commit)
        assertEquals(2, report.meta.caseCount)
        assertEquals(1, report.meta.annotatedCount) // 覆盖如实公布：只标了 1 条
        val engine = report.engines.single { it.engine == "jsqlparser" }
        assertEquals(1.0, engine.precision)
        assertEquals(1.0, engine.recall)
        assertEquals(0, engine.unexplainedGaps)
        // 按方言分别公布（计划 §8：不合并不平均）。
        assertEquals(listOf("ansi"), report.byDialect.map { it.dialect })
    }

    @Test
    fun `out 落盘并给出摘要`() {
        val root = corpus("ansi" to listOf("ok.json" to """{"id":"ok","dialect":"ansi","sql":"SELECT a FROM t"}"""))
        val out = Files.createTempDirectory("ozml-quality-out").resolve("quality.json")

        val result = command.test("--corpus ${root.toAbsolutePath()} --out $out")

        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "annotated: 0")
        assertContains(result.stdout, "jsqlparser")
        val report = CorpusLoader.jsonBuilder().decodeFromString(QualityReport.serializer(), out.readText())
        assertEquals(1, report.meta.caseCount)
        // 没标注 → 不编造 precision/recall（n/a）。
        assertTrue(report.engines.all { it.precision == null && it.recall == null })
        // 但 unknown 率照样算（分母是列引用总数）。
        assertNotNull(report.engines.single { it.engine == "jsqlparser" }.unknownRate)
    }

    @Test
    fun `空语料目录非零退出`() {
        val empty = Files.createTempDirectory("ozml-quality-empty")
        val result = command.test("--corpus $empty")
        assertTrue(result.statusCode != 0)
        // 空目录：加载器给带定位的 problem，CLI 走「语料一条可跑的都没有」路径（与 ozml matrix 同惯例）。
        assertContains(result.stderr, "corrupt_corpus")
    }

    /** 临时语料目录：`<dialect>/<file>.json`。 */
    private fun corpus(vararg dirs: Pair<String, List<Pair<String, String>>>): Path {
        val root = Files.createTempDirectory("ozml-quality-corpus")
        for ((dialect, files) in dirs) {
            val dir = Files.createDirectories(root.resolve(dialect))
            for ((name, text) in files) Files.writeString(dir.resolve(name), text)
        }
        return Path(root.toString())
    }
}
