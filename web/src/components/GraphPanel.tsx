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
  const inspectorOpen = useAppStore((s) => s.inspectorOpen)
  const [search, setSearch] = useState('')

  return (
    <div className="flex h-full min-h-0 flex-col">
      <GraphToolbar search={search} onSearch={setSearch} />
      <div className="min-h-0 flex-[3]">
        <LineageGraphView search={search} />
      </div>
      <EdgeLegend model={lineage ?? graphReport} />
      {/* 详情收起时只留一条表头，把剩余高度全让给上面的图。 */}
      <div className={`border-t border-neutral-200 ${inspectorOpen ? 'min-h-0 flex-[2]' : 'shrink-0'}`}>
        <NodeInspector />
      </div>
    </div>
  )
}
