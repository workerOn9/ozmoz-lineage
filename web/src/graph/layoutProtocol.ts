import type { MappedEdge, MappedNode } from './mapping'
import { layoutGraph, type PositionedNode } from './layout'

/**
 * 大图布局放 **Web Worker**（web 设计稿 R2「大图性能治理」）。
 *
 * R2 的对策原文是「布局计算放 `useMemo` 并考虑 Web Worker（**后续**）」。虚拟化
 * （PR #37）与折叠（PR #38）解决的是「渲染」和「图上节点数」，本条解决最后一块：
 * **把 dagre 布局从主线程移走**——大图布局是同步纯计算，主线程跑它期间界面会卡死。
 *
 * 与 `shouldVirtualize` 同思路**按规模门控**：小图（单语句几十个节点）主线程同步算
 * 完全够用，转 Worker 反而多一次结构化克隆与异步往返；低于阈值时行为与旧版一致。
 */

/** 布局转 Worker 的规模阈值（节点数）。低于此值主线程同步算。 */
export const LAYOUT_WORKER_MIN_NODES = 200

export function shouldUseWorkerLayout(nodeCount: number): boolean {
  return nodeCount >= LAYOUT_WORKER_MIN_NODES
}

/**
 * 主线程 → worker 的请求。带 `seq` 做**竞态保护**：数据快速变化（连续折叠 / 换图库）
 * 时，只认最新一次请求的结果，过期的丢弃。
 *
 * 载荷直接用 `MappedNode` / `MappedEdge`（都是普通对象，可结构化克隆），不另造一套
 * 精简形状——少一层映射，也就少一处可能与 `layoutGraph` 的宽度计算漂移的地方。
 */
export interface LayoutRequest {
  seq: number
  nodes: MappedNode[]
  edges: MappedEdge[]
}

/** worker → 主线程的响应。`Map` 用数组承载，协议形状显式、便于测试。 */
export interface LayoutResponse {
  seq: number
  positions: PositionedNode[]
}

/** worker 内的纯计算：dagre 布局 → 可结构化克隆的坐标数组。 */
export function runLayout(nodes: MappedNode[], edges: MappedEdge[]): PositionedNode[] {
  return Array.from(layoutGraph(nodes, edges).values())
}

/** 坐标数组 → 组件消费的 `Map`（`layoutGraph` 的返回形状）。 */
export function positionsFromArray(rows: PositionedNode[]): Map<string, PositionedNode> {
  return new Map(rows.map((row) => [row.id, row]))
}
