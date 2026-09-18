# -*- coding: utf-8 -*-
"""
事后诸葛亮分析（hindsight）—— 中/长线每满 3 个月一次
========================================================================
对每个季度（3 个月度窗口）的中/长线信号做两层复盘：
1) 收益对照：实际（walk-forward 滚动拟合参数） vs 事后最优（该季度信号网格全搜）
2) 漏选归因：季度末交易日全池扫描，
   - 找出「事后 15/30 日涨幅 ≥ 阈值」的牛股
   - 看它们是否被当前选股参数选中；未选中的 → 统计被哪些检查项挡掉
   - 结论：若漏选率高，建议放宽哪几项（如 ⑤跌幅 / ③粘合持续 / ⑦年线）

用法：
  python _hindsight_report.py              # 全部分析
  python _hindsight_report.py --quarter 1  # 只分析第 1 个季度
产出：
  _records/hindsight_Q1.json / Q2 ... （含文本摘要）
"""
import argparse
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS
from _full_cycle_backtest import load_cache, market_state, simulate_trade, stats, SELL_RULES
from _walk_forward import (PERIODS, RULES_KEY, INDEXES, STATES, RECORD_DIR,
                           HOLD_GRIDS, STREAK_GRID, NEXTDAY_GRID)

LOOKBACK = {"中线": 15, "长线": 30}      # 事后持有交易日
GAIN_TH = {"中线": 10.0, "长线": 15.0}   # 事后牛股涨幅阈值 %
SCAN_DAYS = 5                            # 季度末扫描的交易日数


def load_records():
    if not os.path.isdir(RECORD_DIR):
        return []
    files = sorted(f for f in os.listdir(RECORD_DIR)
                   if f.startswith("selected_") and f.endswith(".json"))
    recs = []
    for f in files:
        with open(os.path.join(RECORD_DIR, f), encoding="utf-8") as fh:
            recs.append(json.load(fh))
    return recs


def group_quarters(recs, size=3):
    """按窗口顺序切成季度组"""
    return [recs[i:i + size] for i in range(0, len(recs), size)]


def hindsight_best(cache, all_dates, sigs, period):
    """网格全搜该信号池的最优参数（事后诸葛亮口径），返回最优统计"""
    if not sigs:
        return None
    if period == "超短":
        cands = [dict(style="nextday", maxHold=h, tp=0.0, sl=0.0) for h in NEXTDAY_GRID["holds"]]
    elif period == "短线":
        cands = [dict(style="streak", streakDays=s, maBreak=m, maxHold=h, tp=0.0, sl=0.0)
                 for s in STREAK_GRID["streaks"] for m in STREAK_GRID["mas"] for h in STREAK_GRID["holds"]]
    else:
        g = HOLD_GRIDS[period]
        cands = [dict(style="hold", maxHold=h, tp=tp, sl=sl, tRatio=0.4)
                 for h in g["holds"] for tp in g["tps"] for sl in g["sls"]]
    rows = []
    for rule in cands:
        rets = []
        for sig in sigs:
            t = simulate_trade(cache, all_dates, tuple(sig), rule)
            if t:
                rets.append(t["ret"])
        if not rets:
            continue
        n, avg, wr, cum, pf, mdd = stats(rets)
        rows.append((avg, wr, cum, rule, n))
    if not rows:
        return None
    rows.sort(key=lambda r: (-r[0], -r[1]))
    avg, wr, cum, rule, n = rows[0]
    return dict(n=n, avg=avg, wr=wr, cum=cum, rule=rule)


def miss_analysis(cache, all_dates, date_to_idx, period, scan_dates):
    """扫描若干交易日：全池「事后牛股」中被选参数漏掉的，及挡掉的检查项"""
    p = PARAMS[period]
    look = LOOKBACK[period]
    th = GAIN_TH[period]
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    misses = []
    total_bulls = 0
    for asof in scan_dates:
        idx_pos = date_to_idx.get(asof)
        if idx_pos is None or idx_pos + look >= len(all_dates):
            continue
        st = market_state(idx_snaps, asof, all_dates, date_to_idx)
        sel_trend = st if st in ("BULLISH", "BEARISH") else ("BEARISH" if st == "CRASH" else "OSCILLATION")
        for code, ent in cache.items():
            if code.startswith("sh000") or code.startswith("sz399"):
                continue
            snaps = ent.get("snaps") or []
            by_date = {s["date"]: s for s in snaps}
            base = by_date.get(asof)
            day = by_date.get(all_dates[idx_pos + look])
            if not base or base["close"] <= 0 or not day or day["close"] <= 0:
                continue
            fwd = (day["close"] / base["close"] - 1) * 100
            if fwd < th:
                continue
            total_bulls += 1
            sub = [s for s in snaps if s["date"] <= asof]
            if len(sub) < 20:
                continue
            sub2 = [dict(s) for s in sub]
            sub2[-1]["name"] = ent.get("name") or code
            try:
                r = analyze_snaps(sub2, p, sel_trend)
            except Exception:
                continue
            if r.get("passed"):
                continue  # 牛股本就会被选到
            fail = [k for k in r.get("activeChecks", []) if not r["checks"][k][0]]
            misses.append(dict(code=code, name=ent.get("name") or code, asof=asof,
                               fwd=round(fwd, 2), fail=fail))
    return misses, total_bulls


