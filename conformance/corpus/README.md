# 兼容性语料（conformance corpus)

「方言兼容性矩阵」的输入：**一个 case 一个 JSON 文件**，记录一条真实 SQL 及其分层标签。
`ozml matrix --corpus ./conformance/corpus --out ./compat-matrix.json` 会把它跑成
引擎 × 语料的实测矩阵（每格带失败原因前 40 字与 commit 溯源）。

CI 会随每个 PR / `main` 提交跑同一命令（`.github/workflows/conformance.yml`）：
产物文件 `compat-matrix.json` 上传为任务 artifact（供将来的文档站渲染），
方言覆盖表与全部失败格渲染进 Actions 任务摘要。没有 ok 率门禁——失败格
是产物，不是事故；门禁只守结构契约（语料损坏会让 `ozml matrix` 非零退出）。

同一个 workflow 还跑 **`ozml quality`**（血缘质量报表，计划 §8 / M1 收尾）：解析成功率（按方言）、
precision / recall（只在标了 `lineage` 期望的 case 上）、unknown 率与**静默漏**计数，
产物 `quality-report.json` 同样上传 artifact 并渲染进任务摘要——**只报不卡**（阈值先作为目标展示）。

当前规模：**200 条**（其中 **24 条**标了 `lineage` 血缘期望），分层口径（M1）：8 方言（`ansi` / `mysql` /
`postgresql` / `hive` / `spark` / `trino` / `oracle` / `tsql`）× 语句类型（SELECT / INSERT / UPDATE /
DELETE / MERGE / DDL / OTHER）× 特性标签（join / subquery / cte / union / window / aggregate /
ddl / upsert / returning / 方言专属语法…）。

## case 文件格式

```json
{
  "id": "postgresql-recursive-cte",
  "dialect": "postgresql",
  "kind": "SELECT",
  "features": ["cte", "recursive"],
  "comment": "WITH RECURSIVE 递归 CTE",
  "sql": "WITH RECURSIVE r (n) AS (...) SELECT n FROM r"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 语料内唯一（重复即损坏，加载器拒绝） |
| `dialect` | ✅ | 编写该 SQL 的方言：`ansi` / `mysql` / `postgresql` / `hive` / `spark` / `trino` / `oracle` / `tsql`…（小写） |
| `kind` | — | 语句类型：`SELECT`（缺省）/ `INSERT` / `UPDATE` / `DELETE` / `MERGE` / `DDL` / `OTHER` |
| `features` | — | 特性标签列表（`cte` / `window` / `union` / `multi-statement`…），供矩阵下钻 |
| `comment` | — | 这个 case 在验证什么（一句话） |
| `sql` | ✅ | SQL 全文；多语句脚本允许分号分隔 |
| `schema` | — | `CREATE TABLE …` DDL。**只给血缘质量报表用**：有它才能把物理表 `*` 展开成具体列、给裸列消歧（缺省 null，兼容性矩阵不看它） |
| `lineage` | — | **血缘 golden 期望**（见下节）。标了它的 case 才进 precision / recall 统计 |

## 血缘 golden 期望（`lineage`）

计划 §8 要求把「血缘好不好」变成公开数字，靠的就是这个块：

```json
{
  "id": "ansi-join-inner",
  "dialect": "ansi",
  "features": ["join"],
  "sql": "SELECT o.id, u.name FROM orders o INNER JOIN users u ON o.user_id = u.id",
  "lineage": {
    "edges": [
      { "from": "orders.id", "to": "id", "kind": "OUTPUT" },
      { "from": "users.name", "to": "name", "kind": "OUTPUT" },
      { "from": "orders.user_id", "to": "users.id", "kind": "JOIN_KEY" },
      { "from": "orders", "to": "id", "kind": "SOURCE" }
    ],
    "unknowns": ["orders.legacy_code"]
  }
}
```

| 字段 | 说明 |
|---|---|
| `edges[].from` / `.to` | 端点的限定名，写法与 `ozml lineage --format edges` 的输出一致（大小写不敏感，内部折叠小写后比较） |
| `edges[].kind` | `OUTPUT` / `PREDICATE` / `JOIN_KEY` / `GROUP_BY` / `ORDER_BY` / `SOURCE`——**对拍单位是 `(from, to, kind)` 三元组** |
| `unknowns` | 声明**允许未知**的名字（列引用限定名；`*` 展开失败时可直接写来源表名）。期望边缺失若其 `from` 在这里，算「有解释的缺口」；否则报表记为**静默漏**（违反计划 §8「缺口全部落在 unknown」的红线） |

口径（`ozml quality` 的判定与 `QualityRunner` 一致）：

- **只断言写进来的边**：precision 的产出侧也只取 `edges` 里出现过的 `kind`——「没断言」不等于「判错」；
- **空期望是有意义的断言**：`"lineage": {"edges": []}` 表示「这条 case 期望**零条边、任何 kind**」
  （典型：`DELETE` 无输出列，正确结果就是零边）；
- **同一三元组重复出现只算一次**（如 `CASE` 里同一列被两个分支引用，产出多条同端点边）；
- precision / recall 只在标了 `lineage` 的 case 上算；**覆盖多少条由报表 `meta.annotatedCount` 如实公布**，
  不拿小样本冒充全量。

### 写期望的方法（重要）

**期望要按 SQL 语义手写，不要照抄引擎输出**——照抄会让 precision/recall 恒为 100%，指标就成了摆设。
本轮（2026-10-13）的做法是「逐条审计引擎输出后固化」：人工读每条 SQL 判断正确边集，
与引擎输出逐条对拍；**引擎没产出但语义上应有的边照样写进期望**（于是报表里体现为 recall 缺口 +
静默漏——那正是要暴露的东西）。当前两类已知缺口因此出现在报表里：

- `EXISTS` 子查询的谓词引用（未建模）→ `ansi-subquery-exists`；
- 选择列表里的**标量子查询**（未建模）→ `ansi-subquery-scalar`。

新增/修改期望时请沿用同一方法，并在 `comment` 里写清验证意图。


## 目录约定

按方言建目录（`ansi`、`mysql`、…），文件名即主题 slug（`conformance/corpus/<dialect>/<slug>.json`）。
目录布局只影响人读组织，矩阵分层以 case 里的 `dialect` 字段为准。

## 原则

- **失败是产物，不是事故**：方言专属语法（如 Trino `QUALIFY`）引擎暂时吃不下时，
  矩格里如实记录失败原因——矩阵的意义就是把能力边界摆上台面。
- **不粉饰**：新增 case 请写真实 生产 形状的 SQL，不要配着引擎现状改语法迁就。
- case 的 `comment` 用一句话说清验证意图；解释性长文放知识库（私有），不进仓库。
- 新增 case 请用**真实生产形状**的 SQL，不要为迁就某个引擎现状而改语法。
