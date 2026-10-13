# ozmoz-lineage 任务编排。
#
# just 是薄编排层：按需调用 npm 与 ./gradlew，自身不含构建逻辑。
# Gradle 只管 JVM 侧（默认构建不要求 Node）；前端与文档站命令经本文件收口。
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

# —— 文档站（site/，Rspress，独立子项目，不进 Gradle）——

# 安装文档站依赖（按锁文件）
docs-install:
    npm --prefix site ci

# 生成文档站数据：引擎能力表 + 两张矩阵（**现生成、不入库**，避免腐化）
docs-data:
    ./gradlew :cli:installDist -q
    mkdir -p site/.data
    ./cli/build/install/ozml/bin/ozml engines --format json > site/.data/engines.json
    ./cli/build/install/ozml/bin/ozml matrix --corpus conformance/corpus --commit "$(git rev-parse --short HEAD)" --out site/.data/compat-matrix.json
    ./cli/build/install/ozml/bin/ozml matrix --convert --corpus conformance/corpus --commit "$(git rev-parse --short HEAD)" --out site/.data/compat-convert.json

# 文档站 dev server（数据缺失时页面自动降级为「先跑 just docs-data」占位）
# 注意：base 写死在 rspress.config.ts（/ozmoz-lineage/），所以本地 URL 也带前缀。
docs-dev:
    npm --prefix site run dev

# 文档站本地预览（先 docs-build 产出 doc_build，再起预览；URL 同样带 base 前缀）
docs-preview: docs-build
    npm --prefix site run preview

# 文档站构建（先现生成数据，再 Rspress 构建 → site/doc_build）
docs-build: docs-data
    npm --prefix site run build

# 文档站闸：数据规整单测
docs-check:
    npm --prefix site run test

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
