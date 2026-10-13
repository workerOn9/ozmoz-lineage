import path from 'node:path'
import { defineConfig } from '@rspress/core'

// 部署在 GitHub Pages 的**项目站点**下：https://workeron9.github.io/ozmoz-lineage/
//
// ⚠️ base 必须写成**字面量**。实测（2026-10-13，Rspress 2.0.23）：从 `process.env.DOCS_BASE`
// 读 base 时，只有资源前缀（`/ozmoz-lineage/static/...`）拿到该值，而**站点数据 / 路由表 /
// 页面链接仍是 `/`** —— 子路径下客户端会直接渲染 404（生成站点数据那一步读不到环境变量）。
// 硬编码后链接、路由、llms.md 链接全部正确带前缀。换部署位置（例如将来挂到应用同域 `/docs`）
// 只需改这一行；本地 `rspress dev` / `preview` 同样按这个 base 挂载（URL 带前缀）。
const base = '/ozmoz-lineage/'

export default defineConfig({
  root: 'docs',
  base,
  siteOrigin: 'https://workeron9.github.io',
  title: 'ozmoz-lineage',
  description: '渗透血缘 —— 面向 JVM 生态的离线 SQL 解析 / 血缘 / 方言对照工具链',
  lang: 'zh',
  icon: '/favicon.svg',
  logoText: 'ozmoz-lineage',
  globalStyles: path.join(__dirname, 'styles/global.css'),
  // AI-native：构建期额外产 llms.txt / llms-full.txt 与每页 .md（Rspress 的 SSG-MD），
  // 让「工具百科」也能被 AI 直接读。
  llms: true,
  themeConfig: {
    nav: [
      { text: '工具百科', link: '/tools/' },
      { text: '兼容性矩阵', link: '/tools/compat' },
      { text: '方言转换矩阵', link: '/tools/convert' },
    ],
    sidebar: {
      '/tools/': [
        {
          text: '工具百科',
          items: [
            { text: '引擎能力 / 方言目录', link: '/tools/' },
            { text: '兼容性矩阵', link: '/tools/compat' },
            { text: '方言转换矩阵', link: '/tools/convert' },
          ],
        },
      ],
    },
    socialLinks: [
      { icon: 'github', mode: 'link', content: 'https://github.com/workerOn9/ozmoz-lineage' },
    ],
    footer: {
      message: 'Apache-2.0 · 数据由 CI 生成，每格可溯源到 commit',
    },
  },
})
