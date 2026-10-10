package io.github.workeron9.ozmoz.lineage.graph

import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * 基于 **SQLite**（`org.xerial:sqlite-jdbc`）的 [LineageStore] 实现。
 *
 * 库里只有两张表：
 * - `ozmoz_info(key, value)`：库级元信息（`schema_version` / `created_at` / `tool`）；
 * - `lineage_model(seq, source_file, statement_index, payload, fingerprint)`：每条语句
 *   一个 `LineageModel` 的 JSON（见 [LineageStore] 的「存的是模型」说明）。
 *
 * `seq` 是 `AUTOINCREMENT` 自增主键，[load] 按它排序 → 写入顺序即读出顺序（确定性）。
 * 增量替换（[replaceSource]）会把该来源的记录**删了再插**，seq 顺延到库尾——
 * `load` 的全局顺序因此随增量历史漂移；对图语义无影响（图不依赖模型顺序），
 * 对逐条 golden 对拍请全量重跑（`save`）。
 *
 * ## 版本迁移
 *
 * v1（无 `fingerprint` 列）的库在 [SqliteLineageStore.open] 时自动 `ALTER TABLE`
 * 加列升级到 [SCHEMA_VERSION]，v1 行的 `fingerprint` 为 null：不影响 [save] / [load]，
 * 只是增量流程把它们当作「未比对 → 需重解析」。更早 / 未知版本仍拒绝打开（要求重建）。
 *
 * ## 线程与生命周期
 *
 * 单个 JDBC 连接，**非线程安全**，`cli` 单线程使用即可；用完必须 [close]（`use {}`）。
 */
public class SqliteLineageStore private constructor(
    private val connection: Connection,
) : LineageStore {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    override fun save(models: List<StoredModel>) {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            clear()
            insertAll(models)
            putInfo(KEY_CREATED_AT, Instant.now().toString())
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    override fun replaceSource(stored: List<StoredModel>) {
        require(stored.isNotEmpty()) { "replaceSource 收到空列表：改用 save（全量）或 removeSource（删除）" }
        val sources = stored.map { it.sourceFile }.distinct()
        val fingerprints = stored.map { it.fingerprint }.distinct()
        require(sources.size == 1) { "replaceSource 的记录必须来自同一个来源文件（实际：$sources）" }
        require(fingerprints.size == 1) { "replaceSource 的记录必须共享同一个指纹（实际：$fingerprints）" }
        val source = requireNotNull(sources.single()) { "replaceSource 的记录必须有 sourceFile" }
        requireNotNull(fingerprints.single()) { "replaceSource 的记录必须有 fingerprint" }
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            deleteSource(source)
            insertAll(stored)
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    override fun removeSource(sourceFile: String): Int {
        var deleted = 0
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            deleted = deleteSource(sourceFile)
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = previousAutoCommit
        }
        return deleted
    }

    override fun load(): List<StoredModel> {
        val result = ArrayList<StoredModel>()
        connection.prepareStatement(
            "SELECT source_file, statement_index, payload, fingerprint FROM lineage_model ORDER BY seq",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val payload = rows.getString("payload")
                    result += StoredModel(
                        model = json.decodeFromString(LineageModel.serializer(), payload),
                        sourceFile = rows.getString("source_file"),
                        statementIndex = rows.getInt("statement_index"),
                        fingerprint = rows.getString("fingerprint"),
                    )
                }
            }
        }
        return result
    }

    override fun clear() {
        connection.createStatement().use { it.executeUpdate("DELETE FROM lineage_model") }
    }

    override fun fingerprints(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT source_file, fingerprint FROM lineage_model " +
                    "WHERE source_file IS NOT NULL AND fingerprint IS NOT NULL",
            ).use { rows ->
                while (rows.next()) {
                    val source = rows.getString(1)
                    if (source !in result) result[source] = rows.getString(2)
                }
            }
        }
        return result
    }

    /** 删除某来源的全部记录（调用方负责事务包裹）。返回删除条数。 */
    private fun deleteSource(sourceFile: String): Int =
        connection.prepareStatement("DELETE FROM lineage_model WHERE source_file = ?").use { statement ->
            statement.setString(1, sourceFile)
            statement.executeUpdate()
        }

    /** 批量插入（调用方负责事务包裹与顺序）。 */
    private fun insertAll(models: List<StoredModel>) {
        connection.prepareStatement(
            "INSERT INTO lineage_model(source_file, statement_index, payload, fingerprint) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            for (stored in models) {
                statement.setString(1, stored.sourceFile)
                statement.setInt(2, stored.statementIndex)
                statement.setString(3, json.encodeToString(LineageModel.serializer(), stored.model))
                statement.setString(4, stored.fingerprint)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    override fun count(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM lineage_model").use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }

    override fun close() {
        connection.close()
    }

    private fun putInfo(key: String, value: String) {
        connection.prepareStatement(
            "INSERT INTO ozmoz_info(key, value) VALUES (?, ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, value)
            statement.executeUpdate()
        }
    }

    private fun ensureSchema() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ozmoz_info (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            )
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS lineage_model (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "source_file TEXT, " +
                    "statement_index INTEGER NOT NULL, " +
                    "payload TEXT NOT NULL, " +
                    "fingerprint TEXT)",
            )
        }
        val existing = readInfo(KEY_SCHEMA_VERSION)
        if (existing != null && existing != SCHEMA_VERSION) {
            // v1 没有 fingerprint 列：原位加列升级（增量三件套把 v1 行视做「未比对」）。
            if (existing == "1") {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("ALTER TABLE lineage_model ADD COLUMN fingerprint TEXT")
                }
            } else {
                throw IllegalStateException(
                    "lineage store schema_version=$existing 与当前版本 $SCHEMA_VERSION 不兼容；请重建图库",
                )
            }
        }
        putInfo(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        putInfo(KEY_TOOL, "ozmoz-lineage")
    }

    private fun readInfo(key: String): String? =
        connection.prepareStatement("SELECT value FROM ozmoz_info WHERE key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    public companion object {

        /** 库格式版本；不兼容变更时递增（并在 [ensureSchema] 里拒绝旧库）。 */
        public const val SCHEMA_VERSION: String = "2"

        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_TOOL = "tool"

        /**
         * 打开（不存在则创建）位于 [path] 的图库。父目录不存在会自动创建。
         *
         * @param path 通常是 `./lineage.db`；SQLite 单文件，直接落盘。
         */
        @JvmStatic
        public fun open(path: Path): SqliteLineageStore {
            val absolute = path.toAbsolutePath()
            absolute.parent?.let { Files.createDirectories(it) }
            return openConnection("jdbc:sqlite:$absolute")
        }

        /** 内存库（测试 / 临时查询用）；进程结束即消失。 */
        @JvmStatic
        public fun inMemory(): SqliteLineageStore = openConnection("jdbc:sqlite::memory:")

        private fun openConnection(url: String): SqliteLineageStore =
            SqliteLineageStore(DriverManager.getConnection(url)).apply { ensureSchema() }
    }
}
