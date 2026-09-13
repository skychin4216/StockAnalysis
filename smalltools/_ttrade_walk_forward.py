# -*- coding: utf-8 -*-
"""
做T/反T 信号参数 walk-forward 拟合（2023-08-15 ~ 2026-08-15）
========================================================================
目标：为 APK TTradeEngine 的做T信号拟合阈值参数（backtest_params.json t_trade 区块）。

方法论（无未来函数）：
- 数据：smalltools/_kline_cache.json（114 只候选股日K，字段 date/open/close/high/low/volume）
- 训练段 2023-08-15 ~ 2025-08-15，验证段 2025-08-15 ~ 2026-08-15
- 信号（与 APK TTradeEngine.generateSignals 同口径）：
    T_BUY ：收盘价贴近支撑位 + 距 ma5 预期收益达标
    RT_SELL：收盘价贴近阻力位 + 距 ma5 回落收益达标
- 配对（T+1 约束天然满足：只看"未来 N 日"的 high/low）：
    T_BUY  成功 = 未来 N 日 high >= 买入价 * (1 + pairProfitPct/100)
    RT_SELL 成功 = 未来 N 日 low  <= 卖出价 * (1 - pairProfitPct/100)
- 评分：总收益（成功按 pairProfitPct 保守计，失败按持有 N 日收盘价计）
- 参数扫描：逐个参数独立扫描（其余固定当前最优），保证计算量可控

产出：
- stdout 打印最优 t_trade 区块 JSON（可直接合并进 app/src/main/assets/backtest_params.json）
- _records/ttrade_params.json 持久化扫描结果

用法：
  python _ttrade_walk_forward.py            # 全量
  python _ttrade_walk_forward.py --max-days 200   # 小规模验证
"""
import argparse
import json
import os
import sys
from datetime import date

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
RECORD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_records")

TRAIN_START = date(2023, 8, 15)
SPLIT = date(2025, 8, 15)      # 训练/验证切分
END = date(2026, 8, 15)
HOLD_N = 3                     # 做T配对观察窗口（未来 N 日）
MIN_TRADES = 30                # 参数组合最少样本数（避免过拟合噪声）
FAIL_IS_LOSS = False           # False=增量收益视角：失败收益按0计（继续持有不做T，不亏）；
                               # True=悲观视角：失败按持有N日收盘价结算真实盈亏

INDEX_CODES = {"sh000001", "sz399001", "sz399006"}

# ── 默认参数（与 TTradeParams.DEFAULT 一致） ──
DEFAULT = dict(
    supportMa5Factor=0.98, supportMa10Factor=0.97,
    resistanceMa5Factor=1.02, resistanceMa10Factor=1.03,
    nearSupportThreshold=0.02, nearResistanceThreshold=0.02,
    minExpectedProfitPct=0.5, pairProfitPct=0.5,
)

# 单参数扫描网格（其余保持当前最优）
SCAN = {
    "supportMa5Factor": [0.95, 0.98, 1.00],
    "supportMa10Factor": [0.94, 0.97, 0.99],
    "resistanceMa5Factor": [1.00, 1.02, 1.04],
    "resistanceMa10Factor": [1.01, 1.03, 1.05],
    "nearSupportThreshold": [0.01, 0.02, 0.03, 0.04],
    "nearResistanceThreshold": [0.01, 0.02, 0.03, 0.04],
    "minExpectedProfitPct": [0.3, 0.5, 0.8],
    "pairProfitPct": [0.3, 0.5, 0.8],
}


def load_cache():
    with open(CACHE, encoding="utf-8") as f:
        return json.load(f)


def ma(values, n):
    return sum(values[-n:]) / n if len(values) >= n else None


