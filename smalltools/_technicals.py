# -*- coding: utf-8 -*-
"""个股主流技术指标综合分析（零第三方依赖，纯 Python）。

数据源: 日线 snaps 序列，元素 dict {date, open, high, low, close, volume}。
指标: MA/EMA/MACD/KDJ/RSI/CCI/OBV/ATR/抛物线SAR。
颜色语义按 A 股习惯：红=多/涨（SAR 在价下方=上升趋势），绿=空/跌。

对外 API:
  analyze(snaps) -> dict   # 结构化指标判定
  make_tag(s)     -> str   # 单行紧凑技术摘要（≤~30 字符）
  sar_alert(snaps) -> dict | None  # SAR 刚转绿(≤3日) 预警
用法: _publish_candidates.py 在选股后对候选/实仓附加，随消息推送。
"""
import math


# ── 基础 ────────────────────────────────────────────────
def _sma(vals, n):
    return sum(vals[-n:]) / n if len(vals) >= n else None


def _ema(vals, n):
    if not vals:
        return None
    k = 2.0 / (n + 1)
    e = vals[0]
    for v in vals[1:]:
        e = v * k + e * (1 - k)
    return e


def _ema_series(vals, n):
    if not vals:
        return []
    k = 2.0 / (n + 1)
    out = [vals[0]]
    for v in vals[1:]:
        out.append(v * k + out[-1] * (1 - k))
    return out


# ── 抛物线 SAR（Wilder，step=0.02 max=0.2）──────────────
def parabolic_sar(highs, lows, closes, step=0.02, max_step=0.2):
    n = len(closes)
    sar = [None] * n
    if n < 3:
        return sar
    if closes[1] >= closes[0]:
        trend_up, ep, af = True, highs[1], step
        sar[0] = lows[0]
    else:
        trend_up, ep, af = False, lows[1], step
        sar[0] = highs[0]
    for i in range(1, n):
        base = sar[i - 1]
        if base is None:
            base = (lows[i - 1] if trend_up else highs[i - 1])
        s = base + af * (ep - base)
        if trend_up:
            if lows[i - 1] < s:
                s = lows[i - 1]
            if i >= 2 and lows[i - 2] < s:
                s = lows[i - 2]
        else:
            if highs[i - 1] > s:
                s = highs[i - 1]
            if i >= 2 and highs[i - 2] > s:
                s = highs[i - 2]
        if trend_up and lows[i] < s:
            sar[i], trend_up, ep, af = ep, False, lows[i], step
        elif (not trend_up) and highs[i] > s:
            sar[i], trend_up, ep, af = ep, True, highs[i], step
        else:
            sar[i] = s
            if trend_up:
                if highs[i] > ep:
                    ep, af = highs[i], min(af + step, max_step)
            elif lows[i] < ep:
                ep, af = lows[i], min(af + step, max_step)
    return sar


def _dir_series(closes, sar_arr):
    seq = []
    for c, s in zip(closes, sar_arr):
        if s is None:
            seq.append(None)
        else:
            seq.append("UP" if c >= s else "DOWN")
    return seq


def _run_bars(seq, tail=20):
    """末段同向持续交易日数 + 最近一次翻转方向/距今。"""
    n = len(seq)
    last = None
    i = n - 1
    while i >= 0 and seq[i] is None:
        i -= 1
    if i < 0:
        return None, 0, None, 0
    last = seq[i]
    bars = 0
    while i >= 0 and seq[i] == last:
        bars += 1
        i -= 1
    flip_dir = seq[i] if i >= 0 and seq[i] is not None else None
    flip_ago = bars if flip_dir else 0
    return last, bars, flip_dir, flip_ago


