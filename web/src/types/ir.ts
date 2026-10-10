import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// `ir` 模块 @Serializable 模型的 TS 镜像（web 设计稿 §5：手写镜像 + 夹具校验）。
// 字段名与 Kotlin 属性名一一对应；形状以 web/test/fixtures/ 的真实输出为准。
// ─────────────────────────────────────────────────────────────────────────────────────────────

export const positionSchema = z.object({
  offset: z.number().int().nonnegative(),
  line: z.number().int().positive(),
  column: z.number().int().positive(),
})
export type Position = z.infer<typeof positionSchema>

/** 半开区间 [start, end)；为 null（缺省）表示位置未知。 */
export const spanSchema = z.object({
  start: positionSchema,
  end: positionSchema,
})
export type Span = z.infer<typeof spanSchema>

export const tableRefSchema = z.object({
  raw: z.string(),
  canonical: z.string(),
  catalog: z.string().optional(),
  schema: z.string().optional(),
  name: z.string(),
  alias: z.string().optional(),
  span: spanSchema.optional(),
})
export type TableRef = z.infer<typeof tableRefSchema>

/** 图与诊断里的稳定节点 id：与 Kotlin `ColumnRef.id` 同规则（qualifiedName 小写）。 */
export function columnRefId(ref: Pick<ColumnRef, 'name' | 'table'>): string {
  return (ref.table == null ? ref.name : `${ref.table}.${ref.name}`).toLowerCase()
}

export const columnRefSchema = z.object({
  raw: z.string(),
  canonical: z.string(),
  name: z.string(),
  table: z.string().optional(),
  schema: z.string().optional(),
  span: spanSchema.optional(),
})
export type ColumnRef = z.infer<typeof columnRefSchema>

export const astNodeSchema: z.ZodType<AstNode> = z.lazy(() =>
  z.object({
    type: z.string(),
    text: z.string(),
    span: spanSchema.optional(),
    children: z.array(astNodeSchema).default([]),
  }),
)
export interface AstNode {
  type: string
  text: string
  span?: Span
  children: AstNode[]
}

export const severitySchema = z.enum(['INFO', 'WARNING', 'ERROR'])
export type Severity = z.infer<typeof severitySchema>

export const diagnosticSchema = z.object({
  severity: severitySchema,
  code: z.string(),
  message: z.string(),
  span: spanSchema.optional(),
  engineId: z.string().optional(),
})
export type Diagnostic = z.infer<typeof diagnosticSchema>
