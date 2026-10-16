package io.github.workeron9.ozmoz.lineage.pipeline

import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.FormatOutcome
import io.github.workeron9.ozmoz.lineage.engine.FormatRequest
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.ParseOutcome
import io.github.workeron9.ozmoz.lineage.engine.SchemaLookup
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.ValidateRequest
import io.github.workeron9.ozmoz.lineage.engine.calcite.CalciteEngine
import io.github.workeron9.ozmoz.lineage.engine.jooq.JooqEngine
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
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
 * [LineagePipeline.models] 的产出：模型 + **逐语句容错**产生的诊断。
 *
 * [diagnostics] 只在 `tolerant = true` 且脚本里确有坏语句时非空（见
 * [Feature.ERROR_TOLERANT]）；严格路径（缺省）恒为空，形状恒定便于调用方统一处理。
 */
public data class BuiltModels(
    val models: List<LineageModel>,
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * `cli` / `server` / `desktop` **三方共用的组合根**（ADR-0022 决定 4）：引擎注册、
 * 输入读取、schema 解析、多语句 → 血缘模型。原 `cli.LineagePipeline`（internal）
 * 与 `server.ServerPipeline`（镜像）收口于此；入口层只保留各自的错误策略与传输形状。
 *
 * 放在独立模块而不是 `lineage`：目录遍历与引擎选择是**入口层**的职责，
 * `lineage` 只依赖 `ir` + `engine-api` + `schema` SPI，不反向依赖任何引擎适配器。
 */
public object LineagePipeline {

    /** 默认引擎 id；调用方未指明时使用（HTTP 层 / 桌面缺省）。 */
    public const val DEFAULT_ENGINE: String = JSqlParserEngine.ID

    /** 引擎 id → 实例；未注册直接报错（CLI 口径：注册 = 能在 CLI 里选中）。 */
    public fun engine(id: String): SqlEngine = when (id) {
        JSqlParserEngine.ID -> JSqlParserEngine()
        CalciteEngine.ID -> CalciteEngine()
        JooqEngine.ID -> JooqEngine()
        else -> error("未注册的引擎：$id")
    }

    /** 引擎 id → 实例；未注册返回 null（HTTP / MCP 层转 `unknown_engine`）。 */
    public fun engineById(id: String): SqlEngine? = when (id) {
        JSqlParserEngine.ID -> JSqlParserEngine()
        CalciteEngine.ID -> CalciteEngine()
        JooqEngine.ID -> JooqEngine()
        else -> null
    }

    /**
     * **血缘可用**的引擎 id 列表（`ozml lineage --engine` / `ozml matrix` 的展开源）。
     *
     * 计算：声明 `SEMANTIC_MODEL` 的注册引擎——`jsqlparser`（解析主力）+
     * `calcite`（方言转换主力，M3 补语义模型）+ `jooq`（2026-10-15 起 QOM 语义模型）。
     * 三者都进血缘矩阵（`jooq` 的集合运算 / MERGE / VALUES 不建模，如实入格）。
     */
    public val ENGINE_IDS: List<String> = listOf(JSqlParserEngine.ID, CalciteEngine.ID, JooqEngine.ID)

    /** 注册的引擎实例（`ozml matrix` 等按列表逐个跑的入口用）。 */
    public fun engines(): List<SqlEngine> = ENGINE_IDS.map { engine(it) }

    /**
     * **可渲染**引擎实例（`ozml matrix --convert` 的方言转换矩阵用）：
     * 注册的引擎里声明 [Feature.DIALECT_RENDER] 的。
     * 与 [engines]（血缘矩阵，[ENGINE_IDS]）分开——两个矩阵的引擎面本来就不同
     * （血缘矩阵按 `SEMANTIC_MODEL`，转换矩阵按 `DIALECT_RENDER`）。
     */
    public fun renderEngines(): List<SqlEngine> =
        allEngines().filter { it.capabilities.supports(Feature.DIALECT_RENDER) }

    /** 全部已注册引擎实例（parse / convert 等非血缘入口的完整面）。 */
    public fun allEngines(): List<SqlEngine> = engines()

    /**
     * 输入路径 → (来源文件, SQL 文本) 列表：
     * - `-` → 单个 `(null, stdin)`；
     * - 普通文件 → `(路径, 内容)`；
     * - 目录 → 递归取 `*.sql`（按路径排序，保证确定性）。
     */
    public fun readInputs(path: String): List<Pair<String?, String>> {
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
    public fun sqlFiles(dir: Path): List<Path> =
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".sql", ignoreCase = true) }
                .sorted()
                .toList()
        }

    /**
     * 一段 SQL（可含多条语句）→ 一组血缘模型 + 逐语句诊断。
     *
     * 走引擎的 [SqlEngine.analyzeAll]（严格）：整段语法错误返回 [Resolved.Unknown]；
     * 脚本里不可建模的语句被跳过（不硬塞）。每提取到的一条语句交给
     * [LineageBuilder.build] 建列级血缘。
     *
     * [tolerant] 为真时改走 [SqlEngine.analyzeScript]（[Feature.ERROR_TOLERANT]）：
     * 坏语句降级为 [BuiltModels.diagnostics]（带位置），好语句照常建模型——调用方
     * 负责把诊断报出去（CLI 口径：WARN）+ 在一条都没建出来时按 `Unknown` / 空结果收尾。
     *
     * schema 解析策略由调用方定（显式 DDL 永远优先，否则 [autoSchema] 收集）——
     * CLI 从全部输入文件收集，HTTP / 桌面从单段文本收集，各不相同。
     */
    public fun models(
        sql: String,
        engine: SqlEngine,
        dialect: String?,
        schema: SchemaProvider?,
        tolerant: Boolean = false,
    ): Resolved<BuiltModels> =
        if (tolerant) {
            engine.analyzeScript(sql, ParseRequest(dialect = dialect)).map { outcome ->
                BuiltModels(
                    models = outcome.statements.map { LineageBuilder.build(it, schema) },
                    diagnostics = outcome.diagnostics,
                )
            }
        } else {
            engine.analyzeAll(sql, ParseRequest(dialect = dialect)).map { statements ->
                BuiltModels(models = statements.map { LineageBuilder.build(it, schema) })
            }
        }

    /** 逐语句诊断的人读行（`--tolerant`）：`WARN <来源>:<行>:<列> <code>: <message>`。 */
    public fun diagnosticLine(source: String?, diagnostic: Diagnostic): String = buildString {
        append("WARN ").append(source ?: "<stdin>")
        diagnostic.span?.let { append(':').append(it.start.line).append(':').append(it.start.column) }
        append(' ').append(diagnostic.code).append(": ").append(diagnostic.message)
    }

    /**
     * 展开 `--schema` 参数：既接**单个 DDL 文件**，也接**目录**（目录时取其下全部
     * `*.sql`，按路径排序，符合 [DdlFileSchemaProvider] 的「先定义者胜」约定）。
     */
    public fun schema(path: String): SchemaProvider {
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
     * 解析**内联 DDL**（HTTP 请求体 `schema` 字段 / MCP 参数）。走**严格**版
     * [DdlFileSchemaProvider.parse]——DDL 是用户显式给出的权威元数据，解析失败
     * 直接抛（HTTP 层转 400 `invalid_schema`），不静默缺表。
     */
    public fun schemaFromInlineDdl(text: String): SchemaProvider =
        DdlFileSchemaProvider.parse(id = "ddl:inline", sqlTexts = arrayOf(text))

    /**
     * **顺手收集**：`--schema` 未给出时，从输入文本里的 `CREATE TABLE` 建 schema——
     * 输入与 DDL 同源的目录不需要再显式 `--schema`。
     *
     * 走 [DdlFileSchemaProvider.parseTolerant]（坏文件跳过——读取侧的解析告警已
     * 覆盖它，schema 收集不二次报错）；没有任何 `CREATE TABLE` 时返回 null，
     * 行为与旧版一致（无 schema）。**显式 schema 永远优先**：调用方只在
     * 未显式给出时才用本函数（DDL 是权威元数据，用户指了就以用户为准）。
     */
    public fun autoSchema(sqlTexts: List<String>): SchemaProvider? =
        DdlFileSchemaProvider.parseTolerant(id = "ddl:auto", sqlTexts = sqlTexts.toTypedArray())

    /** `engine.parse` 的薄代理（HTTP / MCP `/api/parse` 用，保持语义模型 ⊥ 归一化树的分工）。 */
    public fun parse(sql: String, engine: SqlEngine, dialect: String?): ParseOutcome =
        engine.parse(sql, ParseRequest(dialect = dialect))

    /**
     * SQL 排版（[Feature.PRETTY_PRINT]，ADR-0014 的 HTTP 出口）。
     *
     * 与 `ozml format` 同一组合方式：走引擎的 `format`（内容来源是引擎自己的规范化
     * 渲染，不重写渲染器）。引擎未申报能力 / 语法错误 → [Resolved.Unknown]，
     * 由 HTTP 层转成 `{sql: null, diagnostics: [format.skipped]}`（形状恒定）。
     */
    public fun format(
        sql: String,
        engine: SqlEngine,
        dialect: String?,
        multiline: Boolean,
    ): Resolved<FormatOutcome> =
        engine.format(FormatRequest(sql = sql, dialect = dialect, multiline = multiline))

    /**
     * 结合 schema 做语义校验（[Feature.VALIDATE_SCHEMA]，ADR-0013 的 HTTP 出口）。
     *
     * [schema] 显式给出时优先；否则由调用方用 [autoSchema] 从 SQL 文本收集，
     * 收不到则传 null——引擎按 Never-wrong 返回 `Unknown`（校验没跑成）。
     *
     * 装配点用 SAM 转换把 [SchemaProvider] 包成 engine-api 的 [SchemaLookup]——同签名，零胶水。
     */
    public fun validate(
        sql: String,
        engine: SqlEngine,
        dialect: String?,
        schema: SchemaProvider?,
    ): Resolved<List<Diagnostic>> =
        engine.validate(
            ValidateRequest(
                sql = sql,
                dialect = dialect,
                schema = schema?.let { provider -> SchemaLookup { ref -> provider.table(ref) } },
            ),
        )

    /**
     * `--no-source-edges` 的**出口侧过滤**（ Never-wrong 的镜像：不删语义，只裁展示）：
     * 把表级哨兵 `SOURCE` 边从模型里去掉，值血缘六类边剩五类。放在组合根
     * 而不是 `LineageBuilder`：建模语义保持完整与中性，开关只影响入口层的输出与落库。
     * 边 id 保持原编号（出现空洞）——能对上未过滤版本的边号，便于人工比对。
     */
    public fun dropSourceEdges(model: LineageModel): LineageModel =
        if (model.edges.none { it.kind == EdgeKind.SOURCE }) model
        else model.copy(edges = model.edges.filter { it.kind != EdgeKind.SOURCE })

    // ————— 增量更新（--incremental）的指纹 —————

    private const val SEPARATOR = "\u0000"

    /**
     * **上下文摘要**：会影响「同一段 SQL 解析出什么模型」的输入快照：
     * 引擎 id、方言、schema 快照、[noSourceEdges] 开关、[tolerant] 开关。schema 按
     * [schemaPath] 来路取摘要——
     * - 显式 `--schema`：DDL 文件路径 + 全部内容（权威元数据，改一个字就全失效）；
     * - auto：只把**含 `create table` 子串（大小写不敏感）**的输入文本入摘要。
     *   该子串是「能贡献 DDL」的必要条件（文件里没 `create table` 就一定抽不出
     *   表），所以无关文件的编辑不会全局失效；而真正增删 / 改 DDL 的文件，
     *   本身文本必然变化，摘要必变。
     */
    public fun contextDigest(
        engineId: String,
        dialect: String?,
        schemaPath: String?,
        noSourceEdges: Boolean,
        tolerant: Boolean,
        sqlTexts: List<String>,
    ): String {
        val schemaPart = if (schemaPath != null) {
            buildString {
                append("explicit")
                for (file in ddlFiles(schemaPath)) {
                    append(SEPARATOR).append(file).append(SEPARATOR).append(Files.readString(file))
                }
            }
        } else {
            sqlTexts.asSequence()
                .filter { it.contains("create table", ignoreCase = true) }
                .joinToString(SEPARATOR)
                .let { "auto$SEPARATOR$it" }
        }
        return sha256(
            engineId + SEPARATOR + (dialect ?: "") +
                SEPARATOR + "noSourceEdges=$noSourceEdges" +
                SEPARATOR + "tolerant=$tolerant" +
                SEPARATOR + schemaPart,
        )
    }

    /**
     * 单个来源的指纹 = 上下文摘要 + 该文件全文的 hash。同一文件在同一上下文下
     * 指纹不变 ⇒ 引擎 / 方言 / schema / 内容都没变 ⇒ 库里的模型可安全复用。
     */
    public fun fingerprint(contextDigest: String, text: String): String = sha256(contextDigest + SEPARATOR + text)

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
