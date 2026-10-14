import { useMemo } from 'react'
import {
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useAppStore } from '../../state/store'
import type { GraphEdgeData, GraphNodeData, MappedEdge, MappedGraph, MappedNode } from '../../graph/mapping'
import { mapGraphReport, mapLineageModel } from '../../graph/mapping'
import { useGraphLayout } from '../../graph/useGraphLayout'
import { applyCollapse, internalColumnIdsByScope } from '../../graph/collapse'
import { GRAPH_MIN_ZOOM, shouldVirtualize } from '../../graph/perf'
import { reachableSet, subgraphHighlight } from '../../graph/traverse'
import { shortestPath } from '../../graph/path'
import { spansOverlap } from '../../lib/span'
import { revealSpan } from '../../editor/editorHolder'
import { EDGE_KIND_COLORS } from './EdgeLegend'

type OzmozNodeType = Node<GraphNodeData, 'ozmoz'>

function OzmozNode({ data, selected }: NodeProps<OzmozNodeType>) {
  return (
    <div
      className={[
        'rounded-md border px-3 py-1.5 text-[12px] font-mono shadow-sm',
        data.isTableNode
          ? 'border-dashed border-neutral-400 bg-neutral-100 text-neutral-700'
          : data.isOutput
            ? 'border-sky-500 bg-sky-50 text-sky-800'
            : 'border-neutral-300 bg-white text-neutral-800',
        selected ? 'ring-2 ring-sky-400' : '',
      ].join(' ')}
    >
      <Handle type="target" position={Position.Left} className="!h-2 !w-2 !bg-neutral-500" />
      <div className="flex items-center gap-1.5">
        <span>{data.label}</span>
        {data.isOutput && (
          <span className="rounded bg-sky-200 px-1 text-[10px] text-sky-800">输出</span>
        )}
        {data.isTableNode && (
          <span className="rounded bg-neutral-300 px-1 text-[10px] text-neutral-700">表</span>
        )}
      </div>
      <Handle type="source" position={Position.Right} className="!h-2 !w-2 !bg-neutral-500" />
    </div>
  )
}

const nodeTypes = { ozmoz: OzmozNode }

/** 稳定的空数组常量：`visible == null` 时给 hook 的入参，避免每次渲染换身份。 */
const EMPTY_NODES: MappedNode[] = []
const EMPTY_EDGES: MappedEdge[] = []
const EMPTY_IDS: ReadonlySet<string> = new Set()

/** path 高亮：路径上的节点集 + 两端都在路径上的边集。 */
function pathHighlight(mapped: MappedGraph, path: string[]) {
  const nodeIds = new Set(path)
  const edgeIds = new Set<string>()
  for (const e of mapped.edges) {
    if (nodeIds.has(e.source) && nodeIds.has(e.target)) edgeIds.add(e.id)
  }
  return { nodeIds, edgeIds }
}

