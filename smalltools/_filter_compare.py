# -*- coding: utf-8 -*-
"""过滤项组合对比实验：定位每个硬过滤的独立效果，避免「矫枉过正」。

只跑汇总统计（不打印明细），按周周期 × 过滤组合输出 n/平均/胜率/累计。
默认窗口近半年（与 _six_month_review 一致），可 --months / --start/--end 调整。

用法：
  python _filter_compare.py                 # 全部组合 × 全部周期
  python _filter_compare.py --periods 长线  # 只看长线
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _pool_filters as pf  # noqa: E402
from _walk_forward import collect_signals_window  # noqa: E402
from _full_cycle_backtest import load_cache, simulate_trade, stats, SELL_RULES  # noqa: E402

PERIODS = ["超短", "短线", "中线", "长线"]
RULES_KEY = {"超短": "超短线", "短线": "短线", "中线": "中线", "长线": "长线"}
DEFAULT_START = "2026-02-20"

COMBOS = [
    ("旧口径(无过滤)", dict(st=False, main=False, sector=False, sticky=False, short=False)),
    ("仅ST+主板", dict(st=True, main=True, sector=False, sticky=False, short=False)),
    ("ST+主板+板块", dict(st=True, main=True, sector=True, sticky=False, short=False)),
    ("ST+主板+粘合", dict(st=True, main=True, sector=False, sticky=True, short=False)),
    ("ST+主板+短线硬", dict(st=True, main=True, sector=False, sticky=False, short=True)),
    ("ST+主板+板块+粘合", dict(st=True, main=True, sector=True, sticky=True, short=False)),
    ("全开", dict(st=True, main=True, sector=True, sticky=True, short=True)),
]


def run_combo(cache, all_dates, date_to_idx, w_start, w_end, period, flags):
    pf.set_filters(**flags)
    sigs, _ = collect_signals_window(cache, all_dates, date_to_idx, period, w_start, w_end)
    rule = SELL_RULES[RULES_KEY[period]]
    rets = []
    for sig in sigs:
        t = simulate_trade(cache, all_dates, sig, rule)
        if t:
            rets.append(t["ret"])
    n, avg, wr, cum, p, _ = stats(rets)
    return n, avg, wr, cum


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", default="")
    ap.add_argument("--end", default="")
    ap.add_argument("--months", type=int, default=0)
    ap.add_argument("--periods", default="")
    args = ap.parse_args()

    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    w_start = args.start or DEFAULT_START
    w_end = args.end or all_dates[-1]
    if args.months:
        from datetime import date
        ed = date.fromisoformat(args.end or all_dates[-1])
        y, m = ed.year, ed.month - args.months
        y += (m - 1) // 12
        m = (m - 1) % 12 + 1
        w_start = date(y, m, ed.day).isoformat() if ed.day <= 28 else date(y, m, 1).isoformat()
    periods = [p for p in PERIODS if p in args.periods.split(",")] if args.periods else PERIODS

    print(f"窗口 {w_start} ~ {w_end}")
    for period in periods:
        print("\n=== %s ===" % period)
        print(f"  {'组合':<20} {'成交':>4} {'平均':>8} {'胜率':>7} {'累计':>10}")
        for label, flags in COMBOS:
            n, avg, wr, cum = run_combo(cache, all_dates, date_to_idx, w_start, w_end, period, flags)
            print(f"  {label:<20} {n:>4} {avg:>+7.2f}% {wr:>6.1f}% {cum:>+9.2f}%")
    # 复位为默认（全开）
    pf.set_filters(st=True, main=True, sector=True, sticky=True, short=True)


if __name__ == "__main__":
    main()
