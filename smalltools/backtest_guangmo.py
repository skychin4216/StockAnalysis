# -*- coding: utf-8 -*-
"""
复刻 StockCheckPipeline.analyzeSnaps 的均线多头粘合选股逻辑，
用东财日K数据回测 2026-08-12 收盘后能否选中光模块个股。

关键点：
- 四周期参数（超短/短/中/长）与 Kotlin companion 完全一致
- 13 项检查逐项复刻（含大盘动态调整、摆动高点、三日确认）
- 两种口径对比：
    A) 复刻代码 bug：latest.changePct 实际被换手率(f61)覆盖
    B) 修正后：latest.changePct = 真实涨跌幅(f59)
"""
import math
import random
import time

import requests

END_DATE = "20260812"  # 回测截止 8/12 收盘（过滤掉 8/13 大涨日）

HEADERS = {"User-Agent": "Mozilla/5.0"}
PROXIES = {"http": None, "https": None}
_SESSION = requests.Session()  # 连接复用（keep-alive），避免每请求 TCP+TLS 握手（拉取提速）

# ───────────────────────── 数据拉取 ─────────────────────────
# 腾讯 fqkline 为主（稳定）；换手率 = 成交量 / 流通股本（流通股本由实时接口反推）
import re

HOSTS = ["https://90.push2his.eastmoney.com", "https://push2his.eastmoney.com"]


def to_east_secid(secid):
    if secid.startswith("sz"):
        return "0." + secid[2:]
    if secid.startswith("sh"):
        return "1." + secid[2:]
    return secid


def parse_east_klines(klines):
    snaps = []
    for k in klines:
        p = k.split(",")
        snaps.append({
            "date": p[0],
            "open": float(p[1]), "close": float(p[2]),
            "high": float(p[3]), "low": float(p[4]),
            "volume": float(p[5]),
            "changePct": float(p[8]),      # f59 真实涨跌幅(%)
            "turnover": float(p[10]) if len(p) > 10 else 0.0,  # f61 换手率(%)
        })
    return snaps


def fetch_east(secid, beg="20250101", end=END_DATE):
    params = {
        "secid": to_east_secid(secid), "klt": "101", "fqt": "1",
        "beg": beg, "end": end,
        "fields1": "f1,f2,f3,f4,f5,f6",
        "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
    }
    for attempt in range(3):
        host = random.choice(HOSTS)
        try:
            r = _SESSION.get(host + "/api/qt/stock/kline/get", params=params,
                             timeout=10, headers=HEADERS, proxies=PROXIES)
            data = r.json()
            if data.get("data") and data["data"].get("klines"):
                return data["data"]["name"], parse_east_klines(data["data"]["klines"])
        except Exception:
            pass
        time.sleep(0.5)
    return None, None


def fetch_tencent_float_shares(secid):
    """返回流通股本(股)；指数返回 None"""
    if secid.startswith("sh000") or secid.startswith("sz399"):
        return None
    try:
        r = _SESSION.get("https://qt.gtimg.cn/q=" + secid, timeout=10,
                         headers=HEADERS, proxies=PROXIES)
        r.encoding = "gbk"
        m = re.search(r'="([^"]*)"', r.text)
        if not m:
            return None
        f = m.group(1).split("~")
        price = float(f[3])
        float_mv = float(f[44])  # 流通市值(亿)
        if price <= 0 or float_mv <= 0:
            return None
        return float_mv * 1e8 / price
    except Exception:
        return None


def fmt_tencent_date(d):
    """20260812 -> 2026-08-12（腾讯要求带横杠）"""
    if len(d) == 8 and d.isdigit():
        return f"{d[:4]}-{d[4:6]}-{d[6:]}"
    return d


