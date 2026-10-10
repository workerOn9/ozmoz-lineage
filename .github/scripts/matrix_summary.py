#!/usr/bin/env python3
"""把 compat-matrix.json 渲染成 GitHub Actions 任务摘要（Markdown）。

输入只有一个：产物 JSON 的路径（矩阵 schema 见
`conformance/src/main/kotlin/.../CompatMatrix.kt`）。输出追加到 stdout，
由调用方写入 `$GITHUB_STEP_SUMMARY`。尽力而为的展示脚本：解析失败、
字段缺失等情况降级为占位文本，不影响任务结论（矩阵的判定在 `ozml matrix`）。
"""
import json
import sys


def main(argv: list[str]) -> int:
    try:
        with open(argv[1], encoding="utf-8") as fh:
            matrix = json.load(fh)
    except Exception as exc:  # noqa: BLE001 展示脚本吞错降级为占位文本
        print(f"> 矩阵摘要渲染失败：{exc}")
        return 0

    meta = matrix.get("meta", {})
    cells = matrix.get("cells", [])
    total = len(cells)
    ok = sum(1 for c in cells if c.get("ok"))
    failed = [c for c in cells if not c.get("ok")]

    lines = []
    lines.append("## 兼容性矩阵")
    lines.append("")
    lines.append(
        "**{cases} 条语料 × {engines} 个引擎：{ok}/{total} ok**"
        "（引擎 {engineIds}，commit `{commit}`，语料 `{corpus}`）".format(
            cases=meta.get("caseCount", "?"),
            engines=meta.get("engineCount", "?"),
            ok=ok,
            total=total,
            engineIds=", ".join(sorted({c.get("engine", "?") for c in cells})) or "?",
            commit=(meta.get("commit") or "无")[:10],
            corpus=meta.get("corpusRoot", "?"),
        )
    )
    lines.append("")
    lines.append("> 失败格是产物不是事故：方言专属语法引擎吃不下时，如实留在格内（")
    lines.append("`ozml matrix` 不因失败格非零退出）。")
    lines.append("")

    # 方言 × 引擎覆盖（按 coverage schema：dialect → engine → {ok, total}）。
    coverage = matrix.get("coverage", {})
    if coverage:
        engines = sorted({e for per in coverage.values() for e in per})
        lines.append("| 方言 | " + " | ".join(engines) + " |")
        lines.append("|---" * (len(engines) + 1) + "|")
        for dialect in sorted(coverage):
            row = []
            for engine in engines:
                stat = coverage[dialect].get(engine)
                row.append("— 不适用" if stat is None else f"{stat.get('ok', 0)}/{stat.get('total', 0)}")
            lines.append(f"| `{dialect}` | " + " | ".join(row) + " |")
        lines.append("")

    if failed:
        lines.append(f"### 失败格（{len(failed)} 条，原因 truncated 40 字）")
        lines.append("")
        lines.append("| case | 方言 | 引擎 | issue |")
        lines.append("|---|---|---|---|")
        for c in sorted(failed, key=lambda c: (c.get("dialect", ""), c.get("caseId", ""))):
            reason = (c.get("reason") or "（无记录）").replace("|", "\\|")
            lines.append(
                f"| `{c.get('caseId', '?')}` | `{c.get('dialect', '?')}` "
                f"| `{c.get('engine', '?')}` | {reason} |"
            )
        lines.append("")
    else:
        lines.append("本轮无失败格。")
        lines.append("")

    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
