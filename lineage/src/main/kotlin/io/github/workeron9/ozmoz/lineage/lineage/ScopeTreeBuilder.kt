package io.github.workeron9.ozmoz.lineage.lineage

import io.github.workeron9.ozmoz.lineage.engine.semantics.CteSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.NestedSource
import io.github.workeron9.ozmoz.lineage.engine.semantics.OutputItem
import io.github.workeron9.ozmoz.lineage.engine.semantics.QuerySpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.ScopeSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SelectQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.SetOperationQuery
import io.github.workeron9.ozmoz.lineage.engine.semantics.SourceSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.SubquerySource
import io.github.workeron9.ozmoz.lineage.engine.semantics.TableSource
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.ScopeKind

/**
 * 把引擎无关的 [SemanticStatement]（`engine-api` 的语义模型）解析成
 * **名字解析后**的 [ScopeTree]。
 *
 * 这是「表名提取」与「血缘」之间的分水岭：`engine` 只能看到 FROM 里的一个标识符，
 * 只有在这里、在作用域树的上下文里，才能判定 `FROM c` 的 `c` 是 CTE、派生表还是物理表。
 * 判定不出来的一律显式落进 [ScopeSource.Unknown] / [UnknownEntry]，**绝不猜**（Never-wrong）。
 *
 * ## 作用域 id 方案
 *
 * id 形如 `s0`、`s1`……按**分配顺序**自增，`ScopeTree.scopes` 按 id 升序排列。
 * 分配顺序是确定性的：
 * 1. 先给当前查询的**主作用域**分配 id；
 * 2. 再按声明顺序给该查询的每个 CTE 预分配 id（这样 CTE 之间可互相引用）；
 * 3. 然后按声明顺序构建各 CTE 的查询体（其间产生的子作用域继续自增）；
 * 4. 最后解析主作用域的 FROM 来源（其中的子查询继续自增）。
 *
 * 同一输入永远产出同一组 id，便于 golden 测试与 diff。
 *
 * ## CTE 可见性 / 名字解析规则
 *
 * - 查询 `Q` 的作用域**可见的 CTE** = `Q.ctes` 声明的名字 ∪ 从外层查询继承来的 CTE 名字。
 * - 内层同名 CTE **覆盖**外层同名 CTE（大小写不敏感，与 SQL 未加引号标识符一致）。
 * - CTE 之间**互相可见**（含自身）：不强制 `RECURSIVE` 限制——能解析成 CTE 就解析成 CTE。
 * - **带 schema / catalog 限定的表名一律视为物理表**（`db.t` 不会命中名为 `t` 的 CTE）。
 * - 优先级：**可见 CTE 优先于物理表**——即 `FROM c` 在 `c` 是可见 CTE 时解析为 [ScopeSource.Cte]。
 *
 * ## UNION / 集合运算
 *
 * [SetOperationQuery] 本身没有 [ScopeSpec]，因此合成一个**容器作用域**承载各分支，
 * 其 [ScopeKind] 记为 [ScopeKind.ROOT]、无来源无输出；每个分支各自成为一个
 * [ScopeKind.UNION_BRANCH]（分支自身的 `ScopeSpec.kind` 即如此）的子作用域。
 * 这样所有分支都能从 [ScopeTree.rootScopeId] 经 `parentId` 抵达。
 */
public object ScopeTreeBuilder {

    /** 解析一条语句。空语句（只有诊断）返回空树，诊断作为 [UnknownEntry] 透出。 */
    @JvmStatic
    public fun build(statement: SemanticStatement): ScopeTree = Builder().run(statement)
}

/** 解析过程中对某个可见 CTE 的绑定：声明的名字 + 其作用域 id。 */
private data class CteRef(val name: String, val scopeId: String)

/**
 * 单次解析的状态。作用域先按 id 分配「槽位」，最后按 id 升序拉平成列表，
 * 因此列表顺序 == id 顺序，与构建先后无关。
 */
private class Builder {

    private val slots: MutableList<ResolvedScope?> = ArrayList()
    private val unknowns: MutableList<UnknownEntry> = ArrayList()
    private var counter: Int = 0

    private fun nextIndex(): Int = counter++

    private fun idOf(index: Int): String = "s$index"

    private fun register(index: Int, scope: ResolvedScope) {
        while (slots.size <= index) slots.add(null)
        slots[index] = scope
    }

    fun run(statement: SemanticStatement): ScopeTree {
        val query = statement.query
        val scope = statement.scope
        val rootScopeId = when {
            query != null -> buildQuery(query, parentId = null, inherited = emptyMap())
            scope != null -> buildSingleScope(scope)
            else -> null
        }

        val allUnknowns = unknowns.toList() + statement.diagnostics.map { UnknownEntry(it.message, it.span) }
        return ScopeTree(
            scopes = slots.map { it ?: error("内部错误：作用域槽位未被填充") },
            rootScopeId = rootScopeId,
            unknowns = allUnknowns,
        )
    }

    /** UPDATE / DELETE 等「没有 SELECT 产出」的语句：直接从 [ScopeSpec] 建一个作用域。 */
    private fun buildSingleScope(scope: ScopeSpec): String {
        val index = nextIndex()
        val id = idOf(index)
        val sources = resolveScopeSources(scope, parentScopeId = id, visible = emptyMap())
        val outputs = mapOutputs(scope.outputs)
        register(index, ResolvedScope(id = id, kind = scope.kind, parentId = null, sources = sources, outputs = outputs, span = scope.span))
        return id
    }

