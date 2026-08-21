# -*- coding: utf-8 -*-
"""导出长线 KNN 模型 → app/assets/ml_knn_long.json
========================================================================
- 特征：Kotlin 端口径（_kotlin_feats.py，与 StockCheckPipeline 同口径）
- 样本：长线信号（_records/selected_*.json）最近 2 年（实验证明 1-2 年最优，
  更早样本是 regime 噪声，见 _ml_3y.py）
- 模型：KNeighborsClassifier(weights=distance, euclidean)，k 在验证集选最优
- 导出：z-score 缩放参数(mean/std) + 标准化后的支撑样本，Kotlin 端用
  相同缩放做距离加权投票，保证 PC/Kotlin 推理一致
"""
import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
_AUTOQUANT_ROOT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "AutoQuant"))
if _AUTOQUANT_ROOT not in sys.path:
    sys.path.insert(0, _AUTOQUANT_ROOT)
from _full_cycle_backtest import load_cache
# kotlin_feats 已迁移到 AutoQuant/autoquant/kotlin_feats.py（smalltools/_kotlin_feats.py 已归档）
from autoquant.kotlin_feats import build_kotlin_feats, FEATS
from sklearn.neighbors import KNeighborsClassifier
from sklearn.metrics import roc_auc_score

RECORD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_records")
ASSETS = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "assets"))
OUT_MODEL = os.path.join(ASSETS, "ml_knn_long.json")


def load_long_signals(cache, min_date="2024-08-15"):
    """长线信号 → DataFrame(date, code, FEATS...)（Kotlin 口径），取最近2年"""
    feat_cache, rows = {}, []
    for f in sorted(os.listdir(RECORD_DIR)):
        if not (f.startswith("selected_") and f.endswith(".json")):
            continue
        rec = json.load(open(os.path.join(RECORD_DIR, f), encoding="utf-8"))
        for sig in rec.get("signals", {}).get("长线", []):
            code, name, asof = sig[0], sig[1], sig[2]
            if asof < min_date:
                continue
            df = feat_cache.get(code)
            if df is None:
                df = build_kotlin_feats(cache.get(code, {}).get("snaps") or [])
                if df is None:
                    continue
                feat_cache[code] = df
            idx = df["date"].searchsorted(asof, side="right") - 1
            if idx < 0:
                continue
            row = df.iloc[idx]
            rows.append({"date": row["date"], "code": code,
                         **{k: float(row[k]) for k in FEATS}})
    data = pd.DataFrame(rows).dropna().reset_index(drop=True)
    return data.sort_values("date").reset_index(drop=True)


def zscore_fit(X):
    mean = X.mean(axis=0)
    std = X.std(axis=0)
    std[std < 1e-12] = 1.0
    return mean, std


def main():
    cache = load_cache()
    data = load_long_signals(cache)
    n = len(data)
    print(f"长线信号（>=2024-08-15，Kotlin 口径）: {n} 条 | "
          f"{data['date'].iloc[0]} ~ {data['date'].iloc[-1]}")

    # ---- 标签：未来 30 日涨幅为正 ----
    h = 30
    y = (data["date"].shift(-h) >= data["date"].iloc[-1])  # 占位，下面重算
    # 用缓存重算未来 30 日收益
    snaps_by_code = {c: cache.get(c, {}).get("snaps") or [] for c in data["code"].unique()}
    fwd, yl = [], []
    for _, r in data.iterrows():
        snaps = snaps_by_code[r["code"]]
        closes = [s["close"] for s in snaps]
        dates = [s["date"] for s in snaps]
        try:
            i = dates.index(r["date"])
        except ValueError:
            i = max(j for j, d in enumerate(dates) if d <= r["date"])
        if i + h < len(closes):
            fwd.append(closes[i + h] / closes[i] - 1.0)
            yl.append(1 if fwd[-1] > 0 else 0)
        else:
            fwd.append(np.nan)
            yl.append(np.nan)
    data["y"] = yl
    data["fwd30"] = fwd
    data = data.dropna(subset=["y"]).reset_index(drop=True)
    print(f"有未来30日标签: {len(data)} 条（剔除尾部无未来数据）")

    # ---- 时间切分 75/25 ----
    X_all = data[FEATS].values.astype(float)
    y_all = data["y"].values.astype(int)
    cut = int(len(data) * 0.75)
    Xtr, ytr = X_all[:cut], y_all[:cut]
    Xte, yte = X_all[cut:], y_all[cut:]
    mean, std = zscore_fit(Xtr)
    Xtr_s, Xte_s = (Xtr - mean) / std, (Xte - mean) / std
    base = max(yte.mean(), 1 - yte.mean())
    print(f"切分: 训练 {len(Xtr)} / 验证 {len(Xte)} | 验证基线 {base:.1%}")

    # ---- KNN k 选择（验证集 AUC） ----
    best = None
    for k in (3, 5, 8, 10, 15, 20):
        clf = KNeighborsClassifier(n_neighbors=k, weights="distance", metric="euclidean", n_jobs=-1)
        clf.fit(Xtr_s, ytr)
        p = clf.predict_proba(Xte_s)[:, 1]
        auc = roc_auc_score(yte, p) if len(np.unique(yte)) > 1 else 0.5
        acc = float(((p >= 0.5).astype(int) == yte).mean())
        print(f"  k={k:<3} AUC {auc:.3f}  acc {acc:.1%}")
        if best is None or auc > best["auc"]:
            best = dict(k=k, auc=auc, acc=acc, clf=clf, p=p)
    print(f"最优: k={best['k']} AUC {best['auc']:.3f} acc {best['acc']:.1%}")

    # ---- 导出 ----
    samples = np.hstack([Xtr_s, ytr.reshape(-1, 1)]).tolist()
    model = {
        "version": 1,
        "generated": "2026-08-16",
        "source": "smalltools/_export_ml_model.py (Kotlin 口径特征, 近2年长线信号)",
        "period": "长线",
        "horizon": h,
        "k": best["k"],
        "feats": FEATS,
        "scale": {f: [float(mean[i]), float(std[i])] for i, f in enumerate(FEATS)},
        "train_end": data["date"].iloc[cut - 1],
        "n_train": len(Xtr),
        "n_val": len(Xte),
        "val": {"auc": round(best["auc"], 4), "acc": round(best["acc"], 4),
                "base": round(float(base), 4), "y_pos": float(yte.mean())},
        "samples": samples,
    }
    os.makedirs(ASSETS, exist_ok=True)
    with open(OUT_MODEL, "w", encoding="utf-8") as f:
        json.dump(model, f, ensure_ascii=False)
    print(f"\n已导出 {OUT_MODEL}")
    print(f"  支撑样本 {len(samples)} 条 × {len(FEATS)} 特征（标准化） | 文件 {os.path.getsize(OUT_MODEL)/1024:.0f} KB")
    print(f"  验证: AUC {best['auc']:.3f} / acc {best['acc']:.1%} / 基线 {base:.1%}")


if __name__ == "__main__":
    main()
