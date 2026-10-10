import dagre from '@dagrejs/dagre'
import type { MappedEdge, MappedNode } from './mapping'

/**
 * dagre 分层布局（web 设计稿 §2.3：LR 方向，血缘「来源 → 输出」从左到右）。
 * 抽象成单一函数：将来换 elk 只改这里，组件无感。
 */

export interface PositionedNode {
  id: string
  x: number
  y: number
  width: number
  height: number
}

const NODE_HEIGHT = 36
const NODE_WIDTH_MIN = 120
const CHAR_WIDTH = 8
const PAD_X = 28

export function layoutGraph(nodes: MappedNode[], edges: MappedEdge[]): Map<string, PositionedNode> {
  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: 'LR', nodesep: 30, ranksep: 90, marginx: 24, marginy: 24 })
  g.setDefaultEdgeLabel(() => ({}))

  for (const n of nodes) {
    const width = Math.max(NODE_WIDTH_MIN, n.data.label.length * CHAR_WIDTH + PAD_X)
    g.setNode(n.id, { width, height: NODE_HEIGHT })
  }
  for (const e of edges) {
    // 平行边 dagre 可以消化；忽略自环（不该出现）。
    if (e.source !== e.target) g.setEdge(e.source, e.target)
  }
  dagre.layout(g)

  const out = new Map<string, PositionedNode>()
  for (const n of nodes) {
    const laid = g.node(n.id)
    if (laid) {
      out.set(n.id, {
        id: n.id,
        // dagre 给的是中心点，React Flow 要左上角。
        x: laid.x - laid.width / 2,
        y: laid.y - laid.height / 2,
        width: laid.width,
        height: laid.height,
      })
    }
  }
  return out
}
