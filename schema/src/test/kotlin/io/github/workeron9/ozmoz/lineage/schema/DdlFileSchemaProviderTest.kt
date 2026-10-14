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
    fun `parseTolerant 跳过解析失败的段保留其余且先定义者胜`() {
        val provider = DdlFileSchemaProvider.parseTolerant(
            id = "ddl:auto",
            sqlTexts = arrayOf(
                "CREATE TABLE t (a INT);", // 正常
                "THIS IS NOT SQL", // 整段失败 → 跳过
                "CREATE TABLE t (a INT, b INT); CREATE TABLE u (x INT);", // 同名表后定义，不覆盖
            ),
        )!!

        assertEquals("ddl:auto", provider.id)
        assertEquals(listOf("a"), provider.table(tableRef("t"))!!.columns.map { it.name })
        assertEquals(listOf("x"), provider.table(tableRef("u"))!!.columns.map { it.name })
    }

    @Test
    fun `parseTolerant 同一段内的同名表也先定义者胜`() {
        val provider = DdlFileSchemaProvider.parseTolerant(
            id = "ddl:auto",
            sqlTexts = arrayOf("CREATE TABLE t (a INT); CREATE TABLE t (a INT, b INT);"),
        )!!

        assertEquals(listOf("a"), provider.table(tableRef("t"))!!.columns.map { it.name })
    }

    @Test
    fun `parseTolerant 没有可提取的表返回 null`() {
        assertNull(DdlFileSchemaProvider.parseTolerant(id = "ddl:auto", sqlTexts = arrayOf("SELECT 1")))
        assertNull(DdlFileSchemaProvider.parseTolerant(id = "ddl:auto", sqlTexts = arrayOf("THIS IS NOT SQL")))
        assertNull(DdlFileSchemaProvider.parseTolerant(id = "ddl:auto"))
    }

    @Test
    fun `查不到的表返回 null`() {
        val provider = DdlFileSchemaProvider.parse(sqlTexts = arrayOf("CREATE TABLE t (a INT)"))
        assertNull(provider.table(tableRef("missing")))
    }

    @Test
    fun `CREATE VIEW 显式列清单被收录 类型不猜`() {
        val provider = DdlFileSchemaProvider.parse(
            sqlTexts = arrayOf(
                """
                CREATE VIEW v_prd (group_id, product_id, prd_name) AS
                SELECT t1.group_id, t1.prdid, t1.prd_name FROM prd_config t1;
                """.trimIndent(),
            ),
        )

        val schema = provider.table(tableRef("v_prd"))!!
        assertEquals(listOf("group_id", "product_id", "prd_name"), schema.columns.map { it.name })
        assertEquals(listOf(1, 2, 3), schema.columns.map { it.ordinal })
        assertNull(schema.columns[0].type) // 视图列类型由引擎决定，DDL 里写不出 → 不猜
    }

    @Test
    fun `CREATE VIEW 无显式列清单 从 select 输出项推导列名`() {
        val provider = DdlFileSchemaProvider.parse(sqlTexts = arrayOf("CREATE VIEW v AS SELECT a, b AS bee FROM t"))
        assertEquals(listOf("a", "bee"), provider.table(tableRef("v"))!!.columns.map { it.name })
    }

    @Test
    fun `CREATE VIEW 有输出项定不出名字时整张不收录`() {
        val provider = DdlFileSchemaProvider.parse(
            sqlTexts = arrayOf(
                // count(*) 无别名 → 列清单推不全 → 跳过（不完整清单会错误证伪真实列）
                "CREATE VIEW v AS SELECT a, count(*) FROM t",
                "CREATE TABLE keep (x INT)",
            ),
        )

        assertNull(provider.table(tableRef("v")))
        assertEquals(listOf("x"), provider.table(tableRef("keep"))!!.columns.map { it.name })
    }

    @Test
    fun `CREATE VIEW 推导出重复列名时整张不收录`() {
        // TableSchema 要求列名唯一；重复则跳过，不让 schema 装载整体抛异常。
        val provider = DdlFileSchemaProvider.parse(sqlTexts = arrayOf("CREATE VIEW v AS SELECT a, a FROM t"))
        assertNull(provider.table(tableRef("v")))
    }

    @Test
    fun `CREATE VIEW 与同名表先定义者胜`() {
        val provider = DdlFileSchemaProvider.parse(
            sqlTexts = arrayOf("CREATE TABLE t (a INT)", "CREATE VIEW t AS SELECT 1 AS b"),
        )
        assertEquals(listOf("a"), provider.table(tableRef("t"))!!.columns.map { it.name })
    }
}
