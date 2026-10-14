import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import { Toolbar } from './components/Toolbar'
import { SchemaPanel } from './components/SchemaPanel'
import { AstDiffPanel } from './components/ast/AstDiffPanel'
import { DiagnosticsPanel } from './components/DiagnosticsPanel'
import { TreePanel } from './components/TreePanel'
import { GraphPanel } from './components/GraphPanel'
import { ToolRail } from './components/ToolRail'
import { useAppStore } from './state/store'
import { getExample } from './lib/examples'

// Monaco 体积大：编辑器懒加载，首屏不背这个 chunk（设计稿 §9 R1）。
const SqlEditor = lazy(() =>
  import('./components/editor/SqlEditor').then((m) => ({ default: m.SqlEditor })),
)

/** 两栏布局：左 SQL / 右工具面板（归一化树 / 血缘图，IDEA 风格工具条切换、可收起）；底部诊断面板。 */

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
      className="w-1 cursor-col-resize bg-neutral-200 hover:bg-brand active:bg-sky-600"
      onPointerDown={onPointerDown}
    />
  )
}

export default function App() {
  const init = useAppStore((s) => s.init)
  const setSql = useAppStore((s) => s.setSql)
  const sql = useAppStore((s) => s.sql)
  const backend = useAppStore((s) => s.backend)
  const view = useAppStore((s) => s.view)
  const panelTool = useAppStore((s) => s.panelTool)
  const panelOpen = useAppStore((s) => s.panelOpen)
  const formatNotice = useAppStore((s) => s.formatNotice)
  const clearFormatNotice = useAppStore((s) => s.clearFormatNotice)
  const schemaOpen = useAppStore((s) => s.schemaOpen)
  const loadExample = useAppStore((s) => s.loadExample)
  const run = useAppStore((s) => s.run)

  // 左右两栏宽度（左侧百分比），右栏吃剩余；右栏收起时左栏占满。
  const [leftWidth, setLeftWidth] = useState(38)
  const [bottomOpen, setBottomOpen] = useState(false)
  const mainRef = useRef<HTMLDivElement>(null)
  // 落地页画廊深链（/app?example=<id>）：载入示例 + 后端就绪后自动跑一次。
  // exampleRan 防 StrictMode 双跑；replaceState 去掉参数，避免手动刷新又触发。
  const examplePending = useRef(false)
  const exampleRan = useRef(false)

  useEffect(() => {
    void init()
  }, [init])

  useEffect(() => {
    const ex = getExample(new URLSearchParams(window.location.search).get('example'))
    if (ex == null) return
    examplePending.current = true
    loadExample(ex.id)
    window.history.replaceState(null, '', '/app')
  }, [loadExample])

  useEffect(() => {
    if (backend === 'ok' && examplePending.current && !exampleRan.current) {
      exampleRan.current = true
      void run()
    }
  }, [backend, run])

  const clamp = (v: number) => Math.min(70, Math.max(20, v))
  // 工作台里「右栏展开」时左栏固定百分比；其余情况（右栏收起 / AST 对比）左栏占满或按需。
  const panelVisible = view === 'main' && panelOpen
  const leftFills = view === 'main' && !panelOpen

  return (
    <div className="flex h-full flex-col overflow-hidden">
      <Toolbar />
      {formatNotice != null && (
        <div className="flex items-center gap-2 border-b border-amber-200 bg-amber-50 px-3 py-1 text-[12px] text-amber-800">
          <span className="truncate">SQL 排版：{formatNotice}</span>
          <button
            className="ml-auto shrink-0 text-amber-600 hover:text-amber-800"
            onClick={clearFormatNotice}
          >
            知道了
          </button>
        </div>
      )}
      {backend === 'down' && (
        <div className="border-b border-rose-200 bg-rose-50 px-3 py-1 text-[12px] text-rose-700">
          未连接本地后端——解析不可用。请启动 <code className="font-mono">ozml serve</code>
          （默认 8765 端口）；docker 部署见仓库 Dockerfile。
        </div>
      )}
      <div ref={mainRef} className="flex min-h-0 flex-1">
        {/* 左：SQL 编辑器 + 可选 schema 面板（工作台 / AST 对比共用） */}
        <section
          className={`flex min-w-0 flex-col ${leftFills ? 'flex-1' : ''}`}
          style={leftFills ? undefined : { width: `${leftWidth}%` }}
        >
          <PanelTitle title="SQL 输入" />
          <div className={schemaOpen ? 'min-h-0 flex-[3]' : 'min-h-0 flex-1'}>
            <Suspense fallback={<div className="p-3 text-[12px] text-neutral-500">加载编辑器…</div>}>
              <SqlEditor value={sql} onChange={setSql} />
            </Suspense>
          </div>
          {schemaOpen && (
            <>
              <PanelTitle title="Schema（DDL）" />
              <div className="min-h-0 flex-[2] border-t border-neutral-200">
                <SchemaPanel />
              </div>
            </>
          )}
        </section>
        {view === 'main' ? (
          <>
            {panelVisible && (
              <DragHandle
                onDelta={(dx, w) => setLeftWidth((v) => clamp(v + (dx / w) * 100))}
              />
            )}
            {panelVisible && (
              <section className="flex min-w-0 flex-1 flex-col">
                {panelTool === 'tree' ? <TreePanel /> : <GraphPanel />}
              </section>
            )}
            {/* 右侧纵向工具条：归一化树 / 血缘图二选一 + 面板收起。 */}
            <ToolRail />
          </>
        ) : (
          /* AST 对比：占满右栏（T4） */
          <section className="flex min-w-0 flex-1 flex-col">
            <AstDiffPanel />
          </section>
        )}
      </div>
      {/* 底部：诊断面板，默认收起。 */}
      <div className="border-t border-neutral-200 bg-white">
        <button
          className="flex w-full items-center justify-between px-3 py-1 text-[12px] text-neutral-500 hover:text-neutral-800"
          onClick={() => setBottomOpen((v) => !v)}
        >
          <span>诊断</span>
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
    <div className="border-b border-neutral-200 px-3 py-1.5 text-[12px] font-medium text-neutral-500">
      {title}
    </div>
  )
}