def fmt_rule(rule):
    if rule["style"] == "nextday":
        return f"隔{rule['maxHold']}日卖"
    if rule["style"] == "streak":
        return f"连跌{rule['streakDays']}日/破{rule['maBreak']}日线/最多{rule['maxHold']}天"
    return f"持有{rule['maxHold']}天 止盈{rule['tp']}% 止损{rule['sl']}% 做T{int(rule['tRatio'] * 100)}%"


def analyze(cache, all_dates, date_to_idx, records, q_idx, quarter):
    print("\n" + "=" * 90)
    print(f"季度 Q{q_idx}  窗口 {quarter[0]['window'][0]} ~ {quarter[-1]['window'][1]}")
    print("=" * 90)
    result = {"quarter": q_idx, "window": [quarter[0]["window"][0], quarter[-1]["window"][1]]}
    for period in ["中线", "长线"]:
        sigs = [s for rec in quarter for s in rec.get("signals", {}).get(period, [])]
        trades = [t for rec in quarter for t in rec.get("trades", {}).get(period, [])]
        actual = stats([t["ret"] for t in trades])
        best = hindsight_best(cache, all_dates, sigs, period)
        print(f"\n【{period}】季度信号 {len(sigs)} | 已实现 {len(trades)} 笔")
        print(f"  实际(walk-forward) : 平均 {actual[1]:+.2f}% 胜率 {actual[2]:.1f}% 累计 {actual[3]:+.2f}%")
        if best:
            print(f"  事后最优            : 平均 {best['avg']:+.2f}% 胜率 {best['wr']:.1f}% 累计 {best['cum']:+.2f}% "
                  f"规则 {fmt_rule(best['rule'])}")
            gap = best["cum"] - actual[3]
            print(f"  拟合提升空间        : {gap:+.2f}%（事后最优-实际）")
        # 漏选归因：季度末交易日
        last_win = quarter[-1]["window"]
        scan_dates = [d for d in all_dates if d < last_win[1]][-SCAN_DAYS:]
        misses, total_bulls = miss_analysis(cache, all_dates, date_to_idx, period, scan_dates)
        sel_codes = {t["code"] for t in trades}
        bull_codes = {m["code"] for m in misses}
        print(f"  漏选归因(扫描 {scan_dates[0]}~{scan_dates[-1]} {SCAN_DAYS} 日):")
        print(f"    事后牛股(涨幅≥{GAIN_TH[period]:.0f}%)共 {total_bulls} 只/日，其中选股参数漏掉 {len(bull_codes)} 只")
        if misses:
            fail_cnt = Counter()
            for m in misses:
                for k in m["fail"]:
                    fail_cnt[k] += 1
            top_fail = fail_cnt.most_common(5)
            print(f"    主要挡掉牛股的检查项: {dict(top_fail)}")
            best_miss = sorted(misses, key=lambda m: -m["fwd"])[:5]
            for m in best_miss:
                print(f"      {m['name']} {m['asof']} 事后{m['fwd']:+.1f}% 未过: {m['fail']}")
        result[period] = dict(
            n_sigs=len(sigs), n_trades=len(trades),
            actual=dict(avg=actual[1], wr=actual[2], cum=actual[3]),
            best=best,
            miss_n=len(bull_codes), miss_total_bulls=total_bulls,
            top_fail=dict(fail_cnt.most_common(5)) if misses else {},
        )
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quarter", type=int, default=0, help="只分析第 N 个季度（0=全部）")
    args = ap.parse_args()
    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    records = load_records()
    if not records:
        print("没有找到 _records/selected_*.json，请先运行 _walk_forward.py")
        return
    quarters = group_quarters(records)
    for qi, q in enumerate(quarters, 1):
        if args.quarter and qi != args.quarter:
            continue
        r = analyze(cache, all_dates, date_to_idx, records, qi, q)
        with open(os.path.join(RECORD_DIR, f"hindsight_Q{qi}.json"), "w", encoding="utf-8") as f:
            json.dump(r, f, ensure_ascii=False, indent=1)
    print("\n报告已写入 _records/hindsight_Q*.json")


if __name__ == "__main__":
    main()
