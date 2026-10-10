package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.github.workeron9.ozmoz.lineage.conformance.CorpusLoader
import io.github.workeron9.ozmoz.lineage.conformance.MatrixRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * `ozml matrix` —— 产出**引擎 × 语料兼容性矩阵**（计划 §4.4 的杀手级产物）。
 *
 * 管线：[CorpusLoader] 读语料目录（一个 case 一个 JSON）→ [MatrixRunner] 把
 * 「每个引擎 × 每条语料」各跑一遍 `analyzeAll`，成功与否 + unknown 数 + 失败原因
 * （前 40 字）≈ 一格 → `compat-matrix.json`（stdout 或 `--out` 落盘）。
 *
 * 每格可溯源：cell 记录语料文件相对路径；`--commit <sha>` 由调用方 / CI 传入，
 * 落进矩阵 meta（不同 commit 的矩阵可逐格对拍）。
 *
 * 语料损坏（坏 JSON / 重复 id）**不静默**：有可跑语料时按 WARN 继续并逐条列出问题，
 * 一条都跑不了或目录不存在 → 非零退出（error 前缀 `corrupt_corpus` / `empty_corpus`）。
 */
public class MatrixCommand : CliktCommand(name = "matrix") {

    private val corpusPath: String by option(
        "--corpus",
        help = "语料目录（递归取 *.json，一个 case 一个文件；示例：./conformance/corpus）",
    )
        .required()

    private val outPath: String? by option(
        "--out",
        help = "矩阵 JSON 输出文件（如 ./docs/compat-matrix.json）；不给则输出到标准输出",
    )

    private val commit: String? by option(
        "--commit",
        help = "仓库 commit sha（可溯源；CI 里应传 `git rev-parse HEAD`）",
    )

    override fun help(context: Context): String =
        "跑「引擎 × 语料」兼容性矩阵，产出可溯源的 compat-matrix JSON（每格含失败原因前 40 字）。"

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

        val matrix = MatrixRunner(commit).run(corpus.entries, LineagePipeline.engines(), corpusPath)

        val encoded = CorpusLoader.jsonBuilder().encodeToString(
            io.github.workeron9.ozmoz.lineage.conformance.CompatMatrix.serializer(),
            matrix,
        )
        val out = outPath
        if (out == null) {
            echo(encoded)
        } else {
            val target = Path(out)
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, encoded + "\n")
            echo(matrixSummaryLine(matrix) + "  ->  $out")
        }
    }

    /** `--out` 模式下的人读摘要。 */
    private fun matrixSummaryLine(matrix: io.github.workeron9.ozmoz.lineage.conformance.CompatMatrix): String {
        val ok = matrix.cells.count { it.ok }
        return "cases: ${matrix.meta.caseCount}  engines: ${matrix.meta.engineCount}  ok: $ok/${matrix.cells.size}"
    }
}
