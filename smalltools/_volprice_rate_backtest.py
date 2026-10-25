# -*- coding: utf-8 -*-
"""量价速率（单位涨幅成交量）拐点判断回测 — 池级验证

信号口径（与 APK「量价策略」页一致）：
  rate_i    = 当日成交量(万手) / max(|涨跌幅%|, 0.05)，按自身 95 分位封顶
  upRate5   = 近 5 根「涨K」的 rate 均值   （上涨费力程度）
  downRate5 = 近 5 根「跌K」的 rate 均值   （下跌砸盘力度）
  pct120(x) = 在过去 120 根中的分位(0~100)

买点（底信号）：
  收盘在 20 日最低价 +3% 区域内 且 downRate5 分位 <= 30（砸不出量）且 当日止跌(收>=昨收)
  → 次日开盘买入
卖点（顶信号）：
  收盘在 20 日最高价 -3% 区域内 且 upRate5 分位 >= 70（费量推涨）且 当日回落(收<昨收)
  → 次日开盘卖出；另加 止损-8% / 超时60根 强制离场

评估：
  1) 拐点命中率：5% zigzag 拐点 ±3 根内有信号 = 命中（recall）；信号落在拐点附近 = 精准（precision）
  2) 收益：池级逐笔回测（FEE 双边），胜率/均值/盈亏比/最大回撤/年化，对比等权买入持有

用法：
  python smalltools/_volprice_rate_backtest.py            # 池级
  python smalltools/_volprice_rate_backtest.py 600519     # 单票明细
"""
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from _chanlun_engine import load_klines                     # noqa: E402
from _chanlun_backtest import FEE, POOL_JSON, WORKERS       # noqa: E402

# ── 参数 ──
ZIG = 5.0            # 拐点判定 zigzag 阈值 %
HIT_WIN = 3          # 拐点 ±3 根内算命中
STOP_LOSS = -0.08
TIMEOUT = 60


def pctile_rank(hist: list, x: float) -> float:
    """x 在 hist 中的分位 0~100"""
    if not hist:
        return 50.0
    c = sum(1 for v in hist if v <= x)
    return c * 100.0 / len(hist)


def compute(rows: list):
    """返回 (closes, opens, vols, rate, up5, down5)；up5/down5 无同向K时沿用前值"""
    n = len(rows := rows)
    closes = [r["close"] for r in rows]
    opens = [r["open"] for r in rows]
    vols = [r["volume"] / 1e6 for r in rows]           # 股 → 万手
    rate = [0.0] * n
    for i in range(1, n):
        dpct = abs((closes[i] - closes[i - 1]) / closes[i - 1] * 100.0)
        rate[i] = vols[i] / max(dpct, 0.05)
    cap = sorted(rate)[int(n * 0.95)] if n > 20 else (max(rate) or 1.0)
    rate = [min(r, cap) for r in rate]
    up5 = [None] * n
    down5 = [None] * n
    for i in range(n):
        f = max(0, i - 4)
        us = [rate[j] for j in range(f, i + 1) if j == 0 or closes[j] >= closes[j - 1]]
        ds = [rate[j] for j in range(f, i + 1) if j > 0 and closes[j] < closes[j - 1]]
        up5[i] = sum(us) / len(us) if us else (up5[i - 1] if i else rate[0])
        down5[i] = sum(ds) / len(ds) if ds else (down5[i - 1] if i else rate[0])
    return closes, opens, vols, rate, up5, down5


def zigzag_pivots(closes: list, th_pct: float):
    """简化 zigzag：返回 [(idx, is_high)] 已确认拐点"""
    n = len(closes)
    pivots = []
    last_p = closes[0]
    last_i = 0
    trend = 0
    hi, hi_i, lo, lo_i = closes[0], 0, closes[0], 0
    for i in range(1, n):
        p = closes[i]
        if trend >= 0:
            if p > hi:
                hi, hi_i = p, i
            if (hi - p) / hi * 100 >= th_pct:
                pivots.append((hi_i, True))
                trend = -1
                lo, lo_i = p, i
        if trend <= 0:
            if p < lo:
                lo, lo_i = p, i
            if (p - lo) / lo * 100 >= th_pct:
                pivots.append((lo_i, False))
                trend = 1
                hi, hi_i = p, i
    return pivots


