#!/usr/bin/env node
// 文档站数据规整：把 `ozml` 生成的三份 JSON 规整成页面可直接渲染的数据集。
//
// 设计口径（与仓库「Never-wrong / 失败格是产物」一致）：
// - 原始 JSON **缺失** → 该数据源 `available: false`，页面降级为「先跑 just docs-data」占位；
// - 原始 JSON **存在但结构不符** → **抛错**（CI 里宁可红，也不静默渲染空表）；
// - 顺带把原始 JSON 拷进 `docs/public/data/`（Rspress 的静态目录），页面据此提供
//   「全量 JSON（可溯源）」链接。
//
// 产物：`.data/dataset.json`（唯一被 Vue 组件消费的文件）。表格全部由它渲染，无手写表格。

import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..')

/** 一个方言对里最多展示几条成品 SQL 样本（全量见 rawUrl）。 */
const SAMPLES_PER_PAIR = 1

export function normalize(inputs) {
  return {
    generatedAt: new Date().toISOString(),
    engines: normalizeEngines(inputs.engines),
    matrix: normalizeMatrix(inputs.matrix),
    convert: normalizeConvert(inputs.convert),
  }
}

function normalizeEngines(raw) {
  if (raw == null) return { available: false, items: [] }
  if (!Array.isArray(raw)) fail('engines.json', '顶层应为数组')
  const items = raw.map((e, i) => {
    if (!isObj(e) || typeof e.id !== 'string') fail('engines.json', `第 ${i} 项缺 id`)
    if (!Array.isArray(e.features) || !Array.isArray(e.dialects)) fail('engines.json', `${e.id} 的 features/dialects 应为数组`)
    if (!isObj(e.reasons)) fail('engines.json', `${e.id} 的 reasons 应为对象`)
    return {
      id: e.id,
      features: e.features,
      dialects: e.dialects,
      // 不支持原因按 Feature 名排序，保证渲染顺序稳定（JSON 对象键序不进入契约）。
      reasons: Object.fromEntries(Object.entries(e.reasons).sort(([a], [b]) => a.localeCompare(b))),
    }
  })
  return { available: true, items }
}

function normalizeMatrix(raw) {
  if (raw == null) return { available: false }
  const meta = requireMeta('compat-matrix.json', raw)
  const cells = requireCells('compat-matrix.json', raw)
  // 引擎列按名字排序：raw 的格序是 (方言, case, 引擎)，直接取首现顺序会随语料变化。
  const engines = uniqueInOrder(cells.map((c) => c.engine)).sort()
  const coverage = Object.entries(raw.coverage ?? {}).map(([dialect, perEngine]) => {
    const enginesCoverage = engines.map((engine) => {
      const c = perEngine?.[engine] ?? { ok: 0, total: 0 }
      return { engine, ok: num(c.ok), total: num(c.total) }
    })
    return {
      dialect,
      ok: enginesCoverage.reduce((s, e) => s + e.ok, 0),
      total: enginesCoverage.reduce((s, e) => s + e.total, 0),
      engines: enginesCoverage,
    }
  })
  const failures = cells
    .filter((c) => !c.ok)
    .map((c) => ({
      dialect: c.dialect,
      engine: c.engine,
      caseId: c.caseId,
      kind: c.kind,
      features: c.features ?? [],
      reason: c.reason ?? '(无原因)',
    }))
  // 全量格（ok 格不带 reason，保持紧凑）——页面可逐格浏览（M3 验收「每格可溯源」）。
  const allCells = cells.map((c) => ({
    dialect: c.dialect,
    engine: c.engine,
    caseId: c.caseId,
    kind: c.kind,
    features: c.features ?? [],
    ok: Boolean(c.ok),
    ...(c.ok ? {} : { reason: c.reason ?? '(无原因)' }),
  }))
  return {
    available: true,
    commit: meta.commit ?? null,
    generatedAt: meta.generatedAt ?? null,
    caseCount: num(meta.caseCount),
    engineCount: num(meta.engineCount),
    engines,
    coverage,
    failures,
    cells: allCells,
    // 绝对路径：VitePress 的 withBase 只给绝对路径加 base（相对路径原样返回）。
    rawUrl: '/data/compat-matrix.json',
  }
}

