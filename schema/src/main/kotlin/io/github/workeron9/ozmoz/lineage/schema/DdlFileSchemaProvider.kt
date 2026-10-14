package io.github.workeron9.ozmoz.lineage.schema

import io.github.workeron9.ozmoz.lineage.ir.ColumnSchema
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TableSchema
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.create.table.ColumnDefinition
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SelectItem
import net.sf.jsqlparser.statement.select.SetOperationList
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 从 DDL 文件（一个或多个含 `CREATE TABLE` / `CREATE VIEW` 的 SQL 文本）构建的
 * [SchemaProvider]。
 *
 * 用 JSqlParser 逐语句解析（与解析引擎同一套折叠约定：**去引号 + 小写**），
 * 收 `CREATE TABLE`（表名可带 schema 限定 + 列名 / 类型 / NOT NULL / 定义序）与
 * `CREATE VIEW`（列名，类型 / 可空性不猜）。其余语句（INSERT / SELECT / 注释等）
 * **静默跳过**——本提供方只关心表结构。
 *
 * 视图收进来很关键：视图不在 schema 里时，血缘侧无法证伪它的列，任何裸列名都会
 * 把它算作候选 → 歧义 → 丢边（见 [io.github.workeron9.ozmoz.lineage.ir.TableSchema]）。
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
         * 用途：从 SQL 输入（目录 / 脚本）里顺手收集 `CREATE TABLE` / `CREATE VIEW`
         * ——坏文件在读取侧已有告警（如 `ozml lineage` 对目录坏文件的 WARN），
         * schema 收集不必二次硬失败。全部文本解析失败、或没有任何 `CREATE TABLE` /
         * `CREATE VIEW` 时返回 **null**，调用方回落到「无 schema」路径。
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
            CCJSqlParserUtil.parseStatements(text).mapNotNull(::toTableSchema)
        }.distinctBy { it.id } // 同名重复定义：先定义者胜

        /** `CREATE TABLE` / `CREATE VIEW` → schema；其余语句不给元数据（返回 null）。 */
        private fun toTableSchema(stmt: Statement): TableSchema? = when (stmt) {
            is CreateTable -> TableSchema(
                table = tableRefOf(stmt.table),
                columns = stmt.columnDefinitions.mapIndexed { i, def ->
                    ColumnSchema(
                        name = unquote(def.columnName),
                        type = def.colDataType?.toString(),
                        nullable = nullability(def),
                        ordinal = i + 1,
                    )
                },
            )

            is CreateView -> toViewSchema(stmt)
            else -> null
        }

        /**
         * `CREATE VIEW` → 列清单（类型 / 可空性未知，一律 null）。
         *
         * 列名来源：显式列清单（`CREATE VIEW v (a, b) AS …`）优先，其次从 select 的
         * 输出项推导（别名优先，裸列名次之）。**推导不出完整列清单**（有输出项定不出
         * 名字 / `*` / 认不出的形状）或列名有重复 → 返回 null 跳过该视图：不完整的列
         * 清单比没有更危险——会错误证伪真实存在的列，把裸列名解析到别的来源。
         */
        private fun toViewSchema(view: CreateView): TableSchema? {
            val names = view.columnNames?.map { unquote(it.columnName) }
                ?: derivedColumnNames(view.select)
                ?: return null
            if (names.isEmpty() || names.any { it.isBlank() }) return null
            if (names.map { it.lowercase() }.toSet().size != names.size) return null
            return TableSchema(
                table = tableRefOf(view.view),
                columns = names.mapIndexed { i, name -> ColumnSchema(name = name, ordinal = i + 1) },
            )
        }

        /** select 的输出列名；任一输出项定不出名字 → null（不猜）。集合运算以首分支为准。 */
        private fun derivedColumnNames(select: Select?): List<String>? = when (select) {
            is PlainSelect -> {
                val names = select.selectItems.map(::nameOf)
                if (names.any { it == null }) null else names.filterNotNull()
            }

            is ParenthesedSelect -> derivedColumnNames(select.select)
            is SetOperationList -> select.selects.firstOrNull()?.let(::derivedColumnNames)
            else -> null
        }

        /** 输出项的名字：别名优先，裸列名次之，其余（函数 / `*` …）定不出 → null。 */
        private fun nameOf(item: SelectItem<*>): String? {
            item.alias?.name?.let { return unquote(it) }
            return (item.expression as? Column)?.let { unquote(it.columnName) }
        }

        private fun tableRefOf(table: Table): TableRef {
            val nameRaw = table.name ?: ""
            val schemaRaw = table.schemaName?.takeUnless { it == "null" }
            val catalogRaw = table.databaseName?.takeUnless { it == "null" }
            val name = unquote(nameRaw)
            val schema = schemaRaw?.let(::unquote)
            val catalog = catalogRaw?.let(::unquote)
            return TableRef(
                raw = table.fullyQualifiedName ?: nameRaw,
                canonical = listOfNotNull(catalog, schema, name).joinToString(".").lowercase(),
                catalog = catalog,
                schema = schema,
                name = name,
            )
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
