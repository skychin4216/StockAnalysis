# -*- coding: utf-8 -*-
"""
BBI12+ER≥70  vs  SSA T0 三态机 · 同标同窗对照
=============================================
SSA T0（App 真引擎）已在 app/build/ssa_t0_pc_result.txt 有 300750 的单票结果；
本脚本用同一份 1H 缓存（同新浪来源、同 1023 根窗口）算 BBI12+ER≥70 / BBI12 原始 /
买入持有，做同标的对照。ER 分位仍取 171 只核心池横截面口径。
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from _ssa_causal_check import er_series, bbi12_line, run_strategy, ER_W

CACHE = os.path.join(HERE, "data", "_kalman_1h_cache.json")


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

    targets = [c for c in ("300750", "600176") if c in closes]
    print("targets_in_cache:", targets)
    for c in targets:
        cl = closes[c]
        _, _, _, t_er = run_strategy(cl, bbi12_line(cl), er_pct=er_pct[c], er_gate=70)
        _, _, _, t_raw = run_strategy(cl, bbi12_line(cl))
        z = np.asarray(cl, float)
        bh35 = z[-1] / z[35] - 1
        bh120 = z[-1] / z[119] - 1
        print(f"{c}: BBI+ER70={t_er*100:+.2f}%  BBIraw={t_raw*100:+.2f}%  "
              f"BH(35)={bh35*100:+.2f}%  BH(120)={bh120*100:+.2f}%")

    # 池级参考
    pool_er, pool_raw = [], []
    for c in codes:
        _, _, _, t_er = run_strategy(closes[c], bbi12_line(closes[c]),
                                     er_pct=er_pct[c], er_gate=70)
        _, _, _, t_raw = run_strategy(closes[c], bbi12_line(closes[c]))
        pool_er.append(t_er); pool_raw.append(t_raw)
    a = np.array(pool_er); b = np.array(pool_raw)
    print(f"POOL(171): BBI+ER70 mean={a.mean()*100:+.1f}% median={np.median(a)*100:+.1f}%  "
          f"BBIraw mean={b.mean()*100:+.1f}% median={np.median(b)*100:+.1f}%")


if __name__ == "__main__":
    main()
