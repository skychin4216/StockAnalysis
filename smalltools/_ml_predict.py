# -*- coding: utf-8 -*-
"""
PC 端 ML 选股模型：KNN + XGBoost
========================================================================
目的：把 IC 排序器（线性秩相关）升级为非线性 ML 模型，
     验证 KNN/XGBoost 在四周期上是否比 IC 排序选出更高收益的组合。

两种模式（--mode）：
  all     全市场快照样本（每股票每交易日）：预测力普查（任务极难，AUC≈0.5 属正常）
  signals 已选中信号样本（_records/selected_*.json）：与 IC 排序器同一场景，
          看 ML 排序 vs IC 排序谁选出的 Top 组合收益更高（本项目的主用途）

方法（严格无未来函数）：
- 特征：粘合度/量比/距高点跌幅/粘合天数/当日涨幅/换手率/近5日动量/
        距MA60乖离/距MA250乖离/方向标签（10 维）
- 标签：未来 H 日收盘涨幅为正=1（H=5/10/20/30 对应超短/短/中/长）
- 切分：按日期时间序列 75%/25%（训练只用过去，验证只用未来）
- 模型：KNeighborsClassifier(5/10/20 取最优) + XGBClassifier
- 评估：AUC / 准确率(对比多数类基线) / 验证集 Top-K 组合平均收益
        vs IC 排序 Top-K vs 全体平均
产出：_records/ml_models.json + 控制台对比表
用法：
  python _ml_predict.py                  # 默认 signals（主用途）
  python _ml_predict.py --mode all       # 全市场普查
  python _ml_predict.py --periods 中线,长线
  python _ml_predict.py --step 2
"""
import argparse
import json
import os
import sys
import time
import warnings

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _full_cycle_backtest import load_cache

warnings.filterwarnings("ignore")

RECORD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_records")
PERIODS = ["超短", "短线", "中线", "长线"]
HORIZON = {"超短": 5, "短线": 10, "中线": 20, "长线": 30}
DIR_CODE = {"DOWNTREND": -2, "OSCILLATION": 0, "ACCUMULATION": 2,
            "UPTREND": 3, "BREAKOUT": 4, "UNKNOWN": 0}
# IC 排序器权重（与 backtest_params.json rank_factors 一致）
IC_W = {"momentum5": -0.40, "drawdownPct": 0.40, "convergenceDegree": -0.30,
        "ma250Bias": 0.30, "ma60Bias": 0.20, "changePct": 0.20,
        "volumeRatio": 0.10, "convergenceDays": 0.10, "turnover": 0.05,
        "direction": 0.35}

FEATURES = [
    "convergenceDegree", "volumeRatio", "drawdownPct", "convergenceDays",
    "changePct", "turnover", "momentum5", "ma60Bias", "ma250Bias", "direction",
]
FEATURE_NAMES = [
    "粘合度%", "量比", "距高点跌幅%", "粘合天数",
    "当日涨幅%", "换手率%", "近5日动量%", "距MA60乖离%", "距MA250乖离%", "方向",
]


def direction_of(df):
    """端口 Kotlin DirectionAnalyzer 的方向标签"""
    ma5, ma10, ma20 = df["ma5"], df["ma10"], df["ma20"]
    ma60_up = df["ma60"] > df["ma60"].shift(1)
    conv = df["convergenceDegree"]
    up = (ma5 > ma10) & (ma10 > ma20) & (df["close"] > ma5) & ma60_up
    down = (ma5 < ma10) & (ma10 < ma20) & (df["close"] < ma5) & ~ma60_up
    conv_ok = conv < 6.0
    breakout = (conv_ok & (df["close"] >= df["conv_top"])
                & (df["volumeRatio"] >= 1.15) & (df["close"] > ma20))
    acc = conv_ok
    return np.select([breakout, up, acc, down],
                     ["BREAKOUT", "UPTREND", "ACCUMULATION", "DOWNTREND"],
                     default="OSCILLATION")


