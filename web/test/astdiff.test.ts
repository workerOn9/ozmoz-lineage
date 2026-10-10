import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import type { AstNode } from '../src/types'
import { parseReportSchema } from '../src/types'
import { diffAstTrees } from '../src/lib/astDiff'

// AST 并排 diff（web 设计稿 T4.1）的纯函数测试 + 真实跨引擎夹具端到端。

const here = dirname(fileURLToPath(import.meta.url))

function fixture(name: string): unknown {
  return JSON.parse(readFileSync(join(here, 'fixtures', name), 'utf-8'))
}

const n = (type: string, text = type, children: AstNode[] = []): AstNode => ({
  type,
  text,
  children,
})

describe('diffAstTrees', () => {
  it('相同树：全部 same，计数为 0', () => {
    const t = n('select', 'SELECT a FROM s', [n('column', 'a'), n('table', 's')])
    const d = diffAstTrees(t, t)
    expect(d.summary).toEqual({ added: 0, removed: 0, changed: 0 })
    expect(d.left.status).toBe('same')
    expect(d.left.children.map((c) => c.status)).toEqual(['same', 'same'])
    expect(d.right.children.map((c) => c.status)).toEqual(['same', 'same'])
  })

  it('同 type 不同 text：记 changed 且 children 继续比对', () => {
    const l = n('select', 'SELECT a FROM s', [n('column', 'a')])
    const r = n('select', 'select a from s', [n('column', 'a')])
    const d = diffAstTrees(l, r)
    expect(d.summary).toEqual({ added: 0, removed: 0, changed: 1 })
    expect(d.left.status).toBe('changed')
    expect(d.right.status).toBe('changed')
    expect(d.left.children[0].status).toBe('same')
  })

  it('新增子节点：只在右列出现 added，左列不含它', () => {
    const l = n('select', 'q', [n('a')])
    const r = n('select', 'q', [n('a'), n('b')])
    const d = diffAstTrees(l, r)
    expect(d.summary).toEqual({ added: 1, removed: 0, changed: 0 })
    expect(d.left.children.map((c) => c.node.type)).toEqual(['a'])
    expect(d.right.children.map((c) => `${c.node.type}:${c.status}`)).toEqual(['a:same', 'b:added'])
  })

  it('删除子节点：只在左列出现 removed', () => {
    const l = n('select', 'q', [n('a'), n('b')])
    const r = n('select', 'q', [n('a')])
    const d = diffAstTrees(l, r)
    expect(d.summary).toEqual({ added: 0, removed: 1, changed: 0 })
    expect(d.left.children.map((c) => `${c.node.type}:${c.status}`)).toEqual(['a:same', 'b:removed'])
    expect(d.right.children.map((c) => c.node.type)).toEqual(['a'])
  })

  it('type 不同 = 替换对：整棵子树 removed / added，不再展开', () => {
    const l = n('select', 'SELECT 1', [n('a'), n('b')])
    const r = n('order_by', 'SELECT 1', [n('a'), n('b')])
    const d = diffAstTrees(l, r)
    // 左 3 节点全 removed（根 + 2 子），右 3 节点全 added。
    expect(d.summary).toEqual({ added: 3, removed: 3, changed: 0 })
    expect(d.left.status).toBe('removed')
    expect(d.right.status).toBe('added')
  })

  it('子节点按 type 的 LCS 保序对齐（不是按下标硬对）', () => {
    // 左 [a, b]、右 [b, a]：LCS 长 1——一对 same，一删一增。
    const l = n('select', 'q', [n('a', 'a1'), n('b', 'b1')])
    const r = n('select', 'q', [n('b', 'b1'), n('a', 'a1')])
    const d = diffAstTrees(l, r)
    expect(d.summary).toEqual({ added: 1, removed: 1, changed: 0 })
  })

  it('中间插入：前后 same 对齐，中间新增的成 added（LCS 保序）', () => {
    const l = n('select', 'q', [n('a'), n('c')])
    const r = n('select', 'q', [n('a'), n('b'), n('c')])
    const d = diffAstTrees(l, r)
    expect(d.summary).toEqual({ added: 1, removed: 0, changed: 0 })
    expect(d.right.children.map((c) => `${c.node.type}:${c.status}`)).toEqual([
      'a:same',
      'b:added',
      'c:same',
    ])
  })
})

describe('diffAstTrees（真实跨引擎夹具，同一份示例 SQL）', () => {
  const jsqlparser = parseReportSchema.parse(fixture('parse-report.json'))

  it('jsqlparser vs calcite：根 type 不同（select / order_by）→ 两侧全量替换', () => {
    const calcite = parseReportSchema.parse(fixture('parse-report-calcite.json'))
    const d = diffAstTrees(jsqlparser.root, calcite.root)
    expect(d.left.status).toBe('removed')
    expect(d.right.status).toBe('added')
    expect(d.summary.removed).toBeGreaterThan(10)
    expect(d.summary.added).toBeGreaterThan(10)
    expect(d.summary.changed).toBe(0)
  })

  it('jsqlparser vs jooq：两侧树都非空且可对齐出 same 以外的状态', () => {
    const jooq = parseReportSchema.parse(fixture('parse-report-jooq.json'))
    const d = diffAstTrees(jsqlparser.root, jooq.root)
    expect(d.summary.added + d.summary.removed + d.summary.changed).toBeGreaterThan(0)
    // 两侧返回的树各自完整（节点数与输入一致——removed/added 子树保留全部节点）。
    expect(d.summary.removed).toBeGreaterThan(0)
  })

  it('引擎自对比（同树）：全 same', () => {
    const d = diffAstTrees(jsqlparser.root, jsqlparser.root)
    expect(d.summary).toEqual({ added: 0, removed: 0, changed: 0 })
  })
})
