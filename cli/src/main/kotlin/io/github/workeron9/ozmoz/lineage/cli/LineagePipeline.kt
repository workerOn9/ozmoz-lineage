package io.github.workeron9.ozmoz.lineage.cli

import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.calcite.CalciteEngine
import io.github.workeron9.ozmoz.lineage.engine.jooq.JooqEngine
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
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

    /** 引擎 id → 实例；未注册直接报错（比能力表硬：注册 = 能在 CLI 里选中）。 */
    fun engine(id: String): SqlEngine = when (id) {
        JSqlParserEngine.ID -> JSqlParserEngine()
        CalciteEngine.ID -> CalciteEngine()
        JooqEngine.ID -> JooqEngine()
        else -> error("未注册的引擎：$id")
    }

    /**
     * **血缘可用**的引擎 id 列表（`ozml lineage --engine` / `ozml matrix` 的展开源）。
     *
     * 计算：声明 `SEMANTIC_MODEL` 的注册引擎——`jsqlparser`（解析主力）+
     * `calcite`（方言转换主力，M3 补语义模型）。`jooq` 无语义模型，
     * 只走方言转换（`renderEngines()`），不进血缘矩阵。
     */
    val ENGINE_IDS: List<String> = listOf(JSqlParserEngine.ID, CalciteEngine.ID)

    /** 注册的引擎实例（`ozml matrix` 等按列表逐个跑的入口用）。 */
    fun engines(): List<SqlEngine> = ENGINE_IDS.map { engine(it) }

    /**
     * **可渲染**引擎实例（`ozml matrix --convert` 的方言转换矩阵用）：
     * 注册的引擎里声明 [io.github.workeron9.ozmoz.lineage.engine.Feature.DIALECT_RENDER] 的。
     * 与 [engines]（血缘主力，仅 [ENGINE_IDS]）分开——calcite / jooq 只做方言，
     * 没语义模型，不进血缘矩阵。
     */
    fun renderEngines(): List<SqlEngine> =
        allEngines().filter { it.capabilities.supports(io.github.workeron9.ozmoz.lineage.engine.Feature.DIALECT_RENDER) }

    /** 全部已注册引擎实例（parse / convert 等非血缘入口的完整面）。 */
    fun allEngines(): List<SqlEngine> =
        listOf(JSqlParserEngine.ID, CalciteEngine.ID, JooqEngine.ID).map { engine(it) }

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

    /**
     * **顺手收集**：`--schema` 未给出时，从输入文本（目录 / 文件 / stdin）里的
     * `CREATE TABLE` 建 schema——输入与 DDL 同源的目录不需要再显式 `--schema`。
     *
     * 走 [DdlFileSchemaProvider.parseTolerant]（坏文件跳过——读取侧的解析告警已
     * 覆盖它，schema 收集不二次报错）；没有任何 `CREATE TABLE` 时返回 null，
     * 行为与旧版一致（无 schema）。**显式 `--schema` 永远优先**：调用方只在
     * `schemaPath == null` 时才用本函数（DDL 是权威元数据，用户指了就以用户为准）。
     */
    fun autoSchema(inputs: List<Pair<String?, String>>): SchemaProvider? =
        DdlFileSchemaProvider.parseTolerant(id = "ddl:auto", sqlTexts = inputs.map { it.second }.toTypedArray())

    /**
     * `--no-source-edges` 的**出口侧过滤**（ Never-wrong 的镜像：不删语义，只裁展示）：
     * 把表级哨兵 `SOURCE` 边从模型里去掉，值血缘六类边剩五类。放在 cli（组合根）
     * 而不是 `LineageBuilder`：建模语义保持完整与中性，开关只影响入口层的输出与落库。
     * 边 id 保持原编号（出现空洞）——能对上未过滤版本的边号，便于人工比对。
     */
    fun dropSourceEdges(model: LineageModel): LineageModel =
        if (model.edges.none { it.kind == EdgeKind.SOURCE }) model
        else model.copy(edges = model.edges.filter { it.kind != EdgeKind.SOURCE })

    // ————— 增量更新（--incremental）的指纹 —————

    private const val SEPARATOR = "\u0000"

    /**
     * **上下文摘要**：会影响「同一段 SQL 解析出什么模型」的输入快照：
     * 引擎 id、方言、schema 快照、[noSourceEdges] 开关。schema 按 [schemaPath] 来路取摘要——
     * - 显式 `--schema`：DDL 文件路径 + 全部内容（权威元数据，改一个字就全失效）；
     * - auto：只把**含 `create table` 子串（大小写不敏感）**的输入文本入摘要。
     *   该子串是「能贡献 DDL」的必要条件（文件里没 `create table` 就一定抽不出
     *   表），所以无关文件的编辑不会全局失效；而真正增删 / 改 DDL 的文件，
     *   本身文本必然变化，摘要必变。
     */
    fun contextDigest(
        engineId: String,
        dialect: String?,
        schemaPath: String?,
        noSourceEdges: Boolean,
        inputs: List<Pair<String?, String>>,
    ): String {
        val schemaPart = if (schemaPath != null) {
            buildString {
                append("explicit")
                for (file in ddlFiles(schemaPath)) {
                    append(SEPARATOR).append(file).append(SEPARATOR).append(Files.readString(file))
                }
            }
        } else {
            inputs.asSequence()
                .filter { it.second.contains("create table", ignoreCase = true) }
                .joinToString(SEPARATOR) { it.second }
                .let { "auto$SEPARATOR$it" }
        }
        return sha256(engineId + SEPARATOR + (dialect ?: "") + SEPARATOR + "noSourceEdges=$noSourceEdges" + SEPARATOR + schemaPart)
    }

    /**
     * 单个来源的指纹 = 上下文摘要 + 该文件全文的 hash。同一文件在同一上下文下
     * 指纹不变 ⇒ 引擎 / 方言 / schema / 内容都没变 ⇒ 库里的模型可安全复用。
     */
    fun fingerprint(contextDigest: String, text: String): String = sha256(contextDigest + SEPARATOR + text)

    /** `--schema` 路径下的 DDL 文件（单文件或目录递归 `*.sql`，与 [schema] 同口径）。 */
    private fun ddlFiles(schemaPath: String): List<Path> =
        when {
            Files.isRegularFile(Path(schemaPath)) -> listOf(Path(schemaPath))
            Files.isDirectory(Path(schemaPath)) -> sqlFiles(Path(schemaPath))
            else -> throw IllegalArgumentException("找不到 schema 文件或目录：$schemaPath")
        }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
