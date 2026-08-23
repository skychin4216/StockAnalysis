# -*- coding: utf-8 -*-
"""《选股思路》文档策略综合验证（超短/短线/中线，仅用现有日K+量数据）。

把参考文档中「可用日线量化」的维度全部落地，宽松收集候选（带全特征），
再按 策略 × 卖出规则 扫描胜率，找 90~98% 目标的可落地组合。

文档 → 因子 映射：
1. 均线多头粘合选股.md  → 粘合度=MA线相对散度、多头排列、粘合持续天数、放量突破确认、60日线方向
2. 主力資金動向判斷.txt  → MFI(资金流量指数)、CMF(蔡金资金流)、A/D线(Accumulation/Distribution)及背离
3. 振蕩期熱門板塊龍頭低吸.txt → 热门板块内深度跌透(距20日高≥X%)+缩量企稳+连跌2-3天
4. 常见K线图.txt → 早晨之星、看涨吞没反转组合（超短/短线）
5. 全流程闭环/全周期V2.0 → 大盘环境过滤（BULLISH/OSCILLATION/BEARISH/CRASH）
6. 涨停基因（前期强势确认，沿用 _pullback_strategy 已验证维度）

无未来函数：特征只看 asof 当日及之前；买入=次日开盘价。
"""
import argparse
import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _full_cycle_backtest import load_cache, market_state  # noqa: E402
from _pool_filters import build_sector_ret_table, sector_ret20  # noqa: E402
import _pool_filters as _pf  # noqa: E402

INDEXES = ["sh000001", "sz399001", "sz399006"]
NON_MAIN = ("sh688", "sh689", "sz300", "sz301", "bj8", "sh51", "sh56", "sz15", "sz16")
# 涨停阈值：主板 9.5%，创业板/科创板 19.5%（只影响涨停基因与A-D口径，保守用 9.5 统一）
ZT = 9.5
GOOD_ENV = {"BULLISH", "OSCILLATION"}
BAD_ENV = {"BEARISH", "CRASH"}


# ── 技术指标库（全部只用 asof 当日及之前数据） ──────────────────────────

def sma(vals, end, n):
    if end + 1 < n:
        return None
    return sum(vals[end + 1 - n:end + 1]) / n


def mfi(highs, lows, closes, vols, end, n=14):
    """资金流量指数 MFI = 100 - 100/(1+MFR)，MFR=正资金流和/负资金流和。
    返回 0~100 或 None（数据不足/无负流）。"""
    if end + 1 < n + 1:
        return None
    pos = neg = 0.0
    for i in range(end + 1 - n, end + 1):
        tp = (highs[i] + lows[i] + closes[i]) / 3.0
        if i == end + 1 - n:
            prev_tp = (highs[i - 1] + lows[i - 1] + closes[i - 1]) / 3.0
            if tp > prev_tp:
                pos += tp * vols[i]
            elif tp < prev_tp:
                neg += tp * vols[i]
            continue
        prev_tp = (highs[i - 1] + lows[i - 1] + closes[i - 1]) / 3.0
        if tp > prev_tp:
            pos += tp * vols[i]
        elif tp < prev_tp:
            neg += tp * vols[i]
    if neg <= 0:
        return None if pos <= 0 else 100.0
    return 100.0 - 100.0 / (1.0 + pos / neg)


def cmf(highs, lows, closes, vols, end, n=20):
    """蔡金资金流向 CMF = Σ(MFM×VOL)/Σ(VOL)×100。返回 -100~100 或 None。"""
    if end + 1 < n:
        return None
    mfm_sum = 0.0
    vol_sum = 0.0
    for i in range(end + 1 - n, end + 1):
        hi, lo, cl, vo = highs[i], lows[i], closes[i], vols[i]
        if hi == lo or vo <= 0:
            continue
        mfm = ((cl - lo) - (hi - cl)) / (hi - lo)
        mfm_sum += mfm * vo
        vol_sum += vo
    if vol_sum <= 0:
        return None
    return mfm_sum / vol_sum * 100.0


def ad_line(highs, lows, closes, vols, end, n=20):
    """A/D线：逐日 A/D = ((C-L)-(H-C))/(H-L)×V 累积。返回最近 n 日累积值（绝对值，无量纲）。
    A/D 方向比绝对值重要：横盘/下跌中 A/D 上升 = 吸筹。"""
    ad = 0.0
    for i in range(max(0, end + 1 - n), end + 1):
        hi, lo, cl, vo = highs[i], lows[i], closes[i], vols[i]
        if hi == lo or vo <= 0:
            continue
        ad += ((cl - lo) - (hi - cl)) / (hi - lo) * vo
    return ad


