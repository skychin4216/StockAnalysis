# -*- coding: utf-8 -*-
"""
逐票 × 逐策略 收益矩阵（per-stock × per-signal）
=================================================
用户目标纠正：不是跑赢全池，而是找出「哪些股票上哪个策略能足够盈利」。
即使某策略只对少数票有效也可接受。

对池内每只票独立跑 6 个策略（全部无未来：t 收盘定仓位，作用于 t→t+1）：

  1 buyhold       全程持有
  2 obv_cross     OBV 唐奇安位置上穿 0 买入、下穿 0 卖出
  3 obv_state     OBV 位置 > 0 即持仓，否则空仓
  4 donchian      收盘上破前 20 日高买入、下破前 10 日低卖出（Turtle 式）
  5 ma_cross      MA5 上穿 MA20 买、下穿卖
  6 vp_collapse   20 日新低 + 量价速率 < 0.7×60日中位 → 买入，持 20 根后卖出

指标：年化 / 夏普 / 最大回撤 / 交易数 / 胜率 / 持仓占比
特征：ER（Kaufman 效率系数）/ 年化波动率 / 日均成交额（亿元）/ 均价位
输出：
  ① 每策略汇总（平均/中位年化、年化>15%/25% 的票数）
  ② 每票最优策略（按年化）Top / Bottom
  ③ 按 ER 与波动率二分组的策略优劣（找出「什么票配什么策略」）
"""
import json
import os
import sys
from multiprocessing import Pool

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from _chanlun_engine import load_klines                     # noqa: E402
from _chanlun_backtest import FEE, POOL_JSON, WORKERS       # noqa: E402

MIN_BARS = 400
WARM = 60
HOLD_VP = 20
DON_HI, DON_LO = 20, 10


def metrics(position, ret):
    """position[t] 由 t 收盘决定，作用于 ret[t+1]。"""
    pos = np.asarray(position, dtype=float)
    sr = pos[:-1] * ret[1:]
    n = sr.size
    nav = np.cumprod(1 + sr)
    tot = nav[-1] - 1 if n else 0.0
    ann = (1 + tot) ** (252 / n) - 1 if n and tot > -1 else 0.0
    sd = sr.std()
    sharpe = float(sr.mean() / sd * np.sqrt(252)) if sd > 1e-9 else 0.0
    peak = np.maximum.accumulate(nav)
    mdd = float((nav / peak - 1).min()) if n else 0.0
    # 交易段
    chg = np.diff(pos)
    trades = int(np.sum(chg > 0))
    wins = []
    i = 0
    while i < n:
        if pos[i] > 0:
            j = i
            while j < n and pos[j] > 0:
                j += 1
            seg = sr[i:j]
            wins.append(float(np.prod(1 + seg) - 1))
            i = j
        else:
            i += 1
    win_rate = float(np.mean([w > 0 for w in wins])) if wins else None
    return {"ann": float(ann), "sharpe": sharpe, "mdd": mdd,
            "trades": trades, "win": win_rate,
            "exposure": float(pos.mean()),
            "avg_trade": float(np.mean(wins)) if wins else None}


