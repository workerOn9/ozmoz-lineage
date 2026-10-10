package io.github.workeron9.ozmoz.lineage.server

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * `/api` 的 JSON 编码配置——与 CLI 的 `JsonSupport` 同一取向
 * （`encodeDefaults = true` + `explicitNulls = false`：默认值可见、省略 null 字段），
 * 但 HTTP 响应不美化（传输体积优先；CLI 面向人读才 pretty）。
 */
internal object ServerJson {

    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** List 的 serializer（`/api/engines` 返回数组用）。 */
    fun <T> listOf(serializer: KSerializer<T>): KSerializer<List<T>> = ListSerializer(serializer)
}
