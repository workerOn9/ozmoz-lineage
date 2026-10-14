import { describe, expect, it } from 'vitest'
import { EXAMPLES, getExample } from '../src/lib/examples'

describe('演示示例库', () => {
  it('id 唯一且 SQL / 标题 / 简介非空', () => {
    const ids = EXAMPLES.map((e) => e.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const ex of EXAMPLES) {
      expect(ex.sql.trim()).not.toBe('')
      expect(ex.title.trim()).not.toBe('')
      expect(ex.blurb.trim()).not.toBe('')
    }
  })

  it('getExample 只认库内 id（深链入口守卫）', () => {
    expect(getExample('sample')?.id).toBe('sample')
    expect(getExample('nope')).toBeNull()
    expect(getExample(null)).toBeNull()
  })

  it('aggregate 标记只出现在多语句示例上', () => {
    for (const ex of EXAMPLES) {
      const statements = ex.sql.split(';').filter((s) => s.trim() !== '')
      if (ex.aggregate) expect(statements.length).toBeGreaterThan(1)
    }
  })

  it('单语句示例不带 aggregate（否则白白跳过归一化树）', () => {
    for (const ex of EXAMPLES) {
      const statements = ex.sql.split(';').filter((s) => s.trim() !== '')
      if (statements.length === 1) expect(ex.aggregate ?? false).toBe(false)
    }
  })
})
