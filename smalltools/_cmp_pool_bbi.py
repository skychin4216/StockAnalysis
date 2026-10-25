# -*- coding: utf-8 -*-
"""
BBI12+ER≥70 池级回测（与 App 端 SsaT0Engine 同口径对照）
=======================================================
同一份 1H 缓存（_kalman_1h_cache.json，171 只），算：
  - BBI12+ER≥70 策略收益（run_strategy 全序列）
  - 全期买入持有 BH（用引擎暖机位 index≈180 起算，贴近 SsaT0Engine 的 bench 口径）
  - 超额 = 策略 - BH
输出池级 mean/median + 超额胜率，供与 SSA T0 池级结果对照。
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from _ssa_causal_check import er_series, bbi12_line, run_strategy, ER_W

CACHE = os.path.join(HERE, "data", "_kalman_1h_cache.json")
WARM = 180  # 与 SsaT0Engine 暖机位（windowM=120 + 60）对齐


def agg(a):
    a = np.asarray(a, float)
    return a.mean() * 100, float(np.median(a) * 100)


def main():
    cache = json.load(open(CACHE, encoding="utf-8"))
    codes = [c for c in cache if len(cache[c].get("c", [])) > 300]
    closes = {c: cache[c]["c"] for c in codes}

    all_er = {c: er_series(closes[c]) for c in codes}
    nb = min(len(closes[c]) for c in codes)
    er_pct = {c: np.full(nb, np.nan) for c in codes}
    for t in range(ER_W, nb):
        es, vl = [], []
        for c in codes:
            e = all_er[c][t] if t < len(all_er[c]) else np.nan
            if np.isfinite(e):
                es.append(e); vl.append(c)
        if not es:
            continue
        arr = np.array(es)
        for c, e in zip(vl, es):
            er_pct[c][t] = float((arr < e).sum() / len(arr) * 100)

    strat, bh, exc = [], [], []
    rows = []
    for c in codes:
        z = np.asarray(closes[c], float)
        if len(z) <= WARM + 5:
            continue
        _, ntr, _, t = run_strategy(closes[c], bbi12_line(closes[c]),
                                    er_pct=er_pct[c], er_gate=70)
        b = z[-1] / z[WARM] - 1
        strat.append(t); bh.append(b); exc.append(t - b)
        rows.append(f"{c},{t*100:.2f},{b*100:.2f},{(t-b)*100:.2f},{ntr}")

    m_s, md_s = agg(strat)
    m_b, md_b = agg(bh)
    m_e, md_e = agg(exc)
    print(f"POOL({len(strat)}): BBI+ER70  mean={m_s:+.2f}% median={md_s:+.2f}%")
    print(f"POOL[{len(strat)}]: BH(全期)   mean={m_b:+.2f}% median={md_b:+.2f}%")
    print(f"POOL[{len(strat)}]: 超额       mean={m_e:+.2f}% median={md_e:+.2f}%  "
          f"超额>0占比={sum(1 for x in exc if x > 0) / len(exc) * 100:.1f}%")
    with open(os.path.join(HERE, "_records", "_bbi_pool.csv"), "w", encoding="utf-8") as f:
        f.write("\n".join(rows))


if __name__ == "__main__":
    main()
