# -*- coding: utf-8 -*-
"""
长线参数敏感性实验：找出「信号稀少」根因并验证放宽方案。

现状问题（信号仅 6 个/年，全集中在 3 只股）：
  长线 active 8 项检查需过 7 项，且「距高点跌≥40%」与「站上年线+MA250上升+多头排列」
  几乎互斥 → 深跌 40% 的股票很难还站在年线上方且 MA250 仍上升。

本脚本在固定选股窗口（2025-08-15 ~ 2026-02-15）下，逐组测试参数放宽
对「信号数 / 已实现 / 平均收益 / 胜率 / 累计 / 盈亏因子」的影响。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS, get_index_dir, triple_vote

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
INDEXES = ["sh000001", "sz399001", "sz399006"]
START, END = "2025-08-15", "2026-02-15"
HOLD, TP, SL = 20, 40.0, -10.0  # 长线基准持有/止盈/止损

# 候选参数变体（均为在 PARAMS["长线"] 基础上的增量修改）
VARIANTS = {
    "V0 基准(现状)": {},
    "V1 跌40→20": {"minDrawdownPct": 20.0},
    "V2 粘合2.0→2.5": {"convergenceThreshold": 2.5},
    "V3 通过7→6": {"minPassCount": 6},
    "V4 地量0.5→0.7": {"volumeShrinkRatio": 0.7},
    "V5 跌40→20+粘合2.5": {"minDrawdownPct": 20.0, "convergenceThreshold": 2.5},
    "V6 跌40→20+通过6": {"minDrawdownPct": 20.0, "minPassCount": 6},
    "V7 跌40→20+粘合2.5+通过6": {"minDrawdownPct": 20.0, "convergenceThreshold": 2.5, "minPassCount": 6},
    "V8 全放宽": {"minDrawdownPct": 20.0, "convergenceThreshold": 2.5,
                 "minPassCount": 6, "volumeShrinkRatio": 0.7},
    "V9 年线仅牛市": {"minDrawdownPct": 20.0, "convergenceThreshold": 2.5,
                    "minPassCount": 6, "requireAboveYearLine": False,
                    "requireMA250Rising": False},
    "V10 地量可关": {"minDrawdownPct": 20.0, "convergenceThreshold": 2.5,
                    "minPassCount": 6, "requireVolumeShrink": False},
}


def load_cache():
    with open(CACHE, "r", encoding="utf-8") as f:
        return json.load(f)


def filter_asof(snaps, asof):
    return [s for s in snaps if s["date"] <= asof]


def market_dir_asof(idx_snaps_map, asof):
    dirs = []
    for secid in INDEXES:
        snaps = idx_snaps_map.get(secid, [])
        sub = filter_asof(snaps, asof)
        if len(sub) < 20:
            dirs.append("UNKNOWN")
            continue
        dirs.append(get_index_dir(None, sub))
    return triple_vote(dirs)


def collect(cache, all_dates, date_to_idx, p):
    idx_snaps = {secid: cache.get(secid, {}).get("snaps", []) for secid in INDEXES}
    scan_dates = [d for d in all_dates if START <= d <= END]
    sigs = []
    for asof in scan_dates:
        tv = market_dir_asof(idx_snaps, asof)
        for code, ent in cache.items():
            if code.startswith("sh000") or code.startswith("sz399"):
                continue
            snaps = ent.get("snaps") or []
            sub = filter_asof(snaps, asof)
            if len(sub) < 20:
                continue
            name = ent.get("name") or code
            sub = [dict(s) for s in sub]
            sub[-1]["name"] = name
            try:
                ok = bool(analyze_snaps(sub, p, tv).get("passed"))
            except Exception:
                continue
            if ok:
                sigs.append((code, name, asof, date_to_idx.get(asof, 0) + 1))
    return sigs


def simulate(cache, all_dates, sigs, hold, tp, sl):
    rets, details, open_trades = [], [], 0
    for (code, name, sig_date, buy_idx) in sigs:
        snaps = cache.get(code, {}).get("snaps") or []
        if buy_idx >= len(all_dates):
            continue
        buy_date = all_dates[buy_idx]
        buy_snap = next((s for s in snaps if s["date"] == buy_date), None)
        if not buy_snap or buy_snap["open"] <= 0:
            continue
        entry = buy_snap["open"]
        exit_price = exit_date = exit_reason = None
        for k in range(1, hold + 1):
            di = buy_idx + k
            if di >= len(all_dates):
                break
            d = all_dates[di]
            day = next((s for s in snaps if s["date"] == d), None)
            if not day:
                continue
            if day["high"] >= entry * (1 + tp / 100):
                exit_price, exit_date, exit_reason = entry * (1 + tp / 100), d, "止盈"
                break
            if day["low"] <= entry * (1 + sl / 100):
                exit_price, exit_date, exit_reason = entry * (1 + sl / 100), d, "止损"
                break
            exit_price, exit_date, exit_reason = day["close"], d, "持有到期"
        if exit_price is None:
            open_trades += 1
            details.append((name, None, None, "未了结"))
            continue
        ret = (exit_price / entry - 1) * 100
        rets.append(ret)
        details.append((name, sig_date, buy_date, exit_reason))
    return rets, details, open_trades


def stats(rets):
    if not rets:
        return (0, 0, 0, 0, 0, 0)
    wins = [r for r in rets if r > 0]
    losses = [r for r in rets if r <= 0]
    avg = sum(rets) / len(rets)
    wr = len(wins) / len(rets) * 100
    cum = 1.0
    for r in rets:
        cum *= 1 + r / 100
    cum_pct = (cum - 1) * 100
    pf = sum(wins) / abs(sum(losses)) if losses and sum(losses) != 0 else float("inf")
    return len(rets), avg, wr, cum_pct, pf, 0.0


def main():
    cache = load_cache()
    dates = set()
    for code, ent in cache.items():
        for s in (ent.get("snaps") or []):
            dates.add(s["date"])
    all_dates = sorted(dates)
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    print(f"股票池: {len(cache) - 3} 只 | K线 {all_dates[0]} ~ {all_dates[-1]}")
    print(f"选股窗口: {START} ~ {END} | 持有{HOLD}天 止盈+{TP}% 止损{SL}%\n")

    base = PARAMS["长线"]
    print(f"{'变体':<20}{'信号':<6}{'已实现':<6}{'平均':<9}{'胜率':<7}{'累计':<10}{'盈亏因子'}")
    print("-" * 70)
    results = []
    for vname, patch in VARIANTS.items():
        p = dict(base)
        p.update(patch)
        sigs = collect(cache, all_dates, date_to_idx, p)
        rets, details, open_trades = simulate(cache, all_dates, sigs, HOLD, TP, SL)
        n, avg, wr, cum, pf, _ = stats(rets)
        pf_s = "∞" if pf == float("inf") else f"{pf:.2f}"
        print(f"{vname:<20}{len(sigs):<6}{n:<6}{avg:+.2f}%  {wr:<6.1f}{cum:+.2f}%   {pf_s}")
        results.append((vname, len(sigs), n, avg, wr, cum, pf, open_trades))

    print("\n信号来源分布（V7 参考）：")
    p7 = dict(base)
    p7.update(VARIANTS["V7 跌40→20+粘合2.5+通过6"])
    sigs7 = collect(cache, all_dates, date_to_idx, p7)
    from collections import Counter
    cnt = Counter(name for (_, name, _, _) in sigs7)
    for nm, c in cnt.most_common(15):
        print(f"  {nm}: {c} 次")


if __name__ == "__main__":
    main()