def fetch_tencent(secid, beg="20250101", end=END_DATE):
    url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
    params = {"param": f"{secid},day,{fmt_tencent_date(beg)},{fmt_tencent_date(end)},640,qfq"}
    for attempt in range(3):
        try:
            r = _SESSION.get(url, params=params, timeout=15, headers=HEADERS, proxies=PROXIES)
            data = r.json()
            node = (data.get("data") or {}).get(secid) or {}
            rows = node.get("qfqday") or node.get("day") or []
            if rows:
                snaps = []
                for p in rows:
                    snaps.append({
                        "date": p[0],
                        "open": float(p[1]), "close": float(p[2]),
                        "high": float(p[3]), "low": float(p[4]),
                        "volume": float(p[5]),
                        "changePct": 0.0,
                        "turnover": 0.0,
                    })
                for i in range(1, len(snaps)):
                    prev = snaps[i - 1]["close"]
                    if prev > 0:
                        snaps[i]["changePct"] = (snaps[i]["close"] / prev - 1) * 100
                # 用流通股本估算换手率（腾讯日K不直接给换手率）
                fs = fetch_tencent_float_shares(secid)
                if fs and fs > 0:
                    for s in snaps:
                        s["turnover"] = s["volume"] * 100 / fs * 100
                # 名称解析：qt 是 dict {secid: [市场, 名称, 代码, ...]}，
                # 真实名称在 qt[secid][1]（如 ['51','中际旭创','300308',...]）
                name = None
                qt = node.get("qt")
                if isinstance(qt, dict):
                    arr = qt.get(secid)
                    if isinstance(arr, list) and len(arr) > 1:
                        name = arr[1]
                if not name:
                    name = node.get("name") or secid
                return name, snaps
        except Exception:
            pass
        time.sleep(0.5)
    return None, None


def fetch_kline(secid, beg="20250101", end=END_DATE):
    """腾讯为主（含换手率估算）；东财偶尔可用则用于交叉校验"""
    name, snaps = fetch_tencent(secid, beg, end)
    if snaps:
        return name, snaps, "tencent"
    name, snaps = fetch_east(secid, beg, end)
    if snaps:
        return name, snaps, "east"
    return None, [], "none"


def get_index_dir(name, snaps):
    """复刻 StrategyMarketContext.dir(): MA5>MA10>MA20 -> BULLISH 等"""
    if len(snaps) < 20:
        return "UNKNOWN"
    closes = [s["close"] for s in snaps]
    ma5 = sum(closes[-5:]) / 5
    ma10 = sum(closes[-10:]) / 10
    ma20 = sum(closes[-20:]) / 20
    if ma5 > ma10 > ma20:
        return "BULLISH"
    if ma5 < ma10 < ma20:
        return "BEARISH"
    return "OSCILLATION"


def triple_vote(dirs):
    votes = [d for d in dirs if d != "UNKNOWN"]
    if len(votes) < 2:
        return "UNKNOWN"
    bull = votes.count("BULLISH")
    bear = votes.count("BEARISH")
    if bull >= 2:
        return "BULLISH"
    if bear >= 2:
        return "BEARISH"
    return "OSCILLATION"


# ───────────────────────── 复刻 StockCheckPipeline ─────────────────────────
def parse_market_regime(trend):
    if not trend:
        return "NEUTRAL"
    if "BULL" in trend.upper():
        return "BULLISH"
    if "BEAR" in trend.upper():
        return "BEARISH"
    return "NEUTRAL"  # OSCILLATION 等一律 NEUTRAL（与 Kotlin 一致）


def market_lookback_multiplier(regime):
    return {"BULLISH": 0.7, "NEUTRAL": 1.0, "BEARISH": 1.5}[regime]


CYCLICAL_KEYWORDS = ["锂", "矿", "有色", "煤炭", "钢铁", "石化", "稀土", "铜", "铝", "钴", "镍", "黄金", "资源", "能源"]


def is_cyclical_industry(name):
    return any(k in name for k in CYCLICAL_KEYWORDS)


