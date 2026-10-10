# 第三方依赖与许可证登记

本项目以 Apache-2.0 发布。第三方依赖的许可证**不因此改变**，本文件用于逐条登记与说明。

许可证以**上游 POM / LICENSE 原文**为准（登记时逐条核对，2026-10-08）。

## 运行时依赖

| 依赖 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| [Kotlin](https://github.com/JetBrains/kotlin/blob/v2.4.20/license/LICENSE.txt)（stdlib，由 Kotlin JVM 插件带入） | 2.4.20 | Apache-2.0 | 语言运行时 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization/blob/v1.11.0/LICENSE.txt)（json / core） | 1.11.0 | Apache-2.0 | `ir` 契约的 JSON 编解码（CLI 与将来的 HTTP 共用） |
| [JSqlParser](https://github.com/JSQLParser/JSqlParser) | 5.4 | **LGPL-2.1 / Apache-2.0 双许可（二选一）** | 解析主力（`engine-jsqlparser`） |
| [Clikt](https://github.com/ajalt/clikt) | 5.1.0 | Apache-2.0 | CLI 框架（`cli`） |
| [Mordant](https://github.com/ajalt/mordant)（Clikt 传递） | 3.0.2 | Apache-2.0 | 终端渲染 |
| [colormath](https://github.com/ajalt/colormath)（Mordant 传递） | 3.6.0 | MIT | 终端颜色计算 |
| [JNA](https://github.com/java-native-access/jna)（Mordant 传递） | 5.14.0 | LGPL-2.1-or-later / Apache-2.0 双许可 | 终端原生调用 |
| [JGraphT core](https://github.com/jgrapht/jgrapht)（`graph`） | 1.5.3 | **LGPL-2.1 / EPL-2.0 双许可（二选一）** | 图算法（强连通分量 / 最短路 / 邻接结构） |
| [JHeaps](https://github.com/d-michail/jheaps)（JGraphT 传递） | 0.14 | Apache-2.0 | 堆 / 优先队列（JGraphT 内部） |
| [Apfloat](https://github.com/mtommila/apfloat)（JGraphT 传递） | 1.14.0 | MIT | 任意精度浮点（JGraphT 内部） |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)（`graph`） | 3.53.4.0 | Apache-2.0 | 血缘图库的 SQLite 持久化（`ozml lineage --graph` / `ozml impact --graph`） |
| [Apache Calcite](https://github.com/apache/calcite)（`engine-calcite`） | 1.42.0 | Apache-2.0 | **方言转换主力**（`ozml convert`）：`SqlDialect` 渲染 + 按方言配解析器 + 语义模型（ADR-0004 / 2026-10-10 引入 classpath） |
| [calcite-server](https://github.com/apache/calcite)（Calcite 同仓同版本） | 1.42.0 | Apache-2.0 | Calcite 的 DDL 扩展解析器（`SqlDdlParserImpl`——CREATE TABLE / VIEW 等不在 calcite-core 默认语法，probe 实测）；2026-10-10 引入 |
| [calcite-linq4j](https://github.com/apache/calcite)（Calcite 同仓同版本） | 1.42.0 | Apache-2.0 | Calcite 传递依赖（与 calcite-core 同仓发布） |
| [Avatica core / metrics](https://github.com/apache/calcite-avatica)（Calcite 传递） | 1.28.0 | Apache-2.0 | Calcite 传递依赖（JDBC 框架，本项目不用其 JDBC 能力） |
| [Guava](https://github.com/google/guava) | 33.4.8-jre | Apache-2.0 | Calcite 的 Google Guava（JVM 工具集）；未破坏封装， CALCITE 私有类型（`SqlNode`/`SqlDialect`/`Lex`）不出本模块公共签名 |
| [jOOQ](https://github.com/jOOQ/jOOQ)（`engine-jooq`） | 3.21.9 | Apache-2.0 | **方言转换第二实现**（`ozml convert --engine jooq`，OSS edition 12 方言 + DEFAULT；ADR-0004 / 2026-10-10 引入 classpath） |
| [r2dbc-spi](https://github.com/r2dbc/r2dbc-spi)（jOOQ 传递） | 1.0.0.RELEASE | Apache-2.0 | jOOQ 唯一传递依赖（R2DBC 反应式 SPI，本项目只用 jOOQ 的 parser / render，不触碰该 API） |
| [reactive-streams](https://github.com/reactive-streams/reactive-streams-jvm)（jOOQ 传递） | 1.0.3 | **CC0 1.0** | jOOQ 传递依赖（r2dbc-spi 的 API 契约）。CC0 = 公有领域等价，不带署名义务；以**未修改的独立依赖**方式使用，不内联不 fork |
| [Ktor Server](https://github.com/ktorio/ktor)（core / netty / content-negotiation / serialization-kotlinx-json，`server`） | 3.5.2 | Apache-2.0 | 本地 HTTP `/api`（`ozml serve`）；锁定 3.5.2 与 ADR-0002 的版本口径一致 |
| [Netty](https://github.com/netty/netty)（Ktor 传递，server 引擎） | 4.2.16.Final | Apache-2.0 | Ktor Netty 引擎（`ozml serve`） |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)（Ktor 传递） | 1.11.0 | Apache-2.0 | Ktor 运行时依赖 |
| [kotlinx-io](https://github.com/Kotlin/kotlinx-io)（Ktor 传递） | 0.9.1 | Apache-2.0 | Ktor 运行时依赖 |
| [JetBrains annotations](https://github.com/JetBrains/java-annotations)（kotlin-stdlib 传递） | 13.0 | Apache-2.0 | 编译期注解 |
| [checker-qual / error-prone annotations / j2objc-annotations / jspecify / failureaccess / listenablefuture（Guava 传递）](https://github.com/google/guava/wiki/UseGuavaInYourBuild) | 3.10.0 / 2.36.0 / 3.0.0 / 1.0.0 / 1.0.3 / 9999.0-empty | Apache-2.0（checker-qual 为 MIT） | Guava / Calcite 的编译注解与工具传递依赖 |

> Calcite 系运行时依赖的许可证均在上游 POM 核对（2026-10-10）：calcite-core / calcite-linq4j / avatica / guava 及其注解类传递依赖全为 Apache-2.0（checker-qual 为 MIT）；`calcite-core` 的 POM 同时声明 slf4j-api（MIT，见上表 SLF4J 行，版本 2.0.20 由 slf4j-nop 对齐）。
| [SLF4J](https://github.com/qos-ch/slf4j)（slf4j-nop，`server`） | 2.0.20 | MIT | 静默 Netty / Ktor 启动时的「No SLF4J providers were found」告警；本地工具无日志需求，绑空实现是有意取舍（2026-10-10 开发记录踩坑 8） |

### JSqlParser 许可证说明（重要）

上游 5.4 的 POM 同时列出两份许可证，上游 README 明确写「Dual licensed under LGPL 2.1 or the Apache License, Version 2.0. **Take your pick.**」（[README@jsqlparser-5.4](https://github.com/JSQLParser/JSqlParser/blob/jsqlparser-5.4/README.md)）。
**本项目择 Apache-2.0 分支**，因此以**未修改的独立依赖**方式使用即可，无需 LGPL 的替换义务。
若将来要 fork 或内联改写，必须重新评估。

> 说明：该判定依据为上游 POM 双 `<license>` 节点 + README 声明 + 两份 LICENSE 全文（`LICENSE_APACHEV2` / `LICENSE_LGPLV21`）；jar 内不含许可文件，故不能只看 jar。注意上游现主推另一坐标（Manticore 构建），本项目用的是 `com.github.jsqlparser:jsqlparser`，**换坐标必须重新核许可证**。

### JGraphT 许可证说明（重要）

上游 1.5.3 的 POM 同时列出 **LGPL-2.1** 与 **EPL-2.0** 两份许可证（[jgrapht-core-1.5.3.pom](https://repo1.maven.org/maven2/org/jgrapht/jgrapht-core/1.5.3/jgrapht-core-1.5.3.pom)）。
**本项目择 EPL-2.0 分支**（与 JSqlParser 的 Apache-2.0 选择同理：优先非 copyleft 分支），以**未修改的独立依赖**方式使用，且 JGraphT 类型**全部封装在 `graph` 模块的 `internal` 实现里**（公共签名零 JGraphT 泄漏），
因此既不构成衍生作品、也不影响本项目的 Apache-2.0 授权。

> 与 ADR-0007「优先 Apache-2.0 以最大化内网采用」的口径**存在张力**：EPL-2.0 是弱 copyleft（文件级），本项目未修改其源码、只做依赖引用，故可用；但若将来要 fork JGraphT 或把它内联进本仓库，必须重新评估并走 ADR。这是引入 JGraphT 时明确记录下来的代价。

### sqlite-jdbc 说明

`sqlite-jdbc` 自身为 Apache-2.0（[POM](https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.pom)）；其内嵌的 SQLite 内核属 **Public Domain**。本项目以**未修改的独立依赖**方式使用（JDBC 驱动），不内联、不 fork。

## 前端运行时依赖（`web/`，2026-10-10 随 M4 引入）

打进 `web/dist` 的浏览器端依赖（版本以 `web/package-lock.json` 为准，许可证逐条核对上游）：

| 依赖 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| [React / React DOM](https://github.com/facebook/react) | 19.3.0 | MIT | UI 框架 |
| [@xyflow/react（React Flow）](https://github.com/xyflowxyflow/xyflow) | 12.12.0 | MIT | 血缘图画布（节点/边/平移缩放） |
| [@dagrejs/dagre](https://github.com/dagrejs/dagre) | 3.1.1 | MIT | 血缘图分层布局（LR），可替换抽象见 `web/src/graph/layout.ts` |
| [Monaco Editor](https://github.com/microsoft/monaco-editor) | 0.57.0 | MIT | SQL 编辑器内核（本地打包 + 本地 worker，不引 CDN，符合离线红线） |
| [Zustand](https://github.com/pmndrs/zustand) | 5.0.15 | MIT | 轻量全局状态 |
| [Zod](https://github.com/colinhacks/zod) | 4.6.5 | MIT | 契约夹具的运行时校验（`web/test/fixtures/`） |

前端**构建期**依赖（Vite / TypeScript / Tailwind / Vitest 等，devDependencies）不进入产物，不逐条登记；如上游许可证扫描要求再补。

## 测试期依赖（不打包进产物）



| 依赖 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| [kotlin-test](https://github.com/JetBrains/kotlin/blob/v2.4.20/license/LICENSE.txt) | 2.4.20 | Apache-2.0 | 单元测试 |
| [JUnit Jupiter](https://www.eclipse.org/legal/epl-2.0/)（`kotlin-test-junit5` 传递） | 5.10.1 | EPL-2.0 | 测试运行器 |
| [H2](https://h2database.com/html/license.html) | 2.3.232 | **MPL 2.0 / EPL 1.0 双许可（二选一）** | `schema` 的 `JdbcSchemaProvider` 实测（仅测试期，不打包） |
| [Ktor Server Test Host](https://github.com/ktorio/ktor) | 3.5.2 | Apache-2.0 | `server` 的 `/api` 端点测试（仅测试期，不打包） |

catalog 里锁定、但尚未进入 classpath 的库（jOOQ、JMH、ANTLR 等）不在本表登记。真正添加时再补行，并以上游许可证原文为准。

## 登记规则

1. 新增依赖时同步补一行：名称、版本、许可证、为什么需要它。
2. 许可证以**上游仓库/POM 原文**为准，不采信二手资料；登记时附上游许可证链接。
3. 非 Apache-2.0 / MIT / BSD 系的依赖，需在 PR 描述里单独说明使用方式。
4. 传递依赖同样适用；发布前跑一次 SBOM（CycloneDX）核对。
