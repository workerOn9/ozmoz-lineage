package io.github.workeron9.ozmoz.lineage.schema

import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TableSchema

/**
 * 按优先级组合多个 [SchemaProvider]：先给出的提供方**优先**（`table` 首个非空命中），
 * 用于「DDL 文件为主、JDBC 兜底」这类多源场景。
 *
 * - [table]：按声明顺序问，首个非空结果胜出；全空返回 null。
 * - [search]：各提供方结果按表 id 去重拼接（先出现者胜），保持确定性。
 * - [defaultSearchPath]：各提供方的并集（先出现者胜的顺序）。
 * - [isVolatile]：任一提供方易变即为 true。
 */
public class CompositeSchemaProvider(
    private val providers: List<SchemaProvider>,
    override val id: String = providers.joinToString("+") { it.id },
) : SchemaProvider {

    public constructor(vararg providers: SchemaProvider) : this(providers.toList())

    init {
        require(providers.isNotEmpty()) { "CompositeSchemaProvider 至少需要一个提供方" }
    }

    override val defaultSearchPath: Set<String>
        get() = providers.flatMap { it.defaultSearchPath }.toLinkedSet()

    override val isVolatile: Boolean
        get() = providers.any { it.isVolatile }

    override fun table(ref: TableRef): TableSchema? {
        for (provider in providers) provider.table(ref)?.let { return it }
        return null
    }

    override fun search(fuzzy: String): List<TableRef> {
        val seen = HashSet<String>()
        val result = ArrayList<TableRef>()
        for (provider in providers) {
            for (ref in provider.search(fuzzy)) {
                if (seen.add(ref.id)) result += ref
            }
        }
        return result
    }

    private fun <T> List<T>.toLinkedSet(): Set<T> = LinkedHashSet(this)
}
