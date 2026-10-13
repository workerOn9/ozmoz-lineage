import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { DOCS_URL } from '../src/lib/links'

// 应用内「文档」入口与文档站部署位置的一致性检查（防漂移）。
//
// 文档站（site/，Rspress）的 `base` 是**写死**在 rspress.config.ts 里的（Rspress 2.0.23
// 从 env 读 base 时不会传到站点数据/链接，见该文件注释）；应用里的链接因此也必须与之对齐。

const here = dirname(fileURLToPath(import.meta.url))
const siteConfig = readFileSync(join(here, '..', '..', 'site', 'rspress.config.ts'), 'utf-8')

describe('文档站链接', () => {
  it('指向 GitHub Pages 项目站点', () => {
    expect(DOCS_URL).toBe('https://workeron9.github.io/ozmoz-lineage/')
  })

  it('与 site/rspress.config.ts 的 base 对齐（改一处必须改另一处）', () => {
    const matched = /const base = '([^']+)'/.exec(siteConfig)
    expect(matched, 'rspress.config.ts 里应有字面量 base').not.toBeNull()
    const base = matched![1]
    expect(DOCS_URL.endsWith(base)).toBe(true)
    // Pages 项目站点的 base 形如 /<repo>/；origin 由 siteOrigin 给出。
    // 接受 `siteOrigin: '…'`（对象属性）与 `siteOrigin = '…'`（顶层 const）两种写法 ——
    // 后者是为了在 defineConfig 之外拼绝对 URL（head 里的图标与 og:image）。
    const origin = /siteOrigin\s*[:=]\s*'([^']+)'/.exec(siteConfig)?.[1]
    expect(origin).toBe('https://workeron9.github.io')
    expect(DOCS_URL).toBe(`${origin}${base}`)
  })
})
