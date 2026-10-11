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

/**
 * `POST /api/graphs` 响应（GraphsReportDto，ADR-0015）——白名单根下可浏览的图库清单。
 * 只在后端以 `ozml serve --graph-root <目录>` 启动时可用；未开启该能力时端点回
 * 400 `unsupported_capability`（能力表路由，与 /api/validate 同惯例）。
 */
export const graphsReportSchema = z.object({
  graphs: z.array(
    z.object({
      /** 库名（相对白名单根）：直接作为 `/api/lineage` 的 `graph` 字段回传。 */
      name: z.string(),
      /** 库里可建模语句数（`lineage_model` 行数；只 count 不载图）。 */
      modelCount: z.number().int(),
    }),
  ),
})
export type GraphsReport = z.infer<typeof graphsReportSchema>
export type GraphEntry = GraphsReport['graphs'][number]

/** 4xx / 422 统一错误体（ErrorResponse）。 */
export const errorResponseSchema = z.object({
  error: z.string(),
  reason: z.string().optional(),
  statementCount: z.number().int().optional(),
})
export type ErrorResponse = z.infer<typeof errorResponseSchema>
