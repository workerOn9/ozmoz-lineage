import { getJson, postJson } from './client'
import type { EngineReport, FormatReport, HealthReport, LineageModel, ParseReport, ValidateReport } from '../types'

/** `/api` 端点薄封装：请求/响应形状与 server/Dtos.kt 一一对应。 */

export interface ParseQuery {
  sql: string
  engine?: string
  dialect?: string
}

export interface LineageQuery extends ParseQuery {
  schema?: string
  /** opt-in 多语句聚合（ADR-0011）：true 回合并图（GraphReport），缺省单语句契约。 */
  aggregate?: boolean
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
  parse: (q: ParseQuery) => postJson<ParseReport>('/api/parse', q),
  lineage: (q: LineageQuery) => postJson<LineageModel>('/api/lineage', q),
  format: (q: FormatQuery) => postJson<FormatReport>('/api/format', q),
  validate: (q: ValidateQuery) => postJson<ValidateReport>('/api/validate', q),
}
