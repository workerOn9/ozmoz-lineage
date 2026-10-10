package io.github.workeron9.ozmoz.lineage.engine.calcite

import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.RenderRequest
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Severity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `CalciteEngine`：按方言配解析器（抽测 E5 工程坑的对策）、归一化树形状、
 * render-verified 门禁（抽测 E3/E4 的两个真缺陷——MSSQL 静默丢 LIMIT、
 * `AnsiSqlDialect` quoting——做成永久回归）。
 */
class CalciteEngineTest {

    private val engine = CalciteEngine()

    // ————— 能力表 —————

    @Test
    fun `能力表声明 parse 与方言渲染 并给出语义模型不支持的原因`() {
        assertTrue(engine.capabilities.supports(Feature.PARSE))
        assertTrue(engine.capabilities.supports(Feature.DIALECT_PARSE))
        assertTrue(engine.capabilities.supports(Feature.DIALECT_RENDER))
        assertTrue(!engine.capabilities.supports(Feature.SEMANTIC_MODEL))
        assertTrue(
            engine.capabilities.reason(Feature.SEMANTIC_MODEL)!!.contains("jsqlparser"),
        )
        // 注册的方言 = 承诺的方言（含别名 tsql）
        assertTrue("tsql" in engine.capabilities.dialects)
        assertTrue("mssql" in engine.capabilities.dialects)
        // AnsiSqlDialect 类有缺陷（抽测 E4：quoting 读不回），**类本身**不使用；
        // 但对外 id `ansi` 是语料里的无方言族——别名到 `calcite` 主档（同配置）。
        assertTrue("ansi" in engine.capabilities.dialects)
        assertTrue("mysql" in engine.capabilities.dialects)
    }

    // ————— parse：按方言配解析器 —————

    @Test
    fun `parse mysql 反引号 SQL 成功`() {
        val outcome = engine.parse("""SELECT `price` FROM `store`""", ParseRequest(dialect = "mysql"))
        assertTrue(outcome.hasErrors.not(), outcome.diagnostics.toString())
        assertEquals("select", outcome.root.type)
        val fromTable = outcome.root.children.first { it.type == "from" }.children.first()
        assertEquals("identifier", fromTable.type)
        // Lossless：原文切片连引号形式一起保留（与 jsqlparser 侧的裸名不同，diff 的信息更多）
        assertEquals("`store`", fromTable.text)
    }

    @Test
    fun `parse tsql 别名走方括号 quoting`() {
        val outcome = engine.parse("SELECT [a] FROM [t]", ParseRequest(dialect = "tsql"))
        assertTrue(outcome.hasErrors.not(), outcome.diagnostics.toString())
        assertEquals("select", outcome.root.type)
        val identifier = outcome.root.children.first { it.type == "select_list" }.children.first()
        assertEquals("identifier", identifier.type)
        assertEquals("[a]", identifier.text)
    }

    @Test
    fun `parse 星号展开记为 star`() {
        val outcome = engine.parse("SELECT * FROM t")
        assertTrue(outcome.hasErrors.not(), outcome.diagnostics.toString())
        val identifier = outcome.root.children.first { it.type == "select_list" }.children.first()
        assertEquals("star", identifier.type)
    }

    @Test
    fun `parse CTE 出 with 与 cte 节点`() {
        val outcome = engine.parse(
            "WITH x AS (SELECT id FROM a) SELECT id FROM x",
            ParseRequest(dialect = "postgresql"),
        )
        assertTrue(outcome.hasErrors.not(), outcome.diagnostics.toString())
        assertEquals("with", outcome.root.type)
        val ctes = outcome.root.children.filter { it.type == "cte" }
        assertEquals(1, ctes.size)
        assertEquals(1, ctes.first().span?.start?.line) // 行列 span 有值
    }

    @Test
    fun `带 ORDER BY LIMIT 的 select 出 order_by 根节点 且 span 覆盖全子句`() {
        // Calcite 的 SqlOrderBy 自身 pos 是 fetch 的位置（实测 quirk），
        // 归一化树的 order_by 节点必须用部件位置并集——text 才是 ORDER BY ... LIMIT 10 全文。
        val outcome = engine.parse("SELECT a FROM t ORDER BY a LIMIT 10")
        assertTrue(outcome.hasErrors.not(), outcome.diagnostics.toString())
        assertEquals("order_by", outcome.root.type)
        assertContains(outcome.root.text, "SELECT")
        assertContains(outcome.root.text, "ORDER BY")
        assertContains(outcome.root.text, "LIMIT")
        val span = requireNotNull(outcome.root.span)
        assertEquals(1, span.start.line)
        assertEquals(1, span.start.column)
        assertEquals("SELECT a FROM t ORDER BY a LIMIT 10", outcome.root.text)
    }

    @Test
    fun `parse 多语句出 statement_list`() {
        val outcome = engine.parse("SELECT 1; SELECT 2;")
        assertTrue(outcome.hasErrors.not(), outcome.diagnostics.toString())
        assertEquals("statement_list", outcome.root.type)
        assertEquals(2, outcome.root.children.size)
    }

    @Test
    fun `parse 语法错误不抛异常 并带 span 定位`() {
        val outcome = engine.parse("SELECT nope FROM;")
        assertTrue(outcome.hasErrors)
        val diagnostic = outcome.diagnostics.first()
        assertEquals(Severity.ERROR, diagnostic.severity)
        assertEquals(CalciteEngine.CODE_PARSE_ERROR, diagnostic.code)
        assertEquals("calcite", diagnostic.engineId)
        assertTrue(diagnostic.span != null, "解析错误必须带 span")
        assertEquals(1, diagnostic.span!!.start.line)
    }

