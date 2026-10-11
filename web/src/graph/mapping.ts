import type { ColumnRef, EdgeKind, GraphReport, LineageModel, TransformKind } from '../types'
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
  /**
   * 原始列引用（NodeInspector 展示 raw/canonical/span 用）。
   * GraphReport（aggregate 合并图）的节点没有 ColumnRef，缺省 undefined。
   */
  ref?: ColumnRef
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

/**
 * `GraphReport`（aggregate 合并图）→ 图节点/边。
 *
 * 与 [mapLineageModel] 同两条约定：SOURCE 边的 from 是表级哨兵（后端
 * `LineageGraph` 里哨兵节点 id 就是表限定名），单独成 `table:` 前缀节点；
 * 合并图节点没有 ColumnRef，GraphNodeData.ref 缺省（NodeInspector 相应降级）。
 */
export function mapGraphReport(report: GraphReport): MappedGraph {
  const sentinelIds = new Set(
    report.edges.filter((e) => e.kind === 'SOURCE').map((e) => e.from),
  )
  const nodeMap = new Map<string, GraphNodeData>()

  const ensure = (id: string, data: GraphNodeData): string => {
    if (!nodeMap.has(id)) nodeMap.set(id, data)
    return id
  }

  for (const n of report.nodes) {
    if (sentinelIds.has(n.id)) {
      // 哨兵节点换成 table: 前缀 id（与 mapLineageModel 一致，防同名裸列冲突）。
      ensure(tableNodeId(n.id), {
        label: n.label,
        isOutput: false,
        isTableNode: true,
      })
    } else {
      ensure(n.id, { label: n.label, isOutput: n.isOutput, isTableNode: false })
    }
  }

  // 边 id 去重：多语句合并时各模型的边各自从 e0 编号（LineageGraph 去重只保
  // 首次出现的键，id 可跨语句重复），React Flow 要求边 id 唯一——重复者按出现
  // 序追加 #n，确定性。
  const usedEdgeIds = new Set<string>()
  const edges: MappedEdge[] = report.edges.map((e) => {
    let id = e.id
    let n = 2
    while (usedEdgeIds.has(id)) id = `${e.id}#${n++}`
    usedEdgeIds.add(id)

    const asTable = e.kind === 'SOURCE'
    const source = asTable
      ? ensure(tableNodeId(e.from), { label: e.from, isOutput: false, isTableNode: true })
      : ensure(e.from, { label: e.from, isOutput: false, isTableNode: false })
    const target = ensure(e.to, { label: e.to, isOutput: false, isTableNode: false })
    return {
      id,
      source,
      target,
      data: { kind: e.kind, transform: e.transform },
    }
  })

  return { nodes: [...nodeMap.entries()].map(([id, data]) => ({ id, data })), edges }
}
