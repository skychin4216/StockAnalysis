# -*- coding: utf-8 -*-
"""个股主流技术指标综合分析（零第三方依赖，纯 Python）。

数据源: 日线 snaps 序列，元素 dict {date, open, high, low, close, volume}。
指标: MA/EMA/MACD/KDJ/RSI/CCI/OBV/ATR/抛物线SAR。
颜色语义按 A 股习惯：红=多/涨（SAR 在价下方=上升趋势），绿=空/跌。

对外 API:
  analyze(snaps) -> dict   # 结构化指标判定
  make_tag(s)     -> str   # 单行紧凑技术摘要（≤~30 字符）
  is_sar_flip(sar, dir) -> bool  # SAR 是否刚翻到该方向(≤3日)：绿转红 / 红转绿
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
def _ohlc(snaps, max_bars=260):
    """snaps(旧→新) → (closes, highs, lows, vols)，四序列【同长且下标对齐】。

    ★ 2026-09-13 修对齐 bug：旧实现先按 close 过滤、highs/lows/vols 却按原长度构造
    → 某天 close 缺失时三个数组整体错位，SAR/MACD/ATR 会静默算错（数值看着还正常）。
    现集中在这里保证「同一批 close 有效样本」构造所有序列，analyze 与自检复算共用。
    """
    ss = [s for s in (snaps or [])[-max_bars:] if s.get("close") is not None]
    closes = [float(s["close"]) for s in ss]
    highs = [float(s.get("high") or closes[i]) for i, s in enumerate(ss)]
    lows = [float(s.get("low") or closes[i]) for i, s in enumerate(ss)]
    vols = [float(s.get("volume") or 0) for s in ss]
    return closes, highs, lows, vols


def analyze(snaps, max_bars=260):
    """snaps(旧→新) → 结构化指标判定 dict；数据不足返回 {}。"""
    if not snaps or len(snaps) < 30:
        return {}
    closes, highs, lows, vols = _ohlc(snaps, max_bars)
    if len(closes) < 30:
        return {}
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
        # MA20 斜率：今值 vs 4 交易日前（走平/向上 = 斜率 ≥0）
        ma20_prev = _sma(closes[:-4], 20) if len(closes) >= 24 else None
        ma20_slope = ((ma20 - ma20_prev) / ma20_prev * 100.0
                      if (ma20 and ma20_prev) else None)
        # 多头真义(2026-09-08 收紧)：MA5≥MA10≥MA20>MA60 排列 + 现价站上 MA5
        # + MA20 走平/向上。避免『均线粘合向下/价破短均』仍标 MA多头 的假多头。
        bull = bool(ma5 and ma10 and ma20 and ma60
                    and ma5 >= ma10 and ma10 >= ma20 and ma20 > ma60
                    and closes[-1] >= ma5
                    and (ma20_slope is None or ma20_slope >= 0))
        squeeze = _ma_squeeze(closes)
        out["ma"] = {"ma20": ma20, "ma60": ma60, "bull": bull,
                     "squeeze": squeeze,
                     "squeeze_down": bool(squeeze and ma5 and ma10 and ma5 < ma10),
                     "ma20_slope": ma20_slope,
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


def is_sar_flip(sar, target_dir):
    """SAR 是否「刚翻到 target_dir」（绿转红 / 红转绿，且翻转 ≤3 日）。

    易错点：`_run_bars` 返回的 `flip_dir` 是【翻转前】的方向，与当前 `dir` 必然相反
    （None = 样本内从未翻转）。历史上此处被写成 `flip_dir == dir`，数学上恒为 False，
    导致「SAR刚翻红/刚翻绿」提示与 `sar_alert` 红转绿预警长期静默失效（2026-09-13 修复）。
    这里改用 `flip_dir != dir` 表达「方向发生过反转」，比硬编码方向更难写反。
    """
    fd = sar.get("flip_dir")
    return bool(sar.get("dir") == target_dir and fd and fd != sar.get("dir")
                and (sar.get("flip_ago") or 99) <= 3)


def make_tag(s):
    """指标结构 → 单行紧凑中文摘要；空结构返回 ''。"""
    if not s:
        return ""
    p = []
    sar = s.get("sar") or {}
    d, bars = sar.get("dir"), sar.get("bars")
    fa = sar.get("flip_ago")
    if d == "UP":
        if is_sar_flip(sar, "UP"):
            p.append("SAR刚翻红↑")   # 绿转红：重点关注加分
        else:
            p.append("SAR红↑%d" % (bars or 0))
    elif d == "DOWN":
        if is_sar_flip(sar, "DOWN"):
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
    elif ma.get("squeeze") and len(p) < 3:
        p.append("粘合↓" if ma.get("squeeze_down") else "粘合")
    if s.get("obv_up") is True and len(p) < 4:
        p.append("OBV升")
    if s.get("atr_pct") is not None and len(p) < 4:
        p.append("ATR%s%%" % s["atr_pct"])
    return " ".join(p[:4])


def _ma_squeeze(closes, tol_pct=1.8):
    """近端 MA5/10/20/30 极差相对中值 ≤ tol_pct% → 均线粘合（横盘蓄势）。"""
    if len(closes) < 30:
        return False
    vals = [_sma(closes, n) for n in (5, 10, 20, 30)]
    if any(v is None for v in vals):
        return False
    mid = sum(vals) / 4.0
    return mid > 0 and (max(vals) - min(vals)) / mid * 100.0 <= tol_pct


def rich_tag(snaps, max_tokens=6):
    """候选行『指标串』：数值 RSI + SAR(含刚翻红=绿转红) + MACD(金叉/死叉/柱收窄扩大)
    + OBV + 均线(多头/粘合) + 突破强度(距20日前高)，比 make_tag 密度更高。
    供推送选股行使用；样本不足或异常返回 ''。"""
    try:
        s = analyze(snaps)
        if not s:
            return ""
        closes = [float(x.get("close") or 0) for x in snaps]
        p = []
        rsi = s.get("rsi")
        if rsi is not None:
            p.append("RSI%d" % int(round(rsi)))
        sar = s.get("sar") or {}
        d, bars = sar.get("dir"), sar.get("bars") or 0
        fa = sar.get("flip_ago")
        if d == "UP":
            p.append("SAR刚翻红" if is_sar_flip(sar, "UP") else "SAR红↑%d" % bars)
        elif d == "DOWN":
            p.append("SAR刚翻绿%d天" % (fa or 0) if is_sar_flip(sar, "DOWN")
                     else "SAR绿↓%d" % bars)
        mc = s.get("macd") or {}
        if mc.get("cross") == "gold":
            p.append("MACD金叉")
        elif mc.get("cross") == "dead":
            p.append("MACD死叉")
        else:
            hist = mc.get("hist")
            if hist is not None:
                try:
                    dif = _ema_series(closes, 12)
                    dea = _ema_series(dif, 9)
                    prev = (dif[-2] - dea[-2]) if len(dif) > 1 and len(dea) > 1 else hist
                except Exception:
                    prev = hist
                color = "红" if hist > 0 else "绿"
                trend = "收窄" if abs(hist) < abs(prev) else "扩大"
                p.append("MACD%s柱%s" % (color, trend))
        obv = s.get("obv_up")
        if obv is True:
            p.append("OBV上行")
        elif obv is False:
            p.append("OBV下行")
        ma = s.get("ma") or {}
        if ma.get("bull") is True:
            p.append("MA多头")
        elif ma.get("squeeze"):
            p.append("均线粘合↓" if ma.get("squeeze_down") else "均线粘合")
        elif ma.get("ma20") and ma.get("ma60") and ma.get("ma20") <= ma.get("ma60"):
            p.append("MA偏空")
        # 突破强度：今日收盘 vs 前20日高点（不含当日）
        try:
            prev_high = max(float(x.get("high") or 0) for x in snaps[-21:-1])
            c = float(snaps[-1].get("close") or 0)
            if prev_high > 0 and c > 0:
                gap = (c / prev_high - 1) * 100
                if gap >= 0:
                    p.append("创20日新高")
                elif gap >= -5:
                    p.append("距前高%+.1f%%" % gap)
        except Exception:
            pass
        return " ".join(p[:max_tokens])
    except Exception:
        return ""


def sar_alert(snaps):
    """SAR 刚翻绿（红转绿初期，翻转≤3日）→ 预警 dict {days}，否则 None。"""
    try:
        s = analyze(snaps)
        sar = s.get("sar") or {}
        if is_sar_flip(sar, "DOWN"):
            return {"days": sar.get("flip_ago") or sar.get("bars") or 0}
    except Exception:
        pass
    return None


# ── 量比 / 人气估值档位标注（与 AutoQuant/autoquant/technicals.py 同源）────
def volume_ratio_of(snaps, k=5):
    """量比 = 当日成交量 / 前 k 日均量（样本不足返回 None）。"""
    if not snaps:
        return None
    vols = [float(s.get("volume") or 0) for s in snaps]
    if len(vols) < k + 1:
        return None
    base = sum(vols[-(k + 1):-1]) / k
    return vols[-1] / base if base > 0 else None


def annotate_quote(turnover=None, pe=None, volume_ratio=None):
    """换手率 / 市盈率 / 量比 → 中文档位标注（缺字段自动跳过；pe≤0 视为亏损）。
    turnover: 换手率%；pe: 动态市盈率；volume_ratio: 量比。"""
    tags = []
    if turnover is not None and turnover > 0:
        if turnover > 25:
            tags.append("换手%.1f%%·疯狂高换手，准备离场" % turnover)
        elif turnover >= 15:
            tags.append("换手%.1f%%·人气票，短期青睐" % turnover)
        elif turnover >= 5:
            tags.append("换手%.1f%%·活跃人气票" % turnover)
        elif turnover >= 2:
            tags.append("换手%.1f%%·有一定人气" % turnover)
        else:
            tags.append("换手%.1f%%·低迷" % turnover)
    if pe is not None:
        if pe <= 0:
            tags.append("PE亏损(%.0f)" % pe)
        elif pe < 20:
            tags.append("PE%.1f·估值较低" % pe)
        elif pe <= 50:
            tags.append("PE%.1f·估值合理" % pe)
        else:
            tags.append("PE%.1f·估值过高(科技/成长需另算)" % pe)
    if volume_ratio is not None and volume_ratio > 0:
        if volume_ratio >= 2.5:
            tags.append("量比%.1f·显著放量，注意变盘" % volume_ratio)
        elif volume_ratio >= 1.2:
            tags.append("量比%.1f·温和放量" % volume_ratio)
        elif volume_ratio >= 0.8:
            tags.append("量比%.1f·正常水平" % volume_ratio)
        else:
            tags.append("量比%.1f·缩量" % volume_ratio)
    return tags


# ── K线形态 / MACD 顶底背离 / 指数一行技术摘要（2026-09-13 新增）──────────────
def _fnum(row, key):
    """取 row[key]，缺失时回退 close。"""
    v = row.get(key)
    if v is None:
        v = row.get("close")
    return float(v or 0)


def patterns(snaps):
    """末根K线的**形态**判定（日线，旧→新）。

    返回 dict：hammer(锤子线)/doji(十字星)/bullish_engulf(看涨吞没)/
    morning_star(早晨之星)/vol_ratio(量比)/shrink(缩量)。

    锤子线口径（与 _idea_scan.py 一致）：下影 ≥ 2×实体、上影 ≤ max(实体, 12%振幅)、
    收阳或收平、且最低价落在近 10 日低位区（避免把下跌中继的长下影当底部信号）。
    """
    if not snaps or len(snaps) < 4:
        return {}
    k, p1, p2 = snaps[-1], snaps[-2], snaps[-3]
    o, c, h, l = (_fnum(k, "open"), _fnum(k, "close"),
                  _fnum(k, "high"), _fnum(k, "low"))
    body = abs(c - o)
    rng = max(h - l, 1e-9)
    up_sh, lo_sh = h - max(o, c), min(o, c) - l
    lo10 = min(_fnum(x, "low") for x in snaps[-10:])
    o1, c1 = _fnum(p1, "open"), _fnum(p1, "close")
    o2, c2 = _fnum(p2, "open"), _fnum(p2, "close")
    vols = [float(x.get("volume") or 0) for x in snaps]
    v5 = sum(vols[-6:-1]) / max(len(vols[-6:-1]), 1)
    vr = round(vols[-1] / v5, 2) if v5 > 0 else None
    return {
        "hammer": bool(lo_sh >= 2 * body and up_sh <= max(body, rng * 0.12)
                       and c >= o and l <= lo10 * 1.01),
        "doji": bool(body <= rng * 0.03),
        "bullish_engulf": bool(c1 < o1 and c > o and c >= o1 and o <= c1),
        "morning_star": bool(c2 < o2
                             and abs(c1 - o1) <= max(abs(c2 - o2) * 0.35, rng * 0.15)
                             and c > o and c >= (o2 + c2) / 2),
        "vol_ratio": vr,
        "shrink": bool(vr is not None and vr < 0.8),
    }


def _pivots(vals, gap=3, kind="low"):
    """摆动点索引：kind='low' → 左右各 gap 根内最低（含自身）。"""
    out = []
    for i in range(gap, len(vals) - gap):
        seg = vals[i - gap:i + gap + 1]
        if kind == "low":
            if vals[i] <= min(seg):
                out.append(i)
        else:
            if vals[i] >= max(seg):
                out.append(i)
    return out


def macd_divergence(snaps, lookback=120, gap=3, max_bars=260):
    """MACD 顶/底背离检测（日线，旧→新）。

    口径说明（两种都判，`by` 记录命中口径）：
    - **柱口径(hist = DIF-DEA)**：价格新低但**绿柱未同步创新低**（收窄）→ 最常用的
      「MACD底背离」；顶背离则是价格新高而红柱未同步创新高。
    - **DIF 口径**：价格新低且 DIF 抬高（动能线本身背离）。

    `level` 区分两种情况：
    - **near（就近背离）**：与**前一个摆动低点**比较 —— 教科书式标准判定；
    - **cross（跨级背离）**：就近不成立，但与该低点之前、价格**更高**的某个摆动低点
      比较成立（例：2026-09-11 科创50 日线 1516.20 破 08-03 低点 1549.74，而 MACD 绿柱
      由 -64.7 收窄到 -24.7 → 跨级柱背离）。这类信号弱于就近背离，输出时须标注对比日。

    返回 {"kind","level","by","p1","p2","price1","price2","dif1","dif2","hist1","hist2","ago"}；
    无背离返回 {}。ago = 距今多少根K线出现确认极值点。
    """
    if not snaps or len(snaps) < 40:
        return {}
    ss = snaps[-max_bars:]
    ss = [s for s in ss if s.get("close") is not None]
    if len(ss) < 40:
        return {}
    closes = [float(s["close"]) for s in ss]
    lows = [_fnum(s, "low") or closes[i] for i, s in enumerate(ss)]
    highs = [_fnum(s, "high") or closes[i] for i, s in enumerate(ss)]
    dif = _ema_series(_ema_series(closes, 12), 9)
    dea = _ema_series(dif, 9)
    if len(dif) != len(closes):
        return {}
    hist = [a - b for a, b in zip(dif, dea)]
    lo0 = max(0, len(closes) - lookback)

    def _cands(vals, kind):
        """摆动点候选：常规 pivot + 「末根若为近 gap*2 根极值」也算
        （关键点常常就是当天确认的，必须能被评估）。"""
        idx = [i for i in _pivots(vals, gap, kind) if i >= lo0]
        last_i = len(vals) - 1
        seg0 = max(lo0, last_i - gap * 2)
        if last_i - lo0 >= gap:
            if kind == "low" and vals[last_i] <= min(vals[seg0:last_i + 1]):
                idx.append(last_i)
            elif kind == "high" and vals[last_i] >= max(vals[seg0:last_i + 1]):
                idx.append(last_i)
        return sorted(set(idx))

    def _pack(kind, level, by, a, b):
        is_bottom = kind == "bottom"
        return {"kind": kind, "level": level, "by": by,
                "p1": ss[a].get("date"), "p2": ss[b].get("date"),
                "price1": round(lows[a] if is_bottom else highs[a], 2),
                "price2": round(lows[b] if is_bottom else highs[b], 2),
                "dif1": round(dif[a], 3), "dif2": round(dif[b], 3),
                "hist1": round(hist[a], 3), "hist2": round(hist[b], 3),
                "ago": len(closes) - 1 - b}

    def _scan(vals, kind, compare):
        """compare(a, b)：价格方向与动能方向是否构成背离，返回 'hist'/'dif'/''。"""
        cs = _cands(vals, kind)
        if len(cs) < 2:
            return {}
        b = cs[-1]
        trials = [("near", cs[-2])] + [("cross", x) for x in reversed(cs[:-1][:-1])]
        for level, a in trials:
            if not compare(a, b):
                continue
            if kind == "low":
                by = "hist" if hist[b] > hist[a] else ("dif" if dif[b] > dif[a] else "")
            else:
                by = "hist" if hist[b] < hist[a] else ("dif" if dif[b] < dif[a] else "")
            if by:
                return _pack("bottom" if kind == "low" else "top", level, by, a, b)
        return {}

    bot = _scan(lows, "low", lambda a, b: lows[b] < lows[a])
    top = _scan(highs, "high", lambda a, b: highs[b] > highs[a])
    if bot and top:
        return bot if bot["ago"] <= top["ago"] else top
    return bot or top


def divergence_text(d):
    """背离 dict → 中文摘要（无背离返回 ''）。"""
    if not d:
        return ""
    lv = "跨级" if d.get("level") == "cross" else "就近"
    by = "柱" if d.get("by") == "hist" else "DIF"
    if d["kind"] == "bottom":
        return "MACD底背离[%s·%s](%s→%s 价%.2f<%.2f 而%s未同步走低)" % (
            lv, by, d["p1"], d["p2"], d["price2"], d["price1"], by)
    return "MACD顶背离[%s·%s](%s→%s 价%.2f>%.2f 而%s未同步走高)" % (
        lv, by, d["p1"], d["p2"], d["price2"], d["price1"], by)


def trend_label(snaps):
    """『↑上涨/↓下跌/→震荡』+ 当日形态（大盘与个股共用的一行趋势图文案）。"""
    s = analyze(snaps)
    if not s:
        return ""
    ma = s.get("ma") or {}
    sar = s.get("sar") or {}
    c = s.get("close") or 0
    ma20 = ma.get("ma20") or 0
    if sar.get("dir") == "UP" and ma20 and c >= ma20:
        base = "↑上涨"
    elif sar.get("dir") == "DOWN" and ma20 and c < ma20:
        base = "↓下跌"
    else:
        base = "→震荡"
    p = patterns(snaps)
    extra = []
    if p.get("hammer"):
        extra.append("锤子线看涨")
    elif p.get("bullish_engulf"):
        extra.append("看涨吞没")
    elif p.get("morning_star"):
        extra.append("早晨之星")
    elif p.get("doji"):
        extra.append("十字星")
    if p.get("shrink"):
        extra.append("缩量")
    return "·".join([base] + extra) if extra else base


def index_brief(snaps, name="", max_bars=260):
    """指数/大盘一行技术摘要（与个股表同口径）：
    RSI / SAR / MACD / OBV / 均线粘合 / 量比 / 趋势图 / MACD背离。
    """
    s = analyze(snaps, max_bars=max_bars)
    if not s:
        return ""
    p = []
    rsi = s.get("rsi")
    if isinstance(rsi, (int, float)):
        rsi_s = "RSI%.0f" % rsi
        if rsi >= 70:
            rsi_s += "超买"
        elif rsi <= 30:
            rsi_s += "超卖"
        p.append(rsi_s)
    sar = s.get("sar") or {}
    if sar.get("dir") == "UP":
        p.append("SAR刚翻红↑" if is_sar_flip(sar, "UP")
                 else "SAR红↑%d" % (sar.get("bars") or 0))
    elif sar.get("dir") == "DOWN":
        p.append("SAR刚翻绿↓%d" % (sar.get("flip_ago") or 0) if is_sar_flip(sar, "DOWN")
                 else "SAR绿↓%d" % (sar.get("bars") or 0))
    mc = s.get("macd") or {}
    if mc.get("cross") == "gold":
        p.append("MACD金叉")
    elif mc.get("cross") == "dead":
        p.append("MACD死叉")
    elif mc.get("bull"):
        p.append("MACD红柱")
    else:
        p.append("MACD绿柱")
    if s.get("obv_up") is True:
        p.append("OBV上行")
    elif s.get("obv_up") is False:
        p.append("OBV下行")
    ma = s.get("ma") or {}
    if ma.get("squeeze"):
        p.append("均线粘合↓" if ma.get("squeeze_down") else "均线粘合")
    elif ma.get("bull"):
        p.append("MA多头")
    vr = s.get("atr_pct")
    v = volume_ratio_of(snaps)
    if v is not None:
        p.append("量比%.1f" % v)
    elif isinstance(vr, (int, float)):
        p.append("ATR%.1f%%" % vr)
    t = trend_label(snaps)
    if t:
        p.append("趋势图 " + t)
    d = macd_divergence(snaps)
    if d:
        p.append("⚠" + ("MACD底背离" if d["kind"] == "bottom" else "MACD顶背离"))
    return ("%s " % name if name else "") + " ".join(p)


# ── 命令行体检（2026-09-13）────────────────────────────────
# 用法:
#   python _technicals.py                 # 全池体检：列「刚翻红 / 刚翻绿」重点票
#   python _technicals.py sh600973 ...    # 指定代码 → 全指标明细
#   python _technicals.py --selfcheck     # SAR 语义不变量自检（防「刚翻红」判断写反）
def _load_cache():
    import json
    import os
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
    with open(p, encoding="utf-8") as fh:
        return json.load(fh)


def _detail(code, name, snaps):
    s = analyze(snaps)
    if not s:
        return "%-9s %s  样本不足(<30 根)" % (code, name)
    sar = s.get("sar") or {}
    return "\n".join([
        "%-9s %s" % (code, name),
        "  SAR       dir=%s bars=%s flip=%s/%s → 刚翻红=%s 刚翻绿=%s"
        % (sar.get("dir"), sar.get("bars"), sar.get("flip_dir"), sar.get("flip_ago"),
           is_sar_flip(sar, "UP"), is_sar_flip(sar, "DOWN")),
        "  sar_alert %s" % sar_alert(snaps),
        "  RSI %s  MACD %s" % (s.get("rsi"), s.get("macd")),
        "  KDJ %s" % (s.get("kdj"),),
        "  MA %s" % (s.get("ma"),),
        "  OBV升 %s  ATR%% %s  量比 %s"
        % (s.get("obv_up"), s.get("atr_pct"), volume_ratio_of(snaps)),
        "  趋势 %s   背离 %s" % (trend_label(snaps), macd_divergence(snaps)),
        "  make_tag %s" % make_tag(s),
        "  rich_tag %s" % rich_tag(snaps),
    ])


def _selfcheck(cache):
    """SAR 语义自检：① 翻转前方向必与当前相反 ② 独立复算 bars/flip ③ 显示与判定一致。"""
    bad, n, fu, fd = [], 0, 0, 0
    for code, e in (cache or {}).items():
        snaps = (e or {}).get("snaps") or []
        if len(snaps) < 30:
            continue
        n += 1
        s = analyze(snaps)
        sar = s.get("sar") or {}
        d, bars, fdir, fago = (sar.get("dir"), sar.get("bars"),
                               sar.get("flip_dir"), sar.get("flip_ago"))
        if fdir is not None and fdir == d:
            bad.append("%s flip_dir==dir(%s)：翻转前方向不可能与当前相同" % (code, d))
        closes, highs, lows, _v = _ohlc(snaps)
        last, b2, f2, a2 = _run_bars(_dir_series(closes, parabolic_sar(highs, lows, closes)))
        if (d, bars, fdir, fago) != (last, b2, f2, a2):
            bad.append("%s 复算不一致 analyze=%s 复算=%s"
                       % (code, (d, bars, fdir, fago), (last, b2, f2, a2)))
        up, dn = is_sar_flip(sar, "UP"), is_sar_flip(sar, "DOWN")
        fu += up
        fd += dn
        for tag in (make_tag(s), rich_tag(snaps)):
            if tag and ("SAR刚翻红" in tag) != up:
                bad.append("%s 刚翻红显示不一致: %s" % (code, tag))
        if (sar_alert(snaps) is not None) != dn:
            bad.append("%s sar_alert 与 is_sar_flip 不一致" % code)
        # ④ 数据合理性：A 股个股单日换手率不可能 >100%（科创板曾因 volume 在日K路径
        #    被当「手」而放大 100 倍，见 backtest_guangmo.vol_shares_factor）
        to = (snaps[-1] or {}).get("turnover") or 0
        if to > 100:
            bad.append("%s 换手率异常 %.1f%%（疑 volume 单位错）" % (code, to))
    print("SAR 自检：样本 %d 只 · 刚翻红 %d · 刚翻绿 %d · 异常 %d 处" % (n, fu, fd, len(bad)))
    for b in bad[:20]:
        print("  ✗ " + b)
    return 1 if bad else 0


def main(argv):
    cache = _load_cache()
    if "--selfcheck" in argv:
        return _selfcheck(cache)
    codes = [a for a in argv if not a.startswith("-")]
    if codes:
        for c in codes:
            e = cache.get(c)
            if not e:
                print("未找到 %s（缓存共 %d 只）" % (c, len(cache)))
                continue
            print(_detail(c, e.get("name") or "", e.get("snaps") or []))
            print()
        return 0
    rows = []
    for code, e in cache.items():
        snaps = (e or {}).get("snaps") or []
        if len(snaps) >= 30:
            s = analyze(snaps)
            rows.append((code, (e or {}).get("name") or "", s.get("sar") or {}, s))
    up_rows = [r for r in rows if is_sar_flip(r[2], "UP")]
    dn_rows = [r for r in rows if is_sar_flip(r[2], "DOWN")]
    print("全池体检 %d 只：刚翻红 %d、刚翻绿 %d" % (len(rows), len(up_rows), len(dn_rows)))
    print("\n【刚翻红（绿转红 ≤3 日，重点关注）】")
    for code, name, _sar, s in up_rows:
        print("  %-9s %-7s %s" % (code, name[:7], make_tag(s)))
    print("\n【刚翻绿（红转绿 ≤3 日，预警）】")
    for code, name, _sar, s in dn_rows:
        print("  %-9s %-7s %s" % (code, name[:7], make_tag(s)))
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main(sys.argv[1:]))
