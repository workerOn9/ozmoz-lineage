import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { lineageModelSchema, parseReportSchema } from '../src/types'

// store.run 的 schema 接线：schema 面板的 DDL 必须喂给 /api/lineage
// （星号展开 / 裸列消歧的元数据来源），但**不能**喂给 /api/parse（ParseQuery
// 无 schema 字段，服务端 ignoreUnknownKeys=false → 400）。
// 触发：2026-10-14 真实报表 SQL 试跑，最终列追不到根——根因就是这里没传 schema。

const { parseMock, lineageMock } = vi.hoisted(() => ({
  parseMock: vi.fn(),
  lineageMock: vi.fn(),
}))

vi.mock('../src/api/endpoints', () => ({
  endpoints: { parse: parseMock, lineage: lineageMock },
}))

const { useAppStore } = await import('../src/state/store')

const here = dirname(fileURLToPath(import.meta.url))

function parseFixture() {
  return parseReportSchema.parse(
    JSON.parse(readFileSync(join(here, 'fixtures', 'parse-report.json'), 'utf-8')),
  )
}
function lineageFixture() {
  return lineageModelSchema.parse(
    JSON.parse(readFileSync(join(here, 'fixtures', 'lineage-model.json'), 'utf-8')),
  )
}

describe('store.run（schema 喂给 /api/lineage）', () => {
  beforeEach(() => {
    parseMock.mockReset().mockResolvedValue(parseFixture())
    lineageMock.mockReset().mockResolvedValue(lineageFixture())
    useAppStore.setState({
      sql: 'SELECT o.price FROM orders o',
      schema: 'CREATE TABLE orders (price DECIMAL(10,2))',
      engineId: null,
      dialect: '',
      aggregate: false,
      backend: 'ok',
      running: false,
      runError: null,
    })
  })

  it('非空 schema：lineage 带 schema，parse 不带', async () => {
    await useAppStore.getState().run()

    expect(lineageMock).toHaveBeenCalledWith({
      sql: 'SELECT o.price FROM orders o',
      schema: 'CREATE TABLE orders (price DECIMAL(10,2))',
    })
    expect(parseMock).toHaveBeenCalledWith({ sql: 'SELECT o.price FROM orders o' })
  })

  it('空白 schema 视同未提供：lineage 不带 schema 字段', async () => {
    useAppStore.setState({ schema: '   ' })
    await useAppStore.getState().run()

    expect(lineageMock).toHaveBeenCalledWith({ sql: 'SELECT o.price FROM orders o' })
  })

  it('聚合模式：lineage 带 schema + aggregate，parse 不打', async () => {
    useAppStore.setState({ aggregate: true })
    await useAppStore.getState().run()

    expect(lineageMock).toHaveBeenCalledWith({
      sql: 'SELECT o.price FROM orders o',
      aggregate: true,
      schema: 'CREATE TABLE orders (price DECIMAL(10,2))',
    })
    expect(parseMock).not.toHaveBeenCalled()
  })
})
