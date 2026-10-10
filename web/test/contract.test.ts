import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import {
  astNodeSchema,
  edgeKindSchema,
  engineReportSchema,
  lineageModelSchema,
  parseReportSchema,
  scopeKindSchema,
  severitySchema,
  transformKindSchema,
} from '../src/types'

// 契约夹具校验（web 设计稿 §5.2）：类型漂移的第一道闸。

const here = dirname(fileURLToPath(import.meta.url))

function fixture(name: string): unknown {
  return JSON.parse(readFileSync(join(here, 'fixtures', name), 'utf-8'))
}

describe('契约夹具（真实后端输出）', () => {
  it('engines.json 符合 EngineReport[]', () => {
    const raw = fixture('engines.json')
    expect(Array.isArray(raw)).toBe(true)
    for (const e of raw as unknown[]) {
      engineReportSchema.parse(e)
    }
    const ids = (raw as Array<{ id: string }>).map((e) => e.id)
    // server 注册面 = CLI LineagePipeline.allEngines()（jsqlparser / calcite / jooq，
    // 2026-10-10 AST 对比 T4.2 起三引擎全注册）。
    expect(ids).toEqual(['jsqlparser', 'calcite', 'jooq'])
  })

  it('parse-report.json 符合 ParseReport', () => {
    const report = parseReportSchema.parse(fixture('parse-report.json'))
    // 归一化树必须自洽（zod 递归已保证结构；这里锁语义）。
    expect(report.root.type).toBe('select')
    expect(report.root.text).not.toBe('')
    expect(report.root.span).toBeDefined()
    expect(report.diagnostics).toHaveLength(0)
  })

  it('parse-report-error.json：整段语法错误是数据不是 HTTP 错误', () => {
    const report = parseReportSchema.parse(fixture('parse-report-error.json'))
    expect(report.root.type).toBe('empty')
    expect(report.diagnostics).toHaveLength(1)
    expect(report.diagnostics[0].severity).toBe('ERROR')
  })

  it('parse-report-calcite.json / parse-report-jooq.json：跨引擎 AST 对比夹具符合 ParseReport', () => {
    for (const name of ['parse-report-calcite.json', 'parse-report-jooq.json']) {
      const report = parseReportSchema.parse(fixture(name))
      expect(report.root.type).not.toBe('')
      expect(report.diagnostics).toHaveLength(0)
    }
    // 同一份示例 SQL 的 jsqlparser 夹具早已入库（parse-report.json），
    // 三份夹具并排放置供 astdiff.test.ts 做真实跨引擎 diff。
    const calcite = parseReportSchema.parse(fixture('parse-report-calcite.json'))
    expect(calcite.root.type).toBe('order_by') // calcite 把 ORDER BY 包在根上——与 jsqlparser 不同形
  })

  it('lineage-model.json 符合 LineageModel', () => {
    const model = lineageModelSchema.parse(fixture('lineage-model.json'))
    expect(model.scopes.length).toBeGreaterThan(0)
    expect(model.edges.length).toBeGreaterThan(0)
    expect(model.unknowns.length).toBeGreaterThan(0) // 该输入含无限定聚合列
  })

  it('lineage-model-unknowns.json：unknown 的形状是 {reason, span?}（无多态判别字段）', () => {
    const model = lineageModelSchema.parse(fixture('lineage-model-unknowns.json'))
    expect(model.unknowns.length).toBeGreaterThan(0)
    for (const u of model.unknowns) {
      expect(Object.keys(u).sort()).toEqual(['reason', 'span'].sort())
      expect(u.reason).not.toBe('')
    }
  })

  it('夹具里出现的枚举值都在 TS 镜像集合内', () => {
    const kinds = new Set<string>()
    const transforms = new Set<string>()
    const scopes = new Set<string>()
    const severities = new Set<string>()
    for (const name of ['lineage-model.json', 'lineage-model-unknowns.json']) {
      const model = lineageModelSchema.parse(fixture(name))
      model.edges.forEach((e) => {
        kinds.add(e.kind)
        transforms.add(e.transform)
      })
      model.scopes.forEach((s) => scopes.add(s.kind))
      model.diagnostics.forEach((d) => severities.add(d.severity))
    }
    const report = parseReportSchema.parse(fixture('parse-report.json'))
    report.diagnostics.forEach((d) => severities.add(d.severity))

    for (const k of kinds) edgeKindSchema.parse(k)
    for (const t of transforms) transformKindSchema.parse(t)
    for (const s of scopes) scopeKindSchema.parse(s)
    for (const sev of severities) severitySchema.parse(sev)
  })

  it('astNodeSchema 递归自洽且宽容缺省 children', () => {
    const node = astNodeSchema.parse({ type: 'select', text: 'SELECT 1' })
    expect(node.children).toEqual([])
  })
})
