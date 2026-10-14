import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import type { AstNode, Span } from '../src/types'
import { lineageModelSchema } from '../src/types'
import { spansOverlap } from '../src/lib/span'
import { collectActivity, matchesNode } from '../src/lib/astSearch'
import { mapLineageModel } from '../src/graph/mapping'

const here = dirname(fileURLToPath(import.meta.url))

function span(start: number, end: number): Span {
  return { start: { offset: start, line: 1, column: start + 1 }, end: { offset: end, line: 1, column: end + 1 } }
}

function node(type: string, text: string, children: AstNode[] = [], s?: Span): AstNode {
  return { type, text, children, ...(s != null ? { span: s } : {}) }
}

describe('spansOverlap', () => {
  it('半开区间重叠判定', () => {
    expect(spansOverlap(span(0, 10), span(5, 15))).toBe(true) // 部分重叠
    expect(spansOverlap(span(0, 10), span(10, 20))).toBe(false) // 相邻不算（半开）
    expect(spansOverlap(span(0, 10), span(2, 4))).toBe(true) // 包含
    expect(spansOverlap(span(20, 30), span(0, 10))).toBe(false) // 分离
  })

  it('任一为空 → false（位置未知不判断）', () => {
    expect(spansOverlap(span(0, 10), undefined)).toBe(false)
    expect(spansOverlap(null, span(0, 10))).toBe(false)
  })
})

describe('matchesNode', () => {
  const n = node('func_call', 'SUM(oi.price)')
  it('type 与文本都能命中，大小写不敏感，空白折叠', () => {
    expect(matchesNode(n, 'func')).toBe(true)
    expect(matchesNode(n, 'sum')).toBe(true)
    expect(matchesNode(n, 'oi.price')).toBe(true)
  })
  it('空查询 / 不命中 → false', () => {
    expect(matchesNode(n, '   ')).toBe(false)
    expect(matchesNode(n, 'zzz')).toBe(false)
  })
})

describe('collectActivity（树↔图按 span 联动 / 搜索）', () => {
  // select( alpha[7..12], beta[14..18]( gamma[16..21] ) )
  const tree = node('select', 'SELECT alpha, beta', [
    node('column_ref', 'alpha', [], span(7, 12)),
    node('column_ref', 'beta', [node('func_call', 'gamma', [], span(16, 21))], span(14, 18)),
  ])

  it('无搜索无焦点 → 空结果', () => {
    const a = collectActivity(tree, {})
    expect(a.count).toBe(0)
    expect(a.activePaths.size).toBe(0)
    expect(a.expandPaths.size).toBe(0)
  })

  it('搜索命中深层节点：只有命中路径活动，祖先进自动展开集', () => {
    const a = collectActivity(tree, { search: 'gamma' })
    expect(a.activePaths.has('1.0')).toBe(true)
    expect(a.count).toBe(1)
    expect(a.expandPaths.has('')).toBe(true) // 根是命中节点的祖先
    expect(a.expandPaths.has('1')).toBe(true)
    expect(a.expandPaths.has('1.0')).toBe(false) // 命中节点自己不需要展开
  })

  it('焦点 span 重叠：命中与焦点重叠的节点，其祖先进自动展开集', () => {
    const a = collectActivity(tree, { focusSpan: span(16, 17) })
    expect(a.activePaths.has('1.0')).toBe(true)
    expect(a.expandPaths.has('1')).toBe(true)
  })

  it('搜索优先于焦点', () => {
    const a = collectActivity(tree, { search: 'alpha', focusSpan: span(16, 17) })
    expect(a.activePaths.has('0')).toBe(true)
    expect(a.activePaths.has('1.0')).toBe(false) // 焦点被搜索盖过
  })
})

describe('mapping：边带上源文本 span（树↔图联动的基础）', () => {
  it('LineageModel 的边 span 透传到 GraphEdgeData', () => {
    const model = lineageModelSchema.parse(
      JSON.parse(readFileSync(join(here, 'fixtures', 'lineage-model.json'), 'utf-8')),
    )
    const mapped = mapLineageModel(model)
    const withSpan = mapped.edges.filter((e) => e.data.span != null)
    expect(withSpan.length).toBeGreaterThan(0)
    expect(withSpan.length).toBe(model.edges.filter((e) => e.span != null).length)
  })
})
