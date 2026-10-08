package io.github.workeron9.ozmoz.lineage.ir

import kotlinx.serialization.Serializable

/**
 * 表引用。同一张表在 SQL 里可能以不同写法出现（`T` / `t` / `"T"` / `db.t`），
 * [raw] 保留**原始拼写**（Lossless），[canonical] 是折叠后的规范形式（用于匹配）。
 */
@Serializable
public data class TableRef(
    val raw: String,
    val canonical: String,
    val catalog: String? = null,
    val schema: String? = null,
    val name: String,
    val alias: String? = null,
    val span: Span? = null,
) {
    init {
        require(raw.isNotBlank()) { "TableRef.raw 不能为空" }
        require(canonical.isNotBlank()) { "TableRef.canonical 不能为空" }
        require(name.isNotBlank()) { "TableRef.name 不能为空" }
    }

    /** 限定名，形如 `schema.name` 或 `catalog.schema.name`。 */
    public val qualifiedName: String
        get() = listOfNotNull(catalog, schema, name).joinToString(".")

    /** 图与诊断里用的稳定标识：限定名折叠后的小写形式。 */
    public val id: String get() = qualifiedName.lowercase()
}

/**
 * 列引用。与 [TableRef] 同理，[raw] 保原始拼写，[canonical] 供匹配。
 */
@Serializable
public data class ColumnRef(
    val raw: String,
    val canonical: String,
    val name: String,
    val table: String? = null,
    val schema: String? = null,
    val span: Span? = null,
) {
    init {
        require(raw.isNotBlank()) { "ColumnRef.raw 不能为空" }
        require(canonical.isNotBlank()) { "ColumnRef.canonical 不能为空" }
        require(name.isNotBlank()) { "ColumnRef.name 不能为空" }
    }

    /** 若带表限定，返回 `table.name`；否则只返回列名。 */
    public val qualifiedName: String
        get() = if (table == null) name else "$table.$name"

    public val id: String get() = qualifiedName.lowercase()
}
