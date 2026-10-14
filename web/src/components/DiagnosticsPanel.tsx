import { useState } from 'react'
import { useAppStore } from '../state/store'
import type { Severity, Span } from '../types'
import { revealSpan } from '../editor/editorHolder'

/** 底部面板：诊断（severity 分组）与 unknowns（Never-wrong 的可视化）。 */

const SEVERITY_STYLE: Record<Severity, { dot: string; text: string; label: string }> = {
  INFO: { dot: 'bg-sky-500', text: 'text-sky-400', label: 'INFO' },
  WARNING: { dot: 'bg-amber-500', text: 'text-amber-600', label: 'WARN' },
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
  const [tab, setTab] = useState<'diagnostics' | 'validate' | 'unknowns'>('diagnostics')
  const parseReport = useAppStore((s) => s.parseReport)
  const lineage = useAppStore((s) => s.lineage)
  const runError = useAppStore((s) => s.runError)
  const validateReport = useAppStore((s) => s.validateReport)
  const validateError = useAppStore((s) => s.validateError)

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
  const validate = validateReport?.diagnostics ?? []

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-1 border-b border-neutral-200 px-2">
        {(
          [
            ['diagnostics', `诊断 (${diagnostics.length})`],
            ['validate', `校验 (${validate.length})`],
            ['unknowns', `unknowns (${unknowns.length})`],
          ] as const
        ).map(([id, label]) => (
          <button
            key={id}
            className={`px-2 py-1 text-[12px] ${
              tab === id ? 'border-b border-sky-400 text-sky-700' : 'text-neutral-400 hover:text-neutral-400'
            }`}
            onClick={() => setTab(id)}
          >
            {label}
          </button>
        ))}
        {tab === 'validate' && validateReport != null && (
          <span className="px-1 font-mono text-[11px] text-neutral-400">引擎 {validateReport.engine}</span>
        )}
        {runError != null && (
          <span className="ml-auto truncate px-2 text-[12px] text-rose-400">{runError}</span>
        )}
      </div>
      <div className="min-h-0 flex-1 overflow-auto">
        {tab === 'diagnostics' &&
          (diagnostics.length === 0 ? (
            <div className="p-3 text-[12px] text-neutral-400">无诊断</div>
          ) : (
            diagnostics.map((d, i) => {
              const style = SEVERITY_STYLE[d.severity]
              return (
                <div
                  key={i}
                  className="flex cursor-pointer items-center gap-2 border-b border-neutral-100 px-3 py-1 text-[12px] hover:bg-neutral-100/60"
                  onClick={() => d.span != null && revealSpan(d.span)}
                >
                  <span className={`h-2 w-2 shrink-0 rounded-full ${style.dot}`} />
                  <span className={`w-14 shrink-0 font-mono ${style.text}`}>{style.label}</span>
                  <span className="w-40 shrink-0 truncate font-mono text-text-neutral-500">{d.code}</span>
                  <span className="truncate text-neutral-400">{d.message}</span>
                  <LocateButton span={d.span} />
                </div>
              )
            })
          ))}
        {tab === 'validate' &&
          (validateError != null ? (
            <div className="p-3 text-[12px] text-rose-400">校验请求失败：{validateError}</div>
          ) : validateReport == null ? (
            <div className="p-3 text-[12px] text-neutral-400">
              尚未校验——点工具栏「校验」，结合 schema 做语义校验（未知表 / 未知列 / 列歧义等）
            </div>
          ) : validate.length === 0 ? (
            <div className="p-3 text-[12px] text-emerald-400">校验通过——未发现语义问题</div>
          ) : (
            validate.map((d, i) => {
              const style = SEVERITY_STYLE[d.severity]
              return (
                <div
                  key={i}
                  className="flex cursor-pointer items-center gap-2 border-b border-neutral-100 px-3 py-1 text-[12px] hover:bg-neutral-100/60"
                  onClick={() => d.span != null && revealSpan(d.span)}
                >
                  <span className={`h-2 w-2 shrink-0 rounded-full ${style.dot}`} />
                  <span className={`w-14 shrink-0 font-mono ${style.text}`}>{style.label}</span>
                  <span className="w-40 shrink-0 truncate font-mono text-text-neutral-500">{d.code}</span>
                  <span className="truncate text-neutral-400">{d.message}</span>
                  <LocateButton span={d.span} />
                </div>
              )
            })
          ))}
        {tab === 'unknowns' &&
          (unknowns.length === 0 ? (
            <div className="p-3 text-[12px] text-neutral-400">
              无 unknown——所有引用都已确知归属
            </div>
          ) : (
            unknowns.map((u, i) => (
              <div
                key={i}
                className="flex cursor-pointer items-center gap-2 border-b border-neutral-100 px-3 py-1 text-[12px] hover:bg-neutral-100/60"
                onClick={() => u.span != null && revealSpan(u.span)}
              >
                <span className="shrink-0 font-mono text-neutral-400">unknown</span>
                <span className="truncate text-amber-800/90">{u.reason}</span>
                <LocateButton span={u.span} />
              </div>
            ))
          ))}
      </div>
    </div>
  )
}
