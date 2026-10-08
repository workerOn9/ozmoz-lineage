# 第三方依赖与许可证登记

本项目以 Apache-2.0 发布。第三方依赖的许可证**不因此改变**，本文件用于逐条登记与说明。

| 依赖 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| [Kotlin](https://github.com/JetBrains/kotlin/blob/v2.4.20/license/LICENSE.txt)（stdlib，由 Kotlin JVM 插件带入） | 2.4.20 | Apache-2.0 | 语言运行时 |
| [kotlin-test](https://github.com/JetBrains/kotlin/blob/v2.4.20/license/LICENSE.txt) | 2.4.20 | Apache-2.0 | `ir` 的工具链冒烟测试 |
| [JUnit Jupiter](https://www.eclipse.org/legal/epl-2.0/)（`kotlin-test-junit5` 的测试期传递依赖，不打包进产物） | 5.10.1 | EPL-2.0 | 测试运行器 |

catalog 里锁定、但尚未进入 classpath 的库（Ktor、Clikt、JSqlParser、Calcite、jOOQ 等）不在本表登记。真正添加时再补行，并以上游许可证原文为准。

## 登记规则

1. 新增依赖时同步补一行：名称、版本、许可证、为什么需要它。
2. 许可证以**上游仓库原文**为准，不采信二手资料；登记时附上游许可证链接。
3. 非 Apache-2.0 / MIT / BSD 系的依赖，需在 PR 描述里单独说明使用方式。
   例：JSqlParser 为 **LGPL-2.1**，计划以**未修改的独立依赖**方式使用——不 fork、不内联改写、保持可替换。
4. 传递依赖同样适用；发布前跑一次 SBOM（CycloneDX）核对。
