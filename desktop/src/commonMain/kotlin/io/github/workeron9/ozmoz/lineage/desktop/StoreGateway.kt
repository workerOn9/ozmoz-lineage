package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.DesktopStoreState

/**
 * 本地设置 / 连接 / Provider 的持久化门面。落一个 JSON 文件
 * （`~/.ozml-lineage/desktop.json`），**不含凭据**——密码与 API Key 是 D3 的凭据存储事项。
 *
 * 读写都吞异常并回落到缺省：设置文件坏掉不该让整个 App 起不来（Never-wrong 的镜像）。
 */
public expect object StoreGateway {
    public fun load(): DesktopStoreState

    public fun save(state: DesktopStoreState)
}
