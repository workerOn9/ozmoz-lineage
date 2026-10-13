#!/usr/bin/env python3
"""把 quality-report.json 渲染成 GitHub Actions 任务摘要（Markdown）。

输入只有一个：产物 JSON 的路径（schema 见
`conformance/src/main/kotlin/.../QualityReport.kt`）。输出追加到 stdout，
由调用方写入 `$GITHUB_STEP_SUMMARY`。尽力而为的展示脚本：解析失败、
字段缺失等情况降级为占位文本，不影响任务结论（判定在 `ozml quality`）。
"""
import json
import sys


def pct(value):
    return "n/a" if value is None else f"{value * 100:.2f}%"


def main(argv: list[str]) -> int:
    try:
        with open(argv[1], encoding="utf-8") as fh:
            report = json.load(fh)
    except Exception as exc:  # noqa: BLE001 展示脚本吞错降级为占位文本
        print(f"> 质量报表摘要渲染失败：{exc}")
        return 0

    meta = report.get("meta", {})
    engines = report.get("engines", [])

    lines = ["## 血缘质量报表", ""]
    lines.append(
        "**语料 {cases} 条（已标注 {annotated}）× {engines} 个引擎**"
        "（commit `{commit}`，语料 `{corpus}`）".format(
            cases=meta.get("caseCount", "?"),
            annotated=meta.get("annotatedCount", "?"),
            engines=meta.get("engineCount", "?"),
            commit=(meta.get("commit") or "无")[:10],
            corpus=meta.get("corpusRoot", "?"),
        )
    )
    lines.append("")
    lines.append("| 引擎 | 解析成功率 | precision | recall | unknown 率 | 静默漏 |")
    lines.append("|---|---|---|---|---|---|")
    for e in engines:
        ok, total = e.get("parseOk", 0), e.get("parseTotal", 0)
        rate = f"{ok}/{total}" + (f"（{ok / total * 100:.1f}%）" if total else "")
        lines.append(
            "| `{engine}` | {parse} | {p} | {r} | {u} | {gaps} |".format(
                engine=e.get("engine", "?"),
                parse=rate,
                p=pct(e.get("precision")),
                r=pct(e.get("recall")),
                u=pct(e.get("unknownRate")),
                gaps=e.get("unexplainedGaps", 0),
            )
        )
    lines.append("")
    lines.append(
        "> 口径：precision / recall 只在**已标注** case 上算（覆盖 {a}/{c}）；"
        "unknown 率 = unknown 诊断数 / 列引用总数（全语料）；"
        "**静默漏** = 期望边缺失且未声明为 unknown（计划 §8「缺口全部落在 unknown」的红线）。".format(
            a=meta.get("annotatedCount", "?"), c=meta.get("caseCount", "?")
        )
    )

    # 失败格 / 缺口明细（cap 20 行，避免摘要过长）
    gaps = []
    for c in report.get("cases", []):
        for missing in c.get("missing", []):
            gaps.append((c.get("engine", "?"), c.get("caseId", "?"), missing))
        for unexpected in c.get("unexpected", []):
            gaps.append((c.get("engine", "?"), c.get("caseId", "?"), f"多产 {unexpected}"))
    fails = [(c.get("engine", "?"), c.get("caseId", "?")) for c in report.get("cases", []) if not c.get("ok")]

    if gaps:
        lines += ["", f"### 期望边缺口（{len(gaps)} 条，最多列 20）", ""]
        for engine, case, detail in gaps[:20]:
            lines.append(f"- `{engine}` · `{case}` — {detail}")
    if fails:
        lines += ["", f"### 解析失败（{len(fails)} 格，最多列 10）", ""]
        for engine, case in fails[:10]:
            lines.append(f"- `{engine}` · `{case}`")

    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
