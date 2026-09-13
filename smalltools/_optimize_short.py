# -*- coding: utf-8 -*-
"""
对「短线」趋势跟随策略做参数拟合：
在 7/1-8/13 回测上网格搜索 持有天数、止盈、止损 的最优组合，
判断当前参数（持有4天/止盈+15%/止损-5%）是否合理，给出更优配置。

同时用「移动止盈（跌破MA5离场）」对比「固定止盈」。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import get_index_dir, triple_vote, parse_market_regime
from _trend_proto import trend_follow_scan
from _profit_backtest import load_cache, filter_asof, market_dir_asof
from _profit_backtest import load_cache, filter_asof, market_dir_asof

INDEXES = ["sh000001", "sz399001", "sz399006"]
START = "2026-07-01"


def ma(closes, n):
    return sum(closes[-n:]) / n if len(closes) >= n else None


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

    # 收集所有 短线 趋势跟随信号（选股日、次日买入）
    # 与 Kotlin 一致：仅 BULLISH 用趋势跟随；否则用粘合（此处粘合在短线几乎无信号，跳过）
    signals = []  # (code, buy_date, buy_idx, entry)
    for asof in scan_dates:
        tv = market_dir_asof(idx_snaps, asof)
        if parse_market_regime(tv) != "BULLISH":
            continue  # 非牛市：短线走均线粘合，不在本拟合范围
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
                passed, _, _, _, _ = trend_follow_scan(sub, tv, "short")
            except Exception:
                continue
            if passed:
                buy_idx = all_dates.index(asof) + 1
                if buy_idx >= len(all_dates):
                    continue
                buy_date = all_dates[buy_idx]
                buy_snap = next((s for s in snaps if s["date"] == buy_date), None)
                if not buy_snap or buy_snap["open"] <= 0:
                    continue
                signals.append((code, ent.get("name", code), buy_date, buy_idx, buy_snap["open"]))

    print(f"短线趋势跟随信号数(可买入): {len(signals)}")

    def run(hold, tp, sl, use_ma5_exit=False):
        rets = []
        for (code, name, buy_date, buy_idx, entry) in signals:
            snaps = cache.get(code, {}).get("snaps", [])
            exit_price, exit_reason = None, None
            closes_so_far = []
            for k in range(1, hold + 1):
                di = buy_idx + k
                if di >= len(all_dates):
                    break
                d = all_dates[di]
                day = next((s for s in snaps if s["date"] == d), None)
                if not day:
                    continue
                closes_so_far.append(day["close"])
                if day["high"] >= entry * (1 + tp / 100):
                    exit_price, exit_reason = entry * (1 + tp / 100), "止盈"
                    break
                if day["low"] <= entry * (1 + sl / 100):
                    exit_price, exit_reason = entry * (1 + sl / 100), "止损"
                    break
                # 移动止盈：跌破 MA5 离场（仅当有利润时）
                if use_ma5_exit and len(closes_so_far) >= 5:
                    m5 = sum(closes_so_far[-5:]) / 5
                    cur = day["close"]
                    if cur < m5 and cur > entry:  # 有利润且破5日线
                        exit_price, exit_reason = cur, "破MA5"
                        break
                exit_price, exit_reason = day["close"], "持有到期"
            if exit_price is None:
                continue
            rets.append((exit_price / entry - 1) * 100)
        if not rets:
            return None
        avg = sum(rets) / len(rets)
        wr = sum(1 for r in rets if r > 0) / len(rets) * 100
        cum = 1.0
        for r in rets:
            cum *= (1 + r / 100)
        return len(rets), avg, wr, (cum - 1) * 100, min(rets), max(rets)

    # 网格：固定止盈止损 vs 移动止盈
    print("\n═══ 网格搜索：固定止盈/止损 ═══")
    print(f"{'持有':<5}{'止盈':<6}{'止损':<6}{'样本':<6}{'平均':<9}{'胜率':<7}{'累计':<9}")
    for hold in [2, 3, 4, 5]:
        for tp in [5, 8, 10, 15]:
            for sl in [-3, -4, -5]:
                r = run(hold, tp, sl)
                if not r:
                    continue
                n, avg, wr, cum, mn, mx = r
                print(f"{hold:<5}{'+'+str(tp)+'%':<6}{str(sl)+'%':<6}{n:<6}{avg:+.2f}%  {wr:<6.1f}{cum:+.2f}%")

    print("\n═══ 移动止盈（有利润跌破MA5离场）+ 固定止损 ═══")
    print(f"{'持有':<5}{'止盈':<6}{'止损':<6}{'样本':<6}{'平均':<9}{'胜率':<7}{'累计':<9}")
    for hold in [3, 4, 5]:
        for tp in [8, 10, 15]:
            for sl in [-4, -5]:
                r = run(hold, tp, sl, use_ma5_exit=True)
                if not r:
                    continue
                n, avg, wr, cum, mn, mx = r
                print(f"{hold:<5}{'+'+str(tp)+'%':<6}{str(sl)+'%':<6}{n:<6}{avg:+.2f}%  {wr:<6.1f}{cum:+.2f}%")


if __name__ == "__main__":
    main()
