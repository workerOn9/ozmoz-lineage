package io.github.workeron9.ozmoz.lineage.engine.calcite

import io.github.workeron9.ozmoz.lineage.engine.SchemaLookup
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TableSchema
import org.apache.calcite.avatica.util.Casing
import org.apache.calcite.config.CalciteConnectionConfig
import org.apache.calcite.config.CalciteConnectionConfigImpl
import org.apache.calcite.config.CalciteConnectionProperty
import org.apache.calcite.config.Lex
import org.apache.calcite.jdbc.CalciteSchema
import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.prepare.CalciteCatalogReader
import org.apache.calcite.adapter.java.JavaTypeFactory
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rel.type.RelDataTypeSystem
import org.apache.calcite.runtime.CalciteContextException
import org.apache.calcite.schema.Schema
import org.apache.calcite.schema.Table
import org.apache.calcite.schema.impl.AbstractSchema
import org.apache.calcite.schema.impl.AbstractTable
import org.apache.calcite.schema.lookup.Lookup
import org.apache.calcite.schema.lookup.Named
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.parser.ddl.SqlDdlParserImpl
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.validate.SqlConformanceEnum
import org.apache.calcite.sql.validate.SqlValidator
import org.apache.calcite.sql.validate.SqlValidatorUtil
import java.util.Properties

/**
 * Calcite **`SqlValidator` 装配**（[Feature.VALIDATE_SCHEMA]，ADR-0013）。
 *
 * 关键事实（probe 实测）：`SqlValidator` 的 FROM 表名解析走
 * `EmptyScope.resolveTable → catalogReader.getRootSchema()` 的 **schema 树遍历**
 *（CalciteSchema.subSchema → table），**不走** `catalogReader.getTable(names)`。
 * 所以 seam 不在 catalog reader，而在 **schema 树**：给校验器一棵
 * **按名懒查**的 schema——[LazySchema] 把 `SchemaLookup`（血缘侧同款 SPI）
 * 包成 Calcite `Schema`，表 / 子 schema 都是**按名懒查**（Lookup.get 时才查一次）。
 *
 * 表名解析：**精确限定优先，Calcite 自身的解析规则管放宽**——根层不把「能当
 * 裸表命中的名字」抢成 schema（steal-guard），限定名必须各自成立（schema 查不到
 * 表就如实 not found，不放宽到裸名——与「歧义不猜」同口径）。
 *
 * 类型映射是**字典不是推导**（[CalciteTypes]）：认不出的类型一律 `ANY`
 *（与任何类型兼容，不产假阳性）；可空性未知按 nullable——同理免假报。
 *
 * 引擎私有类型（`SqlNode` / `RelDataType` / `Lookup`）只在 engine-calcite
 * 内部出现，不进公共签名。
 */
internal object CalciteValidator {

    /** 诊断码：稳定契约（调用方按它分支，不要按 message 文案分支）。 */
    public const val CODE_VALIDATE_ERROR: String = "calcite.validate_error"

    /**
     * 校验单条语句，返回该语句的诊断列表（通过 = 空列表）。
     *
     * 不支持的语句种类（纯 DDL / MERGE 等校验器不说话的）**跳过不产诊断**
     *（Never-wrong 镜像：不造能跑但错的结论）。
     */
    public fun validate(sql: String, node: SqlNode, lookup: SchemaLookup): List<Diagnostic> {
        val kind = node.kind
        if (kind !in SqlKind.QUERY && kind !in SqlKind.DML) return emptyList()

        val typeFactory = JavaTypeFactoryImpl(RelDataTypeSystem.DEFAULT)
        val reader = LookupCatalogReader(typeFactory, lookup)
        val config = SqlValidator.Config.DEFAULT
            .withIdentifierExpansion(true)
            .withConformance(SqlConformanceEnum.DEFAULT)
        val validator = SqlValidatorUtil.newValidator(
            SqlStdOperatorTable.instance(),
            reader,
            typeFactory,
            config,
        )
        return try {
            validator.validate(node)
            emptyList()
        } catch (e: CalciteContextException) {
            listOf(
                Diagnostic.error(
                    CODE_VALIDATE_ERROR,
                    firstLine(e.message) ?: "校验失败",
                    CalcitePositions.spanOf(sql, CalcitePositions.posOf(e)),
                ),
            )
        } catch (e: RuntimeException) {
            listOf(Diagnostic.error(CODE_VALIDATE_ERROR, firstLine(e.message) ?: "校验失败: ${e.javaClass.simpleName}"))
        }
    }

