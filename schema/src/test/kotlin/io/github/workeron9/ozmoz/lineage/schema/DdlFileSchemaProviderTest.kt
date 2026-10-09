package io.github.workeron9.ozmoz.lineage.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DdlFileSchemaProviderTest {

    @Test
    fun `基本 CREATE TABLE 提取列名类型与可空性`() {
        val provider = DdlFileSchemaProvider.parse(
            sqlTexts = arrayOf(
                """
                CREATE TABLE store_sales (
                    ss_sold_date_sk INT NOT NULL,
                    ss_quantity DECIMAL(7,2),
                    ss_coupon_amt NUMERIC,
                    PRIMARY KEY (ss_sold_date_sk)
                );
                """.trimIndent(),
            ),
        )

        val schema = provider.table(tableRef("store_sales"))!!
        assertEquals(listOf("ss_sold_date_sk", "ss_quantity", "ss_coupon_amt"), schema.columns.map { it.name })
        assertEquals("INT", schema.columns[0].type)
        assertEquals(false, schema.columns[0].nullable)
        assertEquals("DECIMAL (7, 2)", schema.columns[1].type) // JSqlParser ColDataType.toString 原文
        assertEquals(null, schema.columns[1].nullable) // 没写 NULL 约束 = 没结论，不猜
        assertEquals(null, schema.columns[2].nullable)
        assertEquals(listOf(1, 2, 3), schema.columns.map { it.ordinal })
    }

    @Test
    fun `schema 限定与引号标识符按折叠约定匹配`() {
        val provider = DdlFileSchemaProvider.parse(
            sqlTexts = arrayOf(
                """
                CREATE TABLE `app`.`Orders` (
                    "Id" BIGINT NOT NULL,
                    total INT NULL
                );
                """.trimIndent(),
            ),
        )

        val schema = provider.table(tableRef("ORDERS", schema = "APP"))!!
        assertEquals(listOf("Id", "total"), schema.columns.map { it.name })
        assertEquals(false, schema.columns[0].nullable)
        assertEquals(true, schema.columns[1].nullable)
    }

    @Test
    fun `多语句混合只收 CREATE TABLE 且先定义者胜`() {
        val provider = DdlFileSchemaProvider.parse(
            sqlTexts = arrayOf(
                """
                -- 注释与无关语句不应干扰
                INSERT INTO t VALUES (1);
                CREATE TABLE t (a INT);
                SELECT 1;
                CREATE TABLE t (a INT, b INT);
                CREATE TABLE IF NOT EXISTS u (x VARCHAR(32));
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("a"), provider.table(tableRef("t"))!!.columns.map { it.name })
        assertEquals("VARCHAR (32)", provider.table(tableRef("u"))!!.columns.single().type)
    }

    @Test
    fun `多个文本与文件入口等价`() {
        val text = "CREATE TABLE t (a INT);"
        val fromText = DdlFileSchemaProvider.parse(sqlTexts = arrayOf(text))
        val file = kotlin.io.path.createTempFile("ozml-schema-test", ".sql").also { it.toFile().deleteOnExit() }
        file.toFile().writeText(text)
        val fromFile = DdlFileSchemaProvider.fromFiles(paths = arrayOf(file))

        assertEquals(fromText.table(tableRef("t")), fromFile.table(tableRef("t")))
        assertEquals("ddl:${file.fileName}", fromFile.id)
    }

    @Test
    fun `整体解析失败抛异常而不是静默跳过`() {
        assertFailsWith<Exception> {
            DdlFileSchemaProvider.parse(sqlTexts = arrayOf("CREATE TABLE t (a INT); THIS IS NOT SQL"))
        }
    }

    @Test
    fun `查不到的表返回 null`() {
        val provider = DdlFileSchemaProvider.parse(sqlTexts = arrayOf("CREATE TABLE t (a INT)"))
        assertNull(provider.table(tableRef("missing")))
    }
}
