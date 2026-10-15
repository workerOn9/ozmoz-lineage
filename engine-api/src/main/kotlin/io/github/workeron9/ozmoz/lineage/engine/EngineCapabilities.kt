package io.github.workeron9.ozmoz.lineage.engine

/**
 * 引擎能力清单——前端 / CLI 用它做「该引擎能不能干这件事」的**路由**，
 * 而不是靠 try-catch 撞墙后再猜。
 */
public enum class Feature {
    /** 基础解析。 */
    PARSE,

    /** 从语句中提取表引用。 */
    EXTRACT_TABLES,

    /** 按方言解析（非 ANSI 的方言专属语法）。 */
    DIALECT_PARSE,

    /** 渲染为目标方言。 */
    DIALECT_RENDER,

    /** 结合 schema 做语义校验。 */
    VALIDATE_SCHEMA,

    /** 字段级血缘。 */
    FIELD_LINEAGE,

    /**
     * 可产出**引擎无关的语句语义模型**（`semantics.SemanticStatement`）——
     * 即「作用域 / 来源 / 输出列 / 表达式」的结构化描述，供 `lineage` 模块消费。
     * 这是列级血缘的原料，比 `AST_EXPORT` 更结构化、更少。
     */
    SEMANTIC_MODEL,

    /** 美化输出。 */
    PRETTY_PRINT,

    /**
     * **逐语句容错**：多语句脚本里一条语句语法错误时，不拖垮整段——坏语句降级为
     * 诊断（带位置），好语句照常产出（见 [SqlEngine.analyzeScript]）。
     *
     * 口径**只到脚本级**：**不**承诺「编辑中不完整 SQL 也能给出部分树」的那种单语句
     * 部分恢复（JSqlParser 无误差恢复文法）；单语句坏输入仍是不折不扣的失败。
     */
    ERROR_TOLERANT,

    /** 可导出归一化 AST。 */
    AST_EXPORT,

    /**
     * 可产出**多语句脚本**的语义模型——把一段 SQL 里的多条语句分别提取为
     * `SemanticStatement` 列表（无法建模的语句跳过，整脚本语法错误才失败）。
     * 未声明时 [SqlEngine.analyzeAll] 退化为单语句 [SqlEngine.analyze]。
     *
     * 与 [ERROR_TOLERANT] 正交：声明本项只要求「能逐条提取」，**不**要求坏语句不拖垮
     * 整段（那是 [ERROR_TOLERANT] 的事，出口是 [SqlEngine.analyzeScript]）。
     */
    MULTI_STATEMENT,
}

/**
 * 引擎的能力声明。
 *
 * [reasons] 记录**为什么不支持**某项能力（如「该方言无 render」「edition 限制」），
 * 让「不支持」成为一个有解释的结果，而不是一句 unsupported。
 */
public data class EngineCapabilities(
    val features: Set<Feature>,
    val dialects: Set<String> = emptySet(),
    /**
     * **方言别名 → 正名**（如 `postgres → postgresql`）。
     *
     * [dialects] 是**可接受的取值全集**（含别名，语料 / `--from` / `--to` 都用得上），
     * 这份映射回答的是另一个问题：**哪些 id 只是同一个方言的另一个写法**。
     * 展示层（`/api/engines` 的消费方）据此只列正名，并把别名标在正名后面——
     * 否则下拉里会出现「postgresql / postgres」这种看起来像两个方言的重复项。
     */
    val dialectAliases: Map<String, String> = emptyMap(),
    val reasons: Map<Feature, String> = emptyMap(),
) {
    public fun supports(feature: Feature): Boolean = feature in features

    /** 不支持时返回原因；支持时返回 null。 */
    public fun reason(feature: Feature): String? =
        if (feature in features) null else reasons[feature]

    public companion object {
        @JvmStatic
        public fun of(vararg features: Feature): EngineCapabilities = EngineCapabilities(features.toSet())
    }
}
