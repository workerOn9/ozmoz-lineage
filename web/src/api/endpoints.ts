import { getJson, postJson } from './client'
import type { EngineReport, HealthReport, LineageModel, ParseReport } from '../types'

/** `/api` 端点薄封装：请求/响应形状与 server/Dtos.kt 一一对应。 */

export interface ParseQuery {
  sql: string
  engine?: string
  dialect?: string
}

export interface LineageQuery extends ParseQuery {
  schema?: string
}

export const endpoints = {
  health: () => getJson<HealthReport>('/api/health'),
  engines: () => getJson<EngineReport[]>('/api/engines'),
  parse: (q: ParseQuery) => postJson<ParseReport>('/api/parse', q),
  lineage: (q: LineageQuery) => postJson<LineageModel>('/api/lineage', q),
}
