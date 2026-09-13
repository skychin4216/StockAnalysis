# -*- coding: utf-8 -*-
"""临时诊断：长线有/无2+3 买入明细差异"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _with23_backtest import (collect_day_signals, run_mode, EXCLUDE_DIR,
                              MAX_POSITIONS, SELL_RULES)
from _full_cycle_backtest import load_cache, simulate_trade

cache = load_cache()
dates = set()
for code, ent in cache.items():
    for s in ent.get("snaps", []):
        dates.add(s["date"])
all_dates = sorted(dates)
date_to_idx = {d: i for i, d in enumerate(all_dates)}

def trade_list(period, use23):
    sigs, _ = collect_day_signals(cache, all_dates, date_to_idx, period, use23)
    sigs.sort(key=lambda s: (s["buy_idx"], -s["score"]))
    max_pos = MAX_POSITIONS[period]
    active = []
    out = []
    for sig in sigs:
        buy_idx = sig["buy_idx"]
        active = [a for a in active if a[0] > buy_idx]
        if len(active) >= max_pos:
            continue
        t = simulate_trade(cache, all_dates,
                           (sig["code"], sig["name"], sig["asof"], sig["buy_idx"], sig["state"]),
                           SELL_RULES[period])
        if not t:
            continue
        end_idx = date_to_idx.get(t["sell"], buy_idx + SELL_RULES[period]["maxHold"])
        active.append((end_idx, t["ret"]))
        out.append((sig["code"], sig["name"], sig["asof"], sig["buy_idx"], t["sell"], t["ret"]))
    return out

for period in ["中线", "长线"]:
    print(f"\n=== {period} 无2+3 买入明细 ===")
    for c in trade_list(period, False):
        print(f"  {c[0]} {c[1]:<8} 买{c[2]} idx{c[3]:>4} 卖{c[4]} ret {c[5]:+.2f}%")
    print(f"=== {period} 有2+3 买入明细 ===")
    for c in trade_list(period, True):
        print(f"  {c[0]} {c[1]:<8} 买{c[2]} idx{c[3]:>4} 卖{c[4]} ret {c[5]:+.2f}%")
