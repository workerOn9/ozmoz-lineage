import type { AstNode, Span } from '../types'
import { spansOverlap } from './span'

/** 节点本身是否命中查询：`type` 或折叠空白后的 `text` 包含关键词（大小写不敏感）。 */
export function matchesNode(node: AstNode, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (needle === '') return false
  if (node.type.toLowerCase().includes(needle)) return true
  return node.text.replace(/\s+/g, ' ').toLowerCase().includes(needle)
}

export interface AstActivity {
  /** 「活动」节点路径（`0.2.1` 形式，根为 `''`）：搜索命中 或 与 [focusSpan] 重叠。 */
  activePaths: Set<string>
  /** 活动节点的祖先路径——自动展开它们，让命中 / 焦点可见。 */
  expandPaths: Set<string>
  /** 活动节点数量（搜索时即「N 处匹配」）。 */
  count: number
}

const EMPTY: AstActivity = { activePaths: new Set(), expandPaths: new Set(), count: 0 }

/**
 * 单趟遍历归一化树，标出「活动」节点与需要自动展开的祖先。
 *
 * 两个来源共用一个机制：搜索命中（`search` 非空）优先，否则与 `focusSpan` 重叠的节点
 * 视为活动（来自血缘图点选的联动）。两者都没有 → 空结果（树按默认折叠状态渲染）。
 */
export function collectActivity(
  root: AstNode | null,
  options: { search?: string; focusSpan?: Span | null },
): AstActivity {
  const search = options.search?.trim() ?? ''
  const focusSpan = options.focusSpan ?? null
  if (root == null || (search === '' && focusSpan == null)) return EMPTY

  const isActive = (node: AstNode): boolean =>
    search !== '' ? matchesNode(node, search) : spansOverlap(node.span, focusSpan)

  const activePaths = new Set<string>()
  const expandPaths = new Set<string>()
  const walk = (node: AstNode, path: number[]): boolean => {
    const key = path.join('.')
    const active = isActive(node)
    let descendantActive = false
    node.children.forEach((child, i) => {
      if (walk(child, [...path, i])) descendantActive = true
    })
    if (active) activePaths.add(key)
    if (descendantActive) expandPaths.add(key)
    return active || descendantActive
  }
  walk(root, [])

  return { activePaths, expandPaths, count: activePaths.size }
}
