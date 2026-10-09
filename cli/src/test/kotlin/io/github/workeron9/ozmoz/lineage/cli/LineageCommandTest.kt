package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.testing.test
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.TransformKind
import io.github.workeron9.ozmoz.lineage.lineage.LineageBuilder
import io.github.workeron9.ozmoz.lineage.schema.DdlFileSchemaProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `ozml lineage` 端到端：真实引擎（`engine-jsqlparser`）→ 语义模型 → `LineageBuilder`
 * → `ir.LineageModel` → JSON / edges / summary 三种输出。
 *
 * 在 `cli`（组合根）验证，避免 `lineage` 反向依赖任何引擎适配器。
 */
class LineageCommandTest {

    private val command = LineageCommand()

    private fun tempSql(content: String): File {
        val f = Files.createTempFile("ozml-lineage-test", ".sql").toFile()
        f.writeText(content)
        f.deleteOnExit()
        return f
    }

    @Test
    fun `CTE 端到端产 DIRECT 链与列节点`() {
        val model = analyze("WITH c AS (SELECT a AS x FROM s) SELECT x FROM c")

        // 两条 DIRECT 边成链：s.a -> c.x（CTE 体）、c.x -> x（顶层）；另有 2 条 SOURCE 边。
        val outputs = model.edges.filter { it.kind == EdgeKind.OUTPUT }
        assertEquals(2, outputs.size)
        assertTrue(outputs.all { it.transform == TransformKind.DIRECT })
        val chain = outputs.associateBy { it.fromColumn.qualifiedName }
        assertEquals("c.x", chain.getValue("s.a").toColumn.qualifiedName)
        assertEquals("x", chain.getValue("c.x").toColumn.qualifiedName)
        assertTrue(model.columns.any { it.column.qualifiedName == "s.a" })
        assertTrue(model.columns.any { it.column.qualifiedName == "c.x" && it.scopeId != null })
    }

    @Test
    fun `星号展开 CTE 端到端产链`() {
        val model = analyze("WITH c AS (SELECT a FROM s) SELECT * FROM c")

        // 展开成链：s.a -> c.a（CTE 体）、c.a -> a（顶层，星号原文）；另有 2 条 SOURCE 边。
        val outputs = model.edges.filter { it.kind == EdgeKind.OUTPUT }
        assertEquals(2, outputs.size)
        assertTrue(outputs.all { it.transform == TransformKind.DIRECT })
        assertEquals("*", outputs.single { it.fromColumn.qualifiedName == "c.a" }.expression)
    }

    @Test
    fun `物理表星号端到端显式记 unknown 不猜列名`() {
        val model = analyze("SELECT * FROM store_sales")

        // 物理表列集合未知：不发明列名，显式记 unknown，不产 OUTPUT 边。
        assertTrue(model.edges.isEmpty())
        assertTrue(model.unknowns.any { it.reason.contains("SchemaProvider") })
    }

    @Test
    fun `JOIN 等值条件端到端产 JOIN_KEY 边`() {
        val model = analyze("SELECT a.x, b.y FROM a JOIN b ON a.id = b.id")

        val joinKey = model.edges.filter { it.kind == EdgeKind.JOIN_KEY }
        assertEquals(1, joinKey.size)
        assertEquals("a.id", joinKey.single().fromColumn.qualifiedName)
        assertEquals("b.id", joinKey.single().toColumn.qualifiedName)
        // 两条 OUTPUT 边：a.x -> x、b.y -> y。
        assertEquals(2, model.edges.count { it.kind == EdgeKind.OUTPUT })
    }

