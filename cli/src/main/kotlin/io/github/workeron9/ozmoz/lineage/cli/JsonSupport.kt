package io.github.workeron9.ozmoz.lineage.cli

import io.github.workeron9.ozmoz.lineage.engine.ParseOutcome
import io.github.workeron9.ozmoz.lineage.graph.Direction
import io.github.workeron9.ozmoz.lineage.graph.ImpactResult
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import io.github.workeron9.ozmoz.lineage.ir.TableRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CLI 的 JSON 输出。使用 `ir` 的 `@Serializable` 契约——**契约只有一份**，
 * CLI 与将来的 HTTP（Ktor）共用同一套模型。
 */
internal object JsonSupport {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encodeAst(root: AstNode): String = json.encodeToString(AstNode.serializer(), root)

    fun encodeParseReport(outcome: ParseOutcome): String =
        json.encodeToString(ParseReport.serializer(), ParseReport.from(outcome))

    fun encodeLineageModel(model: LineageModel): String =
        json.encodeToString(LineageModel.serializer(), model)

    fun encodeImpactResult(result: ImpactResult, label: (String) -> String): String =
        json.encodeToString(ImpactReport.serializer(), ImpactReport.from(result, label))
}

/** `ozml parse --format json` 的稳定输出形状。 */
@Serializable
internal data class ParseReport(
    val root: AstNode,
    val tables: List<TableRef>,
    val diagnostics: List<Diagnostic>,
) {
    companion object {
        fun from(outcome: ParseOutcome): ParseReport = ParseReport(
            root = outcome.root,
            tables = outcome.tables,
            diagnostics = outcome.diagnostics,
        )
    }
}

/** `ozml impact --format json` 的稳定输出形状（`graph` 模块的模型不含序列化注解，此处做 CLI 侧镜像）。 */
@Serializable
internal data class ImpactReport(
    val origin: String,
    val direction: String,
    val depth: Int? = null,
    val truncated: Boolean,
    val nodes: List<ImpactReportNode>,
) {
    @Serializable
    internal data class ImpactReportNode(
        val id: String,
        val label: String,
        val distance: Int,
        val isOutput: Boolean,
    )

    companion object {
        fun from(result: ImpactResult, label: (String) -> String): ImpactReport = ImpactReport(
            origin = result.origin,
            direction = result.direction.name,
            depth = result.depth,
            truncated = result.truncated,
            nodes = result.nodes.map {
                ImpactReportNode(id = it.id, label = label(it.id), distance = it.distance, isOutput = it.isOutput)
            },
        )
    }
}
