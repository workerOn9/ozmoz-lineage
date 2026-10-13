package io.github.workeron9.ozmoz.lineage.conformance

import kotlinx.serialization.Serializable

/**
 * **血缘质量报表**——计划 §8「把血缘好不好变成数字」的产品化落点（M1 收尾）。
 *
 * 三类数字，口径分开、不合并平均：
 * - **解析成功率**：按**方言**分别公布（[DialectQuality]），不合并平均（计划 §8 明文）；
 * - **precision / recall**：只在**标了 `lineage` 期望**的 case 上算，且只对拍「期望里断言过的
 *   边类型」（见 [LineageExpectation]）——覆盖多少条语料由 [QualityMeta.annotatedCount] 如实公布；
 * - **unknown 率**：`unknowns / 列引用总数`（分母口径见 `SemanticRefCounter`），全语料统计。
 *
 * 另单列 [EngineQuality.unexplainedGaps]：期望边缺失、且**没有**声明为 unknown 的条数——
 * 这是计划 §8「缺口全部落在 unknown」的直接红线，>0 即说明有**静默漏**（Never-wrong 的破口）。
 *
 * 报表**只报不卡**（本轮）：阈值（precision ≥0.98 / recall ≥0.85）先作为目标展示，不设门禁。
 */
@Serializable
public data class QualityReport(
    val meta: QualityMeta,
    /** 每个引擎的总账（引擎顺序 = 调用方给的注册顺序）。 */
    val engines: List<EngineQuality>,
    /** 按方言的解析成功率（方言按名字排序，稳定输出）。 */
    val byDialect: List<DialectQuality>,
    /** 按特性标签的 precision / recall（标签按名字排序）——where 质量掉下去。 */
    val byFeature: List<FeatureQuality>,
    /** 逐 case × 引擎明细（只含**已标注**的 case；按方言、caseId、引擎排序）。 */
    val cases: List<CaseQuality>,
)

@Serializable
public data class QualityMeta(
    /** ISO-8601 生成时间。 */
    val generatedAt: String,
    /** 仓库 commit（可溯源）；CI 未填时为 null。 */
    val commit: String? = null,
    val corpusRoot: String,
    /** 语料总条数。 */
    val caseCount: Int,
    /** 其中标了 `lineage` 期望的条数（precision / recall 只覆盖这些）。 */
    val annotatedCount: Int,
    val engineCount: Int,
)

/** 一个引擎的总账。 */
@Serializable
public data class EngineQuality(
    val engine: String,
    /** 解析/建模成功的 case 数（全部 case，不只已标注）。 */
    val parseOk: Int,
    val parseTotal: Int,
    /** 已标注 case 上的对拍结果（无标注 / 无可对拍产出时为 null）。 */
    val precision: Double? = null,
    val recall: Double? = null,
    val truePositives: Int = 0,
    /** 产出里期望没有的边（宁缺勿错的反面）。 */
    val falsePositives: Int = 0,
    /** 期望有但没产出的边。 */
    val falseNegatives: Int = 0,
    /** FN 里「没有声明为 unknown」的条数——>0 即静默漏。 */
    val unexplainedGaps: Int = 0,
    /** unknown 诊断总数（全语料）。 */
    val unknownDiagnostics: Int = 0,
    /** 列引用总数（全语料，含 `*`）——unknown 率的分母。 */
    val columnRefs: Int = 0,
    /** `unknownDiagnostics / columnRefs`；分母为 0 时 null。 */
    val unknownRate: Double? = null,
)

/** 一个方言下某引擎的解析成功率。 */
@Serializable
public data class DialectQuality(
    val dialect: String,
    val engines: List<EngineParseRate>,
)

@Serializable
public data class EngineParseRate(
    val engine: String,
    val ok: Int,
    val total: Int,
)

/** 一个特性标签下某引擎的 precision / recall（只统计已标注且该引擎解析成功的 case）。 */
@Serializable
public data class FeatureQuality(
    val feature: String,
    val engines: List<EngineQualitySlice>,
)

@Serializable
public data class EngineQualitySlice(
    val engine: String,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val precision: Double? = null,
    val recall: Double? = null,
)

/** 逐 case × 引擎明细（只含已标注的 case；缺口/意外边给出明细，便于定位）。 */
@Serializable
public data class CaseQuality(
    val caseId: String,
    val sourcePath: String,
    val dialect: String,
    val engine: String,
    /** 该引擎能否解析/建模这条 case。 */
    val ok: Boolean,
    val truePositives: Int = 0,
    val falsePositives: Int = 0,
    val falseNegatives: Int = 0,
    val unknownDiagnostics: Int = 0,
    val columnRefs: Int = 0,
    /** 期望有、产出没有的边（`from -> to [KIND]`）。 */
    val missing: List<String> = emptyList(),
    /** 产出有、期望没有的边（`from -> to [KIND]`）。 */
    val unexpected: List<String> = emptyList(),
    /** 解析/建模失败原因；成功为 null。 */
    val reason: String? = null,
)
