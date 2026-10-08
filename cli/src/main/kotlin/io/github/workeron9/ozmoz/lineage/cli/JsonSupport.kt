package io.github.workeron9.ozmoz.lineage.cli

import io.github.workeron9.ozmoz.lineage.engine.ParseOutcome
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
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
