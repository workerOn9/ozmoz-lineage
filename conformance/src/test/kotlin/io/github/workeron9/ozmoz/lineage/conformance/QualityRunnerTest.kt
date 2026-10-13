package io.github.workeron9.ozmoz.lineage.conformance

import io.github.workeron9.ozmoz.lineage.engine.EngineCapabilities
import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.ParseOutcome
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.semantics.OutputItem
import io.github.workeron9.ozmoz.lineage.engine.semantics.ScopeSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.engine.semantics.TableSource
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 质量报表生成器：precision / recall / unknown 率 / 静默漏的**口径**全部钉在这里。
 *
 * 用例都用桩引擎（只依赖 `engine-api` SPI）+ 手写语义模型——测的是**度量的数学与口径**，
 * 不是引擎能力；真引擎的实测数字由 `ozml quality` 在语料上产出。
 */
class QualityRunnerTest {

    /** 桩：一条 `SELECT a FROM t`（产出 1 条 OUTPUT + 1 条 SOURCE）。 */
    private object SelectEngine : SqlEngine {
        override val id = "select"
        override val capabilities = EngineCapabilities.of(Feature.PARSE, Feature.SEMANTIC_MODEL)
        override fun parse(sql: String, request: ParseRequest): ParseOutcome =
            ParseOutcome(root = AstNode(type = "script", text = sql))

        override fun analyze(sql: String, request: ParseRequest): Resolved<SemanticStatement> = Resolved.Known(selectAFromT())
    }

    private val outputEdge = ExpectedEdge(from = "t.a", to = "a", kind = EdgeKind.OUTPUT)
    private val sourceEdge = ExpectedEdge(from = "t", to = "a", kind = EdgeKind.SOURCE)

    @Test
    fun `完全命中给出 P=1 R=1 且零缺口`() {
        val report = run(
            entry("hit", LineageExpectation(edges = listOf(outputEdge, sourceEdge))),
        )
        val engine = report.engines.single()
        assertEquals(1.0, engine.precision)
        assertEquals(1.0, engine.recall)
        assertEquals(2, engine.truePositives)
        assertEquals(0, engine.falsePositives)
        assertEquals(0, engine.falseNegatives)
        assertEquals(0, engine.unexplainedGaps)
    }

    @Test
    fun `期望缺一条且没声明 unknown 记 FN 与静默漏`() {
        val report = run(
            entry("gap", LineageExpectation(edges = listOf(outputEdge, sourceEdge, ExpectedEdge("t.b", "b", EdgeKind.OUTPUT)))),
        )
        val engine = report.engines.single()
        assertEquals(2, engine.truePositives)
        assertEquals(1, engine.falseNegatives)
        assertEquals(1, engine.unexplainedGaps) // 「缺口全部落在 unknown」的红线：这条是静默漏
        assertEquals(2.0 / 3.0, engine.recall)
        assertEquals(1.0, engine.precision)
    }

    @Test
    fun `缺口声明为 unknown 时不算静默漏`() {
        val report = run(
            entry(
                "explained",
                LineageExpectation(
                    edges = listOf(outputEdge, sourceEdge, ExpectedEdge("t.b", "b", EdgeKind.OUTPUT)),
                    unknowns = listOf("t.b"),
                ),
            ),
        )
        assertEquals(1, report.engines.single().falseNegatives)
        assertEquals(0, report.engines.single().unexplainedGaps)
    }

    @Test
    fun `未断言的 kind 不进精确率分母`() {
        // 期望只断言 OUTPUT：产出里的 SOURCE 是「没断言」而不是「判错」。
        val report = run(entry("scoped", LineageExpectation(edges = listOf(outputEdge))))
        val engine = report.engines.single()
        assertEquals(1, engine.truePositives)
        assertEquals(0, engine.falsePositives)
        assertEquals(1.0, engine.precision)
        // 但 SOURCE 边确实被产出了——它不在断言范围内，所以既不 TP 也不 FP。
        assertEquals(0, engine.falseNegatives)
    }

