# -*- coding: utf-8 -*-
"""
Kalman 局部线性趋势（LLT）模型信号回测 —— 对照 SSA 滚动口径
============================================================
状态空间模型：x = [level, slope]ᵀ，F=[[1,1],[0,1]]，H=[1,0]
  level ≈ 趋势（位移），slope ≈ 速度（一阶导数，Kalman 递推天然无未来）
  Q = q·[[1/3,1/2],[1/2,1]]（连续白噪声加速度离散化），R = 前 WARM 根收益方差
  平滑度由 q/R 控制（q_ratio），默认 0.01；附录扫 0.003/0.03 看敏感性。

信号与 _ssa_cycle_rolling_backtest.py 完全同口径：
  slope 谷底/峰顶（局部极值）、slope 上穿/下穿零轴（金叉/死叉）
  A 命中率 vs 随机基准；C 条件收益 5/10/20 根；D 金叉买死叉卖 vs 买入持有
抽样与 SSA 滚动版相同（种子 7，前 30 只），结果可直接对比。
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

PIVOT_W = 5
HIT_N = 10
FWD = (5, 10, 20)
WARM = 60
SAMPLE = 30
MIN_BARS = 300
Q_RATIO = 0.01


def kalman_llt(close: np.ndarray, q_ratio: float = Q_RATIO):
    """局部线性趋势 Kalman 滤波，返回 (level, slope)。全程递推，无未来。"""
    n = close.size
    r = float(np.var(np.diff(close[:WARM])))     # 观测噪声：暖机段收益方差
    r = max(r, 1e-8)
    q = r * q_ratio
    F = np.array([[1.0, 1.0], [0.0, 1.0]])
    H = np.array([1.0, 0.0])
    Q = q * np.array([[1.0 / 3.0, 0.5], [0.5, 1.0]])
    x = np.array([close[0], 0.0])
    P = np.eye(2)
    level = np.zeros(n)
    slope = np.zeros(n)
    I2 = np.eye(2)
    for t in range(n):
        x = F @ x
        P = F @ P @ F.T + Q
        innov = close[t] - x[0]
        S = P[0, 0] + r
        K = np.array([P[0, 0] / S, P[1, 0] / S])
        x = x + K * innov
        P = (I2 - np.outer(K, H)) @ P
        level[t] = x[0]
        slope[t] = x[1]
    return level, slope


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

    _, slope = kalman_llt(close)
    bot, top = pivots(close)
    lo, hi = max(WARM, PIVOT_W), n - PIVOT_W - HIT_N - 1

    sig = {"vel_bottom": [], "vel_top": [], "golden": [], "dead": []}
    for t in range(lo, hi):
        v0, v1, v2 = slope[t - 2], slope[t - 1], slope[t]
        if v1 < v0 and v2 > v1:
            sig["vel_bottom"].append(t)
        if v1 > v0 and v2 < v1:
            sig["vel_top"].append(t)
        if v1 < 0 <= v2:
            sig["golden"].append(t)
        if v1 > 0 >= v2:
            sig["dead"].append(t)
    sig_set = {k: set(v) for k, v in sig.items()}

    def hit_rate(idx, mask):
        if not idx:
            return None
        return sum(1 for t in idx if mask[t + 1:t + HIT_N + 1].any()) / len(idx)

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
            out[h] = {"mean": float(np.mean(rs)), "win": float(np.mean([r > 0 for r in rs]))} if rs else None
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

    # 敏感性：另外两个 q_ratio 只跑策略
    sens = {}
    for qr in (0.003, 0.03):
        _, sl = kalman_llt(close, qr)
        tr_list = []
        pos2 = None
        for t in range(lo, n - 1):
            if pos2 is None and sl[t - 1] < 0 <= sl[t]:
                pos2 = close[t]
            elif pos2 is not None and sl[t - 1] > 0 >= sl[t]:
                tr_list.append(close[t] / pos2 - 1 - FEE)
                pos2 = None
        if pos2 is not None:
            tr_list.append(close[-1] / pos2 - 1 - FEE)
        sens[str(qr)] = {"trades": len(tr_list),
                         "win": float(np.mean([x > 0 for x in tr_list])) if tr_list else None,
                         "total": float(np.prod([1 + x for x in tr_list]) - 1) if tr_list else None}

    return {
        "code": code, "n": n,
        "sig_cnt": {k: len(v) for k, v in sig.items()},
        "hit": {"vel_bottom→底": hit_rate(sig["vel_bottom"], bot),
                "rand_b": rand_base(bot, len(sig["vel_bottom"])),
                "vel_top→顶": hit_rate(sig["vel_top"], top),
                "rand_t": rand_base(top, len(sig["vel_top"])),
                "金叉→底": hit_rate(sig["golden"], bot),
                "rand_gb": rand_base(bot, len(sig["golden"])),
                "死叉→顶": hit_rate(sig["dead"], top),
                "rand_xt": rand_base(top, len(sig["dead"]))},
        "fwd": {"vel_bottom": fwd(sig["vel_bottom"]), "vel_top": fwd(sig["vel_top"]),
                "golden": fwd(sig["golden"]), "dead": fwd(sig["dead"]), "base": base},
        "strat": {"trades": len(trades),
                  "win": float(np.mean([r > 0 for r in trades])) if trades else None,
                  "avg": float(np.mean(trades)) if trades else None,
                  "total": float(np.prod([1 + r for r in trades]) - 1) if trades else None,
                  "buy_hold": float(buy_hold)},
        "sens": sens,
    }


def agg(vals):
    vals = [v for v in vals if v is not None]
    return float(np.mean(vals)) if vals else None


def main():
    pool_cfg = json.load(open(POOL_JSON, encoding="utf-8"))
    codes = pool_cfg.get("codes") or pool_cfg.get("stocks")
    random.Random(7).shuffle(codes)          # 与 SSA 滚动版同种子同顺序
    codes = codes[:SAMPLE]
    print(f"Kalman LLT 无未来口径：抽样 {len(codes)} 只，q_ratio={Q_RATIO}，暖机 {WARM}")
    with Pool(WORKERS) as p:
        results = p.map(analyze, codes)
    results = [r for r in results if r]
    print(f"有效样本 {len(results)} 只")

    rep = {
        "params": {"model": "Kalman LLT", "q_ratio": Q_RATIO, "WARM": WARM,
                   "PIVOT_W": PIVOT_W, "HIT_N": HIT_N, "FEE": FEE, "stocks": len(results)},
        "hit_rate": {
            "slope谷底→价格底": agg([r["hit"]["vel_bottom→底"] for r in results]),
            "随机基准": agg([r["hit"]["rand_b"] for r in results]),
            "slope峰顶→价格顶": agg([r["hit"]["vel_top→顶"] for r in results]),
            "随机基准_": agg([r["hit"]["rand_t"] for r in results]),
            "金叉(slope上穿0)→价格底": agg([r["hit"]["金叉→底"] for r in results]),
            "随机基准__": agg([r["hit"]["rand_gb"] for r in results]),
            "死叉(slope下穿0)→价格顶": agg([r["hit"]["死叉→顶"] for r in results]),
            "随机基准___": agg([r["hit"]["rand_xt"] for r in results]),
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
        "sensitivity_q_ratio": {},
    }
    for name in ("vel_bottom", "vel_top", "golden", "dead"):
        rep["fwd_ret"][name] = {f"{h}根": {"均值%": agg([r["fwd"][name][h]["mean"] * 100
                                                        for r in results if r["fwd"][name][h]]),
                                        "胜率%": agg([r["fwd"][name][h]["win"] * 100
                                                        for r in results if r["fwd"][name][h]])}
                                for h in FWD}
    rep["fwd_ret"]["无条件基准"] = {f"{h}根均值%": agg([r["fwd"]["base"][h] * 100 for r in results])
                                   for h in FWD}
    for qr in ("0.003", "0.03"):
        rep["sensitivity_q_ratio"][qr] = {
            "金叉买死叉卖-复合总收益%": agg([r["sens"][qr]["total"] * 100 for r in results
                                             if r["sens"][qr]["total"] is not None]),
            "金叉买死叉卖-胜率%": agg([r["sens"][qr]["win"] * 100 for r in results
                                       if r["sens"][qr]["win"] is not None]),
        }

    out = os.path.join(HERE, "_records", "_kalman_llt_report.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({"summary": rep, "per_stock": results}, open(out, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(json.dumps(rep, ensure_ascii=False, indent=2))
    print(f"\n明细已存 {out}")


if __name__ == "__main__":
    main()
