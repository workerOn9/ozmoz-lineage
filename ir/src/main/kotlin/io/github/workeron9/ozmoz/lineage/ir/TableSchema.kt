package io.github.workeron9.ozmoz.lineage.ir

/**
 * 一列的 schema 定义。这是**外部元数据**（JDBC / DDL / manifest）给出的权威事实，
 * 不是从 SQL 猜出来的——血缘引擎靠它做 `*` 展开、歧义消解与类型补全。
 */
public data class ColumnSchema(
    val name: String,
    val type: String? = null,
    val nullable: Boolean? = null,
    val ordinal: Int? = null,
    val comment: String? = null,
) {
    init {
        require(name.isNotBlank()) { "ColumnSchema.name 不能为空" }
    }
}

/**
 * 一张表的 schema 定义。
 *
 * [id] 是折叠后的稳定标识，与 [TableRef.id] 同构，用于在血缘图里对齐。
 */
public data class TableSchema(
    val table: TableRef,
    val columns: List<ColumnSchema> = emptyList(),
) {
    init {
        val names = columns.map { it.name.lowercase() }
        require(names.size == names.toSet().size) { "TableSchema 内列名不能重复：${table.id}" }
    }

    public val id: String get() = table.id

    /** 按折叠名查列；查不到返回 null，由调用方决定是 `unknown` 还是报诊断。 */
    public fun column(name: String): ColumnSchema? {
        val key = name.lowercase()
        return columns.firstOrNull { it.name.lowercase() == key }
    }

    public companion object {
        @JvmStatic
        public fun of(table: TableRef, vararg columns: ColumnSchema): TableSchema =
            TableSchema(table, columns.toList())
    }
}
