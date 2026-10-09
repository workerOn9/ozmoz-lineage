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
 * - `lineage_model(seq, source_file, statement_index, payload)`：每条语句一个 `LineageModel`
 *   的 JSON（见 [LineageStore] 的「存的是模型」说明）。
 *
 * `seq` 是 `AUTOINCREMENT` 自增主键，[load] 按它排序 → 写入顺序即读出顺序（确定性）。
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
            connection.prepareStatement(
                "INSERT INTO lineage_model(source_file, statement_index, payload) VALUES (?, ?, ?)",
            ).use { statement ->
                for (stored in models) {
                    statement.setString(1, stored.sourceFile)
                    statement.setInt(2, stored.statementIndex)
                    statement.setString(3, json.encodeToString(LineageModel.serializer(), stored.model))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            putInfo(KEY_CREATED_AT, Instant.now().toString())
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    override fun load(): List<StoredModel> {
        val result = ArrayList<StoredModel>()
        connection.prepareStatement(
            "SELECT source_file, statement_index, payload FROM lineage_model ORDER BY seq",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val payload = rows.getString("payload")
                    result += StoredModel(
                        model = json.decodeFromString(LineageModel.serializer(), payload),
                        sourceFile = rows.getString("source_file"),
                        statementIndex = rows.getInt("statement_index"),
                    )
                }
            }
        }
        return result
    }

    override fun clear() {
        connection.createStatement().use { it.executeUpdate("DELETE FROM lineage_model") }
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
                    "payload TEXT NOT NULL)",
            )
        }
        val existing = readInfo(KEY_SCHEMA_VERSION)
        if (existing != null && existing != SCHEMA_VERSION) {
            throw IllegalStateException(
                "lineage store schema_version=$existing 与当前版本 $SCHEMA_VERSION 不兼容；请重建图库",
            )
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
        public const val SCHEMA_VERSION: String = "1"

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
