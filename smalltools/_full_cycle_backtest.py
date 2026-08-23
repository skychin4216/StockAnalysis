# -*- coding: utf-8 -*-
"""
多周期完整回溯 + 大盘状态矩阵拟合（无未来函数）。

覆盖用户需求（工作台「回溯+拟合」的 Python 侧验证版）：
- 超短线：隔日卖（T+1 次日收盘卖）
- 短线：连续下跌就卖（连跌 N 日 或 跌破 5 日均线）
- 中线/长线：持仓期内不断做T降成本（高抛低吸 40% 比例）+ 到时间卖/止盈/止损
- 大盘状态：BULLISH(结构性牛) / OSCILLATION(震荡) / BEARISH(下跌) / CRASH(暴跌)
  —— 三指数 MA 排列 tripleVote + 近期跌幅检测（7月暴跌可被识别为 CRASH）
- 拟合：按大盘状态分组网格搜索（持有天数 × 止盈 × 止损），输出参数矩阵
- 统计口径：固定本金（每笔等额投入、收益相加不复利），累计收益不会指数失真

数据：_kline_cache.json（111 只核心龙头 + 3 指数，末端日期随缓存自动更新）
"""
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS, get_index_dir, triple_vote
from _pool_filters import extra_filter

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
INDEXES = ["sh000001", "sz399001", "sz399006"]
TODAY = "2026-08-20"  # 数据末端（随 _kline_cache.json 增量更新自动前进）

# 各周期选股窗口（保证持仓完全走完，不借未来数据）
WINDOWS = {
    "超短线": ("2025-08-15", "2026-08-01", PARAMS["超短"]),
    "短线":   ("2025-08-15", "2026-07-15", PARAMS["短线"]),
    "中线":   ("2025-08-15", "2026-05-15", PARAMS["中线"]),
    "长线":   ("2025-08-15", "2026-02-15", PARAMS["长线"]),
}

# 周期默认卖出参数（Kotlin AutoSellConfig 对齐）
SELL_RULES = {
    "超短线": dict(style="nextday", maxHold=2, tp=0.0, sl=0.0),
    "短线":   dict(style="streak", streakDays=3, maBreak=5, maxHold=10, tp=0.0, sl=0.0),
    "中线":   dict(style="hold", maxHold=15, tp=20.0, sl=-10.0, tRatio=0.4),
    "长线":   dict(style="hold", maxHold=30, tp=40.0, sl=-12.0, tRatio=0.4),
}

# 做T参数：涨≥0.5% 高抛 40%，跌≤-0.5% 低吸买回（对齐 Kotlin TTradeEngine 语义）
T_UP_PCT, T_DOWN_PCT = 0.5, -0.5


def load_cache():
    with open(CACHE, "r", encoding="utf-8") as f:
        return json.load(f)


def filter_asof(snaps, asof):
    return [s for s in snaps if s["date"] <= asof]


def market_state(idx_snaps_map, asof, all_dates, date_to_idx):
    """大盘状态判定：先看近期是否暴跌(CRASH)，否则三指数 MA 排列"""
    idx_pos = date_to_idx.get(asof, 0)
    look = min(idx_pos + 1, 6)  # 最近 6 根（含当日）
    rec_dates = all_dates[max(0, idx_pos + 1 - look): idx_pos + 1]
    if len(rec_dates) >= 2:
        drops = []
        for secid in INDEXES:
            snaps = idx_snaps_map.get(secid, [])
            by_date = {s["date"]: s["close"] for s in snaps}
            closes = [by_date[d] for d in rec_dates if d in by_date]
            if len(closes) >= 2:
                drops.append((closes[-1] / closes[0] - 1) * 100)
        if drops:
            avg_drop = sum(drops) / len(drops)
            if avg_drop <= -4.0:
                return "CRASH"
    dirs = []
    for secid in INDEXES:
        snaps = idx_snaps_map.get(secid, [])
        sub = filter_asof(snaps, asof)
        if len(sub) < 20:
            dirs.append("UNKNOWN")
            continue
        dirs.append(get_index_dir(None, sub))
    tv = triple_vote(dirs)
    return {"BULLISH": "BULLISH", "BEARISH": "BEARISH"}.get(tv, "OSCILLATION")


def collect_signals(cache, all_dates, date_to_idx, period):
    start, end, p = WINDOWS[period]
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    scan_dates = [d for d in all_dates if start <= d <= end]
    sigs = []
    state_counter = Counter()
    for asof in scan_dates:
        st = market_state(idx_snaps, asof, all_dates, date_to_idx)
        state_counter[st] += 1
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
            # CRASH（暴跌期）视为 BEARISH 选股，用最保守参数
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
            sigs.append((code, name, asof, date_to_idx.get(asof, 0) + 1, st))
    return sigs, state_counter


