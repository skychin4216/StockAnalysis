# -*- coding: utf-8 -*-
"""
对 7/1-8/13 四周期回测做补充诊断：
1. 信号时间分布（判断是否信号聚集）
2. 基准对比：同期买入全部核心股（等权）持有 N 天的平均收益，判断趋势跟随选股是否有 alpha
3. 各周期信号在 8/12 大量聚集的原因
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import PARAMS, get_index_dir, triple_vote, parse_market_regime
from _trend_proto import trend_follow_scan
from _profit_backtest import (
    load_cache, filter_asof, market_dir_asof, select, HOLD,
)

INDEXES = ["sh000001", "sz399001", "sz399006"]
START = "2026-07-01"


def main():
    cache = load_cache()
    dates = set()
    for code, ent in cache.items():
        for s in (ent.get("snaps") or []):
            dates.add(s["date"])
    all_dates = sorted(d for d in dates if d >= "2026-01-01")
    scan_dates = [d for d in all_dates if d >= START]

    idx_snaps = {}
    for secid in INDEXES:
        idx_snaps[secid] = cache.get(secid, {}).get("snaps", [])

    # 1. 信号时间分布（超短/短线 用趋势跟随）
    print("=" * 78)
    print("信号时间分布（超短/短线=趋势跟随，中/长线=粘合）")
    print("=" * 78)
    for period, p in PARAMS.items():
        by_date = {}
        for asof in scan_dates:
            tv = market_dir_asof(idx_snaps, asof)
            cnt = 0
            for code, ent in cache.items():
                if code.startswith("sh000") or code.startswith("sz399"):
                    continue
                snaps = ent.get("snaps") or []
                sub = filter_asof(snaps, asof)
                if len(sub) < 20:
                    continue
                sub = [dict(s) for s in sub]
                sub[-1]["name"] = ent.get("name") or code
                try:
                    if select(period, p, sub, tv):
                        cnt += 1
                except Exception:
                    pass
            by_date[asof] = cnt
        nonzero = {d: c for d, c in by_date.items() if c > 0}
        print(f"\n[{period}] 有信号日期数: {len(nonzero)}/{len(scan_dates)}")
        for d, c in nonzero.items():
            print(f"    {d}: {c} 只")

    # 2. 基准对比：同期买入全部核心股等权，持有 N 天
    print("\n" + "=" * 78)
    print("基准对比：同期买入全部核心股（等权）持有各周期天数的平均收益")
    print("（用于判断趋势跟随选股是否有 alpha）")
    print("=" * 78)
    for period in HOLD:
        hold = HOLD[period]
        rets = []
        for code, ent in cache.items():
            if code.startswith("sh000") or code.startswith("sz399"):
                continue
            snaps = ent.get("snaps") or []
            # 用 7/1 起每个交易日买入，持有 hold 天
            for di, d in enumerate(scan_dates):
                buy_snap = next((s for s in snaps if s["date"] == d), None)
                if not buy_snap or buy_snap["open"] <= 0:
                    continue
                exit_d = all_dates[all_dates.index(d) + hold] if all_dates.index(d) + hold < len(all_dates) else None
                if not exit_d:
                    continue
                exit_snap = next((s for s in snaps if s["date"] == exit_d), None)
                if not exit_snap:
                    continue
                rets.append((exit_snap["close"] / buy_snap["open"] - 1) * 100)
        if rets:
            print(f"[{period}] 持有{hold}天 基准样本{len(rets)} 平均{sum(rets)/len(rets):+.2f}% 胜率{sum(1 for r in rets if r>0)/len(rets)*100:.1f}%")


if __name__ == "__main__":
    main()
