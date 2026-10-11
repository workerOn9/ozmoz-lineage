import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { formatReportSchema } from '../src/types'

// SQL 排版动作（T6.4 前端接线）：store.format 打 /api/format 并按形状回写 / 提示。
// 端点用真实夹具 mock（与 contract.test.ts 同源），不发明第二个形状。

const { formatMock } = vi.hoisted(() => ({ formatMock: vi.fn() }))

vi.mock('../src/api/endpoints', () => ({
  endpoints: {
    health: vi.fn(),
    engines: vi.fn(),
    parse: vi.fn(),
    lineage: vi.fn(),
    format: formatMock,
  },
}))

// 在 mock 之后再 import store，保证它拿到的是被替换的 endpoints。
const { useAppStore } = await import('../src/state/store')

const here = dirname(fileURLToPath(import.meta.url))

/** 夹具是真后端输出：先过 zod，保证测试输入与契约一致。 */
function fixture(name: string) {
  return formatReportSchema.parse(JSON.parse(readFileSync(join(here, 'fixtures', name), 'utf-8')))
}

describe('store.format（/api/format 接线）', () => {
  beforeEach(() => {
    formatMock.mockReset()
    useAppStore.setState({
      sql: 'select a,b from t where x=1',
      backend: 'ok',
      formatting: false,
      formatNotice: null,
    })
  })

  it('无注释：缺省引擎请求、回写排版文本、不提示', async () => {
    formatMock.mockResolvedValue(fixture('format-report.json'))
    await useAppStore.getState().format()

    // 刻意不传 engine：排版由后端取默认 jsqlparser（唯一申报 PRETTY_PRINT 的引擎）。
    expect(formatMock).toHaveBeenCalledWith({ sql: 'select a,b from t where x=1' })
    const state = useAppStore.getState()
    expect(state.sql).toBe(fixture('format-report.json').sql)
    expect(state.sql).toContain('\n')
    expect(state.formatNotice).toBeNull()
    expect(state.formatting).toBe(false)
  })

  it('有损（注释丢失）：文本仍回写，但落人读 WARNING 提示', async () => {
    formatMock.mockResolvedValue(fixture('format-report-comment.json'))
    await useAppStore.getState().format()

    const state = useAppStore.getState()
    expect(state.sql).toBe(fixture('format-report-comment.json').sql)
    expect(state.formatNotice).toContain('有损')
    expect(state.formatNotice).toContain('注释')
    expect(state.formatting).toBe(false)
  })

  it('没跑成（形状恒定）：sql 不回写、提示未跑成', async () => {
    const before = useAppStore.getState().sql
    formatMock.mockResolvedValue(fixture('format-report-skipped.json'))
    await useAppStore.getState().format()

    const state = useAppStore.getState()
    expect(state.sql).toBe(before) // 输入不构成一次排版：原文本保留
    expect(state.formatNotice).toContain('未跑成')
    expect(state.formatting).toBe(false)
  })

  it('后端起在别的引擎上（400 unsupported_capability）：提示失败、不抛', async () => {
    const { ApiError } = await import('../src/api/client')
    formatMock.mockRejectedValue(
      new ApiError(400, { error: 'unsupported_capability', reason: '引擎 calcite 不支持排版' }),
    )
    await expect(useAppStore.getState().format()).resolves.toBeUndefined()
    const state = useAppStore.getState()
    expect(state.formatNotice).toContain('排版失败')
    expect(state.formatting).toBe(false)
  })

  it('后端不可达 / 空输入：短路，不打端点', async () => {
    useAppStore.setState({ backend: 'down' })
    await useAppStore.getState().format()
    expect(formatMock).not.toHaveBeenCalled()

    useAppStore.setState({ backend: 'ok', sql: '   ' })
    await useAppStore.getState().format()
    expect(formatMock).not.toHaveBeenCalled()
  })

  it('改 SQL 会清掉上一轮的排版提示（避免陈旧提示滞留）', () => {
    useAppStore.setState({ formatNotice: '已排版（有损）：……' })
    useAppStore.getState().setSql('select 1')
    expect(useAppStore.getState().formatNotice).toBeNull()
  })
})
