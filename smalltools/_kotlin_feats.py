# -*- coding: utf-8 -*-
"""Kotlin 端口径特征库（StockCheckPipeline + DirectionAnalyzer 双端同口径）
========================================================================
用途：
1. _export_ml_model.py —— 用与 app 完全一致的特征口径训练 KNN 并导出
2. _verify_parity.py   —— PC 端复刻 Kotlin 排序算法做一致性验证

与 Kotlin 端 StockCheckPipeline.analyzeSnaps / DirectionAnalyzer.analyze
逐项对齐（长线默认参数：convergenceThreshold=2.5, useMA60=true, durationWindow=20,
loose=+0.5, lookback=250, maRisingDays=10, swingLeftN=5, swingRightN=2）：

- 输入：snaps 截断为最后 lookback+10 根（对齐 app getByCode(lookbackDays+10)）
- momentum5/ma60Bias/ma250Bias = (close/MA-1)*100          （百分比）
- convergenceDegree = (max(MA5,10,20,60)-min)/min*100      （含 MA60）
- volumeRatio      = 当日量 / 前5日均量（不含当日）
- drawdownPct      = (摆动高点 - close)/摆动高点*100（findSwingHighIndex 复刻）
- convergenceDays  = 最近 durationWindow 天内粘合度<=loose 的天数计数（非连续）
- direction        = DirectionAnalyzer 复刻（isConverging: deg∈[0.1,6] 且 days>=8；
                      突破>蓄势>上升>下降>震荡；ma60Rising=ma60 环比 10 日前）
- changePct/turnoverRate 直接读快照字段

假设（与 app 中性行情一致）：marketTrend=null → lookbackMultiplier=1.0、
convergence 系数 1.0；非周期行业 cyclicalMultiplier=1.0。
"""
import numpy as np
import pandas as pd

# 与 Kotlin directionScore / PC DIR_CODE 一致
DIR_CODE = {"DOWNTREND": -2, "OSCILLATION": 0, "ACCUMULATION": 2,
            "UPTREND": 3, "BREAKOUT": 4}

# 特征名（与 Kotlin StockCheckResult 字段对齐；turnoverRate 对应 PC 的 turnover）
FEATS = ["convergenceDegree", "volumeRatio", "drawdownPct", "convergenceDays",
         "changePct", "turnoverRate", "momentum5", "ma60Bias", "ma250Bias", "direction"]

# 长线默认参数（与 backtest_params.json 长线 / StockCheckPipeline.analyze 一致）
LOOKBACK = 250
DURATION_WINDOW = 20
LOOSE_THRESHOLD = 2.5 + 0.5          # effectiveConvergence(默认2.5) + 0.5
SWING_LEFT_N, SWING_RIGHT_N = 5, 2
MA_RISING_DAYS = 10                  # 长线 maRisingDays
CONVERGENCE_MAX, ACCUM_MIN_DAYS = 6.0, 8
BREAKOUT_VOLUME_RATIO = 1.15


def _find_swing_high(highs, start_idx=0):
    """复刻 StockCheckPipeline.findSwingHighIndex：从右往左找左右 N 天不高于它的高点"""
    n = len(highs)
    for i in range(n - 1, start_idx - 1, -1):
        if i - SWING_LEFT_N < start_idx or i + SWING_RIGHT_N >= n:
            continue
        h = highs[i]
        if h >= max(highs[i - SWING_LEFT_N:i]) and h >= max(highs[i + 1:i + SWING_RIGHT_N + 1]):
            return i
    return -1


def direction_of_kotlin(df):
    """复刻 DirectionAnalyzer.analyze（纯函数，输入 df 需含 ma5/10/20/60,
    close, ma60 环比, convergenceDegree, convergenceDays, volumeRatio）"""
    maMax = df[["ma5", "ma10", "ma20", "ma60"]].max(axis=1)
    close_above_top = df["close"] >= maMax * 1.02
    above_all = df["close"] > maMax
    ma60_rising = df["ma60"] > df["ma60"].shift(MA_RISING_DAYS)
    is_conv = (df["convergenceDegree"] >= 0.1) & (df["convergenceDegree"] <= CONVERGENCE_MAX) \
        & (df["convergenceDays"] >= ACCUM_MIN_DAYS)
    breakout = is_conv & close_above_top & (df["volumeRatio"] >= BREAKOUT_VOLUME_RATIO) & above_all
    acc = is_conv
    up = (df["ma5"] > df["ma10"]) & (df["ma10"] > df["ma20"]) & (df["close"] > df["ma5"]) & ma60_rising
    down = (df["ma5"] < df["ma10"]) & (df["ma10"] < df["ma20"]) & (df["close"] < df["ma5"]) & ~ma60_rising
    return np.select([breakout, acc, up, down],
                     ["BREAKOUT", "ACCUMULATION", "UPTREND", "DOWNTREND"],
                     default="OSCILLATION")


