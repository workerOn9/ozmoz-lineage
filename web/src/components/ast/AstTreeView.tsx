import { useState } from 'react'
import type { AstNode } from '../../types'
import { revealSpan } from '../../editor/editorHolder'

/** 归一化树（AstNode）递归渲染：可折叠、按 type 着色、点击定位到编辑器 span。 */

const TYPE_COLORS: Array<[RegExp, string]> = [
  [/^select|plain_select/, 'text-sky-400'],
  [/^table/, 'text-emerald-400'],
  [/^join/, 'text-teal-300'],
  [/^column/, 'text-amber-300'],
  [/^function|sum|count|avg|min|max/i, 'text-violet-300'],
  [/^where|having/, 'text-rose-300'],
  [/^group|^order/, 'text-fuchsia-300'],
]

function typeColor(type: string): string {
  return TYPE_COLORS.find(([re]) => re.test(type))?.[1] ?? 'text-neutral-300'
}

function truncate(text: string, max = 48): string {
  const single = text.replace(/\s+/g, ' ').trim()
  return single.length > max ? `${single.slice(0, max)}…` : single
}

function AstNodeRow({ node, depth }: { node: AstNode; depth: number }) {
  const [expanded, setExpanded] = useState(depth < 2)
  const hasChildren = node.children.length > 0

  return (
    <div>
      <div
        className="ast-row flex cursor-pointer items-center gap-1 rounded px-1 py-0.5 text-[13px] leading-5"
        style={{ paddingLeft: `${depth * 14 + 4}px` }}
        title={node.span != null ? `${node.span.start.line}:${node.span.start.column}` : node.text}
        onClick={() => {
          setExpanded((v) => !v)
          if (node.span != null) revealSpan(node.span)
        }}
      >
        <span className="w-4 shrink-0 text-center text-neutral-500 select-none">
          {hasChildren ? (expanded ? '▾' : '▸') : '·'}
        </span>
        <span className={`shrink-0 font-mono text-[12px] ${typeColor(node.type)}`}>{node.type}</span>
        <span className="truncate text-neutral-500">{truncate(node.text)}</span>
      </div>
      {expanded &&
        node.children.map((child, i) => <AstNodeRow key={i} node={child} depth={depth + 1} />)}
    </div>
  )
}

export function AstTreeView({ root }: { root: AstNode | null }) {
  if (root == null || (root.type === 'empty' && root.children.length === 0)) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-neutral-500">
        运行「解析」后在此浏览归一化树
      </div>
    )
  }
  return (
    <div className="h-full overflow-auto py-1 font-mono">
      <AstNodeRow node={root} depth={0} />
    </div>
  )
}
