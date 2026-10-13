package io.github.workeron9.ozmoz.lineage.conformance

import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.lineage.LineageBuilder
import io.github.workeron9.ozmoz.lineage.schema.DdlFileSchemaProvider
import java.time.Instant

/**
 * 血缘质量报表生成器（计划 §8；M1 收尾）。
 *
 * 与 `MatrixRunner`（兼容性矩阵）的分工：矩阵回答「**吃不吃得下**」，本报表回答「**算得对不对**」。
 * 口径见 [QualityReport]；判定细节：
 *
 * - **解析失败不计 precision/recall**，只计「解析成功率」——把「吃不下」与「算错」混在一起会失真；
 * - **precision 的产出侧只取该 case 断言过的 kind**（[LineageExpectation] 的口径），
 *   未断言的 kind 既不进 TP 也不进 FP；
 * - **对拍单位是 `(from, to, kind)` 三元组**，名字折叠小写后比较（`qualifiedName` 写法；
 *   与 `ozml lineage --format edges` 的输出一致）；
 * - case 带 `schema`（DDL）时喂给 [LineageBuilder]——物理表 `*` 展开、裸列消歧才有意义；
 *   坏 DDL 视为**语料损坏**（该 case 记为失败并带原因，不静默跳过）。
 *
 * @param commit 仓库 commit（可溯源），由调用方传入；CI 未填时为 null。
 */
