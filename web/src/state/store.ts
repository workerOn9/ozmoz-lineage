import { create } from 'zustand'
import { ApiError } from '../api/client'
import { endpoints } from '../api/endpoints'
import type { EngineReport, LineageModel, ParseReport } from '../types'
import { SAMPLE_SQL } from '../lib/sample'
import type { HighlightResult } from '../graph/traverse'

/**
 * 全局状态（Zustand，web 设计稿 §2.1）。状态面很小：
 * SQL 文本、引擎/方言、最近一次解析的两种产物、图选择集与高亮子图。
 */

export type BackendState = 'checking' | 'ok' | 'down'

export type GraphMode = 'none' | 'upstream' | 'downstream' | 'path'

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

  setSql: (sql) => set({ sql }),
  setEngineId: (engineId) => set({ engineId }),
  setDialect: (dialect) => set({ dialect }),

  init: async () => {
    try {
      const health = await endpoints.health()
      const engines = await endpoints.engines()
      set({
        backend: 'ok',
        backendVersion: health.version,
        engines,
        engineId: get().engineId ?? engines[0]?.id ?? null,
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
}))
