package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionKind
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionProfile
import io.github.workeron9.ozmoz.lineage.desktop.model.DesktopStoreState
import io.github.workeron9.ozmoz.lineage.desktop.model.ThemeMode
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D2 连接页的**真实验证**：进程内 JDBC（SQLite 随包）→ 元数据 → 呈现状态。
 * 不是 mock：建一张真表，再走 `ConnectionGateway.openBlocking` 读回来。
 */
class ConnectionGatewayTest {

    private val tempDir = Files.createTempDirectory("ozml-desktop-conn")

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private fun sqliteProfile(): ConnectionProfile = ConnectionProfile(
        id = "test-sqlite",
        name = "测试库",
        kind = ConnectionKind.SQLITE,
        database = tempDir.resolve("test.db").toString(),
    )

    @Test
    fun opensSqliteAndReadsTablesAndColumns() {
        val profile = sqliteProfile()
        // 先用同一驱动建表（桌面模块的 SQLite 驱动来自 jvmMain 依赖）。
        java.sql.DriverManager.getConnection(profile.resolvedUrl()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL)")
                statement.execute("CREATE TABLE customers (id INTEGER, name TEXT)")
            }
        }

        val (result, snapshot) = ConnectionGateway.openBlocking(profile, "")
        assertTrue(result.ok, "SQLite 连接应该成功：${result.message}")
        assertNotNull(snapshot)
        val names = snapshot.tables.map { it.name }.toSet()
        assertTrue("orders" in names, "应该读到 orders：$names")
        assertTrue("customers" in names, "应该读到 customers：$names")

        val orders = snapshot.columns.getValue("orders")
        assertEquals(listOf("id", "amount"), orders.map { it.name })
        assertEquals(false, orders.first { it.name == "amount" }.nullable, "NOT NULL 要如实给出 false")
    }

    @Test
    fun driverPlanOnlyOffersKnownDownloadsWhenDriverIsMissing() {
        // SQLite 随包 → 不缺，不给下载按钮（不许对已能用的驱动喊「下载」）。
        assertNull(ConnectionGateway.driverPlan(sqliteProfile()))

        val postgres = ConnectionProfile(
            id = "p", name = "pg", kind = ConnectionKind.POSTGRESQL,
            host = "127.0.0.1", port = "1", database = "x",
        )
        assertEquals("PostgreSQL", ConnectionGateway.driverPlan(postgres)?.name)

        val mysql = ConnectionProfile(id = "m", name = "my", kind = ConnectionKind.MYSQL, host = "h", database = "d")
        assertEquals("MySQL", ConnectionGateway.driverPlan(mysql)?.name)

        val duckdb = ConnectionProfile(id = "d", name = "duck", kind = ConnectionKind.DUCKDB, database = "/tmp/x.duckdb")
        assertEquals("DuckDB", ConnectionGateway.driverPlan(duckdb)?.name)

        // 没有已知下载源：如实返回 null（界面只留「手动放 jar」的说明）。
        val custom = ConnectionProfile(id = "c", name = "x", kind = ConnectionKind.CUSTOM, jdbcUrl = "jdbc:oracle:thin:@h:1521:x")
        assertNull(ConnectionGateway.driverPlan(custom))
    }

    @Test
    fun failedConnectionReportsReasonInsteadOfThrowing() {
        val profile = ConnectionProfile(
            id = "test-missing",
            name = "连不上",
            kind = ConnectionKind.CUSTOM,
            jdbcUrl = "jdbc:postgresql://127.0.0.1:1/nope",
        )
        val (result, snapshot) = ConnectionGateway.openBlocking(profile, "secret")
        assertFalse(result.ok)
        assertEquals(null, snapshot)
        assertTrue(result.message.isNotBlank(), "失败必须带原因")
        assertFalse(result.message.contains("secret"), "错误信息不能回显密码")
    }

    /**
     * 连接 → 血缘的接线：`SELECT *` 的展开靠连接的 JDBC 元数据（而不是 SQL 文本里的 DDL）。
     * 这条不通过，「在血缘图中打开」就只是把 SQL 塞进编辑器。
     */
    @Test
    fun analysisUsesConnectionSchemaForStarExpansion() {
        val profile = sqliteProfile()
        java.sql.DriverManager.getConnection(profile.resolvedUrl()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE orders (id INTEGER, amount NUMERIC)")
            }
        }
        val (result, _) = ConnectionGateway.openBlocking(profile, "")
        assertTrue(result.ok, result.message)
        try {
            val withSchema = EngineGateway.analyze(
                "SELECT * FROM orders",
                engineId = EngineGateway.engineIds.first(),
                tolerant = true,
                connectionId = profile.id,
            )
            val ids = withSchema.columns.map { it.id }
            assertTrue(ids.any { it.endsWith("orders.amount") }, "带连接时 * 应展开成 orders.amount：$ids")
            assertTrue(withSchema.unknowns.isEmpty(), "带 schema 不该有未知：${withSchema.unknowns}")

            val withoutSchema = EngineGateway.analyze(
                "SELECT * FROM orders",
                engineId = EngineGateway.engineIds.first(),
                tolerant = true,
                connectionId = null,
            )
            assertTrue(
                withoutSchema.unknowns.isNotEmpty(),
                "不带 schema 时 * 无法展开，必须如实记未知（对照组）",
            )
        } finally {
            ConnectionGateway.close(profile.id)
        }
    }

    @Test
    fun resolvedUrlCoversKinds() {        assertEquals(
            "jdbc:postgresql://db.example:5433/analytics",
            ConnectionProfile(id = "p", name = "p", kind = ConnectionKind.POSTGRESQL, host = "db.example", port = "5433", database = "analytics").resolvedUrl(),
        )
        assertEquals(
            "jdbc:sqlite:/tmp/x.db",
            ConnectionProfile(id = "s", name = "s", kind = ConnectionKind.SQLITE, database = "/tmp/x.db").resolvedUrl(),
        )
        // 高级模式覆盖一切
        assertEquals(
            "jdbc:duckdb:/tmp/y.duckdb",
            ConnectionProfile(id = "d", name = "d", kind = ConnectionKind.DUCKDB, database = "ignored", jdbcUrl = "jdbc:duckdb:/tmp/y.duckdb").resolvedUrl(),
        )
    }

    @Test
    fun storeRoundTripsWithoutSecrets() {
        val root = tempDir.resolve("store")
        Files.createDirectories(root)
        System.setProperty("ozml.storage.root", root.toString())
        try {
            val state = DesktopStoreState(
                connections = listOf(sqliteProfile()),
                theme = ThemeMode.LIGHT,
            )
            StoreGateway.save(state)
            val loaded = StoreGateway.load()
            assertEquals(state, loaded)
            val raw = Files.readString(root.resolve("desktop.json"))
            assertFalse(raw.contains("password", ignoreCase = true), "设置文件不许出现 password 字段：$raw")
        } finally {
            System.clearProperty("ozml.storage.root")
        }
    }

    /**
     * 真下载（手动检查，**不进 CI**）：`-PozmlManualChecks=true` 时把 SQLite 驱动下到临时目录，
     * 再从驱动目录连一个真库。走的是用户点「下载驱动」时的同一条路径。
     */
    @Test
    fun downloadsDriverFromMavenCentralAndUsesIt() {
        if (System.getProperty("ozml.manual.checks") != "true") return
        val root = Files.createDirectories(tempDir.resolve("driver-root"))
        System.setProperty("ozml.storage.root", root.toString())
        try {
            val profile = sqliteProfile()
            var done = false
            var message = ""
            ConnectionGateway.installDriver(profile) { ok, text ->
                done = ok
                message = text
            }
            var waited = 0
            while (!done && waited < 120_000) {
                Thread.sleep(100)
                waited += 100
            }
            assertTrue(done, "下载应成功：$message")
            assertTrue(Files.exists(root.resolve("drivers").resolve("sqlite-jdbc-3.53.4.0.jar")), message)
            val (result, _) = ConnectionGateway.openBlocking(profile, "")
            assertTrue(result.ok, result.message)
        } finally {
            ConnectionGateway.close("test-sqlite")
            System.clearProperty("ozml.storage.root")
        }
    }
}
