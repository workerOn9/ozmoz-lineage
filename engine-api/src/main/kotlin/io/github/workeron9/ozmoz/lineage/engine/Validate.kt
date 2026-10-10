package io.github.workeron9.ozmoz.lineage.engine

import io.github.workeron9.ozmoz.lineage.ir.TableRef
import io.github.workeron9.ozmoz.lineage.ir.TableSchema

/**
 * **Schema 视图**（engine-api 侧的最小面）：按表引用查表定义。
 *
 * 与 `schema` 模块的 `SchemaProvider.table` **同签名**——装配点用 SAM 转换零胶水：
 * `SchemaLookup { ref -> provider.table(ref) }`。engine-api 只依赖 `ir`，
 * 不能（也不该）依赖 `schema` 模块（那是它的上层消费者），所以这里放的是
 * 「校验器实际需要的那一个方法」而不是整个 SPI。
 *
 * Never-wrong：查不到返回 null（调用方决定记 unknown 还是报诊断），**绝不猜**。
 * 大小写 / 引号 / 默认 schema 的折叠规则在实现内部处理（本项目 v1：去引号 + 小写）。
 */
public fun interface SchemaLookup {

    /** 按 [ref] 查表定义；查不到（未收录 / 折叠后歧义）返回 null。 */
    public fun table(ref: TableRef): TableSchema?
}

/**
 * 一次 schema 校验请求。
 *
 * [schema] 是校验目标：没有它就没有「校验」可言（引擎侧返回 Unknown 而不是
 * 空跑一遍解析）。
 */
public data class ValidateRequest(
    val sql: String,
    val dialect: String? = null,
    val schema: SchemaLookup? = null,
) {
    public companion object {
        @JvmStatic
        public fun of(sql: String, schema: SchemaLookup): ValidateRequest =
            ValidateRequest(sql = sql, schema = schema)
    }
}
