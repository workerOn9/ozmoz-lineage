package io.github.workeron9.ozmoz.lineage.server

import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.graph.SqliteLineageStore
import io.github.workeron9.ozmoz.lineage.graph.StoredModel
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.lineage.LineageBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 图库浏览（ADR-0015）的端点测试：白名单根解析 + /api/graphs + graph 字段。
 *
 * 核心断言是**安全面**：绝对路径 / 逃逸 / 非 .db 必须被拒；未配置 --graph-root
 * 时该能力整体不可用（400）；sql 路径行为不变（老契约回归）。
 */
class GraphRootTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun tmpRoot(): Path =
        Files.createTempDirectory("ozml-graph-root").also { it.toFile().deleteOnExit() }

    /** 造一个最小可用的图库：真 SQL → LineageBuilder → SQLite（与 CLI --graph 落库同路）。 */
    private fun seedDb(dir: Path, name: String): Path {
        val path = dir.resolve(name)
        val statements = when (val r = JSqlParserEngine().analyzeAll(SQL, ParseRequest())) {
            is Resolved.Known -> r.value
            is Resolved.Unknown -> error(r.reason)
        }
        val models: List<LineageModel> = statements.map { LineageBuilder.build(it, null) }
        SqliteLineageStore.open(path).use { store ->
            store.save(models.map { StoredModel(model = it, sourceFile = "q.sql", statementIndex = 0) })
        }
        return path
    }

    private fun request(path: String, body: String?, graphRoot: Path? = null): Pair<Int, String> =
        runBlocking {
            var status = -1
            var text = ""
            testApplication {
                application { serverModule(graphRoot = GraphRoot.ofOrNull(graphRoot)) }
                val response = if (body == null) {
                    client.get(path)
                } else {
                    client.post(path) { contentType(ContentType.Application.Json); setBody(body) }
                }
                status = response.status.value
                text = response.bodyAsText()
            }
            status to text
        }

    private fun parse(text: String) = json.parseToJsonElement(text).jsonObject

    private fun errorCode(text: String): String =
        parse(text).getValue("error").jsonPrimitive.content

    /** 现建图取全图节点 id（挑真实列 id 喂给 graph 路径）。 */
    private fun nodeIds(): List<String> {
        // 与 seedDb 用同一条 SQL，避免两处漂移。
        val body = "{\"sql\": \"" + SQL + "\", \"aggregate\": true}"
        val (status, text) = request("/api/lineage", body)
        assertEquals(200, status, text)
        return parse(text).getValue("nodes").jsonArray.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
        }
    }

    // —— 纯函数：白名单根解析（安全面的最小单元） ——

    @Test
    fun `白名单根只接受目录内的纯 db 文件名`() {
        val root = GraphRoot.ofOrNull(tmpRoot())!!

        assertTrue(root.resolve("reports.db") != null)
        assertNull(root.resolve("/etc/passwd.db"), "绝对路径必须被拒")
        assertNull(root.resolve("../evil.db"), "上级逃逸必须被拒")
        assertNull(root.resolve("sub/other.db"), "子路径必须被拒")
        assertNull(root.resolve("reports.sqlite"), "非 .db 后缀必须被拒")
        assertNull(root.resolve(""), "空串必须被拒")
    }

    @Test
    fun `未配置 graphRoot 时 ofOrNull 返回 null`() {
        assertNull(GraphRoot.ofOrNull(null), "不给 --graph-root = 图库能力整体关闭")
    }

    @Test
    fun `list 只列 db 文件且按名排序`() {
        val dir = tmpRoot()
        seedDb(dir, "b.db")
        seedDb(dir, "a.db")
        Files.writeString(dir.resolve("notes.txt"), "x")

        val names = GraphRoot.ofOrNull(dir)!!.list().map { it.fileName.toString() }
        assertEquals(listOf("a.db", "b.db"), names)
    }

    // —— 端点：/api/graphs ——

    @Test
    fun `graphs 未配置白名单根时给 unsupported_capability`() {
        val (status, text) = request("/api/graphs", "{}", graphRoot = null)

        assertEquals(400, status)
        assertEquals("unsupported_capability", errorCode(text))
    }

    @Test
    fun `graphs 列出白名单根下的库与语句数`() {
        val dir = tmpRoot()
        seedDb(dir, "reports.db")
        val (status, text) = request("/api/graphs", "{}", graphRoot = dir)

        assertEquals(200, status)
        val graphs = parse(text).getValue("graphs").jsonArray
        assertEquals(1, graphs.size)
        assertEquals("reports.db", graphs[0].jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(1, graphs[0].jsonObject.getValue("modelCount").jsonPrimitive.content.toInt())
    }

    // —— 端点：graph 字段（与 sql 二选一） ——

    @Test
    fun `impact 用 graph 字段读已落库的图`() {
        val dir = tmpRoot()
        seedDb(dir, "reports.db")
        // SELECT a FROM t_src → 节点 t_src.a（来源列）/ a（输出列）/ t_src（表哨兵）。
        val origin = nodeIds().first { it.startsWith("t_src.") }

        val (status, text) = request("/api/impact", "{\"graph\": \"reports.db\", \"on\": \"$origin\"}", graphRoot = dir)

        assertEquals(200, status, text)
        assertEquals(origin, parse(text).getValue("origin").jsonPrimitive.content)
    }

    @Test
    fun `impact 未配置 graphRoot 时用 graph 给 unsupported_capability`() {
        val (status, text) = request("/api/impact", "{\"graph\": \"reports.db\", \"on\": \"t.c\"}", graphRoot = null)

        assertEquals(400, status)
        assertEquals("unsupported_capability", errorCode(text))
    }

    @Test
    fun `impact graph 名非法给 invalid_graph_ref`() {
        val dir = tmpRoot()
        for (bad in listOf("/etc/passwd.db", "../evil.db", "sub/other.db", "notes.txt")) {
            val (status, text) = request("/api/impact", "{\"graph\": \"$bad\", \"on\": \"t.c\"}", graphRoot = dir)
            assertEquals(400, status, "应拒绝：" + bad)
            assertEquals("invalid_graph_ref", errorCode(text), "应拒绝：" + bad)
        }
    }

    @Test
    fun `impact graph 文件不存在给 graph_not_found`() {
        val dir = tmpRoot()
        val (status, text) = request("/api/impact", "{\"graph\": \"missing.db\", \"on\": \"t.c\"}", graphRoot = dir)

        assertEquals(422, status, text)
        assertEquals("graph_not_found", errorCode(text))
    }

    @Test
    fun `impact sql 与 graph 都给或都不给给 invalid_request`() {
        val dir = tmpRoot()
        seedDb(dir, "reports.db")

        val both = request("/api/impact", "{\"sql\": \"SELECT a FROM s\", \"graph\": \"reports.db\", \"on\": \"s.a\"}", graphRoot = dir)
        assertEquals(400, both.first, both.second)
        assertEquals("invalid_request", errorCode(both.second))

        val neither = request("/api/impact", "{\"on\": \"s.a\"}", graphRoot = dir)
        assertEquals(400, neither.first, neither.second)
        assertEquals("invalid_request", errorCode(neither.second))
    }

    @Test
    fun `path 用 graph 字段查短路径与 impact 同构`() {
        val dir = tmpRoot()
        seedDb(dir, "reports.db")
        val ids = nodeIds()
        val from = ids.first { it.startsWith("t_src.") }
        // 输出列：非表哨兵、非来源列（SELECT a FROM t_src → a）。
        val to = ids.first { it != "t_src" && !it.startsWith("t_src.") }

        val (status, text) = request("/api/path", "{\"graph\": \"reports.db\", \"from\": \"$from\", \"to\": \"$to\", \"direction\": \"downstream\"}", graphRoot = dir)

        assertEquals(200, status, text)
        assertEquals(from, parse(text).getValue("from").jsonPrimitive.content)
    }

    @Test
    fun `sql 路径行为不变（老契约回归）`() {
        // 不给 graphRoot、只给 sql：与改动前完全一致，必须照常 200。
        val (status, text) = request("/api/impact", "{\"sql\": \"SELECT a FROM s\", \"on\": \"s.a\"}", graphRoot = null)

        assertEquals(200, status, text)
        assertEquals("s.a", parse(text).getValue("origin").jsonPrimitive.content)
    }


    // —— 端点：/api/lineage 的 graph 模式（整库合并图，UI 浏览的数据出口） ——

    @Test
    fun `lineage 用 graph 字段回整库合并图`() {
        val dir = tmpRoot()
        seedDb(dir, "reports.db")

        val (status, text) = request(
            "/api/lineage",
            "{\"graph\": \"reports.db\"}",
            graphRoot = dir,
        )

        assertEquals(200, status, text)
        // 与 aggregate=true 同形：{nodes, edges}，且边真的带出来（可渲染）。
        val body = parse(text)
        val nodes = body.getValue("nodes").jsonArray
        val edges = body.getValue("edges").jsonArray
        assertTrue(nodes.isNotEmpty(), "整库图应有节点：" + text)
        assertTrue(edges.isNotEmpty(), "整库图应有边：" + text)
    }

    @Test
    fun `lineage graph 与 sql 都给给 invalid_request`() {
        val dir = tmpRoot()
        seedDb(dir, "reports.db")
        val (status, text) = request(
            "/api/lineage",
            "{\"sql\": \"SELECT a FROM t_src\", \"graph\": \"reports.db\"}",
            graphRoot = dir,
        )

        assertEquals(400, status, text)
        assertEquals("invalid_request", errorCode(text))
    }

    @Test
    fun `lineage 未配置 graphRoot 时用 graph 给 unsupported_capability`() {
        val (status, text) = request("/api/lineage", "{\"graph\": \"reports.db\"}", graphRoot = null)

        assertEquals(400, status)
        assertEquals("unsupported_capability", errorCode(text))
    }

    @Test
    fun `lineage graph 与非 json format 互斥`() {
        val dir = tmpRoot()
        seedDb(dir, "reports.db")
        val (status, text) = request(
            "/api/lineage",
            "{\"graph\": \"reports.db\", \"format\": \"mermaid\"}",
            graphRoot = dir,
        )

        assertEquals(400, status, text)
        assertEquals("aggregate_with_format", errorCode(text))
    }

    @Test
    fun `lineage 只给 sql 时老契约不变`() {
        val (status, text) = request("/api/lineage", "{\"sql\": \"SELECT a FROM t_src\"}", graphRoot = null)

        assertEquals(200, status, text)
        // 单语句仍回 LineageModel 本体（有 scopes / columns / edges），不是合并图。
        assertTrue(parse(text).containsKey("scopes"), text)
    }

    @Test
    fun `lineage 缺 sql 与 graph 给 invalid_request`() {
        val (status, text) = request("/api/lineage", "{\"engine\": \"jsqlparser\"}", graphRoot = null)

        assertEquals(400, status)
        assertEquals("invalid_request", errorCode(text))
    }


    // —— 回归：sql 路径的既有行为（本轮重构差点丢掉，用测试锁住） ——

    @Test
    fun `impact sql 路径的未知 engine 仍给 unknown_engine`() {
        val (status, text) = request(
            "/api/impact",
            "{\"sql\": \"SELECT a FROM s\", \"engine\": \"mine\", \"on\": \"s.a\"}",
            graphRoot = null,
        )

        assertEquals(400, status, text)
        assertEquals("unknown_engine", errorCode(text))
    }

    @Test
    fun `path sql 路径的未知 engine 仍给 unknown_engine`() {
        val (status, text) = request(
            "/api/path",
            "{\"sql\": \"SELECT a FROM s\", \"engine\": \"mine\", \"from\": \"s.a\", \"to\": \"s.a\"}",
            graphRoot = null,
        )

        assertEquals(400, status, text)
        assertEquals("unknown_engine", errorCode(text))
    }

    @Test
    fun `impact graph 模式忽略 engine 且不因未知 engine 报错`() {
        // graph 模式读的是已定型的库模型，engine / dialect / schema 均不参与（ADR-0015）：
        // 传一个未注册 engine 也不该 400——否则前端要按来源做条件分支。
        val dir = tmpRoot()
        seedDb(dir, "reports.db")
        val origin = nodeIds().first { it.startsWith("t_src.") }
        val body = "{\"graph\": \"reports.db\", \"engine\": \"mine\", \"on\": \"$origin\"}"

        val (status, text) = request("/api/impact", body, graphRoot = dir)

        assertEquals(200, status, text)
    }

    companion object {
        private const val SQL = "SELECT a FROM t_src"
    }
}
