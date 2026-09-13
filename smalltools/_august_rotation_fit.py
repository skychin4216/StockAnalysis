# -*- coding: utf-8 -*-
"""8月板块轮动拟合：验证「板块20日动量转正 → 板块内选20日动量最强个股」能否吃到农业/创新药。

诊断结论(来自 _august_diag.py)：
- 农业板块 20日涨幅: 8/7 为 -5.06% → 8/14 转正 +5.93% → 8/27 +14.45%
- 创新药板块 20日涨幅: 8/7 已 +8.42% → 8/27 +6.23%
- 个股信号滞后：农业/创新药多数个股 8/14 时 5日动量仍为负 → 现有均线粘合策略选不出

假设验证：板块动量轮动信号 = 板块20日平均涨幅 > 阈值(如 2%) 且当日板块动量转正
→ 在板块内选 20 日动量前 N 且当日放量的个股。
对比两策略收益：现有粘合策略 vs 板块轮动策略。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _pool_filters import build_sector_ret_table, sector_ret20

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
EXTRA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_aug_extra.json")

# 板块 → 代表股(手动构建模拟"轮动池",含农业/创新药/煤炭)
SECTORS = {
    "农业种植": ["sh601952", "sh600598", "sz000998", "sz002041", "sh600354", "sz002385", "sh600371", "sh600359"],
    "创新药": ["sh603259", "sh600276", "sz300122", "sz300142"],
    "煤炭": ["sh601001", "sh600985", "sh601898", "sh601666"],
}


def load_cache():
    with open(CACHE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    with open(EXTRA, "r", encoding="utf-8") as f:
        for code, ent in json.load(f).items():
            cache[code] = ent
    return cache


def mom20(cache, code, asof):
    """个股截至 asof 的 20 日动量(含当日, 用 close)"""
    snaps = [s for s in cache.get(code, {}).get("snaps", []) if s["date"] <= asof]
    if len(snaps) < 21:
        return None
    return snaps[-1]["close"] / snaps[-21]["close"] - 1


def vol_ratio(cache, code, asof):
    """当日量比(当日量 / 前5日均量)"""
    snaps = [s for s in cache.get(code, {}).get("snaps", []) if s["date"] <= asof]
    if len(snaps) < 6:
        return None
    vol_now = snaps[-1].get("volume", 0)
    avg = sum(s.get("volume", 0) for s in snaps[-6:-1]) / 5
    return vol_now / avg if avg > 0 else None


def simulate(cache, all_dates, date_to_idx, build_table=True):
    if build_table:
        build_sector_ret_table(cache)
    # 逐日扫描
    dates = [d for d in all_dates if "2026-08-01" <= d <= "2026-08-27"]
    rotation_hits = {}   # (date, sector) -> [codes]
    for asof in dates:
        for sec, codes in SECTORS.items():
            # 板块20日涨幅(用板块内代表股均值近似)
            rets = [sector_ret20(cache, all_dates, date_to_idx, c,
                                 cache.get(c, {}).get("name", ""), asof) for c in codes]
            rets = [r for r in rets if r is not None]
            if not rets:
                continue
            sec_ret = sum(rets) / len(rets)
            if sec_ret < 2.0:
                continue  # 板块动量不足
            # 板块内选 20日动量最强 top3
            ranked = []
            for c in codes:
                m = mom20(cache, c, asof)
                if m is None:
                    continue
                ranked.append((m, c, cache.get(c, {}).get("name", "?")))
            ranked.sort(reverse=True)
            for m, c, nm in ranked[:3]:
                vr = vol_ratio(cache, c, asof)
                rotation_hits.setdefault((asof, sec), []).append(
                    (c, nm, round(m * 100, 1), round(vr, 2) if vr else None))


def main():
    cache = load_cache()
    all_dates = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    build_sector_ret_table(cache)

    print("=" * 80)
    print("【板块动量轮动信号】8月逐日模拟:板块20日涨幅≥2% → 板块内20日动量Top3")
    print("=" * 80)
    for asof in [d for d in all_dates if "2026-08-01" <= d <= "2026-08-27"]:
        for sec, codes in SECTORS.items():
            rets = [sector_ret20(cache, all_dates, date_to_idx, c,
                                 cache.get(c, {}).get("name", ""), asof) for c in codes]
            rets = [r for r in rets if r is not None]
            sec_ret = sum(rets) / len(rets) if rets else 0
            if sec_ret < 2.0:
                continue
            ranked = []
            for c in codes:
                m = mom20(cache, c, asof)
                if m is not None:
                    ranked.append((m, c, cache.get(c, {}).get("name", "?")))
            ranked.sort(reverse=True)
            top3 = ", ".join(f"{nm}({m*100:+.1f}%)" for m, c, nm in ranked[:3])
            print(f"  {asof} [{sec}] 板块20日{sec_ret:+.2f}% → Top3动量: {top3}")
    print("=" * 80)
    print("结论对比(板块轮动 vs 现有均线粘合):见上方8月拟合信号与下方个股收益")


if __name__ == "__main__":
    main()