def evaluate(params, snaps, start, end):
    """对一只股票的日K序列，在 [start,end) 日期窗口内统计做T/反T信号与配对结果。

    返回 (trades, total_profit, wins)：
      trades = T_BUY 次数 + RT_SELL 次数
      total_profit = 各笔配对收益之和（%）
      wins = 配对成功次数
    """
    dates = [s["date"] for s in snaps]
    # 定位日期窗口 [start, end]
    lo, hi = 0, len(snaps)
    for i, d in enumerate(dates):
        if d >= start.isoformat():
            lo = i
            break
    for i in range(lo, len(dates)):
        if dates[i] > end.isoformat():
            hi = i
            break
    if hi - lo < 30:
        return 0, 0.0, 0

    trades = 0
    total_profit = 0.0
    wins = 0

    for i in range(lo, hi):
        # 需要足够历史计算 ma5/ma10 和 20 日高低点
        if i < 20:
            continue
        closes = [snaps[j]["close"] for j in range(i - 19, i + 1)]
        ma5 = ma(closes, 5)
        ma10 = ma(closes, 10)
        if ma5 is None or ma10 is None or ma5 <= 0 or ma10 <= 0:
            continue
        win = snaps[i - 19:i + 1]
        recent_low = min(s["low"] for s in win)
        recent_high = max(s["high"] for s in win)
        close = snaps[i]["close"]

        support = max(recent_low, ma5 * params["supportMa5Factor"], ma10 * params["supportMa10Factor"])
        resistance = min(recent_high, ma5 * params["resistanceMa5Factor"], ma10 * params["resistanceMa10Factor"])

        # 未来 N 日观察窗（配对判断；T+1 天然满足）
        future = snaps[i + 1:i + 1 + HOLD_N]
        if not future:
            continue
        future_high = max(s["high"] for s in future)
        future_low = min(s["low"] for s in future)
        future_close = future[-1]["close"]

        # ── T_BUY：贴近支撑 + 预期到 ma5 的收益 ──
        if support > 0:
            price_to_support = (close - support) / support
            expected = (ma5 - close) / close * 100 if close > 0 else 0.0
            if price_to_support < params["nearSupportThreshold"] and expected > params["minExpectedProfitPct"]:
                trades += 1
                buy = close
                target = buy * (1 + params["pairProfitPct"] / 100)
                if future_high >= target:
                    total_profit += params["pairProfitPct"]
                    wins += 1
                elif FAIL_IS_LOSS:
                    total_profit += (future_close - buy) / buy * 100

        # ── RT_SELL：贴近阻力 + 预期从 ma5 回落 ──
        if resistance > 0:
            price_to_resistance = (resistance - close) / resistance
            expected = (close - ma5) / close * 100 if close > 0 else 0.0
            if price_to_resistance < params["nearResistanceThreshold"] and expected > params["minExpectedProfitPct"]:
                trades += 1
                sell = close
                target = sell * (1 - params["pairProfitPct"] / 100)
                if future_low <= target:
                    total_profit += params["pairProfitPct"]
                    wins += 1
                elif FAIL_IS_LOSS:
                    total_profit += (sell - future_close) / sell * 100

    return trades, total_profit, wins


def run_config(cache, params, start, end):
    """对所有股票统计指定窗口内的做T总收益。返回 (trades, profit, wins)"""
    trades, profit, wins = 0, 0.0, 0
    for code, meta in cache.items():
        if code in INDEX_CODES:
            continue
        snaps = meta.get("snaps", [])
        if not snaps:
            continue
        t, p, w = evaluate(params, snaps, start, end)
        trades += t
        profit += p
        wins += w
    return trades, profit, wins


