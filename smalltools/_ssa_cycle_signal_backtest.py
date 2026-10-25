# -*- coding: utf-8 -*-
"""
SSA cycle 分量一阶导数（速度）信号回测
=====================================
与 App 量价策略页③号图同口径：整窗 SSA 分解（Gram=XᵀX 特征分解，L=30，
分量0=趋势、分量1+2 配对 = cycle），vel = cycle 一阶差分。

验证 4 类信号对**价格拐点**的预测能力：
  S1 速度谷底（vel 局部极小，确认于 t）→ 预测价格底
  S2 速度峰顶（vel 局部极大）          → 预测价格顶
  S3 金叉（vel 上穿零轴）              → 周期走强
  S4 死叉（vel 下穿零轴）              → 周期走弱

统计口径：
  A 命中率：信号后 10 根内出现同向价格拐点的比例（随机基准对照）
  B 召回率：价格拐点前 10 根内出现对应信号的比例
  C 条件收益：信号后 5/10/20 根收益 vs 全样本无条件均值
  D 策略：金叉买死叉卖（收盘成交，FEE 双边）vs 买入持有

⚠️ 整窗分解含未来信息，本报告是**乐观上限**（与 App 图一致的可视化口径）；
   无未来口径需逐 K 滚动分解（SsaEngine.run），成本 O(N²)，另行抽样验证。
"""
import json
import math
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

L = 30            # SSA 嵌入窗口（与 App cyclePeriod 默认一致）
PIVOT_W = 5       # 价格拐点确认窗：t 为 ±5 根内极值
HIT_N = 10        # 信号→拐点命中窗口
FWD = (5, 10, 20)  # 条件收益前瞻
EDGE = L          # 两端边缘效应区，信号与拐点都剔除
MIN_BARS = 200


# ── SSA cycle（与 SsaEngine.decompose 同口径：Gram 特征分解，分量1+2=cycle） ──
def ssa_cycle(x: np.ndarray, window: int = L) -> np.ndarray:
    n = x.size
    K = n - window + 1
    X = np.lib.stride_tricks.sliding_window_view(x, window)      # (K, L)
    G = X.T @ X
    eigvals, eigvecs = np.linalg.eigh(G)                  # 升序
    order = np.argsort(eigvals)[::-1]                     # 降序
    idx = (np.arange(K)[:, None] + np.arange(window)[None, :]).ravel()
    cnt = np.bincount(idx, minlength=n)
    cyc = np.zeros(n)
    for rank in (1, 2):
        col = order[rank]
        if eigvals[col] <= 1e-12:
            continue
        v = eigvecs[:, col]                               # (L,)
        a = X @ v                                         # (K,)
        Y = (a[:, None] * v[None, :]).ravel()
        cyc += np.bincount(idx, weights=Y, minlength=n)
    return cyc / cnt


def pivots(close: np.ndarray, w: int = PIVOT_W):
    """价格拐点 ground truth：底部 t ⟺ close[t]=min(close[t-w..t+w])；顶部对称。"""
    n = close.size
    bottom = np.zeros(n, dtype=bool)
    top = np.zeros(n, dtype=bool)
    for t in range(w, n - w):
        seg = close[t - w:t + w + 1]
        if close[t] == seg.min() and close[t] < close[t - 1] and close[t] < close[t + 1]:
            bottom[t] = True
        if close[t] == seg.max() and close[t] > close[t - 1] and close[t] > close[t + 1]:
            top[t] = True
    return bottom, top


