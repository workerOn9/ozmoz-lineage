#!/usr/bin/env bash
# 从 design/logo/src/ 派生品牌资产，并把站点/Web UI 要服务的文件分发到位。
#
#   ./build.sh              # 全量派生 + 分发
#   LOCKUP_WIDTH=300 ./build.sh
#
# 依赖（**只有本脚本需要**，不参与 CI，也没有接进 justfile）：
#   brew install librsvg imagemagick
#
# 写到哪里：
#   design/logo/dist/     中间产物（已 gitignore）—— preview.html 也在这里
#   site/docs/public/     文档站真正要服务的文件（已入库，站点的静态目录）
#   web/public/           Web UI 真正要服务的文件（已入库）
#
# 唯一源是 src/icon.svg 与 src/icon-16.svg；site/ 与 web/ 里的都是本脚本的输出，
# 因此不要手改那两个目录里的图标文件 —— 改这里再重跑。
set -euo pipefail
cd "$(dirname "$0")"

DOCS=../../site/docs/public
WEB=../../web/public

for c in rsvg-convert magick node; do
  command -v "$c" >/dev/null 2>&1 || {
    echo "缺少依赖：$c（macOS: brew install librsvg imagemagick）" >&2
    exit 1
  }
done

mkdir -p dist "$DOCS" "$WEB"

# 1) 派生 SVG 形态：单色 / 全出血方块 / maskable / 锁版 / OG
node derive.mjs

# 2) 派生位图。16px 那一档来自**单独画的**简化标记，32/48 用全尺寸标记。
r() { rsvg-convert -w "$2" -h "$2" "$1" -o "$3"; }

r src/icon-16.svg      16  dist/favicon-16.png
r src/icon.svg         32  dist/favicon-32.png
r src/icon.svg         48  dist/favicon-48.png
r src/icon.svg         16  dist/full-16.png          # 仅作预览页的 16px 对照
magick dist/favicon-16.png dist/favicon-32.png dist/favicon-48.png dist/favicon.ico

r dist/icon-square.svg 180  dist/apple-touch-icon.png          # iOS：全出血、无透明
r dist/icon-maskable.svg 192 dist/android-chrome-192.png       # Android：内容已缩进安全区
r dist/icon-maskable.svg 512 dist/android-chrome-512.png
r src/icon.svg         512  dist/icon-512.png                  # GitHub 头像 / README
r src/icon.svg         256  dist/icon-256.png

rsvg-convert -w 1200 -h 630 dist/og.svg -o dist/og.png

# GitHub 仓库 social preview 要 1280×640（官方建议 ≥640×320，1280×640 显示最佳）。
# 等比放大到 1280 宽是 1280×672，再居中裁到 640 高 —— 上下各裁 16px，版式里最靠边的
# 页脚文字离底边还有 57px，裁不到。
magick dist/og.png -resize 1280x -gravity center -extent 1280x640 dist/og-github.png

# 自检图：maskable 被裁成圆形后关键内容是否还在（改图形后先看这张）
magick dist/android-chrome-512.png \
  \( +clone -alpha extract -fill black -colorize 100 -fill white \
     -draw "circle 256,256 256,6" -alpha off \) \
  -alpha off -compose CopyOpacity -composite dist/maskable-circle-preview.png

# 3) 分发：站点静态目录（文件名用站点约定）+ Web UI
cp src/icon.svg            "$DOCS/favicon.svg"
cp src/icon-16.svg         "$DOCS/favicon-16.svg"
cp src/site.webmanifest    "$DOCS/site.webmanifest"
cp dist/favicon-32.png     "$DOCS/favicon-32.png"
cp dist/favicon.ico        "$DOCS/favicon.ico"
cp dist/apple-touch-icon.png "$DOCS/apple-touch-icon.png"
cp dist/android-chrome-192.png "$DOCS/android-chrome-192.png"
cp dist/android-chrome-512.png "$DOCS/android-chrome-512.png"
cp dist/og.svg             "$DOCS/og.svg"
cp dist/og.png             "$DOCS/og.png"

cp src/icon.svg            "$WEB/favicon.svg"
cp src/icon-16.svg         "$WEB/favicon-16.svg"
cp dist/favicon.ico        "$WEB/favicon.ico"
cp dist/apple-touch-icon.png "$WEB/apple-touch-icon.png"

# 4) 预览页 + 锁版宽度实测（字标用系统字体，跨机器字宽会漂移）
node preview.mjs
echo "--- 锁版内容实测宽度（上限 ${LOCKUP_WIDTH:-288}）---"
for v in logo-lockup logo-lockup-inverse; do
  rsvg-convert -w 1200 "dist/$v.svg" -o "dist/.lk.png"
  printf '  %-22s %s\n' "$v" "$(magick dist/.lk.png -background none -trim -format '%wx%h' info:)"
done
rm -f dist/.lk.png

echo "完成。预览： open design/logo/dist/preview.html"
