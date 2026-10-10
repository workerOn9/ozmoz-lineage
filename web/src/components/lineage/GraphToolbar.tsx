import { useAppStore } from '../../state/store'
import type { GraphMode } from '../../state/store'

const MODES: Array<{ id: GraphMode; label: string; hint: string }> = [
  { id: 'none', label: '浏览', hint: '点击节点查看详情' },
  { id: 'upstream', label: '上溯', hint: '点击列节点，高亮其全部上游来源' },
  { id: 'downstream', label: '下溯', hint: '点击列节点，高亮其全部下游去向' },
  { id: 'path', label: '短路径', hint: '依次点击起点、终点两列节点，高亮最短路径' },
]

export function GraphToolbar() {
  const graphMode = useAppStore((s) => s.graphMode)
  const setGraphMode = useAppStore((s) => s.setGraphMode)
  const pathFromId = useAppStore((s) => s.pathFromId)
  const highlight = useAppStore((s) => s.highlight)
  const setHighlight = useAppStore((s) => s.setHighlight)

  const active = MODES.find((m) => m.id === graphMode) ?? MODES[0]

  return (
    <div className="flex items-center gap-2 border-b border-neutral-800 px-3 py-1.5">
      <div className="flex overflow-hidden rounded border border-neutral-700">
        {MODES.map((m) => (
          <button
            key={m.id}
            className={`px-2.5 py-1 text-[12px] ${
              graphMode === m.id
                ? 'bg-sky-700 text-white'
                : 'bg-neutral-900 text-neutral-400 hover:bg-neutral-800'
            }`}
            onClick={() => setGraphMode(m.id)}
          >
            {m.label}
          </button>
        ))}
      </div>
      <span className="text-[12px] text-neutral-500">
        {graphMode === 'path' && pathFromId != null
          ? `已选起点 ${pathFromId}，点击终点`
          : active.hint}
      </span>
      {highlight != null && (
        <button
          className="ml-auto rounded border border-neutral-700 px-2 py-0.5 text-[12px] text-neutral-300 hover:bg-neutral-800"
          onClick={() => setHighlight(null)}
        >
          清除高亮
        </button>
      )}
    </div>
  )
}
