package io.github.workeron9.ozmoz.lineage.desktop.model

import kotlinx.serialization.Serializable

/** 连接类型。只影响表单缺省值与 JDBC URL 的拼法；驱动本身由 classpath / 驱动目录提供。 */
@Serializable
public enum class ConnectionKind(public val label: String) {
    SQLITE("SQLite"),
    POSTGRESQL("PostgreSQL"),
    MYSQL("MySQL"),
    CLICKHOUSE("ClickHouse"),
    DUCKDB("DuckDB"),
    CUSTOM("自定义 JDBC"),
}

/** 连接状态。灰 = 未连 / 绿 = 已连 / 红 = 失败——空态不撒谎。 */
public enum class ConnectionStatus { UNKNOWN, CONNECTING, ONLINE, ERROR }

/**
 * 连接**元数据**。**不含密码**：密码只在内存里活到本次连接，不落盘
 * （ADR-0022 决定 5 的凭据存储是 D3 的事，D2 不假装有加密）。
 */
@Serializable
public data class ConnectionProfile(
    val id: String,
    val name: String,
    val kind: ConnectionKind = ConnectionKind.SQLITE,
    val host: String = "",
    val port: String = "",
    val database: String = "",
    val user: String = "",
    /** 高级模式：非空时覆盖 host/port/database。 */
    val jdbcUrl: String = "",
    /** PostgreSQL AGE 图插件开关（D3 才真正用）。 */
    val ageGraph: Boolean = false,
) {
    /** 表单值 → JDBC URL。缺什么就给一条能跑的缺省（SQLite 走文件路径）。 */
    public fun resolvedUrl(): String {
        if (jdbcUrl.isNotBlank()) return jdbcUrl
        return when (kind) {
            ConnectionKind.SQLITE -> "jdbc:sqlite:${database.ifBlank { ":memory:" }}"
            ConnectionKind.POSTGRESQL -> "jdbc:postgresql://${host.ifBlank { "localhost" }}:${port.ifBlank { "5432" }}/${database}"
            ConnectionKind.MYSQL -> "jdbc:mysql://${host.ifBlank { "localhost" }}:${port.ifBlank { "3306" }}/${database}"
            ConnectionKind.CLICKHOUSE -> "jdbc:clickhouse://${host.ifBlank { "localhost" }}:${port.ifBlank { "8123" }}/${database}"
            ConnectionKind.DUCKDB -> "jdbc:duckdb:${database.ifBlank { ":memory:" }}"
            ConnectionKind.CUSTOM -> jdbcUrl
        }
    }
}

/** 连接测试 / 取元数据的结果。失败必须带原因，不能只说「连不上」。 */
public data class ConnectionResult(
    val ok: Boolean,
    val message: String,
)

/**
 * 可自动补的 JDBC 驱动（连接因缺驱动失败时由 [io.github.workeron9.ozmoz.lineage.desktop.ConnectionGateway.driverPlan] 给出）。
 * [sizeLabel] 是下载体积的量级，给用户点之前一个预期（DuckDB 的驱动有几十 MB）。
 */
public data class DriverPlan(
    val name: String,
    val sizeLabel: String,
)

/** Schema 树里的一张表 / 视图。 */
public data class DbTable(
    val schema: String?,
    val name: String,
    val kind: String,
) {
    public val qualifiedName: String get() = if (schema.isNullOrBlank()) name else "$schema.$name"
}

/** 表的一列。[nullable] 为 null = 驱动没给结论（`columnNullableUnknown`），不猜。 */
public data class DbColumn(
    val name: String,
    val type: String,
    val nullable: Boolean?,
    val ordinal: Int,
)

/** 连接页的运行时状态（不落盘）。[schemaColumns] 是整库快照的列索引。 */
public data class ConnectionRuntime(
    val status: ConnectionStatus = ConnectionStatus.UNKNOWN,
    val message: String? = null,
    val tables: List<DbTable> = emptyList(),
    val selectedTable: DbTable? = null,
    val schemaColumns: Map<String, List<DbColumn>> = emptyMap(),
) {
    /** 当前选中表的列。 */
    public val columns: List<DbColumn>
        get() = selectedTable?.let { schemaColumns[it.qualifiedName] }.orEmpty()
}
