package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionProfile
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionResult
import io.github.workeron9.ozmoz.lineage.desktop.model.DbColumn
import io.github.workeron9.ozmoz.lineage.desktop.model.DbTable
import io.github.workeron9.ozmoz.lineage.desktop.model.SchemaSnapshot
import io.github.workeron9.ozmoz.lineage.schema.JdbcSchemaProvider
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.concurrent.thread

/**
 * [ConnectionGateway] 的 JVM 实现：`DriverManager` + `DatabaseMetaData`，
 * **只读元数据**（标准 JDBC 接口，不写各数据库的 information_schema SQL）。
 *
 * 驱动来源三层（不打包重型驱动，也不假装能用没装的驱动）：
 * 1. classpath——SQLite 随包（已在 THIRD-PARTY 登记）；
 * 2. `~/.ozml-lineage/drivers` 下的 *.jar——PostgreSQL / MySQL / ClickHouse / DuckDB 的
 *    官方驱动放进来即可（放进来的 jar 不改、不 fork）；
 * 3. 都没有时给出明确原因，而不是 `No suitable driver`。
 */
public actual object ConnectionGateway {

    private val connections = ConcurrentHashMap<String, Connection>()
    private val providers = ConcurrentHashMap<String, SchemaProvider>()
    private val externalDrivers: List<Driver> by lazy { loadExternalDrivers() }

    public actual fun open(
        profile: ConnectionProfile,
        password: String,
        onResult: (ConnectionResult, SchemaSnapshot?) -> Unit,
    ) {
        thread(isDaemon = true, name = "ozml-connect-${profile.id}") {
            val result = runCatching { reuseOrConnect(profile, password) }
            result.fold(
                onSuccess = { (connection, reused) -> onResult(ConnectionResult(true, if (reused) "复用已打开的连接" else "连接成功"), snapshot(connection)) },
                onFailure = { onResult(ConnectionResult(false, describe(it, profile)), null) },
            )
        }
    }

    public actual fun close(profileId: String) {
        providers.remove(profileId)
        connections.remove(profileId)?.let { runCatching { it.close() } }
    }

    /**
     * 已打开连接的 schema 提供方（快照语义，首次访问时载入）。
     * 血缘分析经这里拿「权威 schema」；连接没开时返回 null（退回 SQL 文本里的 DDL）。
     */
    internal fun schemaProvider(profileId: String?): SchemaProvider? {
        if (profileId == null) return null
        providers[profileId]?.let { return it }
        val connection = connections[profileId] ?: return null
        return JdbcSchemaProvider(connection, id = "jdbc:$profileId").also { providers[profileId] = it }
    }

    /** 同步版：测试与「跳转血缘」用（调用方保证不在 UI 线程）。 */
    internal fun openBlocking(profile: ConnectionProfile, password: String): Pair<ConnectionResult, SchemaSnapshot?> = runCatching {
        val (connection, reused) = reuseOrConnect(profile, password)
        ConnectionResult(true, if (reused) "复用已打开的连接" else "连接成功") to snapshot(connection)
    }.getOrElse { ConnectionResult(false, describe(it, profile)) to null }

    private fun reuseOrConnect(profile: ConnectionProfile, password: String): Pair<Connection, Boolean> {
        connections[profile.id]?.let { if (runCatching { it.isValid(2) }.getOrDefault(false)) return it to true }
        val url = profile.resolvedUrl()
        val properties = Properties()
        if (profile.user.isNotBlank()) properties.setProperty("user", profile.user)
        if (password.isNotEmpty()) properties.setProperty("password", password)
        val connection = connect(url, properties)
        connection.autoCommit = true
        connections[profile.id] = connection
        return connection to false
    }

    private fun connect(url: String, properties: Properties): Connection {
        runCatching { return DriverManager.getConnection(url, properties) }
        for (driver in externalDrivers) {
            if (runCatching { driver.acceptsURL(url) }.getOrDefault(false)) {
                return driver.connect(url, properties)
                    ?: throw SQLException("驱动 ${driver.javaClass.name} 拒绝了该 URL")
            }
        }
        throw SQLException(
            "没有可用的 JDBC 驱动（$url）。把对应驱动 jar 放进 ~/.ozml-lineage/drivers/ 后重试。",
        )
    }

    /** 读表与列。全库扫描（JDBC 标准接口）；大库靠 D3 的 schema 收敛，不在这里猜。 */
    private fun snapshot(connection: Connection): SchemaSnapshot {
        val meta = connection.metaData
        val tables = ArrayList<DbTable>()
        meta.getTables(null, null, "%", arrayOf("TABLE", "VIEW")).use { rs ->
            while (rs.next()) {
                tables += DbTable(
                    schema = rs.getString("TABLE_SCHEM"),
                    name = rs.getString("TABLE_NAME"),
                    kind = rs.getString("TABLE_TYPE") ?: "TABLE",
                )
            }
        }
        val columns = HashMap<String, List<DbColumn>>()
        for (table in tables) {
            val list = ArrayList<DbColumn>()
            meta.getColumns(null, table.schema, table.name, "%").use { rs ->
                while (rs.next()) {
                    list += DbColumn(
                        name = rs.getString("COLUMN_NAME"),
                        type = rs.getString("TYPE_NAME") ?: "",
                        nullable = when (rs.getInt("NULLABLE")) {
                            java.sql.DatabaseMetaData.columnNoNulls -> false
                            java.sql.DatabaseMetaData.columnNullable -> true
                            else -> null
                        },
                        ordinal = rs.getInt("ORDINAL_POSITION"),
                    )
                }
            }
            columns[table.qualifiedName] = list.sortedBy { it.ordinal }
        }
        return SchemaSnapshot(tables = tables.sortedBy { it.qualifiedName }, columns = columns)
    }

    private fun describe(error: Throwable, profile: ConnectionProfile): String {
        val cause = generateSequence(error) { it.cause }.last()
        val message = cause.message ?: error.message ?: cause.javaClass.simpleName
        return "${profile.resolvedUrl()}：$message"
    }

    private fun loadExternalDrivers(): List<Driver> {
        val dir = Storage.root().resolve("drivers")
        if (!Files.isDirectory(dir)) return emptyList()
        val jars = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jar") }.toList()
        }
        if (jars.isEmpty()) return emptyList()
        val loader = URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), javaClass.classLoader)
        return runCatching {
            java.util.ServiceLoader.load(Driver::class.java, loader).toList().map(::DriverShim)
        }.getOrElse { emptyList() }
    }

    /** `DriverManager` 不认子加载器里的驱动，包装一层转发即可（JDBC 驱动是 SPI，不用改驱动源码）。 */
    private class DriverShim(private val delegate: Driver) : Driver {
        override fun connect(url: String?, info: Properties?): Connection? = delegate.connect(url, info)
        override fun acceptsURL(url: String?): Boolean = delegate.acceptsURL(url)
        override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> =
            delegate.getPropertyInfo(url, info)

        override fun getMajorVersion(): Int = delegate.majorVersion
        override fun getMinorVersion(): Int = delegate.minorVersion
        override fun jdbcCompliant(): Boolean = delegate.jdbcCompliant()
        override fun getParentLogger(): Logger = delegate.parentLogger
    }
}