    /** 校验失败的第一行消息（Calcite 的异常 message 首行即用户可读诊断）。 */
    private fun firstLine(message: String?): String? =
        message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * **按名懒查的 catalog reader**：继承 [CalciteCatalogReader]（它实现
 * `Prepare.CatalogReader` = `SqlValidatorCatalogReader` + `RelOptSchema`——
 * 校验器把普通 `Table` 包成 namespace 时要 `unwrap(RelOptSchema)`，基类已给），
 * 只把**根 schema** 换成 [LazySchema]（懒查），并把大小写匹配关掉
 *（大小写折叠交给 `SchemaProvider` 实现的既有约定：去引号 + 小写）。
 */
internal class LookupCatalogReader(
    typeFactory: RelDataTypeFactory,
    lookup: SchemaLookup,
) : CalciteCatalogReader(
    CalciteSchema.createRootSchema(false, false, "root", LazySchema(emptyList(), lookup)),
    emptyList(),
    typeFactory,
    CalciteConnectionConfigImpl(Properties())
        .set(CalciteConnectionProperty.CASE_SENSITIVE, "false"),
)

/**
 * **懒查 schema**：[prefix] 是已解析的命名空间前缀（`[]` = 根 / `[schema]` /
 * `[catalog, schema]`），表与子 schema 都是**按名懒查**：
 *
 * - `tables()`：按 `prefix + [name]` 查 [SchemaLookup]；命中但**列清单为空**按
 *   未收录处理（schema 对它没有可信的列定义，不给校验器喂空行类型——`SELECT *`
 *   会产「no columns」类假误差）；
 * - `subSchemas()`：**只在根层垫限定名路径**（steal-guard + 不深垫，理由见方法内注释）；
 * - `getNames(...)`：空集——懒查 SPI 没有清单，补全类功能如实不给（校验不用它）。
 */
internal class LazySchema(
    private val prefix: List<String>,
    private val lookup: SchemaLookup,
) : AbstractSchema() {

    override fun tables(): Lookup<Table> = object : Lookup<Table> {
        override fun get(name: String): Table? = tableOrNull(name)

        override fun getIgnoreCase(name: String): Named<Table>? {
            val table = tableOrNull(name) ?: return null
            return Named(name.lowercase(), table)
        }

        override fun getNames(pattern: org.apache.calcite.schema.lookup.LikePattern): Set<String> = emptySet()
    }

    override fun subSchemas(): Lookup<Schema> = object : Lookup<Schema> {
        override fun get(name: String): Schema? {
            // 子 schema 只在**根层**垫脚（限定 `schema.table` 的第一段）：
            //  - steal-guard：能当裸表命中的名字不能被抢成 schema；
            //  - 已在 schema 层内（prefix 非空）不再深垫——否则 schema 段会被
            //    下一段抢走（probe 实测：`sales.customers` 的 customers 被
            //    抢成 catalog 层，永远查不到表）；
            //  - 3 段 catalog 限定（db.schema.table）v1 不支持，第一段吃不上。
            //    （不可枚举的懒查 SPI 分不清「catalog 段」与「表名」，宁可浅。）
            if (prefix.isNotEmpty()) return null
            if (lookup.table(refOf(listOf(name))) != null) return null
            return LazySchema(listOf(name), lookup)
        }

        override fun getIgnoreCase(name: String): Named<Schema>? {
            val schema = get(name) ?: return null
            return Named(name.lowercase(), schema)
        }

        override fun getNames(pattern: org.apache.calcite.schema.lookup.LikePattern): Set<String> = emptySet()
    }

    private fun tableOrNull(name: String): Table? {
        if (prefix.size >= 3) return null
        val ts = lookup.table(refOf(prefix + name)) ?: return null
        return if (ts.columns.isEmpty()) null else LookupTable(ts)
    }

    private fun refOf(parts: List<String>): TableRef {
        val raw = parts.joinToString(".")
        return TableRef(
            raw = raw,
            canonical = raw.lowercase(),
            catalog = parts.getOrNull(parts.size - 3),
            schema = parts.getOrNull(parts.size - 2),
            name = parts.last(),
        )
    }
}

/**
 * 一张已解析的表 → 校验器可消费的 [Table]：行类型从 [TableSchema.columns] 建
 *（见 [CalciteTypes]），其余交给 [AbstractTable] 的默认（无约束 / 非临时）——
 * **校验器只该对 schema 说的事说话**。
 */
internal class LookupTable(private val schema: TableSchema) : AbstractTable() {

