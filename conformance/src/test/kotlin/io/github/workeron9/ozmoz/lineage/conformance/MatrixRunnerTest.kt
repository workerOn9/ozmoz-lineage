package io.github.workeron9.ozmoz.lineage.conformance

import io.github.workeron9.ozmoz.lineage.engine.EngineCapabilities
import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.ParseOutcome
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.ScopeSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 矩阵生成器：ok / fail / unknown 分开说话，与失败原因 40 字截断。 */
class MatrixRunnerTest {

    private val cases = listOf(
        entry("ok-ansi", "ansi", "SELECT 1 FROM t"),
        entry("ok-mysql", "mysql", "SELECT a FROM t"),
        entry("unknown-san", "mysql", "SELECT '@@version' FROM t"),
        entry("long-reason", "hive", "SELECT explode(x) FROM t"),
    )

    @Test
    fun `Known 记 ok 与 sql unknown 数 Unknown 记失败原因`() {
        val matrix = MatrixRunner(commit = "abc123").run(cases, listOf(SuccessEngine, FailEngine), "corpus/")

        assertEquals(8, matrix.cells.size) // 4 case × 2 引擎
        assertEquals("abc123", matrix.meta.commit)
        assertEquals(4, matrix.meta.caseCount)
        assertEquals(2, matrix.meta.engineCount)

        // SuccessEngine：Known 两条；Unknown 两条（方言哨兵 case 如实记失败）。
        val success = matrix.cells.filter { it.engine == SuccessEngine.id }.associateBy { it.caseId }
        assertTrue(success.getValue("ok-ansi").ok)
        assertEquals(1, success.getValue("ok-ansi").statements)
        assertNull(success.getValue("ok-ansi").reason)
        assertTrue(!success.getValue("unknown-san").ok)
        assertEquals("语义不可用: 方言不支持", success.getValue("unknown-san").reason)
        // 失败原因超 40 字 → 截断 + 省略号（计划 §4.4：每格附「前 40 字」诊断）。
        val trimmed = success.getValue("long-reason").reason!!
        assertEquals(41, trimmed.length)
        assertTrue(trimmed.endsWith("…"))

        // FailEngine：全部失败，原因一致。
        val fail = matrix.cells.filter { it.engine == FailEngine.id }
        assertTrue(fail.all { !it.ok })
        assertEquals("语义不可用: 方言不支持", fail.single { it.caseId == "ok-ansi" }.reason)
    }

    @Test
    fun `Failed with exception lands as failure cell`() {
        val matrix = MatrixRunner().run(listOf(entry("boom", "ansi", "SELECT 1")), listOf(ThrowingEngine), "corpus/")

        val cell = matrix.cells.single()
        assertTrue(!cell.ok)
        assertTrue(cell.reason!!.contains("RuntimeException"))
    }

    @Test
    fun `覆盖计数按方言聚合 引擎列序跟注册顺序`() {
        val matrix = MatrixRunner().run(cases, listOf(SuccessEngine, FailEngine), "corpus/")

        val mysql = matrix.coverage.getValue("mysql")
        assertEquals(listOf(SuccessEngine.id, FailEngine.id), mysql.keys.toList())
        assertEquals(Coverage(1, 2), mysql.getValue(SuccessEngine.id)) // unknown-san 失败
        assertEquals(Coverage(0, 2), mysql.getValue(FailEngine.id))
    }

    @Test
    fun `矩阵 JSON 序列化往返与字段稳定性`() {
        val matrix = MatrixRunner(commit = "deadbeef").run(cases.take(2), listOf(SuccessEngine), "corpus/")
        val json = CorpusLoader.jsonBuilder()
        val text = json.encodeToString(CompatMatrix.serializer(), matrix)
        val roundTrip = json.decodeFromString(CompatMatrix.serializer(), text)

        assertEquals(matrix, roundTrip)
        assertTrue(text.contains("\"features\""))
        assertIs<MatrixCell>(roundTrip.cells.first())
    }

    /** 临时文件名 = case id，保证 sourcePath 可用于对拍。 */
    /** 临时文件名 = case id，保证 sourcePath 可用于对拍。 */
    private fun entry(id: String, dialect: String, sql: String): CorpusEntry =
        CorpusEntry(CorpusCase(id = id, dialect = dialect, sql = sql), "$dialect/$id.json")

    /** 只回 Known 单语句的桩，`analyzeAll` 不覆写 = 走默认单语句退化路径。 */
    private object SuccessEngine : SqlEngine {
        override val id = "success"
        override val capabilities = EngineCapabilities.of(Feature.PARSE, Feature.SEMANTIC_MODEL)
        override fun parse(sql: String, request: ParseRequest): ParseOutcome =
            ParseOutcome(root = AstNode(type = "script", text = sql))
        override fun analyze(sql: String, request: ParseRequest): Resolved<SemanticStatement> = when {
            sql.contains("@@version") -> Resolved.Unknown("语义不可用: 方言不支持")
            request.dialect == "hive" -> Resolved.Unknown(
                "hive 的 explode / LATERAL VIEW 语义模型在 v1 不支持，这里给一个超过四十个字符的长原因用于截断验证",
            )
            else -> Resolved.Known(emptySemantic("SELECT 1"))
        }
    }

    private object FailEngine : SqlEngine {
        override val id = "fail"
        override val capabilities = EngineCapabilities.of(Feature.PARSE, Feature.SEMANTIC_MODEL)
        override fun parse(sql: String, request: ParseRequest): ParseOutcome =
            ParseOutcome(root = AstNode(type = "script", text = sql))
        override fun analyze(sql: String, request: ParseRequest): Resolved<SemanticStatement> =
            Resolved.Unknown("语义不可用: 方言不支持")
    }

    private object ThrowingEngine : SqlEngine {
        override val id = "throwing"
        override val capabilities = EngineCapabilities.of(Feature.PARSE, Feature.SEMANTIC_MODEL)
        override fun parse(sql: String, request: ParseRequest): ParseOutcome =
            throw RuntimeException("boom")
        override fun analyze(sql: String, request: ParseRequest): Resolved<SemanticStatement> =
            throw RuntimeException("boom")
    }
}

/** 真语义模型桩：`ScopeSpec` + `SelectQuery` 的最小 SELECT（顶层私有，供桩引擎复用）。 */
private fun emptySemantic(sql: String): SemanticStatement = SemanticStatement(
    kind = StatementKind.SELECT,
    query = SelectQuery(
        scope = ScopeSpec(kind = ScopeKind.SELECT),
        raw = sql,
    ),
)