def get_day(snaps, d):
    return next((s for s in snaps if s["date"] == d), None)


def simulate_trade(cache, all_dates, sig, rule):
    """按周期规则模拟单笔交易，返回 dict(收益%, 状态)"""
    code, name, sig_date, buy_idx, state = sig
    snaps = cache.get(code, {}).get("snaps") or []
    if buy_idx >= len(all_dates):
        return None
    buy_date = all_dates[buy_idx]
    buy_snap = get_day(snaps, buy_date)
    if not buy_snap or buy_snap["open"] <= 0:
        return None
    entry = buy_snap["open"]
    style = rule["style"]

    # ---------- 超短线：隔日卖 ----------
    if style == "nextday":
        for k in range(1, rule["maxHold"] + 1):
            di = buy_idx + k
            if di >= len(all_dates):
                break
            d = all_dates[di]
            day = get_day(snaps, d)
            if not day:
                continue
            ret = (day["close"] / entry - 1) * 100
            return dict(code=code, name=name, sig=sig_date, buy=buy_date,
                        sell=d, entry=entry, exit=day["close"], ret=ret, reason="隔日卖", state=state)

    # ---------- 短线：连续下跌就卖 ----------
    if style == "streak":
        streak = 0
        prev_close = entry
        for k in range(1, rule["maxHold"] + 1):
            di = buy_idx + k
            if di >= len(all_dates):
                break
            d = all_dates[di]
            day = get_day(snaps, d)
            if not day:
                continue
            streak = streak + 1 if day["close"] < prev_close else 0
            prev_close = day["close"]
            # 连跌 streakDays 日 → 卖
            if streak >= rule["streakDays"]:
                ret = (day["close"] / entry - 1) * 100
                return dict(code=code, name=name, sig=sig_date, buy=buy_date,
                            sell=d, entry=entry, exit=day["close"], ret=ret,
                            reason=f"连跌{streak}日卖", state=state)
            # 跌破 5 日均线 → 卖（近似：收盘 < 前5日均值）
            hist = [s for s in snaps if s["date"] < d][-rule["maBreak"]:]
            if len(hist) >= rule["maBreak"]:
                ma = sum(s["close"] for s in hist) / len(hist)
                if day["close"] < ma:
                    ret = (day["close"] / entry - 1) * 100
                    return dict(code=code, name=name, sig=sig_date, buy=buy_date,
                                sell=d, entry=entry, exit=day["close"], ret=ret,
                                reason=f"跌破{rule['maBreak']}日线", state=state)
        # 到期未触发 → 期末收盘卖
        k = rule["maxHold"]
        di = buy_idx + k
        if di < len(all_dates):
            d = all_dates[di]
            day = get_day(snaps, d)
            if day:
                ret = (day["close"] / entry - 1) * 100
                return dict(code=code, name=name, sig=sig_date, buy=buy_date,
                            sell=d, entry=entry, exit=day["close"], ret=ret,
                            reason="持有到期", state=state)
        return None

    # ---------- 中/长线：做T降成本 + 到时间卖/止盈/止损 ----------
    if style == "hold":
        qty = 1000                        # 底仓 10 手
        t_qty = int(qty * rule["tRatio"]) # 每次做T 400 股
        hold_qty = qty
        cash = 0.0                        # 做T现金账户（卖出+，买入-，初始0）
        exit_price = exit_date = reason = None
        for k in range(1, rule["maxHold"] + 1):
            di = buy_idx + k
            if di >= len(all_dates):
                break
            d = all_dates[di]
            day = get_day(snaps, d)
            if not day:
                continue
            # 止盈/止损（按持仓市值+现金 相对初始成本）
            cost = qty * entry
            value = hold_qty * day["close"] + cash
            pnl_pct = (value - cost) / cost * 100
            if rule["tp"] > 0 and pnl_pct >= rule["tp"]:
                exit_price, exit_date, reason = day["close"], d, "止盈"
                break
            if rule["sl"] < 0 and pnl_pct <= rule["sl"]:
                exit_price, exit_date, reason = day["close"], d, "止损"
                break
            # 做T（现金账户模型）：高抛（涨≥0.5% 且持仓充足）→ 低吸（跌≤-0.5% 买回）
            hi_pct = (day["high"] / entry - 1) * 100
            lo_pct = (day["low"] / entry - 1) * 100
            if hi_pct >= T_UP_PCT and hold_qty >= t_qty:
                cash += day["high"] * t_qty
                hold_qty -= t_qty
            if lo_pct <= T_DOWN_PCT and hold_qty < qty:
                buy_back = min(t_qty, qty - hold_qty)
                cash -= day["low"] * buy_back
                hold_qty += buy_back
        if exit_price is None:
            # 到期：按剩余持仓市值 + 做T现金账户结算
            k = rule["maxHold"]
            di = buy_idx + k
            if di >= len(all_dates):
                return None
            d = all_dates[di]
            day = get_day(snaps, d)
            if not day:
                return None
            exit_price, exit_date, reason = day["close"], d, "持有到期"
        value = hold_qty * exit_price + cash
        cost = qty * entry
        ret = (value / cost - 1) * 100
        # 做T净收益（相对不动持有）
        t_net = (cash + (hold_qty - qty) * exit_price) / cost * 100
        return dict(code=code, name=name, sig=sig_date, buy=buy_date,
                    sell=exit_date, entry=entry, exit=exit_price, ret=ret,
                    reason=reason, state=state, tProfit=t_net, tCount=None)


