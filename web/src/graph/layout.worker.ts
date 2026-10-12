import { runLayout, type LayoutRequest, type LayoutResponse } from './layoutProtocol'

/**
 * 图布局 worker（web 设计稿 R2）。只做一件事：收 [LayoutRequest]，算 dagre 坐标，
 * 回 [LayoutResponse]。计算本身在 `layoutProtocol.runLayout`（纯函数，单测直接调，
 * 不必起真实 worker）。
 *
 * 不依赖 DOM lib 的 `DedicatedWorkerGlobalScope`（本项目 tsconfig 的 lib 是 DOM：
 * DOM 与 WebWorker 两套 lib 的 `self` / `postMessage` 签名冲突），改用最小结构类型
 * 收口 `self` 的形状——够用且不污染其他文件的类型环境。
 */
interface LayoutWorkerScope {
  onmessage: ((event: MessageEvent<LayoutRequest>) => void) | null
  postMessage(message: LayoutResponse): void
}

const scope = self as unknown as LayoutWorkerScope

scope.onmessage = (event) => {
  const { seq, nodes, edges } = event.data
  scope.postMessage({ seq, positions: runLayout(nodes, edges) })
}