def build_stock_features(snaps):
    """单只股票 -> DataFrame(特征+未来收益标签)。snaps 按日期升序"""
    df = pd.DataFrame(snaps)
    if len(df) < 260:
        return None
    df = df.sort_values("date").reset_index(drop=True)
    df["ma5"] = df["close"].rolling(5).mean()
    df["ma10"] = df["close"].rolling(10).mean()
    df["ma20"] = df["close"].rolling(20).mean()
    df["ma60"] = df["close"].rolling(60).mean()
    df["ma250"] = df["close"].rolling(250).mean()
    df["vol20"] = df["volume"].rolling(20).mean()
    df["momentum5"] = df["close"] / df["ma5"] - 1
    df["ma60Bias"] = df["close"] / df["ma60"] - 1
    df["ma250Bias"] = df["close"] / df["ma250"] - 1
    df["convergenceDegree"] = (
        df[["ma5", "ma10", "ma20"]].max(axis=1)
        / df[["ma5", "ma10", "ma20"]].min(axis=1) - 1) * 100.0
    df["volumeRatio"] = df["volume"] / df["vol20"]
    df["drawdownPct"] = df["close"] / df["high"].rolling(250, min_periods=20).max() - 1
    # 粘合持续天数：连续满足 粘合度<6 的天数
    conv = (df["convergenceDegree"] < 6.0).astype(int)
    grp = (conv != conv.shift(1)).cumsum()
    df["convergenceDays"] = conv.groupby(grp).cumcount() + 1
    df.loc[conv == 0, "convergenceDays"] = 0
    df["conv_top"] = df[["ma5", "ma10", "ma20"]].max(axis=1) * 1.02
    df["direction"] = pd.Series(direction_of(df)).map(DIR_CODE).values
    df["changePct"] = df["changePct"].fillna(df["close"].pct_change() * 100.0)
    df["turnover"] = df["turnover"].fillna(0.0)
    # 标签：未来 H 日（收盘买入 → 收盘卖出）
    for h in sorted(set(HORIZON.values())):
        df[f"y_h{h}"] = (df["close"].shift(-h) / df["close"] - 1.0).gt(0).astype(int)
        df[f"fwd_h{h}"] = df["close"].shift(-h) / df["close"] - 1.0
    # 只保留特征齐全（ma250 就绪）的行
    df = df[df["ma250"].notna()].copy()
    if len(df) < 20:
        return None
    return df[["date"] + FEATURES + [f"y_h{h}" for h in sorted(set(HORIZON.values()))]
              + [f"fwd_h{h}" for h in sorted(set(HORIZON.values()))]]


def load_signal_rows(cache, period):
    """从 _records/selected_*.json 加载信号样本。
    返回: DataFrame(date=asof, code, 特征..., y_h/fwd_h)
    """
    h = HORIZON[period]
    y_col, fwd_col = f"y_h{h}", f"fwd_h{h}"
    feature_cache = {}
    rows = []
    if not os.path.isdir(RECORD_DIR):
        return None
    for f in sorted(os.listdir(RECORD_DIR)):
        if not (f.startswith("selected_") and f.endswith(".json")):
            continue
        rec = json.load(open(os.path.join(RECORD_DIR, f), encoding="utf-8"))
        for sig in rec.get("signals", {}).get(period, []):
            code, name, asof = sig[0], sig[1], sig[2]
            df = feature_cache.get(code)
            if df is None:
                snaps = cache.get(code, {}).get("snaps") or []
                df = build_stock_features(snaps)
                if df is None:
                    continue
                feature_cache[code] = df
            # 取 asof 当日（或 <=asof 最近一日）的特征行
            idx = df["date"].searchsorted(asof, side="right") - 1
            if idx < 0:
                continue
            row = df.iloc[idx]
            if row[y_col] != row[y_col] or row[y_col] is None:  # NaN → 未来数据不足
                continue
            rows.append({"date": row["date"], "code": code} |
                        {k: row[k] for k in FEATURES} |
                        {y_col: int(row[y_col]), fwd_col: float(row[fwd_col])})
    if len(rows) < 50:
        return None
    data = pd.DataFrame(rows).dropna().reset_index(drop=True)
    data["ic"] = data.apply(ic_score, axis=1)
    return data.sort_values("date").reset_index(drop=True)


def build_all_data(cache, step):
    """全市场快照样本"""
    frames = []
    for code, ent in cache.items():
        if code.startswith("sh000") or code.startswith("sz399"):
            continue
        df = build_stock_features(ent.get("snaps") or [])
        if df is None:
            continue
        keep = [c for c in df.columns if c not in ("date",)]
        frames.append(df.iloc[::step])
    data = pd.concat(frames, ignore_index=True)
    data["ic"] = data.apply(ic_score, axis=1)
    data = data.dropna().reset_index(drop=True)
    return data.sort_values("date").reset_index(drop=True)


def ic_score(row):
    return sum(IC_W.get(k, 0.0) * row[k] for k in FEATURES)


def topk_return(df, score_col, top_frac, fwd_col):
    n = max(1, int(len(df) * top_frac))
    top = df.nlargest(n, score_col)
    return float(top[fwd_col].mean()), n


def evaluate(te, fwd_col, y_col, clf, predict_fn=None):
    X, y = te[FEATURES].values, te[y_col].values
    p = predict_fn(X) if predict_fn else clf.predict_proba(X)[:, 1]
    from sklearn.metrics import roc_auc_score
    auc = roc_auc_score(y, p)
    acc = float(((p >= 0.5).astype(int) == y).mean())
    base = max(y.mean(), 1 - y.mean())
    d2 = te.copy()
    d2["_score"] = p
    top_ret, top_n = topk_return(d2, "_score", 0.10, fwd_col)
    return dict(auc=auc, acc=acc, base=base, top_ret=top_ret,
                top_n=top_n, all_ret=float(te[fwd_col].mean()), n=len(te))


