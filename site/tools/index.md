# 引擎能力 / 方言目录

> 工具百科的第一页：**注册引擎的能力表**。数据来自 `ozml engines --format json`，
> 与 `GET /api/engines` 同一形状、同一构造器——本页表格由它渲染，没有手写内容。

<EngineCatalog />

## 为什么「能力表」是路由依据

本项目**不统一 AST**，四个引擎的能力天然不齐（JSqlParser 无方言概念、Calcite 是方言转换主力、
jOOQ 无公开 AST）。因此调用方**先查能力表再选引擎**，而不是撞墙后猜：

```bash
ozml engines --format text          # 人读
ozml engines --format json          # 与 /api/engines 同形
```

CLI 与 HTTP 都按这张表路由：`--tolerant` 需要 `ERROR_TOLERANT`、`ozml validate` 需要
`VALIDATE_SCHEMA`、`ozml format` 需要 `PRETTY_PRINT`；引擎没申报就给**可解释的**
`unsupported_capability`（带出上表的「不支持」原因），而不是抛一个内部异常。
