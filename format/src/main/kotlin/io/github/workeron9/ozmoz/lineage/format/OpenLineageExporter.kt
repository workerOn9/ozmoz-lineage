package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.EdgeKind
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.TransformKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 把 [LineageModel] 导出成 **OpenLineage RunEvent** JSON。
 *
 * ## 确定性（硬性）
 *
 * 导出文本**逐字节可复现**：`eventType` / `eventTime` / `run.runId` 都写死成占位值，
 * 不含任何随机数或当前时间。真实运行上下文（真实 `eventTime`、真实 `runId`、
 * 目标命名空间与作业名）**应由调用方覆盖**——用 [of] 拿到一个带真实上下文的
 * [LineageExporter] 实例，object 本身始终产出确定性文本（golden 测试与 diff 的前提）。
 *
 * ## inputs / outputs 的推导（不猜）
 *
 * 模型没有「输入 / 输出数据集」字段，只有边。于是：
 * - `inputs`：`kind != SOURCE` 的边里 `fromColumn.table` 非空的去重表名，**加上**
 *   `SOURCE` 边 `fromColumn.name`（表级哨兵，`name` 即表限定名）；按字符串升序去重。
 * - `outputs`：**仅当** `isOutput == true` 的列收敛到唯一一个非空 `table` 时才产一个输出数据集
 *   （`columnLineage` facet 挂在它上面）；否则 `outputs` 为空数组。**绝不发明表名**。
 *
 * ## ColumnLineageDatasetFacet 的 `type` / `subtype`
 *
 * 严格按 OpenLineage 规范原文映射 [TransformKind]（见 [openLineageTransform]）：
 * `DIRECT/IDENTITY`、`DIRECT/TRANSFORMATION`、`DIRECT/AGGREGATION`、
 * `INDIRECT/CONDITIONAL`、`INDIRECT/WINDOW`、`INDIRECT/JOIN`、
 * `INDIRECT/FILTER`、`INDIRECT/GROUP_BY`、`INDIRECT/SORT`。
 * `SOURCE` 边不进 `columnLineage`（表级血缘已由 inputs/outputs 表达）。
 */
public object OpenLineageExporter : LineageExporter {

    /** 本项目作为 OpenLineage producer 的规范 URL。 */
    public const val PRODUCER: String = "https://github.com/workerOn9/ozmoz-lineage"

    /** OpenLineage 1-2-0 的 ColumnLineageDatasetFacet 规范 URL。 */
    public const val COLUMN_LINEAGE_SCHEMA_URL: String =
        "https://openlineage.io/spec/facets/1-2-0/ColumnLineageDatasetFacet.json"

    /** 确定性占位：固定事件类型。 */
    public const val DEFAULT_EVENT_TYPE: String = "COMPLETE"

    /** 确定性占位：Unix 纪元起点，可被真实事件时间覆盖。 */
    public const val DEFAULT_EVENT_TIME: String = "1970-01-01T00:00:00.000Z"

    /** 确定性占位：全零 UUID，可被真实 runId 覆盖。 */
    public const val DEFAULT_RUN_ID: String = "00000000-0000-0000-0000-000000000000"

    /** 默认作业命名空间。 */
    public const val DEFAULT_NAMESPACE: String = "ozmoz-lineage"

    /** 模型没给 `meta.engineId` 时的作业名兜底。 */
    public const val DEFAULT_JOB_NAME: String = "ozmoz-lineage"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun export(model: LineageModel): String = render(
        model = model,
        eventTime = DEFAULT_EVENT_TIME,
        runId = DEFAULT_RUN_ID,
        namespace = DEFAULT_NAMESPACE,
        jobName = model.meta.engineId ?: DEFAULT_JOB_NAME,
    )

    /**
     * 用**真实运行上下文**配置一个导出器实例：CLI / Ktor 在真正投递事件时用它，
     * 把 [eventTime] / [runId] 换成当次运行的值。object 本身保留确定性默认值。
     *
     * [jobName] 为 null 时回退到 `model.meta.engineId`，再退到 [DEFAULT_JOB_NAME]。
     */
    public fun of(
        eventTime: String,
        runId: String,
        namespace: String = DEFAULT_NAMESPACE,
        jobName: String? = null,
    ): LineageExporter = LineageExporter { model ->
        render(
            model = model,
            eventTime = eventTime,
            runId = runId,
            namespace = namespace,
            jobName = jobName ?: model.meta.engineId ?: DEFAULT_JOB_NAME,
        )
    }

