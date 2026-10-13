import { describe, expect, it } from 'vitest'
import { normalize } from './normalize.mjs'

// 数据规整单测：站点表格的**唯一数据入口**——口径错了页面就错，所以在这里钉死。

const engines = [
  { id: 'jsqlparser', features: ['PARSE', 'SEMANTIC_MODEL'], dialects: ['ansi'], reasons: { DIALECT_RENDER: '不做方言转换' } },
  { id: 'calcite', features: ['PARSE', 'DIALECT_RENDER'], dialects: ['mysql'], reasons: { SEMANTIC_MODEL: '未实现' } },
]

const matrix = {
  meta: { generatedAt: '2026-10-13T00:00:00Z', commit: 'abc123', caseCount: 2, engineCount: 2 },
  coverage: {
    ansi: { jsqlparser: { ok: 1, total: 2 }, calcite: { ok: 0, total: 2 } },
  },
  cells: [
    { engine: 'jsqlparser', caseId: 'c1', dialect: 'ansi', kind: 'SELECT', features: ['cte'], ok: true },
    { engine: 'jsqlparser', caseId: 'c2', dialect: 'ansi', kind: 'DDL', features: ['ddl'], ok: false, reason: '解析失败: x' },
    { engine: 'calcite', caseId: 'c1', dialect: 'ansi', kind: 'SELECT', features: ['cte'], ok: false, reason: 'calcite.parse_error: y' },
    { engine: 'calcite', caseId: 'c2', dialect: 'ansi', kind: 'DDL', features: ['ddl'], ok: false, reason: 'calcite.parse_error: z' },
  ],
}

const convert = {
  meta: { generatedAt: '2026-10-13T00:00:00Z', commit: 'abc123', caseCount: 2, engineCount: 2, pairCount: 3 },
  coverage: [],
  cells: [
    { engine: 'calcite', caseId: 'c1', fromDialect: 'ansi', toDialect: 'mysql', ok: true, sql: 'SELECT 1' },
    { engine: 'calcite', caseId: 'c2', fromDialect: 'ansi', toDialect: 'mysql', ok: false, reason: 'calcite.render_failed: x' },
    { engine: 'jooq', caseId: 'c1', fromDialect: 'ansi', toDialect: 'mysql', ok: true, sql: 'SELECT 2' },
  ],
}

describe('normalize', () => {
  it('缺数据源时降级为 available:false，不抛错', () => {
    const d = normalize({ engines: null, matrix: null, convert: null })
    expect(d.engines.available).toBe(false)
    expect(d.matrix.available).toBe(false)
    expect(d.convert.available).toBe(false)
    expect(d.engines.items).toEqual([])
  })

  it('存在但结构不符时抛错（不静默渲染空表）', () => {
    expect(() => normalize({ engines: {}, matrix: null, convert: null })).toThrow(/engines\.json 结构不符/)
    expect(() => normalize({ engines: null, matrix: { cells: [] }, convert: null })).toThrow(/缺 meta/)
    expect(() => normalize({ engines: null, matrix: { meta: {}, cells: 'x' }, convert: null })).toThrow(/缺 cells/)
  })

  it('engines：按 Feature 名排序不支持原因，顺序稳定', () => {
    const d = normalize({ engines: [{ id: 'e', features: [], dialects: [], reasons: { ZZZ: 'z', AAA: 'a' } }], matrix: null, convert: null })
    expect(Object.keys(d.engines.items[0].reasons)).toEqual(['AAA', 'ZZZ'])
  })

  it('matrix：覆盖表按引擎聚合，失败格带原因，commit 透传', () => {
    const d = normalize({ engines: null, matrix, convert: null })
    expect(d.matrix.available).toBe(true)
    expect(d.matrix.commit).toBe('abc123')
    expect(d.matrix.engines).toEqual(['calcite', 'jsqlparser'])
    expect(d.matrix.coverage).toEqual([
      {
        dialect: 'ansi',
        ok: 1,
        total: 4,
        engines: [
          { engine: 'calcite', ok: 0, total: 2 },
          { engine: 'jsqlparser', ok: 1, total: 2 },
        ],
      },
    ])
    expect(d.matrix.failures).toHaveLength(3)
    expect(d.matrix.failures[0]).toMatchObject({ dialect: 'ansi', engine: 'jsqlparser', caseId: 'c2', reason: '解析失败: x' })
    // 全量格：ok 格不带 reason，失败格带（页面逐格浏览用）。
    expect(d.matrix.cells).toHaveLength(4)
    expect(d.matrix.cells[0]).toEqual({ dialect: 'ansi', engine: 'jsqlparser', caseId: 'c1', kind: 'SELECT', features: ['cte'], ok: true })
    expect(d.matrix.cells[1].reason).toBe('解析失败: x')
    expect(d.matrix.rawUrl).toBe('/data/compat-matrix.json')
  })

  it('convert：按方言对聚合、每对每引擎留一条成品样本、失败格带原因', () => {
    const d = normalize({ engines: null, matrix: null, convert })
    expect(d.convert.available).toBe(true)
    expect(d.convert.declaredPairCount).toBe(3)
    expect(d.convert.pairs).toHaveLength(1)
    const pair = d.convert.pairs[0]
    expect(pair).toMatchObject({ from: 'ansi', to: 'mysql', ok: 2, total: 3 })
    expect(pair.engines).toEqual([
      { engine: 'calcite', ok: 1, total: 2 },
      { engine: 'jooq', ok: 1, total: 1 },
    ])
    expect(pair.samples).toEqual([
      { engine: 'calcite', caseId: 'c1', sql: 'SELECT 1' },
      { engine: 'jooq', caseId: 'c1', sql: 'SELECT 2' },
    ])
    expect(d.convert.failures).toEqual([
      { from: 'ansi', to: 'mysql', engine: 'calcite', caseId: 'c2', reason: 'calcite.render_failed: x' },
    ])
  })
})