def score(profit, trades, wins):
    """综合评分：优先胜率与净收益，惩罚样本不足"""
    if trades < MIN_TRADES:
        return -1e9
    win_rate = wins / trades if trades else 0.0
    avg = profit / trades if trades else 0.0
    return profit * (0.4 + 0.6 * win_rate) - (trades * 0.1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-days", type=int, default=0, help="小规模验证：只统计最近 N 天")
    args = ap.parse_args()

    cache = load_cache()
    print(f"📦 数据加载完成：{len(cache)} 只（含指数），将过滤 {len(INDEX_CODES)} 个指数")
    print(f"训练段 {TRAIN_START} ~ {SPLIT} | 验证段 {SPLIT} ~ {END} | 配对观察 {HOLD_N} 日\n")

    best = dict(DEFAULT)
    if args.max_days > 0:
        END2 = date(2026, 8, 15)
        SPLIT2 = date(2026, 7, 15)
        train_start = date(2026, 5, 15)
    else:
        END2, SPLIT2, train_start = END, SPLIT, TRAIN_START

    results = {}
    for key, grid in SCAN.items():
        row = {}
        for val in grid:
            cand = dict(best)
            cand[key] = val
            t, p, w = run_config(cache, cand, train_start, SPLIT2)
            sc = score(p, t, w)
            row[val] = dict(trades=t, profit=round(p, 1), wins=w, score=round(sc, 1))
        best_val = max(row, key=lambda v: row[v]["score"])
        print(f"{key:24s} 最优={best_val}  {row}")
        if row[best_val]["score"] > -1e8:
            best[key] = best_val
        results[key] = row

    # ── 样本外验证（训练段拟合的参数在验证段的表现） ──
    val_t, val_p, val_w = run_config(cache, best, SPLIT2, END2)
    val_win = val_w / val_t * 100 if val_t else 0.0
    val_avg = val_p / val_t if val_t else 0.0
    print("\n════════ 最优参数（训练段拟合） ════════")
    for k, v in best.items():
        print(f"  {k} = {v}")

    print("\n════════ 样本外验证（验证段） ════════")
    print(f"  信号数: {val_t} | 配对成功: {val_w} | 胜率: {val_win:.1f}% | 总收益: {val_p:.1f}% | 平均每笔: {val_avg:.2f}%")

    # 全样本再跑一遍，给出最终参数
    all_t, all_p, all_w = run_config(cache, best, TRAIN_START, END)
    all_win = all_w / all_t * 100 if all_t else 0.0
    all_avg = all_p / all_t if all_t else 0.0
    print(f"\n════════ 全样本（{TRAIN_START} ~ {END}）════════")
    print(f"  信号数: {all_t} | 配对成功: {all_w} | 胜率: {all_win:.1f}% | 总收益: {all_p:.1f}% | 平均每笔: {all_avg:.2f}%")

    # 输出可合并的 t_trade 区块（仅含拟合到的核心阈值，其余走 APK 默认）
    t_trade = {
        "supportMa5Factor": best["supportMa5Factor"],
        "supportMa10Factor": best["supportMa10Factor"],
        "resistanceMa5Factor": best["resistanceMa5Factor"],
        "resistanceMa10Factor": best["resistanceMa10Factor"],
        "nearSupportThreshold": best["nearSupportThreshold"],
        "nearResistanceThreshold": best["nearResistanceThreshold"],
        "minExpectedProfitPct": best["minExpectedProfitPct"],
        "pairProfitPct": best["pairProfitPct"],
    }
    print("\n════════ 合并到 backtest_params.json 的 t_trade 区块 ════════")
    print(json.dumps({"t_trade": t_trade}, ensure_ascii=False, indent=2))

    os.makedirs(RECORD_DIR, exist_ok=True)
    out = {
        "updated": date.today().isoformat(),
        "train": [str(TRAIN_START), str(SPLIT)],
        "valid": [str(SPLIT), str(END)],
        "hold_n": HOLD_N,
        "params": best,
        "scan_results": results,
        "validation": {"trades": val_t, "wins": val_w, "profit": round(val_p, 1)},
        "full_sample": {"trades": all_t, "wins": all_w, "profit": round(all_p, 1)},
    }
    rec = os.path.join(RECORD_DIR, "ttrade_params.json")
    with open(rec, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"\n💾 已保存扫描结果 → {rec}")


if __name__ == "__main__":
    main()
