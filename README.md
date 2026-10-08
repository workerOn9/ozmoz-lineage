# ozmoz-lineage

**渗透血缘** —— 面向 JVM 生态的离线 SQL 解析 / 血缘 / 方言对照工具链。

> 状态：**构建骨架已落地**（多模块 Gradle 可编译；引擎与血缘尚未接入）。

## 它要解决什么

在 JVM 服务或 CI 里，一条命令把一整个 SQL 目录解析成**列级血缘图**，并导出 [OpenLineage](https://openlineage.io/)；方言不认的地方，**明确告诉你哪个引擎能认**——而不是给一个猜测的结果。

- **面向 JVM**：不引入 Python 运行时，不需要商业授权。
- **离线优先**：解析与血缘全部本地完成，SQL 与元数据不出内网。
- **图级血缘**：多语句 / 全库 / DDL / CTAS / VIEW / MERGE，支持上溯、下溯与影响面分析。
- **标准输出**：OpenLineage、Mermaid、Graphviz DOT、Cypher、稳定 JSON。
- **可插拔语义层**：物理表 → 指标 → API → 报表 那一层，用插件接入。

## 命令行（规划中）

```bash
ozml lineage -f sql/ --format mermaid       # 目录级血缘 → Mermaid
ozml impact  --on db.t.c --depth 3          # 影响面分析
ozml convert --from mysql --to postgresql   # 方言转换
```

`ozml` = **ozmoz** + **lineage**。命令在 M0 落地。当前可运行的是构建本身：

```bash
./gradlew build
```

## 设计原则

- **Never wrong**：无法从权威来源推导的绑定或类型，留空或标 `unknown`，绝不猜。
- **Lossless**：解析不丢信息——每个 token 保留精确 span，标识符保留原始大小写与引号形式。

## 名字

`ozmoz` 取自 **osmosis（渗透）**：数据像渗透一样，从物理表渗过指标、API，一直渗到报表。`lineage` 是对它的限定。

## 许可

Apache-2.0，见 [LICENSE](LICENSE)。第三方依赖与其许可证登记在 [THIRD-PARTY.md](THIRD-PARTY.md)。

## 参与

项目处于极早期，接口与结构都会变。若对 SQL 血缘 / JVM 生态这个方向感兴趣，欢迎先开 issue 聊，不必等代码齐了再提 PR。见 [CONTRIBUTING.md](CONTRIBUTING.md)。
