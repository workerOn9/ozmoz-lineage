package io.github.workeron9.ozmoz.lineage.conformance

import io.github.workeron9.ozmoz.lineage.engine.Feature
import io.github.workeron9.ozmoz.lineage.engine.ParseRequest
import io.github.workeron9.ozmoz.lineage.engine.RenderRequest
import io.github.workeron9.ozmoz.lineage.engine.SqlEngine
import io.github.workeron9.ozmoz.lineage.ir.Resolved
import java.time.Instant

/**
 * **方言转换矩阵**生成器（计划 §6 M3 验收项「convert 对 20 组方言对给出 diff 视图」）。
 *
 * 判定口径（而非只跑 `analyzeAll` 的 [MatrixRunner]，两者并立）：
 * - **方言对 × 语料的匹配规则**：语料 case 的 `dialect` 必须等于方言对的 `from`
 *   才进该格（一条 hive 语料按 `from=mysql` 转写是无意义实验，不进格）。
 * - **方言对 × 引擎的支持规则**：`from` 与 `to` 都必须在引擎声明
 *   （`capabilities.dialects`）里——不支持的（引擎，方言对）组合**不出格**，
 *   coverage 按实际跑的组合统计；这样 calcite 的方言对不会在 jOOQ 列上凭空记失败。
 * - 每格走引擎 `render`（自带 render-verified 门禁）：`Known` → `ok=true` 且
 *   **带 `sql` 成品**；`Unknown` / 异常 → `ok=false`（reason 截 120 字）。
 *   任何异常都吞进格，不让一条坏语料打断整场运行。
 *
 * @param pairs   方言对清单（缺省 [DEFAULT_PAIRS]，M3 口径为 20 组）。
 * @param commit  仓库 commit（可溯源），由调用方（CLI `--commit` / CI）传入。
 */
public class ConvertMatrixRunner(
    private val pairs: List<SqlPair> = DEFAULT_PAIRS,
    private val commit: String? = null,
) {

    public fun run(entries: List<CorpusEntry>, engines: List<SqlEngine>, corpusRoot: String): ConvertMatrix {
        val renderEngines = engines.filter { it.capabilities.supports(Feature.DIALECT_RENDER) }

        val cells = ArrayList<ConvertCell>()
        var pairCount = 0
        for (engine in renderEngines) {
            val dialects = engine.capabilities.dialects
            for (pair in pairs.distinct()) {
                // 引擎不支持的对不出格（连指标带格子），不出空失败格搅浑 diff 视图。
                if (normalized(pair.from) !in dialects || normalized(pair.to) !in dialects) continue
                pairCount++
                val sample = entries.filter { normalized(it.case.dialect) == normalized(pair.from) }
                for (entry in sample.sortedBy { it.path }) {
                    cells += runCell(engine, entry, pair)
                }
            }
        }

        val sorted = cells.sortedWith(
            compareBy({ it.engine }, { it.fromDialect }, { it.toDialect }, { it.caseId }),
        )
        return ConvertMatrix(
            meta = ConvertMatrixMeta(
                generatedAt = Instant.now().toString(),
                commit = commit,
                corpusRoot = corpusRoot,
                caseCount = entries.size,
                engineCount = renderEngines.size,
                pairCount = pairCount,
            ),
            coverage = coverage(sorted),
            cells = sorted,
        )
    }

    private fun runCell(engine: SqlEngine, entry: CorpusEntry, pair: SqlPair): ConvertCell {
        return try {
            when (val rendered = engine.render(
                RenderRequest(
                    sql = entry.case.sql,
                    fromDialect = entry.case.dialect,
                    toDialect = pair.to,
                ),
            )) {
                is Resolved.Known -> ConvertCell(
                    engine = engine.id,
                    caseId = entry.case.id,
                    sourcePath = entry.path,
                    fromDialect = pair.from,
                    toDialect = pair.to,
                    ok = true,
                    sql = rendered.value,
                    reason = null,
                )

                is Resolved.Unknown -> ConvertCell(
                    engine = engine.id,
                    caseId = entry.case.id,
                    sourcePath = entry.path,
                    fromDialect = pair.from,
                    toDialect = pair.to,
                    ok = false,
                    sql = null,
                    reason = truncate(rendered.reason),
                )
            }
        } catch (e: Exception) {
            ConvertCell(
                engine = engine.id,
                caseId = entry.case.id,
                sourcePath = entry.path,
                fromDialect = pair.from,
                toDialect = pair.to,
                ok = false,
                sql = null,
                reason = truncate("${e.javaClass.simpleName}: ${e.message ?: "未知异常"}"),
            )
        }
    }

    /** 方言对覆盖计数（(engine, from, to) 已排好序——直接聚合，内层含 0-failed 项）。 */
    private fun coverage(cells: List<ConvertCell>): List<ConvertPairCoverage> {
        val grouped = LinkedHashMap<String, MutableList<ConvertCell>>()
        for (cell in cells) {
            val key = "${cell.engine}|${cell.fromDialect}|${cell.toDialect}"
            grouped.getOrPut(key) { ArrayList() }.add(cell)
        }
        return grouped.map { (_, scoped) ->
            val head = scoped.first()
            ConvertPairCoverage(
                engine = head.engine,
                from = head.fromDialect,
                to = head.toDialect,
                cases = scoped.size,
                ok = scoped.count { it.ok },
                failed = scoped.count { !it.ok },
            )
        }
    }

    private fun normalized(dialect: String): String = dialect.trim().lowercase().replace('-', '_')

    private fun truncate(reason: String): String =
        reason.take(REASON_MAX_LENGTH) + if (reason.length > REASON_MAX_LENGTH) "…" else ""

    public companion object {
        private const val REASON_MAX_LENGTH: Int = 120

        /**
         * **M3 口径的 20 组方言对**——横跨两档引擎（calcite 数仓系 + jOOQ 关系库），
         * 覆盖双向互转、反引号/方括号/双引号三 quoting、分页/别名/大小写改写形态。
         * 引擎不注册的方言由规则自动剔除对应（引擎，对）组合。
         */
        public val DEFAULT_PAIRS: List<SqlPair> = listOf(
            // ansi（两档引擎的缺省族）
            "ansi>calcite", "calcite>ansi", "ansi>mysql", "ansi>postgresql",
            // 反引号 ⇄ 双引号（quoting 改写）
            "mysql>postgresql", "postgresql>mysql",
            "ansi>trino", "trino>ansi",
            // 数仓系（只有 calcite 承担；jOOQ OSS 没有这些方言）
            "mysql>hive", "hive>mysql",
            "mysql>spark", "spark>mysql",
            "mysql>bigquery", "bigquery>mysql",
            // Oracle / MSSQL（只有 calcite）
            "mysql>oracle", "oracle>mysql",
            "mysql>tsql", "tsql>mysql",
            "postgresql>oracle", "oracle>postgresql",
        ).mapNotNull { SqlPair.parse(it) }
    }
}
