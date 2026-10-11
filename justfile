# ozmoz-lineage 任务编排。
#
# just 是薄编排层：按需调用 npm 与 ./gradlew，自身不含构建逻辑。
# Gradle 只管 JVM 侧（默认构建不要求 Node）；前端命令经本文件收口。
# 安装 just：brew install just（或 cargo install just）。列出全部目标：just

# 列出全部目标
default:
    @just --list

# —— 前端（web/，独立 Vite 子项目，不进 Gradle 多模块）——

# 按锁文件安装依赖（本地日常也可 npm --prefix web install）
web-install:
    npm --prefix web ci

# 前端 dev server（/api 代理到 8765；需另起 ozml serve）
web-dev:
    npm --prefix web run dev

# 前端闸：类型检查 + 单测
web-check:
    npm --prefix web run typecheck
    npm --prefix web run test

# 产出 web/dist
web-build:
    npm --prefix web run build

# —— JVM ——

# JVM 构建与测试（不需要 Node）
build:
    ./gradlew build

# —— 集成 ——

# 构建前端 + 启动 ozml serve 托管 dist（单端口单进程：SPA 与 /api 同端口）
#
# --graph-root graphs：开启「本地图库」数据源（ADR-0015）——只允许浏览 graphs/ 下的 *.db。
# 目录不存在 / 为空时 /api/graphs 回空清单，功能照常可用（只是没有库可选）。
# 用 ozml lineage -f <sql目录> --graph graphs/<名>.db 落库后即可在 UI 里打开。
# 公开部署（Dockerfile.vercel）有意**不带**这个参数：不给 = 图库能力整体关闭。
serve: web-build
    ./gradlew :cli:installDist -q
    ./cli/build/install/ozml/bin/ozml serve --web-root web/dist --graph-root graphs
