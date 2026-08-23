# -*- coding: utf-8 -*-
"""盈亏特征分离度对比：找出能区分 超短/短线/中线 赢亏信号的候选因子。

对每个周期，收集窗口内所有 analyze_snaps 通过的信号，计算技术特征 + 买后收益，
按 盈利/亏损 两组输出各特征均值，观察哪些特征分离度大 → 用于构造周期专属过滤节点。

特征：
  mom20 / mom60        20/60 日动量
  vs_ma10 / vs_ma20   收盘距 MA10/MA20 偏离（回踩度）
  drawdown            距摆动高点回撤（已由 analyze_snaps 给出）
  vol_ratio           量比
  chg                 信号日涨幅
  r3                  近3日涨幅
  sector20            板块近20日平均涨幅
  rel_sector          个股20日涨幅 - 板块20日涨幅（相对强度）
  ret（目标）          买后按周期卖出规则的实际收益
"""
import argparse
import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS  # noqa: E402
from _full_cycle_backtest import (load_cache, market_state, simulate_trade,  # noqa: E402
                                  SELL_RULES)
from _pool_filters import sector_ret20, P2  # noqa: E402

PERIODS = ["超短", "短线", "中线"]
RULES_KEY = {"超短": "超短线", "短线": "短线", "中线": "中线"}
INDEXES = ["sh000001", "sz399001", "sz399006"]
FEATURES = ["mom20", "mom60", "vs_ma10", "vs_ma20", "drawdown", "vol_ratio",
            "chg", "r3", "sector20", "rel_sector"]


def collect(cache, all_dates, date_to_idx, period, w_start, w_end):
    p = PARAMS[period]
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    scan_dates = [d for d in all_dates if w_start <= d < w_end]
    rows = []
    for asof in scan_dates:
        st = market_state(idx_snaps, asof, all_dates, date_to_idx)
        for code, ent in cache.items():
            if code.startswith("sh000") or code.startswith("sz399"):
                continue
            snaps = ent.get("snaps") or []
            sub = [s for s in snaps if s["date"] <= asof]
            if len(sub) < 20:
                continue
            name = ent.get("name") or code
            sub2 = [dict(s) for s in sub]
            sub2[-1]["name"] = name
            sel_trend = st if st in ("BULLISH", "BEARISH") else ("BEARISH" if st == "CRASH" else "OSCILLATION")
            try:
                r = analyze_snaps(sub2, p, sel_trend)
                if not r.get("passed"):
                    continue
            except Exception:
                continue
            closes = [s["close"] for s in sub]
            c0 = closes[-1]
            f = {}
            f["mom20"] = c0 / closes[-21] - 1 if len(closes) >= 21 else None
            f["mom60"] = c0 / closes[-61] - 1 if len(closes) >= 61 else None
            ma10 = sum(closes[-10:]) / 10
            ma20 = sum(closes[-20:]) / 20
            f["vs_ma10"] = c0 / ma10 - 1
            f["vs_ma20"] = c0 / ma20 - 1
            f["drawdown"] = r.get("drawdownPct")
            f["vol_ratio"] = r.get("volumeRatio")
            f["chg"] = sub[-1].get("changePct", 0.0)
            f["r3"] = c0 / closes[-4] - 1 if len(closes) >= 4 else None
            f["sector20"] = sector_ret20(cache, all_dates, date_to_idx, code, name, asof)
            f["rel_sector"] = (f["mom20"] - f["sector20"]) if (f["mom20"] is not None and f["sector20"] is not None) else None
            f["name"] = name
            f["code"] = code
            f["asof"] = asof
            f["st"] = st
            t = simulate_trade(cache, all_dates, (code, name, asof, date_to_idx.get(asof, 0) + 1, st),
                               SELL_RULES[RULES_KEY[period]])
            f["ret"] = t["ret"] if t else None
            if f["ret"] is not None:
                rows.append(f)
    return rows


def stats(vals):
    vals = [v for v in vals if v is not None]
    return (sum(vals) / len(vals)) if vals else float("nan")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", default="2025-08-20")
    ap.add_argument("--end", default="")
    ap.add_argument("--months", type=int, default=12)
    args = ap.parse_args()

    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    w_end = args.end or all_dates[-1]
    if args.months:
        ed = date.fromisoformat(w_end)
        y, m = ed.year, ed.month - args.months
        y += (m - 1) // 12
        m = (m - 1) % 12 + 1
        w_start = date(y, m, ed.day).isoformat() if ed.day <= 28 else date(y, m, 1).isoformat()
    else:
        w_start = args.start
    print(f"窗口 {w_start} ~ {w_end}")

    for period in PERIODS:
        rows = collect(cache, all_dates, date_to_idx, period, w_start, w_end)
        wins = [r for r in rows if r["ret"] > 0]
        losses = [r for r in rows if r["ret"] <= 0]
        print("\n" + "=" * 100)
        print("[%s] 信号 %d 笔 | 赢 %d | 亏 %d | 胜率 %.1f%%" % (
            period, len(rows), len(wins), len(losses),
            100.0 * len(wins) / len(rows) if rows else 0))
        print("=" * 100)
        print("  %-10s %12s %12s %12s   %s" % ("特征", "赢均值", "亏均值", "全体均值", "分离度(赢-亏)"))
        for f in FEATURES:
            wm = stats([r[f] for r in wins])
            lm = stats([r[f] for r in losses])
            am = stats([r[f] for r in rows])
            print("  %-10s %12.3f %12.3f %12.3f   %+8.3f" % (f, wm, lm, am, wm - lm))


if __name__ == "__main__":
    main()
