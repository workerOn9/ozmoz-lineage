import { describe, expect, it } from 'vitest'
import type { MappedEdge, MappedNode } from '../src/graph/mapping'
import { mapGraphReport, mapLineageModel } from '../src/graph/mapping'
import { applyCollapse, internalColumnIdsByScope } from '../src/graph/collapse'
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

function graphOf(nodes: Array<[string, boolean?]>, pairs: Array<[string, string]>): {
  nodes: MappedNode[]
  edges: MappedEdge[]
} {
  return {
    nodes: nodes.map(([id, isTableNode]) => ({
      id,
      data: {
        label: id,
        isOutput: false,
        isTableNode: isTableNode ?? false,
      },
    })),
    edges: pairs.map(([source, target], i) => ({
      id: `e${i}`,
      source,
      target,
      data: { kind: 'OUTPUT', transform: 'DIRECT' },
    })),
  }
}

// 真实夹具（PR #32 时代的 golden 输出）：11 列节点 + 3 表哨兵，24 边（9 SOURCE）。
const modelFixture = () => mapLineageModel(loadModel('lineage-model.json'))

describe('internalColumnIdsByScope', () => {
  it('真实夹具：内部列归属其 scope，output 列排除', () => {
    const model = loadModel('lineage-model.json')
    const byScope = internalColumnIdsByScope(model)
    expect([...byScope.keys()]).toEqual(['s0'])
    const ids = [...byScope.get('s0')!]
    expect(ids).toContain('orders.order_id')
    expect(ids).toContain('order_items.qty')
    // output 列不收：折叠后血缘流要保留「从哪来 → 输出什么」。
    expect(ids).not.toContain('order_id')
    expect(ids).not.toContain('name')
    expect(ids).not.toContain('total')
    expect(ids.length).toBe(8)
  })
})

describe('applyCollapse — 折叠表节点', () => {
  it('真实夹具：table: 哨兵与 SOURCE 边消失，其余原样', () => {
    const mapped = modelFixture()
    const folded = applyCollapse(mapped, {
      foldTables: true,
      collapsedScopes: new Set(),
      internalColumnIdsByScope: new Map(),
    })
    expect(folded.nodes.every((n) => !n.data.isTableNode)).toBe(true)
    expect(folded.nodes.length).toBe(mapped.nodes.length - 3)
    expect(folded.edges.every((e) => e.data.kind !== 'SOURCE')).toBe(true)
    expect(folded.edges.length).toBe(mapped.edges.length - 9)
  })

  it('聚合夹具（GraphReport）：同样成立——表折叠不依赖作用域信息', () => {
    const mapped = mapGraphReport(loadGraphReport('lineage-graph-store.json'))
    const folded = applyCollapse(mapped, {
      foldTables: true,
      collapsedScopes: new Set(),
      internalColumnIdsByScope: new Map(),
    })
    expect(folded.nodes.every((n) => !n.data.isTableNode)).toBe(true)
    expect(folded.edges.length).toBeLessThan(mapped.edges.length)
  })

  it('不折叠时返回原图（同一引用，零开销路径）', () => {
    const mapped = modelFixture()
    const out = applyCollapse(mapped, {
      foldTables: false,
      collapsedScopes: new Set(),
      internalColumnIdsByScope: internalColumnIdsByScope(loadModel('lineage-model.json')),
    })
    expect(out).toBe(mapped)
  })
})

describe('applyCollapse — 折叠作用域', () => {
  it('真实夹具：s0 内部列收起、output 列与表节点保留、只剩 SOURCE 边', () => {
    const mapped = modelFixture()
    const model = loadModel('lineage-model.json')
    const collapsed = applyCollapse(mapped, {
      foldTables: false,
      collapsedScopes: new Set(['s0']),
      internalColumnIdsByScope: internalColumnIdsByScope(model),
    })
    const ids = collapsed.nodes.map((n) => n.id)
    // 内部列没了
    for (const hidden of ['orders.order_id', 'customers.name', 'order_items.qty', 'orders.status']) {
      expect(ids).not.toContain(hidden)
    }
    // output 列与表哨兵保留
    for (const kept of ['order_id', 'name', 'total', 'table:orders', 'table:customers']) {
      expect(ids).toContain(kept)
    }
    expect(collapsed.nodes.length).toBe(6)
    // 触到被收端点的边消失，SOURCE 边(to恰为output列)保留
    expect(collapsed.edges.length).toBe(9)
    expect(collapsed.edges.every((e) => e.data.kind === 'SOURCE')).toBe(true)
  })

  it('折叠表节点 + 折叠作用域叠加：只剩 output 列', () => {
    const mapped = modelFixture()
    const model = loadModel('lineage-model.json')
    const collapsed = applyCollapse(mapped, {
      foldTables: true,
      collapsedScopes: new Set(['s0']),
      internalColumnIdsByScope: internalColumnIdsByScope(model),
    })
    expect(collapsed.nodes.map((n) => n.id).sort()).toEqual(['name', 'order_id', 'total'])
    expect(collapsed.edges.length).toBe(0)
  })

  it('收起不存在的 scope：no-op', () => {
    const mapped = modelFixture()
    const collapsed = applyCollapse(mapped, {
      foldTables: false,
      collapsedScopes: new Set(['s_missing']),
      internalColumnIdsByScope: internalColumnIdsByScope(loadModel('lineage-model.json')),
    })
    expect(collapsed.nodes.length).toBe(mapped.nodes.length)
    expect(collapsed.edges.length).toBe(mapped.edges.length)
  })
})

describe('applyCollapse — 手造图（端点过滤语义）', () => {
  it('只删 touch 到被收端点的边，两端都可见的边保留；展开无损还原', () => {
    const g = graphOf(
      [['a', true], ['b'], ['c'], ['d']],
      [['a', 'b'], ['b', 'd'], ['c', 'd']],
    )
    const folded = applyCollapse(g, {
      foldTables: true,
      collapsedScopes: new Set(['s1']),
      internalColumnIdsByScope: new Map([['s1', new Set(['c'])]]),
    })
    // a→b 端点 a 被收起、c→d 端点 c 被收起，只有 b→d 两端可见
    expect(folded.edges.map((e) => `${e.source}→${e.target}`)).toEqual(['b→d'])
    expect(folded.nodes.map((n) => n.id).sort()).toEqual(['b', 'd'])
    // 展开还原 = 原图（同节点同边 id 集）
    const restored = applyCollapse(folded, {
      foldTables: false,
      collapsedScopes: new Set(),
      internalColumnIdsByScope: new Map([['s1', new Set(['c'])]]),
    })
    expect(restored.nodes.length).toBe(folded.nodes.length)
    // 注意：被收节点在 folded 里已不存在，无法从折叠图还原——还原走的是
    // 「换折叠集合重算原图」，这里验证的是重算路径的自洽（展开后全量可见）。
    const full = applyCollapse(g, {
      foldTables: false,
      collapsedScopes: new Set(),
      internalColumnIdsByScope: new Map([['s1', new Set(['c'])]]),
    })
    expect(full.edges.length).toBe(3)
    expect(full.nodes.length).toBe(4)
  })
})
