import type { EdgeKind, LineageModel } from '../../types'

/** 边类型 → 颜色（全图唯一出处，图渲染与图例共用）。 */

export const EDGE_KIND_COLORS: Record<EdgeKind, string> = {
  OUTPUT: '#60a5fa',
  PREDICATE: '#f59e0b',
  JOIN_KEY: '#34d399',
  GROUP_BY: '#c084fc',
  ORDER_BY: '#f472b6',
  SOURCE: '#94a3b8',
}

export const EDGE_KIND_LABELS: Record<EdgeKind, string> = {
  OUTPUT: 'OUTPUT 值血缘',
  PREDICATE: 'PREDICATE 过滤',
  JOIN_KEY: 'JOIN_KEY 连接键',
  GROUP_BY: 'GROUP_BY 分组',
  ORDER_BY: 'ORDER_BY 排序',
  SOURCE: 'SOURCE 表级哨兵',
}

export function EdgeLegend({ model }: { model: LineageModel | null }) {
  const counts = new Map<EdgeKind, number>()
  for (const e of model?.edges ?? []) {
    counts.set(e.kind, (counts.get(e.kind) ?? 0) + 1)
  }
  const kinds = Object.keys(EDGE_KIND_COLORS) as EdgeKind[]
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-1.5 text-[11px] text-neutral-400">
      {kinds.map((kind) => (
        <span key={kind} className="inline-flex items-center gap-1">
          <span
            className="inline-block h-[3px] w-5 rounded"
            style={{ backgroundColor: EDGE_KIND_COLORS[kind] }}
          />
          {EDGE_KIND_LABELS[kind]}
          {counts.has(kind) && <span className="text-neutral-500">×{counts.get(kind)}</span>}
        </span>
      ))}
    </div>
  )
}
