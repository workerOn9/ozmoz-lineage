# 方言转换矩阵

> `ozml matrix --convert`：**方言对 × 语料 × 渲染引擎**的实测快照。
> 每个 ok 格都过了 `parse → render → re-parse` 等价门禁（不等价就不输出，如实入格）。

<ConvertMatrix />

## 口径

- **内容单一来源**：渲染一律走引擎自己的 `SqlDialect`，本项目不重写渲染器。
- **门禁拦截**：`parse → render → re-parse` 规范化后不等价 ⇒ 该格判失败、不产出 SQL（如 MSSQL 静默丢 `LIMIT` 的已知缺陷被固化成测试）。
- **只承诺实测过的方言**：Calcite 为方言转换主力，jOOQ 是 OSS 第二实现（商业 edition 方言编译期不存在，不对外预支）。
- 引擎不支持某个方言对时**不出格**；声明对数与语料里实际可跑的对数可能不等（from 方言没有 case 时不出格）。
