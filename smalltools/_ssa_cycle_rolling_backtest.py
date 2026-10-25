# -*- coding: utf-8 -*-
"""
SSA cycle 一阶导数信号 —— **无未来**滚动口径验证（抽样）
=========================================================
与 _ssa_cycle_signal_backtest.py（整窗分解，乐观上限）对照：
本脚本每个 t 只用 close[0..t] 重新做 SSA 分解，取末端两个 cycle 值算 vel[t]，
信号在 t 收盘即可见，**全程无未来函数**，是实战可得的口径。

抽样 30 只（种子固定），统计：
  A 命中率：信号后 10 根内出现同向价格拐点 vs 随机基准
  C 条件收益：信号后 5/10/20 根收益 vs 无条件均值
  D 策略：金叉买死叉卖（收盘成交，FEE 双边）vs 买入持有

末端两点对角平均（k=N-1 只 1 个元素；k=N-2 有 2 个元素），无需完整对角化，O(1) 取末端。
"""
import json
import os
import random
import sys
from multiprocessing import Pool

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from _chanlun_engine import load_klines                     # noqa: E402
from _chanlun_backtest import FEE, POOL_JSON, WORKERS       # noqa: E402

L = 30
PIVOT_W = 5
HIT_N = 10
FWD = (5, 10, 20)
WARM = 60          # 滚动分解暖机：t < 60 不出信号
SAMPLE = 30        # 抽样只数
MIN_BARS = 300


def cycle_tail2(x: np.ndarray):
    """用 close[0..t] 整段分解，只取末端两个 cycle 值（供 vel[t]）。
    对角平均：k=N-1 → 1 个元素 Y[K-1][L-1]；k=N-2 → (Y[K-1][L-2]+Y[K-2][L-1])/2。"""
    n = x.size
    K = n - L + 1
    X = np.lib.stride_tricks.sliding_window_view(x, L)     # (K, L)
    G = X.T @ X
    eigvals, eigvecs = np.linalg.eigh(G)
    order = np.argsort(eigvals)[::-1]
    c_last = 0.0
    c_prev = 0.0
    for rank in (1, 2):
        col = order[rank]
        if eigvals[col] <= 1e-12:
            continue
        v = eigvecs[:, col]
        a_last = X[K - 1] @ v
        a_prev = X[K - 2] @ v if K >= 2 else 0.0
        c_last += a_last * v[L - 1]
        c_prev2 = (a_last * v[L - 2] + a_prev * v[L - 1]) / 2.0 if L >= 2 and K >= 2 else 0.0
        c_prev += c_prev2
    return c_prev, c_last


def pivots(close, w=PIVOT_W):
    n = close.size
    bot = np.zeros(n, dtype=bool)
    top = np.zeros(n, dtype=bool)
    for t in range(w, n - w):
        seg = close[t - w:t + w + 1]
        if close[t] == seg.min() and close[t] < close[t - 1] and close[t] < close[t + 1]:
            bot[t] = True
        if close[t] == seg.max() and close[t] > close[t - 1] and close[t] > close[t + 1]:
            top[t] = True
    return bot, top


