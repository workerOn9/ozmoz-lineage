# 品牌图标

啄木鸟图标。**唯一源在本目录的 `src/`**：

```
src/icon.svg            全尺寸标记（也是站点 favicon 的源）
src/icon-16.svg         16px 专用简化标记 —— 单独画的，不是 icon.svg 的缩放
src/site.webmanifest    PWA 清单

./build.sh              派生全部位图/形态，并分发到 site/ 与 web/（见下）
derive.mjs              单色版 / 全出血方块 / maskable / 横向锁版 / OG 图
preview.mjs             生成 dist/preview.html（形态与尺寸总览）
dist/                   中间产物，已 gitignore；preview.html 也在这里
```

## 重建

```bash
brew install librsvg imagemagick     # 只有这个脚本需要，不参与 CI，也没接进 justfile
cd design/logo && ./build.sh
open dist/preview.html
```

`build.sh` 会写两个**入库**目录，所以改图标之后要一起提交：

| 写出位置 | 内容 |
|---|---|
| `site/docs/public/` | `favicon.svg`、`favicon-16.svg`、`favicon-32.png`、`favicon.ico`、`apple-touch-icon.png`、`android-chrome-{192,512}.png`、`og.svg` + `og.png`、`site.webmanifest` |
| `web/public/` | `favicon.svg`、`favicon-16.svg`、`favicon.ico`、`apple-touch-icon.png` |

> `site/docs/public/` 与 `web/public/` 里的图标文件都是**构建输出**。不要手改那两个目录里的图标 —— 改 `src/` 再重跑。

## 为什么是啄木鸟

竖向构图里「躯干贴树 + 尾羽撑住 + 低头啄击」是啄木鸟最不需要解释的姿势；树干上那串虫洞承接
「逐语句 / 逐层下溯」，也承接「啄出 bug」。色板不引入新色：主色 `#3451b2`（沿用原 favicon 的靛蓝）、
强调色 `#f59e0b`（站点已有的告警琥珀）、第二色 `#cdd9f7`。

选型时另外试过**鼹鼠**与**星鼻鼹**：

- 鼹鼠：物种特征（无耳、铲爪）在 16px 下只剩一个形状，会被读成熊／仓鼠。
- 星鼻鼹：彩色版会被读成鸭嘴兽或戴翎的鸟 —— 放射状触手环在 logo 尺度下天生歧义；
  单色深底版反而最像鼹鼠。落选。
- 原 favicon 的「四节点连线」符号血缘语义比任何动物都直白，**没有丢弃**：它降级成了
  OG 图右侧的淡色水印（见 `derive.mjs` 里的 `NODES` / `EDGES`）。

（完整选型记录与逐轮迭代稿在作者的本地 `local/logo-drafts/`，未入库。）

## 四个实现约定

1. **64 网格 + 圆角方块底**：`viewBox="0 0 64 64"`，底 `rect rx="14"`。线宽、虫洞、冠羽都按 64 网格取值，改尺寸只改外部宽高。
2. **用 `<mask>` 打真孔**，而不是「画一块底色上去」：眼睛与树干虫洞是真实镂空，因此同一份图形在彩色底、浅底、深底、单色下都成立，不必为每种底色复制一套。
3. **单色版靠注入 override `<style>`，不靠改根元素 CSS 变量**：实测 **librsvg 2.63.2 只认 `var()` 的回退值，不把根 `<svg style="--x:…">` 里声明的自定义属性继承给子元素**（Chrome 正常）。所以单色版在原 `<style>` 之后追加一段 `currentColor` + 透明度的规则；换色给外层 `color` 即可（`currentColor` 是真正的 CSS 属性，librsvg 能正常继承）。
4. **各平台形态不是同一张图**：
   - `apple-touch-icon` 用**全出血方块**（`rx=0`）—— iOS 自己裁圆角，带透明角的图会被填黑。
   - `maskable` 用全出血 + 内容缩进中央 80% 安全区 —— 否则被裁成圆形/水滴时树干与冠羽会被切掉。
   - `.ico` 的 16px 帧用 `icon-16.svg`，32/48 帧用 `icon.svg`。

## 已知取舍

- **冠羽可能被读成「戴帽子」**。要消掉它就得牺牲那个琥珀色块，而琥珀是整套资产里唯一的暖色锚点，所以有意保留。
- **16px 那档仍然只是「靛蓝小方块 + 一团浅色」**，这是吉祥物类 favicon 的普遍现实。若 16px 可辨性优先级更高，应当另做一枚纯几何标记。
- **字标用系统 sans 栈**，跨机器字宽会漂移；`build.sh` 每次实测并打印锁版内容宽度，换字体后照此调整 `LOCKUP_WIDTH`。
- **彩色 tile 自带底色**，深浅底通吃，暂不需要第二套暗色变体。
