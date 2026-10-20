package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionProfile
import io.github.workeron9.ozmoz.lineage.desktop.model.ConnectionResult
import io.github.workeron9.ozmoz.lineage.desktop.model.DriverPlan
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

    /**
     * 这条连接**缺少驱动、且该驱动有已知下载源**时给出计划；否则 null。
     * 只做本地判断（不联网），可随时调。
     */
    public fun driverPlan(profile: ConnectionProfile): DriverPlan?

    /**
     * 下载 [driverPlan] 指向的驱动 jar 到 `~/.ozml-lineage/drivers`（校验 SHA-1 后落盘），
     * 后台线程；完成后调用方自行重连。装不上的原因是给人看的，不是异常。
     */
    public fun installDriver(profile: ConnectionProfile, onResult: (Boolean, String) -> Unit)
}
