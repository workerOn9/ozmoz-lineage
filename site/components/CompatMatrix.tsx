// 兼容性矩阵（解析 / 血缘语义模型）：`ozml matrix` 的实测快照，逐格可溯源到 commit。
// 数据来自 `.data/dataset.json`（由 site/scripts/normalize.mjs 从 CI 产物规整而来）。
import { useMemo, useState } from 'react'
import { withBase } from '@rspress/core/runtime'
import dataset from '../.data/dataset.json'
import DataMissing from './DataMissing'

const MAX_ROWS = 200

type Cell = {
  dialect: string
  engine: string
  caseId: string
  kind: string
  features: string[]
  ok: boolean
  reason?: string
}

export default function CompatMatrix() {
  const matrix = dataset.matrix as {
    available: boolean
    commit: string | null
    generatedAt: string | null
    caseCount: number
    engineCount: number
    engines: string[]
    coverage: Array<{ dialect: string; ok: number; total: number; engines: Array<{ engine: string; ok: number; total: number }> }>
    failures: unknown[]
    cells: Cell[]
    rawUrl: string
  }

  const [dialect, setDialect] = useState('全部')
  const [engine, setEngine] = useState('全部')
  const [status, setStatus] = useState('全部')

  const filtered = useMemo(() => {
    if (!matrix.available) return []
    return matrix.cells.filter(
      (c) =>
        (dialect === '全部' || c.dialect === dialect) &&
        (engine === '全部' || c.engine === engine) &&
        (status === '全部' || (status === '失败') === !c.ok),
    )
  }, [matrix, dialect, engine, status])

  if (!matrix.available) return <DataMissing what="兼容性矩阵" />

  const shown = filtered.slice(0, MAX_ROWS)
  const ok = matrix.cells.length - matrix.failures.length

  return (
    <div>
      <p className="ozml-lead">
        语料 <strong>{matrix.caseCount}</strong> 条 × 引擎 <strong>{matrix.engineCount}</strong> 个 ={' '}
        <strong>{matrix.cells.length}</strong> 格，其中 <strong>{ok}</strong> ok、
        <strong>{matrix.failures.length}</strong> 失败。失败格是<strong>产物</strong>（不是 bug 列表）：
        矩阵问的就是「哪个引擎吃得下哪条 SQL」。
      </p>
      <p className="ozml-meta">
        commit <code title={matrix.commit ?? ''}>{(matrix.commit ?? '(本地)').slice(0, 7)}</code>
        {matrix.generatedAt ? <> · 生成于 {matrix.generatedAt}</> : null} ·{' '}
        <a href={withBase(matrix.rawUrl)}>全量 JSON（可溯源）</a>
      </p>

      <h2 id="coverage">方言 × 引擎覆盖率</h2>
      <table>
        <thead>
          <tr>
            <th>方言</th>
            {matrix.engines.map((e) => (
              <th key={e}>{e}</th>
            ))}
            <th>合计</th>
          </tr>
        </thead>
        <tbody>
          {matrix.coverage.map((row) => (
            <tr key={row.dialect}>
              <td>
                <code>{row.dialect}</code>
              </td>
              {row.engines.map((cell) => (
                <td key={cell.engine} className={cell.ok < cell.total ? 'ozml-bad' : undefined}>
                  {cell.ok}/{cell.total}
                </td>
              ))}
              <td>
                {row.ok}/{row.total}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2 id="cells">逐格浏览</h2>
      <div className="ozml-filters">
        <label>
          方言
          <select value={dialect} onChange={(e) => setDialect(e.target.value)}>
            <option>全部</option>
            {matrix.coverage.map((row) => (
              <option key={row.dialect}>{row.dialect}</option>
            ))}
          </select>
        </label>
        <label>
          引擎
          <select value={engine} onChange={(e) => setEngine(e.target.value)}>
            <option>全部</option>
            {matrix.engines.map((e) => (
              <option key={e}>{e}</option>
            ))}
          </select>
        </label>
        <label>
          状态
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option>全部</option>
            <option>失败</option>
            <option>ok</option>
          </select>
        </label>
        <span className="ozml-note">命中 {filtered.length} 格</span>
      </div>

      <table>
        <thead>
          <tr>
            <th>case</th>
            <th>方言</th>
            <th>引擎</th>
            <th>语句类型</th>
            <th>特性</th>
            <th>结果</th>
            <th>原因</th>
          </tr>
        </thead>
        <tbody>
          {shown.map((c, i) => (
            <tr key={i} className={c.ok ? undefined : 'ozml-bad'}>
              <td>
                <code>{c.caseId}</code>
              </td>
              <td>{c.dialect}</td>
              <td>{c.engine}</td>
              <td>{c.kind}</td>
              <td className="ozml-reason">{c.features.join(', ')}</td>
              <td>{c.ok ? 'ok' : '失败'}</td>
              <td className="ozml-reason">{c.ok ? '' : c.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {filtered.length > shown.length && (
        <p className="ozml-note">
          仅显示前 {shown.length} 格；其余 {filtered.length - shown.length} 格见{' '}
          <a href={withBase(matrix.rawUrl)}>全量 JSON</a>。
        </p>
      )}
    </div>
  )
}
