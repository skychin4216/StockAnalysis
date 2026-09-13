# -*- coding: utf-8 -*-
"""
一年回溯 + 参数拟合：中线 / 长线选股营收率统计（无未来函数）。

背景：今天是 2026-08-15。用户要求：
- 中线回溯：选股日 ∈ [2025-08-15, 2026-05-15]（1 年前 ~ 3 个月前）
- 长线回溯：选股日 ∈ [2025-08-15, 2026-02-15]（1 年前 ~ 6 个月前）
理由：持仓期（中 10 天 / 长 20 天）必须已完全走完，不借未来数据。

选股逻辑（与 Kotlin StockCheckPipeline 对齐）：
- 中/长线始终「均线粘合」(analyze_snaps)
- 大盘方向：三指数 MA 排列 tripleVote（get_index_dir）

交易规则：
- 选股日 t（截至 t 收盘数据）→ t+1 开盘价买入
- 持有期内：高 ≥ 买价*(1+止盈) 触发止盈；低 ≤ 买价*(1+止损) 触发止损
- 到期无触发 → 期末收盘价了结
- 若持仓未了结（数据不足）→ 记为未了结，不计入已实现统计

输出：
1. 各周期营收率汇总（平均收益/胜率/累计/盈亏比/最大回撤）
2. 参数拟合：网格搜索持有天数 × 止盈 × 止损，找最优组合
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS, get_index_dir, triple_vote

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
INDEXES = ["sh000001", "sz399001", "sz399006"]

# 各周期：选股日窗口 [start, end] + 持有天数 + 止盈% + 止损%
WINDOWS = {
    "中线": ("2025-08-15", "2026-05-15", 10, 25.0, -8.0),
    "长线": ("2025-08-15", "2026-02-15", 20, 40.0, -10.0),
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


def collect_signals(cache, all_dates, date_to_idx, period):
    """扫描窗口内所有选股信号，返回 [(code, name, sig_date, buy_idx)]"""
    start, end, hold, tp, sl = WINDOWS[period]
    p = PARAMS[period]
    idx_snaps = {}
    for secid in INDEXES:
        idx_snaps[secid] = cache.get(secid, {}).get("snaps", [])
    scan_dates = [d for d in all_dates if start <= d <= end]
    print(f"  选股日窗口: {scan_dates[0]} ~ {scan_dates[-1]}  共 {len(scan_dates)} 交易日")

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
    return sigs, idx_snaps


def simulate(cache, all_dates, sigs, hold, tp, sl):
    """模拟交易，返回已实现收益列表 + 明细 + 未了结数"""
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
            details.append((name, sig_date, buy_date, None, entry, None, None, "未了结"))
            continue
        ret = (exit_price / entry - 1) * 100
        rets.append(ret)
        details.append((name, sig_date, buy_date, exit_date, entry, exit_price, ret, exit_reason))
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
    peak, max_dd = 1.0, 0.0
    nav = 1.0
    for r in rets:
        nav *= 1 + r / 100
        peak = max(peak, nav)
        max_dd = min(max_dd, nav / peak - 1)
    return len(rets), avg, wr, cum_pct, pf, max_dd * 100


def run_period(cache, all_dates, date_to_idx, period):
    start, end, hold, tp, sl = WINDOWS[period]
    print(f"\n{'='*80}")
    print(f"【{period}】选股 {start} ~ {end} | 持有 {hold} 天 | 止盈+{tp}% 止损{sl}%")
    print(f"{'='*80}")
    sigs, _ = collect_signals(cache, all_dates, date_to_idx, period)
    print(f"  总信号: {len(sigs)}")
    rets, details, open_trades = simulate(cache, all_dates, sigs, hold, tp, sl)
    n, avg, wr, cum, pf, mdd = stats(rets)
    print(f"  已实现: {len(rets)}   未了结: {open_trades}")
    print(f"  平均收益率: {avg:+.2f}%   胜率: {wr:.1f}%")
    print(f"  累计收益(等权复利): {cum:+.2f}%   盈亏因子: {'∞' if pf == float('inf') else f'{pf:.2f}'}")
    print(f"  最大回撤: {mdd:.2f}%")
    if rets:
        print(f"  最大单笔盈利: {max(rets):+.2f}%   最大单笔亏损: {min(rets):+.2f}%")
    print("  明细 Top12:")
    for (nm, sd, bd, ed, en, ex, r, reason) in sorted(
            details, key=lambda x: -(x[6] if x[6] is not None else -999))[:12]:
        if r is None:
            print(f"    {nm} 选{sd} 买{bd}@{en:.2f} 未了结")
        else:
            print(f"    {nm} 选{sd} 买{bd}@{en:.2f} 卖{ed}@{ex:.2f} {r:+.1f}% [{reason}]")
    return n, avg, wr, cum, pf, mdd, len(sigs), open_trades


def fit_period(cache, all_dates, date_to_idx, period, hold_cands, tp_cands, sl_cands):
    """参数拟合：在固定选股信号上网格搜索持有/止盈/止损"""
    start, end, hold0, tp0, sl0 = WINDOWS[period]
    print(f"\n{'='*80}")
    print(f"【{period}参数拟合】网格搜索: 持有{hold_cands} × 止盈{tp_cands} × 止损{sl_cands}")
    print(f"{'='*80}")
    sigs, _ = collect_signals(cache, all_dates, date_to_idx, period)
    print(f"  固定信号池: {len(sigs)} 个")
    rows = []
    for hold in hold_cands:
        for tp in tp_cands:
            for sl in sl_cands:
                rets, _, _ = simulate(cache, all_dates, sigs, hold, tp, sl)
                n, avg, wr, cum, pf, mdd = stats(rets)
                if n == 0:
                    continue
                rows.append((cum, avg, wr, pf, mdd, n, hold, tp, sl))
    rows.sort(key=lambda r: -r[0])
    print(f"{'持有':<5}{'止盈':<7}{'止损':<7}{'样本':<6}{'平均':<9}{'胜率':<7}{'累计':<10}{'盈亏因子':<8}{'回撤'}")
    for cum, avg, wr, pf, mdd, n, hold, tp, sl in rows[:20]:
        print(f"{hold:<5}{'+'+str(tp)+'%':<7}{str(sl)+'%':<7}{n:<6}{avg:+.2f}%  {wr:<6.1f}{cum:+.2f}%   "
              f"{'∞' if pf==float('inf') else f'{pf:.2f}':<8}{mdd:.2f}%")
    print(f"\n  基准参数（持有{hold0}天 止盈+{tp0}% 止损{sl0}%）排名:")
    for i, (cum, avg, wr, pf, mdd, n, hold, tp, sl) in enumerate(rows):
        if hold == hold0 and tp == tp0 and sl == sl0:
            print(f"    第{i+1}名: 平均{avg:+.2f}% 胜率{wr:.1f}% 累计{cum:+.2f}%")
            break


def main():
    cache = load_cache()
    dates = set()
    for code, ent in cache.items():
        for s in (ent.get("snaps") or []):
            dates.add(s["date"])
    all_dates = sorted(dates)
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    print(f"股票池: {len(cache) - 3} 只核心龙头（剔除 3 个指数）")
    print(f"K线覆盖: {all_dates[0]} ~ {all_dates[-1]}  共 {len(all_dates)} 交易日")
    print("说明：中/长线始终用「均线粘合」，大盘三指数 MA 排列 tripleVote")

    summary = []
    for period in ["中线", "长线"]:
        summary.append(run_period(cache, all_dates, date_to_idx, period))

    print("\n" + "=" * 80)
    print("一年营收率汇总（等权复利口径）")
    print("=" * 80)
    print(f"{'周期':<6}{'选股日窗口':<24}{'总信号':<8}{'已实现':<8}{'平均':<9}{'胜率':<7}{'累计':<10}")
    labels = {
        "中线": "2025-08-15~2026-05-15",
        "长线": "2025-08-15~2026-02-15",
    }
    for (n, avg, wr, cum, pf, mdd, total, opn), period in zip(summary, ["中线", "长线"]):
        print(f"{period:<6}{labels[period]:<24}{total:<8}{n:<8}{avg:+.2f}%  {wr:<6.1f}{cum:+.2f}%")

    # 参数拟合
    fit_period(cache, all_dates, date_to_idx, "中线",
               hold_cands=[5, 8, 10, 12, 15], tp_cands=[15, 20, 25, 30], sl_cands=[-5, -8, -10])
    fit_period(cache, all_dates, date_to_idx, "长线",
               hold_cands=[10, 15, 20, 25, 30], tp_cands=[25, 30, 40, 50], sl_cands=[-6, -8, -10, -12])


if __name__ == "__main__":
    main()