def kline_patterns(opens, highs, lows, closes, end):
    """K线反转组合（超短/短线用）：
    morning_star 早晨之星（前大阴→小实体星→今大阳，两阳夹星，今收>前前收）
    bullish_engulf 看涨吞没（今阳包前阴，实体完全覆盖）
    hammer 锤子线（长下影≥2×实体，位于近期低位）
    返回 dict(布尔)。"""
    if end < 2:
        return dict(morning_star=False, bullish_engulf=False, hammer=False)
    o1, h1, l1, c1 = opens[end - 2], highs[end - 2], lows[end - 2], closes[end - 2]
    o2, h2, l2, c2 = opens[end - 1], highs[end - 1], lows[end - 1], closes[end - 1]
    o3, h3, l3, c3 = opens[end], highs[end], lows[end], closes[end]
    body1 = abs(c1 - o1)
    body3 = abs(c3 - o3)
    rng3 = (h3 - l3) if h3 > l3 else 1e-9
    # 早晨之星
    ms = (c1 < o1 and body1 > 0 and body3 > 0 and
          c3 > o3 and c3 > c1 and
          abs(c2 - o2) <= body1 * 0.3 and
          l2 > min(l1, l3))
    # 看涨吞没
    eng = (c1 < o1 and c3 > o3 and
           o3 <= c1 and c3 >= o1 and
           body3 > body1)
    # 锤子线（下影≥2×实体，且当日收阳或收平，位于近10日低位区）
    lower_shadow = min(o3, c3) - l3
    hammer = (lower_shadow >= 2 * body3 and c3 >= o3 and
              closes[end] <= max(closes[max(0, end - 9):end + 1]) * 0.99)
    return dict(morning_star=ms, bullish_engulf=eng, hammer=hammer)


def collect_one(A, end, p, regime, sector20, is_main):
    """宽松收集单日候选。A=(opens,highs,lows,closes,vols,chgs)。
    返回全特征 dict 或 None。不在此处做强过滤——所有条件留给网格重放。"""
    if end < 80 or regime == "CRASH":
        return None
    opens, highs, lows, closes, vols, chgs = A
    c0 = closes[end]
    ma5 = sma(closes, end, 5)
    ma10 = sma(closes, end, 10)
    ma20 = sma(closes, end, 20)
    ma60 = sma(closes, end, 60)
    ma60_prev = sma(closes, end - 1, 60)
    if None in (ma5, ma10, ma20, ma60):
        return None

    # 均线散度（粘合度）：三线/四线相对 MA20 的最大偏离%
    spread3 = max(abs(ma5 - ma20), abs(ma10 - ma20)) / ma20 * 100.0
    spread4 = max(abs(ma5 - ma20), abs(ma10 - ma20), abs(ma60 - ma20)) / ma20 * 100.0
    bullish5 = ma5 > ma10
    bullish3 = ma5 > ma10 > ma20
    bullish4 = ma5 > ma10 > ma20 > ma60
    ma60_up = ma60 > ma60_prev

    # 粘合持续天数：三线最大偏离≤阈值的天数（倒推）
    stick_days = 0
    for i in range(end, max(end - 40, -1), -1):
        if i < 4:
            break
        m5 = sum(closes[i - 4:i + 1]) / 5.0
        m10 = sum(closes[i - 9:i + 1]) / 10.0
        m20 = sum(closes[i - 19:i + 1]) / 20.0
        sp = max(abs(m5 - m20), abs(m10 - m20)) / m20 * 100.0
        if sp <= 4.0:
            stick_days += 1
        else:
            break

    # 距20日/60日高点回踩幅度
    hi20 = max(highs[end - 19:end + 1])
    hi60 = max(highs[max(0, end - 59):end + 1])
    dd20 = (c0 / hi20 - 1) * 100.0
    dd60 = (c0 / hi60 - 1) * 100.0

    # 量比（今日量/近5日均量）
    vol5 = sum(vols[end - 5:end]) / 5.0 if end >= 5 else 0.0
    vr = vols[end] / vol5 if vol5 > 0 else 1.0

    # 连跌天数（截至今日，含今日为阴线）
    streak_dn = 0
    for i in range(end, -1, -1):
        if chgs[i] < 0:
            streak_dn += 1
        else:
            break

    # 前期强势（涨停基因：近5/10/20日涨停次数）
    zt5 = sum(1 for c in chgs[max(0, end - 4):end + 1] if c >= ZT)
    zt10 = sum(1 for c in chgs[max(0, end - 9):end + 1] if c >= ZT)
    zt20 = sum(1 for c in chgs[max(0, end - 19):end + 1] if c >= ZT)

    # 资金流因子
    mf = mfi(highs, lows, closes, vols, end, 14)
    cf = cmf(highs, lows, closes, vols, end, 20)
    ad5 = ad_line(highs, lows, closes, vols, end, 5)
    ad20 = ad_line(highs, lows, closes, vols, end, 20)
    ad_prev5 = ad_line(highs, lows, closes, vols, end - 5, 5)
    # A-D 背离线：价格创近期新低但资金流未创新低（吸筹）—— 用 AD 斜率 vs 价格斜率
    ad_rising = ad5 > ad_prev5 if ad_prev5 is not None else False
    price_falling = chgs[end] < 0 or dd20 < -8.0

    # K线反转
    pat = kline_patterns(opens, highs, lows, closes, end)

    return dict(
        ma5=ma5, ma10=ma10, ma20=ma20, ma60=ma60,
        spread3=spread3, spread4=spread4,
        bull5=bullish5, bull3=bullish3, bull4=bullish4, ma60_up=ma60_up,
        stick=stick_days, dd20=dd20, dd60=dd60,
        vr=vr, chg=chgs[end], streak_dn=streak_dn,
        zt5=zt5, zt10=zt10, zt20=zt20,
        mfi=mf, cmf=cf, ad5=ad5, ad20=ad20, ad_rising=ad_rising, price_falling=price_falling,
        ms=pat["morning_star"], eng=pat["bullish_engulf"], hammer=pat["hammer"],
        sector20=sector20, regime=regime,
    )


