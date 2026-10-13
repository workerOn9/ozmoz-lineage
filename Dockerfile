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

# ── stage 2：JVM 构建（JDK 25 + Gradle wrapper；temurin 25 无 jammy 变体且无
# gradle:9.8.1-jdk25 官方镜像可用——Docker Hub API/manifests 实测拿不到，改用
# wrapper 下载发行包，与 Dockerfile.vercel 同构）───────────────────────────────
FROM eclipse-temurin:25-jdk AS jvm
WORKDIR /src
# 先复制构建脚本与 wrapper，让依赖解析层可缓存。⚠️ 新增 Gradle 模块时，
# 要把模块目录加到下面第二行。
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# Gradle 发行包源：缺省官方源（CI 不传参，零行为变化）。本机在国内构建时
# 可传腾讯云镜像加速：--build-arg GRADLE_DIST_URL=https://mirrors.cloud.tencent.com/gradle/gradle-9.8.1-bin.zip
# （该镜像 zip 的 SHA-256 与官方一致，06-工程约定已核）。镜像 URL 只出现在本机
# 命令行，不进仓库默认值。
ARG GRADLE_DIST_URL=https://services.gradle.org/distributions/gradle-9.8.1-bin.zip
RUN sed -i "s|^distributionUrl=.*|distributionUrl=${GRADLE_DIST_URL}|" gradle/wrapper/gradle-wrapper.properties
COPY ir/ ./ir/
COPY engine-api/ ./engine-api/
COPY engine-jsqlparser/ ./engine-jsqlparser/
COPY engine-calcite/ ./engine-calcite/
COPY engine-antlr/ ./engine-antlr/
COPY engine-jooq/ ./engine-jooq/
COPY lineage/ ./lineage/
COPY graph/ ./graph/
COPY schema/ ./schema/
COPY format/ ./format/
COPY cli/ ./cli/
COPY server/ ./server/
COPY bench/ ./bench/
COPY conformance/ ./conformance/
# installDist 产出可执行发行版（bin/ozml + lib/），不打测试（测试由 CI 的 build 轴守）。
RUN ./gradlew --no-daemon :cli:installDist

# ── stage 3：jlink 裁剪运行时（temurin 全量 JDK → jlink ~46MB）───────────────
# jlink 需要能产运行时镜像的 JDK：temurin 25 镜像**默认不带 jmods**（25 起
# JEP 493「无 jmods 链接」已启用，jlink 直接从已安装 runtime 取——容器内实测
# 可用，产出 46MB）。裁剪宿主与构建同用 temurin 25，其基座是 Ubuntu 26.04
#（glibc 2.43，无 jammy 变体），运行时基座必须同为 26.04（glibc 不能低于构建侧）。
# 模块清单用 jdeps --print-module-deps 自动推不出来：netty 的
# OSGi manifest（Bundle-SymbolicName + Import-Package）与 guava 的 package 前缀
# 会让模块解析器直接炸（实测），所以这份清单是**实测圈定**——CLI 全部子命令
# （parse / lineage / impact / matrix / convert / validate / format / serve / mcp、
# upgrade）+ SQLite 图库 + web 托管逐一跑通过。`java.net.http` 与 `jdk.crypto.ec`
# 是 `ozml upgrade`（HttpClient / HTTPS EC）所需，容器内不用但清单与
# cli/build.gradle.kts 的 jlinkRuntime **有意保持一致**。
# 新增依赖若用到新的 JDK 模块，容器冒烟会 NoClassDefFoundError——按报错补模块。
FROM eclipse-temurin:25-jdk AS jre
COPY --from=jvm /src/cli/build/install/ozml /app/ozml
RUN jlink \
      --add-modules java.base,java.logging,java.management,java.naming,java.sql,java.xml,jdk.unsupported,java.net.http,jdk.crypto.ec \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /javaruntime

# ── stage 4：运行时（jlink 定制 runtime + ozml + dist，单端口）───────────────
FROM ubuntu:26.04
ENV JAVA_HOME=/javaruntime \
    PATH="/javaruntime/bin:${PATH}" \
    LANG=C.UTF-8
WORKDIR /app
COPY --from=jre /javaruntime /javaruntime
COPY --from=jvm /src/cli/build/install/ozml /app/ozml
COPY --from=web /src/web/dist /app/web/dist
EXPOSE 8765
ENTRYPOINT ["/app/ozml/bin/ozml", "serve", "--host", "0.0.0.0", "--web-root", "/app/web/dist"]
