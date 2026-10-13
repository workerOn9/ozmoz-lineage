// 从 src/icon.svg 派生各平台形态（单色 / 全出血方块 / maskable / 横向锁版 / OG 图）。
//
// 为什么不把 OG 图当作「手写的源」：OG 图里嵌了一份图标图形，若同时存在两份手写图形，
// 改图标时必然漏改一处。所以 OG 图也从 src/icon.svg 现生成。
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'

const ICON = 'src/icon.svg'
const OUT = 'dist'
mkdirSync(OUT, { recursive: true })

const icon = readFileSync(ICON, 'utf8')

/** 取 SVG 的图形本体：丢掉标题/描述，避免派生文件里一份 SVG 出现两个 <title>（读屏会念两遍） */
function bodyOf(svg) {
  const open = svg.indexOf('>', svg.indexOf('<svg')) + 1
  return svg
    .slice(open, svg.lastIndexOf('</svg>'))
    .replace(/<title[^>]*>[\s\S]*?<\/title>\s*/g, '')
    .replace(/<desc[^>]*>[\s\S]*?<\/desc>\s*/g, '')
    .trim()
}

/** 在第一个 </style> 之后追加一段规则块（后出现者优先级更高，无需 !important） */
function appendStyle(svg, block) {
  const at = svg.indexOf('</style>')
  if (at < 0) throw new Error(`${ICON} 里找不到 </style>`)
  const end = at + '</style>'.length
  return `${svg.slice(0, end)}\n  ${block}${svg.slice(end)}`
}

// ── 1. 单色版 ──────────────────────────────────────────────────────
// 不用「在根元素上改 CSS 变量」：实测 librsvg 2.63.2 只解析 var() 的**回退值**，
// 不把根 <svg style="--x:…"> 里声明的自定义属性继承给子元素（浏览器则正常）。
// 用 currentColor + 层级透明度，浏览器与 librsvg 行为一致；换色给外层元素设 color 即可
// （currentColor 是真正的 CSS 属性，librsvg 能正常继承）。
writeFileSync(
  `${OUT}/icon-mono.svg`,
  appendStyle(
    icon,
    `<style>
    /* 单色版 override：把彩色 token 全部并到 currentColor，用透明度分层次 */
    .oz-tile{fill:none}
    .oz-ink{fill:currentColor;opacity:1}
    .oz-tone{fill:currentColor;opacity:.6}
    .oz-acc{fill:currentColor;opacity:.85}
    .oz-dim{fill:currentColor;opacity:.25}
    .oz-acc-s{stroke:currentColor;opacity:.85}
  </style>`,
  ),
)

// ── 2. 全出血方块（apple-touch-icon 源）─────────────────────────────
// iOS 自己裁圆角；带透明角的图会被它填黑，所以这里给的是直角满铺。
const square = icon.replace(/rx="14"\/>/, 'rx="0"/>')
if (square === icon) throw new Error(`没匹配到圆角矩形，检查 ${ICON} 的 rx`)
writeFileSync(`${OUT}/icon-square.svg`, square)

// ── 3. maskable（Android / PWA）─────────────────────────────────────
// 全出血 + 内容缩进中央 80% 安全区：不缩进的话，被裁成圆形/水滴时树干与冠羽会被切掉。
// 只缩放内容组，不缩放底 —— 底必须铺满整块画布。
const maskable = square.replace(/<g mask="url\(#([^)]+)\)">/, (_m, id) =>
  `<g transform="translate(7.04 7.04) scale(0.78)" mask="url(#${id})">`,
)
if (!maskable.includes('scale(0.78)')) throw new Error(`没匹配到内容组，检查 ${ICON} 的 <g mask=…>`)
writeFileSync(`${OUT}/icon-maskable.svg`, maskable)

