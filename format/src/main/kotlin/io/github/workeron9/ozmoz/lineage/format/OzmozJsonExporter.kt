package io.github.workeron9.ozmoz.lineage.format

import io.github.workeron9.ozmoz.lineage.ir.LineageModel
import kotlinx.serialization.json.Json

/**
 * 本项目的**稳定 JSON**：把 [LineageModel] 原样序列化。
 *
 * 形状与 CLI `--format json` 完全一致（同一个 `@Serializable` 契约、同一组
 * `Json` 选项），因此下游解析一套即可。因为契约只有一份、序列化顺序由声明顺序
 * 决定、无 `HashMap` 参与，同一模型必然产出逐字节相同的文本。
 */
public object OzmozJsonExporter : LineageExporter {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun export(model: LineageModel): String = json.encodeToString(LineageModel.serializer(), model)
}