    @Test
    fun `parse 空输入返回 empty_input 诊断`() {
        val outcome = engine.parse("   \n  ")
        assertTrue(outcome.hasErrors)
        assertEquals(CalciteEngine.CODE_EMPTY_INPUT, outcome.diagnostics.first().code)
    }

    @Test
    fun `parse 未注册方言给 dialect_unknown 并列出已注册方言`() {
        val outcome = engine.parse("SELECT 1", ParseRequest(dialect = "redshift"))
        assertTrue(outcome.hasErrors)
        val code = outcome.diagnostics.first().code
        assertEquals(CalciteEngine.CODE_DIALECT_UNKNOWN, code)
        assertTrue(outcome.diagnostics.first().message.contains("mysql"))
    }

    // ————— render：render-verified 门禁 —————

    @Test
    fun `render mysql 转 postgresql 分页改写为 FETCH NEXT 且等价`() {
        val result = engine.render(
            RenderRequest("SELECT id FROM customer ORDER BY id LIMIT 10", fromDialect = "mysql", toDialect = "postgresql"),
        )
        assertTrue(result is Resolved.Known, "预期通过门禁: $result")
        val sql = (result as Resolved.Known).value
        assertTrue(sql.uppercase().contains("FETCH NEXT"), sql)
        assertTrue(sql.uppercase().contains("LIMIT").not(), "分页应按目标方言改写: $sql")
    }

    @Test
    fun `render 恒等方言输出合法 SQL`() {
        val result = engine.render(
            RenderRequest("SELECT id, name FROM customer", fromDialect = "calcite", toDialect = "calcite"),
        )
        assertTrue(result is Resolved.Known, "预期通过门禁: $result")
        assertTrue((result as Resolved.Known).value.uppercase().contains("SELECT"))
    }

    @Test
    fun `render mssql 静默丢弃 LIMIT 被门禁拦截 不输出半吊子 SQL`() {
        // 抽测 E4 的真缺陷：MssqlSqlDialect 会静默丢掉 LIMIT/OFFSET——最危险的降级形态。
        val result = engine.render(
            RenderRequest("SELECT id FROM customer ORDER BY id LIMIT 10", fromDialect = "mysql", toDialect = "mssql"),
        )
        assertTrue(result is Resolved.Unknown, "门禁必须拦截 MSSQL 的 LIMIT 丢失: $result")
        val reason = (result as Resolved.Unknown).reason
        assertTrue(reason.contains(CalciteEngine.CODE_RENDER_UNVERIFIED), reason)
        assertTrue(reason.contains("base="), "原因里要带等价对照信息")
    }

    @Test
    fun `render 未注册目标方言给可解释的 Unknown`() {
        val result = engine.render(
            RenderRequest("SELECT 1", fromDialect = "mysql", toDialect = "redshift"),
        )
        assertTrue(result is Resolved.Unknown)
        assertTrue((result as Resolved.Unknown).reason.contains(CalciteEngine.CODE_DIALECT_UNKNOWN))
    }

    @Test
    fun `render 缺目标方言`() {
        val result = engine.render(
            RenderRequest("SELECT 1", fromDialect = "mysql", toDialect = null),
        )
        assertTrue(result is Resolved.Unknown)
    }

    // RenderRequest 的 init 就要求非空（engine-api 契约）；不重复测空白入参。

    @Test
    fun `render 源方言解析失败不抛异常`() {
        val result = engine.render(
            RenderRequest("SELECT FROM WHERE", fromDialect = "mysql", toDialect = "postgresql"),
        )
        assertTrue(result is Resolved.Unknown)
        assertTrue((result as Resolved.Unknown).reason.contains("源 SQL 解析失败"))
    }

    @Test
    fun `render 多语句逐条转换`() {
        val result = engine.render(
            RenderRequest("SELECT a FROM t1 LIMIT 1; SELECT b FROM t2 LIMIT 2;", fromDialect = "postgresql", toDialect = "postgresql"),
        )
        assertTrue(result is Resolved.Known, "预期通过门禁: $result")
        val sql = (result as Resolved.Known).value
        assertEquals(2, Regex("(?i)select").findAll(sql).count(), sql)
    }

    // ————— 归一化树结构 —————

    @Test
    fun `join 与 where 落位`() {
        val outcome = engine.parse("SELECT c.id FROM customer c JOIN orders o ON o.cid = c.id WHERE c.age > 18")
        assertTrue(outcome.hasErrors.not(), outcome.diagnostics.toString())
        val types = outcome.root.flatten().map { it.type }.toSet()
        assertTrue("inner_join" in types || "join" in types, types.toString())
        assertTrue("where" in types, types.toString())
        assertTrue("greater_than" in types, types.toString())
    }

    @Test
    fun `聚合与窗口落位`() {
        val outcome = engine.parse("SELECT SUM(x) AS s, ROW_NUMBER() OVER (PARTITION BY y ORDER BY x) AS rn FROM t GROUP BY y")
        assertTrue(outcome.hasErrors.not(), outcome.diagnostics.toString())
        val types = outcome.root.flatten().map { it.type }.toSet()
        assertTrue("sum" in types, types.toString())
        assertTrue("over" in types, types.toString())
        assertTrue("group_by" in types, types.toString())
    }
}
