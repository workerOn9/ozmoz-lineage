package io.github.workeron9.ozmoz.lineage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.engine.jsqlparser.JSqlParserEngine
import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Severity
import java.io.File

/**
 * `ozml parse` —— 把一条 SQL 解析成归一化树。
 *
 * M0 验收项②：`ozml parse` 输出归一化树 JSON。
 */
public class ParseCommand : CliktCommand(name = "parse") {

    private val engineId: String by option("--engine", help = "解析引擎（当前仅 jsqlparser）")
        .choice(*SUPPORTED_ENGINES)
        .default(JSqlParserEngine.ID)

    private val dialect: String? by option("--dialect", help = "方言名（jsqlparser 无方言概念，仅记录）")

    private val file: String by option("-f", "--file", help = "SQL 文件路径，`-` 表示从标准输入读取")
        .required()

    private val format: String by option("--format", help = "输出格式：json / tree / ast")
        .choice("json", "tree", "ast")
        .default("json")

    override fun help(context: Context): String =
        "解析一条 SQL 并输出归一化树（不是统一 AST）。"

    override fun run() {
        val sql = readSql(file)
        val engine = engineFor(engineId)
        val outcome = engine.parse(sql, ParseRequest(dialect = dialect))

        when (format) {
            "tree" -> echo(renderTree(outcome.root))
            "ast" -> echo(JsonSupport.encodeAst(outcome.root))
            else -> echo(JsonSupport.encodeParseReport(outcome))
        }

        // 解析失败时以非零码退出，便于 CI 与脚本判断（Never-wrong：不静默成功）
        if (outcome.diagnostics.any { it.severity == Severity.ERROR }) {
            for (d in outcome.diagnostics) {
                // 走 Clikt 的 echo(err = true)，测试才能捕获（不是 System.err）
                echo("${d.severity} ${d.code}: ${d.message}", err = true)
            }
            throw ProgramResult(1)
        }
    }

    private fun readSql(path: String): String =
        if (path == "-") {
            System.`in`.bufferedReader().readText()
        } else {
            val f = File(path)
            require(f.isFile) { "找不到 SQL 文件：$path" }
            f.readText()
        }

    private fun engineFor(id: String): SqlEngine = when (id) {
        JSqlParserEngine.ID -> JSqlParserEngine()
        else -> error("未注册的引擎：$id")
    }

    private fun renderTree(root: AstNode): String = buildString {
        fun walk(node: AstNode, depth: Int) {
            append("  ".repeat(depth))
            append(node.type)
            if (node.text.isNotBlank()) append("  «").append(node.text.replace("\n", " ")).append("»")
            node.span?.let { append("  @").append(it.start.line).append(':').append(it.start.column) }
            append('\n')
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
    }

    public companion object {
        private val SUPPORTED_ENGINES = arrayOf(JSqlParserEngine.ID)
    }
}
