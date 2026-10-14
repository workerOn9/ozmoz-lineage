package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.FormatOutcome
import io.github.workeron9.ozmoz.lineage.engine.FormatRequest
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Severity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JSqlParserEngineTest {

    private val engine = JSqlParserEngine()

    // ——— 能力声明 ———

    @Test
    fun `id 与能力声明`() {
        assertEquals("jsqlparser", engine.id)
        assertTrue(engine.capabilities.supports(Feature.PARSE))
        assertTrue(engine.capabilities.supports(Feature.EXTRACT_TABLES))
        // JSqlParser 没有方言概念：不得声明方言渲染
        assertFalse(engine.capabilities.supports(Feature.DIALECT_RENDER))
        assertNotNull(engine.capabilities.reason(Feature.DIALECT_RENDER))
    }

    @Test
    fun `render 返回 Unknown 且带原因`() {
        val result = engine.render(
            io.github.workeron9.ozmoz.lineage.engine.RenderRequest(
                sql = "SELECT 1", toDialect = "postgresql",
            ),
        )
        assertTrue(result is io.github.workeron9.ozmoz.lineage.ir.Resolved.Unknown)
        assertContains((result as io.github.workeron9.ozmoz.lineage.ir.Resolved.Unknown).reason, "原样回写")
    }

    // ——— 归一化树 ———

    @Test
    fun `select 归一化树形状`() {
        val outcome = engine.parse(
            "SELECT ss_quantity, ss_net_profit AS profit FROM store_sales WHERE ss_quantity > 0",
        )
        assertFalse(outcome.hasErrors)
        assertEquals("select", outcome.root.type)

        val types = outcome.root.flatten().map { it.type }.toList()
        assertContains(types, "plain_select")
        assertContains(types, "table")
        assertContains(types, "select_item")
        assertContains(types, "column")
        assertContains(types, "alias")
        assertContains(types, "where")
        assertContains(types, "binary_op")
        assertContains(types, "literal")
    }

    @Test
    fun `CAST 与双冒号在归一化树是 cast 节点 值表达式是子节点`() {
        val outcome = engine.parse("SELECT CAST(a AS VARCHAR) AS x, b::date AS y FROM t")
        assertFalse(outcome.hasErrors)

        val casts = outcome.root.flatten().filter { it.type == "cast" }.toList()
        assertEquals(2, casts.size) // CAST(...) 与 b::date 各一个
        assertTrue(casts.all { c -> c.children.any { it.type == "column" } })
        // 不再把 cast 当字面量（这是修这个 bug 的直接断言）。
        assertFalse(outcome.root.flatten().any { it.type == "literal" })
    }

    @Test
    fun `认不出的表达式包成 unknown_node 而不是假装 literal`() {
        val outcome = engine.parse("SELECT a FROM t WHERE a IN (1, 2)")
        val types = outcome.root.flatten().map { it.type }.toList()
        assertContains(types, "unknown_node")
    }

    @Test
    fun `节点 text 是源文本切片（Lossless）`() {
        val sql = "SELECT   ss_quantity   FROM store_sales"
        val outcome = engine.parse(sql)
        val column = outcome.root.flatten().first { it.type == "column" }
        // 保留原始空白与大小写，不是引擎重排版后的字符串
        assertEquals("ss_quantity", column.text)
        val table = outcome.root.flatten().first { it.type == "table" }
        assertEquals("store_sales", table.text)
    }

    @Test
    fun `span 精确到源文本`() {
        val sql = "SELECT ss_quantity FROM store_sales"
        val outcome = engine.parse(sql)
        val column = outcome.root.flatten().first { it.type == "column" }
        val span = assertNotNull(column.span)
        assertEquals("ss_quantity", sql.substring(span.start.offset, span.end.offset))
        assertEquals(1, span.start.line)
        assertEquals(8, span.start.column)

        val table = outcome.root.flatten().first { it.type == "table" }
        val tspan = assertNotNull(table.span)
        assertEquals("store_sales", sql.substring(tspan.start.offset, tspan.end.offset))
    }

    @Test
    fun `join 与 on 被归一化`() {
        val outcome = engine.parse(
            "SELECT s.a FROM store_sales AS s JOIN customer AS c ON s.id = c.id",
        )
        val types = outcome.root.flatten().map { it.type }.toList()
        assertContains(types, "join")
        assertContains(types, "on")
    }

    @Test
    fun `CTE 归一化为 cte 节点`() {
        val outcome = engine.parse("WITH c AS (SELECT a FROM s) SELECT a FROM c")
        assertContains(outcome.root.flatten().map { it.type }.toList(), "cte")
    }

    @Test
    fun `UNION 归一化为 set_operation`() {
        val outcome = engine.parse("SELECT a FROM s UNION ALL SELECT b FROM t")
        assertContains(outcome.root.flatten().map { it.type }.toList(), "set_operation")
    }

    @Test
    fun `INSERT SELECT 归一化`() {
        val outcome = engine.parse("INSERT INTO t (a, b) SELECT x, y FROM s")
        assertEquals("insert", outcome.root.type)
        val types = outcome.root.flatten().map { it.type }.toList()
        assertContains(types, "target_table")
        assertContains(types, "target_column")
    }

    @Test
    fun `CREATE TABLE AS 归一化`() {
        val outcome = engine.parse("CREATE TABLE t AS SELECT a FROM s")
        assertEquals("create_table", outcome.root.type)
    }

    @Test
    fun `CREATE VIEW 归一化`() {
        val outcome = engine.parse("CREATE VIEW v AS SELECT a FROM s")
        assertEquals("create_view", outcome.root.type)
    }

    @Test
    fun `UPDATE 与 DELETE 归一化`() {
        assertEquals("update", engine.parse("UPDATE t SET a = 1 WHERE b = 2").root.type)
        assertEquals("delete", engine.parse("DELETE FROM t WHERE a = 1").root.type)
    }

    @Test
    fun `SELECT 星号归一化为 all_columns`() {
        val outcome = engine.parse("SELECT * FROM s")
        assertContains(outcome.root.flatten().map { it.type }.toList(), "all_columns")
    }

    @Test
    fun `CASE 表达式归一化`() {
        val outcome = engine.parse("SELECT CASE WHEN a > 0 THEN 'p' ELSE 'n' END AS sign FROM t")
        val types = outcome.root.flatten().map { it.type }.toList()
        assertContains(types, "case")
        assertContains(types, "when")
        assertContains(types, "else")
    }

    @Test
    fun `函数与窗口归一化`() {
        val outcome = engine.parse("SELECT SUM(a) OVER (PARTITION BY b) FROM t")
        val types = outcome.root.flatten().map { it.type }.toList()
        // 窗口函数是 AnalyticExpression，单独归一化为 window_function
        assertContains(types, "window_function")
        assertContains(types, "partition_by")
    }

    @Test
    fun `普通函数归一化为 function`() {
        val outcome = engine.parse("SELECT SUM(a) FROM t")
        assertContains(outcome.root.flatten().map { it.type }.toList(), "function")
    }

    // ——— 表提取 ———

    @Test
    fun `提取表引用含别名与位置`() {
        val outcome = engine.parse("SELECT s.a FROM store_sales AS s JOIN customer AS c ON s.id = c.id")
        assertEquals(listOf("store_sales", "customer"), outcome.tables.map { it.name })
        assertEquals(listOf("s", "c"), outcome.tables.map { it.alias })
        assertTrue(outcome.tables.all { it.span != null })
    }

    @Test
    fun `限定名与引号保留原始拼写（Lossless）`() {
        val outcome = engine.parse("SELECT a FROM `my_db`.`MyTable`")
        val table = outcome.tables.single()
        assertEquals("MyTable", table.name)
        assertEquals("my_db", table.schema)
        // canonical 折叠为小写供匹配
        assertEquals("my_db.mytable", table.canonical)
    }

    @Test
    fun `双引号标识符大小写保留`() {
        val outcome = engine.parse("SELECT \"MyCol\" FROM \"MyTable\"")
        val table = outcome.tables.single()
        assertEquals("MyTable", table.name)
        assertEquals("mytable", table.canonical)
    }

    @Test
    fun `CTE 内表被提取且去重`() {
        val outcome = engine.parse("WITH c AS (SELECT a FROM s) SELECT a FROM c JOIN s ON c.a = s.a")
        // s 出现两次，去重后一次；c 是 CTE 名，不是物理表来源
        assertContains(outcome.tables.map { it.canonical }, "s")
    }

    @Test
    fun `子查询内的表被提取`() {
        val outcome = engine.parse("SELECT a FROM (SELECT a FROM inner_t) AS sub")
        assertEquals(listOf("inner_t"), outcome.tables.map { it.canonical })
    }

    // ——— 错误处理（Never-wrong） ———

    @Test
    fun `解析失败返回 ERROR 诊断而非抛异常`() {
        val outcome = engine.parse("SELECT * FROM")
        assertTrue(outcome.hasErrors)
        val diag = outcome.diagnostics.single()
        assertEquals(Severity.ERROR, diag.severity)
        assertEquals(JSqlParserEngine.CODE_PARSE_ERROR, diag.code)
        assertEquals("jsqlparser", diag.engineId)
    }

    @Test
    fun `解析失败带位置`() {
        val sql = "SELECT * FROM"
        val outcome = engine.parse(sql)
        val span = assertNotNull(outcome.diagnostics.single().span)
        assertEquals(1, span.start.line)
        // 失败 token 是 `*`（第 8 列）
        assertEquals(8, span.start.column)
    }

    @Test
    fun `空输入返回诊断而非抛异常`() {
        val outcome = engine.parse("   ")
        assertTrue(outcome.hasErrors)
        assertEquals(JSqlParserEngine.CODE_EMPTY_INPUT, outcome.diagnostics.single().code)
        assertEquals(AstNode.empty(), outcome.root)
    }

    @Test
    fun `多语句取首条不报错`() {
        // v1 行为：CCJSqlParserUtil.parse 取首条语句（实测 2026-10-08）。
        // 多语句脚本的完整支持在 lineage 模块（M2）。
        val outcome = engine.parse("SELECT 1; SELECT 2")
        assertFalse(outcome.hasErrors)
    }

    @Test
    fun `ParseRequest 默认值可用`() {
        val outcome = engine.parse("SELECT 1", ParseRequest.DEFAULT)
        assertFalse(outcome.hasErrors)
    }

    // ——— 格式化（PRETTY_PRINT） ———

    private fun format(sql: String, multiline: Boolean = true) =
        engine.format(FormatRequest(sql = sql, multiline = multiline))

    @Test
    fun `format 能力已申报`() {
        assertTrue(engine.capabilities.supports(Feature.PRETTY_PRINT))
        // 申报后 reason 为 null（支持的能力不该有「不支持原因」）
        assertNull(engine.capabilities.reason(Feature.PRETTY_PRINT))
    }

    @Test
    fun `format 多行排版按子句换行`() {
        val result = assertIs<Resolved.Known<FormatOutcome>>(
            format("select t.a, u.b from t join u on t.id=u.id where t.x=1 group by t.a,u.b having count(*)>2 order by t.a desc limit 10 offset 5"),
        )
        val lines = result.value.sql.lines()
        assertEquals("SELECT t.a, u.b", lines[0])
        assertTrue(lines.contains("FROM t"))
        assertTrue(lines.contains("JOIN u ON t.id = u.id"))
        assertTrue(lines.contains("WHERE t.x = 1"))
        assertTrue(lines.contains("GROUP BY t.a, u.b"))
        assertTrue(lines.contains("HAVING count(*) > 2"))
        assertTrue(lines.contains("ORDER BY t.a DESC"))
        assertTrue(lines.contains("LIMIT 10"))
        assertTrue(lines.contains("OFFSET 5"))
        assertTrue(result.value.diagnostics.isEmpty())
    }

    @Test
    fun `format 单行模式产规范化单行`() {
        val result = assertIs<Resolved.Known<FormatOutcome>>(
            format("select a,b from t where x=1", multiline = false),
        )
        assertEquals("SELECT a, b FROM t WHERE x = 1", result.value.sql)
        assertFalse(result.value.sql.contains('\n'))
    }

    @Test
    fun `format 子查询缩进且函数参数不换行`() {
        val result = assertIs<Resolved.Known<FormatOutcome>>(
            format("select x from (select a as x, b from t where a>1) sub where x<10"),
        )
        val sql = result.value.sql
        // 子查询整体缩进一层
        assertTrue(sql.contains("\n  SELECT a AS x, b\n"), sql)
        assertTrue(sql.contains("\n  FROM t\n"), sql)
        // 闭合括号另起一行回到外层缩进
        assertTrue(sql.contains("\n) sub\n"), sql)

        // 函数参数里的 FROM 不是子句，不换行（关键回归：纯文本切分会误伤）
        // 注：EXTRACT 的 unit（year）由 JSqlParser 渲染为小写——格式化器忠于引擎渲染，
        // 不做二次大小写改写（内容单一来源，见 ADR-0014）。
        val fn = assertIs<Resolved.Known<FormatOutcome>>(format("select extract(year from d) as y from t"))
        assertEquals("SELECT EXTRACT(year FROM d) AS y\nFROM t", fn.value.sql)
    }

    @Test
    fun `format 集合运算与 CTE`() {
        // 注：集合运算的 `ALL` 由 JSqlParser 渲染为小写 `all`（引擎既有瑕疵）——
        // 格式化器忠于引擎渲染，不掩盖（内容单一来源，见 ADR-0014）。
        val union = assertIs<Resolved.Known<FormatOutcome>>(format("select a from t1 union all select b from t2"))
        assertEquals(
            "SELECT a\nFROM t1\nUNION all\nSELECT b\nFROM t2",
            union.value.sql,
        )

        val cte = assertIs<Resolved.Known<FormatOutcome>>(format("with c as (select a as x from s) select x from c"))
        assertEquals(
            "WITH c AS (\n  SELECT a AS x\n  FROM s\n)\nSELECT x\nFROM c",
            cte.value.sql,
        )
    }

    @Test
    fun `format 字符串里的关键字不换行`() {
        val result = assertIs<Resolved.Known<FormatOutcome>>(
            format("select 'from where join' as x from t"),
        )
        assertEquals("SELECT 'from where join' AS x\nFROM t", result.value.sql)
    }

    @Test
    fun `format 注释丢失落 WARNING 诊断`() {
        val result = assertIs<Resolved.Known<FormatOutcome>>(
            format("select a /* keep me */ from t"),
        )
        // 注释被渲染器丢弃（有损），如实报 WARNING
        assertEquals("SELECT a\nFROM t", result.value.sql)
        val diag = result.value.diagnostics.single()
        assertEquals(Severity.WARNING, diag.severity)
        assertEquals(JSqlParserEngine.CODE_COMMENT_DROPPED, diag.code)
        assertNotNull(diag.span)
        // span 指向源文本里的注释
        assertEquals("/* keep me */", "select a /* keep me */ from t".substring(diag.span!!.start.offset, diag.span!!.end.offset))
    }

    @Test
    fun `format 无注释时无诊断`() {
        val result = assertIs<Resolved.Known<FormatOutcome>>(format("select a from t"))
        assertTrue(result.value.diagnostics.isEmpty())
    }

    @Test
    fun `format 语法错误返回 Unknown`() {
        val result = format("select from where")
        assertIs<Resolved.Unknown>(result)
    }

    @Test
    fun `format 空输入返回 Unknown`() {
        val result = format("   ")
        assertIs<Resolved.Unknown>(result)
    }
}
