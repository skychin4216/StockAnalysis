# -*- coding: utf-8 -*-
"""
三年期 walk-forward 月度滚动回溯 + 拟合（2023-08-15 ~ 2026-08-15）
========================================================================
方法论（对齐 AutoQuant walk-forward + IS/OOS，无未来函数）：
- 月度窗口 [每月15日 ~ 次月15日)，共 36 个窗口
- 每个窗口：
    1) 回溯(样本外验证)：用【上个月拟合】的卖出参数，模拟本月信号完整持仓
    2) 拟合(样本内优化)：用本月(及之前)信号网格搜索最优卖出参数 → 保存，应用到下月
- 拟合数据范围（用户要求）：
    超短线 = 最近 1 个月信号；短线 = 最近 3 个月信号；中/长线 = 全部至今信号
- 增量：已跑过的窗口记录持久化在 _records/selected_YYYY-MM.json，跳过不重跑
- 选中记录持久化：每窗口记录四周期全部信号（code/name/signal_date/大盘状态）
- 中/长线每满 3 个月自动生成 hindsight 报告（实际 vs 事后最优 + 漏选归因）

产出：
- _records/selected_YYYY-MM.json  每窗口：signals/trades/params_used/fitted
- _records/summary.json           全局样本外汇总
- _records/hindsight_<季度>.json  每季度中/长线事后分析

用法：
  python _walk_forward.py             # 全量三年
  python _walk_forward.py --months 3  # 只跑前 3 个月（小规模验证）
"""
import argparse
import json
import os
import sys
from collections import Counter, defaultdict
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS
from _full_cycle_backtest import load_cache, market_state, simulate_trade, stats, SELL_RULES
from _pool_filters import extra_filter

START = date(2023, 8, 15)
END = date(2026, 8, 15)
MONTH_DAY = 15
RECORD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_records")

PERIODS = ["超短", "短线", "中线", "长线"]
RULES_KEY = {"超短": "超短线", "短线": "短线", "中线": "中线", "长线": "长线"}
INDEXES = ["sh000001", "sz399001", "sz399006"]
STATES = ["BULLISH", "OSCILLATION", "BEARISH", "CRASH"]

# 拟合窗口（月）：由 _refit_experiment.py 三年对比实验确定 —— 全量历史拟合最优
# （短线样本外收益 +295% vs 1/3月 +254%、胜率 42.9% vs 39.9%、参数抖动 36.6% vs 38.4%；
#  超短收益持平但抖动更低），故四周期均用全部至今信号拟合
# 可用 --fit-lookback 覆盖（如 超短:3,短线:6 / 超短:all,短线:all），None=全部
FIT_LOOKBACK = {"超短": None, "短线": None, "中线": None, "长线": None}

# 拟合网格（卖出参数）
HOLD_GRIDS = {
    "中线": dict(holds=[8, 10, 12, 15, 20], tps=[15, 20, 25], sls=[-6, -8, -10]),
    "长线": dict(holds=[15, 20, 25, 30, 40], tps=[25, 30, 40, 50], sls=[-8, -10, -12]),
}
STREAK_GRID = dict(streaks=[2, 3, 4], mas=[5, 8], holds=[5, 8, 10])
NEXTDAY_GRID = dict(holds=[1, 2])


def month_windows(cache_last=None):
    """月度窗口（每月 15 日，[15日, 次月15日)）。

    默认到 END；传入 cache_last（缓存最新交易日）且晚于 END 时，
    自动向后扩展窗口 —— 保证「默认拉取最新数据再选股」时，
    最新行情也被纳入最近一个滚动窗口。
    """
    end = END
    if cache_last:
        last15 = date(cache_last.year, cache_last.month, MONTH_DAY)
        if cache_last.day >= MONTH_DAY:  # 末端在 15 日之后 → 进入下一窗口
            ny = cache_last.year + (1 if cache_last.month == 12 else 0)
            nm = 1 if cache_last.month == 12 else cache_last.month + 1
            last15 = date(ny, nm, MONTH_DAY)
        end = max(end, last15)
    wins, cur = [], START
    while cur < end:
        ny = cur.year + (1 if cur.month == 12 else 0)
        nm = 1 if cur.month == 12 else cur.month + 1
        nxt = date(ny, nm, MONTH_DAY)
        wins.append((cur.isoformat(), nxt.isoformat()))
        cur = nxt
    return wins