function normalizeConvert(raw) {
  if (raw == null) return { available: false }
  const meta = requireMeta('compat-convert.json', raw)
  const cells = requireCells('compat-convert.json', raw)
  const engines = uniqueInOrder(cells.map((c) => c.engine)).sort()

  // 方言对：按 cells 里实际出现过的 (from,to) 归组——声明对数见 meta.pairCount，
  // 两者可能不等（from 方言在语料里没有 case 的对不出格）。
  const pairs = new Map()
  for (const cell of cells) {
    const key = `${cell.fromDialect}\u0000${cell.toDialect}`
    if (!pairs.has(key)) {
      pairs.set(key, { from: cell.fromDialect, to: cell.toDialect, ok: 0, total: 0, engines: [], samples: [] })
    }
    const pair = pairs.get(key)
    pair.total += 1
    if (cell.ok) pair.ok += 1
    let perEngine = pair.engines.find((e) => e.engine === cell.engine)
    if (perEngine == null) {
      perEngine = { engine: cell.engine, ok: 0, total: 0 }
      pair.engines.push(perEngine)
    }
    perEngine.total += 1
    if (cell.ok) perEngine.ok += 1
    if (cell.ok && typeof cell.sql === 'string' && pair.samples.filter((s) => s.engine === cell.engine).length < SAMPLES_PER_PAIR) {
      pair.samples.push({ engine: cell.engine, caseId: cell.caseId, sql: cell.sql })
    }
  }
  const pairList = [...pairs.values()]
    .map((p) => ({ ...p, engines: engines.map((e) => p.engines.find((x) => x.engine === e) ?? { engine: e, ok: 0, total: 0 }) }))
    .sort((a, b) => a.from.localeCompare(b.from) || a.to.localeCompare(b.to))

  const failures = cells
    .filter((c) => !c.ok)
    .map((c) => ({
      from: c.fromDialect,
      to: c.toDialect,
      engine: c.engine,
      caseId: c.caseId,
      reason: c.reason ?? '(无原因)',
    }))

  return {
    available: true,
    commit: meta.commit ?? null,
    generatedAt: meta.generatedAt ?? null,
    caseCount: num(meta.caseCount),
    engineCount: num(meta.engineCount),
    declaredPairCount: num(meta.pairCount),
    engines,
    pairs: pairList,
    failures,
    rawUrl: '/data/compat-convert.json',
  }
}

// ——— 校验小工具（结构不符一律抛错，不猜） ———

function requireMeta(what, raw) {
  if (!isObj(raw.meta)) fail(what, '缺 meta 对象')
  return raw.meta
}

function requireCells(what, raw) {
  if (!Array.isArray(raw.cells)) fail(what, '缺 cells 数组')
  return raw.cells
}

function fail(what, detail) {
  throw new Error(`数据文件 ${what} 结构不符：${detail}`)
}

function isObj(v) {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

function num(v) {
  return typeof v === 'number' ? v : 0
}

function uniqueInOrder(values) {
  return [...new Set(values)]
}

// ——— 入口：读 `.data/*.json` → 写 `.data/dataset.json` + 拷 raw 到 `public/data/` ———

function readJson(path) {
  if (!existsSync(path)) return null
  try {
    return JSON.parse(readFileSync(path, 'utf-8'))
  } catch (e) {
    throw new Error(`数据文件 ${path} 不是合法 JSON：${e.message}`)
  }
}

export function main() {
  const dataDir = join(root, '.data')
  const publicDataDir = join(root, 'docs', 'public', 'data')
  const inputs = {
    engines: readJson(join(dataDir, 'engines.json')),
    matrix: readJson(join(dataDir, 'compat-matrix.json')),
    convert: readJson(join(dataDir, 'compat-convert.json')),
  }
  const dataset = normalize(inputs)
  mkdirSync(dataDir, { recursive: true })
  writeFileSync(join(dataDir, 'dataset.json'), `${JSON.stringify(dataset, null, 2)}\n`)

  mkdirSync(publicDataDir, { recursive: true })
  for (const name of ['engines.json', 'compat-matrix.json', 'compat-convert.json']) {
    const src = join(dataDir, name)
    if (existsSync(src)) copyFileSync(src, join(publicDataDir, name))
  }

  const mark = (v) => (v ? '✓' : '（缺，页面降级）')
  console.log(`docs 数据规整完成：engines ${mark(dataset.engines.available)} / matrix ${mark(dataset.matrix.available)} / convert ${mark(dataset.convert.available)}`)
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main()
}
