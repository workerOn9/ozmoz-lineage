package io.github.workeron9.ozmoz.lineage.schema

import io.github.workeron9.ozmoz.lineage.ir.ColumnSchema
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TableSchema
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.create.table.ColumnDefinition
import net.sf.jsqlparser.statement.create.table.CreateTable
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 从 DDL 文件（一个或多个含 `CREATE TABLE` 的 SQL 文本）构建的 [SchemaProvider]。
 *
 * 用 JSqlParser 逐语句解析（与解析引擎同一套折叠约定：**去引号 + 小写**），
 * 只收 `CREATE TABLE`：表名（可带 schema 限定）+ 列名 / 类型 / NOT NULL / 定义序。
 * 其余语句（INSERT / SELECT / 注释等）**静默跳过**——本提供方只关心表结构。
 *
 * 同名表重复定义时**先定义者胜**（确定性，见 [StaticSchemaProvider] 的折叠重名约束）。
 * 索引列的获取与复合主键等约束不在本轮范围（血缘只需「表有哪些列」）。
 */
public class DdlFileSchemaProvider private constructor(
    override val id: String,
    private val index: StaticSchemaProvider,
) : SchemaProvider by index {

    public companion object {

        /**
         * 解析一段或多段 SQL 文本。任何一段整体解析失败直接抛异常——
         * DDL 是权威元数据，**容错跳过违背 Never-wrong**（静默缺一张表比报错更糟）。
         */
        @JvmStatic
        public fun parse(id: String = "ddl", vararg sqlTexts: String): DdlFileSchemaProvider =
            DdlFileSchemaProvider(id, index = StaticSchemaProvider(parseTables(sqlTexts.toList())))

        /** 从文件读入（UTF-8）再 [parse]。 */
        @JvmStatic
        public fun fromFiles(id: String = "ddl", vararg paths: Path): DdlFileSchemaProvider =
            parse(id = paths.firstOrNull()?.let { "$id:${it.fileName}" } ?: id, sqlTexts = paths.map { it.readText() }.toTypedArray())

        /**
         * **容错**入口：与 [parse] 相同的提取逻辑，但单段文本整体解析失败时
         * **跳过该段**（不抛异常），其余段照常提取，「先定义者胜」按传入顺序。
         *
         * 用途：从 SQL 输入（目录 / 脚本）里顺手收集 `CREATE TABLE`——坏文件在
         * 读取侧已有告警（如 `ozml lineage` 对目录坏文件的 WARN），schema 收集
         * 不必二次硬失败。全部文本解析失败、或没有任何 `CREATE TABLE` 时返回
         * **null**，调用方回落到「无 schema」路径。
         *
         * 与 [parse] 的严格约定**并存不冲突**：显式提供 DDL（`--schema`）的场景
         * 仍必须用 [parse]——DDL 是权威元数据，静默缺一张表比报错更糟；
         * 本函数只用于「顺手收集」的 best-effort 场景，调用方必须意识到
         * 这里**可能缺表**（解析失败的段的表就缺了）。
         */
        @JvmStatic
        public fun parseTolerant(id: String, vararg sqlTexts: String): DdlFileSchemaProvider? {
            // 逐段收集、跨段去重（同名表先定义者胜；[parseTables] 的去重只在其单次调用内生效）。
            val tables = sqlTexts.asSequence()
                .mapNotNull { text -> runCatching { parseTables(listOf(text)) }.getOrNull() }
                .flatten()
                .distinctBy { it.id }
                .toList()
            if (tables.isEmpty()) return null
            return DdlFileSchemaProvider(id, index = StaticSchemaProvider(tables))
        }

        private fun parseTables(texts: List<String>): List<TableSchema> = texts.flatMap { text ->
            CCJSqlParserUtil.parseStatements(text).filterIsInstance<CreateTable>().map(::toTableSchema)
        }.distinctBy { it.id } // 同名重复定义：先定义者胜

        private fun toTableSchema(stmt: CreateTable): TableSchema {
            val table = stmt.table
            val nameRaw = table.name ?: ""
            val schemaRaw = table.schemaName?.takeUnless { it == "null" }
            val catalogRaw = table.databaseName?.takeUnless { it == "null" }
            val name = unquote(nameRaw)
            val schema = schemaRaw?.let(::unquote)
            val catalog = catalogRaw?.let(::unquote)
            val ref = TableRef(
                raw = table.fullyQualifiedName ?: nameRaw,
                canonical = listOfNotNull(catalog, schema, name).joinToString(".").lowercase(),
                catalog = catalog,
                schema = schema,
                name = name,
            )
            val columns = stmt.columnDefinitions.mapIndexed { i, def ->
                ColumnSchema(
                    name = unquote(def.columnName),
                    type = def.colDataType?.toString(),
                    nullable = nullability(def),
                    ordinal = i + 1,
                )
            }
            return TableSchema(ref, columns)
        }

        /** DDL 语句里写了 NULL / NOT NULL 才有结论；没写返回 null（不猜）。 */
        private fun nullability(def: ColumnDefinition): Boolean? {
            val specs = def.columnSpecs ?: return null
            val joined = specs.joinToString(" ") { it.uppercase() }
            return when {
                joined.contains("NOT NULL") -> false
                specs.any { it.equals("NULL", ignoreCase = true) } -> true
                else -> null
            }
        }

        /** 去包裹引号（`"T"` / `` `t` `` / `[t]`），保留内部原始大小写。 */
        private fun unquote(raw: String): String = raw.trim().trim('"', '`', '[', ']')
    }
}
