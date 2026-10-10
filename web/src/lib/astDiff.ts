import type { AstNode } from '../types'

// ─────────────────────────────────────────────────────────────────────────────
// AST 并排 diff（web 设计稿 T4.1）：输入两棵归一化树，输出两侧带状态的对齐树
// + 汇总计数。刻意简单——子节点按 type 做 LCS 对齐（保序、确定性）；对齐上的
// 同 type 节点比 text（不同记 changed，children 继续递归）；对不上的左记
// removed / 右记 added（整棵子树不再展开比对）；type 不同的对（实践中只有
// 根，如 jsqlparser `select` vs calcite `order_by`）是替换对：整树 removed+added。
// ─────────────────────────────────────────────────────────────────────────────

export type DiffStatus = 'same' | 'added' | 'removed' | 'changed'

export interface DiffNode {
  node: AstNode
  status: DiffStatus
  children: DiffNode[]
}

export interface DiffSummary {
  added: number
  removed: number
  changed: number
}

export interface AstDiff {
  left: DiffNode
  right: DiffNode
  summary: DiffSummary
}

export function diffAstTrees(left: AstNode, right: AstNode): AstDiff {
  const pair = diffNode(left, right)
  return {
    left: pair.left,
    right: pair.right,
    summary: {
      added: countStatus(pair.right, 'added'),
      removed: countStatus(pair.left, 'removed'),
      changed: countStatus(pair.left, 'changed'),
    },
  }
}

/** 单节点对：type 不同 = 替换对（整棵子树 removed / added，不再展开）。 */
function diffNode(left: AstNode, right: AstNode): { left: DiffNode; right: DiffNode } {
  if (left.type !== right.type) {
    return { left: removedTree(left), right: addedTree(right) }
  }
  const status: DiffStatus = left.text === right.text ? 'same' : 'changed'
  const aligned = alignChildren(left.children, right.children)
  return {
    left: { node: left, status, children: aligned.left },
    right: { node: right, status, children: aligned.right },
  }
}

function removedTree(node: AstNode): DiffNode {
  return { node, status: 'removed', children: node.children.map(removedTree) }
}

function addedTree(node: AstNode): DiffNode {
  return { node, status: 'added', children: node.children.map(addedTree) }
}

/**
 * 子节点对齐：key = type 的 LCS。对齐上的逐对递归（同 type），剩余的左侧记
 * removed、右侧记 added。两侧返回的列表长度可以不同（removed 只在左列出现，
 * added 只在右列出现）。
 */
function alignChildren(
  left: AstNode[],
  right: AstNode[],
): { left: DiffNode[]; right: DiffNode[] } {
  const matches = lcsMatches(left.map((c) => c.type), right.map((c) => c.type))
  const leftOut: DiffNode[] = []
  const rightOut: DiffNode[] = []
  let li = 0
  let ri = 0
  for (const m of matches) {
    while (li < m.left) leftOut.push(removedTree(left[li++]))
    while (ri < m.right) rightOut.push(addedTree(right[ri++]))
    const pair = diffNode(left[m.left], right[m.right])
    leftOut.push(pair.left)
    rightOut.push(pair.right)
    li = m.left + 1
    ri = m.right + 1
  }
  while (li < left.length) leftOut.push(removedTree(left[li++]))
  while (ri < right.length) rightOut.push(addedTree(right[ri++]))
  return { left: leftOut, right: rightOut }
}

/** LCS 回溯：返回保序的下标对。经典 DP，O(n·m)——AST 单侧子节点数是小的。 */
function lcsMatches(a: string[], b: string[]): Array<{ left: number; right: number }> {
  const n = a.length
  const m = b.length
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out: Array<{ left: number; right: number }> = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ left: i, right: j })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      i++
    } else {
      j++
    }
  }
  return out
}

/** changed 只数左树（配对两侧各有一棵，数一遍即可）。 */
function countStatus(root: DiffNode, status: DiffStatus): number {
  let total = root.status === status ? 1 : 0
  for (const c of root.children) total += countStatus(c, status)
  return total
}
