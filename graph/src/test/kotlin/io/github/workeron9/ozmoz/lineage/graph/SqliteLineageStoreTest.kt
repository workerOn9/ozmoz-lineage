package io.github.workeron9.ozmoz.lineage.graph

import io.github.workeron9.ozmoz.lineage.ir.ColumnNode
import io.github.workeron9.ozmoz.lineage.ir.ColumnRef
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageEdge
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Meta
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.TransformKind
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SqliteLineageStore] 的往返测试：**手工构造** [LineageModel]，不依赖任何引擎。
 *
 * 重点：模型原样往返（Lossless）、写入顺序即读出顺序、[LineageStore.save] 是全量替换。
 */
class SqliteLineageStoreTest {

    /** 用 `meta.schemaSnapshotId` 区分不同模型（真名 `t1.a` 保持不变）。 */
    private fun model(tag: String, unknown: String? = null): LineageModel = LineageModel(
        meta = Meta(engineId = "test", schemaSnapshotId = tag),
        columns = listOf(ColumnNode(column = col("a", "t1"), scopeId = "s0", isOutput = true)),
        edges = listOf(e(0, "t1.b", "t1.a")),
        unknowns = unknown?.let { listOf(Resolved.Unknown(it)) }.orEmpty(),
    )

    private fun tagOf(model: LineageModel): String = model.meta.schemaSnapshotId!!

    @Test
    fun `内存库往返 顺序与溯源信息保留`() {
        SqliteLineageStore.inMemory().use { store ->
            store.save(
                listOf(
                    StoredModel(model("m1"), sourceFile = "a.sql", statementIndex = 0),
                    StoredModel(model("m2", unknown = "列 x 有歧义"), sourceFile = "b.sql", statementIndex = 1),
                ),
            )

            assertEquals(2, store.count())
            val loaded = store.load()
            assertEquals(listOf("a.sql", "b.sql"), loaded.map { it.sourceFile })
            assertEquals(listOf(0, 1), loaded.map { it.statementIndex })
            assertEquals(listOf("m1", "m2"), loaded.map { tagOf(it.model) })
            assertEquals("列 x 有歧义", loaded[1].model.unknowns.single().reason)
        }
    }

    @Test
    fun `save 是全量替换`() {
        SqliteLineageStore.inMemory().use { store ->
            store.save(listOf(StoredModel(model("m1")), StoredModel(model("m2"))))
            store.save(listOf(StoredModel(model("m3"))))

            assertEquals(1, store.count())
            assertEquals("m3", tagOf(store.load().single().model))
        }
    }

    @Test
    fun `clear 清空记录`() {
        SqliteLineageStore.inMemory().use { store ->
            store.save(listOf(StoredModel(model("m1"))))
            store.clear()
            assertEquals(0, store.count())
            assertTrue(store.load().isEmpty())
        }
    }

    @Test
    fun `文件库关闭后重开数据仍在`() {
        val db = Files.createTempDirectory("ozml-store-").resolve("lineage.db")
        SqliteLineageStore.open(db).use { store ->
            store.save(listOf(StoredModel(model("m1"), sourceFile = "a.sql")))
        }
        SqliteLineageStore.open(db).use { reopened ->
            assertEquals(1, reopened.count())
            assertEquals("a.sql", reopened.load().single().sourceFile)
        }
        assertTrue(Files.exists(db))
    }

    @Test
    fun `空库 load 返回空 不抛异常`() {
        SqliteLineageStore.inMemory().use { store ->
            assertEquals(0, store.count())
            assertTrue(store.load().isEmpty())
        }
    }

    @Test
    fun `重建同一路径库时覆盖旧内容`() {
        val db = Files.createTempDirectory("ozml-store-").resolve("lineage.db")
        SqliteLineageStore.open(db).use { it.save(listOf(StoredModel(model("old")))) }
        SqliteLineageStore.open(db).use { store ->
            store.save(listOf(StoredModel(model("new"))))
            assertEquals("new", tagOf(store.load().single().model))
        }
    }

    @Test
    fun `open 时自动创建父目录`() {
        val dir = Files.createTempDirectory("ozml-store-nested-")
        SqliteLineageStore.open(Path(dir.toString(), "nested", "lineage.db")).use { store ->
            store.save(listOf(StoredModel(model("m1"))))
            assertEquals(1, store.count())
        }
    }

    // ————— 增量更新三件套（fingerprints / replaceSource / removeSource） —————

    @Test
    fun `replaceSource 删旧插新 指纹与顺序保留`() {
        SqliteLineageStore.inMemory().use { store ->
            store.save(
                listOf(
                    StoredModel(model("m1"), sourceFile = "a.sql", statementIndex = 0, fingerprint = "fp-a"),
                    StoredModel(model("m2"), sourceFile = "b.sql", statementIndex = 0, fingerprint = "fp-b"),
                ),
            )

            // a.sql 重解析出两条语句（指纹变了）→ 原子替换。
            store.replaceSource(
                listOf(
                    StoredModel(model("m1x"), sourceFile = "a.sql", statementIndex = 0, fingerprint = "fp-a2"),
                    StoredModel(model("m1y"), sourceFile = "a.sql", statementIndex = 1, fingerprint = "fp-a2"),
                ),
            )

            assertEquals(3, store.count())
            val loaded = store.load()
            // 替换行顺延到库尾（seq 追加）：b.sql 排前、a.sql 的两条新记录在其后。
            assertEquals(listOf("m2", "m1x", "m1y"), loaded.map { tagOf(it.model) })
            assertEquals(listOf("fp-b", "fp-a2", "fp-a2"), loaded.map { it.fingerprint })
            assertEquals(mapOf("a.sql" to "fp-a2", "b.sql" to "fp-b"), store.fingerprints())
        }
    }

