import type { MappedEdge } from './mapping'
import type { Direction } from './traverse'

/** 两列节点间的最短路径（BFS，方向可约束）；不可达返回 null（对应后端 path=null 语义）。 */
export function shortestPath(
  edges: MappedEdge[],
  from: string,
  to: string,
  direction: Direction,
): string[] | null {
  if (from === to) return [from]
  const adj = new Map<string, string[]>()
  const push = (a: string, b: string) => {
    const list = adj.get(a)
    if (list) list.push(b)
    else adj.set(a, [b])
  }
  for (const e of edges) {
    if (direction !== 'upstream') push(e.source, e.target)
    if (direction !== 'downstream') push(e.target, e.source)
  }
  const prev = new Map<string, string>([[from, from]])
  const queue = [from]
  while (queue.length > 0) {
    const cur = queue.shift()!
    for (const next of adj.get(cur) ?? []) {
      if (prev.has(next)) continue
      prev.set(next, cur)
      if (next === to) {
        const path = [to]
        let p = cur
        while (p !== from) {
          path.unshift(p)
          p = prev.get(p)!
        }
        path.unshift(from)
        return path
      }
      queue.push(next)
    }
  }
  return null
}