    override fun getRowType(typeFactory: RelDataTypeFactory): RelDataType = buildRowType(schema, typeFactory)
}

/**
 * `ColumnSchema.type`（字符串）→ [SqlTypeName] 的**字典映射**：只收常见类型，
 * 认不出（`typedef` / 方言花名 / 复合类型）一律 `ANY`——ANY 与任何类型兼容，
 * 校验器不会因「我们不认识这个类型」而假报类型不匹配。
 */
internal object CalciteTypes {

    private val NAMES: Map<String, SqlTypeName> = mapOf(
        "VARCHAR" to SqlTypeName.VARCHAR,
        "CHAR" to SqlTypeName.CHAR,
        "CHARACTER" to SqlTypeName.CHAR,
        "TEXT" to SqlTypeName.VARCHAR,
        "STRING" to SqlTypeName.VARCHAR,
        "CLOB" to SqlTypeName.VARCHAR,
        "INT" to SqlTypeName.INTEGER,
        "INTEGER" to SqlTypeName.INTEGER,
        "BIGINT" to SqlTypeName.BIGINT,
        "SMALLINT" to SqlTypeName.SMALLINT,
        "TINYINT" to SqlTypeName.TINYINT,
        "DECIMAL" to SqlTypeName.DECIMAL,
        "NUMERIC" to SqlTypeName.DECIMAL,
        "NUMBER" to SqlTypeName.DECIMAL,
        "FLOAT" to SqlTypeName.DOUBLE,
        "DOUBLE" to SqlTypeName.DOUBLE,
        "REAL" to SqlTypeName.REAL,
        "BOOLEAN" to SqlTypeName.BOOLEAN,
        "BOOL" to SqlTypeName.BOOLEAN,
        "DATE" to SqlTypeName.DATE,
        "TIME" to SqlTypeName.TIME,
        "TIMESTAMP" to SqlTypeName.TIMESTAMP,
        "DATETIME" to SqlTypeName.TIMESTAMP,
        "BLOB" to SqlTypeName.VARBINARY,
        "BINARY" to SqlTypeName.BINARY,
        "VARBINARY" to SqlTypeName.VARBINARY,
        "BYTEA" to SqlTypeName.VARBINARY,
        "BYTES" to SqlTypeName.VARBINARY,
        "JSON" to SqlTypeName.ANY,
        "JSONB" to SqlTypeName.ANY,
        "UUID" to SqlTypeName.ANY,
    )

    /** 取 `VARCHAR(50)` 的基名再查表；null / 认不出 → [SqlTypeName.ANY]。 */
    public fun of(raw: String?): SqlTypeName {
        val base = raw?.trim()?.uppercase()?.substringBefore('(')?.trim().orEmpty()
        return NAMES[base] ?: SqlTypeName.ANY
    }
}

/** 建行类型：列名保原样（校验器按 case-insensitive matcher 折叠），可空性未知 → nullable。 */
internal fun buildRowType(schema: TableSchema, typeFactory: RelDataTypeFactory): RelDataType {
    val builder = typeFactory.builder()
    for (col in schema.columns) {
        val type = typeFactory.createTypeWithNullability(
            typeFactory.createSqlType(CalciteTypes.of(col.type)),
            col.nullable != false,
        )
        builder.add(col.name, type)
    }
    return builder.build()
}
