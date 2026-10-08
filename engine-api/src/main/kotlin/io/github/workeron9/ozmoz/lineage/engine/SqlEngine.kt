package io.github.workeron9.ozmoz.lineage.engine

import io.github.workeron9.ozmoz.lineage.engine.semantics.SemanticStatement
import io.github.workeron9.ozmoz.lineage.ir.Resolved

/**
 * SQL 引擎 SPI。
 *
 * **不统一 AST**：引擎只声明[能力][capabilities]并返回引擎无关的结果
 * （[ParseOutcome] 里的归一化树）。四个引擎的私有类型（JSqlParser `Statement`、
 * Calcite `SqlNode`/`RelNode`、ANTLR `ParseTree`、jOOQ `QueryPart`）
 * **不得出现在本接口或 `ir` 的任何公共签名里**。
 *
 * 实现约定：
 * - 适配器只依赖 `ir` + `engine-api`。
 * - 适配器内部允许「不够 Kotlin」（局部辅助函数包一层），可读性优先于风格。
 * - 引擎给出不出的结果返回 [Resolved.Unknown]，不用 null、不猜。
 */
public interface SqlEngine {

    /** 稳定标识：`jsqlparser` / `calcite` / `antlr4` / `jooq`。 */
    public val id: String

    /** 能力声明。路由靠它，不靠 try-catch。 */
    public val capabilities: EngineCapabilities

    /**
     * 解析一条 SQL。**必选**能力。
     *
     * 解析失败时不要抛异常——返回带 ERROR 诊断的 [ParseOutcome]，
     * 让调用方能拿到诊断与位置（Never-wrong / Lossless）。
     */
    public fun parse(sql: String, request: ParseRequest = ParseRequest.DEFAULT): ParseOutcome

    /**
     * 渲染为目标方言。**可选**能力。
     *
     * 默认实现用类型表达「不支持」，并带上 [EngineCapabilities.reason] 给出的原因——
     * 调用方因此不必先查能力表也能得到可解释的结果。
     */
    public fun render(request: RenderRequest): Resolved<String> {
        val reason = capabilities.reason(Feature.DIALECT_RENDER) ?: "unsupported"
        return Resolved.Unknown(reason)
    }

    /**
     * 提取**语句语义模型**（作用域 / 来源 / 输出列 / 表达式）。**可选**能力。
     *
     * 这是 `lineage` 模块的原料：比 [parse] 的归一化树更结构化、更少，
     * 只保留血缘需要的信息，认不出的部分落进 `SqlExpr.Unknown`，**绝不猜**。
     *
     * 与 [parse] 一致：解析或提取失败**不抛异常**，返回 `Resolved.Unknown(reason)`
     * 或带诊断的模型。默认实现用类型表达「不支持」。
     */
    public fun analyze(sql: String, request: ParseRequest = ParseRequest.DEFAULT): Resolved<SemanticStatement> =
        Resolved.Unknown(capabilities.reason(Feature.SEMANTIC_MODEL) ?: "unsupported")
}
