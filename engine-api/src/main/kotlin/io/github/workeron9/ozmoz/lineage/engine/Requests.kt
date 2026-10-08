package io.github.workeron9.ozmoz.lineage.engine

/**
 * 一次解析请求。
 *
 * [dialect] 为 null 表示按引擎默认（ANSI）处理，不代表「随便挑一个」。
 */
public data class ParseRequest(
    val dialect: String? = null,
    val schemaId: String? = null,
) {
    public companion object {
        @JvmStatic
        public val DEFAULT: ParseRequest = ParseRequest()
    }
}

/**
 * 一次渲染（方言转换）请求。
 */
public data class RenderRequest(
    val sql: String,
    val fromDialect: String? = null,
    val toDialect: String? = null,
) {
    init {
        require(sql.isNotBlank()) { "RenderRequest.sql 不能为空" }
    }
}
