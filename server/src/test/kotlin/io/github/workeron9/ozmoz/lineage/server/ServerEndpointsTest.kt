package io.github.workeron9.ozmoz.lineage.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/api` 端点测试：全部经真实 Ktor 路由（testApplication），真实引擎建图。
 *
 * 形状断言用「解析成 JSON 再导航」而不是字符串包含——`explicitNulls = false`
 * 意味着 null 字段会被省略，字符串断言对形状变化太迟钝。
 * JVM 的 kotlin.test `@Test` 不支持 suspend：统一包 [runBlocking]。
 */
class ServerEndpointsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(text: String) = json.parseToJsonElement(text).jsonObject

    /** GET / POST 一次真实请求，返回 (状态码, 响应体)；body 为 null 时用 GET。 */
    private fun request(path: String, body: String? = null): Pair<Int, String> =
        runBlocking {
            var status = -1
            var text = ""
            testApplication {
                application { serverModule() }
                val response = if (body == null) {
                    client.get(path)
                } else {
                    client.post(path) {
                        contentType(ContentType.Application.Json)
                        headers.append(HttpHeaders.Accept, "application/json")
                        setBody(body)
                    }
                }
                status = response.status.value
                text = response.bodyAsText()
            }
            status to text
        }

    @Test
    fun `health 返回 ok 与版本号`() {
        val (status, text) = request("/api/health")

        assertEquals(200, status)
        val body = parse(text)
        assertEquals("ok", body.getValue("status").jsonPrimitive.content)
        assertTrue(body.getValue("version").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `engines 列出 jsqlparser 及其能力`() {
        val (status, text) = request("/api/engines")

        assertEquals(200, status)
        val engines = json.parseToJsonElement(text).jsonArray
        assertEquals(1, engines.size)
        val engine = engines[0].jsonObject
        assertEquals("jsqlparser", engine.getValue("id").jsonPrimitive.content)
        val features = engine.getValue("features").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("MULTI_STATEMENT" in features)
        assertTrue("SEMANTIC_MODEL" in features)
    }

    @Test
    fun `parse 返回归一化树与表引用`() {
        val (status, text) = request("/api/parse", """{"sql": "SELECT a FROM s"}""")

        assertEquals(200, status)
        val body = parse(text)
        assertEquals("select", body.getValue("root").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("s", body.getValue("tables").jsonArray[0].jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(0, body.getValue("diagnostics").jsonArray.size)
    }

    @Test
    fun `parse 解析失败是数据不是错误`() {
        val (status, text) = request("/api/parse", """{"sql": "SELEC a FROM"}""")

        // 语法错误仍 200：错误形态在 diagnostics（severity=ERROR），调用方按数据读。
        assertEquals(200, status)
        val diagnostics = parse(text).getValue("diagnostics").jsonArray
        assertTrue(diagnostics.size > 0)
    }

    @Test
    fun `parse 缺 sql 返回 400`() {
        val (status, text) = request("/api/parse", """{"engine": "jsqlparser"}""")

        assertEquals(400, status)
        assertEquals("invalid_request", parse(text).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `lineage 单语句返回血缘模型`() {
        val (status, text) = request("/api/lineage", """{"sql": "SELECT a FROM t"}""")

        assertEquals(200, status)
        val body = parse(text)
        val outputs = body.getValue("edges").jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("kind").jsonPrimitive.content == "OUTPUT" }
        assertEquals(1, outputs.size)
        // qualifiedName 是计算属性不序列化；断言落到序列化字段（table / name）。
        val from = outputs[0].getValue("fromColumn").jsonObject
        assertEquals("t", from.getValue("table").jsonPrimitive.content)
        assertEquals("a", from.getValue("name").jsonPrimitive.content)
        assertEquals("a", outputs[0].getValue("toColumn").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `lineage 内联 DDL 撑起星号展开`() {
        val (status, text) = request(
            "/api/lineage",
            """{"sql": "SELECT * FROM t", "schema": "CREATE TABLE t (a INT, b INT)"}""",
        )

        assertEquals(200, status)
        val body = parse(text)
        // 2 OUTPUT（t.a→a、t.b→b）+ 2 SOURCE，且 unknowns 为空。
        assertEquals(4, body.getValue("edges").jsonArray.size)
        assertEquals(0, body.getValue("unknowns").jsonArray.size)
    }

    @Test
    fun `lineage 无 schema 参数时从 sql 自动收集 DDL`() {
        val (status, text) = request(
            "/api/lineage",
            """{"sql": "CREATE TABLE t (a INT, b INT); SELECT * FROM t"}""",
        )

        assertEquals(200, status)
        val body = parse(text)
        assertEquals(4, body.getValue("edges").jsonArray.size)
        assertEquals(0, body.getValue("unknowns").jsonArray.size)
    }

    @Test
    fun `lineage 内联 DDL 解析失败返回 400`() {
        val (status, text) = request(
            "/api/lineage",
            """{"sql": "SELECT a FROM t", "schema": "THIS IS NOT SQL"}""",
        )

        assertEquals(400, status)
        assertEquals("invalid_schema", parse(text).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `lineage 多语句返回 422`() {
        val (status, text) = request(
            "/api/lineage",
            """{"sql": "SELECT a FROM t; SELECT b FROM t"}""",
        )

        assertEquals(422, status)
        val body = parse(text)
        assertEquals("multi_statement_input", body.getValue("error").jsonPrimitive.content)
        assertEquals(2, body.getValue("statementCount").jsonPrimitive.content.toInt())
    }

    @Test
    fun `lineage 整段语法错误返回 200 unknown`() {
        val (status, text) = request("/api/lineage", """{"sql": "SELEC a FROM"}""")

        assertEquals(200, status)
        assertTrue(parse(text).getValue("unknown").jsonObject.getValue("reason").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `impact 返回下溯影响面`() {
        val (status, text) = request(
            "/api/impact",
            """{"sql": "INSERT INTO dst (x) SELECT a FROM t", "on": "t.a", "direction": "downstream"}""",
        )

        assertEquals(200, status)
        val body = parse(text)
        assertEquals("t.a", body.getValue("origin").jsonPrimitive.content)
        val reached = body.getValue("nodes").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertTrue("dst.x" in reached)
    }

    @Test
    fun `impact 目标列不在图里返回 422`() {
        val (status, text) = request(
            "/api/impact",
            """{"sql": "SELECT a FROM t", "on": "nope.col"}""",
        )

        assertEquals(422, status)
        assertEquals("column_not_found", parse(text).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `impact direction 非法返回 400`() {
        val (status, text) = request(
            "/api/impact",
            """{"sql": "SELECT a FROM t", "on": "t.a", "direction": "sideways"}""",
        )

        assertEquals(400, status)
        assertEquals("invalid_direction", parse(text).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `impact 无可建模语句返回 422`() {
        val (status, text) = request(
            "/api/impact",
            """{"sql": "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = 1", "on": "t.a"}""",
        )

        assertEquals(422, status)
        assertEquals("no_modelable_statement", parse(text).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `impact depth 负数返回 400`() {
        val (status, text) = request(
            "/api/impact",
            """{"sql": "SELECT a FROM t", "on": "t.a", "depth": -1}""",
        )

        assertEquals(400, status)
        assertEquals("invalid_depth", parse(text).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `path 返回最短路径与终点标签`() {
        val (status, text) = request(
            "/api/path",
            """{"sql": "INSERT INTO dst (x) SELECT a FROM t", "from": "t.a", "to": "dst.x"}""",
        )

        assertEquals(200, status)
        val path = parse(text).getValue("path").jsonArray.map { it.jsonObject }
        assertEquals(listOf("t.a", "dst.x"), path.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("t.a", path[0].getValue("label").jsonPrimitive.content)
    }

    @Test
    fun `path 不可达返回 200 且 path 为空`() {
        // 两个互不连通的查询（裸列名不跨模型缝合是 graph 的已知限制）→ path 为 null（省略）。
        val (status, text) = request(
            "/api/path",
            """{"sql": "SELECT a FROM t; SELECT z FROM u", "from": "t.a", "to": "u.z"}""",
        )

        assertEquals(200, status)
        assertTrue("path" !in parse(text).keys, "不可达时 path 字段应被省略（explicitNulls=false）")
    }

    @Test
    fun `path 缺 to 返回 400`() {
        val (status, text) = request(
            "/api/path",
            """{"sql": "SELECT a FROM t", "from": "t.a"}""",
        )

        assertEquals(400, status)
        assertEquals("invalid_request", parse(text).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `path 起点不在图里返回 422`() {
        val (status, text) = request(
            "/api/path",
            """{"sql": "SELECT a FROM t", "from": "nope.x", "to": "t.a"}""",
        )

        assertEquals(422, status)
        assertEquals("column_not_found", parse(text).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `未知 engine 返回 400`() {
        val (status, text) = request("/api/parse", """{"sql": "SELECT 1", "engine": "mine"}""")

        assertEquals(400, status)
        assertEquals("unknown_engine", parse(text).getValue("error").jsonPrimitive.content)
    }
}
