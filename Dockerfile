# syntax=docker/dockerfile:1.7
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
# 只拷 JVM 侧输入：前端改动不该让 JVM 层缓存失效（前端迭代只重跑 node 阶段）。
# ⚠️ 新增 Gradle 模块时，要把模块目录加到下面第二行。
COPY --chown=gradle:gradle gradle/ ./gradle/
COPY --chown=gradle:gradle gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY --chown=gradle:gradle ir/ ./ir/
COPY --chown=gradle:gradle engine-api/ ./engine-api/
COPY --chown=gradle:gradle engine-jsqlparser/ ./engine-jsqlparser/
COPY --chown=gradle:gradle engine-calcite/ ./engine-calcite/
COPY --chown=gradle:gradle engine-antlr/ ./engine-antlr/
COPY --chown=gradle:gradle engine-jooq/ ./engine-jooq/
COPY --chown=gradle:gradle lineage/ ./lineage/
COPY --chown=gradle:gradle graph/ ./graph/
COPY --chown=gradle:gradle schema/ ./schema/
COPY --chown=gradle:gradle format/ ./format/
COPY --chown=gradle:gradle cli/ ./cli/
COPY --chown=gradle:gradle server/ ./server/
COPY --chown=gradle:gradle bench/ ./bench/
COPY --chown=gradle:gradle conformance/ ./conformance/
# installDist 产出可执行发行版（bin/ozml + lib/），不打测试（测试由 CI 的 build 轴守）。
RUN gradle --no-daemon :cli:installDist

# ── stage 3：jlink 裁剪运行时（temurin 全量 JRE ~250MB → jlink 44MB）──────────────
# jlink 只在 JDK 里有（temurin 的 jre 镜像不带 jmods），且裁剪宿主要与构建用的
# JDK 同大版本（21）。模块清单用 jdeps --print-module-deps 自动推不出来：netty 的
# OSGi manifest（Bundle-SymbolicName + Import-Package）与 guava 的 package 前缀
# 会让模块解析器直接炸（实测），所以这份清单是**实测圈定**——CLI 全部子命令
# （parse / lineage / impact / matrix / convert / validate / format / serve / mcp，
# path 无 CLI 子命令，走 /api/path 与 MCP 工具）+ SQLite 图库 + web 托管逐一跑通过。
# 新增依赖若用到新的 JDK 模块，容器冒烟会 NoClassDefFoundError——按报错补模块。
FROM eclipse-temurin:21-jdk-jammy AS jre
COPY --from=jvm /src/cli/build/install/ozml /app/ozml
RUN jlink \
      --add-modules java.base,java.logging,java.management,java.naming,java.sql,java.xml,jdk.unsupported \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /javaruntime

# ── stage 4：运行时（jlink 定制 runtime + ozml + dist，单端口）───────────────
FROM ubuntu:22.04
ENV JAVA_HOME=/javaruntime \
    PATH="/javaruntime/bin:${PATH}" \
    LANG=C.UTF-8
WORKDIR /app
COPY --from=jre /javaruntime /javaruntime
COPY --from=jvm /src/cli/build/install/ozml /app/ozml
COPY --from=web /src/web/dist /app/web/dist
EXPOSE 8765
ENTRYPOINT ["/app/ozml/bin/ozml", "serve", "--host", "0.0.0.0", "--web-root", "/app/web/dist"]
