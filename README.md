# ozmoz-lineage

**渗透血缘** —— 面向 JVM 生态的离线 SQL 解析 / 血缘 / 方言对照工具链。

> 状态：**列级血缘 + 图算法 + 导出 + 全库落库 + HTTP API + Web UI 起步 + 方言转换主力 + schema 语义校验 + SQL 排版 + MCP server 已落地**（`ir` / `engine-api` 契约 + `engine-jsqlparser` 适配器 + `engine-calcite` 适配器 + `lineage` 列解析与六类边 + `schema` 的 `SchemaProvider` SPI 与 DDL/JDBC 实现 + `graph` 图算法与 SQLite 模型库 + `format` 导出器 + `ozml parse` / `ozml lineage` / `ozml impact` / `ozml convert` / `ozml validate` / `ozml format` / `ozml serve`（Ktor `/api` + `web/` 静态托管）/ `ozml mcp`（stdio 工具面）可运行；Web UI 已交付三栏起步版，AST 并排 diff 与 Tauri 壳未做）。

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
ozml matrix   --corpus ./conformance/corpus --out ./docs/compat-matrix.json
ozml matrix   --convert --corpus ./conformance/corpus  # 方言转换矩阵：20 组方言对 × 渲染引擎（diff 视图数据）
ozml serve    --port 8765                              # 本地 HTTP：/api/health|engines|parse|lineage|validate|format|impact|path
ozml serve    --port 8765 --graph-root graphs           # 另开「图库浏览」：/api/graphs + lineage/impact/path 的 graph 字段（ADR-0015）
ozml parse   --engine calcite -f q.sql                # Calcite 按方言配解析器（反引号 / 方括号 / 双引号按方言路由）（当前可用）
ozml convert --from mysql --to postgresql -f q.sql    # 方言转换：parse→render→re-parse 等价门禁，不等价不输出（当前可用）
ozml convert --engine jooq --from mysql --to trino -f q.sql  # jOOQ 第二实现（12 个 OSS 关系库方言；当前可用）
ozml validate --schema ddl.sql -f q.sql               # schema 语义校验：未知表 / 未知列 / 歧义给权威诊断（当前可用）
ozml format   -f q.sql                                 # SQL 排版：子句换行 + 规范化（不换方言；注释丢失如实告警）（当前可用）
ozml format   -f q.sql --single-line                   # 规范化单行（关键字大写、空白归一）
ozml mcp                                               # MCP server（stdio）：把能力作为工具暴露给 AI 助手
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

`ozml lineage` 输出列级血缘模型（每格可溯源）并由**两个引擎**共同提供语义模型：`jsqlparser`（解析主力）与 `calcite`（方言转换主力，`ozml matrix` 各占一列，方言不认的地方看另一列能不能认——两引擎合并实测覆盖 190/200 条语料，双失败只剩方言专属语法盲区）。共**六类边**：`OUTPUT`（输出列的值从哪来）、`PREDICATE`（WHERE / HAVING 引用，只影响结果集）、`JOIN_KEY`（`a.id = b.id` 连接键传递）、`GROUP_BY`（分组引用）、`ORDER_BY`（排序引用）、`SOURCE`（表级血缘：表 / CTE 作为整体被引用）。每条边带 `TransformKind`（`DIRECT` / `EXPRESSION` / `AGGREGATE` / `WINDOW` / `CASE_BRANCH` / `CONSTANT` / `JOIN_KEY` / `FILTER_PREDICATE` / `GROUPING` / `ORDERING` / `SOURCE`）：

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
curl -s localhost:8765/api/validate -H 'Content-Type: application/json' \
    -d '{"sql": "SELECT a FROM t", "schema": "CREATE TABLE t (a INT)"}'
