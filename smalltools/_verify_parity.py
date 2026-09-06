# -*- coding: utf-8 -*-
"""app(Kotlin) vs PC(python) 一致性验证
========================================================================
Part 1  模型推理一致性：MlKnnModel.kt 的 KNN 打分 vs sklearn KNeighborsClassifier
        （同一模型文件 ml_knn_long.json，期望最大误差 < 1e-9）
Part 2  icRank 排序复刻：用 Kotlin icPctRank/icRank/directionScore 算法 + 长线
        rank_factors（含 ml_prob），对最近一批长线信号输出排序名单（供 app 对照）
Part 3  特征口径明细：最近长线信号的 10 维 Kotlin 口径特征（供 app StockCheckResult 对照）

注意：Part 2/3 不含 seasonality 伪因子（app 端可临时 seasonality.enabled=false 对照）。

已迁移转正（2026-09）：正式维护版为 AutoQuant/verify_parity.py，
数据源改为 AutoQuant data/cache/*.csv；本文件保留留档参考。
"""
import json
import math
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _full_cycle_backtest import load_cache
from _kotlin_feats import build_kotlin_feats, direction_of_kotlin, DIR_CODE, FEATS

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "assets", "ml_knn_long.json"))
PARAMS = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "assets", "backtest_params.json"))


# ─────────────────────────── Part 1 ───────────────────────────
def kotlin_knn_probability(model, raw_feat_row):
    """复刻 MlKnnModel.probability：z-score(用 json scale) → 欧氏 → k近邻距离加权"""
    feats, scale, k = model["feats"], model["scale"], model["k"]
    x = []
    for f in feats:
        v = float(raw_feat_row[f])
        if math.isnan(v):
            return None
        mean, std = scale[f]
        x.append((v - mean) / std)
    x = np.asarray(x)
    samples = np.asarray([s[:len(feats)] for s in model["samples"]])
    labels = np.asarray([s[len(feats)] for s in model["samples"]])
    dists = np.sqrt(((samples - x) ** 2).sum(axis=1))
    idx = np.argsort(dists)[:k]
    pos = tot = 0.0
    for i in idx:
        w = 1e10 if dists[i] == 0.0 else 1.0 / dists[i]
        if labels[i] == 1:
            pos += w
        tot += w
    return pos / tot if tot > 0 else 0.5


def part1_model_parity():
    from sklearn.neighbors import KNeighborsClassifier
    from sklearn.metrics import roc_auc_score
    model = json.load(open(MODEL, encoding="utf-8"))
    feats = model["feats"]
    samples = np.asarray([s[:len(feats)] for s in model["samples"]])
    labels = np.asarray([s[len(feats)] for s in model["samples"]])
    clf = KNeighborsClassifier(n_neighbors=model["k"], weights="distance", metric="euclidean")
    clf.fit(samples, labels)
    print(f"\n{'='*100}\nPart 1 模型推理一致性（MlKnnModel vs sklearn，k={model['k']}）")
    print(f"{'='*100}")
    rng = np.random.RandomState(42)
    scale = model["scale"]
    mean = np.asarray([scale[f][0] for f in feats])
    std = np.asarray([scale[f][1] for f in feats])
    # query 用「原始空间」生成：standardized = base ± 噪声 → 还原原始量纲
    # （kotlin 端对原始特征做 z-score 后距离；sklearn 端对 standardized query 直接距离）
    raw_q, std_q = [], []
    for _ in range(200):
        base = samples[rng.randint(len(samples))]
        noise_s = rng.normal(0, 0.3, size=len(feats))
        q_s = base + noise_s
        raw_q.append(q_s * std + mean)
        std_q.append(q_s)
    q_sk = clf.predict_proba(np.asarray(std_q))[:, 1]
    q_kt = np.asarray([kotlin_knn_probability(model, dict(zip(feats, q))) for q in raw_q])
    diff = np.abs(q_sk - q_kt)
    print(f"  200 个随机 query：max|diff| = {diff.max():.2e}  mean|diff| = {diff.mean():.2e}")
    print(f"  → {'一致 ✓' if diff.max() < 1e-9 else '不一致 ✗'}")
    # 精确支撑点（dist=0 → 权重 1e10 分支）：原始空间=样本还原
    # 阈值 1e-7：1e10 权重的双精度求和顺序差异 ~1e-9，真实场景 query 与支撑点不会重合
    raw0 = samples[:20] * std + mean
    q_sk0 = clf.predict_proba(samples[:20])[:, 1]
    q_kt0 = np.asarray([kotlin_knn_probability(model, dict(zip(feats, s))) for s in raw0])
    diff0 = np.abs(q_sk0 - q_kt0)
    print(f"  20 个精确支撑点（dist=0 分支）：max|diff| = {diff0.max():.2e}  → "
          f"{'一致 ✓' if diff0.max() < 1e-7 else '不一致 ✗'}")