# ── 策略定义：每策略一个筛选函数，输入候选特征 dict，输出 bool ─────────

def _s1_ma_break(f):
    """均线多头粘合+放量突破：多头排列 + 粘合度低 + 今日放量上攻。"""
    if not (f["bull3"] and f["ma60_up"]):
        return False
    if f["spread3"] > 4.0:
        return False
    if f["stick"] < 5:
        return False
    if f["vr"] < 1.5 or f["chg"] < 1.0:
        return False
    return True


def _s1b_ma_break_fast(f):
    """均线粘合突破·超短收紧版：三线粘合≤3%、粘合≥3天、放量≥2倍、涨≥2%。"""
    if not (f["bull3"] and f["ma60_up"]):
        return False
    if f["spread3"] > 3.0 or f["stick"] < 3:
        return False
    if f["vr"] < 2.0 or f["chg"] < 2.0:
        return False
    return True


def _s2_mfi_strong(f):
    """资金流强吸筹：MFI 超买区(>70 仍强) 或 中高位 + CMF 为正（资金持续流入）。"""
    if f["mfi"] is None or f["cmf"] is None:
        return False
    if f["cmf"] <= 0:
        return False
    if f["mfi"] < 55:
        return False
    return True


def _s2b_ad_divergence(f):
    """A-D 背离线：价格下跌/深回踩，但短线资金流仍在流入（吸筹背离）。"""
    if not f["ad_rising"]:
        return False
    if not f["price_falling"]:
        return False
    if f["dd20"] < -6.0 or f["chg"] < -1.0:
        return True
    return False


def _s3_leader_pullback(f):
    """震荡期热门板块龙头低吸：深跌透(距20日高≥15%) + 缩量企稳 + 连跌后止跌。"""
    if f["regime"] != "OSCILLATION":
        return False
    if f["dd20"] > -15.0:
        return False
    if f["vr"] > 0.8:
        return False
    if f["streak_dn"] < 2:
        return False
    # 今日止跌（未继续大跌）且企稳于 5 日线附近
    if f["chg"] < -3.0:
        return False
    if abs(f["close0"] - f["ma5"]) / f["ma5"] > 4.0:
        return False
    return True


def _s4_kline_reversal(f):
    """K线反转组合：早晨之星 / 看涨吞没 / 锤子线，且不在下跌趋势中。"""
    if not (f["ms"] or f["eng"] or f["hammer"]):
        return False
    if not f["ma60_up"]:
        return False
    if f["chg"] < 0.5:
        return False
    return True


