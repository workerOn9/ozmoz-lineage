package io.github.workeron9.ozmoz.lineage.engine

import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic

/**
 * 一次**逐语句分析**的结果——[SqlEngine.analyzeScript] 的产出。
 *
 * 与 [SqlEngine.analyzeAll] 的差别只在「坏语句怎么办」：多语句脚本里某一条语法错误时，
 * `analyzeAll` 是**整段**失败（`Resolved.Unknown`）；`analyzeScript` 是**逐语句**降级——
 * 坏语句落进 [diagnostics]（带位置），好语句照常进 [statements]（**丢就说**，
 * Never-wrong 的镜像：不静默丢）。
 *
 * [statements] 只含**提取成功**的语句，顺序与源脚本一致；**无法建模**的语句
 * （纯 DDL / `MERGE` / `VALUES`）与 `analyzeAll` 同口径**跳过**，既不进 [statements]
 * 也不进 [diagnostics]——「解析不了」与「建模不了」是两回事。
 */
public data class AnalyzeOutcome(
    val statements: List<SemanticStatement>,
    /** 逐语句降级产生的诊断（当前只有语法错误，ERROR 级，带位置）。 */
    val diagnostics: List<Diagnostic> = emptyList(),
)
