# -*- coding: utf-8 -*-
"""临时脚本：收集 2026 年 8 月全部选股信号（含板块/市值信息待补充）。用完即删。

来源：
  1) 已持久化 selected_2026-07.json（窗口 07-15~08-15），取 asof >= 2026-08-01 的信号
  2) 当前窗口 [2026-08-15, 2026-09-15) 用 collect_signals_window 实时扫描（覆盖 08-17~08-20）
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _full_cycle_backtest import load_cache  # noqa: E402
import _walk_forward as wf  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
RECORD = os.path.join(HERE, "_records", "selected_2026-07.json")
W_START, W_END = "2026-08-15", "2026-09-15"
PERIODS = ["超短", "短线", "中线", "长线"]

cache = load_cache()
all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
date_to_idx = {d: i for i, d in enumerate(all_dates)}
print("缓存 %d 只 | 交易日 %s ~ %s" % (len(cache), all_dates[0], all_dates[-1]))

# 1) 7 月窗口记录里的 8 月信号
july = json.load(open(RECORD, encoding="utf-8"))
print("\n=== selected_2026-07.json 窗口 %s ~ %s ===" % tuple(july["window"]))

# 2) 当前窗口实时扫描
print("\n=== 当前窗口 %s ~ %s 实时扫描 ===" % (W_START, W_END))
all_sigs = {}
for period in PERIODS:
    cur, dist = wf.collect_signals_window(cache, all_dates, date_to_idx, period,
                                          W_START, W_END)
    hist = [(s[0], s[1], s[2], period, "07窗口") for s in
            (sig for sig in july.get("signals", {}).get(period, []) if sig[2] >= "2026-08-01")]
    rows = list(hist) + [(s[0], s[1], s[2], period, "08窗口") for s in cur]
    all_sigs[period] = rows
    print("\n【%s】7月窗口8月部分 %d 条 + 当前窗口 %d 条 = %d 条"
          % (period, len(hist), len(cur), len(rows)))
    for code, name, asof, st, src in sorted(rows, key=lambda x: (x[2], x[0])):
        print("   %s  %s(%s)  信号日 %s  大盘 %s" % (src, name, code, asof, st))

# 汇总去重（按 code 首次出现）
print("\n=== 按股票去重汇总 ===")
uniq = {}
for period in PERIODS:
    for code, name, asof, st, src in all_sigs[period]:
        uniq.setdefault(code, []).append((period, asof))
for code in sorted(uniq):
    per = [f"{p}@{d}" for p, d in uniq[code]]
    print("   %s  %s  触发:%s" % (code, cache[code]["name"], " ".join(per)))

out = os.path.join(HERE, "_august_sigs.json")
json.dump({p: [{"code": s[0], "name": s[1], "date": s[2], "state": s[3], "src": s[4]}
               for s in rows] for p, rows in all_sigs.items()},
          open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("\n已保存 %s" % out)
