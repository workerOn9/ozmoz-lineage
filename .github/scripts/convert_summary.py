#!/usr/bin/env python3
"""把 convert-matrix.json 渲染成 GitHub Actions 任务摘要（Markdown）。

输入只有一个：产物 JSON 的路径（schema 见
`conformance/src/main/kotlin/.../ConvertMatrix.kt`，`ozml matrix --convert` 产物）。
输出追加到 stdout，由调用方写入 `$GITHUB_STEP_SUMMARY`。尽力而为的展示脚本：
解析失败、字段缺失等情况降级为占位文本，不影响任务结论（判定在 CLI）。

视图内容：
1. 方言对 × 引擎的覆盖计数表（cases / ok / failed）；
2. 每个方言对抽 **一条 ok 格的成品 SQL** 作 sample——即「diff 视图」的最小形态：
   来源语料（sourcePath 溯源）+ 转写产物并列。
"""
import json
import sys


def first_ok(sqls):
    return next((c for c in sqls if c.get("ok") and c.get("sql")), None)


def main(argv):
    try:
        with open(argv[1], encoding="utf-8") as fh:
            matrix = json.load(fh)
    except Exception as exc:  # noqa: BLE001 展示脚本吞错降级为占位文本
        print(f"> 方言转换矩阵摘要渲染失败：{exc}")
        return 0

    meta = matrix.get("meta", {})
    cells = matrix.get("cells", [])
    total = len(cells)
    ok = sum(1 for c in cells if c.get("ok"))

    lines = []
    lines.append("## 方言转换矩阵（`ozml matrix --convert`）")
    lines.append("")
    lines.append(
        "**{cases} 条语料 × {engines} 个渲染引擎 × {pairs} 组方言对：{ok}/{total} ok**（commit `{commit}`）".format(
            cases=meta.get("caseCount", "?"),
            engines=meta.get("engineCount", "?"),
            pairs=meta.get("pairCount", "?"),
            ok=ok,
            total=total,
            commit=(meta.get("commit") or "无")[:10],
        )
    )
    lines.append("")
    lines.append("> 门禁拦截的（引擎， 方言对， 语句）组合是产物：输出前 parse→render→re-parse")
    lines.append("不等价就不出 SQL（ADR-0004）。")
    lines.append("")

    coverage = matrix.get("coverage", [])
    if coverage:
        lines.append("| 引擎 | 方言对 | ok | failed |")
        lines.append("|---|---|---|---|")
        for pair in sorted(coverage, key=lambda p: (p.get("engine", ""), p.get("from", ""), p.get("to", "")))[:60]:
            lines.append(
                "| `{engine}` | `{frm}` → `{to}` | {ok} | {failed} |".format(
                    engine=pair.get("engine", "?"),
                    frm=pair.get("from", "?"),
                    to=pair.get("to", "?"),
                    ok=pair.get("ok", 0),
                    failed=pair.get("failed", 0),
                )
            )
        lines.append("")

    # diff 视图：每个（引擎 × 方言对）抽一条 ok 格的成品。
    # cells 已按 (engine, from, to, caseId) 稳定排序（runner 保证），取首个。
    grouped: dict = {}
    for c in cells:
        key = (c.get("engine", ""), c.get("fromDialect", ""), c.get("toDialect", ""))
        grouped.setdefault(key, []).append(c)
    samples = []
    for key in sorted(grouped, key=lambda k: (k[0], k[1], k[2])):
        sample = first_ok(grouped[key])
        if sample:
            samples.append((key, sample))
    if samples:
        lines.append("### 每方言对一条成品（diff 视图样本）")
        lines.append("")
        for key, sample in samples[:40]:
            sql = (sample.get("sql") or "").strip()
            if not sql:
                continue
            lines.append(f"**`{key[0]}`：`{key[1]}` → `{key[2]}`**（样本 `{sample.get('caseId', '?')}`，语料 `{sample.get('sourcePath', '?')}`）")
            lines.append("")
            lines.append("```sql")
            for row in sql.splitlines():
                lines.append(row.replace("|", "\\|"))
            lines.append("```")
            lines.append("")

    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
