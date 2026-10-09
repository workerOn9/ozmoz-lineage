package io.github.workeron9.ozmoz.lineage.lineage

import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Span
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider

/**
 * 规则 B：限定符（已折叠）是否命中该来源。
 *
 * - [TableSourcePlan]：表别名 / 表名 / 限定名（折叠比较）；
 * - [CteSourcePlan]：CTE 名（解析后的名字）或引用别名；
 * - [DerivedSourcePlan]：仅别名非空才可能匹配。
 *
 * 供列解析（[ColumnResolver.resolveQualified]）与 `*` 展开的限定解析（规则 D）共用。
 */
internal fun matchesQualifier(source: SourcePlan, foldedQualifier: String): Boolean = when (source) {
    is TableSourcePlan ->
        source.table.alias?.lowercase() == foldedQualifier ||
            source.table.name.lowercase() == foldedQualifier ||
            source.table.qualifiedName.lowercase() == foldedQualifier

    is CteSourcePlan ->
        source.source.name.lowercase() == foldedQualifier ||
            source.source.alias?.lowercase() == foldedQualifier

    is DerivedSourcePlan -> source.source.alias?.lowercase() == foldedQualifier
}

/**
 * 规则 B：把 [io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr.Column] 里的
 * [ColumnRef] 解析成**上游端点**（端点本身就是一个 [ColumnRef]）。
 *
 * Never-wrong：解析不了（限定名未命中 / 歧义 / 来源没有该列）一律返回
 * [Resolved.Unknown] 并带上原因，由调用方记进 `model.unknowns`，**绝不猜**。
 * Lossless：端点的 `raw` 保留引用原文，`canonical` 用折叠（小写）规则拼装。
 */
