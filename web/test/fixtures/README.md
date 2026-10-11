# 契约夹具（golden fixtures）

来自**真实后端输出**（web 设计稿 §5.2：手写镜像 + 夹具双保险）。
更新流程（契约变更时重新生成）：

```bash
# 1. 本地构建并启动后端
./gradlew :cli:installDist
cli/build/install/ozml/bin/ozml serve --port 8765 &

# 2. 重新抓取（good/bad/unknowns SQL 文本见本目录下各文件对应的输入）
curl -s http://127.0.0.1:8765/api/engines > web/test/fixtures/engines.json
curl -s -X POST http://127.0.0.1:8765/api/parse -H 'Content-Type: application/json' \
  -d '{"sql": <good SQL>}' > web/test/fixtures/parse-report.json
# …其余端点同理（lineage 用同样的请求体）
```

| 文件 | 端点 | 输入 |
|---|---|---|
| `engines.json` | `GET /api/engines` | — |
| `parse-report.json` | `POST /api/parse` | 电商示例（含 join/group by），jsqlparser |
| `parse-report-calcite.json` | `POST /api/parse` | 同上电商示例，engine=`calcite`（AST 对比 T4 夹具） |
| `parse-report-jooq.json` | `POST /api/parse` | 同上电商示例，engine=`jooq`（AST 对比 T4 夹具） |
| `parse-report-error.json` | `POST /api/parse` | `SELEC form where`（整段语法错误） |
| `lineage-model.json` | `POST /api/lineage` | 同上电商示例 |
| `lineage-model-unknowns.json` | `POST /api/lineage` | 无 schema 的无限定列 SQL |
| `lineage-graph-aggregate.json` | `POST /api/lineage` `{"aggregate": true}` | `SELECT a FROM t; SELECT b FROM t`（多语句合并图，含表级哨兵） |
| `format-report.json` | `POST /api/format` | 同上电商示例（缺省多行排版，无诊断） |
| `format-report-comment.json` | `POST /api/format` | `select a /* keep me */ from t`（含注释 → `jsqlparser.comment_dropped` WARNING） |
| `format-report-skipped.json` | `POST /api/format` | `select from where`（语法错误 → 无 `sql` + `format.skipped` ERROR，形状恒定） |

`contract.test.ts` 会对每个夹具做 zod 运行时校验——契约加字段 / 加枚举值 /
改判别字段时测试会红，提示同步 `src/types/`。
