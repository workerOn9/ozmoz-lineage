/**
 * 大图性能治理（web 设计稿 R2）。
 *
 * R2 的对策清单里「必要时启用 React Flow 的虚拟化能力」——能力名已在
 * @xyflow/react 12.12.0 核实为 `onlyRenderVisibleElements`（视口外的节点 /
 * 几何上不可见的边不渲染；边按源点—目标点与视口的几何相交判断，不要求
 * 两端节点都渲染，行为安全）。
 *
 * 有意**按规模门控**而不是恒开：小图（单语句几十个节点）开虚拟化没有收益，
 * 反而多一层视口计算；低于阈值时行为与旧版完全一致。阈值本身给得宽裕——
 * DOM 渲染几百个轻量节点尚无卡顿，上千才需要裁剪。
 */

/** 虚拟化开启阈值（节点数）。低于此值不开，保持小图行为不变。 */
export const VIRTUALIZE_MIN_NODES = 200

export function shouldVirtualize(nodeCount: number): boolean {
  return nodeCount >= VIRTUALIZE_MIN_NODES
}

/**
 * 大图 fitView 边界：React Flow 缺省 minZoom=0.5，上千节点的图 fitView
 * 会被钳在半程、看不到全图。放宽到 0.1（Controls 的缩小钮同样受益）。
 */
export const GRAPH_MIN_ZOOM = 0.1
