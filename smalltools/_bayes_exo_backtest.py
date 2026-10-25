# -*- coding: utf-8 -*-
"""
三假设贝叶斯跟踪器 —— 异源先验注入（OBV 通道位置 + 量价速率塌缩）
====================================================================
在 _bayes_3hypo_backtest.py 基础上，把两个已验证异源信号作为「先验权重修正」
注入三个假设（涨/平/跌），贝叶斯层只负责融合。

异源信号（t 收盘可见，无未来）：
  ① OBV 唐奇安位置 pos_t ∈ [-0.5, +0.5]
       obv = Σ(vol·sign(Δclose))；pos = (obv - 中位) / (上轨 - 下轨)
       pos 高 → 资金持续流入 → 提升「涨」先验；pos 低 → 提升「跌」先验
  ② 量价速率塌缩 / 放大
       rate = 近5K量合计 / max(|近5K涨跌幅|, 1%)   （万手/1%）
       norm = rate 近 60 根中位数
       底部塌缩：价格创 20 日新低 且 rate < 0.7·norm  → 抛压枯竭 → 提升「涨」先验
       顶部放量：价格创 20 日新高 且 rate > 1.3·norm  → 推升费力 → 提升「跌」先验

消融配置：
  base      纯价格（无先验注入）
  obv       仅 OBV
  rate      仅速率
  both      两者
参数扫描：alpha_obv ∈ {0.5, 1.0, 2.0}，alpha_rate ∈ {0.5, 1.0}

评估与前面同口径（30 只同抽样、FEE 0.0013、无未来）：
  A 方向准确率 / Brier；C exp_ret 分组收益；D 策略复合收益 vs 买入持有
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
K = 1.0
STICK = 0.8
LIKE_SCALE = 1.0
THR = 0.1
FLAT_BAND = 0.3
DONCHIAN_WIN = 20
RATE_W = 5
RATE_NORM_WIN = 60


def obv_series(close, vol):
    n = close.size
    obv = np.zeros(n)
    for t in range(1, n):
        if close[t] > close[t - 1]:
            obv[t] = obv[t - 1] + vol[t]
        elif close[t] < close[t - 1]:
            obv[t] = obv[t - 1] - vol[t]
        else:
            obv[t] = obv[t - 1]
    return obv


def donchian_pos(obv, win=DONCHIAN_WIN):
    """返回 [-0.5, +0.5] 的通道位置（0=中位线）"""
    n = obv.size
    pos = np.zeros(n)
    for t in range(n):
        lo = max(0, t - win + 1)
        seg = obv[lo:t + 1]
        hi, lw = seg.max(), seg.min()
        rng = hi - lw
        pos[t] = float((obv[t] - (hi + lw) / 2) / rng) if rng > 1e-9 else 0.0
    return pos


def rate_series(close, vol, w=RATE_W, norm_win=RATE_NORM_WIN):
    n = close.size
    rate = np.zeros(n)
    for t in range(w, n):
        vs = vol[t - w + 1:t + 1].sum() / 10000.0           # 万手
        chg = abs(close[t] / close[t - w] - 1) * 100.0
        rate[t] = vs / max(chg, 1.0)
    norm = np.ones(n)
    for t in range(n):
        lo = max(w, t - norm_win + 1)
        seg = rate[lo:t + 1]
        norm[t] = float(np.median(seg)) if seg.size else 1.0
    return rate, norm


def tracker(close, pos=None, bull=None, bear=None,
            k=K, stick=STICK, like_scale=LIKE_SCALE,
            alpha_obv=0.0, alpha_rate=0.0):
    n = close.size
    ret = np.zeros(n)
    ret[1:] = close[1:] / close[:-1] - 1
    sigma = np.zeros(n)
    for t in range(n):
        lo = max(1, t - SIGMA_WIN + 1)
        seg = ret[lo:t + 1]
        sigma[t] = float(np.std(seg)) if seg.size >= 5 else 0.0
    sigma = np.maximum(sigma, 1e-6)

    off = np.array([1.0, 0.0, -1.0]) * k
    T = np.full((3, 3), (1.0 - stick) / 2.0)
    np.fill_diagonal(T, stick)

    post = np.full(3, 1.0 / 3.0)
    posts = np.zeros((n, 3))
    exp_u = np.zeros(n)
    for t in range(n):
        pri = T @ post
        # ── 异源先验注入 ──
        if alpha_obv > 0.0 and pos is not None:
            f = float(np.exp(alpha_obv * 2.0 * pos[t]))     # pos∈[-.5,.5] → 因子 e^{±alpha}
            pri[0] *= f; pri[2] /= f
        if alpha_rate > 0.0:
            if bull is not None and bull[t]:
                pri[0] *= np.exp(alpha_rate); pri[2] *= np.exp(-alpha_rate * 0.5)
            if bear is not None and bear[t]:
                pri[2] *= np.exp(alpha_rate); pri[0] *= np.exp(-alpha_rate * 0.5)
        s0 = pri.sum()
        if s0 > 1e-12:
            pri = pri / s0
        # ── 观测似然更新 ──
        z = (ret[t] / sigma[t] - off) / like_scale
        ll = np.exp(-0.5 * z * z)
        post = pri * ll
        s = post.sum()
        post = post / s if s > 1e-12 else np.full(3, 1 / 3)
        posts[t] = post
        exp_u[t] = float(post @ off)
    return posts, exp_u, sigma


def labels(ret, sigma, band=FLAT_BAND):
    lab = np.ones(ret.size, dtype=int)
    for t in range(ret.size):
        if ret[t] > band * sigma[t]:
            lab[t] = 0
        elif ret[t] < -band * sigma[t]:
            lab[t] = 2
    return lab


def run_strategy(close, exp_u, lo, n, thr=THR):
    trades = []
    pos_ = None
    for t in range(lo, n - 1):
        if pos_ is None and exp_u[t] > thr:
            pos_ = close[t]
        elif pos_ is not None and exp_u[t] < -thr:
            trades.append(close[t] / pos_ - 1 - FEE)
            pos_ = None
    if pos_ is not None:
        trades.append(close[-1] / pos_ - 1 - FEE)
    return {"trades": len(trades),
            "win": float(np.mean([r > 0 for r in trades])) if trades else None,
            "avg": float(np.mean(trades)) if trades else None,
            "total": float(np.prod([1 + r for r in trades]) - 1) if trades else None}


CONFIGS = {
    "base":   {"alpha_obv": 0.0, "alpha_rate": 0.0},
    "obv1":   {"alpha_obv": 1.0, "alpha_rate": 0.0},
    "obv2":   {"alpha_obv": 2.0, "alpha_rate": 0.0},
    "rate0.5": {"alpha_obv": 0.0, "alpha_rate": 0.5},
    "rate1":  {"alpha_obv": 0.0, "alpha_rate": 1.0},
    "both":   {"alpha_obv": 1.0, "alpha_rate": 1.0},
    "both_mild": {"alpha_obv": 0.5, "alpha_rate": 0.5},
    "both_strong": {"alpha_obv": 2.0, "alpha_rate": 1.0},
}


def analyze(code):
    bars = load_klines(code, "day")
    if not bars or len(bars) < MIN_BARS:
        return None
    close = np.array([b["close"] for b in bars], dtype=float)
    vol = np.array([b.get("volume", 0.0) or 0.0 for b in bars], dtype=float)
    n = close.size
    ret = np.zeros(n)
    ret[1:] = close[1:] / close[:-1] - 1

    obv = obv_series(close, vol)
    pos = donchian_pos(obv)
    rate, norm = rate_series(close, vol)

    # 底部塌缩 / 顶部放量（20 日新低/新高）
    bull = np.zeros(n, dtype=bool)
    bear = np.zeros(n, dtype=bool)
    for t in range(max(DONCHIAN_WIN, RATE_NORM_WIN), n):
        lo = t - DONCHIAN_WIN + 1
        seg_c = close[lo:t + 1]
        if close[t] == seg_c.min() and rate[t] < 0.7 * norm[t]:
            bull[t] = True
        if close[t] == seg_c.max() and rate[t] > 1.3 * norm[t]:
            bear[t] = True

    lo_i, hi_i = max(WARM, SIGMA_WIN, RATE_NORM_WIN), n - 1
    lab = labels(ret, np.maximum(
        np.array([np.std(ret[max(1, t - SIGMA_WIN + 1):t + 1])
                  for t in range(n)]), 1e-6))

    out = {"code": code, "n": n,
           "bull_cnt": int(bull.sum()), "bear_cnt": int(bear.sum()),
           "buy_hold": float(close[-1] / close[lo_i] - 1)}

    for tag, cfg in CONFIGS.items():
        posts, exp_u, sigma = tracker(close, pos, bull, bear, **cfg)
        hit = tot = 0
        brier = brier_base = 0.0
        for t in range(lo_i, hi_i):
            lb = lab[t + 1]
            hit += (int(np.argmax(posts[t])) == lb)
            tot += 1
            oh = np.zeros(3); oh[lb] = 1.0
            brier += float(np.sum((posts[t] - oh) ** 2))
            brier_base += float(np.sum((np.full(3, 1 / 3) - oh) ** 2))
        strat = run_strategy(close, exp_u, lo_i, n)
        grp = {"high": [], "mid": [], "low": []}
        for t in range(lo_i, hi_i):
            g = "high" if exp_u[t] > THR else ("low" if exp_u[t] < -THR else "mid")
            grp[g].append(t)
        fwd = {g: {h: (float(np.mean([close[t + h] / close[t] - 1
                                      for t in idx if t + h < n])) if idx else None)
                   for h in FWD} for g, idx in grp.items()}
        out[tag] = {"acc": hit / tot if tot else None,
                    "brier": brier / tot if tot else None,
                    "brier_base": brier_base / tot if tot else None,
                    "strat": strat, "fwd": fwd}
    base = {h: float(np.mean([close[t + h] / close[t] - 1
                              for t in range(lo_i, hi_i) if t + h < n])) for h in FWD}
    out["base_fwd"] = base
    return out


def agg(vals):
    vals = [v for v in vals if v is not None]
    return float(np.mean(vals)) if vals else None


def main():
    pool_cfg = json.load(open(POOL_JSON, encoding="utf-8"))
    codes = pool_cfg.get("codes") or pool_cfg.get("stocks")
    random.Random(7).shuffle(codes)
    codes = codes[:SAMPLE]
    print(f"异源先验注入回测：{len(codes)} 只，消融 {list(CONFIGS.keys())}")
    with Pool(WORKERS) as p:
        results = p.map(analyze, codes)
    results = [r for r in results if r]
    print(f"有效样本 {len(results)} 只，"
          f"底部塌缩信号均 {agg([r['bull_cnt'] for r in results]):.1f} 次/只，"
          f"顶部放量均 {agg([r['bear_cnt'] for r in results]):.1f} 次/只")

    rep = {"params": {"sigma_win": SIGMA_WIN, "k": K, "stick": STICK, "thr": THR,
                      "donchian_win": DONCHIAN_WIN, "rate_w": RATE_W,
                      "rate_norm_win": RATE_NORM_WIN, "fee": FEE, "stocks": len(results)},
           "ablations": {},
           "buy_hold": agg([r["buy_hold"] * 100 for r in results]),
           "base_fwd": {f"{h}根均值%": agg([r["base_fwd"][h] * 100 for r in results]) for h in FWD}}

    for tag in CONFIGS:
        rep["ablations"][tag] = {
            "方向准确率%": agg([r[tag]["acc"] * 100 for r in results]),
            "Brier": agg([r[tag]["brier"] for r in results]),
            "Brier_等权": agg([r[tag]["brier_base"] for r in results]),
            "策略复合%": agg([r[tag]["strat"]["total"] * 100 for r in results
                              if r[tag]["strat"]["total"] is not None]),
            "策略胜率%": agg([r[tag]["strat"]["win"] * 100 for r in results
                              if r[tag]["strat"]["win"] is not None]),
            "平均单笔%": agg([r[tag]["strat"]["avg"] * 100 for r in results
                              if r[tag]["strat"]["avg"] is not None]),
            "交易数": agg([r[tag]["strat"]["trades"] for r in results]),
            "high组20根%": agg([r[tag]["fwd"]["high"][20] * 100 for r in results
                                if r[tag]["fwd"]["high"][20] is not None]),
            "low组20根%": agg([r[tag]["fwd"]["low"][20] * 100 for r in results
                               if r[tag]["fwd"]["low"][20] is not None]),
        }

    out = os.path.join(HERE, "_records", "_bayes_exo_report.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({"summary": rep, "per_stock": results}, open(out, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(json.dumps(rep, ensure_ascii=False, indent=2))
    print(f"\n明细已存 {out}")


if __name__ == "__main__":
    main()
