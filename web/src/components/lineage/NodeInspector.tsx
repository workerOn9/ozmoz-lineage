import { useMemo } from 'react'
import { useAppStore } from '../../state/store'
import { mapGraphReport, mapLineageModel } from '../../graph/mapping'
import { columnRefId } from '../../types'
import { revealSpan } from '../../editor/editorHolder'

/** 选中节点 / 边的详情面板（NodeInspector）。聚合图（GraphReport）节点无 ColumnRef，只展示 id/label。 */

function Row({ label, value, mono = true }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex gap-2 text-[12px] leading-5">
      <span className="w-20 shrink-0 text-neutral-500">{label}</span>
      <span className={mono ? 'break-all font-mono text-neutral-800' : 'text-neutral-800'}>
        {value}
      </span>
    </div>
  )
}

export function NodeInspector() {
  const lineage = useAppStore((s) => s.lineage)
  const graphReport = useAppStore((s) => s.graphReport)
  const selectedNodeId = useAppStore((s) => s.selectedNodeId)
  const selectedEdgeId = useAppStore((s) => s.selectedEdgeId)
  const foldTables = useAppStore((s) => s.foldTables)
  const toggleFoldTables = useAppStore((s) => s.toggleFoldTables)
  const collapsedScopeIds = useAppStore((s) => s.collapsedScopeIds)
  const toggleScopeCollapse = useAppStore((s) => s.toggleScopeCollapse)
  const inspectorOpen = useAppStore((s) => s.inspectorOpen)
  const setInspectorOpen = useAppStore((s) => s.setInspectorOpen)

  const mapped = useMemo(
    () =>
      lineage != null
        ? mapLineageModel(lineage)
        : graphReport != null
          ? mapGraphReport(graphReport)
          : null,
    [lineage, graphReport],
  )

  const node = mapped?.nodes.find((n) => n.id === selectedNodeId) ?? null
  const edge = lineage?.edges.find((e) => e.id === selectedEdgeId)
    ?? graphReport?.edges.find((e) => e.id === selectedEdgeId)
    ?? null

  const columnMeta = useMemo(() => {
    if (lineage == null || selectedNodeId == null) return null
    return (
      lineage.columns.find((c) => columnRefId(c.column) === selectedNodeId) ?? null
    )
  }, [lineage, selectedNodeId])

  // 折叠（T3.4）：表节点 ↔ SOURCE 边开、关；列节点 hover 到所属作用域的
  // 收起开关（output 列收起后仍常驻）。
  const collapseButton = useMemo(() => {
    if (node == null) return null
    if (node.data.isTableNode) {
      return {
        label: foldTables ? '展开该表的 SOURCE 边' : '收起该表（隐藏 SOURCE 边）',
        action: toggleFoldTables,
      }
    }
    const scopeId = columnMeta?.scopeId
    if (scopeId == null) return null
    const collapsed = collapsedScopeIds.includes(scopeId)
    return {
      label: collapsed ? '展开该作用域的内部列' : '收起该作用域的内部列',
      action: () => toggleScopeCollapse(scopeId),
    }
  }, [node, columnMeta, foldTables, collapsedScopeIds, toggleFoldTables, toggleScopeCollapse])

  // 详情面板表头：收起时只留这一条，把高度让给血缘图。选中对象写在标题里，
  // 收起状态也能看出选的是什么。
  const title =
    node != null
      ? `${node.data.isTableNode ? '表节点' : '列节点'}详情`
      : edge != null
        ? `边 ${edge.id}`
        : '详情'

  return (
    <div className="flex h-full flex-col">
      <button
        className="flex w-full shrink-0 items-center gap-2 px-3 py-1 text-left text-[12px] text-neutral-500 hover:bg-neutral-100"
        title={inspectorOpen ? '收起详情，把空间让给血缘图' : '展开详情'}
        onClick={() => setInspectorOpen(!inspectorOpen)}
      >
        <span className="truncate">{title}</span>
        <span className="ml-auto shrink-0">{inspectorOpen ? '▾' : '▸'}</span>
      </button>
      {inspectorOpen &&
        (node == null && edge == null ? (
          <div className="flex min-h-0 flex-1 items-center justify-center text-xs text-neutral-500">
            点击图上的节点或边查看详情
          </div>
        ) : (
          <div className="min-h-0 flex-1 space-y-3 overflow-auto p-3">
            {node != null && (
              <>
                <div className="text-[13px] font-semibold text-neutral-900">
                  {node.data.isTableNode ? '表节点' : '列节点'}
                </div>
                <Row label="label" value={node.data.label} />
                {node.data.ref != null ? (
                  <>
                    <Row label="raw" value={node.data.ref.raw} />
                    <Row label="canonical" value={node.data.ref.canonical} />
                    {node.data.ref.table != null && <Row label="table" value={node.data.ref.table} />}
                    {node.data.ref.schema != null && <Row label="schema" value={node.data.ref.schema} />}
                    {columnMeta != null && (
                      <>
                        <Row label="isOutput" value={String(columnMeta.isOutput)} />
                        {columnMeta.scopeId != null && <Row label="scope" value={columnMeta.scopeId} />}
                        {columnMeta.type != null && <Row label="type" value={columnMeta.type} />}
                        {columnMeta.nullable != null && (
                          <Row label="nullable" value={String(columnMeta.nullable)} />
                        )}
                      </>
                    )}
                    {node.data.ref.span != null && (
                      <button
                        className="text-[12px] text-sky-600 hover:underline"
                        onClick={() => revealSpan(node.data.ref!.span!)}
                      >
                        定位到 SQL（{node.data.ref.span.start.line}:{node.data.ref.span.start.column}）
                      </button>
                    )}
                  </>
                ) : (
                  // 聚合图（GraphReport）节点没有 ColumnRef：只能给稳定 id。
                  <Row label="id" value={node.id} />
                )}
                {collapseButton != null && (
                  <button
                    className="rounded border border-neutral-300 px-2 py-1 text-[12px] text-neutral-500 hover:bg-neutral-200"
                    onClick={collapseButton.action}
                  >
                    {collapseButton.label}
                  </button>
                )}
              </>
            )}
            {edge != null && (
              <>
                <div className="text-[13px] font-semibold text-neutral-900">边 {edge.id}</div>
                <Row label="kind" value={edge.kind} />
                <Row label="transform" value={edge.transform} />
                {'fromColumn' in edge ? (
                  <>
                    <Row
                      label="from"
                      value={
                        edge.fromColumn.table != null
                          ? `${edge.fromColumn.table}.${edge.fromColumn.name}`
                          : edge.fromColumn.name
                      }
                    />
                    <Row
                      label="to"
                      value={
                        edge.toColumn.table != null
                          ? `${edge.toColumn.table}.${edge.toColumn.name}`
                          : edge.toColumn.name
                      }
                    />
                    {edge.expression != null && (
                      <div>
                        <div className="text-[12px] text-neutral-500">expression</div>
                        <pre className="mt-1 overflow-auto rounded bg-neutral-100 p-2 font-mono text-[12px] text-amber-800">
                          {edge.expression}
                        </pre>
                      </div>
                    )}
                    {edge.span != null && (
                      <button
                        className="text-[12px] text-sky-600 hover:underline"
                        onClick={() => revealSpan(edge.span!)}
                      >
                        定位到 SQL（{edge.span.start.line}:{edge.span.start.column}）
                      </button>
                    )}
                  </>
                ) : (
                  // 聚合图边：端点是列 id（无 ColumnRef / expression / span）。
                  <>
                    <Row label="from" value={edge.from} />
                    <Row label="to" value={edge.to} />
                  </>
                )}
              </>
            )}
          </div>
        ))}
    </div>
  )
}
