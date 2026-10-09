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

    /** 容错解析：不完整的 SQL 也能给出部分树。 */
    ERROR_TOLERANT,

    /** 可导出归一化 AST。 */
    AST_EXPORT,

    /**
     * 可产出**多语句脚本**的语义模型——把一段 SQL 里的多条语句分别提取为
     * `SemanticStatement` 列表（无法建模的语句跳过，整脚本语法错误才失败）。
     * 未声明时 [SqlEngine.analyzeAll] 退化为单语句 [SqlEngine.analyze]。
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
