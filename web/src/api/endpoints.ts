import { getJson, postJson } from './client'
import type {
  EngineReport,
  FormatReport,
  GraphsReport,
  HealthReport,
  LineageModel,
  ParseReport,
  ValidateReport,
} from '../types'

/** `/api` 端点薄封装：请求/响应形状与 server/Dtos.kt 一一对应。 */

export interface ParseQuery {
  sql: string
  engine?: string
  dialect?: string
}

export interface LineageQuery {
  /** 现建现查的 SQL；与 `graph` **二选一**（ADR-0015）。 */
  sql?: string
  engine?: string
  dialect?: string
  schema?: string
  /** opt-in 多语句聚合（ADR-0011）：true 回合并图（GraphReport），缺省单语句契约。 */
  aggregate?: boolean
  /**
   * 已落库的图库名（ADR-0015）：与 `sql` 二选一，回**整库合并图**（GraphReport）。
   * 只传库名（相对后端 `--graph-root` 白名单根）；未开启该能力 → 400
   * `unsupported_capability`，库名非法 → 400 `invalid_graph_ref`，库里没有 → 422
   * `graph_not_found`。该模式下 engine / dialect / schema / format / aggregate 均不参与。
   */
  graph?: string
}

/** `/api/format` 请求（FormatQuery）：`schema` 不参与排版；`multiline` 缺省 true。 */
export interface FormatQuery {
  sql: string
  engine?: string
  dialect?: string
  multiline?: boolean
}

/**
 * `/api/validate` 请求（ValidateQuery）：结合 schema 做语义校验。
 * `schema` 为**内联 DDL 文本**（与 `/api/lineage` 同口径）；未给出时后端从
 * `sql` 文本顺手收集 `CREATE TABLE`。两条路都没有 schema → 回 `validate.skipped`。
 */
export interface ValidateQuery {
  sql: string
  engine?: string
  dialect?: string
  schema?: string
}

export const endpoints = {
  health: () => getJson<HealthReport>('/api/health'),
  engines: () => getJson<EngineReport[]>('/api/engines'),
  /** 图库清单（ADR-0015 契约：**POST** /api/graphs——GET 会 404）。未开启 `--graph-root` 时后端回 400 unsupported_capability。 */
  graphs: () => postJson<GraphsReport>('/api/graphs', {}),
  parse: (q: ParseQuery) => postJson<ParseReport>('/api/parse', q),
  lineage: (q: LineageQuery) => postJson<LineageModel>('/api/lineage', q),
  format: (q: FormatQuery) => postJson<FormatReport>('/api/format', q),
  validate: (q: ValidateQuery) => postJson<ValidateReport>('/api/validate', q),
}
