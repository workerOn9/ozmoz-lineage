package io.github.workeron9.ozmoz.lineage.lineage

import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind
import io.github.workeron9.ozmoz.lineage.ir.Span
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import kotlinx.serialization.Serializable

/**
 * 名字解析后的一个**来源绑定**：FROM 子句里的一个来源到底是「物理表」「CTE」
 * 还是「派生表（子查询）」。
 *
 * 这一步是 `lineage` 与「表名提取」的分水岭：`engine` 只知道那是一个标识符，
 * 只有在这里、在作用域树的上下文中，才能判定 `FROM c` 里的 `c` 是 CTE 而非物理表。
 *
 * 判定不出来的一律落进 [Unknown]，**不猜**（Never-wrong）。
 */
@Serializable
public sealed interface ScopeSource {

    /** FROM 里写的别名（`FROM t AS x` 的 `x`）；无别名为 null。 */
    public val alias: String?

    /** 物理表或视图。 */
    @Serializable
    public data class Table(
        override val alias: String?,
        public val ref: TableRef,
    ) : ScopeSource

    /** 对某个 CTE 的引用。[scopeId] 指向定义它的作用域（若已建）。 */
    @Serializable
    public data class Cte(
        override val alias: String?,
        public val name: String,
        public val scopeId: String? = null,
    ) : ScopeSource

    /** 派生表（子查询）。[scopeId] 指向该子查询自己的作用域。 */
    @Serializable
    public data class Derived(
        override val alias: String?,
        public val scopeId: String,
    ) : ScopeSource

    /** 名字解析不出来的来源（如缺 schema 时无法判定 CTE / 物理表）。 */
    @Serializable
    public data class Unknown(
        override val alias: String?,
        public val raw: String,
        public val reason: String,
    ) : ScopeSource {
        init {
            require(reason.isNotBlank()) { "ScopeSource.Unknown 必须给出非空的 reason" }
        }
    }
}

/**
 * 作用域树里的一个作用域——**已做过名字解析**。
 *
 * 与 `engine-api` 的 `ScopeSpec`（引擎给出的原始结构）的区别：这里是解析后的结果，
 * 来源已判定为表 / CTE / 派生表，CTE 与派生表的嵌套关系已连成树（[parentId]）。
 */
@Serializable
public data class ResolvedScope(
    public val id: String,
    public val kind: ScopeKind,
    public val parentId: String? = null,
    public val sources: List<ScopeSource> = emptyList(),
    public val outputs: List<ColumnRef> = emptyList(),
    public val span: Span? = null,
) {
    init {
        require(id.isNotBlank()) { "ResolvedScope.id 不能为空" }
    }
}

/**
 * 一条语句解析出的**作用域树**。
 *
 * [scopes] 是扁平列表（各自带 [ResolvedScope.parentId] 连成树），
 * [rootScopeId] 是顶层作用域（一条 SELECT 语句有一个；多语句脚本将来会有多个）。
 *
 * [unknowns] 显式记录解析过程中放弃推断的位置与原因，**公开可统计**。
 */
@Serializable
public data class ScopeTree(
    public val scopes: List<ResolvedScope> = emptyList(),
    public val rootScopeId: String? = null,
    public val unknowns: List<UnknownEntry> = emptyList(),
) {
    init {
        val ids = scopes.map { it.id }
        require(ids.size == ids.toSet().size) { "ResolvedScope.id 必须唯一" }
    }

    public fun scope(id: String): ResolvedScope? = scopes.firstOrNull { it.id == id }
}

/** 显式放弃推断的一条记录：位置 + 原因，供统计 unknown 率。 */
@Serializable
public data class UnknownEntry(
    public val reason: String,
    public val span: Span? = null,
) {
    init {
        require(reason.isNotBlank()) { "UnknownEntry 必须给出非空的 reason" }
    }
}
