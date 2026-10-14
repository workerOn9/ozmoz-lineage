import { useEffect, useState } from 'react'
import type { AstNode } from '../../types'
import type { AstActivity } from '../../lib/astSearch'
import { revealSpan } from '../../editor/editorHolder'

/** 归一化树（AstNode）递归渲染：可折叠、按 type 着色、点节点定位到编辑器 span。
 *  `activity` 由调用方（TreePanel）算出：搜索命中 / 与焦点 span 重叠的节点高亮，
 *  并自动展开它们的祖先使其可见。 */

const TYPE_COLORS: Array<[RegExp, string]> = [
  [/^select|plain_select/, 'text-sky-600'],
  [/^table/, 'text-emerald-400'],
  [/^join/, 'text-teal-300'],
  [/^column/, 'text-amber-300'],
  [/^function|sum|count|avg|min|max/i, 'text-violet-300'],
  [/^where|having/, 'text-rose-300'],
  [/^group|^order/, 'text-fuchsia-300'],
]

export function typeColor(type: string): string {
  return TYPE_COLORS.find(([re]) => re.test(type))?.[1] ?? 'text-neutral-700'
}

export function truncate(text: string, max = 48): string {
  const single = text.replace(/\s+/g, ' ').trim()
  return single.length > max ? `${single.slice(0, max)}…` : single
}

interface RowContext {
  activePaths: Set<string>
  expandPaths: Set<string>
  overrides: Record<string, boolean>
  toggle: (key: string, next: boolean) => void
  onSelect?: (node: AstNode) => void
}

function AstNodeRow({ node, path, depth, ctx }: { node: AstNode; path: number[]; depth: number; ctx: RowContext }) {
  const key = path.join('.')
  const hasChildren = node.children.length > 0
  // 自动展开（搜索命中 / 焦点的祖先）优先于手动折叠，保证命中一定看得见。
  const expanded = ctx.expandPaths.has(key) ? true : (ctx.overrides[key] ?? depth < 2)
  const active = ctx.activePaths.has(key)

  return (
    <div>
      <div
        className={`ast-row flex cursor-pointer items-center gap-1 rounded px-1 py-0.5 text-[13px] leading-5 ${
          active ? 'bg-amber-100' : ''
        }`}
        style={{ paddingLeft: `${depth * 14 + 4}px` }}
        title={node.span != null ? `${node.span.start.line}:${node.span.start.column}` : node.text}
        onClick={() => {
          if (hasChildren) ctx.toggle(key, !expanded)
          if (node.span != null) revealSpan(node.span)
          ctx.onSelect?.(node)
        }}
      >
        <span className="w-4 shrink-0 text-center text-neutral-500 select-none">
          {hasChildren ? (expanded ? '▾' : '▸') : '·'}
        </span>
        <span className={`shrink-0 font-mono text-[12px] ${typeColor(node.type)}`}>{node.type}</span>
        <span className="truncate text-neutral-500">{truncate(node.text)}</span>
      </div>
      {expanded &&
        node.children.map((child, i) => (
          <AstNodeRow key={i} node={child} path={[...path, i]} depth={depth + 1} ctx={ctx} />
        ))}
    </div>
  )
}

export function AstTreeView({
  root,
  activity,
  onSelect,
}: {
  root: AstNode | null
  activity?: AstActivity
  onSelect?: (node: AstNode) => void
}) {
  const [overrides, setOverrides] = useState<Record<string, boolean>>({})
  // 换了一份解析结果（root 身份变）→ 手动折叠状态作废。
  useEffect(() => setOverrides({}), [root])

  if (root == null || (root.type === 'empty' && root.children.length === 0)) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-neutral-500">
        运行「解析」后在此浏览归一化树
      </div>
    )
  }

  const ctx: RowContext = {
    activePaths: activity?.activePaths ?? new Set(),
    expandPaths: activity?.expandPaths ?? new Set(),
    overrides,
    toggle: (key, next) => setOverrides((v) => ({ ...v, [key]: next })),
    onSelect,
  }

  return (
    <div className="h-full overflow-auto py-1 font-mono">
      <AstNodeRow node={root} path={[]} depth={0} ctx={ctx} />
    </div>
  )
}
