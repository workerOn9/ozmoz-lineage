import type { AstDiff, DiffNode, DiffStatus } from '../../lib/astDiff'
import { revealSpan } from '../../editor/editorHolder'
import { truncate, typeColor } from './AstTreeView'

/**
 * AST 并排 diff（web 设计稿 T4.1）：两棵带状态的对齐树左右同列渲染。
 * 单一滚动容器——滚动天然同步（T4.1 验收）；删除/新增后两侧行数不同，
 * 行不强制逐行对位。节点行不可折叠：保持两侧行序稳定（diff 视图的诉求
 * 是扫读差异，不是交互浏览；要折叠回三栏视图）。
 */

const STATUS_ICON: Record<DiffStatus, string> = {
  same: '·',
  changed: '~',
  removed: '−',
  added: '+',
}

const STATUS_ROW_CLS: Record<DiffStatus, string> = {
  same: '',
  changed: 'bg-amber-950/40',
  removed: 'bg-rose-950/40',
  added: 'bg-emerald-950/40',
}

function DiffRow({ dn, depth }: { dn: DiffNode; depth: number }) {
  const { node, status, children } = dn
  return (
    <>
      <div
        className={`flex cursor-pointer items-center gap-1 px-1 py-0.5 text-[13px] leading-5 ${STATUS_ROW_CLS[status]}`}
        style={{ paddingLeft: `${depth * 14 + 4}px` }}
        title={node.span != null ? `${node.span.start.line}:${node.span.start.column}` : node.text}
        onClick={() => {
          if (node.span != null) revealSpan(node.span)
        }}
      >
        <span className="w-4 shrink-0 text-center text-neutral-500 select-none">
          {STATUS_ICON[status]}
        </span>
        <span className={`shrink-0 font-mono text-[12px] ${typeColor(node.type)}`}>{node.type}</span>
        <span className="truncate text-neutral-500">{truncate(node.text)}</span>
      </div>
      {children.map((child, i) => (
        <DiffRow key={i} dn={child} depth={depth + 1} />
      ))}
    </>
  )
}

export function AstDiffView({
  diff,
  leftTitle,
  rightTitle,
}: {
  diff: AstDiff
  leftTitle: string
  rightTitle: string
}) {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex border-b border-neutral-800 text-[12px]">
        <div className="w-1/2 border-r border-neutral-800 px-3 py-1 font-mono text-neutral-300">
          {leftTitle}
        </div>
        <div className="w-1/2 px-3 py-1 font-mono text-neutral-300">{rightTitle}</div>
      </div>
      <div className="min-h-0 flex-1 overflow-auto">
        <div className="flex">
          <div className="w-1/2 min-w-0 border-r border-neutral-800 py-1 font-mono">
            <DiffRow dn={diff.left} depth={0} />
          </div>
          <div className="w-1/2 min-w-0 py-1 font-mono">
            <DiffRow dn={diff.right} depth={0} />
          </div>
        </div>
      </div>
    </div>
  )
}