def signals(closes, lows, highs, up5, down5):
    n = len(closes)
    buy_sig = set()
    sell_sig = set()
    for i in range(125, n - 1):
        lo20 = min(lows[i - 19:i + 1])
        hi20 = max(highs[i - 19:i + 1])
        up_hist = [v for v in up5[max(0, i - 119):i + 1] if v is not None]
        dn_hist = [v for v in down5[max(0, i - 119):i + 1] if v is not None]
        up_pct = pctile_rank(up_hist, up5[i])
        dn_pct = pctile_rank(dn_hist, down5[i])
        if closes[i] <= lo20 * 1.03 and dn_pct <= 30 and closes[i] >= closes[i - 1]:
            buy_sig.add(i)
        if closes[i] >= hi20 * 0.97 and up_pct >= 70 and closes[i] < closes[i - 1]:
            sell_sig.add(i)
    return buy_sig, sell_sig


def backtest_one(code: str) -> dict | None:
    rows = load_klines(code, "day")
    if not rows or len(rows) < 200:
        return None
    rows = [r for r in rows if r.get("volume", 0) > 0]
    if len(rows) < 200:
        return None
    closes, opens, vols, rate, up5, down5 = compute(rows)
    lows = [r["low"] for r in rows]
    highs = [r["high"] for r in rows]
    n = len(closes)
    buy_sig, sell_sig = signals(closes, lows, highs, up5, down5)

    # ── 拐点命中 ──
    pivots = zigzag_pivots(closes, ZIG)
    tops = [p for p in pivots if p[1]]
    bots = [p for p in pivots if not p[1]]
    def near(idx_set, pidx):
        return any(abs(s - pidx) <= HIT_WIN for s in idx_set)
    bot_hit = sum(1 for i, _ in bots if near(buy_sig, i))
    top_hit = sum(1 for i, _ in tops if near(sell_sig, i))
    buy_prec = sum(1 for s in buy_sig if any(abs(s - i) <= HIT_WIN for i, _ in bots))
    sell_prec = sum(1 for s in sell_sig if any(abs(s - i) <= HIT_WIN for i, _ in tops))

    # ── 逐笔回测 ──
    trades = []
    i = 126
    pos = None  # (buy_i_exec, buy_px)
    while i < n - 1:
        if pos is None:
            if i in buy_sig:
                px = opens[i + 1] * (1 + FEE)
                pos = (i + 1, px)
                i += 1
                continue
            i += 1
        else:
            bi, bpx = pos
            exit_i = None
            if i in sell_sig:
                exit_i = i + 1
            elif closes[i] / bpx - 1 <= STOP_LOSS:
                exit_i = i + 1
            elif i - bi >= TIMEOUT:
                exit_i = i + 1
            if exit_i is not None and exit_i < n:
                spx = opens[exit_i] * (1 - FEE)
                trades.append({"buy_i": bi, "sell_i": exit_i,
                               "ret": spx / bpx - 1.0, "hold": exit_i - bi})
                pos = None
                i = exit_i
                continue
            i += 1
    bh = closes[-1] / closes[126] - 1.0   # 该票同期买入持有
    return {
        "code": code, "n": n, "trades": trades, "bh": bh,
        "bots": len(bots), "tops": len(tops),
        "bot_hit": bot_hit, "top_hit": top_hit,
        "buy_n": len(buy_sig), "sell_n": len(sell_sig),
        "buy_prec": buy_prec, "sell_prec": sell_prec,
    }


