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
const siteOrigin = 'https://workeron9.github.io'
// 站点绝对根。head 里的资源一律用绝对 URL：
//   - 相对路径在 `/ozmoz-lineage/tools/compat` 这类深层页面会解析错；
//   - og:image 更要求绝对 URL（社交平台不会解析相对路径）。
// 直接拼绝对 URL 还有个好处：不必依赖 Rspress 是否给自定义 head 自动加 base 前缀。
// （对比：`icon` 字段由 Rspress 处理 base，产出的就是 `/ozmoz-lineage/favicon.svg`。）
const origin = `${siteOrigin}${base}`

export default defineConfig({
  root: 'docs',
  base,
  siteOrigin,
  title: 'ozmoz-lineage',
  description: '渗透血缘 —— 面向 JVM 生态的离线 SQL 解析 / 血缘 / 方言对照工具链',
  lang: 'zh',
  icon: '/favicon.svg',
  logoText: 'ozmoz-lineage',
  globalStyles: path.join(__dirname, 'styles/global.css'),
  // 品牌资产（全部落在 docs/public/，构建期原样拷到站点根）：
  //   favicon.svg        全尺寸标记（啄木鸟），`icon` 字段指的就是它
  //   favicon-16.svg     16px 专用简化标记：全尺寸标记缩到 16px 会糊成一团浅色，
  //                      这一枚是单独画的，只留「树干 + 头 + 冠羽 + 喙」
  //   favicon-32.png / favicon.ico / apple-touch-icon.png
  //   og.svg → og.png    OG 图；og.svg 是可复现的源（改完要重新导出 og.png）
  //   site.webmanifest   PWA 清单
  head: [
    // 主图标由上面的 `icon` 字段产出，这里只补它的其它形态，避免重复声明
    ['link', { rel: 'icon', type: 'image/svg+xml', sizes: '16x16', href: `${origin}favicon-16.svg` }],
    ['link', { rel: 'icon', type: 'image/png', sizes: '32x32', href: `${origin}favicon-32.png` }],
    ['link', { rel: 'shortcut icon', href: `${origin}favicon.ico` }],
    ['link', { rel: 'apple-touch-icon', sizes: '180x180', href: `${origin}apple-touch-icon.png` }],
    ['link', { rel: 'manifest', href: `${origin}site.webmanifest` }],
    ['meta', { name: 'theme-color', content: '#3451b2' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'ozmoz-lineage' }],
    ['meta', { property: 'og:title', content: 'ozmoz-lineage —— 渗透血缘' }],
    [
      'meta',
      {
        property: 'og:description',
        content: '面向 JVM 生态的离线 SQL 解析 / 血缘 / 方言对照工具链',
      },
    ],
    ['meta', { property: 'og:url', content: origin }],
    ['meta', { property: 'og:image', content: `${origin}og.png` }],
    ['meta', { property: 'og:image:width', content: '1200' }],
    ['meta', { property: 'og:image:height', content: '630' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:image', content: `${origin}og.png` }],
  ],
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
