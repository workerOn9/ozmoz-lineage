package io.github.workeron9.ozmoz.lineage.engine.jooq

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL

/**
 * jOOQ **方言注册表**——方言 id → [SQLDialect]。
 *
 * **M3 承诺口径（ADR-0004 + 知识库 `04-调研/jOOQ-OSS方言覆盖实测.md`）**：
 * OSS edition（`org.jooq:jooq:3.21.9`）在运行时只有 15 个 `SQLDialect` 常量，
 * Oracle / Snowflake / BigQuery / SQL Server / DB2 / Hive / Spark 等**编译期就不存在**——
 * 不是「受限」，是枚举里根本没有。因此：
 *
 * - 只注册 **12 个可承诺的开源系方言** + `DEFAULT`（无方言族，对齐语料里的 `ansi`）；
 * - `CUBRID` / `IGNITE` 虽是非商业常量但已标记 `@Deprecated(forRemoval)`，**不注册**（不承诺将移除的东西）；
 * - 商业版方言一个不进：对外文档不预支 jOOQ 商业版的能力。
 *
 * 与 Calcite 主力（`engine-calcite` 的方言注册表）的分工：数仓系（Hive / Spark /
 * Snowflake / BigQuery / Trino 数仓侧 / DuckDB / Oracle / MSSQL…）由 Calcite 承担；
 * jOOQ 定位是**可选第二实现**——重点覆盖 Calcite 未列的关系库
 * （mariadb / sqlite / h2 / hsqldb / derby / firebird / clickhouse / yugabytedb）。
 *
 * **internal**：方言细节不进公共签名；外部一律走引擎能力表（`capabilities.dialects`）。
 */
internal object JooqDialects {

    /** 方言 id → jOOQ `SQLDialect`（id 已小写 + 下划线归一；先注册者胜）。 */
    private val SPECS: Map<String, SQLDialect> = mapOf(
        "mysql" to SQLDialect.MYSQL,
        "mariadb" to SQLDialect.MARIADB,
        "postgres" to SQLDialect.POSTGRES,
        "h2" to SQLDialect.H2,
        "hsqldb" to SQLDialect.HSQLDB,
        "derby" to SQLDialect.DERBY,
        "firebird" to SQLDialect.FIREBIRD,
        "sqlite" to SQLDialect.SQLITE,
        "duckdb" to SQLDialect.DUCKDB,
        "trino" to SQLDialect.TRINO,
        "clickhouse" to SQLDialect.CLICKHOUSE,
        "yugabytedb" to SQLDialect.YUGABYTEDB,
        // 无方言族：对齐语料里的 `ansi`（jOOQ 侧等价于不应用任何方言翻译）
        "ansi" to SQLDialect.DEFAULT,
    )

    /** 别名：对齐 Calcite 侧语料习惯（`postgresql` → `postgres`）。 */
    private val ALIASES: Map<String, String> = mapOf("postgresql" to "postgres")

    /** 已注册的方言 id（含别名）。 */
    private val IDS: Set<String> = SPECS.keys + ALIASES.keys

    /**
     * 缺省方言：`ANSI`（jOOQ 的无方言族 `DEFAULT`）——**源方言未给时用**：
     * 不给就按最中性的一档解析，与 Calcite 侧缺省 `calcite` 同思路。
     */
    public const val DEFAULT_DIALECT: String = "ansi"

    /** 已注册的方言 id（含别名）——`ozml convert --engine jooq --from/--to` 与能力表的取值。 */
    public fun ids(): Set<String> = IDS

    /** 按方言 id 取 jOOQ 方言；未注册返回 null（调用方显式报诊断，不猜一个替身）。 */
    public fun dialect(id: String): SQLDialect? {
        val normalized = id.trim().lowercase().replace('-', '_')
        val key = ALIASES[normalized] ?: normalized
        return SPECS[key]
    }

    /** 新建一个 jOOQ `DSLContext`（每方言一个；Settings 默认，logo/tip 已由 [JooqEngine] 静音）。 */
    internal fun context(id: String): DSLContext? = dialect(id)?.let { DSL.using(it) }
}
