import { useAppStore } from '../state/store'
import { EXAMPLES } from '../lib/examples'
import { DOCS_URL } from '../lib/links'

export function Toolbar() {
  const backend = useAppStore((s) => s.backend)
  const backendVersion = useAppStore((s) => s.backendVersion)
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
  const dataSource = useAppStore((s) => s.dataSource)
  const setDataSource = useAppStore((s) => s.setDataSource)
  const graphs = useAppStore((s) => s.graphs)
  const graphName = useAppStore((s) => s.graphName)
  const setGraphName = useAppStore((s) => s.setGraphName)
  const openGraph = useAppStore((s) => s.openGraph)
  const graphOpening = useAppStore((s) => s.graphOpening)

  const backendBadge =
    backend === 'ok' ? (
      <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2 py-0.5 text-[12px] text-emerald-700">
        <span className="h-2 w-2 rounded-full bg-emerald-500" />
        后端 v{backendVersion}
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
        >
          三栏
        </button>
        <button
          className={`border-l border-neutral-300 px-2 py-1 ${view === 'ast-diff' ? 'bg-neutral-900 text-white' : 'text-neutral-500 hover:bg-neutral-100'}`}
          onClick={() => setView('ast-diff')}
        >
          AST 对比
        </button>
      </div>
      <div className="ml-3 flex overflow-hidden rounded border border-neutral-300 text-[12px]">
        <button
          className={`px-2 py-1 ${dataSource === 'sql' ? 'bg-neutral-900 text-white' : 'text-neutral-500 hover:bg-neutral-100'}`}
          title="以左侧编辑器的 SQL 现建现查"
          onClick={() => void setDataSource('sql')}
        >
          SQL
        </button>
        <button
          className={`border-l border-neutral-300 px-2 py-1 ${dataSource === 'graph' ? 'bg-neutral-900 text-white' : 'text-neutral-500 hover:bg-neutral-100'}`}
          title="浏览 ozml lineage --graph 落库的 SQLite 图库（需后端以 --graph-root 启动）"
          onClick={() => void setDataSource('graph')}
        >
          本地图库
        </button>
      </div>
      {dataSource === 'graph' && (
        <>
          <select
            className="rounded border border-neutral-300 bg-white px-1.5 py-1 text-[12px] text-neutral-800 disabled:opacity-40"
            value={graphName ?? ''}
            disabled={graphs.length === 0}
            onChange={(e) => setGraphName(e.target.value || null)}
          >
            {graphs.length === 0 && <option value="">（无可用图库）</option>}
            {graphs.map((g) => (
              <option key={g.name} value={g.name}>
                {g.name}（{g.modelCount} 条）
              </option>
            ))}
          </select>
          <button
            className="rounded border border-neutral-300 px-2 py-1 text-[12px] text-neutral-600 hover:bg-neutral-100 disabled:cursor-not-allowed disabled:opacity-40"
            title="读取选中图库的整库血缘图（/api/lineage graph 模式）"
            disabled={backend !== 'ok' || graphName == null || graphOpening}
            onClick={() => void openGraph()}
          >
            {graphOpening ? '打开中…' : '打开'}
          </button>
        </>
      )}
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
      <label className="flex items-center gap-1.5 text-[12px] text-neutral-500">
        方言
        <input
          className="w-24 rounded border border-neutral-300 bg-white px-1.5 py-1 font-mono text-[12px] text-neutral-800"
          placeholder="可选"
          value={dialect}
          onChange={(e) => setDialect(e.target.value)}
        />
      </label>
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
