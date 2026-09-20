# -*- coding: utf-8 -*-
"""大盘/环境层（2026-09-19 新增）——只做「环境判定」，不改原有选股规则。

依据用户选股思路文档：
  · 《主力資金動向判斷》：业界标准四因子 MFI(14) / CMF(20) / A-D 背离 / 主力净流入趋势；
    **A/D 背离**是识别主力吸筹最可靠信号之一（价跌 A/D 升 = 底背离/吸筹；价涨 A/D 降
    = 顶背离/出货=诱多）。
  · 《三层分级筛选系统》：环境差时（出货警告）应「短期不再新开仓、已持仓减仓」，
    底部启动第一根放量阳线则属吸筹例外。
  · 用户口径：不同环境（战争 / 加息 / 经贸 / 季节周期 春耕—夏—秋收—冬藏）用不同闸值。

本模块输出环境标签，供 generate_orders 做**闸门松紧自适应**（尽量不动原选股链路）：
    env = {
      "regime": "BULL"|"BEAR"|"CHOP",      # 大盘 MA20/MA60 状态
      "divergence": "BOTTOM"|"TOP"|"NONE", # A/D 背离（底背离=吸筹 / 顶背离=诱多）
      "mfi": float, "cmf": float, "ad_slope": float,
      "season": "春耕"|"夏长"|"秋收"|"冬藏",
      "tags": [...],                        # 环境标签（加息/战争/经贸 等外部事件由调用方注入）
    }
"""
import datetime


def _f(v):
    try:
        return float(v or 0)
    except (TypeError, ValueError):
        return 0.0


def mfi(snaps, n=14):
    """Money Flow Index（成交量加权的 RSI）。>80 超买 / <20 超卖。"""
    if len(snaps) < n + 1:
        return None
    pos = neg = 0.0
    for i in range(len(snaps) - n, len(snaps)):
        tp = (_f(snaps[i].get("high")) + _f(snaps[i].get("low")) + _f(snaps[i].get("close"))) / 3
        tp0 = (_f(snaps[i - 1].get("high")) + _f(snaps[i - 1].get("low"))
               + _f(snaps[i - 1].get("close"))) / 3
        mf = tp * _f(snaps[i].get("volume"))
        if tp > tp0:
            pos += mf
        elif tp < tp0:
            neg += mf
    if neg <= 0:
        return 100.0 if pos > 0 else 50.0
    return 100 - 100 / (1 + pos / neg)


def cmf(snaps, n=20):
    """Chaikin Money Flow：>0.25 强势流入 / <-0.25 强势出货。"""
    if len(snaps) < n:
        return None
    num = den = 0.0
    for s in snaps[-n:]:
        h, l, c = _f(s.get("high")), _f(s.get("low")), _f(s.get("close"))
        v = _f(s.get("volume"))
        rng = h - l
        if rng <= 0:
            continue
        num += ((c - l) - (h - c)) / rng * v
        den += v
    return (num / den * 100) if den else None


def ad_series(snaps, n=60):
    """A/D 累积线（资金流量累积）。"""
    out, acc = [], 0.0
    for s in snaps[-n:]:
        h, l, c = _f(s.get("high")), _f(s.get("low")), _f(s.get("close"))
        v = _f(s.get("volume"))
        rng = h - l
        mfm = ((2 * c - h - l) / rng) if rng > 0 else 0.0
        acc += mfm * v
        out.append(acc)
    return out


def divergence(snaps, win=20):
    """A/D 背离：价跌 A/D 升 = BOTTOM(吸筹)；价涨 A/D 降 = TOP(诱多)。"""
    if len(snaps) < win + 5:
        return "NONE"
    seg = snaps[-win:]
    ad = ad_series(seg, win)
    if len(ad) < 10:
        return "NONE"
    px_chg = _f(seg[-1].get("close")) - _f(seg[0].get("close"))
    ad_chg = ad[-1] - ad[0]
    if px_chg < 0 and ad_chg > 0:
        return "BOTTOM"
    if px_chg > 0 and ad_chg < 0:
        return "TOP"
    return "NONE"


def regime(snaps):
    """大盘状态：MA20/MA60 多头= BULL / 空头= BEAR / 其余= CHOP。"""
    if len(snaps) < 60:
        return "CHOP"
    closes = [_f(s.get("close")) for s in snaps[-60:]]
    ma20 = sum(closes[-20:]) / 20
    ma60 = sum(closes) / 60
    last = closes[-1]
    if last > ma20 > ma60:
        return "BULL"
    if last < ma20 < ma60:
        return "BEAR"
    return "CHOP"


def season(d=None):
    """季节周期（用户口径：春耕/夏长/秋收/冬藏）。"""
    m = (d or datetime.date.today()).month
    if 2 <= m <= 4:
        return "春耕"
    if 5 <= m <= 7:
        return "夏长"
    if 8 <= m <= 10:
        return "秋收"
    return "冬藏"


def env_of(snaps, tags=None):
    """综合环境标签（snaps = 大盘指数日K，如 sh000001）。"""
    if not snaps:
        return {"regime": "CHOP", "divergence": "NONE", "season": season(),
                "tags": list(tags or [])}
    m, c = mfi(snaps), cmf(snaps)
    return {
        "regime": regime(snaps),
        "divergence": divergence(snaps),
        "mfi": round(m, 1) if m is not None else None,
        "cmf": round(c, 3) if c is not None else None,
        "season": season(),
        "tags": list(tags or []),
    }


def gate_adjust(env, max_pos60=None, bypass=None):
    """环境 → 闸门松紧（只在环境层做加减，不动原选股规则）。

    规则（保守优先，避免推翻历史高胜率配置）：
      · 顶背离/诱多（TOP）→ 收紧：距60日高更严（-12 或更严），且不放开口诀拦截；
      · 底背离（BOTTOM）+ 非熊市 → 放宽：距60日高放宽到 -3（允许跟启动）；
      · 熊市（BEAR）→ 收紧一档（更严 3 个点），且不放开口诀；
      · 其余维持原配置。
    返回 (max_pos60, bypass_set)。
    """
    bp = set(bypass or ())
    mp = max_pos60
    d = (env or {}).get("divergence")
    rg = (env or {}).get("regime")
    if d == "TOP":
        mp = min(mp, -12.0) if mp is not None else -12.0
        bp.discard("idiom")
    elif rg == "BEAR":
        mp = (mp - 3.0) if mp is not None else -8.0
        bp.discard("idiom")
    elif d == "BOTTOM":
        mp = max(mp, -3.0) if mp is not None else None
    return mp, bp
