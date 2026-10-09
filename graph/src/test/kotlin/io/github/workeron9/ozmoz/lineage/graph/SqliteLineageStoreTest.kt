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
