package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionProfile
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionResult
import io.github.workeron9.ozmoz.lineage.desktop.model.DbColumn
import io.github.workeron9.ozmoz.lineage.desktop.model.DbTable
import io.github.workeron9.ozmoz.lineage.desktop.model.DriverPlan
import io.github.workeron9.ozmoz.lineage.desktop.model.SchemaSnapshot
import io.github.workeron9.ozmoz.lineage.schema.JdbcSchemaProvider
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
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
 * 2. `~/.ozml-lineage/drivers` 下的 *.jar——官方驱动可从 Maven Central 一键下载（[installDriver]），
 *    也可以自己放进去（放进来的 jar 不改、不 fork）；
 * 3. 都没有时给出明确原因，而不是 `No suitable driver`。
 */
public actual object ConnectionGateway {

    private const val CENTRAL = "https://repo1.maven.org/maven2/"

    private val connections = ConcurrentHashMap<String, Connection>()
    private val providers = ConcurrentHashMap<String, SchemaProvider>()

    /** 驱动目录里的 jar；下载新驱动后置空重载（`installDriver`）。 */
    @Volatile
    private var loadedDrivers: List<Driver>? = null

    private fun externalDrivers(): List<Driver> =
        loadedDrivers ?: synchronized(this) { loadedDrivers ?: loadExternalDrivers().also { loadedDrivers = it } }

    /** 已知下载源：URL 前缀 → Maven Central 坐标。版本钉死，换版本就是改这里一行。 */
    private class Artifact(
        val prefix: String,
        val name: String,
        /** 不带 `.jar` 的仓库路径。 */
        val path: String,
        val sizeLabel: String,
    ) {
        val jarUrl: String get() = CENTRAL + path + ".jar"
        val fileName: String get() = path.substringAfterLast('/') + ".jar"
    }

    private val artifacts = listOf(
        Artifact("jdbc:sqlite:", "SQLite", "org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0", "12 MB"),
        Artifact("jdbc:postgresql:", "PostgreSQL", "org/postgresql/postgresql/42.7.8/postgresql-42.7.8", "1.1 MB"),
        Artifact("jdbc:mysql:", "MySQL", "com/mysql/mysql-connector-j/9.1.0/mysql-connector-j-9.1.0", "2.6 MB"),
        Artifact(
            "jdbc:clickhouse:",
            "ClickHouse",
            "com/clickhouse/clickhouse-jdbc/0.6.5/clickhouse-jdbc-0.6.5-all",
            "7.8 MB",
        ),
        Artifact("jdbc:duckdb:", "DuckDB", "org/duckdb/duckdb_jdbc/1.1.3/duckdb_jdbc-1.1.3", "73 MB"),
    )

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

    public actual fun driverPlan(profile: ConnectionProfile): DriverPlan? {
        val url = profile.resolvedUrl()
        if (hasDriver(url)) return null
        return artifactFor(url)?.let { DriverPlan(name = it.name, sizeLabel = it.sizeLabel) }
    }

    public actual fun installDriver(profile: ConnectionProfile, onResult: (Boolean, String) -> Unit) {
        val artifact = artifactFor(profile.resolvedUrl())
        if (artifact == null) {
            onResult(false, "这个 URL 没有已知的驱动下载源，请手动把驱动 jar 放进 ~/.ozml-lineage/drivers/。")
            return
        }
        thread(isDaemon = true, name = "ozml-driver-${profile.id}") {
            val result = runCatching {
                val target = Storage.root().resolve("drivers").also { Files.createDirectories(it) }
                    .resolve(artifact.fileName)
                val temp = Files.createTempFile(target.parent, "driver-", ".jar.download")
                try {
                    fetch(artifact.jarUrl, temp)
                    val expected = String(fetchBytes(artifact.jarUrl + ".sha1")).trim().take(40).lowercase()
                    val actual = sha1(temp)
                    if (expected.length != 40 || expected != actual) {
                        error("SHA-1 校验失败（期望 ${expected.take(12)}，实际 ${actual.take(12)}），已丢弃下载文件")
                    }
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temp)
                }
                loadedDrivers = loadExternalDrivers()
                "已下载 ${artifact.name} 驱动（${artifact.fileName}）"
            }
            result.fold(
                onSuccess = { onResult(true, it) },
                onFailure = { onResult(false, "驱动下载失败：${it.message ?: it.javaClass.simpleName}") },
            )
        }
    }

    private fun artifactFor(url: String): Artifact? = artifacts.firstOrNull { url.startsWith(it.prefix) }

    /** 本地是否已有能处理这条 URL 的驱动（classpath / 驱动目录都算）。 */
    private fun hasDriver(url: String): Boolean =
        runCatching { DriverManager.getDriver(url) != null }.getOrDefault(false) ||
            externalDrivers().any { runCatching { it.acceptsURL(url) }.getOrDefault(false) }

    private fun fetch(url: String, target: Path) {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build()
        val response = http.send(
            request,
            HttpResponse.BodyHandlers.ofFile(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ),
        )
        if (response.statusCode() != 200) error("HTTP ${response.statusCode()}：$url")
    }

    private fun fetchBytes(url: String): ByteArray {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) error("HTTP ${response.statusCode()}：$url")
        return response.body()
    }

    private val http: HttpClient by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build() }

    private fun sha1(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
        missingSqliteDirectory(url)?.let {
            throw SQLException("数据库文件所在目录不存在：$it（先建好目录，或把连接指到已有的 .db 文件）")
        }
        val properties = Properties()
        if (profile.user.isNotBlank()) properties.setProperty("user", profile.user)
        if (password.isNotEmpty()) properties.setProperty("password", password)
        val connection = connect(url, properties)
        connection.autoCommit = true
        connections[profile.id] = connection
        return connection to false
    }

    /** SQLite 的绝对路径 + 不存在的父目录：比驱动那句 CANTOPEN 更能指路。null = 不用管。 */
    private fun missingSqliteDirectory(url: String): String? {
        if (!url.startsWith("jdbc:sqlite:")) return null
        val path = runCatching { Path.of(url.removePrefix("jdbc:sqlite:")) }.getOrNull() ?: return null
        if (!path.isAbsolute) return null
        val parent = path.parent ?: return null
        return if (Files.isDirectory(parent)) null else parent.toString()
    }

    private fun connect(url: String, properties: Properties): Connection {
        // 有驱动处理这条 URL 时，用它自己的错误原样往上抛（路径不存在 / 认证失败 / 库损坏…），
        // 不能一律说成「没有驱动」——那样用户会去下载一个本来就在包里的驱动。
        if (runCatching { DriverManager.getDriver(url) }.getOrNull() != null) {
            return DriverManager.getConnection(url, properties)
        }
        for (driver in externalDrivers()) {
            if (runCatching { driver.acceptsURL(url) }.getOrDefault(false)) {
                return driver.connect(url, properties)
                    ?: throw SQLException("驱动 ${driver.javaClass.name} 拒绝了该 URL")
            }
        }
        throw SQLException(
            "没有可用的 JDBC 驱动。放进 ~/.ozml-lineage/drivers/ 后重试，或用「下载驱动」自动补。",
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