    /**
     * 构建一个 [QuerySpec]，返回**代表该查询的那个作用域 id**（CTE / 派生表引用指向它）。
     *
     * [forcedIndex] 用于 CTE：id 需在构建 CTE 体之前就已知（供互相引用），故由调用方预分配。
     */
    private fun buildQuery(
        query: QuerySpec,
        parentId: String?,
        inherited: Map<String, CteRef>,
        forcedIndex: Int? = null,
    ): String = when (query) {
        is SelectQuery -> buildSelect(query, parentId, inherited, forcedIndex)
        is SetOperationQuery -> buildSetOp(query, parentId, inherited, forcedIndex)
    }

    private fun buildSelect(
        query: SelectQuery,
        parentId: String?,
        inherited: Map<String, CteRef>,
        forcedIndex: Int?,
    ): String {
        val mainIndex = forcedIndex ?: nextIndex()
        val mainId = idOf(mainIndex)

        // 先预分配所有 CTE 的 id，再构建各自的查询体，使 CTE 之间可以互相引用。
        val cteIds = query.ctes.map { it to nextIndex() }
        val visible = LinkedHashMap(inherited)
        for ((cte, index) in cteIds) {
            visible[cte.name.lowercase()] = CteRef(name = cte.name, scopeId = idOf(index))
        }
        for ((cte, index) in cteIds) {
            buildQuery(cte.query, parentId = idOf(index), inherited = visible, forcedIndex = index)
        }

        val sources = resolveScopeSources(query.scope, parentScopeId = mainId, visible = visible)
        val outputs = mapOutputs(query.scope.outputs)
        register(
            mainIndex,
            ResolvedScope(
                id = mainId,
                kind = query.scope.kind,
                parentId = parentId,
                sources = sources,
                outputs = outputs,
                span = query.scope.span,
            ),
        )
        return mainId
    }

    private fun buildSetOp(
        query: SetOperationQuery,
        parentId: String?,
        inherited: Map<String, CteRef>,
        forcedIndex: Int?,
    ): String {
        val containerIndex = forcedIndex ?: nextIndex()
        val containerId = idOf(containerIndex)

        val cteIds = query.ctes.map { it to nextIndex() }
        val visible = LinkedHashMap(inherited)
        for ((cte, index) in cteIds) {
            visible[cte.name.lowercase()] = CteRef(name = cte.name, scopeId = idOf(index))
        }
        for ((cte, index) in cteIds) {
            buildQuery(cte.query, parentId = idOf(index), inherited = visible, forcedIndex = index)
        }

        for (branch in query.branches) {
            buildQuery(branch, parentId = containerId, inherited = visible)
        }

        register(
            containerIndex,
            ResolvedScope(
                id = containerId,
                kind = ScopeKind.ROOT,
                parentId = parentId,
                sources = emptyList(),
                outputs = emptyList(),
                span = query.span,
            ),
        )
        return containerId
    }

    /** 一个作用域的全部来源：FROM 项 + 各 JOIN 的右侧来源，按出现顺序。 */
    private fun resolveScopeSources(
        scope: ScopeSpec,
        parentScopeId: String,
        visible: Map<String, CteRef>,
    ): List<ScopeSource> = buildList {
        for (source in scope.sources) addAll(resolveSource(source, parentScopeId, visible))
        for (join in scope.joins) addAll(resolveSource(join.right, parentScopeId, visible))
    }

    private fun resolveSource(
        source: SourceSpec,
        parentScopeId: String,
        visible: Map<String, CteRef>,
    ): List<ScopeSource> = when (source) {
        is TableSource -> listOf(resolveTable(source, visible))
        is SubquerySource -> listOf(
            ScopeSource.Derived(
                alias = source.alias,
                scopeId = buildQuery(source.query, parentId = parentScopeId, inherited = visible),
            ),
        )
        is NestedSource -> buildList {
            addAll(resolveSource(source.from, parentScopeId, visible))
            for (join in source.joins) addAll(resolveSource(join.right, parentScopeId, visible))
        }
    }

    private fun resolveTable(source: TableSource, visible: Map<String, CteRef>): ScopeSource {
        val table = source.table
        // 只有不带 schema / catalog 限定的名字才可能与 CTE 同名。
        val cte = if (table.catalog == null && table.schema == null) {
            visible[table.name.lowercase()]
        } else {
            null
        }
        return if (cte != null) {
            ScopeSource.Cte(alias = table.alias, name = cte.name, scopeId = cte.scopeId)
        } else {
            ScopeSource.Table(alias = table.alias, ref = table)
        }
    }

    /** SELECT 列表 → 输出列。没有显式名字的输出**不猜**：跳过并记一条 [UnknownEntry]。 */
    private fun mapOutputs(outputs: List<OutputItem>): List<ColumnRef> = buildList {
        for (output in outputs) {
            val name = output.explicitName
            if (name == null) {
                unknowns += UnknownEntry(reason = "输出列没有名字", span = output.span)
            } else {
                add(ColumnRef(raw = name, canonical = name.lowercase(), name = name))
            }
        }
    }
}
