import { useMemo } from 'react'
import { useAppStore } from '../state/store'
import { EXAMPLES } from '../lib/examples'
import { DOCS_URL } from '../lib/links'

export function Toolbar() {
  const backend = useAppStore((s) => s.backend)
  const engines = useAppStore((s) => s.engines)
  const engineId = useAppStore((s) => s.engineId)
  const dialect = useAppStore((s) => s.dialect)
  const sql = useAppStore((s) => s.sql)
  const running = useAppStore((s) => s.running)
  const setEngineId = useAppStore((s) => s.setEngineId)
  const setDialect = useAppStore((s) => s.setDialect)
  const run = useAppStore((s) => s.run)
  const loadExample = useAppStore((s) => s.loadExample)
  const view = useAppStore((s) => s.view)
  const setView = useAppStore((s) => s.setView)
  const aggregate = useAppStore((s) => s.aggregate)
  const setAggregate = useAppStore((s) => s.setAggregate)
  const formatSql = useAppStore((s) => s.format)
  const formatting = useAppStore((s) => s.formatting)
  const validate = useAppStore((s) => s.validate)
  const validating = useAppStore((s) => s.validating)
  const schemaOpen = useAppStore((s) => s.schemaOpen)
  const setSchemaOpen = useAppStore((s) => s.setSchemaOpen)

  const engineDialects = useMemo(
    () => engines.find((e) => e.id === engineId)?.dialects ?? [],
    [engines, engineId],
  )
  // 别名（`postgres` → `postgresql`）与正名是**同一个方言**：只列正名、把别名标在括号里
  // ——否则下拉里会出现看起来重复的两项（用户实测反馈：「结果一样，区别在哪」）。
  const dialectAliases = useMemo(
    () => engines.find((e) => e.id === engineId)?.dialectAliases ?? {},
    [engines, engineId],
  )
  // jsqlparser 等引擎注册表只有 'ansi' 占位（实际无方言概念），不把它当可选方言展示。
  const dialectOptions = useMemo(() => {
    if (engineDialects.length === 0) return []
    if (engineDialects.length === 1 && engineDialects[0] === 'ansi') {
      return [{ value: '', label: '通用 SQL' }]
    }
    const aliasesOf = new Map<string, string[]>()
    for (const [alias, canonical] of Object.entries(dialectAliases)) {
      aliasesOf.set(canonical, [...(aliasesOf.get(canonical) ?? []), alias])
    }
    const canonicals = engineDialects.filter((d) => !(d in dialectAliases))
    return [
      { value: '', label: '默认' },
      ...canonicals.map((d) => {
        const aliases = aliasesOf.get(d)
        return { value: d, label: aliases != null ? `${d}（= ${aliases.join(' / ')}）` : d }
      }),
    ]
  }, [engineDialects, dialectAliases])

  const backendBadge =
    backend === 'ok' ? (
      <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2 py-0.5 text-[12px] text-emerald-700">
        <span className="h-2 w-2 rounded-full bg-emerald-500" />
        已连接后端
      </span>
    ) : backend === 'checking' ? (
      <span className="inline-flex items-center gap-1.5 rounded-full bg-neutral-100 px-2 py-0.5 text-[12px] text-neutral-500">
        <span className="h-2 w-2 animate-pulse rounded-full bg-neutral-400" />
        连接中
      </span>
    ) : (
      <span
        className="inline-flex items-center gap-1.5 rounded-full bg-rose-50 px-2 py-0.5 text-[12px] text-rose-600"
        title="先启动 ozml serve（默认 8765 端口）"
      >
        <span className="h-2 w-2 rounded-full bg-rose-500" />
        未连接本地后端
      </span>
    )

  return (
    <div className="flex items-center gap-3 border-b border-neutral-200 bg-white px-3 py-2">
      <a className="flex items-center gap-2" href="/" title="返回首页">
        <img src="/favicon.svg" alt="" className="h-5 w-5" />
        <span className="text-[14px] font-semibold text-neutral-900">ozmoz-lineage</span>
      </a>
      {backendBadge}
      <div className="ml-3 flex overflow-hidden rounded border border-neutral-300 text-[12px]">
        <button
          className={`px-2 py-1 ${view === 'main' ? 'bg-neutral-900 text-white' : 'text-neutral-500 hover:bg-neutral-100'}`}
          onClick={() => setView('main')}
          title="两栏工作台：左 SQL，右工具面板（归一化树 / 血缘图）"
        >
          工作台
        </button>
        <button
          className={`border-l border-neutral-300 px-2 py-1 ${view === 'ast-diff' ? 'bg-neutral-900 text-white' : 'text-neutral-500 hover:bg-neutral-100'}`}
          onClick={() => setView('ast-diff')}
        >
          AST 对比
        </button>
      </div>
      <label className="ml-auto flex items-center gap-1.5 text-[12px] text-neutral-500">
        引擎
        <select
          className="rounded border border-neutral-300 bg-white px-1.5 py-1 text-[12px] text-neutral-800"
          value={engineId ?? ''}
          onChange={(e) => setEngineId(e.target.value || null)}
        >
          {engines.map((e) => (
            <option key={e.id} value={e.id}>
              {e.id}
            </option>
          ))}
        </select>
      </label>
      {/* 方言选择：**该引擎没有真方言可选时整块不显示**——jsqlparser 无方言概念，
          只给一个「通用 SQL」占位，摆在那儿是废话选项（用户实测反馈）。 */}
      {dialectOptions.length > 1 && (
        <label
          className="flex items-center gap-1.5 text-[12px] text-neutral-500"
          title="方言选项取决于当前引擎；无方言概念的引擎不显示本项"
        >
          方言
          <select
            className="w-44 rounded border border-neutral-300 bg-white px-1.5 py-1 font-mono text-[12px] text-neutral-800"
            value={dialect}
            onChange={(e) => setDialect(e.target.value)}
          >
            {dialectOptions.map((d) => (
              <option key={d.value} value={d.value}>
                {d.label}
              </option>
            ))}
          </select>
        </label>
      )}
      <label
        className="flex items-center gap-1.5 text-[12px] text-neutral-500"
        title="多语句输入合并为一张血缘图（/api/lineage aggregate=true，opt-in 契约）；合并图无单语句归一化树"
      >
        <input
          type="checkbox"
          className="accent-sky-600"
          checked={aggregate}
          onChange={(e) => setAggregate(e.target.checked)}
        />
        聚合多语句
      </label>
      <button
        className="rounded bg-brand px-3 py-1 text-[13px] text-white hover:bg-brand-dark disabled:cursor-not-allowed disabled:opacity-40"
        disabled={backend !== 'ok' || sql.trim() === '' || running}
        onClick={() => void run()}
      >
        {running ? '解析中…' : '解析'}
      </button>
      <button
        className="rounded border border-neutral-300 px-2 py-1 text-[12px] text-neutral-600 hover:bg-neutral-100 disabled:cursor-not-allowed disabled:opacity-40"
        title="结合 schema 做语义校验（calcite SqlValidator）：未知表 / 未知列 / 列歧义等 → 诊断面板「校验」页"
        disabled={backend !== 'ok' || sql.trim() === '' || validating}
        onClick={() => void validate()}
      >
        {validating ? '校验中…' : '校验'}
      </button>
      <button
        className={`rounded border px-2 py-1 text-[12px] ${
          schemaOpen
            ? 'border-sky-500 bg-sky-50 text-sky-700'
            : 'border-neutral-300 text-neutral-600 hover:bg-neutral-100'
        }`}
        title="展开 / 收起 schema（CREATE TABLE 文本）输入面板"
        onClick={() => setSchemaOpen(!schemaOpen)}
      >
        Schema
      </button>
      <select
        className="rounded border border-neutral-300 bg-white px-1.5 py-1 text-[12px] text-neutral-600"
        value=""
        title="载入一个演示场景（SQL / schema / 聚合开关一并切好）"
        onChange={(e) => {
          if (e.target.value !== '') loadExample(e.target.value)
        }}
      >
        <option value="">示例…</option>
        {EXAMPLES.map((ex) => (
          <option key={ex.id} value={ex.id}>
            {ex.title}
          </option>
        ))}
      </select>
      <button
        className="rounded border border-neutral-300 px-2 py-1 text-[12px] text-neutral-600 hover:bg-neutral-100 disabled:cursor-not-allowed disabled:opacity-40"
        title="对当前 SQL 打 /api/format 排版（子句换行 + 规范化），回写编辑器；注释丢失时有损提示"
        disabled={backend !== 'ok' || sql.trim() === '' || formatting}
        onClick={() => void formatSql()}
      >
        {formatting ? '排版中…' : '格式化'}
      </button>
      <a
        className="rounded border border-neutral-300 px-2 py-1 text-[12px] text-neutral-600 hover:bg-neutral-100"
        href={DOCS_URL}
        target="_blank"
        rel="noreferrer"
        title="工具百科：引擎能力 / 方言目录与两张实测矩阵（数据由 CI 生成，每格可溯源到 commit）"
      >
        文档 ↗
      </a>
    </div>
  )
}