# ── 单票综合分析 ────────────────────────────────────────
def analyze(snaps, max_bars=260):
    """snaps(旧→新) → 结构化指标判定 dict；数据不足返回 {}。"""
    if not snaps or len(snaps) < 30:
        return {}
    ss = snaps[-max_bars:]
    closes = [s.get("close") for s in ss]
    if any(c is None for c in closes):
        closes = [c for c in closes if c is not None]
    closes = [float(c) for c in closes]
    if len(closes) < 30:
        return {}
    highs = [float(s.get("high") or closes[i]) for i, s in enumerate(ss)]
    lows = [float(s.get("low") or closes[i]) for i, s in enumerate(ss)]
    vols = [float(s.get("volume") or 0) for s in ss]
    out = {}
    # SAR
    try:
        sar_arr = parabolic_sar(highs, lows, closes)
        seq = _dir_series(closes, sar_arr)
        last, bars, flip_dir, flip_ago = _run_bars(seq)
        out["sar"] = {"dir": last, "bars": bars,
                      "flip_dir": flip_dir, "flip_ago": flip_ago}
    except Exception:
        out["sar"] = {}
    # MA 结构
    try:
        ma5 = _sma(closes, 5)
        ma10 = _sma(closes, 10)
        ma20 = _sma(closes, 20)
        ma60 = _sma(closes, 60)
        out["ma"] = {"ma20": ma20, "ma60": ma60,
                     "bull": (ma20 and ma60 and ma20 > ma60),
                     "above20": (ma20 and closes[-1] >= ma20)}
    except Exception:
        out["ma"] = {}
    # MACD
    try:
        dif = _ema_series(closes, 12)
        dea = _ema_series(dif, 9)
        difv, deav = dif[-1], dea[-1]
        hist = difv - deav
        prev_hist = (dif[-2] - dea[-2]) if len(dif) > 1 else hist
        cross = None
        if prev_hist <= 0 < hist:
            cross = "gold"
        elif prev_hist >= 0 > hist:
            cross = "dead"
        out["macd"] = {"dif": difv, "dea": deav, "hist": hist,
                       "cross": cross, "bull": hist > 0}
    except Exception:
        out["macd"] = {}
    # RSI(14, Wilder)
    try:
        n = 14
        gains, losses = [], []
        for i in range(1, len(closes)):
            chg = closes[i] - closes[i - 1]
            gains.append(max(chg, 0))
            losses.append(max(-chg, 0))
        g = sum(gains[:n]) / n
        l = sum(losses[:n]) / n
        for i in range(n, len(gains)):
            g = (g * (n - 1) + gains[i]) / n
            l = (l * (n - 1) + losses[i]) / n
        rsi = 100.0 if l == 0 else 100 - 100 / (1 + g / l) if l > 0 else 50.0
        out["rsi"] = round(rsi, 1)
    except Exception:
        out["rsi"] = None
    # KDJ(9,3,3)
    try:
        nn = 9
        kvals, dvals = 50.0, 50.0
        for i in range(len(closes)):
            lo = min(lows[max(0, i - nn + 1):i + 1])
            hi = max(highs[max(0, i - nn + 1):i + 1])
            rsv = 50.0 if hi == lo else (closes[i] - lo) / (hi - lo) * 100
            kvals = rsv * 2 / 3 + kvals / 3
            dvals = kvals * 2 / 3 + dvals / 3
        jv = 3 * kvals - 2 * dvals
        out["kdj"] = {"k": round(kvals, 1), "d": round(dvals, 1),
                      "j": round(jv, 1), "gold": 50 <= kvals and dvals < kvals}
    except Exception:
        out["kdj"] = {}
    # CCI(20)
    try:
        nn = 20
        seg_c = closes[-nn:]
        seg_h = highs[-nn:]
        seg_l = lows[-nn:]
        tp = (seg_c[-1] + seg_h[-1] + seg_l[-1]) / 3
        tps = [(c + h + l) / 3 for c, h, l in zip(seg_c, seg_h, seg_l)]
        mtp = sum(tps) / nn
        md = sum(abs(t - mtp) for t in tps) / nn
        out["cci"] = (tp - mtp) / (0.015 * md) if md > 0 else 0.0
    except Exception:
        out["cci"] = None
    # OBV
    try:
        obv = [0.0]
        for i in range(1, len(closes)):
            if closes[i] > closes[i - 1]:
                obv.append(obv[-1] + vols[i])
            elif closes[i] < closes[i - 1]:
                obv.append(obv[-1] - vols[i])
            else:
                obv.append(obv[-1])
        obv20 = sum(obv[-20:]) / min(len(obv), 20)
        out["obv_up"] = obv[-1] >= obv20 if obv20 else None
    except Exception:
        out["obv_up"] = None
    # ATR(14)%
    try:
        nn = 14
        trs = []
        for i in range(max(1, len(closes) - 60), len(closes)):
            tr = max(highs[i] - lows[i],
                     abs(highs[i] - closes[i - 1]),
                     abs(lows[i] - closes[i - 1]))
            trs.append(tr)
        if len(trs) >= nn:
            atr = sum(trs[-nn:]) / nn
            out["atr_pct"] = round(atr / closes[-1] * 100, 1)
    except Exception:
        out["atr_pct"] = None
    out["close"] = closes[-1]
    out["chg_pct"] = (closes[-1] / closes[-2] - 1) * 100 if len(closes) > 1 else None
    return out


def make_tag(s):
    """指标结构 → 单行紧凑中文摘要；空结构返回 ''。"""
    if not s:
        return ""
    p = []
    sar = s.get("sar") or {}
    d, bars = sar.get("dir"), sar.get("bars")
    fd, fa = sar.get("flip_dir"), sar.get("flip_ago")
    if d == "UP":
        if fd == "UP" and fa and fa <= 3:
            p.append("SAR刚翻红↑")   # 绿转红：重点关注加分
        else:
            p.append("SAR红↑%d" % (bars or 0))
    elif d == "DOWN":
        if fd == "DOWN" and fa and fa <= 3:
            p.append("SAR刚翻绿%d天!" % (fa or 0))  # 红转绿：不选/预警
        else:
            p.append("SAR绿↓%d" % (bars or 0))
    mc = s.get("macd") or {}
    if mc.get("cross") == "gold":
        p.append("MACD金叉")
    elif mc.get("cross") == "dead":
        p.append("MACD死叉")
    rsi = s.get("rsi")
    if rsi is not None and rsi >= 70:
        p.append("RSI超买%d" % int(rsi))
    elif rsi is not None and rsi <= 30:
        p.append("RSI超卖%d" % int(rsi))
    kdj = s.get("kdj") or {}
    if kdj and kdj.get("gold") and kdj.get("k", 50) < 35:
        p.append("KDJ低位金叉")
    ma = s.get("ma") or {}
    if ma.get("bull") is True:
        p.append("MA多头")
    if s.get("obv_up") is True and len(p) < 4:
        p.append("OBV升")
    if s.get("atr_pct") is not None and len(p) < 4:
        p.append("ATR%s%%" % s["atr_pct"])
    return " ".join(p[:4])


def sar_alert(snaps):
    """SAR 刚翻绿（空头且持续≤3日，红转绿初期）→ 预警 dict {days}，否则 None。"""
    try:
        s = analyze(snaps)
        sar = s.get("sar") or {}
        if sar.get("dir") == "DOWN" and sar.get("flip_dir") == "DOWN":
            ago = sar.get("flip_ago") or sar.get("bars") or 0
            if ago <= 3:
                return {"days": ago}
    except Exception:
        pass
    return None