    private fun render(
        model: LineageModel,
        eventTime: String,
        runId: String,
        namespace: String,
        jobName: String,
    ): String {
        val root = buildJsonObject {
            put("eventType", DEFAULT_EVENT_TYPE)
            put("eventTime", eventTime)
            putJsonObject("run") { put("runId", runId) }
            putJsonObject("job") {
                put("namespace", namespace)
                put("name", jobName)
            }
            put("inputs", buildInputs(model, namespace))
            put("outputs", buildOutputs(model, namespace))
            put("producer", PRODUCER)
            put("schemaURL", COLUMN_LINEAGE_SCHEMA_URL)
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    /** inputs = 非 SOURCE 边的 `fromColumn.table` ∪ SOURCE 边哨兵 `name`，升序去重。 */
    private fun buildInputs(model: LineageModel, namespace: String) = buildJsonArray {
        val names = sortedSetOf<String>()
        names += GraphProjection.inputTableNames(model)
        names += GraphProjection.sentinelNodes(model).map { it.name }
        for (name in names) {
            add(
                buildJsonObject {
                    put("namespace", namespace)
                    put("name", name)
                },
            )
        }
    }

    /** outputs = 唯一输出表一个数据集（带 columnLineage facet）；推不出唯一表则空数组。 */
    private fun buildOutputs(model: LineageModel, namespace: String) = buildJsonArray {
        val outputTable = GraphProjection.uniqueOutputTable(model) ?: return@buildJsonArray
        add(
            buildJsonObject {
                put("namespace", namespace)
                put("name", outputTable)
                putJsonObject("facets") {
                    put("columnLineage", columnLineageFacet(model, namespace))
                }
            },
        )
    }

    private fun columnLineageFacet(model: LineageModel, namespace: String) = buildJsonObject {
        put("_producer", PRODUCER)
        put("_schemaURL", COLUMN_LINEAGE_SCHEMA_URL)
        put("fields", buildFields(model, namespace))
    }

    /**
     * `fields`：输出列名 → `inputFields`。
     *
     * 每个输入字段按（表名, 列名）去重后**排序**；同一字段的多种变换合并去重，
     * 再按（type, subtype, description, masking）排序。输出列名按键升序，
     * 保证逐字节确定性。`SOURCE` 边与 `fromColumn.table` 为空的边不参与
     * （前者是表级血缘，后者拿不到表名——不编）。
     */
    private fun buildFields(model: LineageModel, namespace: String): JsonObject {
        // 输出列名 → (表名, 列名) → 变换集合（LinkedHashMap 保留插入序，输出前显式排序）
        val byOutput = sortedMapOf<String, MutableMap<Pair<String, String>, MutableSet<JsonObject>>>()
        for (edge in model.edges) {
            if (edge.kind == EdgeKind.SOURCE) continue
            val table = edge.fromColumn.table ?: continue
            val output = edge.toColumn.name
            val transformation = openLineageTransform(edge.transform, edge.expression) ?: continue
            val inputs = byOutput.getOrPut(output) { LinkedHashMap() }
            val field = inputs.getOrPut(table to edge.fromColumn.name) {
                sortedSetOf<JsonObject>(transformationComparator)
            }
            field.add(transformation)
        }
        return buildJsonObject {
            for ((output, inputs) in byOutput) {
                put(
                    output,
                    buildJsonObject {
                        put(
                            "inputFields",
                            buildJsonArray {
                                for ((tableAndColumn, transformations) in sortedByTableAndColumn(inputs)) {
                                    val (table, column) = tableAndColumn
                                    add(
                                        buildJsonObject {
                                            put("namespace", namespace)
                                            put("name", table)
                                            put("field", column)
                                            put(
                                                "transformations",
                                                buildJsonArray {
                                                    for (t in transformations) add(t)
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }
    }

    /**
     * [TransformKind] → OpenLineage `type` / `subtype`。映射表**照抄规范原文**，不得自创。
     *
     * `CONSTANT` / `UNKNOWN` / `SOURCE` 不产生变换项：前两者语义上无边，后者由
     * inputs/outputs 表达，返回 null 表示「不写进 columnLineage」。
     */
    private fun openLineageTransform(kind: TransformKind, expression: String?): JsonObject? {
        val (type, subtype) = when (kind) {
            TransformKind.DIRECT -> "DIRECT" to "IDENTITY"
            TransformKind.EXPRESSION -> "DIRECT" to "TRANSFORMATION"
            TransformKind.AGGREGATE -> "DIRECT" to "AGGREGATION"
            TransformKind.CASE_BRANCH -> "INDIRECT" to "CONDITIONAL"
            TransformKind.WINDOW -> "INDIRECT" to "WINDOW"
            TransformKind.JOIN_KEY -> "INDIRECT" to "JOIN"
            TransformKind.FILTER_PREDICATE -> "INDIRECT" to "FILTER"
            TransformKind.GROUPING -> "INDIRECT" to "GROUP_BY"
            TransformKind.ORDERING -> "INDIRECT" to "SORT"
            TransformKind.CONSTANT, TransformKind.UNKNOWN, TransformKind.SOURCE -> return null
        }
        return buildJsonObject {
            put("type", type)
            put("subtype", subtype)
            put("description", expression ?: "")
            put("masking", false)
        }
    }

    /** 同一输出列下的 inputFields 按（表名, 列名）排序，保证确定性。 */
    private fun sortedByTableAndColumn(
        inputs: Map<Pair<String, String>, MutableSet<JsonObject>>,
    ): List<Map.Entry<Pair<String, String>, MutableSet<JsonObject>>> =
        inputs.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))

    /** 变换对象的确定性顺序：type → subtype → description → masking。 */
    private val transformationComparator: Comparator<JsonObject> =
        compareBy { transformationKey(it) }

    private fun transformationKey(obj: JsonObject): String = listOf("type", "subtype", "description", "masking")
        .joinToString(SEPARATOR) { (obj[it] as? JsonPrimitive)?.content.orEmpty() }

    /** 排序键分隔符，取不可能出现在 type / subtype 枚举里的控制字符。 */
    private const val SEPARATOR = "\u0000"
}
