import { useAppStore } from '../state/store'

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
  const loadSample = useAppStore((s) => s.loadSample)
  const view = useAppStore((s) => s.view)
  const setView = useAppStore((s) => s.setView)

  const backendBadge =
    backend === 'ok' ? (
      <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-950 px-2 py-0.5 text-[12px] text-emerald-300">
        <span className="h-2 w-2 rounded-full bg-emerald-400" />
        后端 v{backendVersion}
      </span>
    ) : backend === 'checking' ? (
      <span className="inline-flex items-center gap-1.5 rounded-full bg-neutral-800 px-2 py-0.5 text-[12px] text-neutral-400">
        <span className="h-2 w-2 animate-pulse rounded-full bg-neutral-500" />
        连接中
      </span>
    ) : (
      <span
        className="inline-flex items-center gap-1.5 rounded-full bg-rose-950 px-2 py-0.5 text-[12px] text-rose-300"
        title="先启动 ozml serve（默认 8765 端口）"
      >
        <span className="h-2 w-2 rounded-full bg-rose-500" />
        未连接本地后端
      </span>
    )

  return (
    <div className="flex items-center gap-3 border-b border-neutral-800 bg-neutral-950 px-3 py-2">
      <span className="text-[14px] font-semibold text-neutral-100">ozmoz-lineage</span>
      {backendBadge}
      <div className="ml-3 flex overflow-hidden rounded border border-neutral-700 text-[12px]">
        <button
          className={`px-2 py-1 ${view === 'main' ? 'bg-neutral-700 text-neutral-100' : 'text-neutral-400 hover:bg-neutral-800'}`}
          onClick={() => setView('main')}
        >
          三栏
        </button>
        <button
          className={`border-l border-neutral-700 px-2 py-1 ${view === 'ast-diff' ? 'bg-neutral-700 text-neutral-100' : 'text-neutral-400 hover:bg-neutral-800'}`}
          onClick={() => setView('ast-diff')}
        >
          AST 对比
        </button>
      </div>
      <label className="ml-auto flex items-center gap-1.5 text-[12px] text-neutral-400">
        引擎
        <select
          className="rounded border border-neutral-700 bg-neutral-900 px-1.5 py-1 text-[12px] text-neutral-200"
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
      <label className="flex items-center gap-1.5 text-[12px] text-neutral-400">
        方言
        <input
          className="w-24 rounded border border-neutral-700 bg-neutral-900 px-1.5 py-1 font-mono text-[12px] text-neutral-200"
          placeholder="可选"
          value={dialect}
          onChange={(e) => setDialect(e.target.value)}
        />
      </label>
      <button
        className="rounded bg-sky-700 px-3 py-1 text-[13px] text-white hover:bg-sky-600 disabled:cursor-not-allowed disabled:opacity-40"
        disabled={backend !== 'ok' || sql.trim() === '' || running}
        onClick={() => void run()}
      >
        {running ? '解析中…' : '解析'}
      </button>
      <button
        className="rounded border border-neutral-700 px-2 py-1 text-[12px] text-neutral-300 hover:bg-neutral-800"
        onClick={loadSample}
      >
        示例 SQL
      </button>
    </div>
  )
}