def build_positions(close, vol):
    n = close.size
    pos = {}

    pos["buyhold"] = np.ones(n)

    obv = np.zeros(n)
    for t in range(1, n):
        obv[t] = obv[t - 1] + (vol[t] if close[t] > close[t - 1]
                               else -vol[t] if close[t] < close[t - 1] else 0.0)
    W = 20
    obv_pos = np.zeros(n)
    for t in range(n):
        lo = max(0, t - W + 1)
        seg = obv[lo:t + 1]
        hi, lw = seg.max(), seg.min()
        obv_pos[t] = (obv[t] - (hi + lw) / 2) / (hi - lw) if hi - lw > 1e-9 else 0.0

    p = np.zeros(n)
    state = 0
    for t in range(1, n):
        if state == 0 and obv_pos[t - 1] <= 0 < obv_pos[t]:
            state = 1
        elif state == 1 and obv_pos[t - 1] >= 0 > obv_pos[t]:
            state = 0
        p[t] = state
    pos["obv_cross"] = p

    pos["obv_state"] = (obv_pos > 0).astype(float)

    p = np.zeros(n)
    state = 0
    for t in range(max(DON_HI, DON_LO) + 1, n):
        if state == 0 and close[t] > close[max(0, t - DON_HI):t].max():
            state = 1
        elif state == 1 and close[t] < close[max(0, t - DON_LO):t].min():
            state = 0
        p[t] = state
    pos["donchian"] = p

    ma5 = np.convolve(close, np.ones(5) / 5, mode="full")[:n]
    ma20 = np.convolve(close, np.ones(20) / 20, mode="full")[:n]
    pos["ma_cross"] = (ma5 > ma20).astype(float)

    # 量价速率塌缩
    rate = np.zeros(n)
    for t in range(5, n):
        vs = vol[t - 4:t + 1].sum()
        chg = abs(close[t] / close[t - 5] - 1) * 100
        rate[t] = vs / max(chg, 1.0)
    norm = np.ones(n)
    for t in range(n):
        lo = max(5, t - 59)
        seg = rate[lo:t + 1]
        norm[t] = np.median(seg) if seg.size else 1.0
    p = np.zeros(n)
    t = 30
    while t < n - 1:
        lo = max(0, t - 19)
        if close[t] == close[lo:t + 1].min() and rate[t] < 0.7 * norm[t]:
            p[t + 1:t + 1 + HOLD_VP] = 1
            t += HOLD_VP
        t += 1
    pos["vp_collapse"] = p
    return pos


SIGNALS = ["buyhold", "obv_cross", "obv_state", "donchian", "ma_cross", "vp_collapse"]


def seg_ann(pos, ret, lo, hi):
    """区间 [lo,hi) 的年化（含买入费用）"""
    p = np.asarray(pos, dtype=float)[lo:hi]
    r = ret[lo + 1:hi]
    if p.size < 10 or r.size < 10:
        return None
    chg = np.diff(p, prepend=p[0])
    cost = np.where(chg > 0, FEE, 0.0)
    sr = p[:-1] * r - cost[:-1]
    nav = np.cumprod(1 + sr)
    m = sr.size
    return float(nav[-1] ** (252 / m) - 1) if nav[-1] > 0 else -1.0


def analyze(code):
    bars = load_klines(code, "day")
    if not bars or len(bars) < MIN_BARS:
        return None
    close = np.array([b["close"] for b in bars], dtype=float)
    vol = np.array([b.get("volume", 0.0) or 0.0 for b in bars], dtype=float)
    n = close.size
    ret = np.zeros(n)
    ret[1:] = close[1:] / close[:-1] - 1
    ret[0] = 0.0
    # 费用：每次买入计 FEE
    positions = build_positions(close, vol)

    out = {"code": code, "n": n,
           "er": float(abs(close[-1] - close[0]) / max(np.sum(np.abs(np.diff(close))), 1e-9)),
           "vol_ann": float(ret[WARM:].std() * np.sqrt(252)),
           "turnover_yi": float(np.mean(vol[WARM:] * close[WARM:]) / 1e8),
           "price": float(np.mean(close[WARM:]))}
    for sig in SIGNALS:
        p = positions[sig].copy()
        # 扣费：每次 0→1 时该笔收益扣 FEE
        chg = np.diff(p, prepend=0)
        cost = np.zeros(n)
        cost[chg > 0] = FEE
        sr = p[:-1] * ret[1:] - cost[1:]
        nav = np.cumprod(1 + sr)
        m = metrics(p, ret)
        m["ann"] = float((nav[-1]) ** (252 / n) - 1) if nav[-1] > 0 else -1.0
        out[sig] = m
    # ── 样本内外：前 2/3 选策略，后 1/3 验证 ──
    split = int(n * 2 / 3)
    oos = {}
    for sig in SIGNALS:
        oos[sig] = {"train": seg_ann(positions[sig], ret, WARM, split),
                    "valid": seg_ann(positions[sig], ret, split, n)}
    best_train = max(SIGNALS, key=lambda s: (oos[s]["train"] if oos[s]["train"] is not None else -9))
    out["oos"] = oos
    out["best_train_sig"] = best_train
    out["valid_pick_ann"] = oos[best_train]["valid"]
    out["valid_buyhold_ann"] = oos["buyhold"]["valid"]
    return out


