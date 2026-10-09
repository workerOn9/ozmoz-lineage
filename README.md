# ozmoz-lineage

**渗透血缘** —— 面向 JVM 生态的离线 SQL 解析 / 血缘 / 方言对照工具链。

> 状态：**列级血缘 + 图算法 + 导出已落地**（`ir` / `engine-api` 契约 + `engine-jsqlparser` 适配器 + `lineage` 列解析与六类边 + `schema` 的 `SchemaProvider` SPI 与 DDL/JDBC 实现 + `graph` 图算法 + `format` 导出器 + `ozml parse` / `ozml lineage` / `ozml impact` 可运行；方言转换、Web UI 尚未接入）。

## 它要解决什么

在 JVM 服务或 CI 里，一条命令把一整个 SQL 目录解析成**列级血缘图**，并导出 [OpenLineage](https://openlineage.io/)；方言不认的地方，**明确告诉你哪个引擎能认**——而不是给一个猜测的结果。

- **面向 JVM**：不引入 Python 运行时，不需要商业授权。
- **离线优先**：解析与血缘全部本地完成，SQL 与元数据不出内网。
- **图级血缘**：多语句 / 全库 / DDL / CTAS / VIEW / MERGE，支持上溯、下溯与影响面分析。
- **标准输出**：OpenLineage、Mermaid、Graphviz DOT、Cypher、稳定 JSON。
- **可插拔语义层**：物理表 → 指标 → API → 报表 那一层，用插件接入。

## 命令行

```bash
ozml parse   -f q.sql --format json|tree|ast          # 解析 → 归一化树（当前可用）
ozml lineage -f q.sql --format json|edges|summary     # 列级血缘模型（当前可用）
ozml lineage -f q.sql --format openlineage|mermaid|dot|cypher  # 导出（当前可用）
ozml lineage -f q.sql --schema schema.sql             # 喂 DDL 元数据：物理表 * 展开、列消歧、INSERT 对齐（当前可用）
ozml impact  -f q.sql --on db.t.c --depth 3           # 上溯 / 下溯 / 影响面（当前可用）
ozml convert --from mysql --to postgresql             # 方言转换（规划中）
```

`ozml` = **ozmoz** + **lineage**。

```bash
# 从源码运行（当前可跑通）
./gradlew :cli:installDist
./cli/build/install/ozml/bin/ozml parse   -f q.sql --format json
./cli/build/install/ozml/bin/ozml lineage -f q.sql --format edges
./cli/build/install/ozml/bin/ozml lineage -f q.sql --format mermaid
./cli/build/install/ozml/bin/ozml impact  -f q.sql --on db.t.c --direction upstream
```

`ozml lineage` 输出列级血缘模型，共**六类边**：`OUTPUT`（输出列的值从哪来）、`PREDICATE`（WHERE / HAVING 引用，只影响结果集）、`JOIN_KEY`（`a.id = b.id` 连接键传递）、`GROUP_BY`（分组引用）、`ORDER_BY`（排序引用）、`SOURCE`（表级血缘：表 / CTE 作为整体被引用）。每条边带 `TransformKind`（`DIRECT` / `EXPRESSION` / `AGGREGATE` / `WINDOW` / `CASE_BRANCH` / `CONSTANT` / `JOIN_KEY` / `FILTER_PREDICATE` / `GROUPING` / `ORDERING` / `SOURCE`）：

```text
$ cat q.sql
WITH c AS (SELECT a AS x FROM s) SELECT x FROM c

$ ozml lineage -f q.sql --format edges
e0  OUTPUT  s.a -> c.x  [DIRECT]  «a»
e1  OUTPUT  c.x -> x  [DIRECT]  «x»
e2  SOURCE  s -> c.x  [SOURCE]
e3  SOURCE  c -> x  [SOURCE]
```

`GROUP_BY` / `ORDER_BY` 与 `PREDICATE` 同形：表达式引用的列 → 本作用域每个有名输出列各一条边。`SOURCE` 是**表级血缘**——每个有表身份的来源（物理表 / CTE 引用）经一个表级哨兵节点连到输出列（派生表无表身份，不发 `SOURCE` 边）。列节点还会在 `SchemaProvider` 明确收录「表 + 列」时补上 `type` / `nullable`，否则留空（不猜类型）。

`ozml lineage --format openlineage|mermaid|dot|cypher` 走 `format` 模块的导出器注册表：OpenLineage 输出 `RunEvent` + `ColumnLineageDatasetFacet`（`TransformKind` 按 OpenLineage 规范原文映射到 `DIRECT`/`INDIRECT` + 子类型），Mermaid / DOT / Cypher 输出可视图与建图语句。

`ozml impact` 在 `graph` 模块的 `LineageGraph` 上做**上溯 / 下溯 / 影响面**（`--direction upstream|downstream|both`，`--depth` 限层）与环检测；目标列不在图里时非零退出，不猜相近列。

解析失败、或引擎不支持该语句的语义提取（如 `MERGE`）时返回非零退出码，并把带位置（行列）的诊断打到 stderr——不会静默给出一个猜测的结果。

`--schema` 接一个含 `CREATE TABLE` 的 DDL 文件（`schema` 模块的 `DdlFileSchemaProvider`，另有 `StaticSchemaProvider` / `JdbcSchemaProvider` / `CompositeSchemaProvider` 可编程接入）：物理表的 `SELECT *` 按列清单展开、裸列名按「哪张表真有这一列」消歧、`INSERT INTO t SELECT …` 未声明目标列时按表列定义序对齐。元数据缺失的列一律显式记 `unknown`，不发明列名。

## 设计原则

- **Never wrong**：无法从权威来源推导的绑定或类型，留空或标 `unknown`，绝不猜。
- **Lossless**：解析不丢信息——每个 token 保留精确 span，标识符保留原始大小写与引号形式。

## 名字

`ozmoz` 取自 **osmosis（渗透）**：数据像渗透一样，从物理表渗过指标、API，一直渗到报表。`lineage` 是对它的限定。

## 许可

Apache-2.0，见 [LICENSE](LICENSE)。第三方依赖与其许可证登记在 [THIRD-PARTY.md](THIRD-PARTY.md)。

## 参与

项目处于极早期，接口与结构都会变。若对 SQL 血缘 / JVM 生态这个方向感兴趣，欢迎先开 issue 聊，不必等代码齐了再提 PR。见 [CONTRIBUTING.md](CONTRIBUTING.md)。
