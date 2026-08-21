# -*- coding: utf-8 -*-
"""临时：扫描本周(08-17~08-24)四个周期应选股票（复用 smalltools 引擎）。"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _full_cycle_backtest import load_cache, market_state, INDEXES
from _walk_forward import collect_signals_window

cache = load_cache()
all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
date_to_idx = {d: i for i, d in enumerate(all_dates)}
idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}

ws, we = "2026-08-17", "2026-08-24"
scan_dates = [d for d in all_dates if ws <= d < we]
print("=" * 72)
print(f"本周窗口 {ws} ~ {we} 交易日: {scan_dates}")
print("=" * 72)

# 每日大盘状态
for asof in scan_dates:
    st = market_state(idx_snaps, asof, all_dates, date_to_idx)
    print(f"  {asof} 大盘状态: {st}")

for period in ["超短", "短线", "中线", "长线"]:
    sigs, dist = collect_signals_window(cache, all_dates, date_to_idx, period, ws, we)
    print(f"\n== {period} == 信号 {len(sigs)} 大盘分布 {dict(dist)}")
    seen = set()
    for code, name, asof, idx, st in sigs:
        key = (code, asof)
        if key in seen:
            continue
        seen.add(key)
        print(f"  {name}({code})  选中日 {asof}  大盘={st}")