def pct(a, b):
    return f"{a * 100.0 / b:.1f}%" if b else "-"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if args:
        codes = [args[0]]
    else:
        p = json.load(open(POOL_JSON, encoding="utf-8"))
        codes = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]

    results = []
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = {ex.submit(backtest_one, c): c for c in codes}
        for i, f in enumerate(as_completed(futs), 1):
            r = f.result()
            if r:
                results.append(r)
            if i % 20 == 0:
                print(f"  {i}/{len(codes)}", flush=True)

    if not results:
        print("无结果")
        return

    # ── 汇总 ──
    all_tr = [t for r in results for t in r["trades"]]
    bots = sum(r["bots"] for r in results)
    tops = sum(r["tops"] for r in results)
    bot_hit = sum(r["bot_hit"] for r in results)
    top_hit = sum(r["top_hit"] for r in results)
    buy_n = sum(r["buy_n"] for r in results)
    sell_n = sum(r["sell_n"] for r in results)
    buy_prec = sum(r["buy_prec"] for r in results)
    sell_prec = sum(r["sell_prec"] for r in results)

    print(f"\n═══ 股票数 {len(results)} ═══")
    print(f"\n【拐点命中】(zigzag {ZIG}% 拐点，±{HIT_WIN} 根内算命中)")
    print(f"  底部拐点 {bots} 个，底信号命中 {bot_hit} → 召回 {pct(bot_hit, bots)}")
    print(f"  顶部拐点 {tops} 个，顶信号命中 {top_hit} → 召回 {pct(top_hit, tops)}")
    print(f"  底信号 {buy_n} 次，落在底部拐点旁 {buy_prec} → 精准 {pct(buy_prec, buy_n)}")
    print(f"  顶信号 {sell_n} 次，落在顶部拐点旁 {sell_prec} → 精准 {pct(sell_prec, sell_n)}")

    if all_tr:
        wins = [t for t in all_tr if t["ret"] > 0]
        losses = [t for t in all_tr if t["ret"] <= 0]
        avg = sum(t["ret"] for t in all_tr) / len(all_tr)
        avg_w = sum(t["ret"] for t in wins) / len(wins) if wins else 0.0
        avg_l = sum(t["ret"] for t in losses) / len(losses) if losses else 0.0
        pl = abs(avg_w / avg_l) if avg_l else 0.0
        avg_hold = sum(t["hold"] for t in all_tr) / len(all_tr)
        print(f"\n【收益回测】(FEE={FEE} 双边，共 {len(all_tr)} 笔)")
        print(f"  胜率 {len(wins)}/{len(all_tr)} = {len(wins) * 100.0 / len(all_tr):.1f}%")
        print(f"  单笔均值 {avg * 100:+.2f}%｜平均盈利 {avg_w * 100:+.2f}%｜平均亏损 {avg_l * 100:+.2f}%｜盈亏比 {pl:.2f}")
        print(f"  平均持仓 {avg_hold:.0f} 天")
        bh_avg = sum(r["bh"] for r in results) / len(results)
        contrib = sum(sum(t["ret"] for t in r["trades"]) for r in results) / len(results)
        print(f"  每票信号累计收益均值 {contrib * 100:+.1f}%｜同期等权买入持有均值 {bh_avg * 100:+.1f}%")

    out = os.path.join(HERE, "_records", "_volprice_rate_report.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({"params": {"zig": ZIG, "hit_win": HIT_WIN, "stop": STOP_LOSS, "timeout": TIMEOUT, "fee": FEE},
               "stocks": len(results),
               "bots": bots, "tops": tops, "bot_hit": bot_hit, "top_hit": top_hit,
               "buy_n": buy_n, "sell_n": sell_n, "buy_prec": buy_prec, "sell_prec": sell_prec,
               "trades_n": len(all_tr),
               "win_rate": round(len([t for t in all_tr if t["ret"] > 0]) / len(all_tr), 4) if all_tr else None,
               "avg_ret": round(sum(t["ret"] for t in all_tr) / len(all_tr), 4) if all_tr else None,
               }, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"\n报告已存 {out}")


if __name__ == "__main__":
    main()