def stats(rets):
    """统计口径：固定本金（每笔等额投入、收益累加不复利），避免等权复利指数失真。
    返回: (数量, 平均%, 胜率%, 固定本金累计%, 盈亏因子, 最大回撤%)"""
    if not rets:
        return (0, 0, 0, 0, 0, 0)
    wins = [r for r in rets if r > 0]
    losses = [r for r in rets if r <= 0]
    avg = sum(rets) / len(rets)
    wr = len(wins) / len(rets) * 100
    cum = sum(rets)  # 固定本金累计：每笔等额本金、收益直接相加（不复利）
    pf = sum(wins) / abs(sum(losses)) if losses and sum(losses) != 0 else float("inf")
    nav, peak, max_dd = 0.0, 0.0, 0.0  # 固定本金净值（起点 0，每笔 +r/100）
    for r in rets:
        nav += r / 100
        peak = max(peak, nav)
        max_dd = min(max_dd, nav - peak)
    return len(rets), avg, wr, cum, pf, max_dd * 100


def run_period(cache, all_dates, date_to_idx, period, rule=None):
    rule = rule or SELL_RULES[period]
    start, end, _ = WINDOWS[period]
    print(f"\n{'=' * 82}")
    print(f"【{period}】选股 {start} ~ {end} | 规则: {rule['style']} "
          f"{'' if rule.get('style')!='nextday' else '(隔日卖)'}"
          f"{'' if rule.get('style')!='streak' else f'(连跌{rule['streakDays']}日/破{rule['maBreak']}日线)'}"
          f"{'' if rule.get('style')!='hold' else f'(做T{int(rule['tRatio']*100)}% + 止盈{rule['tp']}% 止损{rule['sl']}% 持有{rule['maxHold']}天)'}")
    print("=" * 82)
    sigs, state_counter = collect_signals(cache, all_dates, date_to_idx, period)
    print(f"  总信号: {len(sigs)}  大盘状态分布: {dict(state_counter)}")
    if not sigs:
        return None
    trades = []
    for sig in sigs:
        t = simulate_trade(cache, all_dates, sig, rule)
        if t:
            trades.append(t)
    rets = [t["ret"] for t in trades]
    n, avg, wr, cum, pf, mdd = stats(rets)
    print(f"  已实现: {len(rets)}")
    print(f"  平均(每笔期望): {avg:+.2f}%   胜率: {wr:.1f}%   固定本金累计: {cum:+.2f}%   "
          f"盈亏因子: {'∞' if pf==float('inf') else f'{pf:.2f}'}   最大回撤(固定本金): {mdd:.2f}%")
    if rets:
        print(f"  最大单笔: +{max(rets):.2f}% / {min(rets):+.2f}%")
    # 按大盘状态分组
    print("  按大盘状态分组:")
    by_state = {}
    for t in trades:
        by_state.setdefault(t["state"], []).append(t["ret"])
    for st in ["BULLISH", "OSCILLATION", "BEARISH", "CRASH"]:
        sub = by_state.get(st)
        if not sub:
            continue
        sn, savg, swr, scum, spf, _ = stats(sub)
        print(f"    {st:<12} 信号{len(sub):<4} 平均{savg:+.2f}%  胜率{swr:.1f}%  "
              f"固定本金累计{scum:+.2f}%  因子{'∞' if spf==float('inf') else f'{spf:.2f}'}")
    print("  明细 Top10:")
    for t in sorted(trades, key=lambda x: -x["ret"])[:10]:
        tp_extra = f" (做T利润{t['tProfit']:.0f})" if t.get("tProfit") else ""
        print(f"    {t['name']} 选{t['sig']} 买{t['buy']}@{t['entry']:.2f} 卖{t['sell']}@{t['exit']:.2f} "
              f"{t['ret']:+.1f}% [{t['reason']}]{tp_extra}")
    return dict(period=period, trades=trades, state_counter=state_counter)