def collect_signals_window(cache, all_dates, date_to_idx, period, w_start, w_end, verbose=False):
    """扫描 [w_start, w_end) 内的四周期选股信号"""
    p = PARAMS[period]
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    scan_dates = [d for d in all_dates if w_start <= d < w_end]
    sigs, state_counter = [], Counter()
    for asof in scan_dates:
        st = market_state(idx_snaps, asof, all_dates, date_to_idx)
        state_counter[st] += 1
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
                ok = bool(r.get("passed"))
            except Exception:
                continue
            if not ok:
                continue
            # 股票池/信号硬过滤（ST、主板开关、粘合持续、短线关键项、板块代理）
            if not extra_filter(code, name, r, period, cache, all_dates, date_to_idx, asof):
                continue
            sigs.append([code, name, asof, date_to_idx.get(asof, 0) + 1, st])
        if verbose and sigs and len(state_counter) and (asof == scan_dates[-1]):
            print(f"    末交易日 {asof} 大盘 {st} 累计信号 {len(sigs)}")
    return sigs, state_counter


def load_prev_fitted():
    """加载最近一个已跑窗口的 fitted，作为本窗口参数起点（增量续跑的关键）"""
    if not os.path.isdir(RECORD_DIR):
        return {}
    months = sorted(f for f in os.listdir(RECORD_DIR)
                    if f.startswith("selected_") and f.endswith(".json"))
    if not months:
        return {}
    rec = json.load(open(os.path.join(RECORD_DIR, months[-1]), encoding="utf-8"))
    return rec.get("fitted") or {}


def rule_for(period, fitted_prev, state):
    """取该周期该状态的应用规则（上月拟合优先），否则用默认"""
    if fitted_prev:
        st_rule = fitted_prev.get(period, {}).get(state)
        if isinstance(st_rule, dict) and "rule" in st_rule:
            return st_rule["rule"]
    return SELL_RULES[RULES_KEY[period]]


def load_hist_signals(period, lookback_months=None):
    """加载历史窗口信号：lookback_months 限制最近 N 个月，None 表示全部至今"""
    if not os.path.isdir(RECORD_DIR):
        return []
    months = sorted(f for f in os.listdir(RECORD_DIR)
                    if f.startswith("selected_") and f.endswith(".json"))
    if lookback_months is not None:
        months = months[-lookback_months:]
    sigs = []
    for f in months:
        rec = json.load(open(os.path.join(RECORD_DIR, f), encoding="utf-8"))
        sigs.extend(rec.get("signals", {}).get(period, []))
    return sigs


def fit_sell(cache, all_dates, sigs, period, min_samples=3):
    """按大盘状态分组网格搜索最优卖出参数（平均收益优先、胜率次之）"""
    if not sigs:
        return {}
    by_state = defaultdict(list)
    for sig in sigs:
        by_state[sig[4]].append(sig)
    out = {}
    for st in STATES:
        sub = by_state.get(st)
        if not sub or len(sub) < min_samples:
            continue
        if period == "超短":
            cands = [dict(style="nextday", maxHold=h, tp=0.0, sl=0.0)
                     for h in NEXTDAY_GRID["holds"]]
        elif period == "短线":
            cands = [dict(style="streak", streakDays=s, maBreak=m, maxHold=h, tp=0.0, sl=0.0)
                     for s in STREAK_GRID["streaks"]
                     for m in STREAK_GRID["mas"]
                     for h in STREAK_GRID["holds"]]
        else:
            g = HOLD_GRIDS[period]
            cands = [dict(style="hold", maxHold=h, tp=tp, sl=sl, tRatio=0.4)
                     for h in g["holds"] for tp in g["tps"] for sl in g["sls"]]
        rows = []
        for rule in cands:
            rets = []
            for sig in sub:
                t = simulate_trade(cache, all_dates, tuple(sig), rule)
                if t:
                    rets.append(t["ret"])
            if not rets:
                continue
            n, avg, wr, cum, pf, mdd = stats(rets)
            rows.append((avg, wr, rule, n))
        if not rows:
            continue
        rows.sort(key=lambda r: (-r[0], -r[1]))
        best = rows[0]
        out[st] = dict(rule=best[2], avg=best[0], wr=best[1], n=best[3])
    return out


