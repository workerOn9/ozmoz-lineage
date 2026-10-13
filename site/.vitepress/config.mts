import { defineConfig } from 'vitepress'

// GitHub Pages 的**项目站点**部署在 /ozmoz-lineage/ 下（CI 传 DOCS_BASE）；
// 本地 dev / preview 用根路径（缺省 '/'）。
const base = process.env.DOCS_BASE ?? '/'

export default defineConfig({
  title: 'ozmoz-lineage',
  description: '渗透血缘 —— 面向 JVM 生态的离线 SQL 解析 / 血缘 / 方言对照工具链',
  base,
  lang: 'zh-CN',
  // 不用 cleanUrls：GitHub Pages 不做无扩展名重写，默认的 `x.html` 链接才是稳的。
  head: [['link', { rel: 'icon', href: `${base}favicon.svg` }]],
  themeConfig: {
    nav: [
      { text: '工具百科', link: '/tools/' },
      { text: '兼容性矩阵', link: '/tools/compat' },
      { text: '方言转换矩阵', link: '/tools/convert' },
    ],
    sidebar: [
      {
        text: '工具百科',
        items: [
          { text: '引擎能力 / 方言目录', link: '/tools/' },
          { text: '兼容性矩阵', link: '/tools/compat' },
          { text: '方言转换矩阵', link: '/tools/convert' },
        ],
      },
    ],
    socialLinks: [{ icon: 'github', link: 'https://github.com/workerOn9/ozmoz-lineage' }],
    search: { provider: 'local' },
    outline: { label: '本页目录', level: [2, 3] },
    docFooter: { prev: '上一页', next: '下一页' },
    footer: {
      message: 'Apache-2.0 · 数据由 CI 生成，每格可溯源到 commit',
      copyright: 'ozmoz-lineage',
    },
  },
})
