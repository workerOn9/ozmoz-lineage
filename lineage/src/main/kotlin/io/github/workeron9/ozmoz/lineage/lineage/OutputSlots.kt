package io.github.workeron9.ozmoz.lineage.lineage

import io.github.workeron9.ozmoz.lineage.engine.semantics.OutputItem
import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.StatementKind
import io.github.workeron9.ozmoz.lineage.engine.semantics.referencedColumns
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TransformKind
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider

/**
 * 规则 A：一个**输出槽**——每个 [OutputItem] 对应一个（`*` 展开可对应多个）。
 *
 * @property name 输出名；null = 无名槽（无 consumerRef、不产 OUTPUT 边；
 * `ScopeTreeBuilder` 已为无名输出记过「输出列没有名字」，这里不重复记）。
 * @property consumerRef 消费列（按作用域绑定 [ScopeBinding] 推出）；null = 不产 OUTPUT 边。
 * @property expr 原表达式（`*` 展开槽 expression = 星号原文）。
 * @property transform [classifyTransform] 的分类。
 * @property upstream 上游端点列表（解析失败的引用已落进 unknowns，不在此出现）。
 */
internal data class OutputSlot(
    val name: String?,
    val consumerRef: ColumnRef?,
    val expr: SqlExpr,
    val transform: TransformKind,
    val upstream: List<ColumnRef>,
)

/**
 * 规则 A + D：把 [ScopePlan] 的 [OutputItem] 变成 [OutputSlot]，按 scope id 记忆化。
 *
 * 命名来源（规则 A）：显式名（别名优先，其次裸列名）；CTE 显式列名逐位命名；
 * `*` 展开槽的名字 = 被展开来源的输出名（规则 D，优先于 CTE 显式列的逐位命名）。
 * 集合运算容器的「有效输出」= 首个分支的有效输出；非首分支的输出名**位对齐首分支**
 * （UNION 语义：结果列名以首分支为准）；首位失配 → 记 Unknown 并退回各自显式名，不猜位置。
 */
