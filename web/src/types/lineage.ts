import { z } from 'zod'
import { columnRefSchema, diagnosticSchema, spanSchema, tableRefSchema } from './ir'

// LineageModel 契约镜像（io.github.workeron9.ozmoz.lineage.ir.LineageModel）。
// 枚举集合与 Kotlin 枚举一一对应；新增枚举值会被 test/contract.test.ts 的夹具断言暴露。

export const edgeKindSchema = z.enum([
  'OUTPUT',
  'PREDICATE',
  'JOIN_KEY',
  'GROUP_BY',
  'ORDER_BY',
  'SOURCE',
])
export type EdgeKind = z.infer<typeof edgeKindSchema>

export const transformKindSchema = z.enum([
  'DIRECT',
  'EXPRESSION',
  'AGGREGATE',
  'WINDOW',
  'CASE_BRANCH',
  'CONSTANT',
  'JOIN_KEY',
  'FILTER_PREDICATE',
  'UNKNOWN',
  'GROUPING',
  'ORDERING',
  'SOURCE',
])
export type TransformKind = z.infer<typeof transformKindSchema>

export const scopeKindSchema = z.enum([
  'SELECT',
  'CTE',
  'SUBQUERY',
  'UNION_BRANCH',
  'INSERT',
  'UPDATE',
  'DELETE',
  'MERGE',
  'CREATE_VIEW',
  'CREATE_TABLE_AS',
  'ROOT',
])
export type ScopeKind = z.infer<typeof scopeKindSchema>

export const metaSchema = z.object({
  engineId: z.string().optional(),
  dialect: z.string().optional(),
  schemaSnapshotId: z.string().optional(),
  toolVersion: z.string().optional(),
  elapsedMillis: z.number().optional(),
})
export type Meta = z.infer<typeof metaSchema>

export const scopeNodeSchema = z.object({
  id: z.string(),
  kind: scopeKindSchema,
  sources: z.array(tableRefSchema).default([]),
  outputs: z.array(columnRefSchema).default([]),
  parentId: z.string().optional(),
  span: spanSchema.optional(),
})
export type ScopeNode = z.infer<typeof scopeNodeSchema>

export const columnNodeSchema = z.object({
  column: columnRefSchema,
  scopeId: z.string().optional(),
  isOutput: z.boolean().default(false),
  type: z.string().optional(),
  nullable: z.boolean().optional(),
})
export type ColumnNode = z.infer<typeof columnNodeSchema>

export const lineageEdgeSchema = z.object({
  id: z.string(),
  fromColumn: columnRefSchema,
  toColumn: columnRefSchema,
  kind: edgeKindSchema,
  transform: transformKindSchema,
  expression: z.string().optional(),
  span: spanSchema.optional(),
})
export type LineageEdge = z.infer<typeof lineageEdgeSchema>

/** `Resolved.Unknown` 的实际 JSON 形状（实测无多态判别字段）：{reason, span?}。 */
export const unknownItemSchema = z.object({
  reason: z.string(),
  span: spanSchema.optional(),
})
export type UnknownItem = z.infer<typeof unknownItemSchema>

export const lineageModelSchema = z.object({
  meta: metaSchema.default({}),
  scopes: z.array(scopeNodeSchema).default([]),
  columns: z.array(columnNodeSchema).default([]),
  edges: z.array(lineageEdgeSchema).default([]),
  unknowns: z.array(unknownItemSchema).default([]),
  diagnostics: z.array(diagnosticSchema).default([]),
})
export type LineageModel = z.infer<typeof lineageModelSchema>
