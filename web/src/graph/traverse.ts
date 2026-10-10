import type { MappedEdge } from './mapping'

/** 上溯 / 下溯 / 双向可达集（BFS，含起点）。降级路径（设计稿 §2.4 方案 B）：单语句图本地算。 */

export type Direction = 'upstream' | 'downstream' | 'both'

function adjacency(edges: MappedEdge[], direction: Direction): Map<string, string[]> {
  const adj = new Map<string, string[]>()
  const push = (from: string, to: string) => {
    const list = adj.get(from)
    if (list) list.push(to)
    else adj.set(from, [to])
  }
  for (const e of edges) {
    if (direction !== 'upstream') push(e.source, e.target)
    if (direction !== 'downstream') push(e.target, e.source)
  }
  return adj
}

export function reachableSet(edges: MappedEdge[], origin: string, direction: Direction): Set<string> {
  const adj = adjacency(edges, direction)
  const seen = new Set<string>([origin])
  const queue = [origin]
  while (queue.length > 0) {
    const cur = queue.shift()!
    for (const next of adj.get(cur) ?? []) {
      if (!seen.has(next)) {
        seen.add(next)
        queue.push(next)
      }
    }
  }
  return seen
}

export interface HighlightResult {
  nodeIds: Set<string>
  edgeIds: Set<string>
}

/** 可达子图：节点集 + 两端都在集内的边。 */
export function subgraphHighlight(edges: MappedEdge[], nodeIds: Set<string>): HighlightResult {
  const edgeIds = new Set<string>()
  for (const e of edges) {
    if (nodeIds.has(e.source) && nodeIds.has(e.target)) edgeIds.add(e.id)
  }
  return { nodeIds, edgeIds }
}
