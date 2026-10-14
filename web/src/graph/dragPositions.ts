import type { NodeChange, XYPosition } from '@xyflow/react'

/**
 * 节点拖动的位置覆盖（血缘图交互）。
 *
 * React Flow 传 `nodes` prop 时是**受控模式**：位置改动只通过 `onNodesChange`
 * 交回调用方，store 自己不落地（`triggerNodeChanges` 在既无 `hasDefaultNodes`
 * 又无 `onNodesChange` 时是空操作）。不接这个回调，拖动会「开始但不动」——
 * `dragging` 类出来了、节点坐标纹丝不动。
 *
 * 纯函数：只挑 `position` 改动合进覆盖表；**没有位置改动时返回 null**，调用方
 * 据此跳过 `setState`（选中 / 尺寸等改动不该触发重渲染）。
 */
export function mergePositionChanges(
  prev: ReadonlyMap<string, XYPosition>,
  changes: readonly NodeChange[],
): Map<string, XYPosition> | null {
  let moved: Array<[string, XYPosition]> | null = null
  for (const change of changes) {
    if (change.type === 'position' && change.position != null) {
      ;(moved ??= []).push([change.id, change.position])
    }
  }
  return moved == null ? null : new Map([...prev, ...moved])
}
