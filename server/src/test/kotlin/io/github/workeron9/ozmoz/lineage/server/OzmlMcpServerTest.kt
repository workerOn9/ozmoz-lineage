package io.github.workeron9.ozmoz.lineage.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ozml mcp` 的进程内**真实协议**测试：服务端跑在管道流上，测试进程按 MCP
 * （JSON-RPC over stdio）手写 initialize → tools/list → tools/call——不是 mock，
 * 传输、握手、schema 校验、结果编码全走真代码路径。
 *
 * 断言重点（与 `Routes` 的 HTTP 取舍同取向）：
 * - 工具清单恰为 8 个 `ozml_*` 工具；
 * - 语法错误 / unknown / 不可达是**数据**（isError=false），不是工具失败；
 * - 缺参数 / 未知引擎 / 坏 DDL / 非法枚举 / 目标列不在图里是 isError=true，
 *   内容为 ErrorResponse JSON（4xx / 422 的镜像）。
 */
class OzmlMcpServerTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ———————————————————— 管道上的 MCP 会话 ————————————————————

    /**
     * 一个最小 MCP 客户端：后台线程把服务端的 stdout 按行读进队列，
     * [call] / [listTools] 按 id 匹配响应（跳过通知与服务端发起的请求）。
     */
    private class Session : AutoCloseable {
        private val json = Json { ignoreUnknownKeys = true }
        private val clientOut = PipedOutputStream() // 测试写 → 服务端读
        private val serverIn = PipedInputStream(clientOut)
        private val serverOut = PipedOutputStream() // 服务端写 → 测试读
        private val clientIn = PipedInputStream(serverOut)
        private val server: AutoCloseable = OzmlMcpServer.start(serverIn, serverOut)
        private val writer = PrintWriter(clientOut, true)
        private val inbox = LinkedBlockingQueue<JsonObject>()
        private var nextId = 0
        private val reader: Thread

        init {
            reader = Thread {
                BufferedReader(InputStreamReader(clientIn)).useLines { lines ->
                    lines.forEach { line ->
                        val node = json.parseToJsonElement(line).jsonObject
                        // 只认响应（有 id 且有 result / error）；通知与上行请求跳过。
                        if (node.containsKey("id") && (node.containsKey("result") || node.containsKey("error"))) {
                            inbox.put(node)
                        }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            initialize()
        }

        private fun initialize() {
            val result = rpc(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", "2025-06-18")
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", "ozml-test")
                        put("version", "0")
                    }
                },
            )
            assertEquals(
                "ozml-mcp",
                result.getValue("result").jsonObject.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content,
            )
            // initialized 通知（notifications 无需响应）。
            writer.println(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", "notifications/initialized")
                    },
                ),
            )
        }

        /** 发一个 JSON-RPC 请求，等到同 id 的响应（10s 超时）。 */
        private fun rpc(method: String, params: JsonObject? = null): JsonObject {
            val id = ++nextId
            writer.println(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("method", method)
                        if (params != null) put("params", params)
                    },
                ),
            )
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                val msg = inbox.poll(1, TimeUnit.SECONDS) ?: continue
                val msgId = msg["id"]?.jsonPrimitive?.content?.toIntOrNull()
                if (msgId == id) return msg
            }
            error("等待响应超时（id=$id）")
        }

        fun listTools(): List<String> {
            val id = ++nextId
            writer.println(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("method", "tools/list")
                    },
                ),
            )
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                val msg = inbox.poll(1, TimeUnit.SECONDS) ?: continue
                if (msg["id"]?.jsonPrimitive?.content?.toIntOrNull() == id) {
                    return msg.getValue("result").jsonObject.getValue("tools").jsonArray.map {
                        it.jsonObject.getValue("name").jsonPrimitive.content
                    }
                }
            }
            error("tools/list 超时")
        }

        /** 调工具：返回 (isError, 首段文本)。 */
        fun call(name: String, arguments: JsonObject): Pair<Boolean, String> {
            val response = rpc(
                "tools/call",
                buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            )
            val result = response.getValue("result").jsonObject
            val text = result.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content
            return result.getValue("isError").jsonPrimitive.boolean to text
        }

        override fun close() {
            runCatching { writer.close() } // stdin EOF：服务端会话随之关闭
            runCatching { server.close() }
        }
    }

    private fun callTool(
        session: Session,
        name: String,
        sql: String? = null,
        vararg extra: Pair<String, kotlinx.serialization.json.JsonElement>,
    ): Pair<Boolean, String> {
        val args = buildJsonObject {
            if (sql != null) put("sql", sql)
            extra.forEach { (k, v) -> put(k, v) }
        }
        return session.call(name, args)
    }

    // ———————————————————— 测试用例 ————————————————————

    @Test
    fun `工具清单恰为八个 ozml 工具`() {
        Session().use { session ->
            assertEquals(
                listOf(
                    "ozml_engines", "ozml_parse", "ozml_lineage", "ozml_impact",
                    "ozml_path", "ozml_validate", "ozml_format", "ozml_convert",
                ),
                session.listTools(),
            )
        }
    }

    @Test
    fun `engines 返回三个注册引擎`() {
        Session().use { session ->
            val (isError, text) = callTool(session, "ozml_engines")
            assertFalse(isError)
            val ids = json.parseToJsonElement(text).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(listOf("jsqlparser", "calcite", "jooq"), ids)
        }
    }

    @Test
    fun `parse 语法错误是数据不是失败`() {
        Session().use { session ->
            val (isError, text) = callTool(session, "ozml_parse", "SELEC 1")
            assertFalse(isError)
            val diagnostics = json.parseToJsonElement(text).jsonObject.getValue("diagnostics").jsonArray
            assertTrue(diagnostics.isNotEmpty())
            assertEquals("ERROR", diagnostics[0].jsonObject.getValue("severity").jsonPrimitive.content)
        }
    }

    @Test
    fun `lineage 单语句返回模型 JSON`() {
        Session().use { session ->
            val (isError, text) = callTool(session, "ozml_lineage", VIEW_SQL)
            assertFalse(isError)
            val model = json.parseToJsonElement(text).jsonObject
            assertTrue(model.getValue("edges").jsonArray.isNotEmpty(), "应产出列级边")
            assertTrue(model.getValue("columns").jsonArray.isNotEmpty(), "应产出列清单")
        }
    }

    @Test
    fun `lineage 多语句缺省是无法满足 aggregate 为真返回合并图`() {
        Session().use { session ->
            val (err1, text1) = callTool(session, "ozml_lineage", "$VIEW_SQL;\n$REPORT_SQL;")
            assertTrue(err1)
            assertEquals("multi_statement_input", json.parseToJsonElement(text1).jsonObject.getValue("error").jsonPrimitive.content)

            val (err2, text2) = callTool(
                session, "ozml_lineage", "$VIEW_SQL;\n$REPORT_SQL;",
                "aggregate" to kotlinx.serialization.json.JsonPrimitive(true),
            )
            assertFalse(err2)
            val graph = json.parseToJsonElement(text2).jsonObject
            assertTrue(graph.containsKey("nodes") && graph.containsKey("edges"), "aggregate 应回合并图")
        }
    }

    @Test
    fun `lineage 整段语法错误是 unknown 数据`() {
        Session().use { session ->
            val (isError, text) = callTool(session, "ozml_lineage", "SELEC 1")
            assertFalse(isError)
            assertTrue(json.parseToJsonElement(text).jsonObject.containsKey("unknown"))
        }
    }

    @Test
    fun `impact 命中与未命中`() {
        Session().use { session ->
            val (err1, text1) = callTool(
                session, "ozml_impact", "$VIEW_SQL;\n$REPORT_SQL;",
                "on" to kotlinx.serialization.json.JsonPrimitive("orders_report.total"),
            )
            assertFalse(err1)
            val report = json.parseToJsonElement(text1).jsonObject
            // 与 /api/impact 同形：direction 是 Direction 枚举名（大写）。
            assertEquals("UPSTREAM", report.getValue("direction").jsonPrimitive.content)
            val ids = report.getValue("nodes").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertTrue("orders.total" in ids && "v_orders.total" in ids, "上溯应穿透两层：$ids")

            val (err2, text2) = callTool(session, "ozml_impact", VIEW_SQL, "on" to kotlinx.serialization.json.JsonPrimitive("nope.col"))
            assertTrue(err2)
            assertEquals("column_not_found", json.parseToJsonElement(text2).jsonObject.getValue("error").jsonPrimitive.content)
        }
    }

    @Test
    fun `path 可达返回路径 不可达是 path 为 null 的数据`() {
        Session().use { session ->
            // 两级链：orders.total → v_orders.total → orders_report.total（downstream）。
            val (err1, text1) = callTool(
                session, "ozml_path", "$VIEW_SQL;\n$REPORT_SQL;",
                "from" to kotlinx.serialization.json.JsonPrimitive("orders.total"),
                "to" to kotlinx.serialization.json.JsonPrimitive("orders_report.total"),
            )
            assertFalse(err1)
            val report = json.parseToJsonElement(text1).jsonObject
            val path = report["path"]?.jsonArray
            assertNotNull(path, "两级链应可达")
            assertEquals(3, path!!.size)

            // 两条互不相干的 SELECT：两列都在图里但不可达——path=null 是数据，不是失败。
            val (err2, text2) = callTool(
                session, "ozml_path", "SELECT a.x FROM t_a a;\nSELECT b.y FROM t_b b;",
                "from" to kotlinx.serialization.json.JsonPrimitive("t_a.x"),
                "to" to kotlinx.serialization.json.JsonPrimitive("t_b.y"),
            )
            assertFalse(err2)
            assertNull(json.parseToJsonElement(text2).jsonObject["path"])
        }
    }

    @Test
    fun `validate 结合内联 DDL 诊断未知列`() {
        Session().use { session ->
            val (isError, text) = callTool(
                session, "ozml_validate", "SELECT no_such_col FROM orders",
                "schema" to kotlinx.serialization.json.JsonPrimitive("CREATE TABLE orders (id INT, total DECIMAL)"),
            )
            assertFalse(isError)
            val diagnostics = json.parseToJsonElement(text).jsonObject.getValue("diagnostics").jsonArray
            assertTrue(diagnostics.any { it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR" })
        }
    }

    @Test
    fun `format 排版返回多行 SQL`() {
        Session().use { session ->
            val (isError, text) = callTool(session, "ozml_format", "select id, total from orders where total > 0")
            assertFalse(isError)
            val sql = json.parseToJsonElement(text).jsonObject.getValue("sql").jsonPrimitive.content
            assertTrue(sql.lines().size > 1, "多行排版应换行：$sql")
        }
    }

    @Test
    fun `convert 方言转换产出目标方言 SQL`() {
        Session().use { session ->
            val (isError, text) = callTool(
                session, "ozml_convert", "SELECT id FROM orders LIMIT 5",
                "from" to kotlinx.serialization.json.JsonPrimitive("mysql"),
                "to" to kotlinx.serialization.json.JsonPrimitive("hive"),
            )
            assertFalse(isError)
            // render-verified 门禁保证等价；hive（Lex.MYSQL 系）渲染保留 LIMIT。
            val sql = json.parseToJsonElement(text).jsonObject.getValue("sql").jsonPrimitive.content
            assertTrue(sql.contains("LIMIT", ignoreCase = true), "hive 渲染应保留 LIMIT：$sql")
        }
    }

    @Test
    fun `请求非法与未知引擎是 isError`() {
        Session().use { session ->
            val (err1, text1) = callTool(session, "ozml_parse", "   ")
            assertTrue(err1)
            assertEquals("invalid_request", json.parseToJsonElement(text1).jsonObject.getValue("error").jsonPrimitive.content)

            val (err2, text2) = callTool(session, "ozml_parse", "SELECT 1", "engine" to kotlinx.serialization.json.JsonPrimitive("nope"))
            assertTrue(err2)
            assertEquals("unknown_engine", json.parseToJsonElement(text2).jsonObject.getValue("error").jsonPrimitive.content)
        }
    }

    @Test
    fun `坏的内联 DDL 是 invalid_schema`() {
        Session().use { session ->
            val (isError, text) = callTool(
                session, "ozml_lineage", "SELECT 1",
                "schema" to kotlinx.serialization.json.JsonPrimitive("CREATE TABLE t (id"),
            )
            assertTrue(isError)
            assertEquals("invalid_schema", json.parseToJsonElement(text).jsonObject.getValue("error").jsonPrimitive.content)
        }
    }

    companion object {
        /** 测试 SQL：自造电商 schema（合规红线：不用任何真实业务标识）。两级列级血缘链。 */
        private const val VIEW_SQL = "CREATE VIEW v_orders AS SELECT o.id, o.total FROM orders o"
        private const val REPORT_SQL = "CREATE TABLE orders_report AS SELECT v.id, v.total FROM v_orders v"
    }
}