def find_swing_high_index(snaps, start_idx, left_n, right_n):
    for i in range(len(snaps) - 1, start_idx - 1, -1):
        if i - left_n < start_idx or i + right_n >= len(snaps):
            continue
        h = snaps[i]["high"]
        left_max = max(snaps[j]["high"] for j in range(i - left_n, i))
        right_max = max(snaps[j]["high"] for j in range(i + 1, i + right_n + 1))
        if h >= left_max and h >= right_max:
            return i
    return -1


def avg(xs):
    return sum(xs) / len(xs)


def _is_stable_dip(snaps):
    """连跌后企稳形态：基座(最近3日)前存在≥3连跌，且近3日不创新低、低点逐日抬高。
    与 usecase_pipeline._is_stabilize 同口径（v11 企稳低吸旁路使用）。"""
    try:
        closes = [float(s["close"]) for s in snaps]
        lows = [float(s["low"]) for s in snaps]
    except (TypeError, ValueError, KeyError):
        return False
    n = len(closes)
    if n < 20:
        return False
    down3 = any(closes[j] < closes[j - 1] and closes[j - 1] < closes[j - 2]
                for j in range(n - 4, max(n - 12, 2), -1))
    rising = lows[-1] > lows[-2] > lows[-3]
    no_new = min(lows[-3:]) > min(lows[max(n - 13, 0):-3])
    return down3 and rising and no_new


