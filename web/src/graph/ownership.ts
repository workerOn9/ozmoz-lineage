import type { MappedEdge, MappedGraph } from './mapping'
import { tableNodeId } from './mapping'

/**
 * 「所属」虚线（纯展示）：给**没有入边**的物理列节点补一条虚线连到它所属的表节点。
 *
 * 为什么需要：物理列是一张真表里真实存在的列，上游没有东西；模型里也不存在
 * 「表 → 列」归属边——SOURCE 的目标是**本作用域的输出槽**，不是表自己的列
 * （见 `LineageBuilder` 规则 E）。于是这类列在图上是最左端的叶子，看起来「悬空」，
 * 只能靠标签 `表名.列名` 猜出处。
 *
 * 边界（Never-wrong）：
 * - 只给**零入边**的列补线（有入边说明它已有上游，不悬空）；表哨兵自身不补。
 * - 列的 `ref.table` 是派生表名（`subquery_0` 之类）时**没有**对应表节点，不补——
 *   不凭空造节点。
 *
 * 调用方约定：这些边**不进上溯 / 下溯 / 短路径**（不是血缘），只进渲染与布局；
 * 用 id 前缀 [OWNERSHIP_EDGE_PREFIX] 与真血缘边区分。
 */

export const OWNERSHIP_EDGE_PREFIX = 'own:'

export function ownershipEdges(visible: MappedGraph): MappedEdge[] {
  const hasIncoming = new Set(visible.edges.map((e) => e.target))
  const nodeIds = new Set(visible.nodes.map((n) => n.id))

  const out: MappedEdge[] = []
  for (const n of visible.nodes) {
    if (n.data.isTableNode) continue
    const table = n.data.ref?.table
    if (table == null || hasIncoming.has(n.id)) continue
    const source = tableNodeId(table)
    if (!nodeIds.has(source)) continue
    out.push({
      id: `${OWNERSHIP_EDGE_PREFIX}${n.id}`,
      source,
      target: n.id,
      // 仅供布局/渲染；归属不是血缘，消费方按 id 前缀识别，不要读 kind。
      data: { kind: 'SOURCE', transform: 'SOURCE' },
    })
  }
  return out
}
