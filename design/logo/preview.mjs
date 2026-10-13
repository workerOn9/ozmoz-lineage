// 生成 dist/preview.html：把图标在「真实使用尺寸与形态」下摆出来，方便一次性过目。
//
// 为什么要脚本生成：单色版必须**内联**才能用 `color` 换色（<img> 里的 currentColor 取不到
// 外层颜色）；而内联 SVG 的 <style> 是文档级的，同一枚图标在页面里内联多次时，
// 各自的 class 与 <mask> id 会互相污染 —— 所以每一次内联都要有独立前缀。
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'

mkdirSync('dist', { recursive: true })

let seq = 0
function inline(file) {
  const prefix = `p${++seq}-`
  let s = readFileSync(file, 'utf8')
  s = s.replace(/<title[^>]*>[\s\S]*?<\/title>/g, '').replace(/<desc[^>]*>[\s\S]*?<\/desc>/g, '')
  s = s.replace(/\saria-labelledby="[^"]*"/g, '').replace(/\srole="img"/g, '')
  s = s.replace(/class="([^"]+)"/g, (_, c) => `class="${prefix}${c.replace(/\s+/g, ' ' + prefix)}"`)
  s = s.replace(/\.oz-/g, `.${prefix}oz-`)
  s = s.replace(/id="([^"]*)"/g, (_, id) => `id="${prefix}${id}"`)
  s = s.replace(/url\(#([^)]*)\)/g, (_, id) => `url(#${prefix}${id})`)
  s = s.replace(/^<svg([^>]*?)\s+width="[^"]*"\s+height="[^"]*"/, '<svg$1')
  return s.replace('<svg', '<svg aria-hidden="true" focusable="false"')
}

/** 内联一枚 SVG，按给定尺寸 1:1 展示（同一枚多次内联 → 各自独立前缀） */
const scaled = (file, size, label = `${size}px`) =>
  `<div class="cell">${inline(file).replace('<svg', `<svg style="width:${size}px;height:${size}px"`)}<span>${label}</span></div>`

/** 像素放大块：点采样放大，看清该尺寸下到底剩什么 */
const zoom = (png, factor, label) =>
  `<div class="cell"><img class="zoom" src="${png}" width="${16 * factor}" height="${16 * factor}" alt="${label}"><span>${label}</span></div>`

const mono = [
  ['#0f172a', '浅底 · 墨色', 'on-light'],
  ['#ffffff', '深底 · 白', 'on-dark'],
  ['#3451b2', '浅底 · 品牌色', 'on-light'],
]
  .map(
    ([color, label, bg]) =>
      `<div class="cell mono ${bg}" style="color:${color}">${inline('dist/icon-mono.svg')}<span>${label}</span></div>`,
  )
  .join('')

const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ozmoz-lineage 图标 · 形态与尺寸总览</title>
<style>
  :root { --bg:#f6f7fb; --fg:#0f172a; --dim:#5b6478; --line:#e2e6f0; --card:#fff; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#0b1220; --fg:#eef2ff; --dim:#98a2b8; --line:#1e293b; --card:#111a2e; }
  }
  * { box-sizing: border-box; }
  body { margin:0; padding:40px 24px 72px; background:var(--bg); color:var(--fg);
    font:15px/1.65 ui-sans-serif, system-ui, -apple-system, 'PingFang SC', sans-serif; }
  .wrap { max-width:1040px; margin:0 auto; }
  h1 { font-size:24px; margin:0 0 6px; letter-spacing:-.02em; }
  h2 { font-size:16px; margin:0 0 4px; }
  h3 { font-size:12px; text-transform:uppercase; letter-spacing:.08em; color:var(--dim); margin:26px 0 10px; font-weight:600; }
  .sub { color:var(--dim); margin:0 0 8px; }
  .sub code { background:var(--card); border:1px solid var(--line); border-radius:5px; padding:1px 5px; font-size:13px; }
  .note { color:var(--dim); font-size:13px; margin:14px 0 0; max-width:78ch; }
  .card { background:var(--card); border:1px solid var(--line); border-radius:16px; padding:24px 26px 26px; margin:0 0 22px; }
  .strip { display:flex; gap:20px; align-items:flex-end; flex-wrap:wrap; }
  .cell { display:flex; flex-direction:column; align-items:center; gap:8px; }
  .cell span { font-size:11px; color:var(--dim); font-variant-numeric:tabular-nums; text-align:center; }
  .mono { padding:14px; border-radius:10px; }
  .mono svg { width:96px; height:96px; }
  .on-light { background:#fff; box-shadow:inset 0 0 0 1px var(--line); }
  .on-dark { background:#0f172a; }
  .zoom { image-rendering:pixelated; }
  .lockup { padding:14px 16px; border-radius:10px; background:#fff; box-shadow:inset 0 0 0 1px var(--line); }
  .lockup svg { width:288px; height:auto; display:block; }
  .lockup.inv { background:#0f172a; box-shadow:none; }
  .tabs { display:flex; background:var(--card); border:1px solid var(--line); border-radius:12px 12px 0 0; padding:8px 8px 0; }
  .tab { display:flex; align-items:center; gap:8px; padding:8px 14px; border-radius:9px 9px 0 0; background:var(--bg);
    font-size:13px; color:var(--dim); border:1px solid var(--line); border-bottom:none; }
  .page { background:var(--card); border:1px solid var(--line); border-top:none; border-radius:0 0 12px 12px; padding:18px; color:var(--dim); font-size:13px; }
  .og { width:100%; max-width:660px; height:auto; border-radius:10px; display:block; }
</style>
</head>
<body>
<div class="wrap">
  <h1>ozmoz-lineage 图标</h1>
  <p class="sub">唯一源：<code>design/logo/src/icon.svg</code>（全尺寸）与 <code>src/icon-16.svg</code>（16px 专用）。
  <code>site/</code>、<code>web/</code> 里的是 <code>./build.sh</code> 的输出 —— 不要手改，改源后重跑。</p>
  <p class="sub">配色：主色 <code>#3451b2</code>（沿用原 favicon）· 强调色 <code>#f59e0b</code>（站点已有的告警琥珀）· 第二色 <code>#cdd9f7</code>。</p>

  <section class="card">
    <h2>全尺寸标记 <code>icon.svg</code></h2>
    <h3>真实尺寸（1:1）</h3>
    <div class="strip">${[16, 24, 32, 48, 64].map((n) => scaled('src/icon.svg', n)).join('')}</div>
    <h3>放大看细节</h3>
    <div class="strip">${[96, 128, 192].map((n) => scaled('src/icon.svg', n)).join('')}</div>

    <h3>16px 对照：专用简化标记 vs 全尺寸标记</h3>
    <div class="strip">
      ${zoom('favicon-16.png', 8, '简化标记 16px ×8')}
      ${zoom('full-16.png', 8, '全尺寸 16px ×8')}
      <div class="cell">${inline('src/icon-16.svg').replace('<svg', '<svg style="width:64px;height:64px"')}<span>icon-16.svg</span></div>
      <div class="cell">${inline('src/icon.svg').replace('<svg', '<svg style="width:64px;height:64px"')}<span>icon.svg</span></div>
    </div>
    <p class="note">简化标记是**单独画的**，不是缩放：只留树干、鸟头、冠羽、喙四个形状，并把树干对比度从 .26 提到 .32。
    两份在 ≥32px 时视觉一致，所以大图不必各做一套；只有 16px 那一档分道扬镳。</p>

    <h3>平台形态</h3>
    <div class="strip">
      <div class="cell"><img src="apple-touch-icon.png" width="110" height="110" style="width:110px;height:110px" alt=""><span>apple-touch-icon 180<br>全出血、无透明</span></div>
      <div class="cell"><img src="maskable-circle-preview.png" width="110" height="110" style="width:110px;height:110px" alt=""><span>maskable 裁圆预演<br>内容已缩进安全区</span></div>
      <div class="cell"><img src="icon-512.png" width="110" height="110" style="width:110px;height:110px" alt=""><span>icon-512<br>GitHub 头像 / README</span></div>
    </div>

    <h3>单色（currentColor：给外层设 color 即可换色）</h3>
    <div class="strip">${mono}</div>

    <h3>横向锁版</h3>
    <div class="strip">
      <div class="lockup">${inline('dist/logo-lockup.svg')}</div>
      <div class="lockup inv">${inline('dist/logo-lockup-inverse.svg')}</div>
    </div>

    <h3>OG 图 1200×630</h3>
    <img class="og" src="og.png" alt="ozmoz-lineage OG 图">
    <p class="note">右侧那层淡色水印＝原 favicon 的「节点-边」形态。它的血缘语义比任何动物都直白，
    所以不做在吉祥物身上，改由 OG 图与文档配图承载。</p>
  </section>
</div>
</body>
</html>
`

writeFileSync('dist/preview.html', html)
console.log('dist/preview.html')
