package io.github.workeron9.ozmoz.lineage.conformance

import kotlinx.serialization.Serializable

/** 方言对：`from > to`（源方言按它转写为目标方言）。 */
public data class SqlPair(
    public val from: String,
    public val to: String,
) {
    public fun asText(): String = "$from>$to"

    public companion object {
        /** 解析 `a>b` 格式；非法给 null（调用方显式报错，不猜）。 */
        public fun parse(text: String): SqlPair? {
            val parts = text.trim().split('>')
            if (parts.size != 2) return null
            val from = parts[0].trim().lowercase()
            val to = parts[1].trim().lowercase()
            if (from.isEmpty() || to.isEmpty() || from == to) return null
            return SqlPair(from, to)
        }
    }
}

/**
 * 方言转换矩阵——M3 验收项「convert 对 20 组方言对给出 diff 视图」的数据载体
 * （计划 §6 M3 / `ozml matrix --convert`）。
 *
 * 与血缘 [CompatMatrix] 是**两个独立文档**（同步产会让旧解码炸未知字段，
 * 且两者口径不同）：血缘矩阵列「某引擎能不能建语义模型」；本矩阵列
 * 「某方言对能不能把某条语料转写过去」——**cell 带 `sql`（渲染成品）**，
 * 文档站 / CI 摘要直接拿它出 diff 视图。
 */
@Serializable
public data class ConvertMatrix(
    val meta: ConvertMatrixMeta,
    /** 方言对 × 引擎的覆盖计数（已按 (engine, from, to) 排序，JSON 逐字节确定）。 */
    val coverage: List<ConvertPairCoverage>,
    /** 展开格（已按 (engine, from, to, caseId) 排序）。 */
    val cells: List<ConvertCell>,
)

@Serializable
public data class ConvertMatrixMeta(
    /** ISO-8601 生成时间。 */
    val generatedAt: String,
    /** 仓库 commit（可溯源）；CI 未填时为 null。 */
    val commit: String? = null,
    /** 语料根目录（原样记录，人读用）。 */
    val corpusRoot: String,
    val caseCount: Int,
    /** 参与的渲染引擎数（声明 `DIALECT_RENDER` 且被选中的引擎）。 */
    val engineCount: Int,
    /** 实际跑起来的（引擎 × 方言对）组合数——方言对本身不一定被每个引擎支持。 */
    val pairCount: Int,
)

/** 同一引擎下某个方言对的实测计数。 */
@Serializable
public data class ConvertPairCoverage(
    val engine: String,
    val from: String,
    val to: String,
    /** 该方言对实际尝试的语料条数（= 语料里 `dialect == from` 的 case 数）。 */
    val cases: Int,
    val ok: Int,
    val failed: Int,
)

/**
 * 一格 = 「某引擎把某条语料从方言 A 转到方言 B」。
 *
 * - `ok=true` ⇒ **`sql` 是 render-verified 门禁放行的成品**（diff 视图直接展示）；
 * - `ok=false` ⇒ `sql` 为 null，`reason` 给截断后的失败原因（源解析 / 渲染 /
 *   re-parse 门禁拦截分开说话，**前 120 字**——比血缘矩阵的 40 字宽，
 *   因为门禁拦截的原因里带 base/rendered 对照需要更长上下文）。
 */
@Serializable
public data class ConvertCell(
    val engine: String,
    val caseId: String,
    /** 语料文件在语料根目录下的归一化路径（相对路径，`/` 分隔）。 */
    val sourcePath: String,
    val fromDialect: String,
    val toDialect: String,
    val ok: Boolean,
    /** 渲染成品（`ok=true` 时非 null；`ok=false` 永远 null——半吊子 SQL 不出炉）。 */
    val sql: String? = null,
    /** 失败原因（前 120 字）；成功时为 null。 */
    val reason: String? = null,
)