def _s5_mid_deep_pullback(f):
    """中线深度回踩+资金流：60日线向上趋势中的深回踩(距20日高≥12%)+缩量企稳+CMF为正。"""
    if not f["ma60_up"]:
        return False
    if f["dd20"] > -12.0:
        return False
    if f["vr"] > 0.9:
        return False
    if f["cmf"] is None or f["cmf"] <= 0:
        return False
    if f["chg"] < -4.0:
        return False
    return True


ENVS = ["BULLISH", "OSCILLATION", "BEARISH"]  # CRASH 已在 collect_one 排除


def _s1_tunable(f, spread_max=3.0, stick_min=3, vol_min=2.0, chg_min=2.0, zt_key="zt20", zt_min=1):
    """S1 参数化版：均线多头粘合+放量突破+涨停基因，阈值全部可调。"""
    if not (f["bull3"] and f["ma60_up"]):
        return False
    if f["spread3"] > spread_max or f["stick"] < stick_min:
        return False
    if f["vr"] < vol_min or f["chg"] < chg_min:
        return False
    if zt_key and f.get(zt_key, 0) < zt_min:
        return False
    return True


STRATEGIES = {
    "S1均线粘合突破(超短)": dict(fn=_s1b_ma_break_fast, periods={"超短"}),
    "S1均线粘合突破(短线)": dict(fn=_s1_ma_break, periods={"短线"}),
    "S2资金流MFI+CMF":     dict(fn=_s2_mfi_strong, periods={"超短", "短线"}),
    "S2bA-D吸筹背离":      dict(fn=_s2b_ad_divergence, periods={"超短", "短线"}),
    "S3龙头深跌低吸(震荡)": dict(fn=_s3_leader_pullback, periods={"短线"}),
    "S4K线反转(趋势内)":   dict(fn=_s4_kline_reversal, periods={"超短", "短线"}),
    "S5中线深回踩+资金流":  dict(fn=_s5_mid_deep_pullback, periods={"中线"}),
}
def apply_combo(f, base_ok, combo_s2, combo_zt):
    if not base_ok:
        return False
    if combo_s2:
        if f["mfi"] is None or f["cmf"] is None:
            return False
        if not (f["cmf"] > 0 and f["mfi"] >= 50):
            return False
    if combo_zt:
        if f["zt20"] < 1:
            return False
    return True


# ── 模拟交易：买入=信号次日开盘，止盈/止损/到期 ─────────────────────────
# 性能：每个候选预计算持有期逐日路径（hi/lo/close 相对入场%），
# 所有卖出组合用 O(hold) 查路径推导，避免逐组合重复模拟。

MAX_HOLD_ALL = 20  # 三个周期最大持有天数上限（预计算路径长度）


def precompute_path(cache, all_dates, f):
    """返回 [(hi%, lo%, close%), ...] 第k元素=持有第k天，长度≤MAX_HOLD_ALL。
    无后续数据/无法买入返回 None。"""
    buy_idx = f["idx"]
    if buy_idx >= len(all_dates):
        return None
    snaps = cache.get(f["code"], {}).get("snaps") or []
    by_date = {s["date"]: s for s in snaps}
    bs = by_date.get(all_dates[buy_idx])
    if not bs or bs["open"] <= 0:
        return None
    entry = bs["open"]
    path = []
    for k in range(1, MAX_HOLD_ALL + 1):
        di = buy_idx + k
        if di >= len(all_dates):
            break
        day = by_date.get(all_dates[di])
        if not day:
            continue
        hi = day.get("high", day["close"])
        lo = day.get("low", day["close"])
        path.append(((hi / entry - 1) * 100.0,
                     (lo / entry - 1) * 100.0,
                     (day["close"] / entry - 1) * 100.0))
    return path or None


def sim_path(path, tp, sl, max_hold):
    """从预计算路径推导 (tp, sl, hold) 的收益%。逻辑与逐日模拟完全一致：
    盘中触及 +tp% 止盈（优先）或 -sl% 止损；到期未触发按最后一日收盘卖。"""
    last = None
    for k in range(min(max_hold, len(path))):
        hi, lo, cl = path[k]
        last = cl
        if tp > 0 and hi >= tp:
            return tp
        if sl < 0 and lo <= sl:
            return sl
    return last if last is not None else None


