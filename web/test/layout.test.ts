import { describe, expect, it } from 'vitest'
import type { MappedEdge, MappedNode } from '../src/graph/mapping'
import { layoutGraph } from '../src/graph/layout'
import {
  LAYOUT_WORKER_MIN_NODES,
  shouldUseWorkerLayout,
  runLayout,
  positionsFromArray,
} from '../src/graph/layoutProtocol'

function nodes(ids: string[]): MappedNode[] {
  return ids.map((id) => ({
    id,
    data: { label: id, isOutput: false, isTableNode: false, ref: { raw: id, canonical: id, name: id } },
  }))
}

function edges(...pairs: Array<[string, string]>): MappedEdge[] {
  return pairs.map(([source, target], i) => ({
    id: `e${i}`,
    source,
    target,
    data: { kind: 'OUTPUT', transform: 'DIRECT' },
  }))
}

describe('layoutProtocol：阈值门控', () => {
  it('小图不走 worker，达到阈值才走', () => {
    expect(shouldUseWorkerLayout(0)).toBe(false)
    expect(shouldUseWorkerLayout(LAYOUT_WORKER_MIN_NODES - 1)).toBe(false)
    expect(shouldUseWorkerLayout(LAYOUT_WORKER_MIN_NODES)).toBe(true)
    expect(shouldUseWorkerLayout(LAYOUT_WORKER_MIN_NODES + 1)).toBe(true)
  })
})

describe('layoutProtocol：runLayout 与主线程 layoutGraph 同构', () => {
  it('同一输入的坐标逐节点相等（worker 不改变布局结果）', () => {
    const n = nodes(['a', 'b', 'c'])
    const e = edges(['a', 'b'], ['b', 'c'])
    const viaMain = layoutGraph(n, e)
    const viaWorker = runLayout(n, e)

    expect(viaWorker.length).toBe(viaMain.size)
    for (const row of viaWorker) {
      const main = viaMain.get(row.id)
      expect(main).toBeDefined()
      expect(row.x).toBe(main!.x)
      expect(row.y).toBe(main!.y)
      expect(row.width).toBe(main!.width)
      expect(row.height).toBe(main!.height)
    }
  })

  it('空图返回空数组', () => {
    expect(runLayout([], [])).toEqual([])
  })

  it('大图冒烟：200+ 节点每个都有有限坐标', () => {
    const count = LAYOUT_WORKER_MIN_NODES + 100
    const ids = Array.from({ length: count }, (_, i) => `n${i}`)
    const e = edges(
      ...Array.from({ length: count - 1 }, (_, i) => [`n${i}`, `n${i + 1}`] as [string, string]),
    )
    const rows = runLayout(nodes(ids), e)
    expect(rows.length).toBe(count)
    for (const row of rows) {
      expect(Number.isFinite(row.x)).toBe(true)
      expect(Number.isFinite(row.y)).toBe(true)
    }
  })
})

describe('layoutProtocol：坐标数组 ↔ Map', () => {
  it('positionsFromArray 还原为 id → 坐标', () => {
    const map = positionsFromArray([
      { id: 'a', x: 1, y: 2, width: 10, height: 20 },
      { id: 'b', x: 3, y: 4, width: 30, height: 40 },
    ])
    expect(map.size).toBe(2)
    expect(map.get('a')).toEqual({ id: 'a', x: 1, y: 2, width: 10, height: 20 })
    expect(map.get('b')?.x).toBe(3)
  })
})