```

端点：`GET /api/health`、`GET /api/engines`（引擎能力路由表）、`POST /api/parse`（归一化树，与 `ozml parse --format json` 同形）、`POST /api/lineage`（`LineageModel`，仅接受一条可建模语句；可选 `format` 走导出器、`aggregate: true` 走合并图，见下）、`POST /api/validate`（结合 schema 的语义校验，与 `ozml validate` 同形）、`POST /api/format`（SQL 排版，与 `ozml format --json` 同形）、`POST /api/impact` / `POST /api/path`（图查询，响应与 `ozml impact --format json` 同形）、`POST /api/graphs`（列出可浏览的图库，**仅 `--graph-root` 启动时存在**）。**解析失败不是 HTTP 错误**：语法错误以 200 + 结构化 `unknown` 返回（错误是数据）；仅请求本身非法（缺字段、未知引擎、坏 DDL、引擎不支持某能力）才 4xx。

`POST /api/lineage` 另有两个可选字段：`format`（导出格式 id，与 CLI `--format` 同名：mermaid / dot / cypher / openlineage / ozmoz-json；缺省或 `"json"` 回 `LineageModel`）与 `aggregate: true`（opt-in 多语句聚合，不论语句数统一回合并图 `{nodes, edges}`，与 CLI `--format graph-json` 同形；与非 json 的 `format` 互斥 → 400）。`schema` 指定时必须为内联 DDL 文本；未指定时自动从 `sql` 文本收集 `CREATE TABLE`（与 CLI 的目录行为一致）。

`POST /api/lineage` 也可用 **`graph`** 字段（与 `sql` 二选一，ADR-0015）读**已落库的整库血缘图**（`ozml lineage --graph` 的产物）：回与 `aggregate: true` **同形**的合并图 `{nodes, edges}`，这是 Web UI「本地图库」数据源的出口。只传**库名**（如 `reports.db`），相对后端 `--graph-root` 白名单根解析——绝对路径 / `..` 逃逸 / 子路径 / 非 `.db` 一律 400 `invalid_graph_ref`，库里没有 422 `graph_not_found`，两者都给或都不给 400 `invalid_request`，与非 json 的 `format` 互斥。

`POST /api/validate` 结合 schema 做语义校验（Calcite `SqlValidator`）：合法 SQL 回空 `diagnostics`，未知表 / 未知列 / 列歧义 / 函数参数个数不符等给 `calcite.validate_error`（含位置）；**形状恒定**——校验「没跑成」（未给 schema / SQL 为空 / 方言未注册）仍 200，落一条 `validate.skipped`。默认引擎 `calcite`（唯一申报校验能力的引擎）；`engine` 指向未申报该能力的引擎 → 400 `unsupported_capability`。请求体 `{"sql": "...", "schema": "CREATE TABLE ..."}`。

`POST /api/format` 排版 SQL（与 `ozml format --json` 同形 `{engine, dialect, sql, diagnostics}`）：缺省多行子句换行，`multiline: false` 产规范化单行；**形状恒定**——语法错误 / 空输入仍 200、`sql` 缺省、落一条 `format.skipped` ERROR；源文本含注释时落一条 `jsqlparser.comment_dropped` WARNING（渲染器不保留注释）。默认引擎 `jsqlparser`（唯一申报 `PRETTY_PRINT` 的引擎）；calcite / jooq 不支持排版 → 400 `unsupported_capability`。请求体 `{"sql": "..."}`。

### MCP server（`ozml mcp`）

`ozml mcp` 以 **stdio** 起 MCP（Model Context Protocol）服务端，把上面能力作为**工具**暴露给 AI 助手（Claude Desktop / Cursor 等），在宿主配置里指向 `ozml mcp` 即可：

```json
{ "mcpServers": { "ozml": { "command": "ozml", "args": ["mcp"] } } }
```

工具面（响应与对应 CLI / `/api` JSON **同形**）：`ozml_engines`（能力路由表）、`ozml_parse`、`ozml_lineage`（`aggregate: true` 多语句合并图）、`ozml_impact`、`ozml_path`、`ozml_validate`、`ozml_format`、`ozml_convert`。错误语义与 HTTP 镜像：语法错误 / unknown / 不可达是**数据**（`isError=false`）；缺参数 / 未知引擎 / 坏 DDL / 目标列不在图里才是 `isError=true`（内容为 `{error, reason}`）。stdout 是协议信道——运行期间勿把它重定向给别的消费方；v1 只做 stdio + sql 现建现查，图库与导出器有意不进工具面。

### Web UI（`web/`，M4 起步）

`web/` 是独立前端子项目（不进 Gradle 多模块）：Vite + React 19 + TypeScript + Tailwind 4 + Monaco + React Flow，三栏布局——左 SQL 编辑器，中归一化树，右列级血缘图（dagre 布局，支持上溯 / 下溯 / 短路径高亮与节点详情），底部诊断与 unknowns 面板。

仓库根目录的 `justfile` 统一编排跨工具命令（安装 just：`brew install just`；Gradle 只管 JVM 侧，默认构建不要求 Node）：

```bash
just web-install   # = npm --prefix web ci（按锁文件装依赖）
just web-check     # 前端闸：typecheck + 单测
just web-build     # 产出 web/dist
just serve         # 构建前端 + ozml serve --web-root web/dist（单端口托管）
```

```bash
# 开发：前端 dev server（/api 代理到 8765）+ 后端
ozml serve --port 8765 &
cd web && npm install && npm run dev      # http://localhost:5173

