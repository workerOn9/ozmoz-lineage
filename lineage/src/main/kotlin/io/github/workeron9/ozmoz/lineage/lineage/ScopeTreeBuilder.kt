package io.github.workeron9.ozmoz.lineage.lineage

import io.github.workeron9.ozmoz.lineage.engine.semantics.CteSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.JoinSpec
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
import io.github.workeron9.ozmoz.lineage.ir.Span

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
 * 这样所有分支都能从 `ScopeTree.rootScopeId` 经 `parentId` 抵达。
 */
public object ScopeTreeBuilder {

    /** 解析一条语句。空语句（只有诊断）返回空树，诊断作为 [UnknownEntry] 透出。 */
    @JvmStatic
    public fun build(statement: SemanticStatement): ScopeTree = plan(statement).tree

    /**
     * 解析一条语句并保留列级血缘需要的**原表达式**（输出项 / JOIN / WHERE / HAVING 等）。
     * 公开契约（[build]）不变；这是 `LineageBuilder` 的输入。
     */
    internal fun plan(statement: SemanticStatement): ScopeTreePlan = Builder().run(statement)
}

/** 解析过程中对某个可见 CTE 的绑定：声明的名字 + 其作用域 id + 声明原文（显式列名等）。 */
private data class CteRef(val name: String, val scopeId: String, val cte: CteSpec)

/**
 * 单次解析的状态。作用域先按 id 分配「槽位」，最后按 id 升序拉平成列表，
 * 因此列表顺序 == id 顺序，与构建先后无关。
 */
private class Builder {

    private val slots: MutableList<ScopePlan?> = ArrayList()
    private val unknowns: MutableList<UnknownEntry> = ArrayList()
    private var counter: Int = 0

    private fun nextIndex(): Int = counter++

    private fun idOf(index: Int): String = "s$index"

    private fun register(index: Int, plan: ScopePlan) {
        while (slots.size <= index) slots.add(null)
        slots[index] = plan
    }

    fun run(statement: SemanticStatement): ScopeTreePlan {
        val query = statement.query
        val scope = statement.scope
        val root = when {
            query != null -> buildQuery(
                query = query,
                parentId = null,
                inherited = emptyMap(),
                forcedIndex = null,
                binding = ScopeBinding.Terminal,
            )

            scope != null -> buildSingleScope(scope)
            else -> null
        }

        val plans = slots.map { it ?: error("内部错误：作用域槽位未被填充") }
        val allUnknowns = unknowns.toList() + statement.diagnostics.map { UnknownEntry(it.message, it.span) }
        return ScopeTreePlan(
            tree = ScopeTree(
                scopes = plans.map { it.scope },
                rootScopeId = root?.id,
                unknowns = allUnknowns,
            ),
            root = root,
            byId = plans.associateBy { it.id },
        )
    }

    /** UPDATE / DELETE 等「没有 SELECT 产出」的语句：直接从 [ScopeSpec] 建一个作用域。 */
    private fun buildSingleScope(scope: ScopeSpec): ScopePlan {
        val index = nextIndex()
        val id = idOf(index)
        val sourcePlans = ArrayList<SourcePlan>()
        val joinPlans = ArrayList<JoinPlan>()
        collectSources(scope, parentScopeId = id, visible = emptyMap(), cteBodies = emptyMap(), sourcePlans, joinPlans)
        val plan = ScopePlan(
            scope = resolvedScope(id = id, kind = scope.kind, parentId = null, sourcePlans, scope.outputs, scope.span),
            binding = ScopeBinding.Terminal,
            sourcePlans = sourcePlans,
            joins = joinPlans,
            filters = scope.filters,
            having = scope.having,
            groupBy = scope.groupBy,
            orderBy = scope.orderBy,
            outputItems = scope.outputs,
            branches = emptyList(),
        )
        register(index, plan)
        return plan
    }

