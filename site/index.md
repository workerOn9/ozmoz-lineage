---
layout: home
hero:
  name: ozmoz-lineage
  text: 渗透血缘
  tagline: 面向 JVM 生态的离线 SQL 解析 / 血缘 / 方言对照工具链
  actions:
    - theme: brand
      text: 工具百科
      link: /tools/
    - theme: alt
      text: 兼容性矩阵
      link: /tools/compat
    - theme: alt
      text: GitHub
      link: https://github.com/workerOn9/ozmoz-lineage
features:
  - title: 离线优先
    details: 解析与血缘全部本地完成，SQL 与元数据不出内网；不引入 Python 运行时，也不需要商业授权。
  - title: 图级血缘
    details: 多语句 / 全库 / DDL / CTAS / VIEW / MERGE，列级六类边 + 上溯下溯与影响面分析，导出 OpenLineage。
  - title: 工具百科（数据驱动）
    details: 引擎能力、方言覆盖与两张实测矩阵全部由 CI 生成的 JSON 渲染，每格可溯源到 commit——没有手写表格。
---

## 它要解决什么

一条命令把一整个 SQL 目录解析成**列级血缘图**并导出 OpenLineage；方言不认的地方，**明确告诉你哪个引擎能认**——而不是给一个猜测的结果。

```bash
ozml lineage -f sql/ --graph ./lineage.db    # 目录 → 全库血缘图（SQLite）
ozml impact  --graph ./lineage.db --on db.t.c --depth 3
ozml convert --from mysql --to postgresql -f q.sql
ozml serve   --port 8765                     # /api + Web UI 单端口
ozml mcp                                     # MCP stdio 工具面（给 AI 助手）
```

## 工具百科

本站的表格**没有一行是手写的**：CI 每次构建都现跑 `ozml engines` 与两张矩阵，再由
`site/scripts/normalize.mjs` 规整成页面数据；原始 JSON 同时可下载（每格可溯源到 commit）。

- [引擎能力 / 方言目录](/tools/) —— `ozml engines`：声明能力、方言、不支持原因
- [兼容性矩阵](/tools/compat) —— `ozml matrix`：语料 × 引擎，失败格带原因
- [方言转换矩阵](/tools/convert) —— `ozml matrix --convert`：方言对 × 语料 × 渲染引擎，ok 格带成品 SQL

## 快速开始

```bash
# 从源码运行（需要 JDK 25）
./gradlew :cli:installDist
./cli/build/install/ozml/bin/ozml --help

# 本地生成文档站数据（矩阵 + 引擎能力表），再起站点
just docs-data
just docs-dev
```

> 状态：列级血缘 + 图算法 + 导出 + 全库落库 + HTTP API + Web UI + 方言转换 + schema 语义校验 +
> SQL 排版 + MCP server + 逐语句容错已落地；桌面壳与语义层插件在 M5 规划中。