def analyze(code):
    bars = load_klines(code, "day")
    if not bars or len(bars) < MIN_BARS:
        return None
    close = np.array([b["close"] for b in bars], dtype=float)
    n = close.size

    # 逐 t 滚动：vel[t] 只用 close[0..t]
    vel = np.full(n, np.nan)
    for t in range(WARM, n):
        c_prev, c_last = cycle_tail2(close[:t + 1])
        if t >= 1 and not np.isnan(c_prev):
            # vel[t] = cycle_t[t] - cycle_t[t-1]（同一次分解的末端两点，严格无未来）
            vel[t] = c_last - c_prev

    bot, top = pivots(close)
    lo, hi = max(WARM, PIVOT_W), n - PIVOT_W - HIT_N - 1

    sig = {"vel_bottom": [], "vel_top": [], "golden": [], "dead": []}
    for t in range(lo, hi):
        if np.isnan(vel[t]) or np.isnan(vel[t - 1]) or np.isnan(vel[t - 2]):
            continue
        if vel[t - 1] < vel[t - 2] and vel[t] > vel[t - 1]:
            sig["vel_bottom"].append(t)
        if vel[t - 1] > vel[t - 2] and vel[t] < vel[t - 1]:
            sig["vel_top"].append(t)
        if vel[t - 1] < 0 <= vel[t]:
            sig["golden"].append(t)
        if vel[t - 1] > 0 >= vel[t]:
            sig["dead"].append(t)
    sig_set = {k: set(v) for k, v in sig.items()}

    def hit_rate(idx, mask):
        if not idx:
            return None, 0
        return sum(1 for t in idx if mask[t + 1:t + HIT_N + 1].any()) / len(idx), len(idx)

    def rand_base(mask, k, trials=200):
        rng = random.Random(42)
        space = list(range(lo, hi))
        if not space or k == 0:
            return None
        k = min(k, len(space))
        return sum(sum(1 for t in rng.sample(space, k) if mask[t + 1:t + HIT_N + 1].any()) / k
                   for _ in range(trials)) / trials

    def fwd(idx):
        out = {}
        for h in FWD:
            rs = [close[t + h] / close[t] - 1 for t in idx if t + h < n]
            out[h] = {"mean": float(np.mean(rs)), "win": float(np.mean([r > 0 for r in rs])),
                      "cnt": len(rs)} if rs else None
        return out

    base = {h: float(np.mean([close[t + h] / close[t] - 1
                              for t in range(lo, hi) if t + h < n])) for h in FWD}

    trades = []
    pos = None
    for t in range(lo, n - 1):
        if pos is None and t in sig_set["golden"]:
            pos = close[t]
        elif pos is not None and t in sig_set["dead"]:
            trades.append(close[t] / pos - 1 - FEE)
            pos = None
    if pos is not None:
        trades.append(close[-1] / pos - 1 - FEE)
    buy_hold = close[-1] / close[lo] - 1

    hr_gb, cnt_gb = hit_rate(sig["vel_bottom"], bot)
    hr_gt, cnt_gt = hit_rate(sig["vel_top"], top)
    hr_xb, cnt_xb = hit_rate(sig["golden"], bot)
    hr_xt, cnt_xt = hit_rate(sig["dead"], top)

    return {
        "code": code, "n": n,
        "sig_cnt": {k: len(v) for k, v in sig.items()},
        "hit": {"vel_bottom→底": hr_gb, "rand_b": rand_base(bot, cnt_gb),
                "vel_top→顶": hr_gt, "rand_t": rand_base(top, cnt_gt),
                "金叉→底": hr_xb, "rand_gb": rand_base(bot, cnt_xb),
                "死叉→顶": hr_xt, "rand_xt": rand_base(top, cnt_xt)},
        "fwd": {"vel_bottom": fwd(sig["vel_bottom"]), "vel_top": fwd(sig["vel_top"]),
                "golden": fwd(sig["golden"]), "dead": fwd(sig["dead"]), "base": base},
        "strat": {"trades": len(trades),
                  "win": float(np.mean([r > 0 for r in trades])) if trades else None,
                  "avg": float(np.mean(trades)) if trades else None,
                  "total": float(np.prod([1 + r for r in trades]) - 1) if trades else None,
                  "buy_hold": float(buy_hold)},
    }


def agg(vals):
    vals = [v for v in vals if v is not None]
    return float(np.mean(vals)) if vals else None


def main():
    pool_cfg = json.load(open(POOL_JSON, encoding="utf-8"))
    codes = pool_cfg.get("codes") or pool_cfg.get("stocks")
    random.Random(7).shuffle(codes)
    codes = codes[:SAMPLE]
    print(f"无未来滚动口径：抽样 {len(codes)} 只，L={L}，暖机 {WARM}，拐点窗 ±{PIVOT_W}")
    with Pool(WORKERS) as p:
        results = p.map(analyze, codes)
    results = [r for r in results if r]
    print(f"有效样本 {len(results)} 只")

    def g(key):
        return [r["hit"][key][0] if isinstance(r["hit"][key], tuple) else r["hit"][key]
                for r in results]

    rep = {
        "params": {"L": L, "WARM": WARM, "PIVOT_W": PIVOT_W, "HIT_N": HIT_N,
                   "FEE": FEE, "stocks": len(results)},
        "hit_rate": {
            "vel谷底→价格底": agg(g("vel_bottom→底")), "随机基准": agg(g("rand_b")),
            "vel峰顶→价格顶": agg(g("vel_top→顶")), "随机基准_": agg(g("rand_t")),
            "金叉→价格底": agg(g("金叉→底")), "随机基准__": agg(g("rand_gb")),
            "死叉→价格顶": agg(g("死叉→顶")), "随机基准___": agg(g("rand_xt")),
        },
        "fwd_ret": {},
        "strategy": {
            "金叉买死叉卖": {
                "平均单笔%": agg([r["strat"]["avg"] * 100 for r in results if r["strat"]["avg"] is not None]),
                "胜率%": agg([r["strat"]["win"] * 100 for r in results if r["strat"]["win"] is not None]),
                "平均交易数": agg([r["strat"]["trades"] for r in results]),
                "复合总收益%": agg([r["strat"]["total"] * 100 for r in results if r["strat"]["total"] is not None]),
            },
            "买入持有": {"总收益%": agg([r["strat"]["buy_hold"] * 100 for r in results])},
        },
    }
    for name in ("vel_bottom", "vel_top", "golden", "dead"):
        rep["fwd_ret"][name] = {f"{h}根": {"均值%": agg([r["fwd"][name][h]["mean"] * 100
                                                        for r in results if r["fwd"][name][h]]),
                                        "胜率%": agg([r["fwd"][name][h]["win"] * 100
                                                        for r in results if r["fwd"][name][h]])}
                                for h in FWD}
    rep["fwd_ret"]["无条件基准"] = {f"{h}根均值%": agg([r["fwd"]["base"][h] * 100
                                                      for r in results if r["fwd"]["base"][h] is not None])
                                   for h in FWD}

    out = os.path.join(HERE, "_records", "_ssa_cycle_rolling_report.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({"summary": rep, "per_stock": results}, open(out, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(json.dumps(rep, ensure_ascii=False, indent=2))
    print(f"\n明细已存 {out}")


if __name__ == "__main__":
    main()
