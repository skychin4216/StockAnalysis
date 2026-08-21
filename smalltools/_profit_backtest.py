# -*- coding: utf-8 -*-
"""
四周期盈利回测：用真实缓存日K，统计 2026-07-01 ~ 2026-08-13 期间
超短/短/中/长 四个周期选股后的实际盈利（无未来函数）。

选股逻辑（与 Kotlin UnifiedStockClassifier / StockCheckPipeline 对齐）：
- 超短/短线：BULLISH 用「趋势跟随」(trend_follow_scan)，否则「均线粘合」(analyze_snaps)
- 中/长线  ：始终「均线粘合」(analyze_snaps)

交易规则（无未来函数）：
- 每个交易日 t 用「截至 t」数据选股
- 买入：t+1 交易日开盘价
- 卖出：持有固定周期（超短1、短4、中10、长20 交易日），持仓期内触发止盈/止损提前卖
- 若到期末无后续数据，按最后可用收盘价了结

预期（策略文档）：
  超短: 止盈+3% 止损-2% (1天)
  短  : 止盈+15% 止损-5% (3-5天)
  中  : 止盈+25% 止损-8% (10天+)
  长  : 止盈+40% 止损-10% (20天+)
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS, get_index_dir, triple_vote, parse_market_regime
from _trend_proto import trend_follow_scan

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
INDEXES = ["sh000001", "sz399001", "sz399006"]
START = "2026-07-01"

# 各周期持有天数 + 止盈止损（按策略文档）
HOLD = {"超短": 1, "短线": 4, "中线": 10, "长线": 20}
TP = {"超短": 3.0, "短线": 15.0, "中线": 25.0, "长线": 40.0}
SL = {"超短": -2.0, "短线": -5.0, "中线": -8.0, "长线": -10.0}

# 每周期使用趋势跟随还是粘合
TREND_PERIODS = {"超短": "ultra_short", "短线": "short"}


def load_cache():
    with open(CACHE, encoding="utf-8") as f:
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


def select(period, p, sub, tv):
    """返回是否选中（按周期使用趋势跟随或粘合）"""
    if period in TREND_PERIODS and parse_market_regime(tv) == "BULLISH":
        passed, pc, tc, res, extra = trend_follow_scan(sub, tv, TREND_PERIODS[period])
        return passed
    r = analyze_snaps(sub, p, tv)
    return bool(r.get("passed"))


def main():
    cache = load_cache()
    dates = set()
    for code, ent in cache.items():
        for s in (ent.get("snaps") or []):
            dates.add(s["date"])
    all_dates = sorted(d for d in dates if d >= "2026-01-01")
    scan_dates = [d for d in all_dates if d >= START]
    print(f"回测区间: {scan_dates[0]} ~ {scan_dates[-1]}  共 {len(scan_dates)} 个交易日")
    print(f"股票池: {len(cache) - 3} 只核心龙头（剔除 3 个指数）")
    print("说明：超短/短线在 BULLISH 用「趋势跟随」，其余用「均线粘合」")
    print()

    idx_snaps = {}
    for secid in INDEXES:
        idx_snaps[secid] = cache.get(secid, {}).get("snaps", [])

    signals = {p: [] for p in PARAMS}
    date_to_idx = {d: i for i, d in enumerate(all_dates)}

    for asof in scan_dates:
        tv = market_dir_asof(idx_snaps, asof)
        for period, p in PARAMS.items():
            for code, ent in cache.items():
                if code.startswith("sh000") or code.startswith("sz399"):
                    continue
                snaps = ent.get("snaps") or []
                name = ent.get("name") or code
                sub = filter_asof(snaps, asof)
                if len(sub) < 20:
                    continue
                sub = [dict(s) for s in sub]
                sub[-1]["name"] = name
                try:
                    ok = select(period, p, sub, tv)
                except Exception:
                    continue
                if ok:
                    buy_idx = date_to_idx.get(asof, 0) + 1
                    signals[period].append((code, name, asof, sub[-1]["close"], buy_idx))

    summary = []
    for period, p in PARAMS.items():
        hold = HOLD[period]
        tp = TP[period]
        sl = SL[period]
        sigs = signals[period]
        if not sigs:
            summary.append((period, 0, 0, 0, 0, 0, 0, 0, 0))
            continue
        rets = []
        details = []
        open_trades = 0
        for (code, name, sig_date, buy_price, buy_idx) in sigs:
            ent = cache.get(code, {})
            snaps = ent.get("snaps") or []
            if buy_idx >= len(all_dates):
                continue
            buy_date = all_dates[buy_idx]
            buy_snap = next((s for s in snaps if s["date"] == buy_date), None)
            if not buy_snap:
                continue
            entry = buy_snap["open"]
            if entry <= 0:
                continue
            exit_price = None
            exit_date = None
            exit_reason = None
            for k in range(1, hold + 1):
                di = buy_idx + k
                if di >= len(all_dates):
                    break
                d = all_dates[di]
                day = next((s for s in snaps if s["date"] == d), None)
                if not day:
                    continue
                if day["high"] >= entry * (1 + tp / 100):
                    exit_price = entry * (1 + tp / 100)
                    exit_date = d
                    exit_reason = "止盈"
                    break
                if day["low"] <= entry * (1 + sl / 100):
                    exit_price = entry * (1 + sl / 100)
                    exit_date = d
                    exit_reason = "止损"
                    break
                exit_price = day["close"]
                exit_date = d
                exit_reason = "持有到期"
            if exit_price is None:
                # 持仓未了结（后续无数据，8/13 后无新K线）→ 记为未实现，不计入已实现统计
                ret = 0.0
                details.append((name, sig_date, buy_date, exit_date, entry, None, None, "未了结"))
                open_trades += 1
                continue
            ret = (exit_price / entry - 1) * 100
            rets.append(ret)
            details.append((name, sig_date, buy_date, exit_date, entry, exit_price, ret, exit_reason))

        wins = [r for r in rets if r > 0]
        losses = [r for r in rets if r <= 0]
        avg_ret = sum(rets) / len(rets) if rets else 0
        win_rate = len(wins) / len(rets) * 100 if rets else 0
        avg_win = sum(wins) / len(wins) if wins else 0
        avg_loss = sum(losses) / len(losses) if losses else 0
        pf = (sum(wins) / abs(sum(losses))) if losses and sum(losses) != 0 else float("inf")
        cum = 1.0
        for r in rets:
            cum *= (1 + r / 100)
        summary.append((period, len(rets), avg_ret, win_rate, avg_win, avg_loss, pf, (cum - 1) * 100, open_trades))

        plr = (avg_win / abs(avg_loss)) if avg_loss else float("inf")
        plr_s = "∞" if plr == float("inf") else f"{plr:.2f}"
        pf_s = "∞" if pf == float("inf") else f"{pf:.2f}"
        print(f"\n{'='*80}")
        print(f"【{period}】持有 {hold} 天 | 止盈+{tp}% 止损{sl}%")
        print(f"{'='*80}")
        print(f"  总信号: {len(sigs)}   已实现: {len(rets)}   未了结: {open_trades}")
        print(f"  平均收益率(已实现): {avg_ret:+.2f}%   胜率: {win_rate:.1f}%")
        print(f"  平均盈利: {avg_win:+.2f}%   平均亏损: {avg_loss:+.2f}%   盈亏比: {plr_s}")
        print(f"  累计收益(等权复利): {((cum-1)*100):+.2f}%   盈亏因子: {pf_s}")
        if rets:
            print(f"  最大单笔亏损: {min(rets):+.2f}%   最大单笔盈利: {max(rets):+.2f}%")
        print("  明细 Top15:")
        for (nm, sd, bd, ed, en, ex, r, reason) in sorted(details, key=lambda x: -(x[6] if x[6] is not None else -999))[:15]:
            if r is None:
                print(f"    {nm} 选{sd} 买{bd}@{en:.2f} 未了结")
            else:
                print(f"    {nm} 选{sd} 买{bd}@{en:.2f} 卖{ed}@{ex:.2f} {r:+.1f}% [{reason}]")

    print("\n" + "=" * 80)
    print("四周期盈利汇总（7/1 ~ 8/13，已实现交易）")
    print("=" * 80)
    print(f"{'周期':<6}{'总信号':<8}{'已实现':<8}{'平均收益':<10}{'胜率':<8}{'累计收益':<10}")
    for (period, n, avg, wr, aw, al, pf, cum, opn) in summary:
        print(f"{period:<6}{n+opn:<8}{n:<8}{avg:+.2f}%   {wr:.1f}%   {cum:+.2f}%")


if __name__ == "__main__":
    main()
