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
import type { GraphNodeData, MappedGraph } from '../../graph/mapping'
import { mapGraphReport, mapLineageModel } from '../../graph/mapping'
import { layoutGraph } from '../../graph/layout'
import { applyCollapse, internalColumnIdsByScope } from '../../graph/collapse'
import { GRAPH_MIN_ZOOM, shouldVirtualize } from '../../graph/perf'
import { reachableSet, subgraphHighlight } from '../../graph/traverse'
import { shortestPath } from '../../graph/path'
import { EDGE_KIND_COLORS } from './EdgeLegend'

type OzmozNodeType = Node<GraphNodeData, 'ozmoz'>

function OzmozNode({ data, selected }: NodeProps<OzmozNodeType>) {
  return (
    <div
      className={[
        'rounded-md border px-3 py-1.5 text-[12px] font-mono shadow-sm',
        data.isTableNode
          ? 'border-dashed border-neutral-500 bg-neutral-800/60 text-neutral-300'
          : data.isOutput
            ? 'border-sky-500 bg-sky-950/80 text-sky-200'
            : 'border-neutral-600 bg-neutral-800/80 text-neutral-200',
        selected ? 'ring-2 ring-sky-400' : '',
      ].join(' ')}
    >
      <Handle type="target" position={Position.Left} className="!h-2 !w-2 !bg-neutral-500" />
      <div className="flex items-center gap-1.5">
        <span>{data.label}</span>
        {data.isOutput && (
          <span className="rounded bg-sky-700/60 px-1 text-[10px] text-sky-100">输出</span>
        )}
        {data.isTableNode && (
          <span className="rounded bg-neutral-600/60 px-1 text-[10px] text-neutral-300">表</span>
        )}
      </div>
      <Handle type="source" position={Position.Right} className="!h-2 !w-2 !bg-neutral-500" />
    </div>
  )
}

const nodeTypes = { ozmoz: OzmozNode }

/** path 高亮：路径上的节点集 + 两端都在路径上的边集。 */
function pathHighlight(mapped: MappedGraph, path: string[]) {
  const nodeIds = new Set(path)
  const edgeIds = new Set<string>()
  for (const e of mapped.edges) {
    if (nodeIds.has(e.source) && nodeIds.has(e.target)) edgeIds.add(e.id)
  }
  return { nodeIds, edgeIds }
}

export function LineageGraphView() {
  const lineage = useAppStore((s) => s.lineage)
  const graphReport = useAppStore((s) => s.graphReport)
  const highlight = useAppStore((s) => s.highlight)
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

  const { nodes, edges } = useMemo(() => {
    if (visible == null) return { nodes: [] as OzmozNodeType[], edges: [] as Edge[] }
    const laid = layoutGraph(visible.nodes, visible.edges)
    const nodes: OzmozNodeType[] = visible.nodes.map((n) => ({
      id: n.id,
      type: 'ozmoz' as const,
      position: { x: laid.get(n.id)?.x ?? 0, y: laid.get(n.id)?.y ?? 0 },
      data: n.data,
      className: highlight != null && !highlight.nodeIds.has(n.id) ? 'dimmed' : 'highlighted',
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
      className: highlight != null && !highlight.edgeIds.has(e.id) ? 'dimmed' : 'highlighted',
    }))
    return { nodes, edges }
  }, [visible, highlight])

  if (visible == null) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-neutral-500">
        运行「解析」后在此展示列级血缘图
      </div>
    )
  }

  const onNodeClick = (_: unknown, node: Node) => {
    const { graphMode, pathFromId, setPathFrom, selectNode, setHighlight } = useAppStore.getState()
    selectNode(node.id)
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

  return (
    <div className="h-full w-full">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodeClick={onNodeClick}
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
        <Background variant={BackgroundVariant.Dots} gap={18} size={1} color="#333" />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  )
}
