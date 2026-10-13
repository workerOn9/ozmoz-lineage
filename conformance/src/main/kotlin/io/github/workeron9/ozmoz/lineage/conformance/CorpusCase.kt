package io.github.workeron9.ozmoz.lineage.conformance

import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import kotlinx.serialization.Serializable

/**
 * 语料里一条语句的类型（计划 §8 的「语句类型」分层）。
 */
@Serializable
public enum class CaseKind {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    MERGE,
    DDL,
    OTHER,
}

/**
 * 兼容性语料的一条 case（计划 §4.4 / §8：方言矩阵的行）。
 *
 * 一个 case 一个 JSON 文件，字段全部显式——「引擎能不能吃这条 SQL」的判定对象。
 * [dialect] 是**编写该 SQL 的方言**（不是引擎目标方言），决定矩阵按方言分层的归属；
 * [features] 是自由标签（`cte` / `window` / `union`…），供矩阵下钻。
 */
@Serializable
public data class CorpusCase(
    /** 全库唯一 id（重复即语料损坏，加载器拒绝）。 */
    val id: String,
    /** 编写方言语名（小写：`ansi` / `mysql` / `postgresql` / `hive` / `spark` / `trino` / `oracle` / `tsql`…）。 */
    val dialect: String,
    /** 语句类型，缺省 [CaseKind.SELECT]。 */
    val kind: CaseKind = CaseKind.SELECT,
    /** 特性标签，如 `["cte", "window"]`。 */
    val features: List<String> = emptyList(),
    /** 人读说明（这个 case 在验证什么）。 */
    val comment: String? = null,
    /** SQL 全文（多语句脚本允许分号分隔）。 */
    val sql: String,
    /**
     * 可选的 DDL（`CREATE TABLE …`）——**给血缘质量报表用**的元数据：有它才能把物理表的
     * `*` 展开成具体列、给裸列消歧。缺省 null（兼容性矩阵不看它，行为与旧版一致）。
     */
    val schema: String? = null,
    /**
     * **血缘 golden 期望**（可选）。只有标了它的 case 才进 precision / recall 统计
     * （计划 §8：语料标注 → 公开质量指标）；未标注的 case 仍参与「解析成功率 / unknown 率」。
     */
    val lineage: LineageExpectation? = null,
) {
    init {
        require(id.isNotBlank()) { "CorpusCase.id 不能为空" }
        require(dialect.isNotBlank()) { "CorpusCase($id).dialect 不能为空" }
        require(sql.isNotBlank()) { "CorpusCase($id).sql 不能为空" }
    }
}

/**
 * 一条 case 的**血缘期望**——手写的、独立于引擎的「正确结果」，用于把「血缘好不好」变成数字。
 *
 * 口径（与计划 §8 对齐）：
 * - 对拍单位是 **`(from, to, kind)` 三元组**（列引用折叠成小写后比较）——同一条 from→to
 *   可以因语义不同同时是 `OUTPUT` 与 `PREDICATE`，只比端点会掩盖错误；
 * - **只断言写进来的边**：precision 的「产出」侧也只取 [edges] 里出现过的 kind，
 *   未断言的 kind（如本条 case 不关心 `SOURCE`）不进 precision 分母——避免「没断言 = 判错」；
 * - [unknowns] 声明**允许未知的列引用**（折叠限定名，如 `users.email`）：期望边缺失时，
 *   若其 `from` 在 [unknowns] 里，算「**有解释的缺口**」；否则算「**静默漏**」——
 *   后者直接违反计划 §8「缺口全部落在 unknown」的红线，报表单列计数。
 */
@Serializable
public data class LineageExpectation(
    /** 期望存在的边。 */
    val edges: List<ExpectedEdge> = emptyList(),
    /** 声明为 unknown 的列引用（折叠限定名）——用于判定缺口是否「有解释」。 */
    val unknowns: List<String> = emptyList(),
) {
    init {
        // 有意允许**空期望**：`{"edges": []}` 是一个有意义的断言——「这条 case 期望零条边」
        // （典型：`DELETE` 无输出，正确结果就是零边；见 conformance/corpus/README 的口径）。
        for (edge in edges) require(edge.from.isNotBlank()) { "ExpectedEdge.from 不能为空" }
    }
}

/** 期望的一条边：`(from, to, kind)`。名字按 `ozml lineage --format edges` 的写法（限定名，大小写不敏感）。 */
@Serializable
public data class ExpectedEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
) {
    init {
        require(from.isNotBlank()) { "ExpectedEdge.from 不能为空" }
        require(to.isNotBlank()) { "ExpectedEdge.to 不能为空" }
    }
}

/** 语料的一个文件条目：case 本体 + 语料根目录下的归一化路径（供矩阵溯源）。 */
public data class CorpusEntry(
    val case: CorpusCase,
    /** 相对语料根目录的路径，如 `mysql/select-basic.json`。 */
    val path: String,
)

/** 语料加载结果：成功条目 + 全部加载问题（坏文件 / 字段缺失 / 重复 id，各自带文件定位）。 */
public data class LoadedCorpus(
    val entries: List<CorpusEntry>,
    val problems: List<String>,
) {
    public fun hasProblems(): Boolean = problems.isNotEmpty()
}
