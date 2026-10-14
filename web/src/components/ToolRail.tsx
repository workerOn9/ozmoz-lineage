import { useAppStore } from '../state/store'
import type { PanelTool } from '../state/store'

/** IDEA 风格右侧纵向工具条：两个工具二选一 + 面板展开 / 收起。 */

const TOOLS: Array<{ id: PanelTool; icon: string; label: string; hint: string }> = [
  { id: 'tree', icon: '≣', label: '归一化树', hint: '归一化语法树：可搜索，点节点定位 SQL 并联动血缘图' },
  { id: 'graph', icon: '◈', label: '血缘图', hint: '列级血缘图：可搜索，点节点 / 边定位 SQL 并联动归一化树' },
]

export function ToolRail() {
  const tool = useAppStore((s) => s.panelTool)
  const open = useAppStore((s) => s.panelOpen)
  const setPanelTool = useAppStore((s) => s.setPanelTool)
  const setPanelOpen = useAppStore((s) => s.setPanelOpen)

  return (
    <div className="flex w-10 shrink-0 flex-col items-center gap-1 border-l border-neutral-200 bg-neutral-50 py-2">
      {TOOLS.map((t) => {
        const active = open && tool === t.id
        return (
          <button
            key={t.id}
            title={`${t.label}——${t.hint}`}
            className={`flex h-9 w-9 items-center justify-center rounded text-[16px] ${
              active ? 'bg-brand text-white' : 'text-neutral-500 hover:bg-neutral-200'
            }`}
            onClick={() => setPanelTool(t.id)}
          >
            {t.icon}
          </button>
        )
      })}
      <button
        title={open ? '收起右侧面板' : '展开右侧面板'}
        className="mt-auto flex h-9 w-9 items-center justify-center rounded text-[14px] text-neutral-500 hover:bg-neutral-200"
        onClick={() => setPanelOpen(!open)}
      >
        {open ? '»' : '«'}
      </button>
    </div>
  )
}