def analyze(code: str) -> dict | None:
    bars = load_klines(code, "day")
    if not bars or len(bars) < MIN_BARS:
        return None
    close = np.array([b["close"] for b in bars], dtype=float)
    n = close.size
    cyc = ssa_cycle(close)
    vel = np.full(n, np.nan)
    vel[1:] = np.diff(cyc)

    bot, top = pivots(close)

    # ── 信号（t 收盘可见；剔除两端边缘区 & 末端验证窗不足的区段） ──
    lo, hi = max(EDGE, PIVOT_W), n - PIVOT_W - HIT_N - 1
    sig = {"vel_bottom": [], "vel_top": [], "golden": [], "dead": []}
    for t in range(lo, hi):
        if np.isnan(vel[t]) or np.isnan(vel[t - 1]) or (t >= 2 and np.isnan(vel[t - 2])):
            continue
        if t >= 2 and vel[t - 1] < vel[t - 2] and vel[t] > vel[t - 1]:
            sig["vel_bottom"].append(t)
        if t >= 2 and vel[t - 1] > vel[t - 2] and vel[t] < vel[t - 1]:
            sig["vel_top"].append(t)
        if vel[t - 1] < 0 <= vel[t]:
            sig["golden"].append(t)
        if vel[t - 1] > 0 >= vel[t]:
            sig["dead"].append(t)

    def hit_rate(sig_idx, target_mask):
        """信号后 HIT_N 根内出现目标拐点的比例"""
        if not sig_idx:
            return None, 0
        hits = sum(1 for t in sig_idx if target_mask[t + 1:t + HIT_N + 1].any())
        return hits / len(sig_idx), len(sig_idx)

    def random_baseline(target_mask, k: int, trials: int = 200):
        """随机抽 k 个点，后 HIT_N 根内出现拐点的平均命中率"""
        rng = random.Random(42)
        space = list(range(lo, hi))
        if len(space) < k or k == 0:
            return None
        tot = 0.0
        for _ in range(trials):
            sample = rng.sample(space, k)
            tot += sum(1 for t in sample if target_mask[t + 1:t + HIT_N + 1].any()) / k
        return tot / trials

    def recall(target_mask, kind):
        """拐点前 HIT_N 根内有信号（含当日）的比例"""
        idxs = np.where(target_mask)[0]
        idxs = [t for t in idxs if lo <= t < hi]
        if not idxs:
            return None, 0
        hits = sum(1 for t in idxs if sig_mask_range(t, kind))
        return hits / len(idxs), len(idxs)

    sig_set = {k: set(v) for k, v in sig.items()}

    def sig_mask_range(t, kind):
        s = sig_set[kind]
        return any((t - d) in s for d in range(0, HIT_N + 1))

    def fwd_rets(sig_idx):
        out = {}
        for h in FWD:
            rs = [close[t + h] / close[t] - 1 for t in sig_idx if t + h < n]
            if rs:
                out[h] = {"mean": float(np.mean(rs)), "median": float(np.median(rs)),
                          "win": float(np.mean([r > 0 for r in rs])), "cnt": len(rs)}
            else:
                out[h] = None
        return out

    # 无条件基准（同区间所有 t）
    base = {}
    for h in FWD:
        rs = [close[t + h] / close[t] - 1 for t in range(lo, hi) if t + h < n]
        base[h] = float(np.mean(rs)) if rs else None

    # ── 策略 D：金叉买死叉卖（收盘成交，FEE 双边），期末强制平仓 ──
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

    # vel 谷底买 / 峰顶卖（对照策略）
    trades2 = []
    pos2 = None
    for t in range(lo, n - 1):
        if pos2 is None and t in sig_set["vel_bottom"]:
            pos2 = close[t]
        elif pos2 is not None and t in sig_set["vel_top"]:
            trades2.append(close[t] / pos2 - 1 - FEE)
            pos2 = None
    if pos2 is not None:
        trades2.append(close[-1] / pos2 - 1 - FEE)

    hr_gb, cnt_gb = hit_rate(sig["vel_bottom"], bot)
    hr_gt, cnt_gt = hit_rate(sig["vel_top"], top)
    hr_xb, cnt_xb = hit_rate(sig["golden"], bot)
    hr_xt, cnt_xt = hit_rate(sig["dead"], top)

    return {
        "code": code, "n": n,
        "sig_cnt": {k: len(v) for k, v in sig.items()},
        "pivot_cnt": {"bottom": int(bot.sum()), "top": int(top.sum())},
        # A 命中率 vs 随机基准
        "hit": {
            "vel_bottom→底": hr_gb, "rand_bottom": random_baseline(bot, cnt_gb),
            "vel_top→顶": hr_gt, "rand_top": random_baseline(top, cnt_gt),
            "金叉→底": hr_xb, "rand_gb": random_baseline(bot, cnt_xb),
            "死叉→顶": hr_xt, "rand_xt": random_baseline(top, cnt_xt),
        },
        # B 召回率
        "recall": {
            "底←vel_bottom": recall(bot, "vel_bottom"),
            "顶→vel_top": recall(top, "vel_top"),
            "底→金叉": recall(bot, "golden"),
            "顶→死叉": recall(top, "dead"),
        },
        # C 条件收益 vs 无条件
        "fwd": {"vel_bottom": fwd_rets(sig["vel_bottom"]), "vel_top": fwd_rets(sig["vel_top"]),
                "golden": fwd_rets(sig["golden"]), "dead": fwd_rets(sig["dead"]),
                "base": base},
        # D 策略
        "strat": {
            "golden_dead": {"trades": len(trades),
                            "win": float(np.mean([r > 0 for r in trades])) if trades else None,
                            "avg": float(np.mean(trades)) if trades else None,
                            "total": float(np.prod([1 + r for r in trades]) - 1) if trades else None},
            "vel_pivot": {"trades": len(trades2),
                          "win": float(np.mean([r > 0 for r in trades2])) if trades2 else None,
                          "avg": float(np.mean(trades2)) if trades2 else None,
                          "total": float(np.prod([1 + r for r in trades2]) - 1) if trades2 else None},
            "buy_hold": float(buy_hold),
        },
    }


