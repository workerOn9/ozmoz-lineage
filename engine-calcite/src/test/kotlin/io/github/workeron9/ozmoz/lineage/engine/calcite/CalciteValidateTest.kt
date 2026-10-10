package io.github.workeron9.ozmoz.lineage.engine.calcite

import io.github.workeron9.ozmoz.lineage.engine.SchemaLookup
import io.github.workeron9.ozmoz.lineage.engine.ValidateRequest
import io.github.workeron9.ozmoz.lineage.ir.ColumnSchema
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.Severity
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TableSchema
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider
import io.github.workeron9.ozmoz.lineage.schema.StaticSchemaProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * `CalciteEngine.validate`（[Feature.VALIDATE_SCHEMA]，ADR-0013）：
 * Calcite `SqlValidator` + 按名懒查的 catalog——合法 SQL 通过、未知表 / 未知列 /
 * 歧义 / 类型不匹配给权威诊断、多语句逐条互不阻断、DDL 跳过、Never-wrong 口径
 *（schema 缺失 / SQL 为空 / 方言未注册 → Unknown）。
 */
class CalciteValidateTest {

    private val engine = CalciteEngine()

    /** schema 装配惯例：`SchemaProvider` 在装配点 SAM 转换喂入（零胶水）。 */
    private val provider: SchemaProvider = StaticSchemaProvider(
        tables = listOf(
            TableSchema.of(
                TableRef(raw = "orders", canonical = "orders", name = "orders"),
                ColumnSchema(name = "id", type = "INTEGER", nullable = false),
                ColumnSchema(name = "customer_id", type = "BIGINT"),
                ColumnSchema(name = "amount", type = "DECIMAL(10,2)"),
            ),
            TableSchema.of(
                TableRef(raw = "sales.customers", canonical = "sales.customers", catalog = null, schema = "sales", name = "customers"),
                ColumnSchema(name = "id", type = "INTEGER", nullable = false),
                ColumnSchema(name = "name", type = "VARCHAR(50)"),
            ),
            TableSchema.of(TableRef(raw = "bare", canonical = "bare", name = "bare")),
        ),
    )

    private fun validate(sql: String, dialect: String? = null): List<io.github.workeron9.ozmoz.lineage.ir.Diagnostic> =
        when (val result = engine.validate(ValidateRequest(sql = sql, dialect = dialect, schema = provider::table))) {
            is Resolved.Known -> result.value
            is Resolved.Unknown -> throw AssertionError("期望校验跑成，实际 Unknown: ${result.reason}")
        }

    private fun validateUnknown(sql: String, dialect: String? = null): Resolved.Unknown {
        val result = engine.validate(ValidateRequest(sql = sql, dialect = dialect, schema = provider::table))
        assertIs<Resolved.Unknown>(result)
        return result
    }

    // ————— 通过 —————

    @Test
    fun `合法 SQL 通过校验 无诊断`() {
        assertTrue(validate("SELECT id, amount FROM orders WHERE id > 10").isEmpty())
    }

    @Test
    fun `别名与限定列通过校验`() {
        assertTrue(validate("SELECT o.amount FROM orders o").isEmpty())
    }

    @Test
    fun `限定名 schema_table 通过校验`() {
        assertTrue(validate("SELECT c.name FROM sales.customers c").isEmpty())
    }

    @Test
    fun `大小写折叠 校验器按 liberal matcher 匹配`() {
        assertTrue(validate("SELECT ID, AMOUNT FROM Orders").isEmpty())
    }

    @Test
    fun `mysql 方言反引号查询通过校验`() {
        assertTrue(validate("SELECT `amount` FROM `orders`", dialect = "mysql").isEmpty())
    }

    @Test
    fun `合法 INSERT 通过校验`() {
        assertTrue(validate("INSERT INTO orders (id, amount) VALUES (1, 2.5)").isEmpty())
    }

    // ————— 诊断 —————

    @Test
    fun `未知表报 ERROR 带位置`() {
        val diagnostics = validate("SELECT * FROM nope")
        assertEquals(1, diagnostics.size)
        val d = diagnostics.first()
        assertEquals(CalciteValidator.CODE_VALIDATE_ERROR, d.code)
        assertEquals(Severity.ERROR, d.severity)
        assertContains(d.message.lowercase(), "nope")
        // 位置：Calcite 校验异常带行 / 列，换算成 span
        assertTrue(d.span != null, "未知表的诊断应带位置")
    }

