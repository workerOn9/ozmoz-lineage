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
| [JetBrains annotations](https://github.com/JetBrains/java-annotations)（kotlin-stdlib 传递） | 13.0 | Apache-2.0 | 编译期注解 |

### JSqlParser 许可证说明（重要）

上游 5.4 的 POM 同时列出两份许可证，上游 README 明确写「Take your pick」（二选一）。
**本项目择 Apache-2.0 分支**，因此以**未修改的独立依赖**方式使用即可，无需 LGPL 的替换义务。
若将来要 fork 或内联改写，必须重新评估。

## 测试期依赖（不打包进产物）

| 依赖 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| [kotlin-test](https://github.com/JetBrains/kotlin/blob/v2.4.20/license/LICENSE.txt) | 2.4.20 | Apache-2.0 | 单元测试 |
| [JUnit Jupiter](https://www.eclipse.org/legal/epl-2.0/)（`kotlin-test-junit5` 传递） | 5.10.1 | EPL-2.0 | 测试运行器 |

catalog 里锁定、但尚未进入 classpath 的库（Ktor、Calcite、jOOQ、JGraphT、JMH、ANTLR 等）不在本表登记。真正添加时再补行，并以上游许可证原文为准。

## 登记规则

1. 新增依赖时同步补一行：名称、版本、许可证、为什么需要它。
2. 许可证以**上游仓库/POM 原文**为准，不采信二手资料；登记时附上游许可证链接。
3. 非 Apache-2.0 / MIT / BSD 系的依赖，需在 PR 描述里单独说明使用方式。
4. 传递依赖同样适用；发布前跑一次 SBOM（CycloneDX）核对。
