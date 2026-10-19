package io.github.workeron9.ozmoz.lineage.desktop

import io.github.workeron9.ozmoz.lineage.desktop.model.Analysis

/**
 * 引擎访问的**唯一**门面（ADR-0022 决定 2/3）——`commonMain` 只见这一个声明。
 *
 * jvm 侧 `actual` 进程内直调编排层（`:pipeline` → 引擎模块），不走 HTTP；
 * 将来做平板 / 浏览器客户端时换一个 `actual`（改为走 HTTP），UI 与呈现状态一行不改。
 */
public expect object EngineGateway {

    /** **血缘可用**引擎 id 列表（与 CLI / `ozml serve` 同一份注册面）。 */
    public val engineIds: List<String>

    /**
     * 一段 SQL（可含多条语句）→ 呈现状态。**不抛异常**：失败落在
     * [Analysis.note] 与 [Analysis.issues]（界面必须显式说出来，不能让空图冒充「没问题」）。
     *
     * [connectionId] 非空时，用那条已打开连接的 JDBC 元数据当**权威 schema**
     * （`SELECT *` 展开、裸列消歧），SQL 文本里的 DDL 退为兜底。
     */
    public fun analyze(sql: String, engineId: String, tolerant: Boolean, connectionId: String? = null): Analysis

    /** 原生文件选择（AWT FileDialog）；取消 / headless 返回 null。 */
    public fun pickSqlFile(): OpenedFile?
}

/** 一次「打开文件」的产物：文件名（只用于显示）+ 文本。 */
public data class OpenedFile(val name: String, val sql: String)
