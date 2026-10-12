import type { MappedGraph } from './mapping'
import type { LineageModel } from '../types'
import { columnRefId } from '../types'

/**
 * 图折叠（web 设计稿 T3.4 展开/折叠：避免大图一次性铺满）。
 *
 * 纯函数：输入映射图 + 折叠集合，输出可见子图。两条折叠规则——
 *
 * 1. **折叠表节点**：隐藏全部 `table:` 哨兵节点（及其 SOURCE 边，按端点过滤
 *    自然消失）。表节点只是结构哨兵，是图上扇出最大的噪声源；对
 *    LineageModel 与聚合图（GraphReport，ADR-0011）都适用。
 * 2. **折叠作用域**：LineageModel 独有（GraphReport 节点无 scopeId）。收起
 *    某作用域的**内部列节点**（非 output），output 列保留、血缘流不断线；
 *    再次展开无损还原。设计稿验收「折叠一个 scope 后其子节点收起；展开
 *    恢复」——这里的「子节点」取作用域内非 output 的列节点。
 */
export interface CollapseInput {
  /** 是否折叠表节点（隐藏 table: 哨兵 + SOURCE 边）。 */
  foldTables: boolean
  /** 已折叠的作用域 id 集合（收起其内部列节点）。 */
  collapsedScopes: ReadonlySet<string>
  /**
   * scopeId → 该作用域内部列节点 id（非 output）。LineageModel 路径由
   * [internalColumnIdsByScope] 预计算；聚合图传空 map（无作用域可折叠）。
   */
  internalColumnIdsByScope: ReadonlyMap<string, ReadonlySet<string>>
}

/**
 * 折叠后的可见子图。折叠是展示层的裁剪：不修改节点/边内容，新命名的图 =
 * 原图去掉 hidden 节点与 touch 到 hidden 端点的边——高亮 / NodeInspector
 * / 虚拟化计数全部直接消费这份可见子图。
 */
export function applyCollapse(mapped: MappedGraph, input: CollapseInput): MappedGraph {
  const hidden = new Set<string>()

  if (input.foldTables) {
    for (const n of mapped.nodes) {
      if (n.data.isTableNode) hidden.add(n.id)
    }
  }
  for (const scopeId of input.collapsedScopes) {
    for (const id of input.internalColumnIdsByScope.get(scopeId) ?? []) {
      hidden.add(id)
    }
  }

  if (hidden.size === 0) return mapped

  const nodes = mapped.nodes.filter((n) => !hidden.has(n.id))
  const edges = mapped.edges.filter(
    (e) => !hidden.has(e.source) && !hidden.has(e.target),
  )
  return { nodes, edges }
}

/**
 * 从 LineageModel 预计算「scopeId → 内部列节点 id（非 output）」。
 * 只在有 scope 的模型上有意义；output 列永远保留（折叠=收起内部细节，
 * 不断血缘流）。表哨兵节点不属于任何 scope，天然不受影响。
 */
export function internalColumnIdsByScope(
  model: LineageModel,
): Map<string, Set<string>> {
  const out = new Map<string, Set<string>>()
  if (model.scopes.length === 0) return out
  for (const c of model.columns) {
    if (c.scopeId == null || c.isOutput) continue
    let set = out.get(c.scopeId)
    if (set == null) {
      set = new Set()
      out.set(c.scopeId, set)
    }
    set.add(columnRefId(c.column))
  }
  return out
}
