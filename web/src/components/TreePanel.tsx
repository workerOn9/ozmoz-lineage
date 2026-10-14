import { useMemo, useState } from 'react'
import { AstTreeView } from './ast/AstTreeView'
import { useAppStore } from '../state/store'
import { collectActivity } from '../lib/astSearch'

/** 右侧工具「归一化树」面板：搜索 + 树。点节点定位 SQL 并写入联动焦点（图按 span 重叠高亮）。 */
export function TreePanel() {
  const parseReport = useAppStore((s) => s.parseReport)
  const graphReport = useAppStore((s) => s.graphReport)
  const focusedSpan = useAppStore((s) => s.focusedSpan)
  const setFocusedSpan = useAppStore((s) => s.setFocusedSpan)
  const [search, setSearch] = useState('')

  // 聚合合并图没有单语句归一化树（多语句无单树）。
  const root = graphReport != null ? null : (parseReport?.root ?? null)
  const activity = useMemo(
    () => collectActivity(root, { search, focusSpan: focusedSpan }),
    [root, search, focusedSpan],
  )

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-2 border-b border-neutral-200 px-3 py-1.5">
        <span className="text-[12px] font-medium text-neutral-500">归一化树</span>
        <input
          className="ml-auto w-40 rounded border border-neutral-300 bg-white px-2 py-0.5 font-mono text-[12px] text-neutral-800"
          placeholder="搜索节点…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          title="按节点 type 或文本搜索；命中高亮并自动展开"
        />
        {search.trim() !== '' && (
          <span className="shrink-0 text-[12px] text-neutral-500">{activity.count} 处匹配</span>
        )}
      </div>
      <div className="min-h-0 flex-1">
        {graphReport != null ? (
          <div className="flex h-full items-center justify-center p-3 text-center text-[12px] leading-5 text-neutral-500">
            聚合模式：多语句已合并为一张血缘图，
            <br />
            请在右侧工具条切到「血缘图」查看
          </div>
        ) : (
          <AstTreeView
            root={root}
            activity={activity}
            onSelect={(node) => setFocusedSpan(node.span ?? null)}
          />
        )}
      </div>
    </div>
  )
}
