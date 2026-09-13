# -*- coding: utf-8 -*-
"""胜率达标扫描：为 超短/短线/中线 寻找胜率 90%~98% 的过滤组合。

方法论（避免重复扫描全缓存）：
1) 对每个周期，扫描窗口内所有「analyze_snaps 通过」的候选信号，
   一次性缓存过滤所需字段（passCount / 粘合持续 / 多头排列+三日不新低 / 板块20日涨幅 / 大盘状态）；
2) 然后在内存中对 主板开关 × 板块阈值 × passCount覆盖 组合重放过滤 + 模拟交易，
   输出每个组合的 成交数/胜率/累计收益。

长线默认豁免所有增强过滤（只验证 ST+主板基础过滤不改变其收益）。
"""
import argparse
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS  # noqa: E402
from _full_cycle_backtest import (load_cache, market_state, simulate_trade,  # noqa: E402
                                  stats, SELL_RULES)
from _pool_filters import sector_ret20, P2  # noqa: E402

PERIODS = ["超短", "短线", "中线"]
RULES_KEY = {"超短": "超短线", "短线": "短线", "中线": "中线"}
INDEXES = ["sh000001", "sz399001", "sz399006"]
DEFAULT_START = "2026-02-20"
NON_MAIN_PREFIXES = ("sh688", "sh689", "sz300", "sz301", "bj8", "sh51", "sh56", "sz15", "sz16")

# 扫描网格
THRESHOLDS = [-3.0, -1.0, 1.0]
PASS_COMBO = [
    ("无", {}),
    ("短7中7", {"短线": 7, "中线": 7}),
]
# 大盘状态门控（超短/短线/中线共用）：None=不限；safebull=排除 BEARISH/CRASH
STATE_GATES = [
    ("不限", None),
    ("排除弱市", {"BULLISH", "OSCILLATION"}),
]


def collect_candidates(cache, all_dates, date_to_idx, period, w_start, w_end):
    """收集窗口内所有 analyze_snaps 通过的候选（不套 extra_filter）。"""
    p = PARAMS[period]
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    scan_dates = [d for d in all_dates if w_start <= d < w_end]
    cands = []
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
            sub = [dict(s) for s in sub]
            sub[-1]["name"] = name
            sel_trend = st if st in ("BULLISH", "BEARISH") else ("BEARISH" if st == "CRASH" else "OSCILLATION")
            try:
                r = analyze_snaps(sub, p, sel_trend)
                if not r.get("passed"):
                    continue
            except Exception:
                continue
            checks = r.get("checks") or {}
            ck = checks.get("③粘合持续")
            sticky_ok = True if ck is None else bool(ck[0])
            short_ok = all(bool((checks.get(k))[0]) for k in ("②多头排列", "⑬三日不新低")
                           if checks.get(k) is not None)
            r20 = sector_ret20(cache, all_dates, date_to_idx, code, name, asof)
            cands.append(dict(code=code, name=name, asof=asof, idx=date_to_idx.get(asof, 0) + 1,
                              st=st, passCount=r.get("passCount", 0),
                              sticky_ok=sticky_ok, short_ok=short_ok, r20=r20))
    return cands


def replay(cache, all_dates, cands, period, main_on, threshold, pass_count, gate):
    """内存重放过滤 + 模拟交易，返回 (n, avg, wr, cum)。"""
    rule = SELL_RULES[RULES_KEY[period]]
    rets = []
    for c in cands:
        if "ST" in (c["name"] or "").upper():
            continue
        if main_on and c["code"].startswith(NON_MAIN_PREFIXES):
            continue
        if gate and c["st"] not in gate:
            continue
        need = pass_count.get(P2.get(period, period))
        if need and c["passCount"] < need:
            continue
        if period == "中线" and not c["sticky_ok"]:
            continue
        if period in ("超短", "短线") and not c["short_ok"]:
            continue
        if c["r20"] is not None and c["r20"] < threshold:
            continue
        t = simulate_trade(cache, all_dates, (c["code"], c["name"], c["asof"], c["idx"], c["st"]), rule)
        if t:
            rets.append(t["ret"])
    if not rets:
        return 0, 0.0, 0.0, 0.0
    n, avg, wr, cum, pf, mdd = stats(rets)
    return n, avg, wr, cum


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", default=DEFAULT_START)
    ap.add_argument("--end", default="")
    ap.add_argument("--months", type=int, default=0)
    args = ap.parse_args()

    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    w_end = args.end or all_dates[-1]
    if args.months:
        from datetime import date
        ed = date.fromisoformat(w_end)
        y, m = ed.year, ed.month - args.months
        y += (m - 1) // 12
        m = (m - 1) % 12 + 1
        w_start = date(y, m, ed.day).isoformat() if ed.day <= 28 else date(y, m, 1).isoformat()
    else:
        w_start = args.start
    print(f"窗口 {w_start} ~ {w_end} | 股票池 {len(cache)} 只")

    cands_all = {}
    for period in PERIODS:
        cands_all[period] = collect_candidates(cache, all_dates, date_to_idx, period, w_start, w_end)
        print(f"  [收集] {period}: analyze_snaps 通过候选 {len(cands_all[period])} 条")

    for main_on in (True, False):
        print("\n" + "=" * 104)
        print("主板开关: %s" % ("开(排除科创/创业)" if main_on else "关(含科创/创业)"))
        print("=" * 104)
        for gate_label, gate in STATE_GATES:
            print("\n  大盘门控: %s" % gate_label)
            hdr = "  %-10s %-10s | " % ("板块阈值", "pass覆盖")
            for period in PERIODS:
                hdr += "%-24s | " % period
            print(hdr)
            print("  " + "-" * 100)
            for th in THRESHOLDS:
                for label, pc in PASS_COMBO:
                    row = "  %-10s %-10s | " % (f"{th:+.1f}%", label)
                    for period in PERIODS:
                        n, avg, wr, cum = replay(cache, all_dates, cands_all[period], period,
                                                 main_on, th, pc, gate)
                        mark = " <<<" if (90.0 <= wr <= 98.0 and n >= 3) else ""
                        row += "%-24s | " % (f"n={n:>3} wr={wr:5.1f}% cum={cum:+7.1f}%{mark}")
                    print(row)


if __name__ == "__main__":
    main()
