# ozmoz-lineage

**渗透血缘** —— 面向 JVM 生态的离线 SQL 解析 / 血缘 / 方言对照工具链。

> 状态：**列级血缘 + 图算法 + 导出 + 全库落库 + HTTP API + 方言转换主力已落地**（`ir` / `engine-api` 契约 + `engine-jsqlparser` 适配器 + `engine-calcite` 适配器 + `lineage` 列解析与六类边 + `schema` 的 `SchemaProvider` SPI 与 DDL/JDBC 实现 + `graph` 图算法与 SQLite 模型库 + `format` 导出器 + `ozml parse` / `ozml lineage` / `ozml impact` / `ozml convert` / `ozml serve`（Ktor `/api`）可运行；Web UI 尚未接入）。

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
ozml lineage -f sql/ --graph ./lineage.db             # 吃下一个 SQL 目录（含多语句脚本）→ 落库全库血缘图（当前可用）
ozml impact  --graph ./lineage.db --on db.t.c --depth 3  # 从库查询上溯 / 下溯 / 影响面（当前可用）
ozml impact  -f q.sql --on db.t.c --depth 3           # 或单文件现建现查（当前可用）
ozml serve   --port 8765                              # 本地 HTTP：/api/health|engines|parse|lineage|impact|path（当前可用）
ozml parse   --engine calcite -f q.sql                # Calcite 按方言配解析器（反引号 / 方括号 / 双引号按方言路由）（当前可用）
ozml convert --from mysql --to postgresql -f q.sql    # 方言转换：parse→render→re-parse 等价门禁，不等价不输出（当前可用）
ozml convert --engine jooq --from mysql --to trino -f q.sql  # jOOQ 第二实现（12 个 OSS 关系库方言；当前可用）
```

`ozml` = **ozmoz** + **lineage**。

```bash
# 从源码运行（当前可跑通）
./gradlew :cli:installDist
./cli/build/install/ozml/bin/ozml parse   -f q.sql --format json
./cli/build/install/ozml/bin/ozml lineage -f q.sql --format edges
./cli/build/install/ozml/bin/ozml lineage -f q.sql --format mermaid
./cli/build/install/ozml/bin/ozml lineage -f sql/ --graph ./lineage.db
./cli/build/install/ozml/bin/ozml impact  --graph ./lineage.db --on db.t.c --direction upstream
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

`ozml serve` 启动本地单用户 HTTP 服务（默认 `127.0.0.1:8765`，只绑回环），暴露与 CLI 同一契约的 `/api`：

```bash
ozml serve --port 8765 &
curl -s localhost:8765/api/health
curl -s localhost:8765/api/lineage -H 'Content-Type: application/json' \
    -d '{"sql": "SELECT * FROM t", "schema": "CREATE TABLE t (a INT)"}'
```

端点：`GET /api/health`、`GET /api/engines`（引擎能力路由表）、`POST /api/parse`（归一化树，与 `ozml parse --format json` 同形）、`POST /api/lineage`（`LineageModel`，仅接受一条可建模语句）、`POST /api/impact` / `POST /api/path`（图查询，响应与 `ozml impact --format json` 同形）。**解析失败不是 HTTP 错误**：语法错误以 200 + 结构化 `unknown` 返回（错误是数据）；仅请求本身非法（缺字段、未知引擎、坏 DDL）才 4xx。`schema` 指定时必须为内联 DDL 文本；未指定时自动从 `sql` 文本收集 `CREATE TABLE`（与 CLI 的目录行为一致）。

`ozml lineage -f <目录>` 递归读取目录下的 `*.sql`，并可把结果**落库**到 SQLite：

```bash
ozml lineage -f sql/ --graph ./lineage.db   # 每个文件可含多条语句；DDL / MERGE 等不产血缘的语句自动跳过
ozml impact  --graph ./lineage.db --on db.t.c
```