    @Test
    fun `未知列报 ERROR`() {
        val diagnostics = validate("SELECT foo FROM orders")
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.all { it.code == CalciteValidator.CODE_VALIDATE_ERROR && it.severity == Severity.ERROR })
        assertContains(diagnostics.first().message.lowercase(), "foo")
    }

    @Test
    fun `裸列歧义报 ERROR`() {
        val diagnostics = validate("SELECT id FROM orders o, orders o2")
        assertTrue(diagnostics.isNotEmpty())
        assertEquals(Severity.ERROR, diagnostics.first().severity)
    }

    @Test
    fun `函数参数个数不符报 ERROR`() {
        // Calcite 1.42 的比较运算有隐式强转（INTEGER = CHAR 不报错）；
        // 类型层面的硬校验落在「参数个数」这类无强转可救的错上。
        val diagnostics = validate("SELECT ABS(id, 2) FROM orders")
        assertTrue(diagnostics.isNotEmpty())
        assertEquals(Severity.ERROR, diagnostics.first().severity)
    }

    @Test
    fun `INSERT 目标列数不符报 ERROR`() {
        val diagnostics = validate("INSERT INTO orders VALUES (1)")
        assertTrue(diagnostics.isNotEmpty())
        assertEquals(Severity.ERROR, diagnostics.first().severity)
    }

    // ————— 语句组合 —————

    @Test
    fun `多语句逐条校验 互不阻断`() {
        val diagnostics = validate("SELECT * FROM nope;\nSELECT id FROM orders")
        assertEquals(1, diagnostics.size)
        assertContains(diagnostics.first().message.lowercase(), "nope")
    }

    @Test
    fun `DDL 语句跳过 不产诊断`() {
        assertTrue(validate("CREATE TABLE t (a INT)").isEmpty())
    }

    @Test
    fun `解析失败给可背书的 ERROR 诊断（Known 而非 Unknown）`() {
        val result = engine.validate(ValidateRequest(sql = "SELECT FROM FROM", schema = provider::table))
        assertIs<Resolved.Known<*>>(result)
        val diagnostics = result.value as List<*>
        assertTrue(diagnostics.isNotEmpty())
        val d = diagnostics.first() as io.github.workeron9.ozmoz.lineage.ir.Diagnostic
        assertEquals(CalciteEngine.CODE_PARSE_ERROR, d.code)
        assertEquals(Severity.ERROR, d.severity)
    }

    // ————— 没跑成 → Unknown（Never-wrong） —————

    @Test
    fun `schema 未提供 返回 Unknown`() {
        val result = engine.validate(ValidateRequest(sql = "SELECT 1", schema = null))
        assertIs<Resolved.Unknown>(result)
        assertContains(result.reason, "schema")
    }

    @Test
    fun `SQL 为空 返回 Unknown`() {
        val result = engine.validate(ValidateRequest(sql = "   ", schema = provider::table))
        assertIs<Resolved.Unknown>(result)
    }

    @Test
    fun `方言未注册 返回 Unknown`() {
        val result = engine.validate(ValidateRequest(sql = "SELECT 1", dialect = "redshift", schema = provider::table))
        assertIs<Resolved.Unknown>(result)
        assertContains(result.reason, "redshift")
    }

    // ————— 边界语义 —————

    @Test
    fun `列清单为空的表按未收录处理 免得空行类型产假误差`() {
        val diagnostics = validate("SELECT * FROM bare")
        assertTrue(diagnostics.isNotEmpty())
        assertEquals(Severity.ERROR, diagnostics.first().severity)
    }

    @Test
    fun `错 schema 前缀如实 not found（限定名各自成立 不放宽到裸名）`() {
        val diagnostics = validate("SELECT id FROM noschema.orders")
        assertTrue(diagnostics.isNotEmpty())
        assertEquals(Severity.ERROR, diagnostics.first().severity)
        assertContains(diagnostics.first().message.lowercase(), "noschema")
    }

    @Test
    fun `未收录 schema 限定名的表报 ERROR`() {
        val diagnostics = validate("SELECT * FROM sales.nope")
        assertTrue(diagnostics.isNotEmpty())
        assertEquals(Severity.ERROR, diagnostics.first().severity)
    }

    // ————— SchemaLookup SAM 装配 —————

    @Test
    fun `SchemaLookup 直接装配（不经 SchemaProvider）`() {
        val lookup = SchemaLookup { ref ->
            if (ref.name.equals("t", ignoreCase = true)) {
                TableSchema.of(TableRef(raw = "t", canonical = "t", name = "t"), ColumnSchema(name = "a", type = "INT"))
            } else {
                null
            }
        }
        val result = engine.validate(ValidateRequest(sql = "SELECT a FROM t", schema = lookup))
        assertIs<Resolved.Known<*>>(result)
        assertTrue((result.value as List<*>).isEmpty())
    }
}
