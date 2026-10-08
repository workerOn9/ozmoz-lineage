package io.github.workeron9.ozmoz.lineage.engine

import io.github.workeron9.ozmoz.lineage.ir.AstNode
import io.github.workeron9.ozmoz.lineage.ir.Diagnostic
import io.github.workeron9.ozmoz.lineage.ir.Severity
import io.github.workeron9.ozmoz.lineage.ir.TableRef

/**
 * 一次解析的结果——**引擎无关**。
 *
 * 引擎私有的事实（Calcite 已验证的 RelNode 摘要、jOOQ 渲染出的 SQL 等）只以
 * 「字符串键 → 值」的形式放在 [engineFacts] 里，**不进入公共类型**（类型边界铁律）。
 * 下游若依赖某个私有事实，等于自愿放弃引擎可替换性，必须显式取用。
 *
 * [root] 是归一化树（见 `AstNode`），不是统一 AST。
 */
public data class ParseOutcome(
    val diagnostics: List<Diagnostic> = emptyList(),
    val tables: List<TableRef> = emptyList(),
    val root: AstNode,
    val engineFacts: Map<String, Any> = emptyMap(),
) {
    /** 是否有 ERROR 级诊断。有则结果应被视为「解析失败但保留了可诊断的信息」。 */
    public val hasErrors: Boolean
        get() = diagnostics.any { it.severity == Severity.ERROR }

    public companion object {
        @JvmStatic
        public fun failure(
            root: AstNode,
            diagnostics: List<Diagnostic>,
        ): ParseOutcome = ParseOutcome(diagnostics = diagnostics, root = root)
    }
}