# 卖出规则网格（每周期）
SELL_GRID = {
    "超短": dict(tp=[0.8, 1.2, 1.5, 2.0], sl=[-1.5, -2.0, -3.0], hold=[1, 2, 3]),
    "短线": dict(tp=[2.0, 3.0, 4.0, 6.0], sl=[-2.0, -3.0, -4.0], hold=[3, 5, 8]),
    "中线": dict(tp=[5.0, 8.0, 12.0], sl=[-4.0, -6.0, -8.0], hold=[10, 20]),
}


def collect(cache, all_dates, date_to_idx, period, w_start, w_end):
    """按股票外层遍历，宽松收集全特征候选。"""
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    st_by_date = {d: market_state(idx_snaps, d, all_dates, date_to_idx)
                  for d in all_dates if w_start <= d < w_end}
    rows = []
    for code, ent in cache.items():
        if code.startswith(("sh000", "sz399")):
            continue
        name = ent.get("name") or code
        if "ST" in (name or "").upper():
            continue
        snaps = ent.get("snaps") or []
        if len(snaps) < 90:
            continue
        opens = [s["open"] for s in snaps]
        highs = [s["high"] for s in snaps]
        lows = [s["low"] for s in snaps]
        closes = [s["close"] for s in snaps]
        vols = [s["volume"] for s in snaps]
        chgs = [s.get("changePct", 0.0) for s in snaps]
        A = (opens, highs, lows, closes, vols, chgs)
        for end in range(len(snaps)):
            asof = snaps[end]["date"]
            if asof >= w_end:
                break
            if asof < w_start:
                continue
            st = st_by_date[asof]
            r20 = sector_ret20(cache, all_dates, date_to_idx, code, name, asof)
            f = collect_one(A, end, None, st, r20, True)
            if f is None:
                continue
            f.update(code=code, name=name, asof=asof, idx=date_to_idx.get(asof, 0) + 1,
                     regime=st, close0=closes[end])
            f["main_board"] = not code.startswith(NON_MAIN)
            rows.append(f)
    return rows


def build_paths(cache, all_dates, rows):
    """一次性预计算所有候选的持有期路径，返回与 rows 平行的列表。"""
    return [precompute_path(cache, all_dates, f) for f in rows]