def analyze_snaps(snaps, p, market_trend, market_vol_ratio=None):
    """复刻 analyzeSnaps，p 为参数 dict，market_trend 为 tripleVote 字符串。
    market_vol_ratio: 大盘当日量/前5日均量（<1 表示大盘缩量）。大盘缩量时个股缩量是市场
    整体行为，量能阈值应动态下调（个股相对大盘仍放量即可），避免误杀液冷/煤炭等缩量缓涨股。"""
    if len(snaps) < 20:
        return {"error": "数据不足"}
    latest = snaps[-1]
    closes = [s["close"] for s in snaps]

    regime = parse_market_regime(market_trend)
    cyclical = is_cyclical_industry(latest.get("name", ""))
    cyc_mul = 1.3 if (cyclical and p["lookbackDays"] >= 60) else 1.0
    effective_lookback = int(p["lookbackDays"] * market_lookback_multiplier(regime) * cyc_mul)
    effective_lookback = max(20, min(effective_lookback, len(snaps)))
    if regime == "BULLISH":
        effective_convergence = p["convergenceThreshold"] * 1.5  # 牛市均线自然发散，阈值放宽 50%
    elif regime == "BEARISH":
        effective_convergence = max(p["convergenceThreshold"] * 0.8, 1.0)  # 熊市收紧 20%
    else:
        effective_convergence = p["convergenceThreshold"]

    ma5 = avg(closes[-5:])
    ma10 = avg(closes[-10:]) if len(closes) >= 10 else ma5
    ma20 = avg(closes[-20:]) if len(closes) >= 20 else ma5
    ma60 = avg(closes[-60:]) if len(closes) >= 60 else None
    ma250 = avg(closes[-250:]) if len(closes) >= 250 else None

    mas = [ma5, ma10, ma20]
    if p["useMA60"] and ma60 is not None:
        mas.append(ma60)

    # ① 粘合度
    ma_max, ma_min = max(mas), min(mas)
    convergence_degree = (ma_max - ma_min) / ma_min * 100 if ma_min > 0 else 999.0
    convergence_ok = convergence_degree <= effective_convergence

    # ② 多头排列
    if p["useMA250InBullish"] and ma60 is not None and ma250 is not None:
        bullish_aligned = ma5 > ma10 > ma20 > ma60 > ma250
    elif p["useMA60"] and ma60 is not None:
        bullish_aligned = ma5 > ma10 > ma20 > ma60
    else:
        bullish_aligned = ma5 > ma10 > ma20

    # ③ 粘合持续天数
    duration_window = max(p["convergenceDurationDays"], 5)
    required_days = max(math.ceil(duration_window * p["convergenceDurationRatio"]), 1)
    loose_threshold = effective_convergence + 0.5
    convergence_days = 0
    window_start = max(len(closes) - duration_window, 19)
    for i in range(window_start, len(closes)):
        w = closes[:i + 1]
        w_ma5 = avg(w[-5:])
        w_ma10 = avg(w[-10:]) if len(w) >= 10 else w_ma5
        w_ma20 = avg(w[-20:]) if len(w) >= 20 else w_ma5
        w_mas = [w_ma5, w_ma10, w_ma20]
        if p["useMA60"] and len(w) >= 60:
            w_mas.append(avg(w[-60:]))
        w_max, w_min = max(w_mas), min(w_mas)
        w_deg = (w_max - w_min) / w_min * 100 if w_min > 0 else 999.0
        if w_deg <= loose_threshold:
            convergence_days += 1
    convergence_duration_ok = convergence_days >= required_days

    # ④ 量能条件
    vol5_avg = avg([s["volume"] for s in snaps[-6:-1]]) if len(snaps) >= 6 else latest["volume"]
    volume_ratio = latest["volume"] / vol5_avg if vol5_avg > 0 else 1.0
    if p["requireVolumeShrink"]:  # 地量（长线）
        vol10 = avg([s["volume"] for s in snaps[-10:]]) if len(snaps) >= 10 else latest["volume"]
        vol60 = avg([s["volume"] for s in snaps[-60:]]) if len(snaps) >= 60 else vol10
        volume_ok = vol60 > 0 and vol10 < vol60 * p["volumeShrinkRatio"]
    elif p["moderateVolumeLower"] > 0 and p["moderateVolumeUpper"] > 0:  # 温和放量（中线）
        ma_vol5 = avg([s["volume"] for s in snaps[-5:]]) if len(snaps) >= 5 else latest["volume"]
        ma_vol10_prev = avg([s["volume"] for s in snaps[-11:-1]]) if len(snaps) >= 11 else latest["volume"]
        mod_ratio = ma_vol5 / ma_vol10_prev if ma_vol10_prev > 0 else 0.0
        volume_ok = p["moderateVolumeLower"] <= mod_ratio <= p["moderateVolumeUpper"]
    else:  # 放量突破（超短/短线）
        # v10: 大盘量能调节——大盘缩量(market_vol_ratio<1)时阈值动态下调，
        # 个股相对大盘仍放量即可（避免大盘缩量误杀个股缩量缓涨股）
        market_scale = market_vol_ratio if (market_vol_ratio and market_vol_ratio > 0) else 1.0
        # 有效阈值 = 基准阈值 × max(大盘量比, 0.5)：大盘缩量到0.5 → 阈值减半
        effective_ratio = p["volumeBreakoutRatio"] * max(market_scale, 0.5)
        volume_ok = volume_ratio >= effective_ratio

    # ⑤ 距摆动高点跌幅
    lookback_start = max(len(snaps) - effective_lookback, 0)
    swing_idx = find_swing_high_index(snaps, lookback_start, p.get("swingLeftN", 5), p.get("swingRightN", 2))
    if swing_idx >= 0:
        swing_high = snaps[swing_idx]["high"]
    else:
        swing_high = max(s["high"] for s in snaps[lookback_start:]) or latest["high"]
    drawdown_pct = (swing_high - latest["close"]) / swing_high * 100 if swing_high > 0 else 0.0
    drawdown_ok = p["minDrawdownPct"] <= 0 or drawdown_pct >= p["minDrawdownPct"]

    # ⑥ MA60 上升
    ma60_rising = False
    if p["requireMA60Rising"] and ma60 is not None and len(closes) >= 60 + p["maRisingDays"]:
        ma60_prev = avg(closes[:len(closes) - p["maRisingDays"]][-60:])
        ma60_rising = ma60 > ma60_prev

    # ⑩ MA250 上升
    ma250_rising = True
    if p["requireMA250Rising"] and ma250 is not None and len(closes) >= 250 + p["maRisingDays"]:
        ma250_prev = avg(closes[:len(closes) - p["maRisingDays"]][-250:])
        ma250_rising = ma250 > ma250_prev

    # ⑦ 年线
    above_year_line = (latest["close"] > ma250) if (p["requireAboveYearLine"] and ma250 is not None) else False

    # ⑧ 涨幅（注意：changePct 口径由调用方注入——可能被换手率覆盖）
    min_change = p.get("minChangePct", 0.0)
    change_pct_ok = (latest["changePct"] >= min_change) if p["requireChangePct"] else True

    # ⑨ 站上所有均线
    if p["requireAboveAllMAs"]:
        if p["useMA60"] and ma60 is not None:
            above_all = latest["close"] > ma5 and latest["close"] > ma10 and latest["close"] > ma20 and latest["close"] > ma60
        else:
            above_all = latest["close"] > ma5 and latest["close"] > ma10 and latest["close"] > ma20
    else:
        above_all = True

    # ⑪ 收盘远离粘合区上沿 >2%
    close_above_top = (latest["close"] > ma_max * 1.02) if p["requireCloseAboveConvergenceTop"] else True

    # ⑫ 开盘低于三线且收盘站上5日线
    open_below = (latest["open"] < ma5 and latest["open"] < ma10 and latest["open"] < ma20 and latest["close"] > ma5) \
        if p["requireOpenBelowMAs"] else True

    # ⑬ 三日不新低
    three_day_no_new_low = True
    if len(snaps) >= 4:
        prev_low = snaps[-4]["low"]
        three_day_no_new_low = all(s["low"] >= prev_low for s in snaps[-3:])
    three_day_ok = three_day_no_new_low if p["requireThreeDayConfirm"] else True

    # ── v9: 量能放宽（超短/短线缩量缓涨——悄咪咪拉高） ──
    # 放量突破模式：量比达标 或（允许缩量缓涨 且 粘合+多头+三日不新低 且 量比≥quietVolumeRatio）
    if not p.get("requireVolumeShrink") and not (p.get("moderateVolumeLower", 0) > 0 and p.get("moderateVolumeUpper", 0) > 0):
        quiet_ratio = p.get("quietVolumeRatio", 1.0)
        # v10: 大盘缩量时"悄咪咪拉高"通道同样放宽——个股缩量缓涨在大盘缩量下更常见
        mscale = market_vol_ratio if (market_vol_ratio and market_vol_ratio > 0) else 1.0
        quiet_ok = p.get("allowQuietRise", False) and convergence_ok and bullish_aligned and \
            three_day_no_new_low and volume_ratio >= quiet_ratio * max(mscale, 0.5)
        volume_ok = volume_ok or quiet_ok

    # ── v9: 低位埋伏通道（中长线）——上证指数均线粘合+三天不新低即可选中长线 ──
    # 个股满足 均线粘合+多头+三日不新低+站上MA5 即通过（不强制放量/深跌/年线），
    # 可捕捉液冷/煤炭等"悄咪咪拉高"的缩量缓涨股。
    low_ambush = p.get("allowLowAmbush", False)
    macro_hit = False
    if p.get("macroSectorKeywords"):
        macro_hit = any(kw in latest.get("name", "") for kw in p["macroSectorKeywords"])
    ambush_ok = False
    if low_ambush:
        base_ambush = convergence_ok and bullish_aligned and three_day_no_new_low and latest["close"] > ma5
        ambush_ok = base_ambush or (macro_hit and convergence_ok and three_day_no_new_low and latest["close"] > ma5)

    # ── 统计 ──
    checks = {
        "①粘合度": (convergence_ok, f"{convergence_degree:.2f}%≤{effective_convergence:.1f}%"),
        "②多头排列": (bullish_aligned, ""),
        "③粘合持续": (convergence_duration_ok, f"{convergence_days}/{required_days}天"),
        "④量能": (volume_ok, f"量比{volume_ratio:.2f}"),
        "⑤跌幅": (drawdown_ok, f"{drawdown_pct:.1f}%≥{p['minDrawdownPct']}%"),
        "⑥MA60上升": (ma60_rising, ""),
        "⑦年线": (above_year_line, ""),
        "⑧涨幅": (change_pct_ok, f"{latest['changePct']:.2f}%≥{min_change}%"),
        "⑨站上均线": (above_all, ""),
        "⑩MA250上升": (ma250_rising, ""),
        "⑪远离上沿": (close_above_top, ""),
        "⑫开盘条件": (open_below, ""),
        "⑬三日不新低": (three_day_ok, ""),
        "⑭低位埋伏": (ambush_ok, "均线粘合+三日不新低"),
    }
    active_order = ["①粘合度", "②多头排列", "③粘合持续", "④量能", "⑤跌幅"]
    if p["requireMA60Rising"]:
        active_order.append("⑥MA60上升")
    if p["requireAboveYearLine"]:
        active_order.append("⑦年线")
    if p["requireChangePct"]:
        active_order.append("⑧涨幅")
    if p["requireAboveAllMAs"]:
        active_order.append("⑨站上均线")
    if p["requireMA250Rising"]:
        active_order.append("⑩MA250上升")
    if p["requireCloseAboveConvergenceTop"]:
        active_order.append("⑪远离上沿")
    if p["requireOpenBelowMAs"]:
        active_order.append("⑫开盘条件")
    if p["requireThreeDayConfirm"]:
        active_order.append("⑬三日不新低")

    pass_count = sum(1 for k in active_order if checks[k][0])
    total_checks = len(active_order)
    passed = pass_count >= p["minPassCount"]
    # v9: 低位埋伏通道——均线粘合+多头+三日不新低+站上MA5 直接通过（中长线低位埋伏）
    if p.get("allowLowAmbush", False) and ambush_ok:
        passed = True
    # v11: 企稳低吸旁路（2026-09-08 · 晋控能源型场景）——
    #   出现「连跌后 3日不新低 + 低点逐日抬高」企稳形态的候选视为低吸机会，
    #   免去均线粘合/多头排列/放量等"突破型"考核（口径依据 smalltools/_stabilize_dip_stat.py：
    #   企稳"粘合 vs 非粘合"短线差异不显著，粘合过严会漏掉晋控这类企稳低吸机会）。
    #   仅保留两项数据保护：当日非跌停级下跌、非异常天量(除权/停牌复牌污染)。
    stable_dip_bypass = False
    if p.get("allowStableDip", False) and _is_stable_dip(snaps):
        chg = 0.0
        try:
            chg = float(latest.get("changePct", 0))
        except (TypeError, ValueError):
            pass
        if chg > -9.5 and (volume_ratio or 0) < 20.0:
            passed = True
            stable_dip_bypass = True

    return {
        "convergenceDegree": convergence_degree,
        "passCount": pass_count, "totalChecks": total_checks, "passed": passed,
        "activeChecks": active_order, "checks": checks,
        "drawdownPct": drawdown_pct, "swingHigh": swing_high,
        "volumeRatio": volume_ratio, "convergenceDays": convergence_days,
        "effectiveLookback": effective_lookback, "effectiveConvergence": effective_convergence,
        "close": latest["close"], "changePctReal": latest["changePct"], "turnover": latest["turnover"],
        "date": latest["date"], "regime": regime,
        "stableDipBypass": stable_dip_bypass,
    }