def summarize():
    print("\n" + "=" * 90)
    print("三年 walk-forward 汇总（样本外逐月滚动，固定本金口径）")
    print("=" * 90)
    if not os.path.isdir(RECORD_DIR):
        return {}
    files = sorted(f for f in os.listdir(RECORD_DIR)
                   if f.startswith("selected_") and f.endswith(".json"))
    out = {}
    for period in PERIODS:
        all_trades = []
        for f in files:
            rec = json.load(open(os.path.join(RECORD_DIR, f), encoding="utf-8"))
            all_trades.extend(rec.get("trades", {}).get(period, []))
        rets = [t["ret"] for t in all_trades]
        n, avg, wr, cum, pf, mdd = stats(rets)
        print(f"\n【{period}】样本外合计 {n} 笔 | 平均 {avg:+.2f}% 胜率 {wr:.1f}% "
              f"固定本金累计 {cum:+.2f}% 盈亏因子 {'∞' if pf == float('inf') else f'{pf:.2f}'} "
              f"最大回撤 {mdd:.2f}%")
        by_state = defaultdict(list)
        for t in all_trades:
            by_state[t["state"]].append(t["ret"])
        for st in STATES:
            sub = by_state.get(st)
            if not sub:
                continue
            sn, savg, swr, scum, spf, _ = stats(sub)
            print(f"    {st:<12} {sn} 笔  平均 {savg:+.2f}%  胜率 {swr:.1f}%  累计 {scum:+.2f}%")
        out[period] = dict(n=n, avg=avg, wr=wr, cum=cum,
                           pf=None if pf == float('inf') else pf, mdd=mdd)
    with open(os.path.join(RECORD_DIR, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--months", type=int, default=0, help="只跑前 N 个月（0=全部）")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--fit-lookback", default="",
                    help="拟合窗口覆盖，如 超短:3,短线:6；值 all/none=全部至今")
    args = ap.parse_args()

    global FIT_LOOKBACK
    if args.fit_lookback:
        for kv in args.fit_lookback.split(","):
            k, v = kv.split(":")
            if k not in FIT_LOOKBACK:
                print(f"[警告] 未知周期 {k}，忽略")
                continue
            FIT_LOOKBACK[k] = None if v.strip().lower() in ("all", "none") else int(v.strip())
        print(f"拟合窗口(月): {FIT_LOOKBACK}")

    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    print(f"缓存 {len(cache)} 只 | 交易日 {all_dates[0]} ~ {all_dates[-1]} 共 {len(all_dates)} 根")

    wins = month_windows()
    if args.months > 0:
        wins = wins[:args.months]
    os.makedirs(RECORD_DIR, exist_ok=True)

    fitted_prev = load_prev_fitted()
    if fitted_prev:
        print("检测到历史拟合参数，将从其之后的窗口继续（增量）")

    done = 0
    for i, (ws, we) in enumerate(wins):
        tag = ws[:7]
        path = os.path.join(RECORD_DIR, f"selected_{tag}.json")
        if os.path.exists(path):
            rec = json.load(open(path, encoding="utf-8"))
            print(f"[增量跳过] {ws} ~ {we}（{tag}）")
            fitted_prev = rec.get("fitted") or fitted_prev
            done += 1
            continue
        print(f"\n{'#' * 92}\n# [{tag}] 窗口 {ws} ~ {we}  ({i + 1}/{len(wins)})\n{'#' * 92}")
        rec = {"window": [ws, we], "signals": {}, "trades": {}, "state_dist": {},
               "params_used": {}, "fitted": {}}
        for period in PERIODS:
            sigs, dist = collect_signals_window(cache, all_dates, date_to_idx,
                                                period, ws, we, args.verbose)
            rec["signals"][period] = sigs
            rec["state_dist"][period] = dict(dist)
            print(f"\n【{period}】信号 {len(sigs)} | 状态分布 {dict(dist)}")
            # 1) 样本外回溯：用上月拟合参数模拟完整持仓
            trades = []
            used = {}
            for sig in sigs:
                rule = rule_for(period, fitted_prev, sig[4])
                used[sig[4]] = rule
                t = simulate_trade(cache, all_dates, tuple(sig), rule)
                if t:
                    trades.append(t)
            rec["trades"][period] = trades
            rec["params_used"][period] = used
            rets = [t["ret"] for t in trades]
            n, avg, wr, cum, pf, mdd = stats(rets)
            print(f"    已实现 {n} 笔 | 平均 {avg:+.2f}% 胜率 {wr:.1f}% "
                  f"固定本金累计 {cum:+.2f}% 因子 {'∞' if pf == float('inf') else f'{pf:.2f}'}")
            # 2) 样本内拟合：窗口由 FIT_LOOKBACK 决定（可配置）
            lb = FIT_LOOKBACK[period]
            fit_sigs = load_hist_signals(period, lb) + sigs
            fitted = fit_sell(cache, all_dates, fit_sigs, period)
            rec["fitted"][period] = fitted
            brief = {st: r["rule"] for st, r in fitted.items()}
            print(f"    拟合矩阵 {json.dumps(brief, ensure_ascii=False)}")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(rec, f, ensure_ascii=False, indent=1)
        fitted_prev = rec["fitted"]
        done += 1
    print(f"\n完成 {done} 个窗口。")

    summarize()


if __name__ == "__main__":
    main()