def evaluate(paths, period, main_on, sell, strat_fn=None, combo_s2=False, combo_zt=False, rows=None, env=None):
    """paths 为候选路径列表（None=无法买入）。按卖出组合查路径推导收益。
    env 可选：只统计信号日大盘状态=env 的候选（环境感知分层）。"""
    tp, sl, hold = sell
    rets = []
    for i, f in enumerate(rows or []):
        if env and f["regime"] != env:
            continue
        if main_on and not f["main_board"]:
            continue
        if strat_fn is not None:
            ok = strat_fn(f)
            if not apply_combo(f, ok, combo_s2, combo_zt):
                continue
        p = paths[i]
        if p is None:
            continue
        r = sim_path(p, tp, sl, hold)
        if r is not None:
            rets.append(r)
    n = len(rets)
    if n == 0:
        return 0, 0.0, 0.0, 0.0
    wr = 100.0 * len([r for r in rets if r > 0]) / n
    avg = sum(rets) / n
    return n, wr, avg, sum(rets)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--months", type=int, default=12)
    ap.add_argument("--periods", type=str, default="超短,短线,中线")
    args = ap.parse_args()

    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    ed = date.fromisoformat(all_dates[-1])
    y, m = ed.year, ed.month - args.months
    y += (m - 1) // 12
    m = (m - 1) % 12 + 1
    w_start = date(y, m, ed.day).isoformat() if ed.day <= 28 else date(y, m, 1).isoformat()
    w_end = all_dates[-1]
    print(f"文档策略综合验证 | 窗口 {w_start} ~ {w_end}")

    build_sector_ret_table(cache, 20)
    print("板块涨幅表已构建：%d 个板块" % len(_pf._SECTOR_TABLE["data"]))

    periods = [x for x in ("超短", "短线", "中线") if x in args.periods]
    for period in periods:
        rows = collect(cache, all_dates, date_to_idx, period, w_start, w_end)
        paths = build_paths(cache, all_dates, rows)
        print("\n" + "=" * 70)
        print("[%s] 宽松候选 n=%d (有效路径 %d)" % (period, len(rows), sum(1 for p in paths if p)))
        # 收集基线对照
        for main_on in (True, False):
            n, wr, avg, cum = evaluate(paths, period, main_on,
                                       (SELL_GRID[period]["tp"][1], SELL_GRID[period]["sl"][1], SELL_GRID[period]["hold"][0]),
                                       rows=rows)
            print("  基线(全部候选, tp/sl=中档) 主板=%s → n=%-4d wr=%5.1f%% avg=%+5.2f%% cum=%+7.1f%%"
                  % ("开" if main_on else "关", n, wr, avg, cum))

        # 各策略扫描：按大盘环境分层
        for sname, cfg in STRATEGIES.items():
            if period not in cfg["periods"]:
                continue
            for combo_tag, c2, cz in (("", False, False), ("+MFI/CMF", True, False), ("+ZT", False, True)):
                label = sname + combo_tag
                print("  ## %s ##" % label)
                env_counts = {}
                for f in rows:
                    env_counts[f["regime"]] = env_counts.get(f["regime"], 0) + 1
                print("    环境分布: %s" % env_counts)
                for env in ENVS:
                    best_pos = None     # 正期望中最优 (wr, n, avg, sell)
                    best_wr = None      # 最高胜率（n≥5，不论期望）
                    for main_on in (True, False):
                        for tp in SELL_GRID[period]["tp"]:
                            for sl in SELL_GRID[period]["sl"]:
                                for hold in SELL_GRID[period]["hold"]:
                                    n, wr, avg, cum = evaluate(
                                        paths, period, main_on,
                                        (tp, sl, hold), cfg["fn"], c2, cz, rows=rows, env=env)
                                    if n < 5:
                                        continue
                                    if avg > 0 and (best_pos is None or (wr, n) > (best_pos[0], best_pos[1])):
                                        best_pos = (wr, n, avg, (tp, sl, hold), "开" if main_on else "关")
                                    if wr >= 75 and (best_wr is None or wr > best_wr[0]):
                                        best_wr = (wr, n, avg, (tp, sl, hold), "开" if main_on else "关")
                                    tag = " <<<" if (90.0 <= wr <= 98.5 and n >= 8 and avg >= 0) else ""
                                    if tag:
                                        print("      [%s 主板=%s] tp=%+4.1f%% sl=%+4.1f%% hold=%d → n=%-4d wr=%5.1f%% avg=%+5.2f%% cum=%+7.1f%%%s"
                                              % (env, "开" if main_on else "关", tp, sl, hold, n, wr, avg, cum, tag))
                    print("    [%s] 正期望BEST: %s" % (env, best_pos))
                    print("    [%s] 最高胜率(n≥5): %s" % (env, best_wr))

        # 超短 BULLISH 细化：S1 参数网格 × 涨停基因档 × 卖出规则（验证82%稳健性）
        if period == "超短":
            print("  == 超短BULLISH细化（粘合≤X% / 粘合天≥Y / 基因档） ==")
            for env in ("BULLISH", "OSCILLATION"):
                for spread_max in (1.5, 2.0, 2.5, 3.0):
                    for stick_min in (3, 5):
                        for zt_key in ("zt20", "zt10", "zt5"):
                            def _mk(sm=spread_max, sk=stick_min, zk=zt_key):
                                return lambda f: _s1_tunable(f, spread_max=sm, stick_min=sk, zt_key=zk)
                            best = None
                            for main_on in (True, False):
                                for tp in SELL_GRID[period]["tp"]:
                                    for sl in SELL_GRID[period]["sl"]:
                                        for hold in SELL_GRID[period]["hold"]:
                                            n, wr, avg, cum = evaluate(
                                                paths, period, main_on, (tp, sl, hold),
                                                _mk(), False, False, rows=rows, env=env)
                                            if n < 15:
                                                continue
                                            if avg > 0 and (best is None or (wr, n) > (best[0], best[1])):
                                                best = (wr, n, avg, (tp, sl, hold), "开" if main_on else "关")
                            if best and best[0] >= 78:
                                print("    [%s] 粘合≤%.1f%% 粘合天≥%d 基因=%s → wr=%.1f%% n=%d avg=%+.2f%% %s 主板=%s"
                                      % (env, spread_max, stick_min, zt_key, best[0], best[1], best[2],
                                         "tp=%.1f/sl=%.1f/hold=%d" % (best[3]), best[4]))

    print("\n完成")


if __name__ == "__main__":
    main()
