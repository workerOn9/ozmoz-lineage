package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.testing.test
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * `ozml impact` 端到端：真实引擎（`engine-jsqlparser`）→ 语义模型 → `LineageBuilder`
 * → `graph.LineageGraph` → text / json 输出。
 *
 * 在 `cli`（组合根）验证，避免 `graph` 反向依赖任何引擎适配器。
 */
class ImpactCommandTest {

    private val command = ImpactCommand()

    private fun tempSql(sql: String): File {
        val f = Files.createTempFile("ozml-impact-", ".sql").toFile()
        f.deleteOnExit()
        f.writeText(sql)
        return f
    }

    @Test
    fun `下溯从物理列走到输出列`() {
        val f = tempSql("SELECT a FROM t")

        val result = command.test("--file ${f.absolutePath} --on t.a --direction downstream")

        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "DOWNSTREAM")
        // t.a -> a（OUTPUT）；另有 t（哨兵）-> a。
        assertContains(result.stdout, "t.a")
        assertContains(result.stdout, "a  [output]")
    }

    @Test
    fun `上溯从输出列回到物理列与表哨兵`() {
        val f = tempSql("SELECT a FROM t")

        val result = command.test("--file ${f.absolutePath} --on a --direction upstream")

        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "UPSTREAM")
        assertContains(result.stdout, "t.a")
        assertContains(result.stdout, "t")
    }

    @Test
    fun `depth 限制层数`() {
        val f = tempSql("WITH c AS (SELECT a AS x FROM s) SELECT x FROM c")

        val result = command.test("--file ${f.absolutePath} --on s.a --direction downstream --depth 1")

        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "depth=1")
        assertContains(result.stdout, "truncated")
    }

    @Test
    fun `json 格式输出稳定形状`() {
        val f = tempSql("SELECT a FROM t")

        val result = command.test("--file ${f.absolutePath} --on t.a --direction downstream --format json")

        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "\"origin\": \"t.a\"")
        assertContains(result.stdout, "\"direction\": \"DOWNSTREAM\"")
        assertContains(result.stdout, "\"isOutput\": true")
    }

    @Test
    fun `未知列非零退出并给出原因`() {
        val f = tempSql("SELECT a FROM t")

        val result = command.test("--file ${f.absolutePath} --on t.nope")

        assertTrue(result.statusCode != 0)
        assertContains(result.stderr, "column_not_found")
    }
}