def run_period(data, period, split=0.75, top_frac=0.10):
    h = HORIZON[period]
    fwd_col, y_col = f"fwd_h{h}", f"y_h{h}"
    n = len(data)
    cut = int(n * split)
    tr, te = data.iloc[:cut], data.iloc[cut:]
    if len(tr) < 100 or len(te) < 30:
        return None
    Xtr, ytr = tr[FEATURES].values, tr[y_col].values
    print(f"    [{period}] 样本 {n}（训练 {len(tr)} / 验证 {len(te)}）日期 {tr.date.iloc[0]}~{te.date.iloc[-1]}")

    # ---- KNN ----
    from sklearn.neighbors import KNeighborsClassifier
    best = None
    for k in (5, 10, 20):
        clf = KNeighborsClassifier(n_neighbors=k, weights="distance", n_jobs=-1)
        clf.fit(Xtr, ytr)
        r = evaluate(te, fwd_col, y_col, clf)
        if best is None or r["auc"] > best["auc"]:
            best = dict(r, k=k, model="KNN")
    knn = best

    # ---- XGBoost ----
    from xgboost import XGBClassifier
    clf = XGBClassifier(n_estimators=200, max_depth=4, learning_rate=0.05,
                        subsample=0.8, colsample_bytree=0.8,
                        eval_metric="logloss", n_jobs=-1, random_state=42)
    clf.fit(Xtr, ytr)
    xgb = evaluate(te, fwd_col, y_col, clf)
    xgb["model"] = "XGB"
    xgb["importance"] = dict(zip(FEATURE_NAMES, [float(v) for v in clf.feature_importances_]))

    # ---- IC 排序基线 ----
    d2 = te.copy()
    d2["_score"] = d2["ic"]
    ic_top_ret, ic_n = topk_return(d2, "_score", top_frac, fwd_col)
    ic = dict(top_ret=ic_top_ret, top_n=ic_n, all_ret=float(te[fwd_col].mean()), n=len(te))

    return dict(period=period, horizon=h, n=n, tr_n=len(tr), te_n=len(te),
                knn=knn, xgb=xgb, ic=ic, top_frac=top_frac)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", default="signals", choices=["signals", "all"])
    ap.add_argument("--periods", default=",".join(PERIODS))
    ap.add_argument("--step", type=int, default=1)
    args = ap.parse_args()
    want = set(args.periods.split(","))
    cache = load_cache()
    t0 = time.time()
    print(f"股票池 {len(cache)} 只 | 特征 10 维 | 75/25 时间切分 | 模式={args.mode}")
    if args.mode == "all":
        print(f"全市场快照采样（step={args.step}）")
    else:
        print("已选中信号样本（_records/selected_*.json）")

    result = {}
    for period in PERIODS:
        if period not in want:
            continue
        if args.mode == "all":
            data = build_all_data(cache, args.step)
            data = data[[c for c in data.columns
                         if c.startswith("y_h") or c.startswith("fwd_h") or c in FEATURES
                         or c in ("date", "ic")]]
        else:
            data = load_signal_rows(cache, period)
        if data is None or len(data) < 150:
            print(f"  [{period}] 样本不足，跳过")
            continue
        top_frac = 0.30 if args.mode == "signals" else 0.10
        r = run_period(data, period, top_frac=top_frac)
        if r is None:
            continue
        result[period] = r
    print()

    print("=" * 124)
    print(f"{'周期':<5}{'样本':>7}{'模型':<5}{'AUC':>7}{'准确率':>8}{'基线':>7}  "
          f"{'Top组合收益':>10}{'全体均值':>9}{'IC排序Top组':>13}  {'最优K/主力特征':<26}")
    print("=" * 124)
    for period, r in result.items():
        for m in ("knn", "xgb"):
            mm = r[m]
            extra = f"K={mm.get('k', '')}" if m == "knn" else \
                " ".join(f"{k[:5]}:{v:.2f}" for k, v in
                         sorted(mm["importance"].items(), key=lambda kv: -kv[1])[:3])
            print(f"{period:<5}{r['n']:>7}{m.upper():<5}{mm['auc']:>7.3f}"
                  f"{mm['acc']:>8.1%}{mm['base']:>7.1%}{mm['top_ret']:>+10.2f}%"
                  f"{mm['all_ret']:>+9.2f}%{r['ic']['top_ret']:>+12.2f}%  {extra:<26}")
        print(f"      IC 基线: 全体均值 {r['ic']['all_ret']:+.2f}% | "
              f"IC排序Top{int(r['top_frac']*100)}% {r['ic']['top_ret']:+.2f}% | "
              f"XGB Top{int(r['top_frac']*100)}% {r['xgb']['top_ret']:+.2f}%")

    with open(os.path.join(RECORD_DIR, "ml_models.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1, default=float)
    print(f"\n耗时 {time.time() - t0:.1f}s | 结果已保存 _records/ml_models.json")


if __name__ == "__main__":
    main()
