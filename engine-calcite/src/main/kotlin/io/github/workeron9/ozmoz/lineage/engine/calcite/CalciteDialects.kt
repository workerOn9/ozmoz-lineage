package io.github.workeron9.ozmoz.lineage.engine.calcite

import org.apache.calcite.config.Lex
import org.apache.calcite.sql.SqlDialect
import org.apache.calcite.sql.dialect.BigQuerySqlDialect
import org.apache.calcite.sql.dialect.CalciteSqlDialect
import org.apache.calcite.sql.dialect.DuckDBSqlDialect
import org.apache.calcite.sql.dialect.HiveSqlDialect
import org.apache.calcite.sql.dialect.MssqlSqlDialect
import org.apache.calcite.sql.dialect.MysqlSqlDialect
import org.apache.calcite.sql.dialect.OracleSqlDialect
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.dialect.SnowflakeSqlDialect
import org.apache.calcite.sql.dialect.SparkSqlDialect
import org.apache.calcite.sql.dialect.TrinoSqlDialect

/**
 * Calcite **方言注册表**——方言 id → [SqlDialect]（渲染目标）+ [Lex]（解析器配置）。
 *
 * **两个关键事实写死在此（均有本机实测依据，见知识库 `04-调研/Calcite-unparse保真度抽测.md`）：**
 *
 * 1. **解析器的 quoting 是「单一值而非多选」**（抽测 E5 的工程坑）：
 *    用反引号配置解析双引号标识符会失败，反之亦然。所以**解析必须按源方言配**，
 *    re-parse 必须按目标方言配，不能指望一个宽松配置通吃。
 *    - 反引号系（mysql / hive / spark → `Lex.MYSQL`）；
 *    - BigQuery（`Lex.BIG_QUERY`，同为反引号 + 单引号字符串风格）；
 *    - 方括号系（mssql / tsql → `Lex.SQL_SERVER`）；
 *    - 双引号系（其余 → `Lex.ORACLE`）。
 * 2. **`AnsiSqlDialect` 有缺陷（抽测 E4）**：渲染标识符用反引号而非双引号，
 *    连 Calcite 自己的解析器都读不回（re-parse 0/20）——**有意不注册**，
 *    未实测的方言不进对外可承诺清单（ADR-0004）。
 *
 * 对外只承诺本表注册的方言（全部在抽测里 render 全绿或有意排除），注册即「实测过」。
 * **internal**：方言细节不进公共签名；外部一律走引擎能力表（`capabilities.dialects`）。
 */
internal object CalciteDialects {

    /** 一个方言的完整解析 / 渲染配置。 */
    internal data class Spec(
        public val id: String,
        public val dialect: SqlDialect,
        public val lex: Lex,
    )

    /** 主注册表：方言 id → 配置（先注册者胜，别名只登记在 [ALIASES] 里）。 */
    private val SPECS: Map<String, Spec> = buildMap {
        register(id = "mysql", lex = Lex.MYSQL, dialect = MysqlSqlDialect.DEFAULT)
        register(id = "hive", lex = Lex.MYSQL, dialect = HiveSqlDialect.DEFAULT)
        register(id = "spark", lex = Lex.MYSQL, dialect = SparkSqlDialect.DEFAULT)
        register(id = "bigquery", lex = Lex.BIG_QUERY, dialect = BigQuerySqlDialect.DEFAULT)
        register(id = "postgresql", lex = Lex.ORACLE, dialect = PostgresqlSqlDialect.DEFAULT)
        register(id = "oracle", lex = Lex.ORACLE, dialect = OracleSqlDialect.DEFAULT)
        register(id = "snowflake", lex = Lex.ORACLE, dialect = SnowflakeSqlDialect.DEFAULT)
        register(id = "duckdb", lex = Lex.ORACLE, dialect = DuckDBSqlDialect.DEFAULT)
        register(id = "trino", lex = Lex.ORACLE, dialect = TrinoSqlDialect.DEFAULT)
        register(id = "mssql", lex = Lex.SQL_SERVER, dialect = MssqlSqlDialect.DEFAULT)
        // 无方言场景的默认档：Calcite 自身的 ANSI 风格（双引号），抽测 20/20 等价。
        register(id = "calcite", lex = Lex.ORACLE, dialect = CalciteSqlDialect.DEFAULT)
    }

    /** 方言别名：对外 id（如语料里的 `tsql`）→ 主 id。 */
    private val ALIASES: Map<String, String> = mapOf(
        "tsql" to "mssql",
        "big_query" to "bigquery",
        "postgres" to "postgresql",
        // 语料里的 `ansi`（无方言族）：解析按 Lex.ORACLE、渲染按 CalciteSqlDialect——
        // 与主档 `calcite` 同配置。**不等于**缺陷方言类 AnsiSqlDialect（仍不使用）。
        "ansi" to "calcite",
    )

    /** 已注册的方言 id（含别名）——`ozml convert --from/--to` 与引擎能力表的合法取值。 */
    public val IDS: Set<String> = SPECS.keys + ALIASES.keys

    /** 缺省方言（`--from` 未给时）：Calcite 自身的双引号 ANSI 风格。 */
    public const val DEFAULT_DIALECT: String = "calcite"

    /** 按方言 id 取配置；未注册返回 null（调用方显式报诊断，不猜一个替身）。 */
    public fun spec(id: String): Spec? {
        val normalized = id.trim().lowercase().replace('-', '_')
        val key = ALIASES[normalized] ?: normalized
        return SPECS[key]
    }

    private fun MutableMap<String, Spec>.register(id: String, lex: Lex, dialect: SqlDialect) {
        put(id, Spec(id = id, dialect = dialect, lex = lex))
    }
}
