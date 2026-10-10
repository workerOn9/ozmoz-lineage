import { useAppStore } from '../../state/store'
import { AstDiffView } from './AstDiffView'

/**
 * AST 对比面板（web 设计稿 T4.2）：同一份 SQL 用左右两个引擎各跑一次
 * `/api/parse`，并排 diff 归一化树。方言沿用工具栏的全局输入（两侧同方言）。
 */

const SELECT_CLS =
  'rounded border border-neutral-700 bg-neutral-900 px-1.5 py-1 font-mono text-[12px] text-neutral-200'

export function AstDiffPanel() {
  const backend = useAppStore((s) => s.backend)
  const sql = useAppStore((s) => s.sql)
  const engines = useAppStore((s) => s.engines)
  const leftEngine = useAppStore((s) => s.diffLeftEngine)
  const rightEngine = useAppStore((s) => s.diffRightEngine)
  const result = useAppStore((s) => s.diffResult)
  const error = useAppStore((s) => s.diffError)
  const running = useAppStore((s) => s.diffRunning)
  const setDiffLeftEngine = useAppStore((s) => s.setDiffLeftEngine)
  const setDiffRightEngine = useAppStore((s) => s.setDiffRightEngine)
  const runDiff = useAppStore((s) => s.runDiff)

  const engineSelect = (value: string | null, onChange: (id: string | null) => void) => (
    <select
      className={SELECT_CLS}
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value || null)}
    >
      {engines.map((e) => (
        <option key={e.id} value={e.id}>
          {e.id}
        </option>
      ))}
    </select>
  )

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-neutral-800 px-3 py-1.5 text-[12px] text-neutral-400">
        <span className="font-medium text-neutral-300">AST 对比</span>
        <label className="flex items-center gap-1.5">
          左
          {engineSelect(leftEngine, setDiffLeftEngine)}
        </label>
        <label className="flex items-center gap-1.5">
          右
          {engineSelect(rightEngine, setDiffRightEngine)}
        </label>
        <button
          className="rounded bg-sky-700 px-3 py-1 text-[13px] text-white hover:bg-sky-600 disabled:cursor-not-allowed disabled:opacity-40"
          disabled={
            backend !== 'ok' || sql.trim() === '' || running || leftEngine == null || rightEngine == null
          }
          onClick={() => void runDiff()}
        >
          {running ? '对比中…' : '运行对比'}
        </button>
        {result != null && (
          <span>
            新增 <span className="text-emerald-300">{result.summary.added}</span> · 删除{' '}
            <span className="text-rose-300">{result.summary.removed}</span> · 变更{' '}
            <span className="text-amber-300">{result.summary.changed}</span>
          </span>
        )}
        <span className="ml-auto text-neutral-500">
          图例：· 相同 ~ 变更 − 删除 + 新增（点行定位到 SQL）
        </span>
        {error != null && <span className="w-full text-rose-300">{error}</span>}
      </div>
      <div className="min-h-0 flex-1">
        {result != null ? (
          <AstDiffView diff={result} leftTitle={leftEngine ?? ''} rightTitle={rightEngine ?? ''} />
        ) : (
          <div className="flex h-full items-center justify-center px-4 text-center text-sm text-neutral-500">
            {error != null
              ? '对比失败——见上方错误信息'
              : '选择左右两个引擎后点「运行对比」，并排查看同一 SQL 的两棵归一化树差异'}
          </div>
        )}
      </div>
    </div>
  )
}