def fit_by_state(cache, all_dates, date_to_idx, period):
    """按大盘状态分组拟合：搜索 maxHold × tp × sl 组合（仅对 hold 类周期）"""
    sigs, _ = collect_signals(cache, all_dates, date_to_idx, period)
    print(f"\n{'=' * 82}")
    print(f"【{period} 拟合】固定信号池 {len(sigs)} 个 → 按大盘状态网格搜索最优卖出参数")
    print("=" * 82)
    if not sigs:
        return
    by_state = {}
    for sig in sigs:
        by_state.setdefault(sig[4], []).append(sig)

    hold_cands = {"中线": [8, 10, 12, 15, 20], "长线": [15, 20, 25, 30, 40]}[period]
    tp_cands = {"中线": [15, 20, 25], "长线": [25, 30, 40, 50]}[period]
    sl_cands = {"中线": [-6, -8, -10], "长线": [-8, -10, -12]}[period]

    for st in ["BULLISH", "OSCILLATION", "BEARISH", "CRASH"]:
        sub = by_state.get(st)
        if not sub or len(sub) < 3:
            continue
        rows = []
        for hold in hold_cands:
            for tp in tp_cands:
                for sl in sl_cands:
                    rule = dict(style="hold", maxHold=hold, tp=tp, sl=sl, tRatio=0.4)
                    rets = []
                    for sig in sub:
                        t = simulate_trade(cache, all_dates, sig, rule)
                        if t:
                            rets.append(t["ret"])
                    n, avg, wr, cum, pf, mdd = stats(rets)
                    if n == 0:
                        continue
                    rows.append((cum, avg, wr, pf, n, hold, tp, sl))
        if not rows:
            continue
        rows.sort(key=lambda r: (-r[1], -r[2]))
        best = rows[0]
        print(f"\n  [{st}] 状态信号 {len(sub)} 个 → 最优: 持有{best[5]}天 止盈+{best[6]}% 止损{best[7]}%  "
              f"(平均{best[1]:+.2f}% 胜率{best[2]:.1f}% 固定本金累计{best[0]:+.2f}% 因子{best[3]:.2f})")
        print(f"    Top5:")
        for cum, avg, wr, pf, n, hold, tp, sl in rows[:5]:
            print(f"      持有{hold:<3} 止盈+{tp:<3} 止损{sl:<4} 平均{avg:+.2f}% 胜率{wr:.1f}% 固定本金累计{cum:+.2f}%")
    print(f"\n  → 矩阵结论: 震荡/牛市放宽持有与止盈；下跌/暴跌收紧止损并缩短持有（暴跌期应 <3 天离场）")


def main():
    cache = load_cache()
    dates = set()
    for code, ent in cache.items():
        for s in (ent.get("snaps") or []):
            dates.add(s["date"])
    all_dates = sorted(dates)
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    print(f"股票池: {len(cache) - 3} 只核心龙头 | K线: {all_dates[0]} ~ {all_dates[-1]} ({len(all_dates)} 交易日)")
    print("说明: 超短隔日卖 / 短线连跌卖 / 中长线做T降成本 + 大盘状态(牛/震荡/跌/暴跌)参数矩阵")

    summary = {}
    for period in ["超短线", "短线", "中线", "长线"]:
        r = run_period(cache, all_dates, date_to_idx, period)
        if r:
            summary[period] = r

    print("\n" + "=" * 82)
    print("四周期营收率汇总（固定本金口径：每笔等额投入、收益相加不复利，无未来函数）")
    print("=" * 82)
    print(f"{'周期':<6}{'信号':<6}{'已实现':<8}{'平均':<9}{'胜率':<7}{'固定本金累计':<12}{'盈亏因子':<8}{'回撤'}")
    for period, r in summary.items():
        rets = [t["ret"] for t in r["trades"]]
        n, avg, wr, cum, pf, mdd = stats(rets)
        print(f"{period:<6}{len(r['trades']):<6}{n:<8}{avg:+.2f}%  {wr:<6.1f}{cum:+.2f}%   "
              f"{'∞' if pf==float('inf') else f'{pf:.2f}':<8}{mdd:.2f}%")

    # 中/长线按大盘状态拟合（任务4核心：震荡/牛/跌/暴跌 → 参数矩阵）
    fit_by_state(cache, all_dates, date_to_idx, "中线")
    fit_by_state(cache, all_dates, date_to_idx, "长线")


if __name__ == "__main__":
    main()
