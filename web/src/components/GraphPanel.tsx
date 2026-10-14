import { useState } from 'react'
import { useAppStore } from '../state/store'
import { GraphToolbar } from './lineage/GraphToolbar'
import { LineageGraphView } from './lineage/LineageGraphView'
import { NodeInspector } from './lineage/NodeInspector'
import { EdgeLegend } from './lineage/EdgeLegend'

/** 右侧工具「血缘图」面板：工具栏 + 图 + 图例 + 检查器。 */
export function GraphPanel() {
  const lineage = useAppStore((s) => s.lineage)
  const graphReport = useAppStore((s) => s.graphReport)
  const [search, setSearch] = useState('')

  return (
    <div className="flex h-full min-h-0 flex-col">
      <GraphToolbar search={search} onSearch={setSearch} />
      <div className="min-h-0 flex-[3]">
        <LineageGraphView search={search} />
      </div>
      <EdgeLegend model={lineage ?? graphReport} />
      <div className="min-h-0 flex-[2] border-t border-neutral-200">
        <NodeInspector />
      </div>
    </div>
  )
}
