import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import { Toolbar } from './components/Toolbar'
import { AstTreeView } from './components/ast/AstTreeView'
import { AstDiffPanel } from './components/ast/AstDiffPanel'
import { DiagnosticsPanel } from './components/DiagnosticsPanel'
import { GraphToolbar } from './components/lineage/GraphToolbar'
import { LineageGraphView } from './components/lineage/LineageGraphView'
import { NodeInspector } from './components/lineage/NodeInspector'
import { EdgeLegend } from './components/lineage/EdgeLegend'
import { useAppStore } from './state/store'

// Monaco 体积大：编辑器懒加载，首屏不背这个 chunk（设计稿 §9 R1）。
const SqlEditor = lazy(() =>
  import('./components/editor/SqlEditor').then((m) => ({ default: m.SqlEditor })),
)

/** 三栏布局：左 SQL / 中归一化树 / 右血缘图 + 检查器；底部诊断面板。 */

function DragHandle({
  onDelta,
}: {
  onDelta: (deltaPx: number, containerWidth: number) => void
}) {
  const onPointerDown = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      e.preventDefault()
      const startX = e.clientX
      const containerWidth = (e.target as HTMLElement).parentElement?.clientWidth ?? 1
      const onMove = (ev: PointerEvent) => onDelta(ev.clientX - startX, containerWidth)
      const onUp = () => {
        window.removeEventListener('pointermove', onMove)
        window.removeEventListener('pointerup', onUp)
      }
      window.addEventListener('pointermove', onMove)
      window.addEventListener('pointerup', onUp)
    },
    [onDelta],
  )
  return (
    <div
      className="w-1 cursor-col-resize bg-neutral-800 hover:bg-sky-700 active:bg-sky-600"
      onPointerDown={onPointerDown}
    />
  )
}

export default function App() {
  const init = useAppStore((s) => s.init)
  const setSql = useAppStore((s) => s.setSql)
  const sql = useAppStore((s) => s.sql)
  const parseReport = useAppStore((s) => s.parseReport)
  const lineage = useAppStore((s) => s.lineage)
  const backend = useAppStore((s) => s.backend)
  const view = useAppStore((s) => s.view)

  // 左 / 中栏宽度（百分比），右栏吃剩余。
  const [widths, setWidths] = useState({ left: 30, mid: 26 })
  const [bottomOpen, setBottomOpen] = useState(true)
  const mainRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    void init()
  }, [init])

  const clamp = (v: number) => Math.min(60, Math.max(12, v))

  return (
    <div className="flex h-full flex-col overflow-hidden">
      <Toolbar />
      {backend === 'down' && (
        <div className="border-b border-rose-900 bg-rose-950/60 px-3 py-1 text-[12px] text-rose-200">
          未连接本地后端——解析不可用。请启动 <code className="font-mono">ozml serve</code>
          （默认 8765 端口）；docker 部署见仓库 Dockerfile。
        </div>
      )}
      <div ref={mainRef} className="flex min-h-0 flex-1">
        {/* 左：SQL 编辑器（三栏 / AST 对比共用） */}
        <section className="flex min-w-0 flex-col" style={{ width: `${widths.left}%` }}>
          <PanelTitle title="SQL 输入" />
          <div className="min-h-0 flex-1">
            <Suspense fallback={<div className="p-3 text-[12px] text-neutral-500">加载编辑器…</div>}>
              <SqlEditor value={sql} onChange={setSql} />
            </Suspense>
          </div>
        </section>
        <DragHandle
          onDelta={(dx, w) =>
            setWidths((v) => ({ ...v, left: clamp(v.left + (dx / w) * 100) }))
          }
        />
        {view === 'main' ? (
          <>
            {/* 中：归一化树 */}
            <section className="flex min-w-0 flex-col" style={{ width: `${widths.mid}%` }}>
              <PanelTitle title="归一化树" />
              <div className="min-h-0 flex-1">
                <AstTreeView root={parseReport?.root ?? null} />
              </div>
            </section>
            <DragHandle
              onDelta={(dx, w) =>
                setWidths((v) => {
                  const left = clamp(v.left + (dx / w) * 100)
                  return { left, mid: clamp(v.mid - (dx / w) * 100) }
                })
              }
            />
            {/* 右：血缘图 + 检查器 */}
            <section className="flex min-w-0 flex-1 flex-col">
              <GraphToolbar />
              <div className="min-h-0 flex-[3]">
                <LineageGraphView />
              </div>
              <EdgeLegend model={lineage} />
              <div className="min-h-0 flex-[2] border-t border-neutral-800">
                <NodeInspector />
              </div>
            </section>
          </>
        ) : (
          /* AST 对比：占满中栏 + 右栏（T4） */
          <section className="flex min-w-0 flex-1 flex-col">
            <AstDiffPanel />
          </section>
        )}
      </div>
      {/* 底部：诊断 / unknowns */}
      <div className="border-t border-neutral-800 bg-neutral-950">
        <button
          className="flex w-full items-center justify-between px-3 py-1 text-[12px] text-neutral-400 hover:text-neutral-200"
          onClick={() => setBottomOpen((v) => !v)}
        >
          <span>诊断与 unknowns</span>
          <span>{bottomOpen ? '▼' : '▲'}</span>
        </button>
        {bottomOpen && (
          <div className="h-40">
            <DiagnosticsPanel />
          </div>
        )}
      </div>
    </div>
  )
}

function PanelTitle({ title }: { title: string }) {
  return (
    <div className="border-b border-neutral-800 px-3 py-1.5 text-[12px] font-medium text-neutral-400">
      {title}
    </div>
  )
}
