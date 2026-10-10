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

/** 4xx / 422 统一错误体（ErrorResponse）。 */
export const errorResponseSchema = z.object({
  error: z.string(),
  reason: z.string().optional(),
  statementCount: z.number().int().optional(),
})
export type ErrorResponse = z.infer<typeof errorResponseSchema>
