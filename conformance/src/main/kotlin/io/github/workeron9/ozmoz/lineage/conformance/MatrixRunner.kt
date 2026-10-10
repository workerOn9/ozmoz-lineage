package io.github.workeron9.ozmoz.lineage.conformance

import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.lineage.LineageBuilder
import java.time.Instant

/**
 * 矩阵生成器：把「每条语料 × 每个引擎」跑成 [MatrixCell]。
 *
 * 判定口径（Never-wrong）：
 * - `analyzeAll` 返回 [Resolved.Known] → `ok=true`；**unknown 数照实统计**
 *   （对每条语义模型跑 `LineageBuilder.build` 数 `unknowns`），不因有 unknown 而失败——
 *   「解析得出但给不出列名」与「根本吃不下」是两种结果，矩阵分开说话。
 * - 返回 [Resolved.Unknown] / 抛异常 → `ok=false`，原因截**前 40 字**（计划 §4.4）。
 * - 单 cell 任何异常都吞进 cell 的 `reason`，**不让一条坏语料打断整场运行**
 *   ——矩阵的意义就是把失败摆上台面。
 *
 * @param commit 仓库 commit（可溯源），由调用方传入；CI 未填时为 null。
 */
public class MatrixRunner(
    private val commit: String? = null,
) {

    public fun run(entries: List<CorpusEntry>, engines: List<SqlEngine>, corpusRoot: String): CompatMatrix {
        val cells = ArrayList<MatrixCell>()
        for (entry in entries.sortedBy { it.path }) {
            for (engine in engines) {
                cells += runCell(engine, entry)
            }
        }
        // 排序保证 JSON 逐字节确定（同语料同引擎 ⇒ 同输出）。
        val sorted = cells.sortedWith(compareBy({ it.dialect }, { it.caseId }, { it.engine }, { it.sourcePath }))
        return CompatMatrix(
            meta = MatrixMeta(
                generatedAt = Instant.now().toString(),
                commit = commit,
                corpusRoot = corpusRoot,
                caseCount = entries.size,
                engineCount = engines.size,
            ),
            coverage = coverage(sorted, engines),
            cells = sorted,
        )
    }

    private fun runCell(engine: SqlEngine, entry: CorpusEntry): MatrixCell {
        val facts = MatrixRunnerFacts(
            engine = engine.id,
            caseId = entry.case.id,
            sourcePath = entry.path,
            dialect = entry.case.dialect,
            kind = entry.case.kind,
            features = entry.case.features,
        )
        if (!engine.capabilities.supports(Feature.PARSE)) {
            return facts.toCell(
                ok = false,
                reason = engine.capabilities.reason(Feature.PARSE) ?: "未声明 PARSE 能力",
            )
        }
        return try {
            when (val analyzed = engine.analyzeAll(entry.case.sql, ParseRequest(dialect = entry.case.dialect))) {
                is Resolved.Known -> {
                    var unknowns = 0
                    for (statement in analyzed.value) {
                        unknowns += LineageBuilder.build(statement, null).unknowns.size
                    }
                    facts.toCell(ok = true, statements = analyzed.value.size, unknowns = unknowns)
                }
                is Resolved.Unknown -> facts.toCell(ok = false, reason = truncate(analyzed.reason))
            }
        } catch (e: Exception) {
            facts.toCell(ok = false, reason = truncate("${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    /** 方言 × 引擎 覆盖计数（内层 key 顺序 = 引擎声明顺序，不排序——矩阵列序跟注册顺序走）。 */
    private fun coverage(cells: List<MatrixCell>, engines: List<SqlEngine>): Map<String, Map<String, Coverage>> {
        val dialects = cells.map { it.dialect }.distinct().sorted()
        val result = LinkedHashMap<String, Map<String, Coverage>>()
        for (dialect in dialects) {
            val perEngine = LinkedHashMap<String, Coverage>()
            for (engine in engines) {
                val scoped = cells.filter { it.dialect == dialect && it.engine == engine.id }
                perEngine[engine.id] = Coverage(ok = scoped.count { it.ok }, total = scoped.size)
            }
            result[dialect] = perEngine
        }
        return result
    }

    /** 失败原因截**前 40 字**（计划 §4.4：每格附失败原因前 40 字诊断）。 */
    private fun truncate(reason: String?): String? {
        if (reason.isNullOrBlank()) return null
        val line = reason.replace('\n', ' ').trim()
        return if (line.length <= 40) line else line.take(40) + "…"
    }
}

/** cell 的身份字段（先攒齐再落 cell，避免每条路径重复传参）。 */
private data class MatrixRunnerFacts(
    val engine: String,
    val caseId: String,
    val sourcePath: String,
    val dialect: String,
    val kind: CaseKind,
    val features: List<String>,
) {
    fun toCell(ok: Boolean, statements: Int? = null, unknowns: Int? = null, reason: String? = null) = MatrixCell(
        engine = engine,
        caseId = caseId,
        sourcePath = sourcePath,
        dialect = dialect,
        kind = kind,
        features = features,
        ok = ok,
        statements = statements,
        unknowns = unknowns,
        reason = reason,
    )
}
