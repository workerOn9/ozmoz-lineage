import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { validateReportSchema } from '../src/types'

// 语义校验动作（T6.3 前端接线）：store.validate 打 /api/validate，
// 产物是诊断流（形状恒定），结果进「校验」页；请求失败与诊断流分开。
// 端点用真实夹具 mock（与 contract.test.ts 同源），不发明第二个形状。

const { validateMock } = vi.hoisted(() => ({ validateMock: vi.fn() }))

vi.mock('../src/api/endpoints', () => ({
  endpoints: {
    health: vi.fn(),
    engines: vi.fn(),
    parse: vi.fn(),
    lineage: vi.fn(),
    format: vi.fn(),
    validate: validateMock,
  },
}))

const { useAppStore } = await import('../src/state/store')

const here = dirname(fileURLToPath(import.meta.url))

/** 夹具是真后端输出：先过 zod，保证测试输入与契约一致。 */
function fixture(name: string) {
  return validateReportSchema.parse(JSON.parse(readFileSync(join(here, 'fixtures', name), 'utf-8')))
}

describe('store.validate（/api/validate 接线）', () => {
  beforeEach(() => {
    validateMock.mockReset()
    useAppStore.setState({
      sql: 'SELECT id FROM orders',
      schema: 'CREATE TABLE orders (id INT)',
      dialect: '',
      backend: 'ok',
      validating: false,
      validateReport: null,
      validateError: null,
    })
  })

  it('合法 SQL：缺省引擎请求、诊断为空、无错误', async () => {
    validateMock.mockResolvedValue(fixture('validate-report.json'))
    await useAppStore.getState().validate()

    // 刻意不传 engine：语义校验是 calcite 的能力（唯一申报 VALIDATE_SCHEMA 的引擎）。
    expect(validateMock).toHaveBeenCalledWith({
      sql: 'SELECT id FROM orders',
      schema: 'CREATE TABLE orders (id INT)',
    })
    const state = useAppStore.getState()
    expect(state.validateReport?.engine).toBe('calcite')
    expect(state.validateReport?.diagnostics).toHaveLength(0)
    expect(state.validateError).toBeNull()
    expect(state.validating).toBe(false)
  })

  it('未知列：诊断流带 calcite.validate_error（ERROR，带位置）', async () => {
    validateMock.mockResolvedValue(fixture('validate-report-error.json'))
    await useAppStore.getState().validate()

    const state = useAppStore.getState()
    expect(state.validateReport?.diagnostics[0].code).toBe('calcite.validate_error')
    expect(state.validateReport?.diagnostics[0].span).toBeDefined()
  })

  it('没跑成（形状恒定）：validate.skipped 仍进诊断流，不是请求失败', async () => {
    validateMock.mockResolvedValue(fixture('validate-report-skipped.json'))
    await useAppStore.getState().validate()

    const state = useAppStore.getState()
    expect(state.validateReport?.diagnostics[0].code).toBe('validate.skipped')
    expect(state.validateError).toBeNull() // 200 诊断流，不是 4xx
  })

  it('空白 schema 视同未提供：不发 schema 字段', async () => {
    validateMock.mockResolvedValue(fixture('validate-report-skipped.json'))
    useAppStore.setState({ schema: '   ' })
    await useAppStore.getState().validate()

    expect(validateMock).toHaveBeenCalledWith({ sql: 'SELECT id FROM orders' })
  })

  it('非空 dialect：随请求带上（决定解析器 quoting 配置）', async () => {
    validateMock.mockResolvedValue(fixture('validate-report.json'))
    useAppStore.setState({ dialect: 'postgresql' })
    await useAppStore.getState().validate()

    expect(validateMock).toHaveBeenCalledWith({
      sql: 'SELECT id FROM orders',
      schema: 'CREATE TABLE orders (id INT)',
      dialect: 'postgresql',
    })
  })

  it('400 unsupported_capability：落 validateError、不抛、诊断流为空', async () => {
    const { ApiError } = await import('../src/api/client')
    validateMock.mockRejectedValue(
      new ApiError(400, { error: 'unsupported_capability', reason: '引擎 jooq 不支持 schema 校验' }),
    )
    await expect(useAppStore.getState().validate()).resolves.toBeUndefined()
    const state = useAppStore.getState()
    expect(state.validateError).toContain('不支持')
    expect(state.validateReport).toBeNull()
    expect(state.validating).toBe(false)
  })

  it('后端不可达 / 空输入：短路，不打端点', async () => {
    useAppStore.setState({ backend: 'down' })
    await useAppStore.getState().validate()
    expect(validateMock).not.toHaveBeenCalled()

    useAppStore.setState({ backend: 'ok', sql: '   ' })
    await useAppStore.getState().validate()
    expect(validateMock).not.toHaveBeenCalled()
  })

  it('改 SQL / 改 schema 会清掉上一次校验产物（避免陈旧诊断滞留）', () => {
    useAppStore.setState({ validateReport: fixture('validate-report-error.json') })
    useAppStore.getState().setSql('SELECT 1')
    expect(useAppStore.getState().validateReport).toBeNull()

    useAppStore.setState({ validateReport: fixture('validate-report-error.json') })
    useAppStore.getState().setSchema('CREATE TABLE t (x INT)')
    expect(useAppStore.getState().validateReport).toBeNull()
  })
})
