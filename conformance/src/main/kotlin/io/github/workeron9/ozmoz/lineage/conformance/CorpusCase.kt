package io.github.workeron9.ozmoz.lineage.conformance

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
) {
    init {
        require(id.isNotBlank()) { "CorpusCase.id 不能为空" }
        require(dialect.isNotBlank()) { "CorpusCase($id).dialect 不能为空" }
        require(sql.isNotBlank()) { "CorpusCase($id).sql 不能为空" }
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
