package io.github.workeron9.ozmoz.lineage.schema

import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TableSchema

/**
 * 内存静态 [SchemaProvider]：给定一组 [TableSchema]，支持限定名直接命中与
 * search path 逐 schema 匹配（恰一张命中才返回，多张命中视为查不到——歧义不猜）。
 *
 * 它是其余实现的**共同底座**：JDBC 快照、DDL 解析结果都落成一张静态索引再查询，
 * 保证各实现的匹配语义完全一致。
 */
public class StaticSchemaProvider(
    tables: List<TableSchema>,
    override val defaultSearchPath: Set<String> = emptySet(),
    override val id: String = "static",
) : SchemaProvider {

    private val byQualifiedId: Map<String, TableSchema> = tables.associateBy { it.id }

    /** (schema?.lowercase(), name.lowercase()) → 表；同名多 schema 时靠 search path 收敛。 */
    private val byName: Map<Pair<String?, String>, TableSchema> =
        tables.associateBy { (it.table.schema?.lowercase()) to it.table.name.lowercase() }

    init {
        // 折叠后重名（如 `S.T` 与 `s.t` 并存）无法区分，构造即拒绝，免得查询期静默二选一。
        require(byQualifiedId.size == tables.size) { "StaticSchemaProvider 内表标识重复（折叠后）" }
        require(byName.size == tables.size) {
            "StaticSchemaProvider 内表名重复（同 schema 下折叠后同名）：" +
                tables.groupBy { (it.table.schema?.lowercase()) to it.table.name.lowercase() }
                    .filterValues { it.size > 1 }.keys.joinToString()
        }
    }

    override fun table(ref: TableRef): TableSchema? {
        byQualifiedId[ref.id]?.let { return it }
        if (ref.schema != null) return null // 限定名 miss 就是 miss，不拿 search path 猜。

        val foldedName = ref.name.lowercase()
        val candidates = defaultSearchPath.mapNotNull { byName[it.lowercase() to foldedName] }
        return candidates.singleOrNull()
    }

    override fun search(fuzzy: String): List<TableRef> {
        val needle = fuzzy.lowercase()
        return byQualifiedId.values
            .map { it.table }
            .filter { it.name.lowercase().contains(needle) || it.id.contains(needle) }
            .sortedBy { it.id } // 确定性：不依赖 Map 遍历序以外的任何顺序
            .toList()
    }

    public companion object {
        /** Java 友好入口：逐表传入。 */
        @JvmStatic
        public fun of(id: String, vararg tables: TableSchema): StaticSchemaProvider =
            StaticSchemaProvider(tables.toList(), id = id)
    }
}