# ─────────────────────────── Part 2/3 ───────────────────────────
def kotlin_icpctrank(values):
    """复刻 UnifiedStockClassifier.icPctRank（平均秩法，0..1，NaN→0.5）"""
    n = len(values)
    ranks = [0.5] * n
    present = [(i, v) for i, v in enumerate(values) if not math.isnan(v)]
    m = len(present)
    if m < 2:
        return ranks
    order = sorted(range(m), key=lambda i: present[i][1])
    i = 0
    while i < m:
        j = i
        while j + 1 < m and present[order[j + 1]][1] == present[order[i]][1]:
            j += 1
        avg = (i + j) / 2.0 / (m - 1)
        for k in range(i, j + 1):
            ranks[present[order[k]][0]] = avg
        i = j + 1
    return ranks


def kotlin_icrank(rows, weights):
    """复刻 icRank：rows=[{code, feats...}]，weights=rank_factors（含伪因子列）"""
    factors = list(weights.keys())
    cols = {f: [r.get(f, float("nan")) for r in rows] for f in factors}
    pct = {f: kotlin_icpctrank(cols[f]) for f in factors}
    scored = []
    for i, r in enumerate(rows):
        s = sum(weights[f] * pct[f][i] for f in factors)
        scored.append((s, r["code"], r["name"]))
    scored.sort(key=lambda t: -t[0])
    return scored, pct


def part2_rank_parity():
    cache = load_cache()
    model = json.load(open(MODEL, encoding="utf-8"))
    params = json.load(open(PARAMS, encoding="utf-8"))
    weights = params["rank_factors"]["长线"]
    feats = model["feats"]

    # 长线候选集 = allowedForHolding 方向过滤（ACCUMULATION/UPTREND/BREAKOUT），
    # 与 app「先判方向再定周期」一致；取最近 3 个交易日（空窗期 8 月可能无蓄势/突破）。
    allowed = {DIR_CODE["ACCUMULATION"], DIR_CODE["UPTREND"], DIR_CODE["BREAKOUT"]}
    rows = []
    for code, v in cache.items():
        snaps = v.get("snaps") or []
        df = build_kotlin_feats(snaps)
        if df is None or len(df) < 2:
            continue
        for _, row in df.tail(3).iterrows():
            if row["direction"] not in allowed:
                continue
            r = {"code": code, "name": v.get("name", ""), "_date": row["date"]}
            for f in feats:
                r[f] = row[f]
            # ml_prob 伪因子：kotlin 算法算概率
            r["ml_prob"] = kotlin_knn_probability(model, r)
            rows.append(r)
    if not rows:
        print("最近 3 日无 allowedForHolding 候选，无法排序")
        return
    rows.sort(key=lambda t: t["_date"], reverse=True)
    asof = rows[0]["_date"]
    hit = [r for r in rows if r["_date"] == asof]
    if len(hit) < 2:
        asof = rows[1]["_date"]
        hit = [r for r in rows if r["_date"] == asof]
    print(f"\n{'='*100}\nPart 2 icRank 排序复刻（截至 {asof}，命中 {len(hit)} 只，长线 rank_factors 含 ml_prob）")
    print(f"{'='*100}")
    if len(hit) < 2:
        print("命中 <2 只，无法排序")
        return
    scored, pct = kotlin_icrank(hit, weights)
    print(f"{'排名':<4}{'代码':<10}{'名称':<10}{'ic_score':>9}  " + "  ".join(f"{f[:6]:>8}" for f in weights))
    for rank, (s, code, name) in enumerate(scored[:15], 1):
        row = next(r for r in hit if r["code"] == code)
        detail = "  ".join(f"{pct[f][hit.index(row)]:>8.3f}" for f in weights)
        print(f"{rank:<4}{code:<10}{name:<10}{s:>9.4f}  {detail}")
    print(f"\n  app 对照：扫描结果排序应与此一致（app 端含 seasonality 伪因子，"
          f"如需精确对照请临时 seasonality.enabled=false）")
    print(f"  Part 3 特征明细（前3只，Kotlin 口径，app 端见 StockCheckResult）:")
    for _, (s, code, name) in enumerate(scored[:3]):
        row = next(r for r in hit if r["code"] == code)
        print(f"    {code} {name}: " + "  ".join(f"{f}={row[f]:.2f}" for f in feats))


if __name__ == "__main__":
    part1_model_parity()
    part2_rank_parity()
