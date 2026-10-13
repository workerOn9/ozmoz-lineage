package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.github.workeron9.ozmoz.lineage.conformance.CorpusLoader
import io.github.workeron9.ozmoz.lineage.conformance.QualityReport
import io.github.workeron9.ozmoz.lineage.conformance.QualityRunner
import java.nio.file.Files
import kotlin.io.path.Path

/**
 * `ozml quality` —— 产出**血缘质量报表**（计划 §8；M1 收尾）。
 *
 * 与 `ozml matrix` 的分工：矩阵回答「哪个引擎吃得下哪条 SQL」，本命令回答「**算什么、算得对不对**」：
 * - 解析成功率（按方言）、unknown 率（未解析引用 / 列引用总数）：**全语料**统计；
 * - precision / recall：只在语料里标了 `lineage` 期望的 case 上算（覆盖多少条如实公布）；
 * - **静默漏**（期望边缺失且未声明为 unknown）单列——计划 §8「缺口全部落在 unknown」的红线。
 *
 * 用法与 `ozml matrix` 一致：`--corpus` 必填、`--out` 落盘（缺省 stdout）、`--commit` 进 meta。
 * **只报不卡**：阈值（precision ≥0.98 / recall ≥0.85）只展示不设门禁。
 */
public class QualityCommand : CliktCommand(name = "quality") {

    private val corpusPath: String by option(
        "--corpus",
        help = "语料目录（递归取 *.json，一个 case 一个文件；示例：./conformance/corpus）",
    )
        .required()

    private val outPath: String? by option(
        "--out",
        help = "报表 JSON 输出文件（如 ./quality-report.json）；不给则输出到标准输出",
    )

    private val commit: String? by option(
        "--commit",
        help = "仓库 commit sha（可溯源；CI 里应传 `git rev-parse HEAD`）",
    )

    override fun help(context: Context): String =
        "产出血缘质量报表（解析成功率 / precision / recall / unknown 率 + 静默漏计数）的可溯源 JSON。"

    override fun run() {
        val corpus = CorpusLoader.load(Path(corpusPath))
        if (corpus.hasProblems()) {
            if (corpus.entries.isEmpty()) {
                echo("ERROR corrupt_corpus: 语料一条可跑的都没有：", err = true)
                for (problem in corpus.problems) echo("  - $problem", err = true)
                throw ProgramResult(1)
            }
            for (problem in corpus.problems) echo("WARN $problem", err = true)
        }
        if (corpus.entries.isEmpty()) {
            echo("ERROR empty_corpus: 语料目录下没有可跑的 case", err = true)
            throw ProgramResult(1)
        }

        val report = QualityRunner(commit).run(corpus.entries, LineagePipeline.engines(), corpusPath)
        val encoded = CorpusLoader.jsonBuilder().encodeToString(QualityReport.serializer(), report)

        val out = outPath
        if (out == null) {
            echo(encoded)
        } else {
            val target = Path(out)
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, encoded + "\n")
            echo("${summaryLine(report)}  ->  $out")
        }
    }

    /** 人读摘要：语料/标注覆盖 + 每引擎的解析率与 precision/recall/unknown 率。 */
    private fun summaryLine(report: QualityReport): String = buildString {
        append("cases: ").append(report.meta.caseCount)
        append("  annotated: ").append(report.meta.annotatedCount)
        for (engine in report.engines) {
            append("\n  ").append(engine.engine)
            append("  parse: ").append(engine.parseOk).append('/').append(engine.parseTotal)
            append("  P: ").append(percent(engine.precision)).append("  R: ").append(percent(engine.recall))
            append("  unknown: ").append(percent(engine.unknownRate))
            append("  silentGaps: ").append(engine.unexplainedGaps)
        }
    }

    /** 比率 → 百分比（2 位小数）；null（无样本）显示 `n/a`——**不编造 0%**。 */
    private fun percent(value: Double?): String =
        if (value == null) "n/a" else "%.2f%%".format(value * 100)
}
