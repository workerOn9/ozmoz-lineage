import { useAppStore } from '../../state/store'
import type { GraphMode } from '../../state/store'

const MODES: Array<{ id: GraphMode; label: string; hint: string }> = [
  { id: 'none', label: '浏览', hint: '点击节点查看详情' },
  { id: 'upstream', label: '上溯', hint: '点击列节点，高亮其全部上游来源' },
  { id: 'downstream', label: '下溯', hint: '点击列节点，高亮其全部下游去向' },
  { id: 'path', label: '短路径', hint: '依次点击起点、终点两列节点，高亮最短路径' },
]

/** 图折叠（T3.4）：折叠表节点开关 + 作用域收起 chips（仅 LineageModel 有作用域）。 */
function FoldControls() {
  const lineage = useAppStore((s) => s.lineage)
  const foldTables = useAppStore((s) => s.foldTables)
  const toggleFoldTables = useAppStore((s) => s.toggleFoldTables)
  const collapsedScopeIds = useAppStore((s) => s.collapsedScopeIds)
  const toggleScopeCollapse = useAppStore((s) => s.toggleScopeCollapse)

  const collapsibleScopes = (lineage?.scopes ?? []).filter((sc) => {
    // 没有内部列的作用域收起等于没收，不出 chip。
    return lineage!.columns.some((c) => c.scopeId === sc.id && !c.isOutput)
  })

  if (collapsibleScopes.length === 0 && !foldTables) {
    return (
      <button
        className="rounded border border-neutral-300 px-2 py-0.5 text-[12px] text-neutral-500 hover:bg-neutral-200"
        onClick={toggleFoldTables}
        title="隐藏 table: 哨兵节点与 SOURCE 边（T3.4 折叠）"
      >
        折叠表节点
      </button>
    )
  }

  return (
    <div className="flex items-center gap-1.5">
      <button
        className={`rounded border px-2 py-0.5 text-[12px] ${
          foldTables
            ? 'border-sky-600 bg-sky-100 text-sky-800'
            : 'border-neutral-300 text-neutral-500 hover:bg-neutral-200'
        }`}
        onClick={toggleFoldTables}
        title="隐藏 table: 哨兵节点与 SOURCE 边（T3.4 折叠）"
      >
        {foldTables ? '已折叠表节点' : '折叠表节点'}
      </button>
      {collapsibleScopes.map((sc) => {
        const on = collapsedScopeIds.includes(sc.id)
        return (
          <button
            key={sc.id}
            className={`rounded border px-2 py-0.5 text-[12px] ${
              on
                ? 'border-fuchsia-600 bg-fuchsia-950/60 text-fuchsia-200'
                : 'border-neutral-300 text-neutral-500 hover:bg-neutral-200'
            }`}
            onClick={() => toggleScopeCollapse(sc.id)}
            title={on ? '展开该作用域内部列' : '收起该作用域的内部列（output 保留）'}
          >
            {sc.kind} {on ? '▼' : '▶'}
          </button>
        )
      })}
    </div>
  )
}

/**
 * 未归属列引用提示（2026-10-14 真实 SQL 反馈）：`unknowns` 此前只在契约里、界面上
 * 一个字都不给——图上「凭空少了几条线」没有任何解释。裸列歧义（未收录的表 / 视图）
 * 是最常见的一类，点开 Schema 面板补 DDL 即可消歧。
 */
function UnknownsBadge() {
  const lineage = useAppStore((s) => s.lineage)
  const schema = useAppStore((s) => s.schema)
  const setSchemaOpen = useAppStore((s) => s.setSchemaOpen)
  const n = lineage?.unknowns.length ?? 0
  if (n === 0) return null
  return (
    <button
      className="shrink-0 rounded border border-amber-300 bg-amber-50 px-2 py-0.5 text-[12px] text-amber-800 hover:bg-amber-100"
      title={
        `有 ${n} 处列引用无法唯一归属，这些列在图上不会有连线。` +
        (schema.trim() === ''
          ? '把表 / 视图 DDL 粘到 Schema 面板（裸列归属 + 星号展开的元数据来源）。'
          : '在 Schema 面板补全缺失的表 / 视图定义。')
      }
      onClick={() => setSchemaOpen(true)}
    >
      未归属 {n}
    </button>
  )
}

export function GraphToolbar({ search, onSearch }: { search: string; onSearch: (v: string) => void }) {
  const graphMode = useAppStore((s) => s.graphMode)
  const setGraphMode = useAppStore((s) => s.setGraphMode)
  const pathFromId = useAppStore((s) => s.pathFromId)
  const highlight = useAppStore((s) => s.highlight)
  const setHighlight = useAppStore((s) => s.setHighlight)

  const active = MODES.find((m) => m.id === graphMode) ?? MODES[0]

  return (
    <div className="flex items-center gap-2 border-b border-neutral-200 px-3 py-1.5">
      <div className="flex overflow-hidden rounded border border-neutral-300">
        {MODES.map((m) => (
          <button
            key={m.id}
            className={`px-2.5 py-1 text-[12px] ${
              graphMode === m.id
                ? 'bg-brand text-white'
                : 'bg-neutral-100 text-neutral-500 hover:bg-neutral-200'
            }`}
            onClick={() => setGraphMode(m.id)}
          >
            {m.label}
          </button>
        ))}
      </div>
      <input
        className="w-40 rounded border border-neutral-300 bg-white px-2 py-0.5 font-mono text-[12px] text-neutral-800"
        placeholder="搜索节点…"
        value={search}
        onChange={(e) => onSearch(e.target.value)}
        title="按节点标签搜索；命中高亮，其余变淡"
      />
      <span className="truncate text-[12px] text-neutral-500">
        {graphMode === 'path' && pathFromId != null
          ? `已选起点 ${pathFromId}，点击终点`
          : active.hint}
      </span>
      <UnknownsBadge />
      <FoldControls />
      {highlight != null && (
        <button
          className="ml-auto rounded border border-neutral-300 px-2 py-0.5 text-[12px] text-neutral-500 hover:bg-neutral-200"
          onClick={() => setHighlight(null)}
        >
          清除高亮
        </button>
      )}
    </div>
  )
}
