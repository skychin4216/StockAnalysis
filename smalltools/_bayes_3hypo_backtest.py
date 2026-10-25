# -*- coding: utf-8 -*-
"""
三假设贝叶斯跟踪器（IMM / Bayesian Multiple-Model）—— 纯价格先验验证
=====================================================================
用户思路：给定 涨/平/跌 三个预测方向，用波动幅度给出各自的预测区间，
逐 K 用新 K 的似然更新三方向权重（本质 IMM / 贝叶斯多假设跟踪）。

模型：
  假设 H ∈ {up, flat, dn}，第 t 根对 t+1 收益的预测分布 N(μ_h, s²)
    μ_up = +k·σ_t, μ_flat = 0, μ_dn = -k·σ_t
    σ_t = 近 SIGMA_WIN 根收益标准差；s = like_scale·σ_t
  先验：马尔可夫转移 T（对角 stickiness=m，其余均分），post_{t-1} → prior_t
  更新：post_t ∝ prior_t × N(r_t | μ_h, s²)，归一化
  预测：exp_ret_t = Σ_h post_h · μ_h  （供 t+1 使用，无未来）

评估（与 SSA 滚动 / Kalman 同 30 只抽样、同口径、FEE 0.0013）：
  A 方向预测准确率：argmax post_t 预测 t+1 收益符号（三等分：涨>0.3σ/跌<-0.3σ/否则平）
     vs 随机基准 33.3%，vs 全预测"平"的基准
  B Brier score：post 与真实 one-hot 的差距 vs 等权 1/3 基准
  C 条件收益：exp_ret 分位（高/低）后 5/10/20 根收益
  D 策略：exp_ret > thr·σ 买，< -thr·σ 卖（收盘成交）vs 买入持有
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

SIGMA_WIN = 20
WARM = 60
SAMPLE = 30
MIN_BARS = 300
FWD = (5, 10, 20)

K = 1.0            # 涨/跌假设的期望幅度（单位 σ）
STICK = 0.8        # 转移矩阵对角（粘性）
LIKE_SCALE = 1.0   # 似然标准差 = LIKE_SCALE·σ
THR = 0.1          # 交易阈值：exp_ret > THR·σ 买
FLAT_BAND = 0.3    # 方向评估：|r| < FLAT_BAND·σ 记为"平"


def tracker(close, k=K, stick=STICK, like_scale=LIKE_SCALE):
    """返回 (post[n,3], exp_ret_units[n])；exp_ret_units 以 σ 为单位。"""
    n = close.size
    ret = np.zeros(n)
    ret[1:] = close[1:] / close[:-1] - 1

    sigma = np.zeros(n)
    for t in range(n):
        lo = max(1, t - SIGMA_WIN + 1)
        seg = ret[lo:t + 1]
        sigma[t] = float(np.std(seg)) if seg.size >= 5 else 0.0
    sigma = np.maximum(sigma, 1e-6)

    off = np.array([1.0, 0.0, -1.0]) * k          # 单位 σ 的假设均值
    T = np.full((3, 3), (1.0 - stick) / 2.0)
    np.fill_diagonal(T, stick)

    post = np.full(3, 1.0 / 3.0)
    posts = np.zeros((n, 3))
    exp_u = np.zeros(n)
    for t in range(n):
        pri = T @ post
        z = (ret[t] / sigma[t] - off) / like_scale
        ll = np.exp(-0.5 * z * z)
        post = pri * ll
        s = post.sum()
        post = post / s if s > 1e-12 else np.full(3, 1 / 3)
        posts[t] = post
        exp_u[t] = float(post @ off)              # σ 单位
    return posts, exp_u, sigma


def true_label(r, sg, band=FLAT_BAND):
    """真实标签：0=涨, 1=平, 2=跌"""
    if r > band * sg:
        return 0
    if r < -band * sg:
        return 2
    return 1


def run_strategy(close, exp_u, sigma, lo, n, thr=THR):
    trades = []
    pos = None
    for t in range(lo, n - 1):
        up = exp_u[t] > thr
        dn = exp_u[t] < -thr
        if pos is None and up:
            pos = close[t]
        elif pos is not None and dn:
            trades.append(close[t] / pos - 1 - FEE)
            pos = None
    if pos is not None:
        trades.append(close[-1] / pos - 1 - FEE)
    total = float(np.prod([1 + r for r in trades]) - 1) if trades else None
    return {"trades": len(trades),
            "win": float(np.mean([r > 0 for r in trades])) if trades else None,
            "avg": float(np.mean(trades)) if trades else None,
            "total": total}


def analyze(code):
    bars = load_klines(code, "day")
    if not bars or len(bars) < MIN_BARS:
        return None
    close = np.array([b["close"] for b in bars], dtype=float)
    n = close.size
    ret = np.zeros(n)
    ret[1:] = close[1:] / close[:-1] - 1

    posts, exp_u, sigma = tracker(close)
    lo, hi = max(WARM, SIGMA_WIN), n - 1

    # A 方向预测准确率：t 的后验预测 t+1
    hit = 0
    tot = 0
    always_flat = 0
    brier_tot = 0.0
    brier_base = 0.0
    for t in range(lo, hi):
        lab = true_label(ret[t + 1], sigma[t + 1])
        pred = int(np.argmax(posts[t]))
        hit += (pred == lab)
        always_flat += (lab == 1)
        tot += 1
        oh = np.zeros(3); oh[lab] = 1.0
        brier_tot += float(np.sum((posts[t] - oh) ** 2))
        brier_base += float(np.sum((np.full(3, 1 / 3) - oh) ** 2))

    # C exp_ret 分组收益（用 σ 单位阈值 ±0.1）
    grp = {"high": [], "low": [], "mid": []}
    for t in range(lo, hi):
        g = "high" if exp_u[t] > THR else ("low" if exp_u[t] < -THR else "mid")
        grp[g].append(t)

    fwd = {}
    for g, idx in grp.items():
        fwd[g] = {}
        for h in FWD:
            rs = [close[t + h] / close[t] - 1 for t in idx if t + h < n]
            fwd[g][h] = {"mean": float(np.mean(rs)) if rs else None,
                         "win": float(np.mean([r > 0 for r in rs])) if rs else None}
    base = {h: float(np.mean([close[t + h] / close[t] - 1
                              for t in range(lo, hi) if t + h < n])) for h in FWD}

    strat = run_strategy(close, exp_u, sigma, lo, n)
    buy_hold = close[-1] / close[lo] - 1

    # 敏感性：加固/减弱粘性、换 k、换阈值
    sens = {}
    for tag, kw in (("stick0.6", {"stick": 0.6}), ("stick0.9", {"stick": 0.9}),
                    ("k1.5", {"k": 1.5}), ("k0.7", {"k": 0.7})):
        _, eu2, sg2 = tracker(close, **kw)
        sens[tag] = run_strategy(close, eu2, sg2, lo, n)
    for tag, thr in (("thr0.0", 0.0), ("thr0.3", 0.3)):
        sens[tag] = run_strategy(close, exp_u, sigma, lo, n, thr)

    return {
        "code": code, "n": n, "tot": tot,
        "acc": hit / tot if tot else None,
        "acc_flat": always_flat / tot if tot else None,
        "brier": brier_tot / tot if tot else None,
        "brier_base": brier_base / tot if tot else None,
        "fwd": fwd, "base": base,
        "strat": strat, "buy_hold": float(buy_hold), "sens": sens,
    }


def agg(vals):
    vals = [v for v in vals if v is not None]
    return float(np.mean(vals)) if vals else None


def main():
    pool_cfg = json.load(open(POOL_JSON, encoding="utf-8"))
    codes = pool_cfg.get("codes") or pool_cfg.get("stocks")
    random.Random(7).shuffle(codes)          # 与 SSA/Kalman 同种子同顺序
    codes = codes[:SAMPLE]
    print(f"三假设贝叶斯跟踪：抽样 {len(codes)} 只，k={K} 粘性={STICK} 阈值={THR}")
    with Pool(WORKERS) as p:
        results = p.map(analyze, codes)
    results = [r for r in results if r]
    print(f"有效样本 {len(results)} 只")

    rep = {
        "params": {"model": "3-hypothesis Bayes (IMM)", "sigma_win": SIGMA_WIN, "k": K,
                   "stick": STICK, "like_scale": LIKE_SCALE, "thr": THR,
                   "flat_band": FLAT_BAND, "fee": FEE, "stocks": len(results)},
        "direction": {
            "准确率%": agg([r["acc"] * 100 for r in results]),
            "随机基准%(1/3)": 33.33,
            "全猜平基准%": agg([r["acc_flat"] * 100 for r in results]),
            "Brier": agg([r["brier"] for r in results]),
            "Brier_等权基准": agg([r["brier_base"] for r in results]),
        },
        "fwd_ret": {},
        "strategy": {
            "贝叶斯方向策略": {
                "平均单笔%": agg([r["strat"]["avg"] * 100 for r in results if r["strat"]["avg"] is not None]),
                "胜率%": agg([r["strat"]["win"] * 100 for r in results if r["strat"]["win"] is not None]),
                "平均交易数": agg([r["strat"]["trades"] for r in results]),
                "复合总收益%": agg([r["strat"]["total"] * 100 for r in results if r["strat"]["total"] is not None]),
            },
            "买入持有": {"总收益%": agg([r["buy_hold"] * 100 for r in results])},
        },
        "sensitivity": {},
    }
    for g in ("high", "mid", "low"):
        rep["fwd_ret"][g] = {f"{h}根": {"均值%": agg([r["fwd"][g][h]["mean"] * 100
                                                    for r in results if r["fwd"][g][h]["mean"] is not None]),
                                        "胜率%": agg([r["fwd"][g][h]["win"] * 100
                                                    for r in results if r["fwd"][g][h]["win"] is not None])}
                            for h in FWD}
    rep["fwd_ret"]["无条件基准"] = {f"{h}根均值%": agg([r["base"][h] * 100 for r in results])
                                   for h in FWD}
    for tag in ("stick0.6", "stick0.9", "k1.5", "k0.7", "thr0.0", "thr0.3"):
        rep["sensitivity"][tag] = {
            "复合总收益%": agg([r["sens"][tag]["total"] * 100 for r in results
                               if r["sens"][tag]["total"] is not None]),
            "胜率%": agg([r["sens"][tag]["win"] * 100 for r in results
                         if r["sens"][tag]["win"] is not None]),
            "平均交易数": agg([r["sens"][tag]["trades"] for r in results]),
        }

    out = os.path.join(HERE, "_records", "_bayes_3hypo_report.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({"summary": rep, "per_stock": results}, open(out, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(json.dumps(rep, ensure_ascii=False, indent=2))
    print(f"\n明细已存 {out}")


if __name__ == "__main__":
    main()