    @Test
    fun `removeSource 删掉整组并返回条数`() {
        SqliteLineageStore.inMemory().use { store ->
            store.save(
                listOf(
                    StoredModel(model("m1"), sourceFile = "a.sql", statementIndex = 0),
                    StoredModel(model("m2"), sourceFile = "a.sql", statementIndex = 1, fingerprint = "fp-a"),
                    StoredModel(model("m3"), sourceFile = "b.sql", statementIndex = 0, fingerprint = "fp-b"),
                ),
            )

            assertEquals(2, store.removeSource("a.sql"))
            assertEquals(0, store.removeSource("a.sql"))
            assertEquals(mapOf("b.sql" to "fp-b"), store.fingerprints())
            assertEquals(listOf("m3"), store.load().map { tagOf(it.model) })
        }
    }

    @Test
    fun `无指纹的来源不出现在 fingerprints`() {
        SqliteLineageStore.inMemory().use { store ->
            store.save(
                listOf(
                    StoredModel(model("m1"), sourceFile = "a.sql", statementIndex = 0, fingerprint = null),
                    StoredModel(model("m2"), sourceFile = "b.sql", statementIndex = 0, fingerprint = "fp-b"),
                ),
            )

            assertEquals(mapOf("b.sql" to "fp-b"), store.fingerprints())
            // 但全量语义不受影响：v1 迁移过来的 null 指纹行照常往返。
            val loaded = store.load()
            assertEquals(listOf("a.sql", "b.sql"), loaded.map { it.sourceFile })
            assertEquals(null, loaded[0].fingerprint)
        }
    }

    @Test
    fun `replaceSource 拒绝空列表 与来源指纹不一致`() {
        SqliteLineageStore.inMemory().use { store ->
            repositoryAssert({ store.replaceSource(emptyList()) }, "空列表")
            repositoryAssert(
                { store.replaceSource(listOf(StoredModel(model("m1"), sourceFile = null, fingerprint = "f"))) },
                "必须有 sourceFile",
            )
            repositoryAssert(
                { store.replaceSource(listOf(StoredModel(model("m1"), sourceFile = "a", fingerprint = null))) },
                "必须有 fingerprint",
            )
            repositoryAssert(
                {
                    store.replaceSource(
                        listOf(
                            StoredModel(model("m1"), sourceFile = "a", fingerprint = "f1"),
                            StoredModel(model("m2"), sourceFile = "b", fingerprint = "f2"),
                        ),
                    )
                },
                "不一致",
            )
            assertEquals(0, store.count())
        }
    }

    private fun repositoryAssert(block: () -> Unit, messageIn: String) {
        try {
            block()
            assertTrue(false, "应抛异常：$messageIn")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `v1 库自动迁移加列 数据与元信息保留`() {
        val db = Files.createTempDirectory("ozml-v1-").resolve("lineage.db")
        // 手工按 v1 的格式建库（无 fingerprint 列，schema_version=1）。
        java.sql.DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE ozmoz_info (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                statement.executeUpdate(
                    "CREATE TABLE lineage_model (seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "source_file TEXT, statement_index INTEGER NOT NULL, payload TEXT NOT NULL)",
                )
                statement.executeUpdate("INSERT INTO ozmoz_info(key, value) VALUES ('schema_version', '1')")
                statement.executeUpdate("INSERT INTO ozmoz_info(key, value) VALUES ('tool', 'ozmoz-lineage')")
                statement.executeUpdate(
                    "INSERT INTO lineage_model(source_file, statement_index, payload) VALUES ('a.sql', 0, '${v1Payload()}')",
                )
            }
        }

        SqliteLineageStore.open(db).use { migrated ->
            assertEquals("2", SqliteLineageStore.SCHEMA_VERSION)
            assertEquals(1, migrated.count())
            assertEquals("m1", tagOf(migrated.load().single().model))
            assertEquals(null, migrated.load().single().fingerprint)
            assertEquals(emptyMap<String, String>(), migrated.fingerprints())
            // 迁移后增量写入正常工作。
            migrated.replaceSource(
                listOf(StoredModel(model("m1x"), sourceFile = "a.sql", statementIndex = 0, fingerprint = "fp-a")),
            )
            assertEquals("fp-a", migrated.fingerprints()["a.sql"])
        }
        // 重新打开仍是 v2 世界。
        SqliteLineageStore.open(db).use { reopened ->
            assertEquals("fp-a", reopened.fingerprints()["a.sql"])
        }
    }

    /** 组一段 v1 时代 sqlite 写出的模型 JSON（与本实现的序列化字段对齐）。 */
    private fun v1Payload(): String = jsonEncode(model("m1"))

    private fun jsonEncode(model: LineageModel): String =
        kotlinx.serialization.json.Json {
            encodeDefaults = true
            explicitNulls = false
        }.encodeToString(LineageModel.serializer(), model)

    private fun col(name: String, table: String): ColumnRef = ColumnRef(
        raw = "$table.$name",
        canonical = "$table.$name".lowercase(),
        name = name,
        table = table,
    )

    private fun e(index: Int, from: String, to: String): LineageEdge = LineageEdge(
        id = "e$index",
        fromColumn = ref(from),
        toColumn = ref(to),
        kind = EdgeKind.OUTPUT,
        transform = TransformKind.DIRECT,
    )

    private fun ref(id: String): ColumnRef {
        val parts = id.split(".")
        return col(parts.last(), parts.first())
    }
}