    @Test
    fun `凭空边拉低精确率`() {
        // 引擎产出 `a` 与 `b` 两条 OUTPUT，但期望只断言 `a` → `b` 是多产的 FP。
        val twoOutputs = stubOf("two", statement(outputs = listOf("a", "b")))
        val report = QualityRunner(commit = "c").run(
            listOf(
                CorpusEntry(
                    CorpusCase(
                        "extra",
                        "ansi",
                        sql = "SELECT a, b FROM t",
                        lineage = LineageExpectation(edges = listOf(outputEdge)),
                    ),
                    "ansi/extra.json",
                ),
            ),
            listOf(twoOutputs),
            "corpus/",
        )
        assertEquals(1, report.engines.single().falsePositives)
        assertEquals(0.5, report.engines.single().precision)
    }

    @Test
    fun `空期望断言任何 kind 都不该有边`() {
        val report = run(entry("delete-like", LineageExpectation()))
        val engine = report.engines.single()
        // 引擎产出了 OUTPUT + SOURCE，而期望是「零边、任何 kind」→ 全部记 FP。
        assertEquals(2, engine.falsePositives)
        assertEquals(0.0, engine.precision)
    }

    @Test
    fun `未标注的 case 只进解析率与 unknown 率不进 P R`() {
        val report = run(entry("plain", null))
        val engine = report.engines.single()
        assertEquals(0, report.meta.annotatedCount)
        assertEquals(1, engine.parseOk)
        assertEquals(1, engine.parseTotal)
        assertNull(engine.precision)
        assertNull(engine.recall)
        // unknown 率分母是「列引用总数」：`SELECT a FROM t` 有 1 个列引用、0 个 unknown。
        assertEquals(1, engine.columnRefs)
        assertEquals(0, engine.unknownDiagnostics)
        assertEquals(0.0, engine.unknownRate)
        // 明细收口：已标注的 case + 解析失败的 case；这条又没标注又成功 → 不在明细里。
        assertTrue(report.cases.isEmpty())
    }

    @Test
    fun `坏 DDL 的 case 记为失败并带原因`() {
        val report = QualityRunner(commit = "c").run(
            listOf(
                CorpusEntry(
                    CorpusCase("bad-ddl", "ansi", sql = "SELECT a FROM t", schema = "THIS IS NOT DDL"),
                    "ansi/bad-ddl.json",
                ),
            ),
            listOf(SelectEngine),
            "corpus/",
        )
        val engine = report.engines.single()
        assertEquals(0, engine.parseOk)
        assertEquals(1, engine.parseTotal)
        // 解析失败的 case 即使没标注也进明细——失败原因本身是报表内容。
        assertEquals(1, report.cases.size)
        assertTrue(report.cases.single().reason!!.startsWith("schema 解析失败"))
    }

    @Test
    fun `解析成功率按方言分别公布不合并平均`() {
        val report = QualityRunner(commit = "c").run(
            listOf(
                entry("a1", null).copy(case = CorpusCase("a1", "ansi", sql = "SELECT a FROM t")),
                entry("b1", null).copy(case = CorpusCase("b1", "hive", sql = "SELECT a FROM t")),
            ),
            listOf(FailOnHiveEngine),
            "corpus/",
        )
        val byDialect = report.byDialect.associateBy { it.dialect }
        assertEquals(1, byDialect.getValue("ansi").engines.single().ok)
        assertEquals(0, byDialect.getValue("hive").engines.single().ok)
        assertEquals(1, byDialect.getValue("hive").engines.single().total)
        assertEquals(1, report.engines.single().parseOk)
        assertEquals(2, report.engines.single().parseTotal)
        assertNotNull(report.engines.single().parseTotal)
    }

    // ——— 桩与构造 ———

    private fun run(entry: CorpusEntry): QualityReport =
        QualityRunner(commit = "c").run(listOf(entry), listOf(SelectEngine), "corpus/")

    private fun entry(id: String, expectation: LineageExpectation?): CorpusEntry =
        CorpusEntry(
            CorpusCase(id = id, dialect = "ansi", sql = "SELECT a FROM t", lineage = expectation),
            "ansi/$id.json",
        )