def build_kotlin_feats(snaps, lookback_days=LOOKBACK):
    """与 StockCheckPipeline.analyzeSnaps 同口径：snaps(升序) -> DataFrame(date + FEATS)
    逐日回放：第 i 天的特征 = 模拟 app 在第 i 天扫描（输入窗口 = 截至 i 的 lookback+10 根）。
    返回 None 表示 K 线不足（<250 无法算 MA250）"""
    if snaps is None or len(snaps) < 250:
        return None
    df = pd.DataFrame(snaps).sort_values("date").reset_index(drop=True)
    df["ma5"] = df["close"].rolling(5).mean()
    df["ma10"] = df["close"].rolling(10).mean()
    df["ma20"] = df["close"].rolling(20).mean()
    df["ma60"] = df["close"].rolling(60).mean()
    df["ma250"] = df["close"].rolling(250).mean()

    df["momentum5"] = (df["close"] / df["ma5"] - 1.0) * 100.0
    df["ma60Bias"] = (df["close"] / df["ma60"] - 1.0) * 100.0
    df["ma250Bias"] = (df["close"] / df["ma250"] - 1.0) * 100.0

    ma_cols = ["ma5", "ma10", "ma20", "ma60"]
    df["convergenceDegree"] = (
        df[ma_cols].max(axis=1) / df[ma_cols].min(axis=1) - 1.0) * 100.0

    # volumeRatio = 当日量 / 前5日均量（Kotlin: takeLast(6).dropLast(1)）
    df["prev5"] = df["volume"].shift(1).rolling(5).mean()
    df["volumeRatio"] = df["volume"] / df["prev5"]

    # drawdownPct = (摆动高点 - close) / 摆动高点 * 100（findSwingHighIndex 逐日回放）
    highs = df["high"].to_numpy()
    n = len(df)
    swing_hi = np.empty(n)
    for i in range(n):
        start = max(i - lookback_days + 1, 0)
        seg = highs[start:i + 1]
        si = _find_swing_high(seg)
        swing_hi[i] = seg[si] if si >= 0 else seg.max()
    df["swingHigh"] = swing_hi
    df["drawdownPct"] = (df["swingHigh"] - df["close"]) / df["swingHigh"] * 100.0

    # convergenceDays = 最近20天窗口内 粘合度<=loose(3.0) 的天数计数（app 口径）
    # 粘合度与 app 一致：i>=59 时含 ma60，否则仅 ma5/10/20；i<19 → 0
    deg_all = (df[["ma5", "ma10", "ma20", "ma60"]].max(axis=1)
               - df[["ma5", "ma10", "ma20", "ma60"]].min(axis=1)) \
        / df[["ma5", "ma10", "ma20", "ma60"]].min(axis=1) * 100.0
    deg3 = (df[["ma5", "ma10", "ma20"]].max(axis=1)
            - df[["ma5", "ma10", "ma20"]].min(axis=1)) \
        / df[["ma5", "ma10", "ma20"]].min(axis=1) * 100.0
    deg = deg_all.where(df["ma60"].notna(), deg3).fillna(999.0)
    deg_le = (deg <= LOOSE_THRESHOLD).astype(int)
    cdays = deg_le.rolling(DURATION_WINDOW, min_periods=DURATION_WINDOW).sum().fillna(0).to_numpy()
    df["convergenceDays"] = np.where(np.arange(len(df)) >= 19, cdays, 0).astype(int)

    df["direction"] = pd.Series(direction_of_kotlin(df)).map(DIR_CODE).values
    df["changePct"] = df["changePct"].fillna(df["close"].pct_change() * 100.0)
    df["turnoverRate"] = df["turnover"].fillna(0.0)

    out = df[["date"] + FEATS].copy()
    out = out[df["ma250"].notna()]
    return out.dropna().reset_index(drop=True)
