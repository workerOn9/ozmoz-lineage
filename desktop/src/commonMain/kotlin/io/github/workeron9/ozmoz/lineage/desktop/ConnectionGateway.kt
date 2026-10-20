package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionProfile
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionResult
import io.github.workeron9.ozmoz.lineage.desktop.model.SchemaSnapshot

/**
 * JDBC 访问门面（D2 设计指导 P2）。**只读元数据**：打开连接、读 `DatabaseMetaData`
 * 的库表与列，不执行用户 SQL、不写库。
 *
 * [open] 在后台线程执行，结果经回调送回（回调线程 = 工作线程；写 Compose 状态是快照安全的）。
 * 打开成功的连接**留在内存里**（按 profile id 复用），使「浏览 schema → 跳转血缘」不必重连；
 * [close] 关掉它。
 */
public expect object ConnectionGateway {

    /** 打开（或复用）连接并读取 schema 快照。 */
    public fun open(profile: ConnectionProfile, password: String, onResult: (ConnectionResult, SchemaSnapshot?) -> Unit)

    /** 关闭并移除已缓存的连接（幂等）。 */
    public fun close(profileId: String)
}
