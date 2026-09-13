# -*- coding: utf-8 -*-
"""
拟合窗口对比实验（不重跑回溯扫描，直接复用 _records 已持久化信号）
========================================================================
背景：超短/短线只用最近 1/3 个月拟合，样本少、参数抖动大。
本脚本对同一批信号，用不同拟合窗口（当前 1/3 月 / 扩展 3/6 月 / 全量三年）
分别重算每月的拟合矩阵，并逐窗口重放样本外交易，对比：
  - 样本外收益（平均收益/胜率/累计/盈亏因子/最大回撤）
  - 参数抖动率（相邻月拟合规则不一致的比例，越低越稳）
输出：_records/refit_experiment.json

用法：
  python _refit_experiment.py                 # 三组全量对比
  python _refit_experiment.py --months 12     # 只对比前 12 个月（快速验证）
"""
import argparse
import json
import os
import sys
from collections import defaultdict
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import PARAMS
from _full_cycle_backtest import load_cache, simulate_trade, stats, SELL_RULES
from _walk_forward import month_windows, fit_sell, RULES_KEY

RECORD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_records")
PERIODS = ["超短", "短线", "中线", "长线"]
STATES = ["BULLISH", "OSCILLATION", "BEARISH", "CRASH"]

# 三组对比：超短/短线拟合窗口(月)，None=全部至今；中/长线始终全部
GROUPS = [
    ("当前1/3月", {"超短": 1, "短线": 3}),
    ("扩展3/6月", {"超短": 3, "短线": 6}),
    ("全量三年", {"超短": None, "短线": None}),
]


def load_records(limit=None):
    """按窗口顺序加载全部 records，返回 (tags, per_period_signals)"""
    if not os.path.isdir(RECORD_DIR):
        return [], {}
    files = sorted(f for f in os.listdir(RECORD_DIR)
                   if f.startswith("selected_") and f.endswith(".json"))
    if limit:
        files = files[:limit]
    tags = []
    per = {p: [] for p in PERIODS}
    for f in files:
        tags.append(f.replace("selected_", "").replace(".json", ""))
        rec = json.load(open(os.path.join(RECORD_DIR, f), encoding="utf-8"))
        for p in PERIODS:
            per[p].append(rec.get("signals", {}).get(p, []))
    return tags, per


FIT_CACHE_FILE = os.path.join(RECORD_DIR, "refit_fitted_cache.json")


def _load_fit_cache():
    if not os.path.exists(FIT_CACHE_FILE):
        return {}
    try:
        return json.load(open(FIT_CACHE_FILE, encoding="utf-8"))
    except Exception:
        return {}


def _save_fit_cache(fit_cache):
    with open(FIT_CACHE_FILE, "w", encoding="utf-8") as f:
        json.dump(fit_cache, f, ensure_ascii=False)


def fit_window(cache, all_dates, per, period, i, lb, fit_cache):
    """拟合窗口 i：历史(按 lb 截断) + 当前窗口信号；结果落盘缓存（中断可续跑）"""
    key = f"{period}|{i}|{'all' if lb is None else lb}"
    if key in fit_cache:
        return fit_cache[key]
    hist = []
    if lb is None:
        for j in range(i):
            hist.extend(per[period][j])
    else:
        for j in range(max(0, i - lb), i):
            hist.extend(per[period][j])
    fitted = fit_sell(cache, all_dates, hist + per[period][i], period)
    fit_cache[key] = fitted
    _save_fit_cache(fit_cache)          # 逐步写回，中断后可续
    return fitted


