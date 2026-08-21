# -*- coding: utf-8 -*-
"""
趋势跟随策略原型：验证在 BULLISH 大盘下，超短/短线趋势跟随能否选出股票。
对比现有"均线多头粘合"（8/11-8/13 三周期均无选股）。

设计（超短/短 趋势跟随 pipeline，替代粘合选股）：
- 多头排列：MA5>MA10>MA20[>MA60]（趋势向上）
- 突破动量：收盘价距近20日最高点回落 < 一定阈值（贴近新高，强势）
- 放量：当日量 / 5日均量 ≥ threshold
- 涨幅：当日涨幅为正或突破大阳
- 大盘 BULLISH 时启用；不再要求"均线粘合"和"三日不新低"
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import get_index_dir, triple_vote, parse_market_regime

CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
INDEXES = ["sh000001", "sz399001", "sz399006"]
SCAN_DATES = ["20260811", "20260812", "20260813"]
OUT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out_trend.txt")

KLINE_CACHE = {}
with open(CACHE_FILE, "r", encoding="utf-8") as f:
    KLINE_CACHE = json.load(f)

LOG = []
def log(msg):
    LOG.append(str(msg))
    print(msg)

def filter_asof(snaps, asof):
    return [s for s in snaps if s["date"] <= asof]

def ma(closes, n):
    return sum(closes[-n:]) / n if len(closes) >= n else None

def market_dir_asof(asof):
    dirs = []
    for secid in INDEXES:
        snaps = filter_asof(KLINE_CACHE.get(secid, {}).get("snaps", []), asof)
        if len(snaps) < 20:
            dirs.append("UNKNOWN"); continue
        dirs.append(get_index_dir(None, snaps))
    return triple_vote(dirs), dirs

def trend_follow_scan(snaps, market_trend, mode):
    """趋势跟随扫描。返回 (passed, reasons)
    mode: 'ultra_short' / 'short'
    返回 passCount, totalChecks, 及各项检查明细
    """
    if len(snaps) < 20:
        return 0, 0, [], {}
    latest = snaps[-1]
    closes = [s["close"] for s in snaps]
    vols = [s["volume"] for s in snaps]
    name = latest.get("name", "")

    # 过滤 ST / *ST 股票（退市风险，不参与趋势跟随）
    if "ST" in name.upper():
        return 0, 0, [], {"filtered": "ST"}

    ma5 = ma(closes, 5); ma10 = ma(closes, 10); ma20 = ma(closes, 20)
    ma60 = ma(closes, 60)
    if mode == "ultra_short":
        mas = [ma5, ma10, ma20]
    else:
        mas = [ma5, ma10, ma20, ma60] if ma60 else [ma5, ma10, ma20]

    results = {}

    # ① 多头排列（趋势向上核心）
    if mode == "ultra_short":
        bull = ma5 > ma10 > ma20
    else:
        bull = (ma5 > ma10 > ma20 > ma60) if ma60 else (ma5 > ma10 > ma20)
    results["多头排列"] = bull

    # ② 贴近新高（突破动量）：收盘距20日高点的回撤 < 8%（强势不追高）
    hi20 = max(s["high"] for s in snaps[-20:])
    drawdown = (hi20 - latest["close"]) / hi20 * 100 if hi20 > 0 else 0
    near_high = drawdown < 8.0
    results["贴近新高"] = near_high

    # ③ 放量：当日量/5日均量
    vol5 = sum(vols[-6:-1]) / 5 if len(vols) >= 6 else vols[-1]
    vr = latest["volume"] / vol5 if vol5 > 0 else 1.0
    vol_threshold = 1.2 if mode == "ultra_short" else 1.0
    vol_ok = vr >= vol_threshold
    results["放量"] = vol_ok

    # ④ 涨幅（突破强度，须为当日真实上涨）
    chg = latest.get("changePct", 0.0)
    chg_ok = chg > 0
    results["上涨"] = chg_ok

    # ⑤ 趋势持续（MA20 向上）
    ma20_prev = ma(closes[:-1], 20)
    ma20_up = ma20_prev is not None and ma20 > ma20_prev
    results["MA20上行"] = ma20_up

    # ⑥ 站上5日线（强势）
    above_ma5 = latest["close"] > ma5
    results["站上5日线"] = above_ma5

    active = ["多头排列", "贴近新高", "放量", "上涨", "MA20上行", "站上5日线"]
    passed_count = sum(1 for k in active if results[k])
    total = len(active)

    # 超短/短通过标准：5/6 通过（核心：多头+贴近新高+放量），且当日须上涨
    is_pass = passed_count >= 5 and chg_ok
    return is_pass, passed_count, total, results, {"drawdown": drawdown, "vr": vr, "chg": chg}

def main():
    log("=" * 78)
    log("趋势跟随策略原型：BULLISH 大盘下 超短/短线 选股验证")
    log("=" * 78)
    codes = [k for k in KLINE_CACHE if not (k.startswith("sh000") or k.startswith("sz399"))]
    log(f"扫描股票池: {len(codes)} 只")

    for asof in SCAN_DATES:
        tv, dirs = market_dir_asof(asof)
        regime = parse_market_regime(tv)
        log(f"\n{'#'*78}")
        log(f"## {asof}  大盘: {tv} {dirs} → {regime}")
        log(f"{'#'*78}")

        for mode, label in [("ultra_short", "超短线"), ("short", "短线")]:
            selected = []
            near = []
            for code in codes:
                snaps = KLINE_CACHE.get(code, {}).get("snaps", [])
                sub = filter_asof(snaps, asof)
                if len(sub) < 20:
                    continue
                sub = [dict(s) for s in sub]
                sub[-1]["name"] = KLINE_CACHE.get(code, {}).get("name", code)
                try:
                    is_pass, pc, tc, res, extra = trend_follow_scan(sub, tv, mode)
                except Exception as e:
                    continue
                entry = (code, pc, tc, res, extra)
                if is_pass:
                    selected.append(entry)
                else:
                    near.append(entry)
            if selected:
                log(f"  [{label}] ✅ 选出 {len(selected)} 只:")
                for code, pc, tc, res, extra in sorted(selected, key=lambda x: -x[1]):
                    nm = KLINE_CACHE.get(code, {}).get("name", code)
                    log(f"      {nm}({code}) {pc}/{tc} "
                        f"回撤{extra['drawdown']:.1f}% 量比{extra['vr']:.2f} 涨{extra['chg']:.2f}%")
            else:
                log(f"  [{label}] ❌ 无选股")
                near_sorted = sorted(near, key=lambda x: -x[1])[:5]
                for code, pc, tc, res, extra in near_sorted:
                    fails = [k for k, v in res.items() if not v]
                    log(f"      接近: {code} {pc}/{tc} 未过:{';'.join(fails)} 回撤{extra['drawdown']:.1f}%")

    with open(OUT_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(LOG))
    print(f"\n[已写入 {OUT_FILE}]")

if __name__ == "__main__":
    try:
        main()
    except Exception:
        import traceback
        log(traceback.format_exc())
    finally:
        with open(OUT_FILE, "w", encoding="utf-8") as f:
            f.write("\n".join(LOG))