export function LineageGraphView({ search = '' }: { search?: string }) {
  const lineage = useAppStore((s) => s.lineage)
  const graphReport = useAppStore((s) => s.graphReport)
  const highlight = useAppStore((s) => s.highlight)
  const focusedSpan = useAppStore((s) => s.focusedSpan)
  const foldTables = useAppStore((s) => s.foldTables)
  const collapsedScopeIds = useAppStore((s) => s.collapsedScopeIds)

  const mapped = useMemo(
    () =>
      lineage != null
        ? mapLineageModel(lineage)
        : graphReport != null
          ? mapGraphReport(graphReport)
          : null,
    [lineage, graphReport],
  )

  // 折叠（T3.4）：展示层裁剪，布局 / 渲染 / 虚拟化计数全部吃可见子图。
  // 聚合图与图库模式（GraphReport）节点无 scopeId，作用域折叠天然不生效。
  const internalNodesByScope = useMemo(
    () => (lineage != null ? internalColumnIdsByScope(lineage) : new Map()),
    [lineage],
  )
  const visible = useMemo(() => {
    if (mapped == null) return null
    if (!foldTables && collapsedScopeIds.length === 0) return mapped
    return applyCollapse(mapped, {
      foldTables,
      collapsedScopes: new Set(collapsedScopeIds),
      internalColumnIdsByScope: internalNodesByScope,
    })
  }, [mapped, foldTables, collapsedScopeIds, internalNodesByScope])

  // 布局（R2）：小图主线程同步、大图转 Web Worker（阈值门控 + 竞态保护），见 useGraphLayout。
  const { positions, computing } = useGraphLayout(
    visible?.nodes ?? EMPTY_NODES,
    visible?.edges ?? EMPTY_EDGES,
  )

  // 搜索：命中标签的节点高亮，其余变淡（搜索优先于模式高亮 / 焦点高亮）。
  const searchIds = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (q === '' || visible == null) return null
    return new Set(visible.nodes.filter((n) => n.data.label.toLowerCase().includes(q)).map((n) => n.id))
  }, [search, visible])

  // 树→图联动：归一化树点选的节点写入 focusedSpan，这里按 span 重叠高亮列节点 / 边。
  const focusHighlight = useMemo(() => {
    if (focusedSpan == null || visible == null) return null
    return {
      nodeIds: new Set(
        visible.nodes.filter((n) => spansOverlap(n.data.ref?.span, focusedSpan)).map((n) => n.id),
      ),
      edgeIds: new Set(visible.edges.filter((e) => spansOverlap(e.data.span, focusedSpan)).map((e) => e.id)),
    }
  }, [focusedSpan, visible])

  const active = searchIds != null
    ? { nodeIds: searchIds, edgeIds: EMPTY_IDS }
    : (highlight ?? focusHighlight)

  const { nodes, edges } = useMemo(() => {
    if (visible == null) return { nodes: [] as OzmozNodeType[], edges: [] as Edge[] }
    const nodes: OzmozNodeType[] = visible.nodes.map((n) => ({
      id: n.id,
      type: 'ozmoz' as const,
      position: { x: positions.get(n.id)?.x ?? 0, y: positions.get(n.id)?.y ?? 0 },
      data: n.data,
      className: active != null && !active.nodeIds.has(n.id) ? 'dimmed' : 'highlighted',
    }))
    const edges: Edge[] = visible.edges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      style: { stroke: EDGE_KIND_COLORS[e.data.kind], strokeWidth: 1.5 },
      markerEnd: {
        type: MarkerType.ArrowClosed,
        color: EDGE_KIND_COLORS[e.data.kind],
        width: 18,
        height: 18,
      },
      className: active != null && !active.edgeIds.has(e.id) ? 'dimmed' : 'highlighted',
    }))
    return { nodes, edges }
  }, [visible, active, positions])

  if (visible == null) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-neutral-500">
        运行「解析」后在此展示列级血缘图
      </div>
    )
  }

  // 大图首帧：worker 还没回坐标，先显示占位，别把节点全挤到原点。
  if (computing && positions.size === 0) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-neutral-500">
        布局中…
      </div>
    )
  }

  const onNodeClick = (_: unknown, node: Node) => {
    const { graphMode, pathFromId, setPathFrom, selectNode, setHighlight, setFocusedSpan } =
      useAppStore.getState()
    selectNode(node.id)
    // 图→SQL / 图→树联动：把该节点的源文本位置写进共享焦点并选中编辑器。
    const span = (node.data as GraphNodeData).ref?.span
    setFocusedSpan(span ?? null)
    if (span != null) revealSpan(span)
    if (graphMode === 'upstream' || graphMode === 'downstream') {
      const set = reachableSet(visible.edges, node.id, graphMode)
      setHighlight(subgraphHighlight(visible.edges, set))
    } else if (graphMode === 'path') {
      if (pathFromId == null) {
        setPathFrom(node.id)
      } else {
        const path = shortestPath(visible.edges, pathFromId, node.id, 'downstream')
        setHighlight(path != null ? pathHighlight(visible, path) : null)
        setPathFrom(null)
      }
    }
  }

  const onEdgeClick = (_: unknown, edge: Edge) => {
    const { selectEdge, setFocusedSpan } = useAppStore.getState()
    selectEdge(edge.id)
    const span = (edge.data as GraphEdgeData | undefined)?.span
    setFocusedSpan(span ?? null)
    if (span != null) revealSpan(span)
  }

  return (
    <div className="relative h-full w-full">
      {computing && (
        <div className="pointer-events-none absolute right-3 top-3 z-10 rounded bg-neutral-200/90 px-2 py-1 text-[11px] text-neutral-500 shadow">
          布局中…
        </div>
      )}
      {searchIds != null && (
        <div className="pointer-events-none absolute left-3 top-3 z-10 rounded bg-neutral-200/90 px-2 py-1 text-[11px] text-neutral-600 shadow">
          搜索：{searchIds.size} 个节点
        </div>
      )}
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodeClick={onNodeClick}
        onEdgeClick={onEdgeClick}
        fitView
        fitViewOptions={{ padding: 0.15 }}
        // 大图性能治理（R2）：视口外不渲染。按规模门控，小图行为不变；
        // 折叠后按**可见**节点数计（T3.4 之后折叠本身就是第一道裁剪）。
        onlyRenderVisibleElements={shouldVirtualize(visible.nodes.length)}
        minZoom={GRAPH_MIN_ZOOM}
        // 图上不需要 Space 拖拽平移 / Backspace 删节点：这两个默认全局键绑定会
        // preventDefault，而它的输入框守卫只看 nodeName/contenteditable——
        // Monaco 换输入通道（如 EditContext div）时守卫会漏，把编辑器里的
        // 空格 / 退格吃掉。显式关掉，图交互不受影响（拖拽平移仍可用）。
        panActivationKeyCode={null}
        deleteKeyCode={null}
        proOptions={{ hideAttribution: true }}
      >
        <Background variant={BackgroundVariant.Dots} gap={18} size={1} color="#d4d4d8" />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  )
}
