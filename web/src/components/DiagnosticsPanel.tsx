import { useState } from 'react'
import { useAppStore } from '../state/store'
import type { Severity, Span } from '../types'
import { revealSpan } from '../editor/editorHolder'

/** 底部面板：诊断（severity 分组）与 unknowns（Never-wrong 的可视化）。 */

const SEVERITY_STYLE: Record<Severity, { dot: string; text: string; label: string }> = {
  INFO: { dot: 'bg-sky-500', text: 'text-sky-400', label: 'INFO' },
  WARNING: { dot: 'bg-amber-500', text: 'text-amber-400', label: 'WARN' },
  ERROR: { dot: 'bg-rose-500', text: 'text-rose-400', label: 'ERROR' },
}

function LocateButton({ span }: { span?: Span }) {
  if (span == null) return null
  return (
    <button
      className="shrink-0 font-mono text-[11px] text-sky-400 hover:underline"
      onClick={(e) => {
        e.stopPropagation()
        revealSpan(span)
      }}
    >
      {span.start.line}:{span.start.column}
    </button>
  )
}

export function DiagnosticsPanel() {
  const [tab, setTab] = useState<'diagnostics' | 'unknowns'>('diagnostics')
  const parseReport = useAppStore((s) => s.parseReport)
  const lineage = useAppStore((s) => s.lineage)
  const runError = useAppStore((s) => s.runError)

  const diagnostics = [
    ...(parseReport?.diagnostics ?? []),
    // lineage 侧的诊断与 parse 侧可能重复（同一引擎同一输入）；按 code+span 去重。
    ...(lineage?.diagnostics ?? []).filter(
      (d) =>
        !(parseReport?.diagnostics ?? []).some(
          (p) => p.code === d.code && p.span?.start.offset === d.span?.start.offset,
        ),
    ),
  ]
  const unknowns = lineage?.unknowns ?? []

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-1 border-b border-neutral-800 px-2">
        {(
          [
            ['diagnostics', `诊断 (${diagnostics.length})`],
            ['unknowns', `unknowns (${unknowns.length})`],
          ] as const
        ).map(([id, label]) => (
          <button
            key={id}
            className={`px-2 py-1 text-[12px] ${
              tab === id ? 'border-b border-sky-400 text-sky-300' : 'text-neutral-500 hover:text-neutral-300'
            }`}
            onClick={() => setTab(id)}
          >
            {label}
          </button>
        ))}
        {runError != null && (
          <span className="ml-auto truncate px-2 text-[12px] text-rose-400">{runError}</span>
        )}
      </div>
      <div className="min-h-0 flex-1 overflow-auto">
        {tab === 'diagnostics' &&
          (diagnostics.length === 0 ? (
            <div className="p-3 text-[12px] text-neutral-500">无诊断</div>
          ) : (
            diagnostics.map((d, i) => {
              const style = SEVERITY_STYLE[d.severity]
              return (
                <div
                  key={i}
                  className="flex cursor-pointer items-center gap-2 border-b border-neutral-900 px-3 py-1 text-[12px] hover:bg-neutral-900/60"
                  onClick={() => d.span != null && revealSpan(d.span)}
                >
                  <span className={`h-2 w-2 shrink-0 rounded-full ${style.dot}`} />
                  <span className={`w-14 shrink-0 font-mono ${style.text}`}>{style.label}</span>
                  <span className="w-40 shrink-0 truncate font-mono text-neutral-400">{d.code}</span>
                  <span className="truncate text-neutral-300">{d.message}</span>
                  <LocateButton span={d.span} />
                </div>
              )
            })
          ))}
        {tab === 'unknowns' &&
          (unknowns.length === 0 ? (
            <div className="p-3 text-[12px] text-neutral-500">
              无 unknown——所有引用都已确知归属
            </div>
          ) : (
            unknowns.map((u, i) => (
              <div
                key={i}
                className="flex cursor-pointer items-center gap-2 border-b border-neutral-900 px-3 py-1 text-[12px] hover:bg-neutral-900/60"
                onClick={() => u.span != null && revealSpan(u.span)}
              >
                <span className="shrink-0 font-mono text-neutral-500">unknown</span>
                <span className="truncate text-amber-200/90">{u.reason}</span>
                <LocateButton span={u.span} />
              </div>
            ))
          ))}
      </div>
    </div>
  )
}
