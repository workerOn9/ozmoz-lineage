package io.github.workeron9.ozmoz.lineage.conformance

import kotlinx.serialization.Serializable

/**
 * 兼容性矩阵——计划 §4.4 的「语料 × 方言 × 引擎」产物（M3 的 `compat-matrix.json`）。
 *
 * 每个 cell = 「一个引擎吃一条语料」的实测结果，**每格可溯源**：
 * [MatrixCell.sourcePath] 指回语料文件，[MatrixCell.reason] 给出失败原因（前 40 字），
 * [MatrixMeta.commit] 由调用方（CLI `--commit` / CI）填入仓库快照。
 */
@Serializable
public data class CompatMatrix(
    val meta: MatrixMeta,
    /** 方言 × 引擎 覆盖计数（排序后落 JSON，格式稳定）。 */
    val coverage: Map<String, Map<String, Coverage>>,
    val cells: List<MatrixCell>,
)

@Serializable
public data class MatrixMeta(
    /** ISO-8601 生成时间。 */
    val generatedAt: String,
    /** 仓库 commit（可溯源）；CI 未填时为 null。 */
    val commit: String? = null,
    /** 语料根目录（原样记录，人读用）。 */
    val corpusRoot: String,
    val caseCount: Int,
    val engineCount: Int,
)

/** 同一方言下某引擎的实测计数。 */
@Serializable
public data class Coverage(
    val ok: Int,
    val total: Int,
)

@Serializable
public data class MatrixCell(
    val engine: String,
    val caseId: String,
    /** 语料文件在语料根目录下的归一化路径（相对路径，`/` 分隔）。 */
    val sourcePath: String,
    val dialect: String,
    val kind: CaseKind,
    val features: List<String> = emptyList(),
    /** `analyzeAll` 成功（Known），无视未知数多少——文案级 unknown 数另见 [unknowns]。 */
    val ok: Boolean,
    /** 提取到的语义模型条数（Known；脚本里不可建模语句被跳过时可能为 0）。 */
    val statements: Int? = null,
    /** 列级 unknown 总数（Known 时对每条语句跑 [io.github.workeron9.ozmoz.lineage.lineage.LineageBuilder] 统计）。 */
    val unknowns: Int? = null,
    /** 失败原因（Unknown 的 reason / 异常消息，**前 40 字**）；成功时为 null。 */
    val reason: String? = null,
)
