# -*- coding: utf-8 -*-
"""三年数据训练实验：更多训练数据是否提高命中率？
对比：单次75/25 vs 训练窗口1y/2y/3y vs walk-forward expanding；
     以及增加市场环境特征后的增益。
"""
import os, sys, json, argparse
import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _ml_predict import (load_signal_rows, build_stock_features, FEATURES, PERIODS,
                         HORIZON, IC_W)
from _full_cycle_backtest import load_cache
from sklearn.neighbors import KNeighborsClassifier
from sklearn.metrics import roc_auc_score

IDX = "sh000001"  # 上证指数
EXTRA_FEATS = ["mkt_ret10", "mkt_ma20_bias", "mkt_ma20_slope", "mkt_change"]


def build_market_features(cache):
    """上证指数 asof 特征（时间序列）-> DataFrame(date, 4特征)"""
    snaps = cache.get(IDX, {}).get("snaps") or []
    df = pd.DataFrame(snaps)[["date", "close", "changePct"]]
    df["ma20"] = df["close"].rolling(20).mean()
    df["mkt_ret10"] = df["close"] / df["close"].shift(10) - 1
    df["mkt_ma20_bias"] = df["close"] / df["ma20"] - 1
    df["mkt_ma20_slope"] = df["ma20"] / df["ma20"].shift(5) - 1
    df["mkt_change"] = df["changePct"]
    return df[["date"] + EXTRA_FEATS].dropna()


def add_market_feats(data, mkt):
    out = data.merge(mkt, on="date", how="left")
    for c in EXTRA_FEATS:
        if c not in out.columns:
            out[c] = np.nan
    return out.dropna().reset_index(drop=True)


def knn_cv(tr, te, feats, ks=(5, 10, 20), metric="accuracy"):
    """KNN 在验证集上选最优 k，返回 (auc, acc, best_k)"""
    Xtr, ytr = tr[feats].values, np.asarray(tr["y"].values)
    Xte, yte = te[feats].values, np.asarray(te["y"].values)
    best = None
    for k in ks:
        clf = KNeighborsClassifier(n_neighbors=k, weights="distance", metric="euclidean")
        clf.fit(Xtr, ytr)
        p = clf.predict_proba(Xte)[:, 1]
        auc = roc_auc_score(yte, p) if len(np.unique(yte)) > 1 else 0.5
        acc = float((np.asarray(p >= 0.5, dtype=int) == yte).mean())
        if best is None or (metric == "auc" and auc > best[0]) or (metric == "accuracy" and acc > best[1]):
            best = (auc, acc, k, clf)
    return best


def split_by_date(data, frac):
    data = data.sort_values("date").reset_index(drop=True)
    cut = int(len(data) * frac)
    return data.iloc[:cut], data.iloc[cut:]


def walk_forward(data, feats, train_months, step_months=6):
    """expanding walk-forward：训练从 train_months 起，每档 +step_months，验证后 step_months"""
    data = data.sort_values("date").reset_index(drop=True)
    dates = pd.to_datetime(data["date"])
    t0, t1 = dates.iloc[0], dates.iloc[-1]
    rows, preds = [], []
    start = t0 + pd.DateOffset(months=train_months)
    cur = start
    while cur < t1:
        tr = data[dates <= cur]
        te = data[(dates > cur) & (dates <= cur + pd.DateOffset(months=step_months))]
        if len(tr) < 60 or len(te) < 20:
            cur += pd.DateOffset(months=step_months)
            continue
        auc, acc, k, clf = knn_cv(tr, te, feats)
        rows.append((str(cur.date()), len(tr), len(te), auc, acc, k))
        preds.append((te.copy(), clf))
        cur += pd.DateOffset(months=step_months)
    # 汇总累计 AUC：合并所有验证段概率
    if not preds:
        return rows, None
    all_p, all_y = [], []
    for te, clf in preds:
        p = clf.predict_proba(te[feats].values)[:, 1]
        all_p.extend(p)
        all_y.extend(te["y"].values)
    auc = roc_auc_score(all_y, all_p) if len(np.unique(all_y)) > 1 else 0.5
    acc = float(((np.asarray(all_p) >= 0.5).astype(int) == np.asarray(all_y)).mean())
    return rows, (auc, acc)


def run():
    cache = load_cache()
    mkt = build_market_features(cache)
    ap = argparse.ArgumentParser()
    ap.add_argument("--periods", default="超短,短线,中线,长线")
    args = ap.parse_args()
    periods = args.periods.split(",")

    result = {}
    for period in periods:
        data = load_signal_rows(cache, period)
        if data is None or len(data) < 60:
            print(f"[{period}] 样本不足")
            continue
        h = HORIZON[period]
        data = data.rename(columns={f"y_h{h}": "y"}).sort_values("date").reset_index(drop=True)
        base = data[["date"] + FEATURES + ["y"]].dropna().reset_index(drop=True)
        aug = add_market_feats(base, mkt)
        feats_aug = FEATURES + EXTRA_FEATS
        print(f"\n{'='*110}\n{period}（horizon={h}天）三年信号样本 {len(aug)} 条 | {aug['date'].iloc[0]} ~ {aug['date'].iloc[-1]}")
        print(f"{'='*110}")

        # ---- 1) 单次 75/25（复现现状）----
        tr, te = split_by_date(base, 0.75)
        r = knn_cv(tr, te, FEATURES, metric="auc")
        print(f"  [单次75/25]  训练{len(tr)} 验证{len(te)}  KNN-AUC {r[0]:.3f} acc {r[1]*100:.1f}% (k={r[2]})")

        # ---- 2) 训练窗口 1y/2y/3y 对比（同一验证集=最后25%）----
        _, te_all = split_by_date(base, 0.75)
        print(f"  [训练窗口对比] 验证集固定 = 最后25% ({len(te_all)} 条)")
        for label, frac in [("前1年", 0.33), ("前2年", 0.66), ("前3年(全部)", 1.0)]:
            if frac < 1.0:
                tr_all, _ = split_by_date(base, 0.75)
                tr_all = tr_all.iloc[: int(len(tr_all) * (frac / 0.75))]
            else:
                tr_all, _ = split_by_date(base, 0.75)
            r = knn_cv(tr_all, te_all, FEATURES, metric="auc")
            print(f"    训练{label:<12} n={len(tr_all):>4}  KNN-AUC {r[0]:.3f}  acc {r[1]*100:.1f}%  (k={r[2]})")

        # ---- 3) walk-forward expanding（训练≥1年，每档+6mo）----
        print(f"  [Walk-forward expanding] 训练起点=最早信号+1年，每档扩展6个月")
        for label, feats in [("基础10特征", FEATURES), ("+4市场特征", feats_aug)]:
            src = base if feats == FEATURES else aug
            rows, agg = walk_forward(src, feats, train_months=12, step_months=6)
            if rows:
                for d, ntr, nte, a, ac, k in rows:
                    print(f"    训练至{d} n={ntr:>4} 验证{nte:>3}  AUC {a:.3f}  acc {ac*100:.1f}%  k={k}")
            if agg:
                print(f"    → {label} 汇总: 累计AUC {agg[0]:.3f}  累计acc {agg[1]*100:.1f}%")
        result[period] = aug

    print(f"\n{'='*110}\n汇总：更多训练数据（1y→2y→3y）+ 市场特征 对命中率的影响")
    print(f"{'='*110}")


if __name__ == "__main__":
    run()
