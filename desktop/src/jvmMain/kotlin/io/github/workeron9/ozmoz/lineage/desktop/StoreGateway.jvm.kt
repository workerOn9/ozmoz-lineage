package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.DesktopStoreState
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * [StoreGateway] 的 JVM 实现：`~/.ozml-lineage/desktop.json`。
 * 文件坏掉 / 读不到一律回落缺省——设置问题不该让 App 起不来。
 */
public actual object StoreGateway {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val path: Path
        get() = Storage.root().resolve("desktop.json")

    public actual fun load(): DesktopStoreState = runCatching {
        val file = path
        if (!Files.isRegularFile(file)) return DesktopStoreState()
        json.decodeFromString(DesktopStoreState.serializer(), Files.readString(file))
    }.getOrElse { DesktopStoreState() }

    public actual fun save(state: DesktopStoreState) {
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(DesktopStoreState.serializer(), state))
        }
    }
}