# 生产形态：单端口同托管（ozml serve 直接托管 web/dist）
cd web && npm run build
ozml serve --port 8765 --web-root web/dist   # 打开 http://localhost:8765

# Docker 一体化（前端构建 + JVM 构建 + JRE 运行时，单镜像单端口）
docker build -t ozmoz-lineage .
docker run --rm -p 8765:8765 ozmoz-lineage
```

前端类型与 Kotlin `@Serializable` 契约的同步靠「手写 TS 镜像 + golden 夹具」双保险（`web/src/types/` + `web/test/fixtures/`，`npm run test` 校验）。

### 部署到 Vercel（双服务：`web` 静态 + `server` 容器）

**图库浏览是 opt-in 能力**（ADR-0015）：`ozml serve --graph-root <目录>` 开启后才存在 `POST /api/graphs`，且 `/api/impact` / `/api/path` / `/api/lineage` 才接受 `graph` 字段；**不给该参数则能力整体关闭**（一律 400 `unsupported_capability`）。原因：本服务默认 `--host 0.0.0.0` 且无鉴权，公开部署（`Dockerfile.vercel`）暴露「按名打开文件」等于开放服务端文件系统探测面——所以线上**有意不带**这个参数，仅本地使用。路径防护：库名必须是白名单根下的**纯 `.db` 文件名**（拒绝对路径、`..` 逃逸、子路径、其它后缀），规范化后仍须落在根目录树内。

仓库根目录的 `vercel.json` 把部署拆成两个服务：`web`（Vite 静态产物，CDN 分发）与 `server`（`Dockerfile.vercel` 构建的 JRE 容器，只跑 `/api/*`）。根级 rewrites 把 `/api/*` 路由到 server 服务、其余回落到 SPA——前端代码零改动（同源相对路径，与 dev 的 vite proxy 同一套语义）。`ozml serve` 在容器内读平台注入的 `PORT` 环境变量（`--port` 显式传入时优先），`--host 0.0.0.0` 由镜像 ENTRYPOINT 显式给出。

```bash
vercel          # CLI 登录后一键预览；或把仓库导入 Vercel 面板，zero-config 识别 vercel.json
```

**注意**：`ozml serve` 是无鉴权的单用户工具，Vercel 部署等于把 `/api` 公开到公网——只用于 demo / 个人试用，别把真实 SQL 或元数据贴给公网实例。本地 / 内网的离线优先形态（CLI 与一体化 Docker 镜像）不受影响。

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

### schema 语义校验（`ozml validate`）

结合 schema 做语义校验（能力位 `VALIDATE_SCHEMA`，Calcite `SqlValidator`）：未知表 / 未知列 / 列歧义 / 函数参数错误等给**权威诊断**（引擎无关的 `Diagnostic`，带行列位置）。schema 来自 `--schema`（单个 DDL 文件或目录，与 `ozml lineage --schema` 同一口径，大小写折叠 / search path 由 `SchemaProvider` 实现约定处理）。`--schema` 必填——没有校验目标就没有 validate；部分收录的 schema 会把「未收录表」如实报 `Object not found`，别拿半份 DDL 当全量校验用。

```bash
ozml validate --schema ddl.sql -f q.sql                  # output json: {engine, dialect, diagnostics}
ozml validate --schema ddl.sql --dialect mysql -f q.sql  # 方言决定解析器 quoting（反引号等）
```

诊断输出为 JSON（stdout），存在 ERROR 级诊断（含语法错误——那是可背书的诊断）时非零退出，便于 CI。校验器说不出话的语句种类（纯 DDL 等）不产诊断，不猜。

### SQL 排版（`ozml format`）

把一条 SQL 按**子句换行**重排（能力位 `PRETTY_PRINT`）。与 `ozml convert` 的本质区别：**convert 换方言，format 只重排**——内容来源是引擎自己的规范化渲染（关键字大写、空白归一），本项目**不重写渲染器**，只在规范文本上插换行，因此 `parse → format → re-parse` 语义等价、一个字符都不丢。

```bash
ozml format -f q.sql                    # 多行排版（子句换行）
ozml format -f q.sql --single-line      # 规范化单行
ozml format -f q.sql --json             # output json: {engine, dialect, sql, diagnostics}
```

换行**上下文感知**：只在子查询里按 `SELECT` / `FROM` / `WHERE` / `GROUP BY` / `ORDER BY` / `HAVING` / `LIMIT` / `OFFSET` / 集合运算 / `JOIN` 家族换行；函数参数里的 `FROM`（如 `EXTRACT(YEAR FROM d)`）所在括号**不透明**，整段原样保留（这是纯文本切分会误伤、而结构化换行能避开的关键点）；字符串 / 带引号标识符 / `[方括号]` 内的关键字不触发换行。

**有损如实报**：JSqlParser 的词法器把注释当 `SPECIAL_TOKEN` 跳过，渲染器因此**必然丢注释**——检测到源文本含注释时落一条 `jsqlparser.comment_dropped` WARNING（带第一条注释位置，stdout 是 SQL 文本时诊断走 stderr），不静默丢。默认引擎 `jsqlparser`（唯一申报排版的引擎）；calcite / jooq 明说不支持排版 → 可解释的错。

### 方言转换矩阵（`ozml matrix --convert`）

```bash
ozml matrix --convert --corpus conformance/corpus            # 缺省 M3 的 20 组方言对
ozml matrix --convert --pairs mysql>postgresql,ansi>trino # 自定义方言对
```

- 方言对缺省 **20 组**（横跨 calcite 数仓系与 jOOQ 关系库：双 quoting、分页、大小写改写形态）；引擎不注册的（方言，对）组合**不出格**（coverage 只记实际跑的组合——jOOQ 没有的数仓方言不会在 jOOQ 列上凭空记失败）。
- 语料按 `case.dialect == 对.from` 进格；ok 格**带 `sql` 成品**（render-verified 门禁放行才出炉），失败格给截断原因（源解析 / 渲染 / 门禁拦截分开说话）。
- CI（`conformance` workflow）产出 `compat-convert.json` artifact，并在任务摘要里渲染**覆盖表 + 每方言对一条成品样本**（diff 视图样本，可见两档引擎的大小写 / quoting / 分页差异）。

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