def main():
    pool_cfg = json.load(open(POOL_JSON, encoding="utf-8"))
    codes = pool_cfg.get("codes") or pool_cfg.get("stocks")
    print(f"池内 {len(codes)} 只，逐票逐策略…")
    with Pool(WORKERS) as p:
        res = [r for r in p.map(analyze, codes) if r]
    print(f"有效 {len(res)} 只\n")

    # ① 每策略汇总
    summary = {}
    for sig in SIGNALS:
        anns = np.array([r[sig]["ann"] for r in res])
        shs = np.array([r[sig]["sharpe"] for r in res])
        summary[sig] = {
            "平均年化%": float(anns.mean() * 100),
            "中位年化%": float(np.median(anns) * 100),
            "年化>15%只数": int((anns > 0.15).sum()),
            "年化>25%只数": int((anns > 0.25).sum()),
            "年化>50%只数": int((anns > 0.50).sum()),
            "平均夏普": float(shs.mean()),
            "平均持仓占比%": float(np.mean([r[sig]["exposure"] for r in res]) * 100),
            "平均交易数": float(np.mean([r[sig]["trades"] for r in res])),
        }

    # ② 每票最优策略（排除 buyhold 以外的比较，同时记录 buyhold）
    best = []
    for r in res:
        cand = [(sig, r[sig]["ann"], r[sig]["sharpe"], r[sig]["mdd"]) for sig in SIGNALS]
        cand.sort(key=lambda x: x[1], reverse=True)
        best.append({"code": r["code"], "ann": cand[0][1], "sig": cand[0][0],
                     "sharpe": cand[0][2], "mdd": cand[0][3],
                     "buyhold_ann": r["buyhold"]["ann"], "er": r["er"],
                     "vol_ann": r["vol_ann"]})
    best.sort(key=lambda x: x["ann"], reverse=True)

    # ③ ER / 波动率二分组的策略表现
    def split_stats(key):
        vals = np.array([r[key] for r in res])
        med = np.median(vals)
        hi = [r for r, v in zip(res, vals) if v >= med]
        lo = [r for r, v in zip(res, vals) if v < med]
        out = {}
        for sig in SIGNALS:
            out[sig] = {
                "高组平均年化%": float(np.mean([x[sig]["ann"] for x in hi]) * 100),
                "低组平均年化%": float(np.mean([x[sig]["ann"] for x in lo]) * 100),
                "差(高-低)pp": float((np.mean([x[sig]["ann"] for x in hi])
                                     - np.mean([x[sig]["ann"] for x in lo])) * 100),
            }
        out["_组内只数"] = [len(hi), len(lo)]
        out["_分界值"] = float(med)
        return out

    rep = {"params": {"stocks": len(res), "min_bars": MIN_BARS, "fee": FEE,
                      "hold_vp": HOLD_VP, "signals": SIGNALS},
           "signal_summary": summary,
           "er_split": split_stats("er"),
           "vol_split": split_stats("vol_ann"),
           "best_per_stock_top20": best[:20],
           "best_per_stock_bottom10": best[-10:],
           "best_signal_distribution": {}}
    for sig in SIGNALS:
        rep["best_signal_distribution"][sig] = sum(1 for b in best if b["sig"] == sig)

    out = os.path.join(HERE, "_records", "_perstock_signal_matrix.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({"summary": rep, "per_stock": res, "best": best},
              open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    print("== 策略汇总 ==")
    print("策略          平均年化%  中位%   >15%  >25%  >50%  夏普   持仓%  交易数")
    for sig in SIGNALS:
        s = summary[sig]
        print(f"{sig:12s} {s['平均年化%']:8.1f} {s['中位年化%']:7.1f} "
              f"{s['年化>15%只数']:6d} {s['年化>25%只数']:5d} {s['年化>50%只数']:5d} "
              f"{s['平均夏普']:6.2f} {s['平均持仓占比%']:6.1f} {s['平均交易数']:6.1f}")

    print("\n== 每票最优策略 Top12 ==")
    for b in best[:12]:
        print(f"{b['code']:10s} {b['sig']:12s} 年化{b['ann']*100:7.1f}% 夏普{b['sharpe']:5.2f} "
              f"回撤{b['mdd']*100:6.1f}% 持有{b['buyhold_ann']*100:6.1f}% ER{b['er']:.2f} σ{b['vol_ann']*100:.0f}%")

    print("\n== ER 二分组（高趋势 vs 低趋势）==")
    for sig in SIGNALS:
        d = rep["er_split"][sig]
        print(f"{sig:12s} 高{d['高组平均年化%']:7.1f}% 低{d['低组平均年化%']:7.1f}% 差{d['差(高-低)pp']:+7.1f}pp")
    print(f"  （分组只数 {rep['er_split']['_组内只数']}，分界 ER={rep['er_split']['_分界值']:.2f}）")

    print("\n== 波动率二分组 ==")
    for sig in SIGNALS:
        d = rep["vol_split"][sig]
        print(f"{sig:12s} 高{d['高组平均年化%']:7.1f}% 低{d['低组平均年化%']:7.1f}% 差{d['差(高-低)pp']:+7.1f}pp")
    print(f"\n最优策略分布: {rep['best_signal_distribution']}")

    # ── 样本外验证 ──
    valid_by_sig = {}
    for sig in SIGNALS:
        vs = [r["oos"][sig]["valid"] for r in res if r["oos"][sig]["valid"] is not None]
        ts = [r["oos"][sig]["train"] for r in res if r["oos"][sig]["train"] is not None]
        valid_by_sig[sig] = {"train_mean%": float(np.mean(ts) * 100) if ts else None,
                             "valid_mean%": float(np.mean(vs) * 100) if vs else None,
                             "valid_median%": float(np.median(vs) * 100) if vs else None,
                             "valid>15%": int(sum(1 for v in vs if v > 0.15)),
                             "valid>25%": int(sum(1 for v in vs if v > 0.25)),
                             "valid>0": int(sum(1 for v in vs if v > 0))}
    pick = [(r["valid_pick_ann"], r["valid_buyhold_ann"], r["best_train_sig"], r["code"])
            for r in res if r["valid_pick_ann"] is not None and r["valid_buyhold_ann"] is not None]
    beat = sum(1 for p_ in pick if p_[0] > p_[1])
    rep["out_of_sample"] = {
        "valid_by_signal": valid_by_sig,
        "picked_strategy": {
            "只数": len(pick),
            "验证期平均年化%": float(np.mean([p_[0] for p_ in pick]) * 100),
            "验证期买入持有平均年化%": float(np.mean([p_[1] for p_ in pick]) * 100),
            "跑赢持有的比例%": beat / len(pick) * 100 if pick else None,
            "验证期>15%只数": int(sum(1 for p_ in pick if p_[0] > 0.15)),
            "验证期>25%只数": int(sum(1 for p_ in pick if p_[0] > 0.25)),
            "训练期最优=验证期最优的票数": int(sum(
                1 for r in res
                if r["best_train_sig"] == max(SIGNALS, key=lambda s: r["oos"][s]["valid"]
                                              if r["oos"][s]["valid"] is not None else -9))),
        },
    }
    print("\n== 样本外：固定策略在验证期（后 1/3）的表现 ==")
    print("策略          训练均值%  验证均值%  验证中位%  >0只  >15%  >25%")
    for sig in SIGNALS:
        d = valid_by_sig[sig]
        print(f"{sig:12s} {d['train_mean%']:8.1f} {d['valid_mean%']:9.1f} {d['valid_median%']:9.1f} "
              f"{d['valid>0']:6d} {d['valid>15%']:5d} {d['valid>25%']:5d}")
    ps = rep["out_of_sample"]["picked_strategy"]
    print(f"\n== 样本外：「训练期挑最优策略」在验证期 ==")
    print(f"  验证期平均年化 {ps['验证期平均年化%']:.1f}%  vs  同期买入持有 {ps['验证期买入持有平均年化%']:.1f}%")
    print(f"  跑赢持有 {ps['跑赢持有的比例%']:.1f}% 的票；验证期 >15% {ps['验证期>15%只数']} 只，>25% {ps['验证期>25%只数']} 只")
    print(f"  训练期最优与验证期最优一致的票：{ps['训练期最优=验证期最优的票数']}/{ps['只数']}")
    print(f"明细已存 {out}")


if __name__ == "__main__":
    main()
