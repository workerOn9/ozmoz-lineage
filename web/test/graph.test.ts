import { describe, expect, it } from 'vitest'
import type { MappedEdge, MappedNode } from '../src/graph/mapping'
import { mapGraphReport, mapLineageModel, tableNodeId } from '../src/graph/mapping'
import { reachableSet, subgraphHighlight } from '../src/graph/traverse'
import { shortestPath } from '../src/graph/path'
import { layoutGraph } from '../src/graph/layout'
import { shouldVirtualize, VIRTUALIZE_MIN_NODES } from '../src/graph/perf'
import { visibleEdges } from '../src/graph/edgeFilter'
import { OWNERSHIP_EDGE_PREFIX, ownershipEdges } from '../src/graph/ownership'
import { mergePositionChanges } from '../src/graph/dragPositions'
import { DEFAULT_HIDDEN_EDGE_KINDS } from '../src/state/store'
import { graphReportSchema, lineageModelSchema } from '../src/types'
import type { EdgeKind } from '../src/types'
import type { NodeChange, XYPosition } from '@xyflow/react'
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

  it('大图冒烟：200+ 节点链全部有位且无重叠坐标对撞', () => {
    const count = VIRTUALIZE_MIN_NODES + 100
    const nodes: MappedNode[] = Array.from({ length: count }, (_, i) => ({
      id: `n${i}`,
      data: {
        label: `n${i}`,
        isOutput: false,
        isTableNode: false,
        ref: { raw: `n${i}`, canonical: `n${i}`, name: `n${i}` },
      },
    }))
    const e = edges(...(Array.from({ length: count - 1 }, (_, i) => [`n${i}`, `n${i + 1}`] as [string, string])))
    const laid = layoutGraph(nodes, e)
    expect(laid.size).toBe(count)
    for (const n of laid.values()) {
      expect(Number.isFinite(n.x)).toBe(true)
      expect(Number.isFinite(n.y)).toBe(true)
    }
  })
})

describe('大图性能治理（R2）', () => {
  it('阈值门控：小图不开虚拟化，达到阈值才开', () => {
    expect(shouldVirtualize(0)).toBe(false)
    expect(shouldVirtualize(VIRTUALIZE_MIN_NODES - 1)).toBe(false)
    expect(shouldVirtualize(VIRTUALIZE_MIN_NODES)).toBe(true)
    expect(shouldVirtualize(VIRTUALIZE_MIN_NODES + 1)).toBe(true)
  })
})

describe('边类型过滤（visibleEdges）', () => {
  const kinds: EdgeKind[] = ['OUTPUT', 'PREDICATE', 'JOIN_KEY', 'GROUP_BY', 'ORDER_BY', 'SOURCE']
  const all: MappedEdge[] = kinds.map((kind, i) => ({
    id: `e${i}`,
    source: 's',
    target: 't',
    data: { kind, transform: 'DIRECT' },
  }))

  it('缺省只留列级血缘：OUTPUT / JOIN_KEY 可见，其余四类关', () => {
    const kept = visibleEdges(all, DEFAULT_HIDDEN_EDGE_KINDS)
    expect(kept.map((e) => e.data.kind)).toEqual(['OUTPUT', 'JOIN_KEY'])
  })

  it('一个都没隐藏 → 原引用直返（零开销）', () => {
    expect(visibleEdges(all, [])).toBe(all)
  })

  it('过滤后的边就是遍历口径：被隐藏的边不贡献上游', () => {
    // a -(PREDICATE)→ t，b -(OUTPUT)→ t；隐藏 PREDICATE 后 t 的上游只剩 b。
    const edges: MappedEdge[] = [
      { id: 'p', source: 'a', target: 't', data: { kind: 'PREDICATE', transform: 'FILTER_PREDICATE' } },
      { id: 'o', source: 'b', target: 't', data: { kind: 'OUTPUT', transform: 'DIRECT' } },
    ]
    expect([...reachableSet(visibleEdges(edges, DEFAULT_HIDDEN_EDGE_KINDS), 't', 'upstream')].sort()).toEqual([
      'b',
      't',
    ])
  })
})

describe('拖动位置覆盖（mergePositionChanges）', () => {
  const xy = (x: number, y: number): XYPosition => ({ x, y })

  it('只挑 position 改动合进覆盖表；select / dimensions 忽略；不改原表', () => {
    const prev = new Map<string, XYPosition>([['a', xy(1, 1)]])
    const next = mergePositionChanges(prev, [
      { id: 'a', type: 'position', position: xy(10, 20), dragging: true },
      { id: 'b', type: 'select', selected: true },
    ] as NodeChange[])
    expect(next).not.toBeNull()
    expect(next!.get('a')).toEqual(xy(10, 20))
    expect(next!.has('b')).toBe(false)
    expect(prev.get('a')).toEqual(xy(1, 1))
  })

  it('没有 position 改动 → null（调用方据此跳过 setState）', () => {
    const changes = [{ id: 'a', type: 'select', selected: true }] as NodeChange[]
    expect(mergePositionChanges(new Map(), changes)).toBeNull()
  })

  it('position 缺省的 position 改动不算（拖动结束那次只有 dragging 标记）', () => {
    const changes = [{ id: 'a', type: 'position', dragging: false }] as NodeChange[]
    expect(mergePositionChanges(new Map(), changes)).toBeNull()
  })
})

describe('所属虚线（ownershipEdges）', () => {
  function column(id: string, table: string | undefined, isOutput = false): MappedNode {
    return {
      id,
      data: {
        label: id,
        isOutput,
        isTableNode: false,
        ...(table != null ? { ref: { raw: id, canonical: id, name: id, table } } : {}),
      },
    }
  }
  function tableNode(name: string): MappedNode {
    return { id: tableNodeId(name), data: { label: name, isOutput: false, isTableNode: true } }
  }

  it('零入边的物理列 → 补一条连到所属表的虚线', () => {
    const graph = {
      nodes: [tableNode('db.t'), column('db.t.a', 'db.t')],
      edges: [] as MappedEdge[],
    }
    const own = ownershipEdges(graph)
    expect(own).toHaveLength(1)
    expect(own[0].id.startsWith(OWNERSHIP_EDGE_PREFIX)).toBe(true)
    expect(own[0].source).toBe(tableNodeId('db.t'))
    expect(own[0].target).toBe('db.t.a')
  })

  it('已有入边的列不补（不悬空）；表节点自身不补', () => {
    const graph = {
      nodes: [tableNode('db.t'), column('db.t.a', 'db.t'), column('u.b', 'db.t')],
      edges: [
        { id: 'e0', source: 'db.t.a', target: 'u.b', data: { kind: 'OUTPUT', transform: 'DIRECT' } },
      ] as MappedEdge[],
    }
    const own = ownershipEdges(graph)
    // u.b 有入边不补；db.t.a 零入边补一条；表哨兵不补。
    expect(own.map((e) => e.target)).toEqual(['db.t.a'])
  })

  it('派生表名（没有对应表节点）不补 —— 不凭空造节点', () => {
    const graph = {
      nodes: [column('subquery_0.a', 'subquery_0')],
      edges: [] as MappedEdge[],
    }
    expect(ownershipEdges(graph)).toEqual([])
  })
})