// ── 4. 横向锁版 ────────────────────────────────────────────────────
// 字标用系统 sans 栈，跨机器字宽会漂移 —— build.sh 每次实测并打印内容宽度，
// 换字体后照着调 LOCKUP_WIDTH。图标 64 宽 + 间距 16 → 字标从 x=80 起。
const FONT = "ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif"
const WIDTH = Number(process.env.LOCKUP_WIDTH ?? 288)

function lockup(textFill) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${WIDTH} 64" width="${WIDTH}" height="64" role="img" aria-labelledby="ozml-lockup-t ozml-lockup-d">
  <title id="ozml-lockup-t">ozmoz-lineage</title>
  <desc id="ozml-lockup-d">左侧为啄木鸟方形图标，右侧为 ozmoz-lineage 字标</desc>
  ${bodyOf(icon)}
  <text x="80" y="41.5" font-family="${FONT}" font-size="30" font-weight="650" letter-spacing="-0.6" fill="${textFill}">ozmoz-lineage</text>
</svg>
`
}
writeFileSync(`${OUT}/logo-lockup.svg`, lockup('#0f172a'))
writeFileSync(`${OUT}/logo-lockup-inverse.svg`, lockup('#ffffff'))

// ── 5. OG 图 1200×630 ──────────────────────────────────────────────
// 右侧那层淡色「节点-边」水印＝**原 favicon 的形态**（四节点连线）。它的血缘语义比
// 任何动物都直白，所以不做在吉祥物身上，改由 OG / 文档配图承载。
const NODES = [
  [742, 196], [886, 140], [886, 296], [1030, 224], [1030, 400], [1158, 296], [840, 440],
]
const EDGES = [[0, 1], [0, 2], [1, 3], [2, 3], [2, 4], [4, 5], [3, 5], [2, 6], [6, 4]]
const CJK = "PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"

writeFileSync(
  `${OUT}/og.svg`,
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 630" width="1200" height="630" role="img" aria-labelledby="ozml-og-t ozml-og-d">
  <title id="ozml-og-t">ozmoz-lineage —— 渗透血缘</title>
  <desc id="ozml-og-d">靛蓝背景上，左侧是啄木鸟图标与 ozmoz-lineage 字标，右侧是淡色血缘图水印</desc>

  <rect width="1200" height="630" fill="#3451b2"/>
  <g stroke="#ffffff" stroke-width="3" opacity=".13" fill="none">
${EDGES.map(([a, b]) => `    <line x1="${NODES[a][0]}" y1="${NODES[a][1]}" x2="${NODES[b][0]}" y2="${NODES[b][1]}"/>`).join('\n')}
  </g>
  <g fill="#ffffff" opacity=".13">
${NODES.map(([x, y]) => `    <circle cx="${x}" cy="${y}" r="9"/>`).join('\n')}
  </g>

  <!-- 嵌套 svg：把图标图形缩进这个 132×132 的框，同时顺带把它的 .oz-* 类带进本文件
       （SVG 里的 <style> 是文档级的，所以嵌套进来即可复用，不必抄一份配色） -->
  <svg x="88" y="168" width="132" height="132" viewBox="0 0 64 64">${bodyOf(icon)}</svg>
  <text x="248" y="260" font-family="${CJK}" font-size="70" font-weight="700" letter-spacing="-2" fill="#ffffff">ozmoz-lineage</text>

  <rect x="88" y="318" width="72" height="5" rx="2.5" fill="#f59e0b"/>

  <text x="88" y="380" font-family="${CJK}" font-size="29" fill="#ffffff" fill-opacity=".92">渗透血缘 —— 面向 JVM 生态的离线 SQL 解析 / 血缘 / 方言对照工具链</text>
  <text x="88" y="428" font-family="${MONO}" font-size="20" fill="#ffffff" fill-opacity=".7">ozml parse · lineage · impact · convert · validate · format · serve · mcp</text>

  <text x="88" y="562" font-family="${CJK}" font-size="20" fill="#ffffff" fill-opacity=".62">Apache-2.0 · 解析与血缘全部本地完成，SQL 与元数据不出内网</text>
</svg>
`,
)
