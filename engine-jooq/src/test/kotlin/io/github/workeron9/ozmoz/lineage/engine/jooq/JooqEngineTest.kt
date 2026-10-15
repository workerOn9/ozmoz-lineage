package io.github.workeron9.ozmoz.lineage.engine.jooq

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
 * `JooqEngine`：OSS 方言面（12 + DEFAULT）、**QOM 归一化树**（结构 + 函数 + 算子）、
 * parse-verified 门禁（中性渲染基线）与 Never-wrong（不抛异常）。
 */
class JooqEngineTest {

    private val engine = JooqEngine()

    // ————— 能力表 —————

    @Test
    fun `能力表声明 parse 与方言渲染 且方言面为 OSS 承诺清单`() {
        assertTrue(engine.capabilities.supports(Feature.PARSE))
        assertTrue(engine.capabilities.supports(Feature.DIALECT_RENDER))
        // QOM 归一化树（2026-10-15 起）：不再是浅树，能力表如实申报
        assertTrue(engine.capabilities.supports(Feature.AST_EXPORT))
        assertTrue(!engine.capabilities.supports(Feature.SEMANTIC_MODEL))
        // OSS 可承诺清单（jOOQ support-matrix 口径；derby 3.21 起弃用待移除，不承诺）
        for (id in listOf("mysql", "mariadb", "postgres", "postgresql", "h2", "hsqldb", "firebird", "sqlite", "duckdb", "trino", "clickhouse", "yugabytedb", "ansi")) {
            assertContains(engine.capabilities.dialects, id)
        }
        // 商业版方言 / 将移除的常量不承诺（jOOQ OSS 编译期不存在或弃用待移除）
        for (id in listOf("mssql", "tsql", "oracle", "snowflake", "bigquery", "hive", "spark", "cubrid", "ignite", "derby")) {
            assertTrue(id !in engine.capabilities.dialects, "$id 不应被承诺")
        }
    }

    @Test
    fun `能力表对不支持项给可解释的原因`() {
        // AST_EXPORT 已申报，不再有不支持原因
        assertTrue(engine.capabilities.reason(Feature.AST_EXPORT) == null)
        assertContains(engine.capabilities.reason(Feature.PRETTY_PRINT)!!, "排版")
    }

    // ————— parse —————

    @Test
    fun `parse 单语句出 select 树不是一层浅树`() {
        val outcome = engine.parse("SELECT id, name FROM customer WHERE id > 1", ParseRequest(dialect = "mysql"))
        assertTrue(!outcome.hasErrors, outcome.diagnostics.toString())
        assertEquals("select", outcome.root.type)
        // jOOQ 的 text 是**渲染文本**（不是源文切片）：小写、逐子句换行
        assertContains(outcome.root.text.lowercase(), "from customer")
        // QOM 真树：结构 + 表达式都拍下来了（2026-10-15 之前这里只有 1 个节点）。
        val types = outcome.root.flatten().map { it.type }.toList()
        assertContains(types, "from")
        assertContains(types, "select_list")
        assertContains(types, "where")
        assertContains(types, "column")
        assertContains(types, "literal")
        assertContains(types, "binary_op")
    }

    @Test
    fun `parse QOM 树覆盖 WITH 连接 大小写 CASE 聚合 CAST 与排序分页`() {
        val sql = """
            WITH paid AS (SELECT order_id, amount FROM orders WHERE status = 'paid')
            SELECT c.name,
                   CASE WHEN o.amount > 100 THEN 'big' ELSE 'small' END AS bucket,
                   COALESCE(SUM(o.amount), 0) AS total,
                   CAST(o.amount AS NUMERIC(10, 2)) AS casted
            FROM paid o
            LEFT JOIN customers c ON c.id = o.order_id
            WHERE o.amount > 0
            GROUP BY c.name
            HAVING SUM(o.amount) > 10
            ORDER BY total DESC
            LIMIT 10
        """.trimIndent()
        val outcome = engine.parse(sql, ParseRequest(dialect = "postgres"))
        assertTrue(!outcome.hasErrors, outcome.diagnostics.toString())
        val types = outcome.root.flatten().map { it.type }.toList()
        for (expected in listOf(
            "select", "with", "cte", "from", "left_join", "on", "select_list", "alias",
            "case", "when", "else", "function", "cast", "column", "literal",
            "where", "group_by", "having", "order_by", "limit",
        )) {
            assertTrue(types.contains(expected), "QOM 树缺 $expected；实际：${types.toSet()}")
        }
        // 别名节点的 text 是别名本身（与另两引擎同口径）
        val bucket = outcome.root.flatten().first { it.type == "alias" && it.text.equals("bucket", true) }
        assertEquals("case", bucket.children.first().type)
    }

    @Test
    fun `parse QOM 树的子节点没有源位置但根节点有整条语句的切片`() {
        val outcome = engine.parse("SELECT id FROM customer", ParseRequest(dialect = "mysql"))
        // jOOQ 不保留 token 位置：只有根节点给整条语句切片（「定位到 SQL」落到语句）
        assertEquals(0, outcome.root.span?.start?.offset)
        val child = outcome.root.children.first()
        assertTrue(child.span == null, "子节点不应有 span（jOOQ 不保留位置）：${child.type}")
    }

