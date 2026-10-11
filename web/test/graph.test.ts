import { describe, expect, it } from 'vitest'
import type { MappedEdge, MappedNode } from '../src/graph/mapping'
import { mapGraphReport, mapLineageModel, tableNodeId } from '../src/graph/mapping'
import { reachableSet, subgraphHighlight } from '../src/graph/traverse'
import { shortestPath } from '../src/graph/path'
import { layoutGraph } from '../src/graph/layout'
import { graphReportSchema, lineageModelSchema } from '../src/types'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))

function loadModel(name: string) {
  return lineageModelSchema.parse(
    JSON.parse(readFileSync(join(here, 'fixtures', name), 'utf-8')),
  )
}

function loadGraphReport(name: string) {
  return graphReportSchema.parse(
    JSON.parse(readFileSync(join(here, 'fixtures', name), 'utf-8')),
  )
}

function edges(...pairs: Array<[string, string]>): MappedEdge[] {
  return pairs.map(([source, target], i) => ({
    id: `e${i}`,
    source,
    target,
    data: { kind: 'OUTPUT', transform: 'DIRECT' },
  }))
}

describe('mapLineageModel', () => {
  it('真实夹具：节点 id 唯一、SOURCE 边产出表级哨兵节点', () => {
    const mapped = mapLineageModel(loadModel('lineage-model.json'))
    const ids = mapped.nodes.map((n) => n.id)
    expect(new Set(ids).size).toBe(ids.length)

    const sourceEdges = mapped.edges.filter((e) => e.data.kind === 'SOURCE')
    expect(sourceEdges.length).toBeGreaterThan(0)
    for (const e of sourceEdges) {
      expect(e.source.startsWith('table:')).toBe(true)
    }
    const tableNode = mapped.nodes.find((n) => n.data.isTableNode)
    expect(tableNode).toBeDefined()
    expect(tableNode!.data.label).toBe('orders')
  })

  it('表哨兵 id 不与同名裸列冲突', () => {
    const id = tableNodeId('Orders')
    expect(id).toBe('table:orders')
  })
})

describe('mapGraphReport（aggregate 合并图）', () => {
  it('真实夹具：SOURCE 边 from 收成 table: 哨兵节点，节点 id 唯一', () => {
    const mapped = mapGraphReport(loadGraphReport('lineage-graph-aggregate.json'))
    const ids = mapped.nodes.map((n) => n.id)
    expect(new Set(ids).size).toBe(ids.length)

    // 表级哨兵 t → table:t 节点；裸列 a / b 保留原 id（同名不冲突）。
    const sentinel = mapped.nodes.find((n) => n.data.isTableNode)
    expect(sentinel).toBeDefined()
    expect(sentinel!.id).toBe('table:t')
    expect(sentinel!.data.label).toBe('t')
    expect(mapped.nodes.some((n) => n.id === 'a' && !n.data.isTableNode)).toBe(true)

    for (const e of mapped.edges.filter((e) => e.data.kind === 'SOURCE')) {
      expect(e.source).toBe('table:t')
    }
  })

  it('跨语句重复的边 id 按出现序追加 #n（React Flow 要求边 id 唯一）', () => {
    const mapped = mapGraphReport(loadGraphReport('lineage-graph-aggregate.json'))
    const edgeIds = mapped.edges.map((e) => e.id)
    expect(new Set(edgeIds).size).toBe(edgeIds.length)
    // 真实夹具两句各有 e0 / e1：第二条重复 id 必须被改写。
    expect(edgeIds).toContain('e0')
    expect(edgeIds).toContain('e0#2')
  })

  it('合并图节点无 ColumnRef（ref 缺省，NodeInspector 走 id 降级）', () => {
    const mapped = mapGraphReport(loadGraphReport('lineage-graph-aggregate.json'))
    for (const n of mapped.nodes) {
      expect(n.data.ref).toBeUndefined()
    }
    // 输出列标记从后端 isOutput 透传。
    expect(mapped.nodes.find((n) => n.id === 'a')!.data.isOutput).toBe(true)
  })
})

describe('上溯 / 下溯', () => {
  // a -> b -> c；d -> b
  const graph = edges(['a', 'b'], ['b', 'c'], ['d', 'b'])

  it('downstream 从 a 出发到 {a, b, c}', () => {
    expect(reachableSet(graph, 'a', 'downstream')).toEqual(new Set(['a', 'b', 'c']))
  })
  it('upstream 从 c 出发到 {a, b, c, d}', () => {
    expect(reachableSet(graph, 'c', 'upstream')).toEqual(new Set(['a', 'b', 'c', 'd']))
  })
  it('both 从 b 出发全图', () => {
    expect(reachableSet(graph, 'b', 'both')).toEqual(new Set(['a', 'b', 'c', 'd']))
  })
  it('subgraphHighlight 只保留两端都在集合内的边', () => {
    const h = subgraphHighlight(graph, new Set(['a', 'b']))
    expect(h.edgeIds).toEqual(new Set(['e0']))
  })
})

describe('shortestPath', () => {
  // a -> b -> d；a -> c -> d
  const graph = edges(['a', 'b'], ['b', 'd'], ['a', 'c'], ['c', 'd'])

  it('downstream 最短路径存在', () => {
    const path = shortestPath(graph, 'a', 'd', 'downstream')
    expect(path).not.toBeNull()
    expect(path).toHaveLength(3)
    expect(path![0]).toBe('a')
    expect(path![2]).toBe('d')
  })
  it('反向不可达（方向约束）', () => {
    expect(shortestPath(graph, 'd', 'a', 'downstream')).toBeNull()
  })
  it('both 方向可逆', () => {
    expect(shortestPath(graph, 'd', 'a', 'both')).not.toBeNull()
  })
  it('起点=终点', () => {
    expect(shortestPath(graph, 'a', 'a', 'downstream')).toEqual(['a'])
  })
})

describe('layoutGraph（dagre LR）', () => {
  it('链式图 x 单调递增且无重叠节点', () => {
    const nodes: MappedNode[] = ['a', 'b', 'c'].map((id) => ({
      id,
      data: { label: id, isOutput: false, isTableNode: false, ref: { raw: id, canonical: id, name: id } },
    }))
    const e = edges(['a', 'b'], ['b', 'c'])
    const laid = layoutGraph(nodes, e)
    expect(laid.size).toBe(3)
    const xs = ['a', 'b', 'c'].map((id) => laid.get(id)!.x)
    expect(xs[0]).toBeLessThan(xs[1])
    expect(xs[1]).toBeLessThan(xs[2])
    for (const n of laid.values()) {
      expect(Number.isFinite(n.x)).toBe(true)
      expect(Number.isFinite(n.y)).toBe(true)
    }
  })
})
