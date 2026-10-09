package io.github.workeron9.ozmoz.lineage.format

/**
 * 全部内置导出器的**注册表**（确定性顺序：按 [LineageExporterInfo.id] 排序）。
 *
 * 调用方按 id 查表，不写 `when`：
 * ```kotlin
 * val info = LineageExporters.byId("mermaid")
 * val text = info?.exporter?.export(model)
 * ```
 * 新增一种格式只需在 [all] 里加一项，CLI 与 HTTP 自动可见。
 */
public object LineageExporters {

    /** 内置导出器（顺序即注册顺序，供 `--list-formats` 之类展示）。 */
    public val all: List<LineageExporterInfo> = listOf(
        LineageExporterInfo(
            id = "openlineage",
            mime = "application/json",
            description = "OpenLineage 运行事件（含 ColumnLineageDatasetFacet）",
            exporter = OpenLineageExporter,
        ),
        LineageExporterInfo(
            id = "mermaid",
            mime = "text/vnd.mermaid",
            description = "Mermaid flowchart 图（可直接进 Markdown）",
            exporter = MermaidExporter,
        ),
        LineageExporterInfo(
            id = "dot",
            mime = "text/vnd.graphviz",
            description = "Graphviz DOT 图",
            exporter = DotExporter,
        ),
        LineageExporterInfo(
            id = "cypher",
            mime = "application/x-cypher",
            description = "Neo4j Cypher 建图语句",
            exporter = CypherExporter,
        ),
        LineageExporterInfo(
            id = "ozmoz-json",
            mime = "application/json",
            description = "本项目的稳定 JSON（LineageModel 原样序列化）",
            exporter = OzmozJsonExporter,
        ),
    )

    private val byId: Map<String, LineageExporterInfo> = all.associateBy { it.id.lowercase() }

    /** 按 id（大小写不敏感）查导出器；未注册返回 null——调用方决定报错还是回退。 */
    public fun byId(id: String): LineageExporterInfo? = byId[id.lowercase()]

    /** 全部已注册 id（稳定顺序）。 */
    public val ids: List<String> get() = all.map { it.id }
}
