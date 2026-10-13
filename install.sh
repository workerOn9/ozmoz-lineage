#!/bin/sh
#
# ozml 引导安装脚本（macOS / Linux）。
#
#   curl -fsSL https://raw.githubusercontent.com/workerOn9/ozmoz-lineage/main/install.sh | sh
#   curl -fsSL .../install.sh | sh -s -- --version 0.2.0        # 装指定版本
#   curl -fsSL .../install.sh | sh -s -- --root /opt/ozml        # 换安装根
#
# 产物自带精简运行时（jlink），装完**不需要**本机装 JDK。安装布局与 `ozml upgrade`
# 共用（两侧必须一致，改一处要同步另一处，见 ADR-0021）：
#
#   $OZML_ROOT/                    默认 ~/.local/share/ozml
#     current                      ← 文本文件，内容为当前版本号
#     bin-dir                      ← shim 所在目录（upgrade 沿用）
#     versions/<version>/{bin,lib,runtime}
#   $BIN_DIR/ozml                  ← shim（exec current 指向的版本）
#
# 幂等：重复运行总是装到目标版本并把 current 切过去。

set -eu

REPO_URL_DEFAULT="https://github.com/workerOn9/ozmoz-lineage/releases"
API_URL_DEFAULT="https://api.github.com/repos/workerOn9/ozmoz-lineage"
RELEASE_BASE="${OZML_RELEASE_BASE:-$REPO_URL_DEFAULT}"
API_BASE="${OZML_API_BASE:-$API_URL_DEFAULT}"

VERSION=""
ROOT="${OZML_ROOT:-$HOME/.local/share/ozml}"
BIN_DIR="${OZML_BIN_DIR:-$HOME/.local/bin}"

usage() {
    cat <<EOF
用法: install.sh [选项]

  --version <v>    安装指定版本（如 0.2.0 或 v0.2.0）；缺省取 GitHub 最新 release
  --root <dir>     安装根目录（默认 \$OZML_ROOT 或 ~/.local/share/ozml）
  --bin-dir <dir>  放 shim 的目录（默认 \$OZML_BIN_DIR 或 ~/.local/bin）
  -h, --help       显示本帮助
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --version) VERSION="${2:?--version 需要一个值}"; shift 2 ;;
        --root) ROOT="${2:?--root 需要一个值}"; shift 2 ;;
        --bin-dir) BIN_DIR="${2:?--bin-dir 需要一个值}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
    esac
done

die() { echo "ozml 安装失败：$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "需要 curl"
command -v tar  >/dev/null 2>&1 || die "需要 tar"

# ── 平台探测（与 ozml upgrade / cli/build.gradle.kts 同规则）──────────────────
os="$(uname -s | tr '[:upper:]' '[:lower:]')"
arch="$(uname -m | tr '[:upper:]' '[:lower:]')"
case "$os" in
    darwin|mac*|darwin*) os_tag="macos" ;;
    linux*)              os_tag="linux" ;;
    *) die "不支持的平台：${os}（Windows 请下载 .zip 手动解压）" ;;
esac
case "$arch" in
    arm64|aarch64) arch_tag="arm64" ;;
    x86_64|amd64)  arch_tag="x64" ;;
    *) die "不支持的架构：$arch" ;;
esac
platform="${os_tag}-${arch_tag}"

# ── 版本解析 ─────────────────────────────────────────────────────────────────
if [ -z "$VERSION" ]; then
    echo "查询最新版本…"
    tag="$(curl -fsSL -H 'Accept: application/vnd.github+json' "$API_BASE/releases/latest" \
        | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"v\{0,1\}\([^"]*\)".*/\1/p' | head -n1)"
    [ -n "$tag" ] || die "无法获取最新版本（可用 --version 指定；或用 OZML_API_BASE 指向本地镜像）"
    VERSION="$tag"
fi
VERSION="${VERSION#v}"

echo "平台：$platform"
echo "版本：$VERSION"
echo "安装根：$ROOT"

# ── 下载 + 校验 ──────────────────────────────────────────────────────────────
asset="ozml-${VERSION}-${platform}.tar.gz"
url="${RELEASE_BASE}/download/v${VERSION}/${asset}"

tmp="$(mktemp -d "${TMPDIR:-/tmp}/ozml-install.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT INT TERM

echo "下载：$url"
curl -fsSL "$url" -o "$tmp/$asset" || die "下载失败（版本 / 平台不存在，或网络不可达）"
curl -fsSL "${RELEASE_BASE}/download/v${VERSION}/checksums.txt" -o "$tmp/checksums.txt" \
    || die "下载 checksums.txt 失败"

expected="$(awk -v n="$asset" '$2 != "" { f=$2; sub(/^\*/, "", f); if (f == n) { print $1; exit } }' "$tmp/checksums.txt")"
[ -n "$expected" ] || die "checksums.txt 中缺少 ${asset}"
if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$tmp/$asset" | awk '{print $1}')"
else
    actual="$(shasum -a 256 "$tmp/$asset" | awk '{print $1}')"
fi
[ "$actual" = "$expected" ] || die "校验失败：${asset} 的 sha256 不匹配（期望 ${expected}，实际 ${actual}）"
echo "校验通过（sha256 ${actual}）"

# ── 解压 + 原子切换 ──────────────────────────────────────────────────────────
mkdir -p "$ROOT/versions"
stage="$ROOT/versions/.${VERSION}.stage.$$"
rm -rf "$stage"
mkdir -p "$stage"
tar -xzf "$tmp/$asset" -C "$stage" || die "解压失败"
[ -d "$stage/ozml" ] || die "归档结构异常（缺少顶层 ozml/）"

target="$ROOT/versions/$VERSION"
rm -rf "$target"
mv "$stage/ozml" "$target"
rmdir "$stage" 2>/dev/null || true

printf '%s\n' "$VERSION" > "$ROOT/current.tmp"
mv "$ROOT/current.tmp" "$ROOT/current"
printf '%s\n' "$BIN_DIR" > "$ROOT/bin-dir"

# ── shim（与 RootLayout.ensureShim 输出一致）─────────────────────────────────
mkdir -p "$BIN_DIR"
cat > "$BIN_DIR/ozml" <<EOF
#!/bin/sh
# 由 ozml install.sh / \`ozml upgrade\` 生成，勿手改。
OZML_ROOT="\${OZML_ROOT:-$ROOT}"
v=\$(cat "\$OZML_ROOT/current" 2>/dev/null) || { echo "ozml: 未安装（缺 \$OZML_ROOT/current）" >&2; exit 1; }
exec "\$OZML_ROOT/versions/\$v/bin/ozml" "\$@"
EOF
chmod 755 "$BIN_DIR/ozml"

echo
echo "完成：$target"
echo "启动器：$BIN_DIR/ozml"
case ":$PATH:" in
    *":$BIN_DIR:"*) echo "验证：ozml --version" ;;
    *) echo "提示：$BIN_DIR 不在 PATH 中，请把它加进 PATH（如 export PATH=\"$BIN_DIR:\$PATH\"），再运行 ozml --version" ;;
esac