    /**
     * 构建一个 [QuerySpec]，返回**代表该查询的那个作用域计划**（CTE / 派生表引用指向它）。
     *
     * [forcedIndex] 用于 CTE：id 需在构建 CTE 体之前就已知（供互相引用），故由调用方预分配。
     * [binding] 是该查询体作为「被消费方」时的命名绑定（CTE 名 / 派生表别名 / 顶层）。
     */
    private fun buildQuery(
        query: QuerySpec,
        parentId: String?,
        inherited: Map<String, CteRef>,
        forcedIndex: Int?,
        binding: ScopeBinding,
    ): ScopePlan = when (query) {
        is SelectQuery -> buildSelect(query, parentId, inherited, forcedIndex, binding)
        is SetOperationQuery -> buildSetOp(query, parentId, inherited, forcedIndex, binding)
    }

    private fun buildSelect(
        query: SelectQuery,
        parentId: String?,
        inherited: Map<String, CteRef>,
        forcedIndex: Int?,
        binding: ScopeBinding,
    ): ScopePlan {
        val mainIndex = forcedIndex ?: nextIndex()
        val mainId = idOf(mainIndex)

        // 先预分配所有 CTE 的 id，再构建各自的查询体，使 CTE 之间可以互相引用。
        val cteIds = query.ctes.map { it to nextIndex() }
        val visible = LinkedHashMap(inherited)
        for ((cte, index) in cteIds) {
            visible[cte.name.lowercase()] = CteRef(name = cte.name, scopeId = idOf(index), cte = cte)
        }
        val cteBodies = LinkedHashMap<String, ScopePlan>()
        for ((cte, index) in cteIds) {
            cteBodies[cte.name.lowercase()] = buildQuery(
                query = cte.query,
                parentId = idOf(index),
                inherited = visible,
                forcedIndex = index,
                binding = ScopeBinding.Cte(name = cte.name, explicitColumns = cte.columns),
            )
        }

        val sourcePlans = ArrayList<SourcePlan>()
        val joinPlans = ArrayList<JoinPlan>()
        collectSources(query.scope, parentScopeId = mainId, visible, cteBodies, sourcePlans, joinPlans)
        val plan = ScopePlan(
            scope = resolvedScope(
                id = mainId,
                kind = query.scope.kind,
                parentId = parentId,
                sourcePlans,
                query.scope.outputs,
                query.scope.span,
            ),
            binding = binding,
            sourcePlans = sourcePlans,
            joins = joinPlans,
            filters = query.scope.filters,
            having = query.scope.having,
            groupBy = query.scope.groupBy,
            orderBy = query.scope.orderBy,
            outputItems = query.scope.outputs,
            branches = emptyList(),
        )
        register(mainIndex, plan)
        return plan
    }

    private fun buildSetOp(
        query: SetOperationQuery,
        parentId: String?,
        inherited: Map<String, CteRef>,
        forcedIndex: Int?,
        binding: ScopeBinding,
    ): ScopePlan {
        val containerIndex = forcedIndex ?: nextIndex()
        val containerId = idOf(containerIndex)

        val cteIds = query.ctes.map { it to nextIndex() }
        val visible = LinkedHashMap(inherited)
        for ((cte, index) in cteIds) {
            visible[cte.name.lowercase()] = CteRef(name = cte.name, scopeId = idOf(index), cte = cte)
        }
        val cteBodies = LinkedHashMap<String, ScopePlan>()
        for ((cte, index) in cteIds) {
            cteBodies[cte.name.lowercase()] = buildQuery(
                query = cte.query,
                parentId = idOf(index),
                inherited = visible,
                forcedIndex = index,
                binding = ScopeBinding.Cte(name = cte.name, explicitColumns = cte.columns),
            )
        }

        // 各分支继承容器的绑定：集合运算作为 CTE 体 / 派生表时，消费名由分支逐位承接。
        val branches = query.branches.map { branch ->
            buildQuery(query = branch, parentId = containerId, inherited = visible, forcedIndex = null, binding = binding)
        }

        val plan = ScopePlan(
            scope = ResolvedScope(
                id = containerId,
                kind = ScopeKind.ROOT,
                parentId = parentId,
                sources = emptyList(),
                outputs = emptyList(),
                span = query.span,
            ),
            binding = binding,
            sourcePlans = emptyList(),
            joins = emptyList(),
            filters = emptyList(),
            having = emptyList(),
            groupBy = emptyList(),
            orderBy = query.orderBy,
            outputItems = emptyList(),
            branches = branches,
        )
        register(containerIndex, plan)
        return plan
    }