def agg_mean(vals):
    vals = [v for v in vals if v is not None and not (isinstance(v, float) and math.isnan(v))]
    return float(np.mean(vals)) if vals else None


def main():
    pool_cfg = json.load(open(POOL_JSON, encoding="utf-8"))
    codes = pool_cfg.get("codes") or pool_cfg.get("stocks") if isinstance(pool_cfg, dict) else pool_cfg
    print(f"票池 {len(codes)} 只，SSA L={L}，拐点窗 ±{PIVOT_W}，命中窗 {HIT_N}，费用 {FEE}")
    with Pool(WORKERS) as p:
        results = p.map(analyze, codes)
    results = [r for r in results if r]
    print(f"有效样本 {len(results)} 只")

    def g(path):
        out = []
        for r in results:
            cur = r
            for k in path:
                cur = cur.get(k) if isinstance(cur, dict) else None
                if cur is None:
                    break
            if isinstance(cur, tuple):
                out.append(cur[0])
            else:
                out.append(cur)
        return out

    rep = {
        "params": {"L": L, "PIVOT_W": PIVOT_W, "HIT_N": HIT_N, "FWD": FWD, "FEE": FEE,
                   "stocks": len(results)},
        "hit_rate": {
            "vel谷底→价格底": agg_mean(g(["hit", "vel_bottom→底"])),
            "随机基准(底)": agg_mean(g(["hit", "rand_bottom"])),
            "vel峰顶→价格顶": agg_mean(g(["hit", "vel_top→顶"])),
            "随机基准(顶)": agg_mean(g(["hit", "rand_top"])),
            "金叉→价格底": agg_mean(g(["hit", "金叉→底"])),
            "随机基准(金叉)": agg_mean(g(["hit", "rand_gb"])),
            "死叉→价格顶": agg_mean(g(["hit", "死叉→顶"])),
            "随机基准(死叉)": agg_mean(g(["hit", "rand_xt"])),
        },
        "recall": {
            "价格底前10根有vel谷底": agg_mean(g(["recall", "底←vel_bottom"])),
            "价格顶前10根有vel峰顶": agg_mean(g(["recall", "顶→vel_top"])),
            "价格底前10根有金叉": agg_mean(g(["recall", "底→金叉"])),
            "价格顶前10根有死叉": agg_mean(g(["recall", "顶→死叉"])),
        },
        "fwd_ret": {},
        "strategy": {
            "金叉买死叉卖": {"平均单笔%": None, "胜率%": None, "平均交易数": None,
                            "复合总收益%": None},
            "vel谷底买峰顶卖": {"平均交易数": None},
            "买入持有": {"总收益%": None},
        },
    }
    for name in ("vel_bottom", "vel_top", "golden", "dead"):
        rep["fwd_ret"][name] = {f"{h}根": {"均值%": agg_mean([r["fwd"][name][h]["mean"] * 100
                                                            for r in results if r["fwd"][name][h]]),
                                        "胜率%": agg_mean([r["fwd"][name][h]["win"] * 100
                                                            for r in results if r["fwd"][name][h]])}
                                for h in FWD}
    rep["fwd_ret"]["无条件基准"] = {f"{h}根均值%": agg_mean([r["fwd"]["base"][h] * 100
                                                          for r in results if r["fwd"]["base"][h] is not None])
                                   for h in FWD}
    for key, tag in (("golden_dead", "金叉买死叉卖"), ("vel_pivot", "vel谷底买峰顶卖")):
        rep["strategy"][tag] = {
            "平均单笔%": agg_mean([r["strat"][key]["avg"] * 100 for r in results
                                   if r["strat"][key]["avg"] is not None]),
            "胜率%": agg_mean([r["strat"][key]["win"] * 100 for r in results
                               if r["strat"][key]["win"] is not None]),
            "平均交易数": agg_mean([r["strat"][key]["trades"] for r in results]),
            "复合总收益%(中位)": agg_mean([r["strat"][key]["total"] * 100 for r in results
                                          if r["strat"][key]["total"] is not None]),
        }
    rep["strategy"]["买入持有"] = {
        "总收益%": agg_mean([r["strat"]["buy_hold"] * 100 for r in results])}

    out = os.path.join(HERE, "_records", "_ssa_cycle_signal_report.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({"summary": rep, "per_stock": results}, open(out, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(json.dumps(rep, ensure_ascii=False, indent=2))
    print(f"\n明细已存 {out}")


if __name__ == "__main__":
    main()