    @Test
    fun `MERGE 端到端非零退出且不猜`() {
        // MERGE 不建模为 SemanticStatement：analyzeAll 跳过它，输入里没有可建模语句 → 非零退出。
        val f = tempSql("MERGE INTO t USING s ON t.id = s.id")
        val result = command.test("--file ${f.absolutePath}")
        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "no_modelable_statement")
    }

    @Test
    fun `edges 格式输出人读边表`() {
        val f = tempSql("SELECT a AS x FROM s")
        val result = command.test("--file ${f.absolutePath} --format edges")
        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "OUTPUT")
        assertContains(result.stdout, "s.a -> x")
        assertContains(result.stdout, "[DIRECT]")
    }

    @Test
    fun `summary 格式输出计数与 unknown`() {
        val f = tempSql("SELECT * FROM store_sales")
        val result = command.test("--file ${f.absolutePath} --format summary")
        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "edges: 0") // 物理表星号不产边
        assertContains(result.stdout, "unknowns: 1") // 仅物理表列未知（星号名字由展开决定，不再记「输出列没有名字」）
        assertContains(result.stdout, "SchemaProvider")
    }

    @Test
    fun `schema DDL 文件端到端展开物理表星号`() {
        val sql = tempSql("SELECT * FROM store_sales")
        val ddl = tempSql("CREATE TABLE store_sales (ss_sold_date_sk INT, ss_quantity DECIMAL(7,2))")

        val model = analyze("SELECT * FROM store_sales", schemaDdl = ddl)

        // 星号展开成两条 DIRECT 边（另有 2 条 SOURCE），不再记「需要 SchemaProvider」的 unknown。
        val outputs = model.edges.filter { it.kind == EdgeKind.OUTPUT }
        assertEquals(listOf("store_sales.ss_sold_date_sk", "store_sales.ss_quantity"),
            outputs.map { it.fromColumn.qualifiedName })
        assertTrue(model.unknowns.none { it.reason.contains("SchemaProvider") })
    }

    @Test
    fun `schema 参数经命令行传递且 meta 记录其 id`() {
        val sql = tempSql("SELECT * FROM store_sales")
        val ddl = tempSql("CREATE TABLE store_sales (ss_sold_date_sk INT)")
        val result = command.test("--file ${sql.absolutePath} --schema ${ddl.absolutePath} --format summary")

        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "edges: 2") // 1 OUTPUT + 1 SOURCE
        assertContains(result.stdout, "unknowns: 0")
    }

    @Test
    fun `format 模块导出器经 --format 可用`() {
        val sql = tempSql("SELECT a FROM t")

        val mermaid = command.test("--file ${sql.absolutePath} --format mermaid")
        assertEquals(0, mermaid.statusCode)
        assertContains(mermaid.stdout, "flowchart LR")
        assertContains(mermaid.stdout, "OUTPUT/DIRECT")

        val dot = command.test("--file ${sql.absolutePath} --format dot")
        assertEquals(0, dot.statusCode)
        assertContains(dot.stdout, "digraph lineage")

        val cypher = command.test("--file ${sql.absolutePath} --format cypher")
        assertEquals(0, cypher.statusCode)
        assertContains(cypher.stdout, "MERGE")

        val openlineage = command.test("--file ${tempSql("INSERT INTO tgt (x) SELECT a FROM t").absolutePath} --format openlineage")
        assertEquals(0, openlineage.statusCode)
        assertContains(openlineage.stdout, "columnLineage")
        assertContains(openlineage.stdout, "\"subtype\": \"IDENTITY\"")
    }

    @Test
    fun `未知 format 非零退出`() {
        val sql = tempSql("SELECT a FROM t")
        val result = command.test("--file ${sql.absolutePath} --format nosuch")
        // Clikt 的 choice 校验会先拦下未注册值，退出码非 0。
        assertTrue(result.statusCode != 0)
    }

    /** 真实引擎分析 → 列级血缘模型（与 LineagePipelineTest 同一组合根模式）。 */
    private fun analyze(sql: String, schemaDdl: File? = null): LineageModel {
        val semantic = JSqlParserEngine().analyze(sql)
        val statement = assertIs<Resolved.Known<SemanticStatement>>(semantic).value
        val schema = schemaDdl?.let { DdlFileSchemaProvider.fromFiles(id = "ddl", it.toPath()) }
        return LineageBuilder.build(statement, schema)
    }
}
