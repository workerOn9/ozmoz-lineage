package io.github.workeron9.ozmoz.lineage.schema

import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TableSchema

/**
 * **Schema 元数据 SPI**：物理表有哪些列、什么类型的**权威来源**（JDBC / DDL 文件 /
 * manifest …）。血缘引擎靠它做三件无法从 SQL 本身推导的事：
 *
 * - 物理表的 `SELECT *` 展开（SQL 里不含列清单）；
 * - 裸列名的歧义消解（哪张物理表真有这一列）；
 * - `INSERT INTO t SELECT …` 未声明目标列时的逐位对齐。
 *
 * Never-wrong：查不到就返回 null，由调用方决定记 `unknown` 还是报诊断，**绝不猜**。
 * 大小写 / 引号 / 默认 schema（search path）的匹配规则**在实现内部处理**——
 * 本项目 v1 采用大小写不敏感折叠（去引号 + 小写），调用方传原始 [TableRef] 即可。
 *
 * 实现见 [StaticSchemaProvider] / [DdlFileSchemaProvider] / [JdbcSchemaProvider] /
 * [CompositeSchemaProvider]。
 */
public interface SchemaProvider {

    /** 提供方标识，写进 `LineageModel.meta.schemaSnapshotId` 供溯源。 */
    public val id: String

    /**
     * 按 [ref] 查表定义；查不到（未收录 / 折叠后歧义）返回 null。
     *
     * 匹配约定（各实现一致）：
     * - 限定名直接命中（折叠比较 catalog.schema.name）；
     * - [TableRef.schema] 为空时按 [defaultSearchPath] 逐候选 schema 匹配，
     *   恰一张命中才返回，多张命中视为查不到（歧义不猜）。
     */
    public fun table(ref: TableRef): TableSchema?

    /** 按名字子串（大小写不敏感）搜表，供 CLI 补全；无搜索能力返回空列表。 */
    public fun search(fuzzy: String): List<TableRef>

    /** 解析未限定表名时的候选 schema（如 `public`）；无则空集合。 */
    public val defaultSearchPath: Set<String>
        get() = emptySet()

    /** 元数据是否易变（true = 每次直接查源、不可缓存）。快照式实现返回 false。 */
    public val isVolatile: Boolean
        get() = false
}
