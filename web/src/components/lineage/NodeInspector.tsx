import { useMemo } from 'react'
import { useAppStore } from '../../state/store'
import { mapLineageModel } from '../../graph/mapping'
import { columnRefId } from '../../types'
import { revealSpan } from '../../editor/editorHolder'

/** 选中节点 / 边的详情面板（NodeInspector）。 */

function Row({ label, value, mono = true }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex gap-2 text-[12px] leading-5">
      <span className="w-20 shrink-0 text-neutral-500">{label}</span>
      <span className={mono ? 'break-all font-mono text-neutral-200' : 'text-neutral-200'}>
        {value}
      </span>
    </div>
  )
}

export function NodeInspector() {
  const lineage = useAppStore((s) => s.lineage)
  const selectedNodeId = useAppStore((s) => s.selectedNodeId)
  const selectedEdgeId = useAppStore((s) => s.selectedEdgeId)

  const mapped = useMemo(() => (lineage != null ? mapLineageModel(lineage) : null), [lineage])

  const node = mapped?.nodes.find((n) => n.id === selectedNodeId) ?? null
  const edge = lineage?.edges.find((e) => e.id === selectedEdgeId) ?? null

  const columnMeta = useMemo(() => {
    if (lineage == null || selectedNodeId == null) return null
    return (
      lineage.columns.find((c) => columnRefId(c.column) === selectedNodeId) ?? null
    )
  }, [lineage, selectedNodeId])

  if (node == null && edge == null) {
    return (
      <div className="flex h-full items-center justify-center text-xs text-neutral-500">
        点击图上的节点或边查看详情
      </div>
    )
  }

  return (
    <div className="h-full space-y-3 overflow-auto p-3">
      {node != null && (
        <>
          <div className="text-[13px] font-semibold text-neutral-100">
            {node.data.isTableNode ? '表节点' : '列节点'}
          </div>
          <Row label="label" value={node.data.label} />
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
              className="text-[12px] text-sky-400 hover:underline"
              onClick={() => revealSpan(node.data.ref.span!)}
            >
              定位到 SQL（{node.data.ref.span.start.line}:{node.data.ref.span.start.column}）
            </button>
          )}
        </>
      )}
      {edge != null && (
        <>
          <div className="text-[13px] font-semibold text-neutral-100">边 {edge.id}</div>
          <Row label="kind" value={edge.kind} />
          <Row label="transform" value={edge.transform} />
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
              <pre className="mt-1 overflow-auto rounded bg-neutral-900 p-2 font-mono text-[12px] text-amber-200">
                {edge.expression}
              </pre>
            </div>
          )}
          {edge.span != null && (
            <button
              className="text-[12px] text-sky-400 hover:underline"
              onClick={() => revealSpan(edge.span!)}
            >
              定位到 SQL（{edge.span.start.line}:{edge.span.start.column}）
            </button>
          )}
        </>
      )}
    </div>
  )
}