internal class SlotEngine(
    private val plans: Map<String, ScopePlan>,
    private val statement: SemanticStatement,
    private val rootId: String?,
    private val resolver: ColumnResolver,
    private val unknowns: MutableList<Resolved.Unknown>,
    private val schema: SchemaProvider? = null,
) {

    private val memo = LinkedHashMap<String, List<OutputSlot>>()

    /** CTE 显式列名已与输出数失配的容器（其分支一律退回各自显式名，不猜位置）。 */
    private val cteColumnFallback = HashSet<String>()

    /** 「INSERT 未声明目标列」对每条语句只记一次（规则 A）。 */
    private var insertNoTargetColumnsRecorded = false

    /** 该作用域的全部输出槽（容器 → 空列表：容器无输出，规则 A）。 */
    fun slots(scopeId: String): List<OutputSlot> {
        memo[scopeId]?.let { return it }
        val computed = compute(scopeId)
        memo[scopeId] = computed
        return computed
    }

    /**
     * **有效输出**（供名字解析与 `*` 展开消费）：有名字且有消费位的槽；
     * 集合运算容器 = 首个分支的有效输出（递归：首分支本身也可能是容器）。
     */
    fun effectiveOutputs(scopeId: String): List<OutputSlot> {
        val plan = plans.getValue(scopeId)
        val firstBranch = plan.branches.firstOrNull()
            ?: return slots(scopeId).filter { it.name != null && it.consumerRef != null }
        return effectiveOutputs(firstBranch.id)
    }

    private fun compute(scopeId: String): List<OutputSlot> {
        if (scopeId == rootId) {
            when (statement.kind) {
                // 规则 A：UPDATE 不用 query 输出，改用 assignments（每个赋值一个输出槽）。
                StatementKind.UPDATE -> return assignmentSlots()
                // 规则 A：DELETE 无输出，无 OUTPUT 边。
                StatementKind.DELETE -> return emptyList()
                else -> {}
            }
        }
        val plan = plans.getValue(scopeId)
        if (plan.branches.isNotEmpty()) return emptyList() // 集合运算容器无输出
        return outputSlots(plan)
    }

    // ——— 步骤 1：输出项 → 原始槽（`*` 展开 / 分类 / 上游解析，规则 C + D） ———

    /** 命名前的原始槽：starName 是 `*` 展开槽的名字（规则 D），explicitName 是输出项显式名。 */
    private data class RawSlot(
        val starName: String?,
        val explicitName: String?,
        val expr: SqlExpr,
        val transform: TransformKind,
        val upstream: List<ColumnRef>,
    ) {
        fun toSlot(name: String?, consumerRef: ColumnRef?): OutputSlot =
            OutputSlot(name = name, consumerRef = consumerRef, expr = expr, transform = transform, upstream = upstream)
    }

    private fun outputSlots(plan: ScopePlan): List<OutputSlot> {
        val raw = ArrayList<RawSlot>(plan.outputItems.size)
        for (item in plan.outputItems) {
            val expr = item.expr
            if (expr is SqlExpr.Star) {
                // 规则 D：输出项顶层的 `*` → 展开槽（名字 = 被展开来源的输出名，DIRECT）。
                for ((name, upstream) in expandStar(expr, plan)) {
                    raw += RawSlot(starName = name, explicitName = null, expr = expr, transform = TransformKind.DIRECT, upstream = upstream)
                }
            } else {
                // 规则 C：SqlExpr.Unknown → UNKNOWN + 记 1 条 unknown（reason 用 expr.reason），无边。
                if (expr is SqlExpr.Unknown) unknowns += Resolved.Unknown(expr.reason, expr.span)
                raw += RawSlot(
                    starName = null,
                    explicitName = item.explicitName,
                    expr = expr,
                    transform = classifyTransform(expr),
                    upstream = resolveUpstream(expr.referencedColumns(), plan),
                )
            }
        }
        return when (val binding = plan.binding) {
            is ScopeBinding.Terminal -> terminalSlots(plan, raw)
            is ScopeBinding.Cte -> cteSlots(plan, raw, binding)
            is ScopeBinding.Derived -> derivedSlots(plan, raw, binding)
        }
    }

    /**
     * 规则 D：`*` 展开。
     *
     * - qualifier 非空：限定到唯一来源（解析同规则 B 的带限定规则：0 命中 → Unknown
     *   「限定名未命中来源」，≥2 → 「限定名歧义」）；CTE / 派生 → 逐个有效输出产槽
     *   （上游 = 来源槽的 consumerRef，DIRECT，expression = 星号原文）；物理表 →
     *   [expandPhysicalSource]。
     * - qualifier 为空：按来源顺序展开全部来源；物理来源无法展开时记 1 条 Unknown
     *   后继续展开其余来源。
     * - 展开目标是集合运算容器 → 有效输出 = 首分支输出（[effectiveOutputs]）。
     */
    private fun expandStar(star: SqlExpr.Star, plan: ScopePlan): List<Pair<String?, List<ColumnRef>>> {
        val qualifier = star.qualifier
        return if (qualifier == null) {
            plan.sourcePlans.flatMap { expandSource(it, star) }
        } else {
            val folded = qualifier.lowercase()
            val matches = plan.sourcePlans.filter { matchesQualifier(it, folded) }
            when {
                matches.isEmpty() -> {
                    unknowns += Resolved.Unknown("限定名未命中来源: $qualifier", star.span)
                    emptyList()
                }

                matches.size > 1 -> {
                    unknowns += Resolved.Unknown("限定名歧义: $qualifier", star.span)
                    emptyList()
                }

                else -> expandSource(matches.single(), star)
            }
        }
    }

    private fun expandSource(source: SourcePlan, star: SqlExpr.Star): List<Pair<String?, List<ColumnRef>>> = when (source) {
        is TableSourcePlan -> expandPhysicalSource(source, star)
        is CteSourcePlan -> expandOutputs(source.body.id)
        is DerivedSourcePlan -> expandOutputs(source.body.id)
    }

    /**
     * 规则 D：物理表的 `*` 展开。schema 提供方收录该表 → 按列清单（定义序）逐个产
     * 槽（名字 = 列名，上游 = `表限定名.列名` 端点，DIRECT）；未提供 schema 或
     * 提供方未收录 → 记 Unknown 且不产槽（两种情形措辞不同，便于排查是谁缺元数据）。
     */
    private fun expandPhysicalSource(source: TableSourcePlan, star: SqlExpr.Star): List<Pair<String?, List<ColumnRef>>> {
        val tableSchema = schema?.table(source.table)
        if (tableSchema == null) {
            unknowns += Resolved.Unknown(
                if (schema == null) {
                    "物理表 ${source.table.qualifiedName} 的列未知（`*` 展开需要 SchemaProvider）"
                } else {
                    "物理表 ${source.table.qualifiedName} 不在 schema 提供方 ${schema.id} 中，无法展开 `*`"
                },
                star.span,
            )
            return emptyList()
        }
        return tableSchema.columns.map { column ->
            val ref = ColumnRef(
                raw = column.name,
                canonical = (source.table.qualifiedName + "." + column.name).lowercase(),
                name = column.name,
                table = source.table.qualifiedName,
            )
            column.name to listOf(ref)
        }
    }

    private fun expandOutputs(bodyId: String): List<Pair<String?, List<ColumnRef>>> =
        effectiveOutputs(bodyId).map { slot -> slot.name to listOfNotNull(slot.consumerRef) }

    /** 规则 B 的上游解析：每个列引用 → 端点；解析失败 → 记 Unknown（不猜），不进 upstream。 */
    private fun resolveUpstream(refs: List<ColumnRef>, plan: ScopePlan): List<ColumnRef> =
        refs.mapNotNull { ref ->
            when (val resolved = resolver.resolve(ref, plan)) {
                is Resolved.Known -> resolved.value
                is Resolved.Unknown -> {
                    unknowns += resolved
                    null
                }
            }
        }

    // ——— 步骤 2：命名 + 消费位（规则 A 的各绑定） ———

    private fun ownName(slot: RawSlot): String? = slot.starName ?: slot.explicitName

    private fun ownNames(raw: List<RawSlot>): List<String?> = raw.map { ownName(it) }

    /** 该作用域所属的集合运算容器（非分支返回 null）。 */
    private fun branchContainer(plan: ScopePlan): ScopePlan? {
        val parentId = plan.scope.parentId ?: return null
        val parent = plans[parentId] ?: return null
        return parent.takeIf { it.branches.isNotEmpty() }
    }

    /**
     * 集合运算**非首分支**的输出名：位对齐首分支（UNION 语义：结果列名以首分支为准）。
     * 首分支 / 非分支 / CTE 显式列已失配 / 分支输出数与首分支不一致 → 返回 null，
     * 调用方退回各自显式名（失配各记一条 Unknown，不猜位置）。
     */
    private fun branchAlignedNames(plan: ScopePlan, raw: List<RawSlot>): List<String?>? {
        val container = branchContainer(plan) ?: return null
        if (container.branches.first().id == plan.id) return null
        // 先算首分支——它可能在此把容器标记进 cteColumnFallback（显式列失配）。
        val first = slots(container.branches.first().id)
        if (cteColumnFallback.contains(container.id)) return null
        if (first.size != raw.size) {
            unknowns += Resolved.Unknown("集合运算分支输出数与首分支不一致", plan.scope.span)
            return null
        }
        return first.map { it.name }
    }

    /** 规则 A：顶层绑定按语句种类分派（纯 SELECT 裸名 / INSERT 逐位 / CREATE 目标限定）。 */
    private fun terminalSlots(plan: ScopePlan, raw: List<RawSlot>): List<OutputSlot> = when (statement.kind) {
        StatementKind.SELECT -> {
            val names = branchAlignedNames(plan, raw) ?: ownNames(raw)
            raw.mapIndexed { i, slot -> slot.toSlot(name = names[i], consumerRef = names[i]?.let(::bareConsumerRef)) }
        }

        StatementKind.INSERT -> insertSlots(plan, raw)
        StatementKind.CREATE_VIEW, StatementKind.CREATE_TABLE_AS -> createSlots(plan, raw)

        // UPDATE 顶层已由 assignmentSlots 接管；DELETE 顶层无输出。这里只是防御，
        // Terminal 绑正常不会落到这两种（顶层集合运算分支只出现在 SELECT 系语句上）。
        StatementKind.UPDATE, StatementKind.DELETE -> raw.map { it.toSlot(ownName(it), null) }
    }

    /**
     * 规则 A：INSERT。
     * - 有 targetColumns → **逐位**对齐（consumerRef 的 table = target 限定名）；
     *   数量不一致 → 记 Unknown「INSERT 目标列数与查询输出数不一致」，该顶层不发 OUTPUT 边；
     * - 无 targetColumns → schema 提供方收录目标表 → 按**表列定义序**逐位对齐（同失配处理）；
     *   未收录（或 schema 缺失）→ 记 Unknown「INSERT 未声明目标列，需要 SchemaProvider」
     *   （每条语句一次），顶层不发 OUTPUT 边；
     * - 顶层是集合运算 → 每个分支各自逐位对齐 targetColumns（不依赖首分支）。
     */
    private fun insertSlots(plan: ScopePlan, raw: List<RawSlot>): List<OutputSlot> {
        val columns = statement.targetColumns
        val target = statement.target
        if (columns.isEmpty()) {
            val tableSchema = target?.let { schema?.table(it) }
            if (target != null && tableSchema != null) {
                if (tableSchema.columns.size != raw.size) {
                    unknowns += Resolved.Unknown("INSERT 目标列数与查询输出数不一致", plan.scope.span)
                    return inertNamed(raw)
                }
                return raw.mapIndexed { i, slot ->
                    val column = tableSchema.columns[i]
                    val name = slot.starName ?: column.name
                    slot.toSlot(name = name, consumerRef = targetConsumerRef(target, column.name, column.name))
                }
            }
            if (!insertNoTargetColumnsRecorded) {
                insertNoTargetColumnsRecorded = true
                unknowns += Resolved.Unknown("INSERT 未声明目标列，需要 SchemaProvider", plan.scope.span)
            }
            return inertNamed(raw)
        }
        if (target == null) {
            unknowns += Resolved.Unknown("INSERT 缺少写入目标，无法对齐目标列", plan.scope.span)
            return inertNamed(raw)
        }
        if (columns.size != raw.size) {
            unknowns += Resolved.Unknown("INSERT 目标列数与查询输出数不一致", plan.scope.span)
            return inertNamed(raw)
        }
        return raw.mapIndexed { i, slot ->
            val name = slot.starName ?: columns[i].name
            slot.toSlot(name = name, consumerRef = targetConsumerRef(target, columns[i].name, columns[i].raw))
        }
    }

    /**
     * 规则 A：CREATE_VIEW / CREATE_TABLE_AS。
     * - 无 targetColumns → consumerRef = `ColumnRef(table=target 限定名, name=输出显式名)`
     *   （无名输出跳过；集合运算分支位对齐首分支名——视图列名以首分支为准）；
     * - 有 targetColumns → 逐位对齐（同 INSERT 规则，含失配处理）。
     */
    private fun createSlots(plan: ScopePlan, raw: List<RawSlot>): List<OutputSlot> {
        val columns = statement.targetColumns
        val target = statement.target
        if (columns.isEmpty()) {
            if (target == null) {
                unknowns += Resolved.Unknown("${statement.kind.name} 缺少写入目标，无法定位输出列", plan.scope.span)
                return inertNamed(raw)
            }
            val names = branchAlignedNames(plan, raw) ?: ownNames(raw)
            return raw.mapIndexed { i, slot ->
                slot.toSlot(name = names[i], consumerRef = names[i]?.let { targetConsumerRef(target, it, it) })
            }
        }
        if (target == null) {
            unknowns += Resolved.Unknown("${statement.kind.name} 缺少写入目标，无法对齐目标列", plan.scope.span)
            return inertNamed(raw)
        }
        if (columns.size != raw.size) {
            unknowns += Resolved.Unknown("${statement.kind.name} 目标列数与查询输出数不一致", plan.scope.span)
            return inertNamed(raw)
        }
        return raw.mapIndexed { i, slot ->
            val name = slot.starName ?: columns[i].name
            slot.toSlot(name = name, consumerRef = targetConsumerRef(target, columns[i].name, columns[i].raw))
        }
    }

    /**
     * 规则 A：CTE 体绑定。
     * - 显式列名逐位命名（`WITH c(p, q) AS …` → 第 i 个输出名叫 p/q；`*` 展开槽的名字
     *   仍以规则 D 为准）；列数与输出数不一致 → 记 1 条 Unknown「CTE 显式列数与输出数
     *   不一致」并退回显式名，不猜位置（容器分支同样退回，容器记入失配名单）；
     * - 无显式列名 → name = 输出显式名；集合运算非首分支位对齐首分支名。
     */
    private fun cteSlots(plan: ScopePlan, raw: List<RawSlot>, binding: ScopeBinding.Cte): List<OutputSlot> {
        fun build(names: List<String?>): List<OutputSlot> = raw.mapIndexed { i, slot ->
            slot.toSlot(name = names[i], consumerRef = names[i]?.let { cteConsumerRef(binding.name, it) })
        }

        val columns = binding.explicitColumns
        val container = branchContainer(plan)
        val isNamingSource = container == null || container.branches.first().id == plan.id
        if (columns.isEmpty() || !isNamingSource) {
            // 无显式列，或非首分支（位对齐首分支——首分支已按显式列 / 显式名命名）。
            return build(branchAlignedNames(plan, raw) ?: ownNames(raw))
        }
        if (columns.size != raw.size) {
            unknowns += Resolved.Unknown("CTE 显式列数与输出数不一致", plan.scope.span)
            container?.let { cteColumnFallback.add(it.id) }
            return build(ownNames(raw))
        }
        return raw.mapIndexed { i, slot ->
            val name = slot.starName ?: columns[i]
            slot.toSlot(name = name, consumerRef = cteConsumerRef(binding.name, name))
        }
    }

    /**
     * 规则 A：派生表绑定。alias 非空 → `ColumnRef(table=alias, name=输出名)`；
     * alias 为空 → 裸名 `ColumnRef(name=输出名)`。集合运算非首分支位对齐首分支名。
     */
    private fun derivedSlots(plan: ScopePlan, raw: List<RawSlot>, binding: ScopeBinding.Derived): List<OutputSlot> {
        val names = branchAlignedNames(plan, raw) ?: ownNames(raw)
        return raw.mapIndexed { i, slot ->
            val name = names[i]
            slot.toSlot(
                name = name,
                consumerRef = name?.let { n ->
                    binding.alias?.let { a -> derivedConsumerRef(a, n) } ?: bareConsumerRef(n)
                },
            )
        }
    }

    /** 命名回退：保留名字（供排查）但不产 OUTPUT 边。 */
    private fun inertNamed(raw: List<RawSlot>): List<OutputSlot> = raw.map { it.toSlot(ownName(it), null) }

    /**
     * 规则 A（UPDATE）：每个 assignment 一个输出槽——consumerRef =
     * `ColumnRef(raw=assignment.target.raw, canonical=fold(target 限定名.目标列名),
     * name=目标列名, table=target 限定名)`，transform 按 assignment.value 分类。
     */
    private fun assignmentSlots(): List<OutputSlot> {
        val rootPlan = rootId?.let { plans[it] } ?: return emptyList()
        val target = statement.target
        return statement.assignments.map { assignment ->
            val value = assignment.value
            if (value is SqlExpr.Unknown) unknowns += Resolved.Unknown(value.reason, value.span)
            if (value is SqlExpr.Star) {
                unknowns += Resolved.Unknown("星号出现在不支持的展开位置（`*` 展开仅支持输出项顶层）", value.span)
            }
            val consumerRef = if (target == null) {
                unknowns += Resolved.Unknown("UPDATE 缺少目标表，无法定位赋值列 ${assignment.target.name}", assignment.target.span)
                null
            } else {
                ColumnRef(
                    raw = assignment.target.raw,
                    canonical = (target.qualifiedName + "." + assignment.target.name).lowercase(),
                    name = assignment.target.name,
                    table = target.qualifiedName,
                )
            }
            OutputSlot(
                name = assignment.target.name,
                consumerRef = consumerRef,
                expr = value,
                transform = classifyTransform(value),
                upstream = resolveUpstream(value.referencedColumns(), rootPlan),
            )
        }
    }

    // ——— 消费位构造（Lossless：raw 保留原文，canonical 折叠拼装） ———

    private fun bareConsumerRef(name: String): ColumnRef =
        ColumnRef(raw = name, canonical = name.lowercase(), name = name)

    private fun cteConsumerRef(cteName: String, name: String): ColumnRef =
        ColumnRef(raw = name, canonical = "$cteName.$name".lowercase(), name = name, table = cteName)

    private fun derivedConsumerRef(alias: String, name: String): ColumnRef =
        ColumnRef(raw = name, canonical = "$alias.$name".lowercase(), name = name, table = alias)

    private fun targetConsumerRef(target: TableRef, name: String, raw: String): ColumnRef =
        ColumnRef(raw = raw, canonical = "${target.qualifiedName}.$name".lowercase(), name = name, table = target.qualifiedName)
}
