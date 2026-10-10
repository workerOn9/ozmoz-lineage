import type { ColumnRef, EdgeKind, LineageModel, TransformKind } from '../types'
import { columnRefId } from '../types'

/**
 * `LineageModel` → 图节点/边（web 设计稿 §5.3：只映射、不改字段语义）。
 * - 列节点 id 与 Kotlin `ColumnRef.id` 同规则（`table.name` 小写）；
 * - SOURCE 边的 fromColumn 是**表级哨兵**（无 table 限定的表名），单独成节点
 *   （id 前缀 `table:`），避免与同名裸列冲突。
 */

export interface GraphNodeData extends Record<string, unknown> {
  label: string
  isOutput: boolean
  isTableNode: boolean
  ref: ColumnRef
}

export interface GraphEdgeData {
  kind: EdgeKind
  transform: TransformKind
  expression?: string
}

export interface MappedNode {
  id: string
  data: GraphNodeData
}

export interface MappedEdge {
  id: string
  source: string
  target: string
  data: GraphEdgeData
}

export interface MappedGraph {
  nodes: MappedNode[]
  edges: MappedEdge[]
}

/** 表级哨兵节点的稳定 id。 */
export function tableNodeId(name: string): string {
  return `table:${name.toLowerCase()}`
}

export function isTableSentinel(ref: ColumnRef): boolean {
  return ref.table == null
}

function nodeLabel(ref: ColumnRef): string {
  return ref.table == null ? ref.name : `${ref.table}.${ref.name}`
}

export function mapLineageModel(model: LineageModel): MappedGraph {
  const nodeMap = new Map<string, GraphNodeData>()
  const outputIds = new Set(
    model.columns.filter((c) => c.isOutput).map((c) => columnRefId(c.column)),
  )

  const ensure = (ref: ColumnRef, asTableNode: boolean): string => {
    const id = asTableNode ? tableNodeId(ref.name) : columnRefId(ref)
    if (!nodeMap.has(id)) {
      nodeMap.set(id, {
        label: asTableNode ? ref.name : nodeLabel(ref),
        isOutput: !asTableNode && outputIds.has(id),
        isTableNode: asTableNode,
        ref,
      })
    }
    return id
  }

  // columns 列表里的节点（含未出现在任何边上的孤立输出列）。
  for (const c of model.columns) ensure(c.column, false)

  const edges: MappedEdge[] = model.edges.map((e) => {
    const asTable = e.kind === 'SOURCE' && isTableSentinel(e.fromColumn)
    const source = ensure(e.fromColumn, asTable)
    const target = ensure(e.toColumn, false)
    return {
      id: e.id,
      source,
      target,
      data: {
        kind: e.kind,
        transform: e.transform,
        ...(e.expression != null ? { expression: e.expression } : {}),
      },
    }
  })

  return { nodes: [...nodeMap.entries()].map(([id, data]) => ({ id, data })), edges }
}
