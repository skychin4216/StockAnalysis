# -*- coding: utf-8 -*-
"""
AutoQuant 1:1 同步 · 第③步 DAG 选股 + 四周期回测
================================================
把第②步 DAG 选股引擎接入 _full_cycle_backtest 的回测框架
（market_state 环境路由 + sell_rule_for 卖出矩阵 + simulate_trade 交易模拟
 + stats 固定本金口径，无未来函数）。

与 legacy（原粘合链）口径一致，仅选股链不同：
  legacy: analyze_snaps + extra_filter（粘合链硬过滤）
  dag   : pool_filter(ST/创业/科创/指数剔除) + analyze_snaps（DAG 选股核心链，
          数据敏感节点 financial_health/smart_money_filter/news_guard/... 留接口跳过）

用法：
  python dag_backtest.py --period 短线 --engine dag
  python dag_backtest.py --period all --engine both   # dag vs legacy 并列对比
"""
import argparse
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _full_cycle_backtest import (  # noqa: E402
    INDEXES, WINDOWS, collect_signals as collect_legacy_signals,
    load_cache, filter_asof, market_state, simulate_trade, sell_rule_for, stats,
)
from backtest_guangmo import analyze_snaps  # noqa: E402
import dag_selection as DAG               # noqa: E402

CANON = {"超短": "超短线", "短线": "短线", "中线": "中线", "长线": "长线"}


def collect_dag_signals(cache, all_dates, date_to_idx, period, engine="dag"):
    """DAG 选股信号收集：pool_filter + analyze_snaps（不经过 legacy extra_filter）"""
    start, end, p = WINDOWS[CANON[period]]
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    scan_dates = [d for d in all_dates if start <= d <= end]
    sigs, state_counter = [], Counter()
    for asof in scan_dates:
        st = market_state(idx_snaps, asof, all_dates, date_to_idx)
        state_counter[st] += 1
        for code, ent in cache.items():
            if not (ent.get("snaps") or []):
                continue
            if engine == "dag" and not DAG.is_pool_eligible(code, ent.get("name", "")):
                continue
            name = ent.get("name") or code
            sub = filter_asof(ent["snaps"], asof)
            if len(sub) < 20:
                continue
            sub = [dict(s) for s in sub]
            sub[-1]["name"] = name
            # CRASH（暴跌期）视为 BEARISH 选股，用最保守参数
            sel_trend = st if st in ("BULLISH", "BEARISH") else (
                "BEARISH" if st == "CRASH" else "OSCILLATION")
            try:
                r = analyze_snaps(sub, p, sel_trend)
                ok = bool(r.get("passed"))
            except Exception:
                continue
            if not ok:
                continue
            sigs.append((code, name, asof, date_to_idx.get(asof, 0) + 1, st))
    return sigs, state_counter


def run_period(cache, all_dates, date_to_idx, period, engine):
    if engine == "legacy":
        sigs, state_counter = collect_legacy_signals(
            cache, all_dates, date_to_idx, CANON[period])
    else:
        sigs, state_counter = collect_dag_signals(
            cache, all_dates, date_to_idx, period, engine)
    rule = sell_rule_for(CANON[period])
    trades = []
    for sig in sigs:
        t = simulate_trade(cache, all_dates, sig, rule)
        if t:
            trades.append(t)
    rets = [t["ret"] for t in trades]
    n, avg, wr, cum, pf, mdd = stats(rets)
    return dict(period=period, engine=engine, sigs=len(sigs), trades=trades,
                state_counter=state_counter, n=n, avg=avg, wr=wr,
                cum=cum, pf=pf, mdd=mdd)


def _fmt_pf(pf):
    return "∞" if pf == float("inf") else f"{pf:.2f}"


def main():
    ap = argparse.ArgumentParser(description="DAG 选股 + 四周期回测")
    ap.add_argument("--period", default="all", choices=["all"] + list(CANON))
    ap.add_argument("--engine", default="both", choices=["dag", "legacy", "both"])
    args = ap.parse_args()

    cache = load_cache()
    dates = set()
    for ent in cache.values():
        for s in (ent.get("snaps") or []):
            dates.add(s["date"])
    all_dates = sorted(dates)
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    print(f"股票池: {len(cache) - len(INDEXES)} 只 | K线 {all_dates[0]} ~ {all_dates[-1]} "
          f"({len(all_dates)} 交易日)")
    print(f"对比: {args.engine}（dag = DAG 选股链 / legacy = 粘合链+硬过滤）\n")

    periods = list(CANON) if args.period == "all" else [args.period]
    engines = ["dag", "legacy"] if args.engine == "both" else [args.engine]

    rows = []
    for period in periods:
        print("=" * 78)
        print(f"【{period}】")
        print("=" * 78)
        for eng in engines:
            r = run_period(cache, all_dates, date_to_idx, period, eng)
            rows.append(r)
            print(f"  [{eng:<6}] 信号{r['sigs']:<5} 已实现{r['n']:<4} "
                  f"平均{r['avg']:+.2f}% 胜率{r['wr']:.1f}% 固定本金累计{r['cum']:+.2f}% "
                  f"因子{_fmt_pf(r['pf'])}  状态分布 {dict(r['state_counter'])}")
        # 按大盘状态分组（仅 dag）
        drow = [r for r in rows if r["period"] == period and r["engine"] == "dag"]
        if drow and drow[0]["trades"]:
            print("  [dag] 按大盘状态分组:")
            by_state = {}
            for t in drow[0]["trades"]:
                by_state.setdefault(t["state"], []).append(t["ret"])
            for st in ["BULLISH", "OSCILLATION", "BEARISH", "CRASH"]:
                sub = by_state.get(st)
                if not sub:
                    continue
                sn, savg, swr, scum, spf, _ = stats(sub)
                print(f"    {st:<12} 信号{len(sub):<4} 平均{savg:+.2f}% 胜率{swr:.1f}% "
                      f"固定本金累计{scum:+.2f}% 因子{_fmt_pf(spf)}")

    print("\n" + "=" * 78)
    print("汇总（固定本金口径：每笔等额投入、收益相加不复利，无未来函数）")
    print("=" * 78)
    print(f"{'周期':<5}{'引擎':<8}{'信号':<6}{'已实现':<7}{'平均':<9}"
          f"{'胜率':<7}{'固定本金累计':<12}{'回撤'}")
    for r in rows:
        print(f"{r['period']:<5}{r['engine']:<8}{r['sigs']:<6}{r['n']:<7}"
              f"{r['avg']:+.2f}%  {r['wr']:<6.1f}{r['cum']:+.2f}%   {r['mdd']:.2f}%")


if __name__ == "__main__":
    main()
