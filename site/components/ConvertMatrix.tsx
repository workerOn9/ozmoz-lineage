// 方言转换矩阵：`ozml matrix --convert` 的实测快照（方言对 × 语料 × 渲染引擎）。
// 每方言对给覆盖率 + 一条**成品 SQL 样本**（渲染门禁通过才算 ok）；失败格带原因。
import { useMemo, useState } from 'react'
import { withBase } from '@rspress/core/runtime'
import dataset from '../.data/dataset.json'
import DataMissing from './DataMissing'

const MAX_FAILURES = 100

type Pair = {
  from: string
  to: string
  ok: number
  total: number
  engines: Array<{ engine: string; ok: number; total: number }>
  samples: Array<{ engine: string; caseId: string; sql: string }>
}
type Failure = { from: string; to: string; engine: string; caseId: string; reason: string }

export default function ConvertMatrix() {
  const convert = dataset.convert as {
    available: boolean
    commit: string | null
    generatedAt: string | null
    caseCount: number
    engineCount: number
    declaredPairCount: number
    pairs: Pair[]
    failures: Failure[]
    rawUrl: string
  }

  const [from, setFrom] = useState('全部')
  const [onlyFailed, setOnlyFailed] = useState(false)

  const pairs = useMemo(
    () => (convert.available ? convert.pairs.filter((p) => from === '全部' || p.from === from) : []),
    [convert, from],
  )
  const failures = useMemo(
    () => (convert.available ? convert.failures.filter((f) => from === '全部' || f.from === from) : []),
    [convert, from],
  )

  if (!convert.available) return <DataMissing what="方言转换矩阵" />

  const okTotal = convert.pairs.reduce((s, p) => s + p.ok, 0)
  const allTotal = convert.pairs.reduce((s, p) => s + p.total, 0)
  const shownFailures = failures.slice(0, MAX_FAILURES)

  return (
    <div>
      <p className="ozml-lead">
        语料 <strong>{convert.caseCount}</strong> 条 · 引擎 <strong>{convert.engineCount}</strong> 个 · 方言对{' '}
        <strong>{convert.pairs.length}</strong> 组（声明 {convert.declaredPairCount} 组，其余 from 方言在语料里没有
        case，故不出格）：<strong>{okTotal}</strong> / <strong>{allTotal}</strong> ok。ok 格都过了{' '}
        <code>parse → render → re-parse</code> 等价门禁。
      </p>
      <p className="ozml-meta">
        commit <code title={convert.commit ?? ''}>{(convert.commit ?? '(本地)').slice(0, 7)}</code>
        {convert.generatedAt ? <> · 生成于 {convert.generatedAt}</> : null} ·{' '}
        <a href={withBase(convert.rawUrl)}>全量 JSON（可溯源）</a>
      </p>

      <div className="ozml-filters">
        <label>
          from 方言
          <select value={from} onChange={(e) => setFrom(e.target.value)}>
            <option>全部</option>
            {convert.pairs.map((p) => (
              <option key={p.from}>{p.from}</option>
            ))}
          </select>
        </label>
        <label>
          <input type="checkbox" checked={onlyFailed} onChange={(e) => setOnlyFailed(e.target.checked)} /> 只看失败
        </label>
      </div>

      {!onlyFailed && (
        <>
          <h2 id="pairs">方言对覆盖率与成品样本</h2>
          {pairs.map((p) => (
            <section key={`${p.from}->${p.to}`}>
              <h3 id={`${p.from}-to-${p.to}`}>
                <code>{p.from}</code> → <code>{p.to}</code>
              </h3>
              <p className="ozml-pair-ok">
                {p.ok}/{p.total}
              </p>
              <table>
                <thead>
                  <tr>
                    <th>引擎</th>
                    <th>ok</th>
                  </tr>
                </thead>
                <tbody>
                  {p.engines.map((e) => (
                    <tr key={e.engine}>
                      <td>{e.engine}</td>
                      <td className={e.total > 0 && e.ok < e.total ? 'ozml-bad' : undefined}>
                        {e.total === 0 ? '—' : `${e.ok}/${e.total}`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {p.samples.map((s) => (
                <details key={`${s.engine}-${s.caseId}`}>
                  <summary>
                    成品样本 · {s.engine} · <code>{s.caseId}</code>
                  </summary>
                  <pre>
                    <code>{s.sql}</code>
                  </pre>
                </details>
              ))}
            </section>
          ))}
        </>
      )}

      <h2 id="failures">失败格</h2>
      <p className="ozml-meta">命中 {failures.length} 格（渲染门禁不等价时不输出，如实入格）。</p>
      <table>
        <thead>
          <tr>
            <th>from</th>
            <th>to</th>
            <th>引擎</th>
            <th>case</th>
            <th>原因</th>
          </tr>
        </thead>
        <tbody>
          {shownFailures.map((f, i) => (
            <tr key={i} className="ozml-bad">
              <td>{f.from}</td>
              <td>{f.to}</td>
              <td>{f.engine}</td>
              <td>
                <code>{f.caseId}</code>
              </td>
              <td className="ozml-reason">{f.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {failures.length > shownFailures.length && (
        <p className="ozml-note">
          仅显示前 {shownFailures.length} 格；其余见 <a href={withBase(convert.rawUrl)}>全量 JSON</a>。
        </p>
      )}
    </div>
  )
}