def replay_group(cache, all_dates, tags, per, fit_lb, fit_cache):
    """对一组拟合窗口配置：逐窗口拟合 + 用上月参数重放样本外（无未来函数）"""
    trades_by_p = {p: [] for p in PERIODS}
    rules_by_p = {p: [] for p in PERIODS}   # 每窗口每状态实际使用的规则
    prev_fitted = {}                        # 上一窗口拟合矩阵（i=0 时空 → 默认规则）
    for i in range(len(tags)):
        cur_fitted = {}
        for p in PERIODS:
            if p not in cur_fitted:
                cur_fitted[p] = {}
            # 1) 样本外重放：优先用上一窗口拟合的规则，否则默认规则
            used = {}
            for sig in per[p][i]:
                rule = None
                st_rule = prev_fitted.get(p, {}).get(sig[4])
                if isinstance(st_rule, dict) and "rule" in st_rule:
                    rule = st_rule["rule"]
                if rule is None:
                    rule = SELL_RULES[RULES_KEY[p]]
                used[sig[4]] = rule
                t = simulate_trade(cache, all_dates, tuple(sig), rule)
                if t:
                    trades_by_p[p].append(t)
            rules_by_p[p].append(used)
            # 2) 拟合本窗口 → 供下月使用（缺配置的周期默认全部至今）
            cur_fitted[p] = fit_window(cache, all_dates, per, p, i, fit_lb.get(p), fit_cache)
        prev_fitted = cur_fitted
    return trades_by_p, rules_by_p


def rule_equal(a, b):
    if a is None or b is None:
        return a is b
    if set(a.keys()) != set(b.keys()):
        return False
    return all(a[k] == b[k] for k in a)


def jitter_rate(rules_by_p, states=STATES):
    """相邻窗口规则不一致比例（越低越稳）"""
    n_pair, n_diff = 0, 0
    for rl in rules_by_p.values():
        for i in range(1, len(rl)):
            for st in states:
                a = rl[i - 1].get(st)
                b = rl[i].get(st)
                n_pair += 1
                if not rule_equal(a, b):
                    n_diff += 1
    return (n_diff / n_pair * 100) if n_pair else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--months", type=int, default=0, help="只对比前 N 个月（0=全部）")
    args = ap.parse_args()

    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    tags, per = load_records(args.months if args.months else None)
    if not tags:
        print("没有已跑窗口记录，请先运行 python _walk_forward.py")
        return
    print(f"复用 {len(tags)} 个窗口已持久化信号（{tags[0]} ~ {tags[-1]}）")

    out = {}
    print("\n" + "=" * 100)
    print(f"{'拟合窗口':<10} | {'周期':<4} | {'笔数':>5} {'平均':>7} {'胜率':>6} "
          f"{'累计':>8} {'盈亏因子':>7} {'最大回撤':>7} | {'参数抖动':>7}")
    print("-" * 100)
    fit_cache = _load_fit_cache()
    for gname, fit_lb in GROUPS:
        trades, rules = replay_group(cache, all_dates, tags, per, fit_lb, fit_cache)
        jit = jitter_rate(rules)
        gstat = {}
        for p in PERIODS:
            rets = [t["ret"] for t in trades[p]]
            n, avg, wr, cum, pf, mdd = stats(rets)
            gstat[p] = dict(n=n, avg=round(avg, 2), wr=round(wr, 1),
                            cum=round(cum, 2), mdd=round(mdd, 2),
                            pf=None if pf == float('inf') else round(pf, 2))
            pf_s = "∞" if pf == float('inf') else f"{pf:.2f}"
            print(f"{gname:<10} | {p:<4} | {n:>5} {avg:>+7.2f} {wr:>6.1f}% "
                  f"{cum:>+8.2f} {pf_s:>7} {mdd:>7.2f} | {jit:>6.1f}%")
        out[gname] = dict(jitter=round(jit, 1), periods=gstat)
        print("-" * 100)

    # 最佳判定：先看样本外累计，再看抖动
    print("\n最佳判定（样本外累计收益优先，抖动率其次）：")
    for p in PERIODS:
        best_g, best_cum, best_jit = None, -1e9, None
        for gname, fit_lb in GROUPS:
            g = out[gname]
            cum = g["periods"][p]["cum"]
            if cum > best_cum:
                best_cum, best_g, best_jit = cum, gname, g["jitter"]
        print(f"  {p}: {best_g}（累计 {best_cum:+.2f}%，该组参数抖动 {best_jit:.1f}%）")

    with open(os.path.join(RECORD_DIR, "refit_experiment.json"), "w", encoding="utf-8") as f:
        json.dump({"groups": GROUPS, "windows": len(tags), "result": out},
                  f, ensure_ascii=False, indent=1)
    print("\n结果已保存 _records/refit_experiment.json")


if __name__ == "__main__":
    main()