# ───────────────────────── 四周期参数（与 Kotlin companion 一致） ─────────────────────────
PARAMS = {
    "超短": dict(convergenceThreshold=3.0, useMA60=False, convergenceDurationDays=5,
                 volumeBreakoutRatio=2.5, minChangePct=2.0, requireChangePct=True,
                 minDrawdownPct=10.0, requireCloseAboveConvergenceTop=True,
                 requireOpenBelowMAs=True, lookbackDays=30, minPassCount=7,
                 requireThreeDayConfirm=True, convergenceDurationRatio=0.8,
                 maRisingDays=5, requireMA60Rising=False, requireMA250Rising=False,
                 useMA250InBullish=False, requireAboveYearLine=False,
                 requireAboveAllMAs=False, requireVolumeShrink=False,
                 moderateVolumeLower=0.0, moderateVolumeUpper=0.0,
                 allowQuietRise=True, quietVolumeRatio=1.0, allowLowAmbush=False,
                 allowStableDip=True),
    "短线": dict(convergenceThreshold=3.0, useMA60=True, convergenceDurationDays=10,
                volumeBreakoutRatio=1.5, minChangePct=3.0, requireChangePct=True,
                minDrawdownPct=20.0, requireAboveAllMAs=True, lookbackDays=60,
                minPassCount=6, requireThreeDayConfirm=True, convergenceDurationRatio=0.8,
                maRisingDays=5, requireMA60Rising=False, requireMA250Rising=False,
                useMA250InBullish=False, requireAboveYearLine=False,
                requireCloseAboveConvergenceTop=False, requireOpenBelowMAs=False,
                requireVolumeShrink=False, moderateVolumeLower=0.0, moderateVolumeUpper=0.0,
                allowQuietRise=True, quietVolumeRatio=1.0, allowLowAmbush=False,
                allowStableDip=True),
    "中线": dict(convergenceThreshold=2.5, useMA60=True, convergenceDurationDays=15,
                minDrawdownPct=15.0, requireMA60Rising=True, requireAboveAllMAs=True,
                allowLowAmbush=True, allowQuietRise=False,
                moderateVolumeLower=1.2, moderateVolumeUpper=1.8, lookbackDays=120,
                minPassCount=6, requireThreeDayConfirm=True, convergenceDurationRatio=0.8,
                maRisingDays=5, requireChangePct=False, requireMA250Rising=False,
                useMA250InBullish=False, requireAboveYearLine=False,
                requireCloseAboveConvergenceTop=False, requireOpenBelowMAs=False,
                requireVolumeShrink=False, volumeBreakoutRatio=0.0),
    # 长线参数修复（2026-08-15 一年回溯实证）：
    # 原参数 minDrawdownPct=40 + requireAboveYearLine + requireMA250Rising + minPassCount=7 几乎互斥
    # （深跌40%的股票很难还站在年线上方且MA250上升）→ 一年仅 6 个信号且全部亏损。
    # 敏感性实验（V8 全放宽）信号 222 个、平均 +4.20%、胜率 49.5%、盈亏因子 2.41。
    "长线": dict(convergenceThreshold=2.5, useMA60=True, convergenceDurationDays=20,
                minDrawdownPct=10.0, requireMA60Rising=True, maRisingDays=10,
                requireMA250Rising=True, useMA250InBullish=True, requireAboveYearLine=True,
                requireVolumeShrink=True, volumeShrinkRatio=0.7, lookbackDays=250,
                minPassCount=6, requireThreeDayConfirm=True, convergenceDurationRatio=0.8,
                requireChangePct=False, requireAboveAllMAs=False,
                requireCloseAboveConvergenceTop=False, requireOpenBelowMAs=False,
                moderateVolumeLower=0.0, moderateVolumeUpper=0.0, volumeBreakoutRatio=0.0,
                allowLowAmbush=True, allowQuietRise=False),
}