- 多语句：引擎用 `parseStatements` 逐条提取；纯 DDL / `MERGE` 等不可建模的语句**跳过**，只有整段语法错误才失败。输入里没有任何可建模语句时非零退出（不假装成功）。
- 落库：`graph` 模块的 `LineageStore` SPI（当前实现 `SqliteLineageStore`）把**每条语句的 `LineageModel`** 存进 `lineage.db`（Lossless：`unknown` / 诊断 / span 全保留）；`ozml impact --graph` 载入后由 `LineageGraph.of(models)` 现建图。跨文件的链（`INSERT … SELECT` → 后续 `INSERT`/`VIEW` 读该表）在库里自然缝合。
- 不带 `--graph` 时，多文件 / 多语句可用 `--format summary` 或 `--format graph-json` 直接看合并后的图。

解析失败、或引擎不支持该语句的语义提取（如 `MERGE`）时返回非零退出码，并把带位置（行列）的诊断打到 stderr——不会静默给出一个猜测的结果。

### 方言转换（`ozml convert`）

`ozml convert` 有**两档实现**（ADR-0004）：

- **Calcite 主力**（`--engine calcite`，缺省）：数仓系 + 常用库，方言 `mysql` / `postgresql` / `oracle` / `hive` / `spark` / `bigquery` / `snowflake` / `duckdb` / `trino` / `mssql`（别名 `tsql`）/ `calcite`。**只承诺注册表里实测过的方言**，`AnsiSqlDialect` 有已知 quoting 缺陷，有意未注册。
- **jOOQ 第二实现**（`--engine jooq`）：OSS edition 的 12 个可承诺开源方言（`mysql` / `mariadb` / `postgres` / `h2` / `hsqldb` / `derby` / `firebird` / `sqlite` / `duckdb` / `trino` / `clickhouse` / `yugabytedb`）+ 无方言族 `ansi`；Oracle / Snowflake / 数仓系在 jOOQ OSS **编译期不存在**（不是受限，枚举里没有），不对外预支。jOOQ 无公开 SQL AST，`parse` 只产浅树——树对比主力仍是 jsqlparser / calcite。

```bash
ozml convert --from mysql --to postgresql -f q.sql
ozml convert --from mysql --to mssql        -f q.sql    # → 门禁拦截，非零退出 + 诊断
```

输出前跑 **render-verified 门禁**：按源方言解析 → 按目标方言渲染 → 将渲染结果按目标方言的解析器配置 re-parse → 等价（canon：空白折叠 + 小写）才输出。**不等价就不输出 SQL**，非零退出并报告诊断——这档住了 Calcite unparse 的已知静默降级（如 `MssqlSqlDialect` 丢 `LIMIT`/`OFFSET`：输出能跑、语义已变）。未知方言、引擎不支持渲染同样给可解释的错，不靠异常碰运气。

`--schema` 接一个含 `CREATE TABLE` 的 DDL 文件或目录（目录取其下全部 `*.sql`；`schema` 模块的 `DdlFileSchemaProvider`，另有 `StaticSchemaProvider` / `JdbcSchemaProvider` / `CompositeSchemaProvider` 可编程接入）：物理表的 `SELECT *` 按列清单展开、裸列名按「哪张表真有这一列」消歧、`INSERT INTO t SELECT …` 未声明目标列时按表列定义序对齐。**未给 `--schema` 时从输入文本自动收集同源的 `CREATE TABLE`**（逐文件容错：解析失败的文件跳过——读取侧已有告警；显式 `--schema` 永远优先）。元数据缺失的列一律显式记 `unknown`，不发明列名。

## 设计原则

- **Never wrong**：无法从权威来源推导的绑定或类型，留空或标 `unknown`，绝不猜。
- **Lossless**：解析不丢信息——每个 token 保留精确 span，标识符保留原始大小写与引号形式。

## 名字

`ozmoz` 取自 **osmosis（渗透）**：数据像渗透一样，从物理表渗过指标、API，一直渗到报表。`lineage` 是对它的限定。

## 许可

Apache-2.0，见 [LICENSE](LICENSE)。第三方依赖与其许可证登记在 [THIRD-PARTY.md](THIRD-PARTY.md)。

## 参与

项目处于极早期，接口与结构都会变。若对 SQL 血缘 / JVM 生态这个方向感兴趣，欢迎先开 issue 聊，不必等代码齐了再提 PR。见 [CONTRIBUTING.md](CONTRIBUTING.md)。