internal class ColumnResolver(
    /** 集合运算容器的「有效输出」= 首个分支的有效输出（规则 A）。 */
    private val effectiveOutputs: (scopeId: String) -> List<OutputSlot>,
    /** 可选的 schema 提供方：收录了物理来源时其列清单可**证伪**（Never-wrong 的前提）。 */
    private val schema: SchemaProvider? = null,
) {

    /** 规则 B 的完整入口：带限定走 [resolveQualified]，裸列名走 [resolveBareAmong]。 */
    fun resolve(ref: ColumnRef, scope: ScopePlan): Resolved<ColumnRef> =
        if (ref.table != null) resolveQualified(ref, scope)
        else resolveBareAmong(ref.name, ref.span, scope.sourcePlans)

    /**
     * 带限定（`ref.table != null`，限定符 = 别名或表名，折叠比较）：
     * 在 `scope.sourcePlans` 里找匹配来源。0 命中 → Unknown「限定名未命中来源」；
     * ≥2 命中 → Unknown「限定名歧义」；恰 1 命中 → [endpointInSource]。
     */
    fun resolveQualified(ref: ColumnRef, scope: ScopePlan): Resolved<ColumnRef> {
        val qualifier = ref.table
            // 防御：resolve 入口已保证非空；直接调用也不猜，显式落 Unknown。
            ?: return Resolved.Unknown("限定名缺失: ${ref.raw}", ref.span)
        val folded = qualifier.lowercase()
        val matches = scope.sourcePlans.filter { matchesQualifier(it, folded) }
        return when (matches.size) {
            0 -> Resolved.Unknown("限定名未命中来源: $qualifier", ref.span)
            1 -> endpointInSource(ref.name, ref.raw, ref.span, matches.single())
            else -> Resolved.Unknown("限定名歧义: $qualifier", ref.span)
        }
    }

    /**
     * 裸列名：候选 = 所有物理来源 + 拥有该名列的 CTE / 派生来源（按有效输出判定）。
     * **schema 参与消歧**：物理来源被提供方收录（列清单已知）且其中没有该列时，
     * 不再是候选（可证伪）；未收录（查不到）的物理来源仍是候选（无法证伪，同旧规则）。
     * 恰 1 候选 → 归属它；≥2 → Unknown「列名无法唯一归属」；0 → Unknown「来源没有列」。
     *
     * `sources` 是候选来源集合：整作用域解析传 `scope.sourcePlans`；
     * JOIN `USING` 左侧解析传「该 JOIN 之前的来源」（规则 E）。
     */
    fun resolveBareAmong(name: String, span: Span?, sources: List<SourcePlan>): Resolved<ColumnRef> {
        val folded = name.lowercase()
        val candidates = sources.filter { isBareCandidate(it, folded) }
        return when (candidates.size) {
            0 -> Resolved.Unknown("来源没有列: $name", span)
            1 -> endpointInSource(name, name, span, candidates.single())
            else -> Resolved.Unknown("列名无法唯一归属: $name", span)
        }
    }

    /**
     * 在**唯一**来源里解析列名（JOIN `USING` 右侧来源，规则 E）：
     * 物理表 → 直接归属（列集合未知、无法证伪，但来源唯一）；CTE / 派生 →
     * 在其 body 的有效输出里按名找（折叠），未命中 → Unknown「来源 X 没有列 Y」。
     */
    fun resolveInSource(name: String, span: Span?, source: SourcePlan): Resolved<ColumnRef> =
        endpointInSource(name, name, span, source)

    /** 裸列名的候选判定：物理表恒为候选（schema 收录且没有该列时可证伪，排除）；
     *  CTE / 派生只有拥有该名列（按有效输出）才是候选。 */
    private fun isBareCandidate(source: SourcePlan, foldedName: String): Boolean = when (source) {
        is TableSourcePlan -> schema?.table(source.table)?.let { it.column(foldedName) != null } ?: true
        is CteSourcePlan -> hasColumn(source.body.id, foldedName)
        is DerivedSourcePlan -> hasColumn(source.body.id, foldedName)
    }

    private fun hasColumn(bodyId: String, foldedName: String): Boolean =
        effectiveOutputs(bodyId).any { it.name!!.lowercase() == foldedName }

    /**
     * 恰一命中来源 → 端点：
     * - 物理表 → schema 收录该表且**没有**该列 → Unknown「来源 X 没有列 Y」（可证伪）；
     *   否则端点 = `ColumnRef(raw=引用原文, canonical=fold(表限定名.列名), name=列名, table=表限定名)`
     *   （schema 未收录时沿用「无法证伪、直接归属」的旧规则）；
     * - CTE / 派生 → 在其 body 的有效输出里按名找（折叠）：未命中 → Unknown「来源 X 没有列 Y」；
     *   命中 → 端点 = 该输出槽的 consumerRef。
     */
    private fun endpointInSource(name: String, raw: String, span: Span?, source: SourcePlan): Resolved<ColumnRef> =
        when (source) {
            is TableSourcePlan -> {
                val known = schema?.table(source.table)
                if (known != null && known.column(name) == null) {
                    Resolved.Unknown("来源 ${source.table.qualifiedName} 没有列 $name", span)
                } else {
                    Resolved.Known(
                        ColumnRef(
                            raw = raw,
                            canonical = (source.table.qualifiedName + "." + name).lowercase(),
                            name = name,
                            table = source.table.qualifiedName,
                            span = span,
                        ),
                    )
                }
            }

            is CteSourcePlan -> outputEndpoint(name, span, displayName = source.source.name, bodyId = source.body.id)
            is DerivedSourcePlan -> outputEndpoint(name, span, displayName = source.source.alias ?: "无别名派生表", bodyId = source.body.id)
        }

    private fun outputEndpoint(name: String, span: Span?, displayName: String, bodyId: String): Resolved<ColumnRef> {
        val folded = name.lowercase()
        val slot = effectiveOutputs(bodyId).firstOrNull { it.name!!.lowercase() == folded }
            ?: return Resolved.Unknown("来源 $displayName 没有列 $name", span)
        return Resolved.Known(slot.consumerRef ?: return Resolved.Unknown("来源 $displayName 的列 $name 没有消费位", span))
    }
}
