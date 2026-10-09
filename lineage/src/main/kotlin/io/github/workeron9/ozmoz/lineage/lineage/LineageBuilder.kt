package io.github.workeron9.ozmoz.lineage.lineage

import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.referencedColumns
import io.github.workeron9.ozmoz.lineage.ir.ColumnNode
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageEdge
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Meta
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.ScopeNode
import io.github.workeron9.ozmoz.lineage.ir.Span
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TransformKind
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider

/**
 * 列级血缘构建器：把 [SemanticStatement]（`engine-api` 的语义模型）变成
 * [LineageModel]（`ir` 的对外稳定契约）。内部先经 [ScopeTreeBuilder.plan]
 * 拿到作用域计划（来源已判定 CTE / 派生 / 物理表，原表达式保留），再按以下
 * **冻结的语义规则**逐条实现：
 *
 * ## A. 输出槽（`OutputSlots.kt`）
 * 每个 `OutputItem` → 一个输出槽（名字 / 消费列 consumerRef / 原表达式 /
 * TransformKind / 上游端点），按 scope id 记忆化；集合运算容器的「有效输出」=
 * 首个分支的有效输出。命名来源：显式名；CTE 显式列名逐位命名（失配 → 记
 * Unknown 并退回显式名，不猜位置）；`*` 展开槽的名字 = 被展开来源的输出名；
 * 集合运算非首分支**位对齐首分支**（UNION 语义：结果列名以首分支为准）。
 * 没有任何名字 → 该槽无 consumerRef，不产 OUTPUT 边（「输出列没有名字」由
 * `ScopeTreeBuilder` 记，不重复记）。消费位按 [ScopeBinding]：
 * CTE → `表名.列名`；派生表 → `别名.输出名`（无别名裸名）；顶层再按语句种类
 * 覆盖——纯 SELECT 裸名，INSERT / CREATE 目标列逐位对齐（失配 → Unknown 且该
 * 顶层不发 OUTPUT 边），UPDATE 改用 assignments（每个赋值一个输出槽），
 * DELETE 无输出。
 *
 * ## B. 列引用解析（`ColumnResolver.kt`）
 * `SqlExpr.Column` → 上游端点（端点就是一个 [ColumnRef]）。带限定按别名 / 表名
 * （折叠比较）命中唯一来源；裸列名的候选 = 所有物理来源（无法证伪）+ 拥有该名
 * 列的 CTE / 派生来源。解析不了一律 `Resolved.Unknown` 记进 `model.unknowns`，
 * **绝不猜**；`raw` 保留原文，`canonical` 折叠（小写）拼装（Lossless）。
 *
 * ## C. TransformKind 分类（`AggregateFunctions.kt`）
 * 聚合注册表是**策划清单**：命中才标 AGGREGATE（含 `COUNT(*)`，`*` 不给上游），
 * 未收录的函数名标 EXPRESSION / CONSTANT 而不是猜。见 [classifyTransform]。
 *
 * ## D. `*` 展开（`OutputSlots.kt` 的 expandStar）
 * 输出项顶层的 `SqlExpr.Star`：qualifier 非空限定到唯一来源；CTE / 派生逐个
 * 有效输出产槽（DIRECT）；物理表 → 有 [io.github.workeron9.ozmoz.lineage.schema.SchemaProvider]
 * 且收录该表 → 按 schema 列清单逐个产槽（DIRECT）；否则记 Unknown（schema 缺失与
 * schema 未收录是两条不同措辞）。qualifier 为空按来源顺序展开，物理来源无法展开时
 * 记 Unknown 后继续。函数实参里的 `*`（如 `COUNT(*)`）不贡献上游。
 *
 * ## E. 边生成（确定性）
 * 边 id `e0`、`e1`…按分配顺序；全局顺序 = 作用域按 id 升序；作用域内：
 * 输出项（含 `*` 展开槽）→ JOIN（按 JoinPlan 顺序，先 USING 列后 ON 表达式）
 * → WHERE → HAVING → GROUP_BY → ORDER_BY → SOURCE。OUTPUT：每个有名槽且上游非空的
 * 每个上游端点 → 1 条边（CONSTANT / UNKNOWN 槽无边）；JOIN_KEY：`USING` 列或 `ON`
 * 的等值双列（折叠比较 `=`），expression=null、span=join.span；PREDICATE：WHERE /
 * HAVING（及 ON 里不构成 JOIN_KEY 的其余列引用）每个列引用端点 → 对本作用域每个有名
 * 输出槽的 consumerRef 各发 1 条边；作用域无名输出（如 DELETE）→ 不发。
 * GROUP_BY / ORDER_BY：与 PREDICATE 完全同形——每个 groupBy / orderBy 表达式
 * `referencedColumns()` 的每个列引用端点 → 对本作用域每个有名输出槽的 consumerRef
 * 各发 1 条边（GROUP_BY / GROUPING、ORDER_BY / ORDERING，expression=表达式原文、
 * span=表达式 span）；集合运算容器承载 `SetOperationQuery.orderBy`（容器无输出）→ 不发。
 * SOURCE（表级血缘）：本作用域每个**有表身份**的来源（物理表 / CTE 引用；派生表无表
 * 身份不发）→ 一个表级哨兵 [ColumnRef]（`table = null`）→ 对本作用域每个有名输出槽的
 * consumerRef 各发 1 条边（SOURCE / SOURCE，expression=null、span=来源 span）；
 * 同一作用域内同一「哨兵 id → 输出 id」只发 1 条（自连接合并）。
 *
 * ## F. 模型组装
 * scopes：物理 [TableRef] + CTE 的 TableRef（**派生来源无表身份，不进 sources**；
 * UPDATE 作用域 outputs = assignment refs）；columns：全部边的 from / to + 全部
 * 有名输出槽的 consumerRef，按 column.id 去重（先出现者胜；isOutput / scopeId
 * 按输出槽归属优先，否则取首个引用它的作用域）——**SOURCE 边的 from 是表级哨兵，
 * 不是列，不进 columns**；unknowns：作用域树的 unknowns
 * （保持原顺序）+ 本轮新增（按生成顺序）；diagnostics 原样透传。
 *
 * ## G. 确定性
 * 同一输入永远产出同一模型（无随机、无无序集合影响顺序），记忆化不改变结果。
 *
 * `meta` 由调用方（CLI / 服务层）填充引擎与耗时；传入 schema 提供方时自动记录其 id
 * 到 `Meta.schemaSnapshotId`。
 */
