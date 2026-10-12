import { useEffect, useMemo, useRef, useState } from 'react'
import type { MappedEdge, MappedNode } from './mapping'
import { layoutGraph, type PositionedNode } from './layout'
import {
  shouldUseWorkerLayout,
  positionsFromArray,
  type LayoutRequest,
  type LayoutResponse,
} from './layoutProtocol'

/**
 * 图布局 hook（web 设计稿 R2）：小图主线程同步算（与旧版逐字一致），大图把 dagre
 * 布局丢给 [Web Worker]，并带竞态保护——数据快速变化时只认最新一次请求的结果。
 *
 * 消费方在 `computing && positions.size === 0` 时显示「布局中…」占位；已有旧坐标时
 * 沿用旧坐标（折叠 / 换高亮不会闪空白）。
 *
 * worker 单例（模块级）：布局请求可能很频繁（折叠、切图库、切高亮），每次新建 / 销毁
 * worker 不划算；单例 + `seq` 过滤即可——旧结果按 seq 丢弃，不会污染新图。
 */

let sharedWorker: Worker | null = null

function getLayoutWorker(): Worker {
  if (sharedWorker == null) {
    sharedWorker = new Worker(new URL('./layout.worker.ts', import.meta.url), { type: 'module' })
  }
  return sharedWorker
}

export interface GraphLayout {
  /** 节点 id → 坐标（`layoutGraph` 的返回形状）。 */
  positions: Map<string, PositionedNode>
  /** 是否正在算（大图异步路径）。小图恒 false。 */
  computing: boolean
}

export function useGraphLayout(nodes: MappedNode[], edges: MappedEdge[]): GraphLayout {
  const useWorker = shouldUseWorkerLayout(nodes.length)

  // 小图：同步算，行为与旧版完全一致（不进 worker、不异步、不闪）。
  const sync = useMemo(
    () => (useWorker ? null : layoutGraph(nodes, edges)),
    [useWorker, nodes, edges],
  )

  const [asyncState, setAsyncState] = useState<GraphLayout>(() => ({
    positions: new Map(),
    computing: shouldUseWorkerLayout(nodes.length),
  }))
  const seqRef = useRef(0)

  useEffect(() => {
    if (!useWorker) {
      // 使任何在途 worker 结果失效（图缩到阈值以下时不能再覆盖同步结果）。
      seqRef.current++
      return
    }
    const seq = ++seqRef.current
    // 保留旧坐标，仅置 computing——避免大图重算时节点全挤到原点。
    setAsyncState((prev) => ({ positions: prev.positions, computing: true }))

    const worker = getLayoutWorker()
    const onMessage = (event: MessageEvent<LayoutResponse>) => {
      if (event.data.seq !== seqRef.current) return // 过期结果，丢弃
      setAsyncState({ positions: positionsFromArray(event.data.positions), computing: false })
    }
    // worker 加载 / 运行失败时回落主线程同步算，别让界面永远卡在「布局中」。
    const onError = () => {
      if (seq !== seqRef.current) return
      setAsyncState({ positions: layoutGraph(nodes, edges), computing: false })
    }

    worker.addEventListener('message', onMessage)
    worker.addEventListener('error', onError)
    const request: LayoutRequest = { seq, nodes, edges }
    worker.postMessage(request)
    return () => {
      worker.removeEventListener('message', onMessage)
      worker.removeEventListener('error', onError)
    }
  }, [useWorker, nodes, edges])

  return useWorker ? asyncState : { positions: sync!, computing: false }
}
