package io.github.workeron9.ozmoz.lineage.engine.jsqlparser

import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table

/**
 * 标识符处理（Lossless + Never-wrong）。
 *
 * 规则：
 * - **raw 保原始拼写**：含引号字符（`"MyTable"` / `` `my_db` ``）与原始大小写。
 * - **canonical 折叠**：去引号、按需小写，供跨语句匹配。
 *
 * 注意（实测 2026-10-08，JSqlParser 5.4）：引擎**保留**引号字符在 `getName()` 里
 * （`SELECT a FROM "MyTable"` → `name = '"MyTable"'`）。所以这里不能直接 lowercase 了当
 * canonical，必须先剥离引号。这与「用 `getUnquotedName`」的直觉不同，是实测出来的。
 */
internal object Identifiers {

    private val QUOTES = charArrayOf('"', '`', '[', ']')

    /** 去掉包裹的引号字符，保留内部原始大小写。 */
    fun unquote(raw: String): String = raw.trim().trim(*QUOTES)

    /**
     * 折叠为匹配用的规范形式：去引号 + 小写。
     *
     * 大小写折叠策略：本项目 v1 采用**大小写不敏感**折叠（多数方言的默认行为），
     * 若将来需要区分大小写方言，改这里一处即可（并在 ADR 记录）。
     */
    fun canonical(raw: String): String = unquote(raw).lowercase()

    /** 由 JSqlParser `Table` 构造 [TableRef]，保留原始拼写与位置。 */
    fun tableRef(source: String, table: Table): TableRef {
        val nameRaw = table.name ?: ""
        val schemaRaw = table.schemaName?.takeUnless { it == "null" }
        val catalogRaw = table.databaseName?.takeUnless { it == "null" }
        val name = unquote(nameRaw)
        val schema = schemaRaw?.let(::unquote)
        val catalog = catalogRaw?.let(::unquote)
        return TableRef(
            raw = table.fullyQualifiedName ?: nameRaw,
            // canonical 由**逐段去引号后**拼装——不能对整串 FQN 去引号，
            // 否则 `my_db`.`MyTable` 会剩下中间的反引号（实测踩到）。
            canonical = listOfNotNull(catalog, schema, name).joinToString(".").lowercase(),
            catalog = catalog,
            schema = schema,
            name = name,
            alias = table.alias?.name?.let(::unquote),
            span = JsSqlSpan.ofNode(source, table),
        )
    }

    /** 由 JSqlParser `Column` 构造 [ColumnRef]。[table] 是可能存在的表限定前缀。 */
    fun columnRef(source: String, column: Column): ColumnRef {
        val nameRaw = column.columnName ?: ""
        val name = unquote(nameRaw)
        val tableName = column.table?.name?.let(::unquote)
        return ColumnRef(
            raw = column.fullyQualifiedName ?: nameRaw,
            canonical = listOfNotNull(tableName, name).joinToString(".").lowercase(),
            name = name,
            table = tableName,
            schema = column.table?.schemaName?.takeUnless { it == "null" }?.let(::unquote),
            span = JsSqlSpan.ofNode(source, column),
        )
    }
}
