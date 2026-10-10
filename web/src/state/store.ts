import { create } from 'zustand'
import { ApiError } from '../api/client'
import { endpoints } from '../api/endpoints'
import type { EngineReport, LineageModel, ParseReport } from '../types'
import { SAMPLE_SQL } from '../lib/sample'
import { diffAstTrees } from '../lib/astDiff'
import type { AstDiff } from '../lib/astDiff'
import type { HighlightResult } from '../graph/traverse'

/**
 * 全局状态（Zustand，web 设计稿 §2.1）。状态面很小：
 * SQL 文本、引擎/方言、最近一次解析的两种产物、图选择集与高亮子图、
 * AST 对比（T4：左右引擎 + diff 结果）。
 */

export type BackendState = 'checking' | 'ok' | 'down'

export type GraphMode = 'none' | 'upstream' | 'downstream' | 'path'

/** 顶层视图：三栏主视图 / AST 并排对比（web 设计稿 App.tsx 的「视图切换」）。 */
export type ViewMode = 'main' | 'ast-diff'

interface AppState {
  sql: string
  engineId: string | null
  dialect: string
  engines: EngineReport[]
  backend: BackendState
  backendVersion: string | null
  running: boolean
  /** 上一次运行的人读错误（4xx/422、后端不可达、整段解析失败）。 */
  runError: string | null
  parseReport: ParseReport | null
  lineage: LineageModel | null
  graphMode: GraphMode
  /** path 模式下已选起点。 */
  pathFromId: string | null
  selectedNodeId: string | null
  selectedEdgeId: string | null
  highlight: HighlightResult | null
  view: ViewMode
  /** AST 对比（T4.2）：左右引擎（null = 尚未初始化）。 */
  diffLeftEngine: string | null
  diffRightEngine: string | null
  diffResult: AstDiff | null
  diffError: string | null
  diffRunning: boolean

  setSql: (sql: string) => void
  setEngineId: (id: string | null) => void
  setDialect: (d: string) => void
  init: () => Promise<void>
  run: () => Promise<void>
  loadSample: () => void
  setGraphMode: (mode: GraphMode) => void
  setPathFrom: (id: string | null) => void
  selectNode: (id: string | null) => void
  selectEdge: (id: string | null) => void
  setHighlight: (h: HighlightResult | null) => void
  setView: (view: ViewMode) => void
  setDiffLeftEngine: (id: string | null) => void
  setDiffRightEngine: (id: string | null) => void
  runDiff: () => Promise<void>
}

/** `/api/lineage` 整段解析失败时返回 `{unknown: {reason, span?}}`（200，错误是数据）。 */
function unknownReason(raw: unknown): string | null {
  if (typeof raw === 'object' && raw !== null && 'unknown' in raw) {
    const u = (raw as { unknown?: { reason?: string } }).unknown
    return u?.reason ?? '解析失败（unknown）'
  }
  return null
}

export const useAppStore = create<AppState>((set, get) => ({
  sql: SAMPLE_SQL,
  engineId: null,
  dialect: '',
  engines: [],
  backend: 'checking',
  backendVersion: null,
  running: false,
  runError: null,
  parseReport: null,
  lineage: null,
  graphMode: 'none',
  pathFromId: null,
  selectedNodeId: null,
  selectedEdgeId: null,
  highlight: null,
  view: 'main',
  diffLeftEngine: null,
  diffRightEngine: null,
  diffResult: null,
  diffError: null,
  diffRunning: false,

  setSql: (sql) => set({ sql }),
  setEngineId: (engineId) => set({ engineId }),
  setDialect: (dialect) => set({ dialect }),

  init: async () => {
    try {
      const health = await endpoints.health()
      const engines = await endpoints.engines()
      const engineId = get().engineId ?? engines[0]?.id ?? null
      // AST 对比默认值：左 = 当前引擎，右 = 另一个引擎（没有则同左，退化为自对比）。
      const diffLeftEngine = get().diffLeftEngine ?? engineId
      const diffRightEngine =
        get().diffRightEngine ?? engines.map((e) => e.id).find((id) => id !== diffLeftEngine) ?? diffLeftEngine
      set({
        backend: 'ok',
        backendVersion: health.version,
        engines,
        engineId,
        diffLeftEngine,
        diffRightEngine,
      })
    } catch {
      set({ backend: 'down' })
    }
  },

  run: async () => {
    const { sql, engineId, dialect } = get()
    if (get().backend !== 'ok' || sql.trim() === '') return
    set({ running: true, runError: null })
    const query = { sql, ...(engineId != null ? { engine: engineId } : {}), ...(dialect !== '' ? { dialect } : {}) }
    try {
      const [parseRaw, lineageRaw] = await Promise.all([
        endpoints.parse(query) as Promise<unknown>,
        endpoints.lineage(query) as Promise<unknown>,
      ])
      const parseUnknown = unknownReason(parseRaw)
      const lineageUnknown = unknownReason(lineageRaw)
      set({
        parseReport: parseRaw as ParseReport,
        lineage: lineageUnknown == null ? (lineageRaw as LineageModel) : null,
        runError: parseUnknown ?? lineageUnknown,
        selectedNodeId: null,
        selectedEdgeId: null,
        highlight: null,
        graphMode: 'none',
        pathFromId: null,
      })
    } catch (e) {
      const reason = e instanceof ApiError ? (e.body?.reason ?? e.message) : String(e)
      set({ runError: reason })
    } finally {
      set({ running: false })
    }
  },

  loadSample: () => set({ sql: SAMPLE_SQL }),
  setGraphMode: (graphMode) => set({ graphMode, highlight: null, pathFromId: null }),
  setPathFrom: (pathFromId) => set({ pathFromId }),
  selectNode: (selectedNodeId) => set({ selectedNodeId, selectedEdgeId: null }),
  selectEdge: (selectedEdgeId) => set({ selectedEdgeId, selectedNodeId: null }),
  setHighlight: (highlight) => set({ highlight }),
  setView: (view) => set({ view }),

  setDiffLeftEngine: (diffLeftEngine) => set({ diffLeftEngine }),
  setDiffRightEngine: (diffRightEngine) => set({ diffRightEngine }),

  runDiff: async () => {
    const { sql, dialect, backend, diffLeftEngine, diffRightEngine } = get()
    if (backend !== 'ok' || sql.trim() === '' || diffLeftEngine == null || diffRightEngine == null) return
    set({ diffRunning: true, diffError: null })
    const mk = (engine: string) => ({
      sql,
      engine,
      ...(dialect !== '' ? { dialect } : {}),
    })
    try {
      const [rawL, rawR] = await Promise.all([
        endpoints.parse(mk(diffLeftEngine)) as Promise<unknown>,
        endpoints.parse(mk(diffRightEngine)) as Promise<unknown>,
      ])
      // 解析失败是数据（200 unknown）：单侧失败即整体对比失败，但报文给出哪侧。
      const errL = unknownReason(rawL)
      const errR = unknownReason(rawR)
      if (errL != null || errR != null) {
        const parts = [
          errL != null ? `左侧（${diffLeftEngine}）：${errL}` : null,
          errR != null ? `右侧（${diffRightEngine}）：${errR}` : null,
        ].filter((p): p is string => p != null)
        set({ diffResult: null, diffError: parts.join('；') })
      } else {
        set({
          diffResult: diffAstTrees((rawL as ParseReport).root, (rawR as ParseReport).root),
        })
      }
    } catch (e) {
      const reason = e instanceof ApiError ? (e.body?.reason ?? e.message) : String(e)
      set({ diffError: reason })
    } finally {
      set({ diffRunning: false })
    }
  },
}))