    /** 组装 [ResolvedScope]，输出列只记名字（无名不猜）。 */
    private fun resolvedScope(
        id: String,
        kind: ScopeKind,
        parentId: String?,
        sourcePlans: List<SourcePlan>,
        outputs: List<OutputItem>,
        span: Span?,
    ): ResolvedScope = ResolvedScope(
        id = id,
        kind = kind,
        parentId = parentId,
        sources = sourcePlans.map { it.source },
        outputs = mapOutputs(outputs),
        span = span,
    )

    /**
     * 一个作用域的全部来源（FROM 项 + 各 JOIN 的右侧来源，按出现顺序）与全部 JOIN 明细
     * （含 `NestedSource` 内嵌的 JOIN），写入 [sourcePlans] / [joinPlans]。
     */
    private fun collectSources(
        scope: ScopeSpec,
        parentScopeId: String,
        visible: Map<String, CteRef>,
        cteBodies: Map<String, ScopePlan>,
        sourcePlans: MutableList<SourcePlan>,
        joinPlans: MutableList<JoinPlan>,
    ) {
        for (source in scope.sources) {
            collectSource(source, parentScopeId, visible, cteBodies, sourcePlans, joinPlans)
        }
        for (join in scope.joins) {
            collectSource(join.right, parentScopeId, visible, cteBodies, sourcePlans, joinPlans)
            joinPlans += JoinPlan(spec = join, right = sourcePlans.last())
        }
    }

    private fun collectSource(
        source: SourceSpec,
        parentScopeId: String,
        visible: Map<String, CteRef>,
        cteBodies: Map<String, ScopePlan>,
        sourcePlans: MutableList<SourcePlan>,
        joinPlans: MutableList<JoinPlan>,
    ) {
        when (source) {
            is TableSource -> sourcePlans += resolveTable(source, visible, cteBodies)
            is SubquerySource -> {
                val body = buildQuery(
                    query = source.query,
                    parentId = parentScopeId,
                    inherited = visible,
                    forcedIndex = null,
                    binding = ScopeBinding.Derived(alias = source.alias),
                )
                sourcePlans += DerivedSourcePlan(
                    source = ScopeSource.Derived(alias = source.alias, scopeId = body.id),
                    spec = source,
                    body = body,
                )
            }

            is NestedSource -> {
                collectSource(source.from, parentScopeId, visible, cteBodies, sourcePlans, joinPlans)
                for (join in source.joins) {
                    collectSource(join.right, parentScopeId, visible, cteBodies, sourcePlans, joinPlans)
                    joinPlans += JoinPlan(spec = join, right = sourcePlans.last())
                }
            }
        }
    }

    private fun resolveTable(
        source: TableSource,
        visible: Map<String, CteRef>,
        cteBodies: Map<String, ScopePlan>,
    ): SourcePlan {
        val table = source.table
        // 只有不带 schema / catalog 限定的名字才可能与 CTE 同名。
        val cte = if (table.catalog == null && table.schema == null) {
            visible[table.name.lowercase()]
        } else {
            null
        }
        return if (cte != null) {
            CteSourcePlan(
                source = ScopeSource.Cte(alias = table.alias, name = cte.name, scopeId = cte.scopeId),
                cte = cte.cte,
                body = cteBodies[cte.name.lowercase()]
                    ?: error("内部错误：CTE 体未构建：${cte.name}"),
            )
        } else {
            TableSourcePlan(source = ScopeSource.Table(alias = table.alias, ref = table), table = table, spec = source)
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
