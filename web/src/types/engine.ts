import { z } from 'zod'
import { astNodeSchema, diagnosticSchema, tableRefSchema } from './ir'

// server 模块 DTO 的镜像（server/Dtos.kt）。响应里能直接用 ir 模型处不重复定义。

export const healthReportSchema = z.object({
  status: z.string(),
  version: z.string(),
})
export type HealthReport = z.infer<typeof healthReportSchema>

export const engineReportSchema = z.object({
  id: z.string(),
  features: z.array(z.string()),
  dialects: z.array(z.string()),
  reasons: z.record(z.string(), z.string()),
})
export type EngineReport = z.infer<typeof engineReportSchema>

/** `POST /api/parse` 响应（ParseReportDto：{root, tables, diagnostics}）。 */
export const parseReportSchema = z.object({
  root: astNodeSchema,
  tables: z.array(tableRefSchema),
  diagnostics: z.array(diagnosticSchema),
})
export type ParseReport = z.infer<typeof parseReportSchema>

/**
 * `POST /api/format` 响应（FormatReportDto：{engine, dialect, sql, diagnostics}），
 * 与 `ozml format --json` 同形。**形状恒定**：排版「没跑成」时 `sql` 缺省、
 * `diagnostics` 含 `format.skipped` ERROR——「没跑成」也是一种诊断（ADR-0014）。
 */
export const formatReportSchema = z.object({
  engine: z.string(),
  dialect: z.string().optional(),
  sql: z.string().optional(),
  diagnostics: z.array(diagnosticSchema),
})
export type FormatReport = z.infer<typeof formatReportSchema>

/**
 * `POST /api/validate` 响应（ValidateReportDto：{engine, dialect?, diagnostics}），
 * 与 `ozml validate` 的 stdout JSON 同形。**形状恒定**：校验「没跑成」（未给 schema、
 * SQL 为空、方言未注册）仍 200、落一条 `validate.skipped` ERROR——validate 的产物
 * **本身就是诊断流**（ADR-0013），「没跑成」也是一种诊断。
 */
export const validateReportSchema = z.object({
  engine: z.string(),
  dialect: z.string().optional(),
  diagnostics: z.array(diagnosticSchema),
})
export type ValidateReport = z.infer<typeof validateReportSchema>

/** 4xx / 422 统一错误体（ErrorResponse）。 */
export const errorResponseSchema = z.object({
  error: z.string(),
  reason: z.string().optional(),
  statementCount: z.number().int().optional(),
})
export type ErrorResponse = z.infer<typeof errorResponseSchema>
