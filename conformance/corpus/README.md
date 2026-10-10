# 兼容性语料（conformance corpus)

「方言兼容性矩阵」的输入：**一个 case 一个 JSON 文件**，记录一条真实 SQL 及其分层标签。
`ozml matrix --corpus ./conformance/corpus --out ./docs/compat-matrix.json` 会把它跑成
引擎 × 语料的实测矩阵（每格带失败原因前 40 字与 commit 溯源）。

当前规模：**200 条**，分层口径（M1）：8 方言（`ansi` / `mysql` / `postgresql` / `hive` /
`spark` / `trino` / `oracle` / `tsql`）× 语句类型（SELECT / INSERT / UPDATE / DELETE /
MERGE / DDL / OTHER）× 特性标签（join / subquery / cte / union / window / aggregate /
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

## 目录约定

按方言建目录（`ansi`、`mysql`、…），文件名即主题 slug（`conformance/corpus/<dialect>/<slug>.json`）。
目录布局只影响人读组织，矩阵分层以 case 里的 `dialect` 字段为准。

## 原则

- **失败是产物，不是事故**：方言专属语法（如 Trino `QUALIFY`）引擎暂时吃不下时，
  矩格里如实记录失败原因——矩阵的意义就是把能力边界摆上台面。
- **不粉饰**：新增 case 请写真实 生产 形状的 SQL，不要配着引擎现状改语法迁就。
- case 的 `comment` 用一句话说清验证意图；解释性长文放知识库（私有），不进仓库。
- 新增 case 请用**真实生产形状**的 SQL，不要为迁就某个引擎现状而改语法。