# 光模块核心 + 池内相关个股
STOCKS = [
    "sz300308",  # 中际旭创
    "sz300502",  # 新易盛
    "sz300394",  # 天孚通信
    "sz002281",  # 光迅科技
    "sh600498",  # 烽火通信
    "sh688498",  # 源杰科技
    "sz300620",  # 光库科技
    "sz002475",  # 立讯精密
    "sh603236",  # 移远通信（池内）
    "sz002463",  # 沪电股份（池内 PCB）
]

INDEXES = ["sh000001", "sz399001", "sz399006"]


def main():
    print(f"===== 回测截止日: {END_DATE}（8/13 数据已排除） =====\n")

    # 1. 大盘判定
    print("── 大盘三指数（8/12 收盘 MA5/MA10/MA20 方向） ──")
    dirs = []
    for secid in INDEXES:
        name, snaps, src = fetch_kline(secid)
        if not snaps:
            print(f"  {secid}: 拉取失败")
            dirs.append("UNKNOWN")
            continue
        d = get_index_dir(name, snaps)
        dirs.append(d)
        closes = [s["close"] for s in snaps]
        ma5, ma10, ma20 = avg(closes[-5:]), avg(closes[-10:]), avg(closes[-20:])
        print(f"  {name}({secid})[{src}]: 方向={d}  MA5={ma5:.2f} MA10={ma10:.2f} MA20={ma20:.2f}  最新日={snaps[-1]['date']}")
    tv = triple_vote(dirs)
    print(f"  tripleVote = {tv}  → parseMarketRegime = {parse_market_regime(tv)}")
    print()

    # 2. 逐股回测
    print("── 光模块个股逐周期回测 ──")
    for secid in STOCKS:
        name, snaps, src = fetch_kline(secid)
        if not snaps:
            print(f"\n{secid}: 拉取失败")
            continue
        snaps[-1]["name"] = name
        print(f"\n{'='*70}\n{name}({secid})[{src}]  最新K线: {snaps[-1]['date']} 收盘={snaps[-1]['close']:.2f} "
              f"真实涨幅={snaps[-1]['changePct']:.2f}% 换手率={snaps[-1]['turnover']:.2f}%  K线数={len(snaps)}")
        for period, p in PARAMS.items():
            # 口径A：复刻 bug（changePct=换手率）；口径B：正确（changePct=f59 涨跌幅）
            res_list = []
            for label, setter in [("BUG口径", "turnover"), ("正确口径", "changePct")]:
                snaps2 = [dict(s) for s in snaps]
                for s in snaps2:
                    s["changePct"] = s[setter]
                snaps2[-1]["name"] = name
                r = analyze_snaps(snaps2, p, tv)
                r["label"] = label
                res_list.append(r)
            # 只对需要涨幅的周期区分口径；其余周期两种口径结果一致
            need_pct = p["requireChangePct"]
            if need_pct:
                for r in res_list:
                    mark = "✅ 通过" if r["passed"] else "❌ 未过"
                    fail = [k for k in r["activeChecks"] if not r["checks"][k][0]]
                    print(f"  [{period}][{r['label']}] {mark} {r['passCount']}/{r['totalChecks']} "
                          f"(粘合{r['convergenceDegree']:.2f}% 跌幅{r['drawdownPct']:.1f}% 量比{r['volumeRatio']:.2f})")
                    if not r["passed"]:
                        failstr = "; ".join(f"{k}:{r['checks'][k][1]}" for k in fail)
                        print(f"      未过项: {failstr}")
            else:
                r = res_list[0]
                mark = "✅ 通过" if r["passed"] else "❌ 未过"
                fail = [k for k in r["activeChecks"] if not r["checks"][k][0]]
                print(f"  [{period}] {mark} {r['passCount']}/{r['totalChecks']} "
                      f"(粘合{r['convergenceDegree']:.2f}% 跌幅{r['drawdownPct']:.1f}% 量比{r['volumeRatio']:.2f})")
                if not r["passed"]:
                    failstr = "; ".join(f"{k}:{r['checks'][k][1]}" for k in fail)
                    print(f"      未过项: {failstr}")


if __name__ == "__main__":
    main()
