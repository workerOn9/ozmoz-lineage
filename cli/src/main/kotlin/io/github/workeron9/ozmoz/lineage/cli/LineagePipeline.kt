package io.github.workeron9.ozmoz.lineage.cli

import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import io.github.workeron9.ozmoz.lineage.ir.map
import io.github.workeron9.ozmoz.lineage.lineage.LineageBuilder
import io.github.workeron9.ozmoz.lineage.schema.DdlFileSchemaProvider
import io.github.workeron9.ozmoz.lineage.schema.SchemaProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText

/**
 * `lineage` / `impact` 共用的**组合根**逻辑：把「一个路径（文件 / 目录 / stdin）」
 * 变成一组 [LineageModel]。
 *
 * 放在 `cli`（组合根）而不是 `lineage` 模块：目录遍历与引擎选择是**入口层**的职责，
 * `lineage` 只依赖 `ir` + `engine-api` + `schema` SPI，不反向依赖任何引擎适配器。
 */
internal object LineagePipeline {

    /** 引擎 id → 实例；未注册直接报错（与能力表路由不同，这里只有必选引擎）。 */
    fun engine(id: String): SqlEngine = when (id) {
        JSqlParserEngine.ID -> JSqlParserEngine()
        else -> error("未注册的引擎：$id")
    }

    /**
     * 输入路径 → (来源文件, SQL 文本) 列表：
     * - `-` → 单个 `(null, stdin)`；
     * - 普通文件 → `(路径, 内容)`；
     * - 目录 → 递归取 `*.sql`（按路径排序，保证确定性）。
     */
    fun readInputs(path: String): List<Pair<String?, String>> {
        if (path == "-") {
            return listOf(null to System.`in`.bufferedReader().readText())
        }
        val root = Path(path)
        return when {
            Files.isRegularFile(root) -> listOf(path to root.readText())
            Files.isDirectory(root) -> sqlFiles(root).map { it.toString() to it.readText() }
            else -> throw IllegalArgumentException("找不到 SQL 文件或目录：$path")
        }
    }

    /** 目录下全部 `*.sql`（递归、按路径字符串排序）。 */
    fun sqlFiles(dir: Path): List<Path> =
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".sql", ignoreCase = true) }
                .sorted()
                .toList()
        }

    /**
     * 一段 SQL（可含多条语句）→ 一组血缘模型。
     *
     * 走引擎的 [SqlEngine.analyzeAll]：整段语法错误返回 [Resolved.Unknown]；
     * 脚本里不可建模的语句被跳过（不硬塞）。每提取到的一条语句交给
     * [LineageBuilder.build] 建列级血缘。
     */
    fun models(
        sql: String,
        engine: SqlEngine,
        dialect: String?,
        schema: SchemaProvider?,
    ): Resolved<List<LineageModel>> =
        engine.analyzeAll(sql, ParseRequest(dialect = dialect))
            .map { statements -> statements.map { LineageBuilder.build(it, schema) } }

    /**
     * 展开 `--schema` 参数：既接**单个 DDL 文件**，也接**目录**（目录时取其下全部
     * `*.sql`，按路径排序，符合 [io.github.workeron9.ozmoz.lineage.schema.DdlFileSchemaProvider]
     * 的「先定义者胜」约定）。
     */
    fun schema(path: String): SchemaProvider {
        val root = Path(path)
        val files: List<Path> = when {
            Files.isRegularFile(root) -> listOf(root)
            Files.isDirectory(root) -> sqlFiles(root)
            else -> throw IllegalArgumentException("找不到 schema 文件或目录：$path")
        }
        require(files.isNotEmpty()) { "schema 路径下没有 *.sql 文件：$path" }
        return DdlFileSchemaProvider.fromFiles(
            id = "ddl:${files.first().fileName}",
            *files.toTypedArray(),
        )
    }
}
