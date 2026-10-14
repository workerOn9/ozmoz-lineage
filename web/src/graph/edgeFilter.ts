import type { EdgeKind } from '../types'
import type { MappedEdge } from './mapping'

/**
 * 图边类型过滤：图上不画的 kind 也不参与上溯 / 下溯 / 短路径（三者都吃过滤后的边）。
 *
 * 为什么需要：`LineageBuilder` 规则 E 里 PREDICATE / GROUP_BY / ORDER_BY / SOURCE 是
 * **作用域级**边——对本作用域**每一个有名字的输出列**各发一条。同一条 SQL 里一个
 * `sum(x)` 输出列会因此带上十几个「入口」，把「这一列的上游」和「这个 SELECT 的
 * 过滤/分组/来源条件」混成一张图，上溯时全被点亮。
 */
export function visibleEdges(edges: MappedEdge[], hidden: readonly EdgeKind[]): MappedEdge[] {
  if (hidden.length === 0) return edges
  const set = new Set(hidden)
  return edges.filter((e) => !set.has(e.data.kind))
}