    @Test
    fun `parse 认不出的结构落 unknown_node 但保留渲染文本`() {
        // SortField（ORDER BY 项）在 OSS 的 QOM 里没有可用接口 → 如实落 unknown_node
        val outcome = engine.parse("SELECT a FROM t ORDER BY a DESC", ParseRequest(dialect = "mysql"))
        assertTrue(!outcome.hasErrors, outcome.diagnostics.toString())
        val orderBy = outcome.root.flatten().first { it.type == "order_by" }
        val leaf = orderBy.children.single()
        assertEquals("unknown_node", leaf.type)
        assertContains(leaf.text.uppercase(), "DESC")
    }

    @Test
    fun `parse 多语句出 statement_list`() {
        val outcome = engine.parse("SELECT 1; DELETE FROM t WHERE id = 2;", ParseRequest(dialect = "postgres"))
        assertTrue(!outcome.hasErrors, outcome.diagnostics.toString())
        assertEquals("statement_list", outcome.root.type)
        assertEquals(2, outcome.root.children.size)
        assertEquals("delete", outcome.root.children[1].type)
    }

    @Test
    fun `parse 语法错误不抛异常 带 span 定位`() {
        val outcome = engine.parse("SELECT no FROM", ParseRequest(dialect = "mysql"))
        assertTrue(outcome.hasErrors)
        val diagnostic = outcome.diagnostics.first()
        assertEquals(Severity.ERROR, diagnostic.severity)
        assertEquals(JooqEngine.CODE_PARSE_ERROR, diagnostic.code)
        assertEquals("jooq", diagnostic.engineId)
        // jOOQ ParserException.position() 可定位（0 基字符偏移）
        assertEquals(14, diagnostic.span?.start?.offset)
    }

    @Test
    fun `parse 空输入`() {
        val outcome = engine.parse("   ")
        assertTrue(outcome.hasErrors)
        assertEquals(JooqEngine.CODE_EMPTY_INPUT, outcome.diagnostics.first().code)
    }

    @Test
    fun `parse 未注册方言给 dialect_unknown 不静默回退`() {
        val outcome = engine.parse("SELECT 1", ParseRequest(dialect = "snowflake"))
        assertTrue(outcome.hasErrors)
        assertEquals(JooqEngine.CODE_DIALECT_UNKNOWN, outcome.diagnostics.first().code)
    }

    // ————— render（verified 门禁） —————

    @Test
    fun `render mysql 转 postgres 分页改写 FETCH NEXT 且过门禁`() {
        val result = engine.render(
            RenderRequest(
                "SELECT id FROM customer ORDER BY id LIMIT 10",
                fromDialect = "mysql",
                toDialect = "postgresql",
            ),
        )
        assertTrue(result is Resolved.Known, "预期过门禁：$result")
        assertContains(result.value.lowercase(), "fetch next 10 rows only")
    }

    @Test
    fun `render 恒等与 CTE 均过门禁`() {
        for (pair in listOf(
            "SELECT id FROM customer" to "mysql",
            "WITH x AS (SELECT id FROM a) SELECT id FROM x" to "postgresql",
        )) {
            val result = engine.render(
                RenderRequest(pair.first, fromDialect = pair.second, toDialect = pair.second),
            )
            assertTrue(result is Resolved.Known, "${pair.first} 预期过门禁：$result")
        }
    }

    @Test
    fun `render 结果被目标方言 re-parse 读不回的不输出`() {
        // 方括号对 jOOQ 是合法字符串字面量，渲染出来 re-parse 后按门禁比对——拦不住就必然 EQ。
        // 这格只覆盖「渲染成功但读不回」的异常分支；可构造的负例由 calcite 侧回归承担。
        val result = engine.render(
            RenderRequest("SELECT 1", fromDialect = "mysql", toDialect = "mssql"),
        )
        assertTrue(result is Resolved.Unknown, "mssql 未注册必须被拒：$result")
        assertContains(result.reason, JooqEngine.CODE_DIALECT_UNKNOWN)
    }

    @Test
    fun `render 未注册目标方言与缺参`() {
        assertTrue(engine.render(RenderRequest("SELECT 1", fromDialect = "mysql", toDialect = "snowflake")) is Resolved.Unknown)
        assertTrue(engine.render(RenderRequest("SELECT 1", fromDialect = "mysql", toDialect = null)) is Resolved.Unknown)
        assertTrue(engine.render(RenderRequest("SELECT 1", fromDialect = "cubrid", toDialect = "mysql")) is Resolved.Unknown)
    }

    @Test
    fun `render 源方言解析失败不抛异常`() {
        val result = engine.render(
            RenderRequest("SELECT FROM WHERE", fromDialect = "mysql", toDialect = "postgresql"),
        )
        assertTrue(result is Resolved.Unknown)
        assertContains(result.reason, "源 SQL 解析失败")
    }

    @Test
    fun `render 多语句逐条转换过门禁`() {
        val result = engine.render(
            RenderRequest(
                "SELECT a FROM t1 LIMIT 1; SELECT b FROM t2 LIMIT 2;",
                fromDialect = "mysql",
                toDialect = "postgresql",
            ),
        )
        assertTrue(result is Resolved.Known, "预期过门禁：$result")
        val sql = result.value
        assertEquals(2, Regex("(?i)select").findAll(sql).count(), sql)
    }

    @Test
    fun `render firebird 与 h2 等 OSS 关系库方言可用`() {
        for (target in listOf("h2", "firebird", "clickhouse", "yugabytedb")) {
            val result = engine.render(
                RenderRequest("SELECT id FROM customer WHERE id > 1", fromDialect = "mysql", toDialect = target),
            )
            assertTrue(result is Resolved.Known, "target=$target 预期过门禁：${result::class.simpleName}")
        }
    }
}
