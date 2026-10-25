# -*- coding: utf-8 -*-
"""
OBV 唐奇安位置作为「选股筛选条件」验证（横截面，非个股择时）
==============================================================
上一轮结论：OBV 通道位置作为择时信号有显著方向信息（+4.7pp，30/30 只提升），
但「全进全出」策略仍大输买入持有。本轮改口径验证：**用它选股**。

方法（全程无未来）：
  调仓日 d：每 STEP=20 个交易日一次（用全池日期并集）
  因子 pos_d = (OBV_d - 近20日OBV中位) / (近20日OBV上轨-下轨)，pos ∈ [-0.5, +0.5]
  未来收益 fwd_d = close(d+STEP) / close(d) - 1（容忍停牌 5 天）
  ① 横截面分位：每期按 pos 分 5 组，看各组平均 fwd 及 Q5-Q1 价差、IC（Spearman）
  ② 组合回测：等权持有
       全池（基准） / pos>0 / pos>0.2 / pos<-0.2（反向） / top20（pos 最高）
     每期按换手计费（FEE 0.13% 双边）；报告总收益/年化/最大回撤/夏普/期胜率
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

DONCHIAN_WIN = 20
STEP = 20
TOPN = 20
MIN_BARS = 250
TOL = 5          # 停牌容忍（交易日）


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
    n = obv.size
    pos = np.zeros(n)
    for t in range(n):
        lo = max(0, t - win + 1)
        seg = obv[lo:t + 1]
        hi, lw = seg.max(), seg.min()
        rng = hi - lw
        pos[t] = float((obv[t] - (hi + lw) / 2) / rng) if rng > 1e-9 else 0.0
    return pos


def load_one(code):
    bars = load_klines(code, "day")
    if not bars or len(bars) < MIN_BARS:
        return None
    close = np.array([b["close"] for b in bars], dtype=float)
    vol = np.array([b.get("volume", 0.0) or 0.0 for b in bars], dtype=float)
    dates = [b["time"] for b in bars]
    return {"code": code, "dates": dates, "close": close,
            "pos": donchian_pos(obv_series(close, vol))}


def main():
    pool_cfg = json.load(open(POOL_JSON, encoding="utf-8"))
    codes = pool_cfg.get("codes") or pool_cfg.get("stocks")
    print(f"池内 {len(codes)} 只，加载中…")

    with Pool(WORKERS) as p:
        series = [s for s in p.map(load_one, codes) if s]
    print(f"有效 {len(series)} 只")

    # 全池日期并集（交易日轴）
    all_dates = sorted({d for s in series for d in s["dates"]})
    first_ok = MIN_BARS
    rebal_idx = list(range(first_ok, len(all_dates) - STEP, STEP))
    rebal_dates = [all_dates[i] for i in rebal_idx]
    print(f"调仓日 {len(rebal_dates)} 个（{rebal_dates[0]} → {rebal_dates[-1]}），间隔 {STEP} 交易日")

    # 预索引：code -> {date: idx}
    for s in series:
        s["idx"] = {d: i for i, d in enumerate(s["dates"])}

    # 面板：每期每票 (pos, fwd)
    panels = []          # list of dict date -> [(code, pos, fwd)]
    for d, d_next in zip(rebal_dates, rebal_dates[1:] + [all_dates[-1]]):
        recs = []
        for s in series:
            i0 = s["idx"].get(d)
            i1 = s["idx"].get(d_next)
            if i0 is None or i1 is None:
                continue
            if i1 <= i0:
                continue
            fwd = s["close"][i1] / s["close"][i0] - 1
            recs.append((s["code"], float(s["pos"][i0]), float(fwd)))
        if len(recs) >= 20:
            panels.append({"date": d, "rows": recs})

    print(f"有效面板期数 {len(panels)}，平均每期 {np.mean([len(p['rows']) for p in panels]):.0f} 只")

    # ── ① 横截面分位 ──
    q_rets = {k: [] for k in range(1, 6)}
    per_period_q = []
    spreads = []
    for p_ in panels:
        rows = sorted(p_["rows"], key=lambda x: x[1])
        vals = np.array([r[2] for r in rows])
        qs = np.array_split(np.arange(len(rows)), 5)
        period_q = {}
        for qi, idxs in enumerate(qs, start=1):
            m = float(np.mean(vals[idxs]))
            q_rets[qi].append(m)
            period_q[qi] = m
        per_period_q.append(period_q)
        spreads.append(period_q[5] - period_q[1])

    # IC：每期 Spearman
    ics = []
    for p_ in panels:
        pos = np.array([r[1] for r in p_["rows"]])
        fwd = np.array([r[2] for r in p_["rows"]])
        rk_p = pos.argsort().argsort().astype(float)
        rk_f = fwd.argsort().argsort().astype(float)
        if rk_p.std() > 1e-9 and rk_f.std() > 1e-9:
            ics.append(float(np.corrcoef(rk_p, rk_f)[0, 1]))

    # ── ② 组合回测 ──
    strategies = {
        "全池等权": lambda rows: rows,
        "pos>0": lambda rows: [r for r in rows if r[1] > 0.0],
        "pos>0.2": lambda rows: [r for r in rows if r[1] > 0.2],
        "pos<0(反向)": lambda rows: [r for r in rows if r[1] < 0.0],
        "top20": lambda rows: sorted(rows, key=lambda x: x[1], reverse=True)[:TOPN],
    }
    results = {}
    for name, sel in strategies.items():
        nav = [1.0]
        period_rets = []
        for p_ in panels:
            picked = sel(p_["rows"])
            if not picked:
                period_rets.append(0.0)
                nav.append(nav[-1])
                continue
            r = float(np.mean([x[2] for x in picked])) - FEE   # 保守：每期计一次双边费
            period_rets.append(r)
            nav.append(nav[-1] * (1 + r))
        nav = np.array(nav)
        peak = np.maximum.accumulate(nav)
        mdd = float((nav / peak - 1).min())
        pr = np.array(period_rets)
        ann = float(nav[-1] ** (252 / (STEP * len(panels))) - 1) if nav[-1] > 0 else None
        sharpe = float(pr.mean() / pr.std() * np.sqrt(252 / STEP)) if pr.std() > 1e-9 else None
        results[name] = {
            "总收益%": float(nav[-1] - 1) * 100,
            "年化%": (ann or 0) * 100 if ann is not None else None,
            "最大回撤%": mdd * 100,
            "夏普": sharpe,
            "期胜率%": float(np.mean(pr > 0)) * 100,
            "平均每期持仓数": float(np.mean([len(sel(p_["rows"])) for p_ in panels])),
        }
        # 分年度（按调仓日年份聚合）
        annual = {}
        for p_, r in zip(panels, period_rets):
            annual.setdefault(p_["date"][:4], []).append(r)
        results[name]["分年度%"] = {y: float(np.prod([1 + x for x in v]) - 1) * 100
                                   for y, v in sorted(annual.items())}

    spread_mean = float(np.mean(spreads))
    spread_se = float(np.std(spreads) / np.sqrt(len(spreads)))
    ic_mean = float(np.mean(ics)) if ics else None
    ic_se = float(np.std(ics) / np.sqrt(len(ics))) if ics else None

    rep = {
        "params": {"donchian_win": DONCHIAN_WIN, "step": STEP, "topn": TOPN,
                   "fee_per_period": FEE, "stocks": len(series),
                   "periods": len(panels), "start": rebal_dates[0], "end": rebal_dates[-1]},
        "quintile_avg_ret%": {f"Q{k}": float(np.mean(v) * 100) for k, v in q_rets.items()},
        "Q5_minus_Q1%": spread_mean * 100,
        "Q5_Q1_t": spread_mean / spread_se if spread_se > 0 else None,
        "IC_mean": ic_mean, "IC_t": (ic_mean / ic_se) if ic_se and ic_se > 0 else None,
        "IC_pos_ratio%": float(np.mean([i > 0 for i in ics])) * 100 if ics else None,
        "portfolios": results,
    }
    out = os.path.join(HERE, "_records", "_obv_screen_report.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump(rep, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(json.dumps(rep, ensure_ascii=False, indent=2))
    print(f"\n明细已存 {out}")


if __name__ == "__main__":
    main()
