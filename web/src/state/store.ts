import { create } from 'zustand'
import { ApiError } from '../api/client'
import { endpoints } from '../api/endpoints'
import type {
  EngineReport,
  GraphEntry,
  GraphReport,
  LineageModel,
  ParseReport,
  ValidateReport,
} from '../types'
import { EXAMPLES, getExample } from '../lib/examples'
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
  /**
   * aggregate（聚合多语句）开关：true 时 `/api/lineage` 带 `aggregate: true`，
   * 回合并图（GraphReport，ADR-0011 opt-in 契约）；跳过 /api/parse（多语句无单树）。
   */
  aggregate: boolean
  graphReport: GraphReport | null
  /**
   * 当前 graphReport 的来源：null = 无图；'aggregate' = 本请求 SQL 的聚合合并图；
   * 'store' = 已落库图库（ADR-0015 图库浏览）。中栏据此给不同说明文案。
   */
  graphSource: 'aggregate' | 'store' | null
  /**
   * 数据源（ADR-0015）：'sql' = 编辑器里的 SQL 现建现查；'graph' = 已落库的图库。
   * 切到 'graph' 会拉一次图库清单（`/api/graphs`）。
   */
  dataSource: 'sql' | 'graph'
  /** 图库清单（`/api/graphs`）；未开启 `--graph-root` 时为空。 */
  graphs: GraphEntry[]
  /** 当前选中的库名。 */
  graphName: string | null
  /** 图库清单拉取的人读错误（典型：后端未以 --graph-root 启动 → unsupported_capability）。 */
  graphsError: string | null
  /** 图库清单请求中。 */
  graphsLoading: boolean
  /** 打开图库（`/api/lineage` graph 模式）请求中。 */
  graphOpening: boolean
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
  /** `/api/format` 排版中（工具栏按钮的 disabled 依据）。 */
  formatting: boolean
  /**
   * 排版结果提示（非 null 时工具栏下沿展示）：有损（注释丢失 WARNING）或
   * 没跑成（format.skipped ERROR，`sql` 不回写）——文案已人读化。
   */
  formatNotice: string | null

  /** 图折叠（T3.4）：折叠表节点——隐藏 table: 哨兵与 SOURCE 边。缺省 false。 */
  foldTables: boolean
  /** 图折叠（T3.4）：已收起内部列的作用域 id。新数据载入时重置（scopes 换了）。 */
  collapsedScopeIds: string[]

  /**
   * 语义校验（`/api/validate`）用的内联 DDL 文本。空字符串 = 未提供——
   * 后端退化为从 `sql` 文本自动收 `CREATE TABLE`，收不到则回 `validate.skipped`。
   */
  schema: string
  /** schema 面板是否展开。 */
  schemaOpen: boolean
  /** `/api/validate` 请求中（工具栏按钮的 disabled 依据）。 */
  validating: boolean
  /** 最近一次校验产物（形状恒定：合法回空诊断、没跑成落 validate.skipped）。 */
  validateReport: ValidateReport | null
  /** 最近一次校验的人读错误（4xx / 后端不可达）；与诊断流分开。 */
  validateError: string | null

  setSql: (sql: string) => void
  setEngineId: (id: string | null) => void
  setDialect: (d: string) => void
  setAggregate: (on: boolean) => void
  /** 切数据源；切到 'graph' 时顺带拉图库清单。 */
  setDataSource: (ds: 'sql' | 'graph') => Promise<void>
  /** 拉图库清单（`/api/graphs`）；无可用库时 graphName 置 null。 */
  loadGraphs: () => Promise<void>
  setGraphName: (name: string | null) => void
  /** 打开选中的图库：`/api/lineage {graph}` → graphReport（整库合并图）。 */
  openGraph: () => Promise<void>
  setSchema: (ddl: string) => void
  setSchemaOpen: (open: boolean) => void
  init: () => Promise<void>
  run: () => Promise<void>
  /** 载入示例库里的一个演示场景（SQL / schema / aggregate 一并切好）。 */
  loadExample: (id: string) => void
  /** 对当前 sql + schema 打 `/api/validate` 语义校验，结果进校验诊断流。 */
  validate: () => Promise<void>
  /** 清掉上一次校验产物（改 SQL / 改 schema 时避免陈旧诊断滞留）。 */
  clearValidate: () => void
  /** 对当前 sql 打 `/api/format` 排版，成功则回写编辑器；有损 / 没跑成给提示。 */
  format: () => Promise<void>
  clearFormatNotice: () => void
  setGraphMode: (mode: GraphMode) => void
  setPathFrom: (id: string | null) => void
  selectNode: (id: string | null) => void
  selectEdge: (id: string | null) => void
  setHighlight: (h: HighlightResult | null) => void
  /** 图折叠（T3.4）：切「折叠表节点」开关。 */
  toggleFoldTables: () => void
  /** 图折叠（T3.4）：切换某作用域的折叠状态。 */
  toggleScopeCollapse: (scopeId: string) => void
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
  sql: EXAMPLES[0].sql,
  engineId: null,
  dialect: '',
  engines: [],
  backend: 'checking',
  backendVersion: null,
  running: false,
  runError: null,
  parseReport: null,
  lineage: null,
  aggregate: false,
  graphReport: null,
  graphSource: null,
  dataSource: 'sql',
  graphs: [],
  graphName: null,
  graphsError: null,
  graphsLoading: false,
  graphOpening: false,
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
  formatting: false,
  formatNotice: null,
  foldTables: false,
  collapsedScopeIds: [],
  schema: EXAMPLES[0].schema ?? '',
  schemaOpen: false,
  validating: false,
  validateReport: null,
  validateError: null,

  setSql: (sql) => set({ sql, formatNotice: null, validateReport: null, validateError: null }),
  setEngineId: (engineId) => {
    const engine = get().engines.find((e) => e.id === engineId)
    const dialect = engine?.dialects.includes(get().dialect) ? get().dialect : ''
    set({ engineId, dialect })
  },
  setDialect: (dialect) => set({ dialect }),
  setAggregate: (aggregate) => set({ aggregate }),

  setDataSource: async (dataSource) => {
    set({ dataSource })
    // 切到图库：拉一次清单（未开启 --graph-root 时后端回 400，落到 graphsError 提示）。
    if (dataSource === 'graph') await get().loadGraphs()
  },

  loadGraphs: async () => {
    set({ graphsLoading: true })
    try {
      const report = await endpoints.graphs()
      const graphs = report.graphs
      // 选中项保持（若已失效则回落第一个）；没有库时置 null。
      const current = get().graphName
      const graphName = current != null && graphs.some((g) => g.name === current)
        ? current
        : (graphs[0]?.name ?? null)
      set({ graphs, graphName, graphsError: null })
    } catch (e) {
      const reason = e instanceof ApiError ? (e.body?.reason ?? e.message) : String(e)
      // unsupported_capability 是「后端没开这个能力」，给一句能照做的提示。
      const notEnabled = e instanceof ApiError && e.body?.error === 'unsupported_capability'
      set({
        graphs: [],
        graphName: null,
        graphsError: notEnabled
          ? '后端未开启图库浏览：请用 ozml serve --graph-root <目录> 启动'
          : reason,
      })
    } finally {
      set({ graphsLoading: false })
    }
  },

  setGraphName: (graphName) => set({ graphName }),

  openGraph: async () => {
    const { graphName, backend } = get()
    if (backend !== 'ok' || graphName == null) return
    set({ graphOpening: true, runError: null })
    try {
      // graph 模式：与 sql 二选一，回整库合并图（GraphReport，与 aggregate 同形）。
      const raw = (await endpoints.lineage({ graph: graphName })) as unknown
      const unknown = unknownReason(raw)
      set({
        parseReport: null,
        lineage: null,
        graphReport: unknown == null ? (raw as GraphReport) : null,
        graphSource: unknown == null ? 'store' : null,
        runError: unknown,
        selectedNodeId: null,
        selectedEdgeId: null,
        highlight: null,
        graphMode: 'none',
        pathFromId: null,
        collapsedScopeIds: [],
      })
    } catch (e) {
      const reason = e instanceof ApiError ? (e.body?.reason ?? e.message) : String(e)
      set({ graphReport: null, graphSource: null, runError: `打开图库失败：${reason}` })
    } finally {
      set({ graphOpening: false })
    }
  },
  // 改 schema 后旧校验诊断就失效了（针对的是上一份元数据），清掉避免误导。
  setSchema: (schema) => set({ schema, validateReport: null, validateError: null }),
  setSchemaOpen: (schemaOpen) => set({ schemaOpen }),

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
    const { sql, engineId, dialect, aggregate } = get()
    if (get().backend !== 'ok' || sql.trim() === '') return
    set({ running: true, runError: null })
    const query = { sql, ...(engineId != null ? { engine: engineId } : {}), ...(dialect !== '' ? { dialect } : {}) }
    try {
      if (aggregate) {
        // 聚合模式（ADR-0011 opt-in）：只打 /api/lineage（aggregate=true 回合并图
        // GraphReport）；多语句没有单棵归一化树，/api/parse 跳过、中栏显示说明。
        const raw = (await endpoints.lineage({ ...query, aggregate: true })) as unknown
        const lineageUnknown = unknownReason(raw)
        set({
          parseReport: null,
          lineage: null,
          graphReport: lineageUnknown == null ? (raw as GraphReport) : null,
          graphSource: lineageUnknown == null ? 'aggregate' : null,
          runError: lineageUnknown,
          selectedNodeId: null,
          selectedEdgeId: null,
          highlight: null,
          graphMode: 'none',
          pathFromId: null,
          collapsedScopeIds: [],
        })
      } else {
        const [parseRaw, lineageRaw] = await Promise.all([
          endpoints.parse(query) as Promise<unknown>,
          endpoints.lineage(query) as Promise<unknown>,
        ])
        const parseUnknown = unknownReason(parseRaw)
        const lineageUnknown = unknownReason(lineageRaw)
        set({
          parseReport: parseRaw as ParseReport,
          lineage: lineageUnknown == null ? (lineageRaw as LineageModel) : null,
          graphReport: null,
          graphSource: null,
          runError: parseUnknown ?? lineageUnknown,
          selectedNodeId: null,
          selectedEdgeId: null,
          highlight: null,
          graphMode: 'none',
          pathFromId: null,
          collapsedScopeIds: [],
        })
      }
    } catch (e) {
      const reason = e instanceof ApiError ? (e.body?.reason ?? e.message) : String(e)
      // 422 multi_statement_input：后端 reason 已说明 aggregate=true，补一句前端落点。
      const hint =
        e instanceof ApiError && e.body?.error === 'multi_statement_input'
          ? `${reason}（可勾选工具栏「聚合多语句」后重新解析）`
          : reason
      set({ runError: hint })
    } finally {
      set({ running: false })
    }
  },

  loadExample: (id) => {
    const ex = getExample(id)
    if (ex == null) return
    set({
      sql: ex.sql,
      schema: ex.schema ?? '',
      aggregate: ex.aggregate ?? false,
      formatNotice: null,
      validateReport: null,
      validateError: null,
      // 回到 SQL 数据源：否则编辑器换成示例 SQL、右栏还挂着上一个图库的图，状态对不上。
      dataSource: 'sql',
      graphReport: null,
      graphSource: null,
      runError: null,
      collapsedScopeIds: [],
    })
  },

  clearFormatNotice: () => set({ formatNotice: null }),

  clearValidate: () => set({ validateReport: null, validateError: null }),

  validate: async () => {
    const { sql, schema, dialect, backend } = get()
    if (backend !== 'ok' || sql.trim() === '') return
    set({ validating: true, validateError: null })
    try {
      // 刻意不传 engine：语义校验是 calcite 的能力（唯一申报 VALIDATE_SCHEMA 的引擎），
      // 正是 /api/validate 的缺省；传用户当前引擎（可能是 jooq/jsqlparser）只会撞 400。
      const report = await endpoints.validate({
        sql,
        ...(dialect !== '' ? { dialect } : {}),
        // 空白 schema 视同未提供：交给后端从 sql 里收 CREATE TABLE（缺省行为）。
        ...(schema.trim() !== '' ? { schema } : {}),
      })
      set({ validateReport: report })
    } catch (e) {
      const reason = e instanceof ApiError ? (e.body?.reason ?? e.message) : String(e)
      set({ validateReport: null, validateError: reason })
    } finally {
      set({ validating: false })
    }
  },

  format: async () => {
    const { sql, backend } = get()
    if (backend !== 'ok' || sql.trim() === '') return
    set({ formatting: true, formatNotice: null })
    try {
      // 刻意不传 engine：排版是「规范化渲染」而非方言/引擎选择，由后端取默认
      // jsqlparser（唯一申报 PRETTY_PRINT 的引擎）；这正是 /api/format 的缺省。
      const report = await endpoints.format({ sql })
      if (report.sql == null) {
        // 形状恒定：没跑成时 sql 缺省，诊断里是 format.skipped ERROR（输入不构成一次排版）。
        const reason = report.diagnostics[0]?.message ?? '排版未跑成'
        set({ formatNotice: `排版未跑成：${reason}` })
      } else {
        // 有损如实报：注释丢失时回 jsqlparser.comment_dropped（WARNING）——文本已回写，
        // 但要提示用户「注释没保留」，避免静默改稿。
        const lossy = report.diagnostics.filter((d) => d.severity !== 'INFO')
        set({
          sql: report.sql,
          formatNotice:
            lossy.length > 0 ? `已排版（有损）：${lossy.map((d) => d.message).join('；')}` : null,
        })
      }
    } catch (e) {
      const reason = e instanceof ApiError ? (e.body?.reason ?? e.message) : String(e)
      set({ formatNotice: `排版失败：${reason}` })
    } finally {
      set({ formatting: false })
    }
  },
  setGraphMode: (graphMode) => set({ graphMode, highlight: null, pathFromId: null }),
  setPathFrom: (pathFromId) => set({ pathFromId }),
  selectNode: (selectedNodeId) => set({ selectedNodeId, selectedEdgeId: null }),
  selectEdge: (selectedEdgeId) => set({ selectedEdgeId, selectedNodeId: null }),
  setHighlight: (highlight) => set({ highlight }),
  setView: (view) => set({ view }),

  toggleFoldTables: () => set((s) => ({ foldTables: !s.foldTables })),
  toggleScopeCollapse: (scopeId) =>
    set((s) => ({
      collapsedScopeIds: s.collapsedScopeIds.includes(scopeId)
        ? s.collapsedScopeIds.filter((id) => id !== scopeId)
        : [...s.collapsedScopeIds, scopeId],
    })),

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