public object LineageBuilder {

    /**
     * 解析一条语句并构建列级血缘模型。空语句（只有诊断）产出空模型，不崩。
     *
     * @param schema 可选的 [io.github.workeron9.ozmoz.lineage.schema.SchemaProvider]：
     * 物理表 `*` 展开、裸列歧义消解（可证伪的物理来源不再恒为候选）、`INSERT` 未声明
     * 目标列的逐位对齐都靠它；为 null 时全部退回显式 unknown（行为与本轮之前一致）。
     */
    @JvmStatic
    @JvmOverloads
    public fun build(statement: SemanticStatement, schema: SchemaProvider? = null): LineageModel =
        Assembler(ScopeTreeBuilder.plan(statement), statement, schema).assemble()
}

/** 单次构建的全部状态（阶段 1 输出槽 → 阶段 2 边 → 阶段 3 组装，规则 E / F）。 */
private class Assembler(
    private val plan: ScopeTreePlan,
    private val statement: SemanticStatement,
    private val schema: SchemaProvider?,
) {

    private val newUnknowns = mutableListOf<Resolved.Unknown>()

    private val resolver: ColumnResolver by lazy {
        ColumnResolver(effectiveOutputs = { scopeId -> slotsEngine.effectiveOutputs(scopeId) }, schema = schema)
    }

    private val slotsEngine: SlotEngine by lazy {
        SlotEngine(
            plans = plan.byId,
            statement = statement,
            rootId = plan.root?.id,
            resolver = resolver,
            unknowns = newUnknowns,
            schema = schema,
        )
    }

    private val edges = mutableListOf<LineageEdge>()

    /** 列节点候选（ref → 首个引用它的作用域），先边后槽（规则 F），可能重复。 */
    private val columnCandidates = mutableListOf<Pair<ColumnRef, String>>()

    /** SOURCE 边的去重集合（哨兵 id → 输出 id），保证同作用域内同表同列只发 1 条。 */
    private val sourceEdgeSeen = LinkedHashSet<Pair<String, String>>()

    private var edgeCounter = 0

    fun assemble(): LineageModel {
        // 作用域 id 升序（ScopeTreeBuilder 的分配顺序，同一输入永远一致）。
        val scopeIds = plan.tree.scopes.map { it.id }

        // 阶段 1：全部作用域的输出槽（记忆化；`*` 展开 / 上游解析的 unknown 在此产生）。
        for (id in scopeIds) slotsEngine.slots(id)

        // 阶段 2：按规则 E 的全局顺序产边（作用域内：输出项 → JOIN → WHERE → HAVING
        // → GROUP_BY → ORDER_BY → SOURCE）。
        for (id in scopeIds) {
            val scope = plan.byId.getValue(id)

            for (slot in slotsEngine.slots(id)) {
                // CONSTANT / UNKNOWN 槽无边；有名槽且上游非空的每个上游端点 → 1 条边。
                if (slot.consumerRef == null) continue
                if (slot.upstream.isEmpty()) continue
                if (slot.transform == TransformKind.CONSTANT || slot.transform == TransformKind.UNKNOWN) continue
                for (endpoint in slot.upstream) {
                    emitEdge(
                        from = endpoint,
                        to = slot.consumerRef,
                        kind = EdgeKind.OUTPUT,
                        transform = slot.transform,
                        expression = slot.expr.raw, // `*` 展开槽 expression = 星号原文
                        span = slot.expr.span,
                        scopeId = id,
                    )
                }
            }

            for (join in scope.joins) joinEdges(join, scope)

            for (filter in scope.filters) predicateEdges(filter, scope)

            for (having in scope.having) predicateEdges(having, scope)

            for (groupBy in scope.groupBy) qualifierEdges(groupBy, scope, EdgeKind.GROUP_BY, TransformKind.GROUPING)

            for (orderBy in scope.orderBy) qualifierEdges(orderBy, scope, EdgeKind.ORDER_BY, TransformKind.ORDERING)

            sourceEdges(scope)
        }

        return LineageModel(
            meta = Meta(schemaSnapshotId = schema?.id), // 提供 schema 时记录其 id 供溯源
            scopes = scopeNodes(scopeIds),
            columns = columnNodes(scopeIds),
            edges = edges.toList(),
            unknowns = plan.tree.unknowns.map { Resolved.Unknown(reason = it.reason, span = it.span) } + newUnknowns,
            diagnostics = statement.diagnostics, // 原样透传（规则 F）
        )
    }

    // ——— JOIN_KEY（规则 E） ———

    /**
     * 每个 JOIN 先 USING 列后 ON 表达式：
     * - `USING (id)` → 在 JOIN 右侧来源解析列 + 在其之前的来源里解析（同规则 B），
     *   两端点可得 → 1 条边（左 → 右，JOIN_KEY，TransformKind.JOIN_KEY，
     *   expression=null，span=join.span）；解析不了 → 记 Unknown，不发；
     * - ON 里的 `BinaryOp(op="=")`（折叠比较）且两侧都是 `SqlExpr.Column`、各自可解析成
     *   恰一端点 → 1 条边（文本序左 → 右，同样 expression=null、span=join.span）；
     *   解析不了 → 记 Unknown，不发；不构成 JOIN_KEY 的其余列引用按 PREDICATE 处理。
     */
    private fun joinEdges(join: JoinPlan, scope: ScopePlan) {
        val spec = join.spec
        val rightIndex = scope.sourcePlans.indexOf(join.right)
        val before = if (rightIndex > 0) scope.sourcePlans.subList(0, rightIndex) else emptyList()

        for (using in spec.using) {
            val left = resolver.resolveBareAmong(using, spec.span, before)
            val right = resolver.resolveInSource(using, spec.span, join.right)
            if (left is Resolved.Known && right is Resolved.Known) {
                emitEdge(
                    from = left.value,
                    to = right.value,
                    kind = EdgeKind.JOIN_KEY,
                    transform = TransformKind.JOIN_KEY,
                    expression = null,
                    span = spec.span,
                    scopeId = scope.id,
                )
            } else {
                if (left is Resolved.Unknown) newUnknowns += left
                if (right is Resolved.Unknown) newUnknowns += right
            }
        }

        for (onExpr in spec.on) {
            if (isEquiColumnPair(onExpr)) {
                val binary = onExpr as SqlExpr.BinaryOp
                val left = resolver.resolve((binary.left as SqlExpr.Column).ref, scope)
                val right = resolver.resolve((binary.right as SqlExpr.Column).ref, scope)
                if (left is Resolved.Known && right is Resolved.Known) {
                    emitEdge(
                        from = left.value,
                        to = right.value,
                        kind = EdgeKind.JOIN_KEY,
                        transform = TransformKind.JOIN_KEY,
                        expression = null,
                        span = spec.span,
                        scopeId = scope.id,
                    )
                } else {
                    // 解析不了 → 记 Unknown，不发 JOIN_KEY；解析成功的一侧仍按 PREDICATE 处理
                    // （不构成 JOIN_KEY 的其余列引用同样按 PREDICATE 处理，规则 E）。
                    if (left is Resolved.Unknown) newUnknowns += left
                    if (right is Resolved.Unknown) newUnknowns += right
                    if (left is Resolved.Known) predicateFromEndpoint(left.value, onExpr, scope)
                    if (right is Resolved.Known) predicateFromEndpoint(right.value, onExpr, scope)
                }
            } else {
                predicateEdges(onExpr, scope)
            }
        }
    }

    /** `BinaryOp(op="=")`（折叠比较）且两侧都是 `SqlExpr.Column`。 */
    private fun isEquiColumnPair(expr: SqlExpr): Boolean =
        expr is SqlExpr.BinaryOp &&
            expr.op.trim().lowercase() == "=" &&
            expr.left is SqlExpr.Column &&
            expr.right is SqlExpr.Column

    // ——— PREDICATE（规则 E） ———

    /**
     * WHERE / HAVING（及 ON 里不构成 JOIN_KEY 的条件）每个表达式 referencedColumns
     * 的每个列引用 → 解析端点 → 对本作用域**每个有名输出槽的 consumerRef** 各发
     * 1 条边（PREDICATE，FILTER_PREDICATE，expression=表达式原文 raw，
     * span=表达式 span）。解析失败 → 记 Unknown（不猜）；作用域无名输出 → 不发。
     */
    private fun predicateEdges(expr: SqlExpr, scope: ScopePlan) {
        for (ref in expr.referencedColumns()) {
            when (val resolved = resolver.resolve(ref, scope)) {
                is Resolved.Known -> predicateFromEndpoint(resolved.value, expr, scope)
                is Resolved.Unknown -> newUnknowns += resolved
            }
        }
    }

    private fun predicateFromEndpoint(endpoint: ColumnRef, expr: SqlExpr, scope: ScopePlan) {
        for (consumer in slotsEngine.slots(scope.id).mapNotNull { it.consumerRef }) {
            emitEdge(
                from = endpoint,
                to = consumer,
                kind = EdgeKind.PREDICATE,
                transform = TransformKind.FILTER_PREDICATE,
                expression = expr.raw,
                span = expr.span,
                scopeId = scope.id,
            )
        }
    }

    // ——— GROUP_BY / ORDER_BY（规则 E，形状与 PREDICATE 完全同形） ———

    /**
     * GROUP BY / ORDER BY：每个表达式 [referencedColumns] 的每个列引用 → 解析端点 →
     * 对本作用域**每个有名输出槽的 consumerRef** 各发 1 条边（[kind] / [transform]
     * 由调用方给出，expression=表达式原文 raw，span=表达式 span）。
     * 解析失败 → 记 Unknown（不猜）；作用域无名输出 → 不发边（但解析仍尝试）。
     */
    private fun qualifierEdges(expr: SqlExpr, scope: ScopePlan, kind: EdgeKind, transform: TransformKind) {
        for (ref in expr.referencedColumns()) {
            when (val resolved = resolver.resolve(ref, scope)) {
                is Resolved.Known -> qualifierFromEndpoint(resolved.value, expr, scope, kind, transform)
                is Resolved.Unknown -> newUnknowns += resolved
            }
        }
    }

    private fun qualifierFromEndpoint(
        endpoint: ColumnRef,
        expr: SqlExpr,
        scope: ScopePlan,
        kind: EdgeKind,
        transform: TransformKind,
    ) {
        for (consumer in slotsEngine.slots(scope.id).mapNotNull { it.consumerRef }) {
            emitEdge(
                from = endpoint,
                to = consumer,
                kind = kind,
                transform = transform,
                expression = expr.raw,
                span = expr.span,
                scopeId = scope.id,
            )
        }
    }

    // ——— SOURCE（规则 E，表级血缘的哨兵边） ———

    /**
     * SOURCE：本作用域每个**有表身份**的来源（物理表 / CTE 引用；派生表无表身份，
     * 不发）→ 一个表级哨兵 [ColumnRef]（`table = null`）→ 对本作用域每个有名输出槽的
     * consumerRef 各发 1 条边。作用域无名输出 → 不发边。
     *
     * 去重：同一作用域内，同一「哨兵 id → 输出 id」只发 1 条（自连接 `t a JOIN t b`
     * 两个来源哨兵限定名相同 → 合并），用 [sourceEdgeSeen] 保证确定性。
     */
    private fun sourceEdges(scope: ScopePlan) {
        val consumers = slotsEngine.slots(scope.id).mapNotNull { it.consumerRef }
        if (consumers.isEmpty()) return
        for (source in scope.sourcePlans) {
            val sentinel = sourceSentinel(source) ?: continue
            for (consumer in consumers) {
                if (!sourceEdgeSeen.add(sentinel.id to consumer.id)) continue
                emitEdge(
                    from = sentinel,
                    to = consumer,
                    kind = EdgeKind.SOURCE,
                    transform = TransformKind.SOURCE,
                    expression = null,
                    span = sentinel.span,
                    scopeId = scope.id,
                )
            }
        }
    }

    /**
     * 表级哨兵 [ColumnRef]：`raw` = 来源原始拼写、`name` = 限定名（物理表 `qualifiedName` /
     * CTE 名）、`canonical` = `name` 折叠、`table = null`、`span` = 来源 span。
     * 派生表（[DerivedSourcePlan]）无表身份 → null（不发边）。
     */
    private fun sourceSentinel(source: SourcePlan): ColumnRef? = when (source) {
        is TableSourcePlan -> {
            val name = source.table.qualifiedName
            ColumnRef(raw = source.table.raw, canonical = name.lowercase(), name = name, table = null, span = source.table.span)
        }

        is CteSourcePlan -> {
            // CTE 引用来源的原始 TableSource 未保留在计划里，拿不到引用 span → null。
            val name = source.source.name
            ColumnRef(raw = name, canonical = name.lowercase(), name = name, table = null, span = null)
        }

        is DerivedSourcePlan -> null
    }

    // ——— 边与候选（规则 E：边 id 按分配顺序 e0、e1…） ———

    private fun emitEdge(
        from: ColumnRef,
        to: ColumnRef,
        kind: EdgeKind,
        transform: TransformKind,
        expression: String?,
        span: Span?,
        scopeId: String,
    ) {
        edges += LineageEdge(
            id = "e${edgeCounter++}",
            fromColumn = from,
            toColumn = to,
            kind = kind,
            transform = transform,
            expression = expression,
            span = span,
        )
        // SOURCE 边的 from 是**表级哨兵**（表 / CTE 整体），不是列——不进列节点集合
        // （规则 F 的 columns 只装列）。to 恒为输出列 consumerRef，照常登记。
        if (kind != EdgeKind.SOURCE) columnCandidates += from to scopeId
        columnCandidates += to to scopeId
    }

    // ——— 模型组装（规则 F） ———

    private fun scopeNodes(scopeIds: List<String>): List<ScopeNode> = scopeIds.map { id ->
        val scope = plan.byId.getValue(id)
        ScopeNode(
            id = id,
            kind = scope.scope.kind,
            // 物理表 TableRef + CTE 的 TableRef（raw=名，canonical=折叠，name=名，alias=别名）；
            // 派生来源无表身份，不进 sources。
            sources = scope.sourcePlans.mapNotNull { source ->
                when (source) {
                    is TableSourcePlan -> source.table
                    is CteSourcePlan -> TableRef(
                        raw = source.source.name,
                        canonical = source.source.name.lowercase(),
                        name = source.source.name,
                        alias = source.source.alias,
                    )

                    is DerivedSourcePlan -> null
                }
            },
            // 有名输出槽的 consumerRef 列表；UPDATE 作用域 = assignment refs（规则 F）。
            outputs = slotsEngine.slots(id).mapNotNull { it.consumerRef },
            parentId = scope.scope.parentId,
            span = scope.scope.span,
        )
    }

    private fun columnNodes(scopeIds: List<String>): List<ColumnNode> {
        // 输出槽归属（按 id 升序先到先得）：id → 首个把它作为输出槽的作用域。
        val slotOwners = LinkedHashMap<String, String>()
        for (id in scopeIds) {
            for (slot in slotsEngine.slots(id)) {
                val ref = slot.consumerRef ?: continue
                if (!slotOwners.containsKey(ref.id)) slotOwners[ref.id] = id
            }
        }
        // 有名输出槽的 consumerRef 追加在全部边之后（规则 F 的列举顺序），一并去重。
        for (id in scopeIds) {
            for (slot in slotsEngine.slots(id)) {
                slot.consumerRef?.let { columnCandidates += it to id }
            }
        }
        val seen = HashSet<String>()
        val columns = ArrayList<ColumnNode>(columnCandidates.size)
        for ((ref, scopeId) in columnCandidates) {
            if (!seen.add(ref.id)) continue // 先出现者胜
            val owner = slotOwners[ref.id]
            columns += ColumnNode(
                column = ref,
                scopeId = owner ?: scopeId, // 输出槽归属优先，否则取首个引用它的作用域
                isOutput = owner != null,
            )
        }
        return columns
    }
}
