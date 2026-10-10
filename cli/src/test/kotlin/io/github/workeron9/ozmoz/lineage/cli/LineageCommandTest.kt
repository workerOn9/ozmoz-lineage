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
    fun `CTE 链式引用端到端 b 引用 a 产三段 DIRECT 链`() {
        // 回归（语料矩阵暴露）：CTE 体引用同层先声明的 CTE 曾报「内部错误：CTE 体未构建」。
        val model = analyze("WITH a AS (SELECT id, name FROM t1), b AS (SELECT id, name FROM a) SELECT id FROM b")

        // id 链：t1.id -> a.id（a 的体）、a.id -> b.id（b 的体）、b.id -> id（顶层）；
        // name 链只到 b.name（顶层未消费 name）：t1.name -> a.name、a.name -> b.name，共 5 条。
        val outputs = model.edges.filter { it.kind == EdgeKind.OUTPUT }
        assertEquals(5, outputs.size)
        assertTrue(outputs.all { it.transform == TransformKind.DIRECT })
        val chain = outputs.associateBy { it.fromColumn.qualifiedName }
        assertEquals("a.id", chain.getValue("t1.id").toColumn.qualifiedName)
        assertEquals("b.id", chain.getValue("a.id").toColumn.qualifiedName)
        assertEquals("id", chain.getValue("b.id").toColumn.qualifiedName)
        assertEquals("a.name", chain.getValue("t1.name").toColumn.qualifiedName)
        assertEquals("b.name", chain.getValue("a.name").toColumn.qualifiedName)
        assertTrue(model.unknowns.none { it.reason.contains("CTE") })
    }

    @Test
    fun `派生表在内层引用 CTE 端到端产链`() {
        // 子查询体也要能引用外层 CTE（构建体的传播覆盖派生表层级）。
        val model = analyze("WITH a AS (SELECT id FROM t1) SELECT d.x FROM (SELECT id AS x FROM a) d")

        val outputs = model.edges.filter { it.kind == EdgeKind.OUTPUT }
        assertEquals(3, outputs.size)
        val chain = outputs.associateBy { it.fromColumn.qualifiedName }
        assertEquals("a.id", chain.getValue("t1.id").toColumn.qualifiedName)
        assertEquals("d.x", chain.getValue("a.id").toColumn.qualifiedName)
        assertEquals("x", chain.getValue("d.x").toColumn.qualifiedName)
    }

    @Test
    fun `递归 CTE 自引用端到端显式 unknown 不抛内部错误`() {
        // 递归血缘展开需要迭代求值，暂不支持；自引用显式降级为 Unknown（Never-wrong），不炸。
        val model =
            analyze("WITH r (n) AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 10) SELECT n FROM r")

        assertTrue(model.unknowns.any { it.reason.startsWith("递归 CTE r 的自引用") })
        // 非递归分支照常出边：顶层 SELECT n 解析到 r 的有效输出（首分支 n）。
        assertTrue(model.edges.any { it.kind == EdgeKind.OUTPUT && it.toColumn.qualifiedName == "n" })
    }

    @Test
    fun `递归 CTE 限定自引用与星号引用都记录了 unknown 而非 crash`() {
        val qualified =
            analyze("WITH r AS (SELECT a AS n FROM t UNION ALL SELECT n FROM r) SELECT n FROM r")
        assertTrue(qualified.unknowns.any { it.reason.startsWith("递归 CTE r 的自引用") })

        val starred = analyze("WITH r AS (SELECT a FROM t UNION ALL SELECT * FROM r) SELECT a FROM r")
        assertTrue(starred.unknowns.any { it.reason.startsWith("递归 CTE r") })
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
    fun `no-source-edges 关闭哨兵边 落到 json 与 edges 输出`() {
        val f = tempSql("SELECT a AS x FROM s")

        val default = command.test("--file ${f.absolutePath} --format summary")
        assertEquals(0, default.statusCode)
        assertContains(default.stdout, "edges: 2") // 1 OUTPUT + 1 SOURCE
        assertContains(default.stdout, "SOURCE: 1")

        val trimmed = command.test("--file ${f.absolutePath} --format edges --no-source-edges")
        assertEquals(0, trimmed.statusCode)
        assertContains(trimmed.stdout, "OUTPUT")
        assertTrue("SOURCE" !in trimmed.stdout)
        // 哨兵边原本占 e1：开关后编号出现空洞（对上未过滤版本的边号）。
        assertContains(trimmed.stdout, "e0  OUTPUT")
        assertTrue("e1" !in trimmed.stdout)
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
    fun `未给 schema 时从输入文本自动收集 DDL 展开星号`() {
        // 单文件内 DDL 与查询同源：无须 --schema，CREATE TABLE 顺手喂给展开。
        val sql = tempSql("CREATE TABLE t (a INT, b INT);\nSELECT * FROM t")
        val result = command.test("--file ${sql.absolutePath} --format summary")

        assertEquals(0, result.statusCode)
        // 2 条 OUTPUT + 2 条 SOURCE（哨兵 → 每个输出列各 1 条）。
        assertContains(result.stdout, "edges: 4")
        assertContains(result.stdout, "unknowns: 0")
    }

    @Test
    fun `显式 schema 优先于自动收集`() {
        // 输入里的 DDL 是 t(a, b)，显式 --schema 是 t(a)：以用户为准 → 只展开 1 列。
        val sql = tempSql("CREATE TABLE t (a INT, b INT);\nSELECT * FROM t")
        val ddl = tempSql("CREATE TABLE t (a INT)")
        val result = command.test("--file ${sql.absolutePath} --schema ${ddl.absolutePath} --format summary")

        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "edges: 2") // 1 OUTPUT（a）+ 1 SOURCE
        assertContains(result.stdout, "unknowns: 0")
    }

    @Test
    fun `输入无 DDL 时自动收集为空 行为与旧版一致`() {
        val sql = tempSql("SELECT * FROM store_sales")
        val result = command.test("--file ${sql.absolutePath} --format summary")

        assertEquals(0, result.statusCode)
        assertContains(result.stdout, "edges: 0") // 无 schema 可展开，不产边
        assertContains(result.stdout, "unknowns: 1")
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