public class QualityRunner(
    private val commit: String? = null,
) {

    public fun run(entries: List<CorpusEntry>, engines: List<SqlEngine>, corpusRoot: String): QualityReport {
        val evaluated = ArrayList<CaseEval>()
        for (entry in entries.sortedBy { it.path }) {
            for (engine in engines) {
                evaluated += evaluate(engine, entry)
            }
        }
        val annotated = entries.count { it.case.lineage != null }
        return QualityReport(
            meta = QualityMeta(
                generatedAt = Instant.now().toString(),
                commit = commit,
                corpusRoot = corpusRoot,
                caseCount = entries.size,
                annotatedCount = annotated,
                engineCount = engines.size,
            ),
            engines = engines.map { engine -> engineQuality(engine, evaluated) },
            byDialect = byDialect(entries, engines, evaluated),
            byFeature = byFeature(entries, engines, evaluated),
            cases = evaluated
                // 明细收口：**已标注**的 case（对拍明细）+ **解析失败**的 case（失败原因也是报表内容）。
                .filter { it.entry.case.lineage != null || !it.ok }
                .sortedWith(compareBy({ it.entry.case.dialect }, { it.entry.case.id }, { it.engine }))
                .map { it.toCaseQuality() },
        )
    }

    // ——— 单 case × 单引擎 ———

    private fun evaluate(engine: SqlEngine, entry: CorpusEntry): CaseEval {
        val case = entry.case
        val schema = if (case.schema == null) {
            null
        } else {
            try {
                DdlFileSchemaProvider.parse(id = "corpus:${case.id}", case.schema)
            } catch (e: Exception) {
                return CaseEval(entry, engine.id, ok = false, reason = truncate("schema 解析失败: ${e.message}"))
            }
        }

        val statements = try {
            when (val analyzed = engine.analyzeAll(case.sql, ParseRequest(dialect = case.dialect))) {
                is Resolved.Known -> analyzed.value
                is Resolved.Unknown -> return CaseEval(entry, engine.id, ok = false, reason = truncate(analyzed.reason))
            }
        } catch (e: Exception) {
            return CaseEval(entry, engine.id, ok = false, reason = truncate("${e.javaClass.simpleName}: ${e.message}"))
        }

        val models: List<LineageModel> = try {
            statements.map { LineageBuilder.build(it, schema) }
        } catch (e: Exception) {
            return CaseEval(entry, engine.id, ok = false, reason = truncate("建模失败: ${e.message}"))
        }

        val edges = models.flatMap { it.edges }
        val unknownDiagnostics = models.sumOf { it.unknowns.size }
        val columnRefs = statements.sumOf { SemanticRefCounter.count(it) }

        val expectation = case.lineage
            ?: return CaseEval(
                entry = entry,
                engine = engine.id,
                ok = true,
                unknownDiagnostics = unknownDiagnostics,
                columnRefs = columnRefs,
            )

        val expected = expectation.edges.map { Triple(fold(it.from), fold(it.to), it.kind) }.toSet()
        // 断言范围：空期望 = 「**任何 kind 都不该有边**」（如 `DELETE` 无输出），
        // 此时产出侧取全部 kind，才能把凭空边抓成 FP；非空期望则只对拍断言过的 kind。
        val assertedKinds: Set<EdgeKind> =
            if (expected.isEmpty()) EdgeKind.entries.toSet() else expected.map { it.third }.toSet()
        val emitted = edges
            .filter { it.kind in assertedKinds }
            .map { Triple(fold(it.fromColumn.qualifiedName), fold(it.toColumn.qualifiedName), it.kind) }
            .toSet()

        val missing = (expected - emitted).sortedBy { render(it) }
        val unexpected = (emitted - expected).sortedBy { render(it) }
        val declaredUnknown = expectation.unknowns.map { fold(it) }.toSet()

        return CaseEval(
            entry = entry,
            engine = engine.id,
            ok = true,
            truePositives = (expected intersect emitted).size,
            falsePositives = unexpected.size,
            falseNegatives = missing.size,
            unknownDiagnostics = unknownDiagnostics,
            columnRefs = columnRefs,
            missing = missing.map { render(it) },
            unexpected = unexpected.map { render(it) },
            unexplainedGaps = missing.count { it.first !in declaredUnknown },
        )
    }

    // ——— 聚合 ———

    private fun engineQuality(engine: SqlEngine, evaluated: List<CaseEval>): EngineQuality {
        val mine = evaluated.filter { it.engine == engine.id }
        val annotated = mine.filter { it.entry.case.lineage != null }
        val tp = annotated.sumOf { it.truePositives }
        val fp = annotated.sumOf { it.falsePositives }
        val fn = annotated.sumOf { it.falseNegatives }
        val unknownDiagnostics = mine.sumOf { it.unknownDiagnostics }
        val columnRefs = mine.sumOf { it.columnRefs }
        return EngineQuality(
            engine = engine.id,
            parseOk = mine.count { it.ok },
            parseTotal = mine.size,
            precision = ratio(tp, tp + fp),
            recall = ratio(tp, tp + fn),
            truePositives = tp,
            falsePositives = fp,
            falseNegatives = fn,
            unexplainedGaps = annotated.sumOf { it.unexplainedGaps },
            unknownDiagnostics = unknownDiagnostics,
            columnRefs = columnRefs,
            unknownRate = ratio(unknownDiagnostics, columnRefs),
        )
    }

    private fun byDialect(entries: List<CorpusEntry>, engines: List<SqlEngine>, evaluated: List<CaseEval>): List<DialectQuality> =
        entries.map { it.case.dialect }.distinct().sorted().map { dialect ->
            DialectQuality(
                dialect = dialect,
                engines = engines.map { engine ->
                    val scoped = evaluated.filter { it.engine == engine.id && it.entry.case.dialect == dialect }
                    EngineParseRate(engine = engine.id, ok = scoped.count { it.ok }, total = scoped.size)
                },
            )
        }

    private fun byFeature(entries: List<CorpusEntry>, engines: List<SqlEngine>, evaluated: List<CaseEval>): List<FeatureQuality> {
        val features = entries.filter { it.case.lineage != null }.flatMap { it.case.features }.distinct().sorted()
        return features.map { feature ->
            FeatureQuality(
                feature = feature,
                engines = engines.map { engine ->
                    val scoped = evaluated.filter {
                        it.engine == engine.id && it.entry.case.lineage != null && feature in it.entry.case.features
                    }
                    val tp = scoped.sumOf { it.truePositives }
                    val fp = scoped.sumOf { it.falsePositives }
                    val fn = scoped.sumOf { it.falseNegatives }
                    EngineQualitySlice(
                        engine = engine.id,
                        truePositives = tp,
                        falsePositives = fp,
                        falseNegatives = fn,
                        precision = ratio(tp, tp + fp),
                        recall = ratio(tp, tp + fn),
                    )
                },
            )
        }
    }

    // ——— 小工具 ———

    /** 名字折叠：与仓库既有的列名折叠口径一致（小写 + 去首尾空白）。 */
    private fun fold(name: String): String = name.trim().lowercase()

    private fun render(triple: Triple<String, String, EdgeKind>): String =
        "${triple.first} -> ${triple.second} [${triple.third}]"

    /** 分母为 0 时返回 null——**不编造 0%**（无样本 ≠ 100%）。 */
    private fun ratio(numerator: Int, denominator: Int): Double? =
        if (denominator <= 0) null else numerator.toDouble() / denominator.toDouble()

    /** 失败原因截**前 40 字**（与矩阵同口径）。 */
    private fun truncate(reason: String?): String? {
        if (reason.isNullOrBlank()) return null
        val line = reason.replace('\n', ' ').trim()
        return if (line.length <= 40) line else line.take(40) + "…"
    }
}

/** 单 case × 单引擎的评估结果（聚合前的素材）。 */
private data class CaseEval(
    val entry: CorpusEntry,
    val engine: String,
    val ok: Boolean,
    val truePositives: Int = 0,
    val falsePositives: Int = 0,
    val falseNegatives: Int = 0,
    val unexplainedGaps: Int = 0,
    val unknownDiagnostics: Int = 0,
    val columnRefs: Int = 0,
    val missing: List<String> = emptyList(),
    val unexpected: List<String> = emptyList(),
    val reason: String? = null,
) {
    fun toCaseQuality(): CaseQuality = CaseQuality(
        caseId = entry.case.id,
        sourcePath = entry.path,
        dialect = entry.case.dialect,
        engine = engine,
        ok = ok,
        truePositives = truePositives,
        falsePositives = falsePositives,
        falseNegatives = falseNegatives,
        unknownDiagnostics = unknownDiagnostics,
        columnRefs = columnRefs,
        missing = missing,
        unexpected = unexpected,
        reason = reason,
    )
}
