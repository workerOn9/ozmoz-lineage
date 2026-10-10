package io.github.workeron9.ozmoz.lineage.lineage

import io.github.workeron9.ozmoz.lineage.engine.semantics.CteSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.JoinSpec
import io.github.workeron9.ozmoz.lineage.engine.semantics.OutputItem
import io.github.workeron9.ozmoz.lineage.engine.semantics.SqlExpr
import io.github.workeron9.ozmoz.lineage.engine.semantics.SubquerySource
import io.github.workeron9.ozmoz.lineage.engine.semantics.TableSource
import io.github.workeron9.ozmoz.lineage.ir.TableRef

/**
 * 作用域构建的**内部计划**（module-private）：`ScopeTree`（可序列化、对外稳定）之外，
 * 把列级血缘需要的**原表达式**（[OutputItem] / [SqlExpr] / [JoinSpec]）一并保留下来。
 *
 * [ScopeTreeBuilder.build] 的公开行为完全不变；列级血缘（`LineageBuilder`）从这里取
 * 「作用域 → 输出项 → 表达式」的对应，而不是回头反解析 `ScopeTree` 里只剩名字的 outputs。
 *
 * 这些类型**故意不是** `@Serializable`：`SqlExpr` / `SemanticStatement` 是引擎语义模型，
 * 不承担持久化契约；对外稳定契约是 [io.github.workeron9.ozmoz.lineage.ir.LineageModel]。
 */
internal sealed interface ScopeBinding {

    /** 顶层查询：输出即最终结果列（裸名，或由写入目标逐位对应）。 */
    data object Terminal : ScopeBinding

    /** CTE 体：消费名 = CTE 名（或 `WITH c(a, b)` 显式列名逐位对应）。 */
    data class Cte(val name: String, val explicitColumns: List<String>) : ScopeBinding

    /** 派生表（FROM 子查询）：消费名 = 别名；无别名时消费列为裸名。 */
    data class Derived(val alias: String?) : ScopeBinding
}

/** 一个作用域的全部来源，携带名字解析结果 + 原始 spec 的对应关系。 */
internal sealed interface SourcePlan {
    val source: ScopeSource
}

/** 物理表来源（或未命中 CTE 的名字）：列集合未知，`*` 展开需要 `SchemaProvider`。 */
internal data class TableSourcePlan(
    override val source: ScopeSource.Table,
    val table: TableRef,
    val spec: TableSource,
) : SourcePlan

/** CTE 来源：直接持有 CTE 体（[ScopePlan]），其输出列可递归推导。 */
internal data class CteSourcePlan(
    override val source: ScopeSource.Cte,
    val cte: CteSpec,
    val body: ScopePlan,
) : SourcePlan

/**
 * 递归 CTE 的**自引用**来源：`visible` 已命中但体的 [ScopePlan] 尚未构建完（正在构建中）。
 * 递归血缘展开需要迭代求值，暂不支持 → 来源显式落 [ScopeSource.Unknown]（Never-wrong），
 * 引用它的列解析记 unknown 并可统计，而不是抛内部错误。
 */
internal data class RecursiveSourcePlan(
    override val source: ScopeSource.Unknown,
) : SourcePlan

/** 派生表来源：持有子查询体的代表 [ScopePlan]。 */
internal data class DerivedSourcePlan(
    override val source: ScopeSource.Derived,
    val spec: SubquerySource,
    val body: ScopePlan,
) : SourcePlan

/** 一次 JOIN 的明细：原文类型 + 右侧来源计划（`NestedSource` 内嵌的 JOIN 也会归并到这里）。 */
internal data class JoinPlan(
    val spec: JoinSpec,
    val right: SourcePlan,
)

/**
 * 名字解析后的单个作用域：`ResolvedScope`（可序列化视图）+ 列级血缘需要的全部原表达式。
 *
 * @property scope 可序列化的作用域视图（id / kind / parentId / sources / outputs）。
 * @property binding 该作用域作为「被消费方」时的命名绑定（CTE 名 / 派生表别名 / 顶层）。
 * @property sourcePlans 与 [scope.sources] **一一对应**且同序。
 * @property joins 本作用域全部 JOIN（含 `NestedSource` 内嵌的），按出现顺序。
 * @property branches 仅集合运算容器非空：各分支计划（容器自身无来源无输出）。
 */
internal data class ScopePlan(
    val scope: ResolvedScope,
    val binding: ScopeBinding,
    val sourcePlans: List<SourcePlan>,
    val joins: List<JoinPlan>,
    val filters: List<SqlExpr>,
    val having: List<SqlExpr>,
    val groupBy: List<SqlExpr>,
    val orderBy: List<SqlExpr>,
    val outputItems: List<OutputItem>,
    val branches: List<ScopePlan>,
) {
    val id: String get() = scope.id
}

/** 一条语句的完整作用域计划：[ScopeTree]（对外视图）+ 供列级血缘消费的内部明细。 */
internal data class ScopeTreePlan(
    val tree: ScopeTree,
    val root: ScopePlan?,
    val byId: Map<String, ScopePlan>,
)
