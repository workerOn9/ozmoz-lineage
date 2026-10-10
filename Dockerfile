# syntax=docker/dockerfile:1
#
# ozmoz-lineage 一体化镜像（web 设计稿 §6.5）：web/ 静态产物 + `ozml serve` 单端口。
# 全程离线可跑：不依赖任何云服务，SQL 与元数据不出本机。
#
#   构建：docker build -t ozmoz-lineage .
#   运行：docker run --rm -p 8765:8765 ozmoz-lineage
#   打开：http://localhost:8765
#
# 容器内必须绑 0.0.0.0（--host）：CLI 默认只绑回环是「本地工具不暴露局域网」的
# 有意取舍，容器场景由编排层显式放宽。

# ── stage 1：前端构建（Node 22 轴）───────────────────────────────────────────
FROM node:22-bookworm-slim AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ── stage 2：JVM 构建（JDK 21 轴；gradle 镜像版本与 wrapper 锁定一致）─────────
FROM gradle:9.8.1-jdk21 AS jvm
WORKDIR /src
COPY --chown=gradle:gradle . .
# installDist 产出可执行发行版（bin/ozml + lib/），不打测试（测试由 CI 的 build 轴守）。
RUN gradle --no-daemon :cli:installDist

# ── stage 3：运行时（JRE + ozml + dist，单端口）───────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=jvm /src/cli/build/install/ozml /app/ozml
COPY --from=web /src/web/dist /app/web/dist
EXPOSE 8765
ENTRYPOINT ["/app/ozml/bin/ozml", "serve", "--host", "0.0.0.0", "--web-root", "/app/web/dist"]