    private fun stubOf(id: String, statement: SemanticStatement): SqlEngine = object : SqlEngine {
        override val id: String = id
        override val capabilities = EngineCapabilities.of(Feature.PARSE, Feature.SEMANTIC_MODEL)
        override fun parse(sql: String, request: ParseRequest): ParseOutcome =
            ParseOutcome(root = AstNode(type = "script", text = sql))

        override fun analyze(sql: String, request: ParseRequest): Resolved<SemanticStatement> = Resolved.Known(statement)
    }

    private object FailOnHiveEngine : SqlEngine {
        override val id = "hive-fail"
        override val capabilities = EngineCapabilities.of(Feature.PARSE, Feature.SEMANTIC_MODEL)
        override fun parse(sql: String, request: ParseRequest): ParseOutcome =
            ParseOutcome(root = AstNode(type = "script", text = sql))

        override fun analyze(sql: String, request: ParseRequest): Resolved<SemanticStatement> =
            if (request.dialect == "hive") Resolved.Unknown("hive 不吃") else Resolved.Known(selectAFromT())
    }
}

/** 「列引用总数」计数器：unknown 率的分母口径（`*` 计 1，目标列清单不计）。 */
class SemanticRefCounterTest {

    @Test
    fun `输出与过滤与星号都计一`() {
        val plain = statement(outputs = listOf("a", "b"))
        assertEquals(2, SemanticRefCounter.count(plain))

        // WHERE 里的列引用 + 输出列 + `*`
        val withFilter = SemanticStatement(
            kind = StatementKind.SELECT,
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = ScopeKind.SELECT,
                    sources = listOf(TableSource(TableRef(raw = "t", canonical = "t", name = "t"))),
                    filters = listOf(
                        SqlExpr.BinaryOp(
                            op = ">",
                            left = SqlExpr.Column(ColumnRef(raw = "age", canonical = "age", name = "age", table = "t")),
                            right = SqlExpr.Literal(raw = "1", span = null),
                            raw = "age > 1",
                            span = null,
                        ),
                    ),
                    outputs = listOf(OutputItem(expr = SqlExpr.Star(qualifier = null, raw = "*", span = null))),
                ),
                raw = "SELECT * FROM t WHERE age > 1",
            ),
        )
        // 1 个过滤列引用 + 1 个 `*` = 2
        assertEquals(2, SemanticRefCounter.count(withFilter))
    }

    @Test
    fun `目标列清单不计入引用`() {
        val insert = SemanticStatement(
            kind = StatementKind.INSERT,
            target = TableRef(raw = "dst", canonical = "dst", name = "dst"),
            targetColumns = listOf(ColumnRef(raw = "x", canonical = "x", name = "x")),
            query = SelectQuery(
                scope = ScopeSpec(
                    kind = ScopeKind.INSERT,
                    sources = listOf(TableSource(TableRef(raw = "src", canonical = "src", name = "src"))),
                    outputs = listOf(
                        OutputItem(expr = SqlExpr.Column(ColumnRef(raw = "a", canonical = "a", name = "a", table = "src"))),
                    ),
                ),
                raw = "INSERT INTO dst (x) SELECT a FROM src",
            ),
        )
        assertEquals(1, SemanticRefCounter.count(insert))
    }
}

/** 语义模型桩：`SELECT <列…> FROM t`（物理表 t，裸列）。 */
private fun statement(outputs: List<String> = listOf("a")): SemanticStatement = SemanticStatement(
    kind = StatementKind.SELECT,
    query = SelectQuery(
        scope = ScopeSpec(
            kind = ScopeKind.SELECT,
            sources = listOf(TableSource(TableRef(raw = "t", canonical = "t", name = "t"))),
            outputs = outputs.map { column ->
                OutputItem(expr = SqlExpr.Column(ColumnRef(raw = column, canonical = column, name = column, table = "t")))
            },
        ),
        raw = "SELECT ${outputs.joinToString(", ")} FROM t",
    ),
)

private fun selectAFromT(): SemanticStatement = statement()
