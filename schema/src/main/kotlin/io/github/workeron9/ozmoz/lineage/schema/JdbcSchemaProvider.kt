package io.github.workeron9.ozmoz.lineage.schema

import io.github.workeron9.ozmoz.lineage.ir.ColumnSchema
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TableSchema
import java.sql.Connection

/**
 * 从 JDBC `DatabaseMetaData` 取数（`getTables` / `getColumns`，**JDBC 标准接口，
 * 不写方言特定的 information_schema SQL**）的 [SchemaProvider]。
 *
 * **快照语义**：首次访问时把当前连接可见的 TABLE / VIEW 全量载入内存索引
 * （[StaticSchemaProvider]），之后查询不再触库；[isVolatile] 恒为 false——
 * 元数据变更需重建提供方。大小写折叠在载入时统一完成（v1 大小写不敏感），
 * 因此不依赖各数据库对未加引号标识符的大写 / 小写习惯。
 *
 * 代价：全库扫描对大库不轻。需要按 schema 收敛时，把连接收窄到对应 catalog/schema
 * （或后续加 filter 参数），别在这里猜。
 */
public class JdbcSchemaProvider(
    private val connection: Connection,
    override val defaultSearchPath: Set<String> = emptySet(),
    override val id: String = "jdbc",
) : SchemaProvider {

    private val delegate: StaticSchemaProvider by lazy(::loadSnapshot)

    override val isVolatile: Boolean get() = false

    override fun table(ref: TableRef): TableSchema? = delegate.table(ref)

    override fun search(fuzzy: String): List<TableRef> = delegate.search(fuzzy)

    private fun loadSnapshot(): StaticSchemaProvider {
        val meta = connection.metaData
        val tables = ArrayList<TableSchema>()
        meta.getTables(null, null, "%", arrayOf("TABLE", "VIEW")).use { rs ->
            while (rs.next()) {
                val catalogRaw = rs.getString("TABLE_CAT")
                val schemaRaw = rs.getString("TABLE_SCHEM")
                val nameRaw = rs.getString("TABLE_NAME")
                val catalog = catalogRaw?.takeUnless { it.isBlank() }
                val schema = schemaRaw?.takeUnless { it.isBlank() }
                val name = nameRaw
                tables += TableSchema(
                    table = TableRef(
                        raw = listOfNotNull(catalog, schema, name).joinToString("."),
                        canonical = listOfNotNull(catalog, schema, name).joinToString(".").lowercase(),
                        catalog = catalog,
                        schema = schema,
                        name = name,
                    ),
                    columns = readColumns(meta, catalogRaw, schemaRaw, nameRaw),
                )
            }
        }
        return StaticSchemaProvider(tables, defaultSearchPath = defaultSearchPath, id = id)
    }

    private fun readColumns(
        meta: java.sql.DatabaseMetaData,
        catalog: String?,
        schema: String?,
        table: String,
    ): List<ColumnSchema> {
        val columns = ArrayList<ColumnSchema>()
        meta.getColumns(catalog, schema, table, "%").use { rs ->
            while (rs.next()) {
                val nullable = when (rs.getInt("NULLABLE")) {
                    java.sql.DatabaseMetaData.columnNoNulls -> false
                    java.sql.DatabaseMetaData.columnNullable -> true
                    else -> null // columnNullableUnknown：没结论，不猜
                }
                columns += ColumnSchema(
                    name = rs.getString("COLUMN_NAME"),
                    type = rs.getString("TYPE_NAME"),
                    nullable = nullable,
                    ordinal = rs.getInt("ORDINAL_POSITION"),
                    comment = rs.getString("REMARKS")?.takeUnless { it.isBlank() },
                )
            }
        }
        return columns.sortedBy { it.ordinal ?: Int.MAX_VALUE }
    }
}
