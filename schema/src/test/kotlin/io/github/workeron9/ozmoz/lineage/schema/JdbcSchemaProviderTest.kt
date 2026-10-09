package io.github.workeron9.ozmoz.lineage.schema

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * JDBC 提供方用 **H2 内存库**实测（H2 未加引号的标识符默认大写存储，
 * 正好验证「载入时统一折叠、不依赖数据库大小写习惯」这条设计）。
 */
class JdbcSchemaProviderTest {

    private fun h2(): java.sql.Connection =
        DriverManager.getConnection("jdbc:h2:mem:ozml_schema_test;DB_CLOSE_DELAY=-1", "sa", "")

    private fun provider(connection: java.sql.Connection = h2()) =
        JdbcSchemaProvider(connection, defaultSearchPath = setOf("PUBLIC"))

    init {
        // IF NOT EXISTS：JUnit 每个用例新建测试类实例，init 会重复执行，
        // 而 DB_CLOSE_DELAY=-1 让同 JVM 内的库跨连接存活。
        h2().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS CUSTOMERS (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL, AGE INT)")
                statement.execute("CREATE TABLE IF NOT EXISTS \"lowercase_t\" (\"flag\" BOOLEAN)")
                statement.execute("CREATE VIEW IF NOT EXISTS customer_names AS SELECT NAME FROM CUSTOMERS")
            }
        }
    }

    @Test
    fun `表与列大小写不敏感命中`() {
        val schema = provider().table(tableRef("customers"))!!
        assertEquals(listOf("ID", "NAME", "AGE"), schema.columns.map { it.name })
        assertEquals("BIGINT", schema.columns[0].type)
        assertEquals(false, schema.columns[1].nullable)
        assertEquals(true, schema.columns[2].nullable)
        assertEquals(listOf(1, 2, 3), schema.columns.map { it.ordinal })
    }

    @Test
    fun `加引号的原始小写名也能命中`() {
        val schema = provider().table(tableRef("LOWERCASE_T"))!!
        assertEquals(listOf("flag"), schema.columns.map { it.name })
    }

    @Test
    fun `VIEW 也在收录范围`() {
        assertEquals(listOf("NAME"), provider().table(tableRef("customer_names"))!!.columns.map { it.name })
    }

    @Test
    fun `search 大小写不敏感`() {
        assertEquals(
            listOf("CUSTOMER_NAMES", "CUSTOMERS"),
            provider().search("customer").map { it.name },
        )
    }

    @Test
    fun `查不到的表返回 null`() {
        assertNull(provider().table(tableRef("missing")))
    }

    @Test
    fun `快照语义不随源库变化且不可变标记为 false`() {
        val connection = h2()
        val snapshot = provider(connection)
        assertFalse(snapshot.isVolatile)
        assertEquals("BIGINT", snapshot.table(tableRef("customers"))!!.columns[0].type)

        // 快照已载入：事后改库不影响结果。
        connection.createStatement().use { it.execute("ALTER TABLE CUSTOMERS ADD COLUMN extra INT") }
        assertEquals(listOf("ID", "NAME", "AGE"), snapshot.table(tableRef("customers"))!!.columns.map { it.name })
    }
}
