# -*- coding: utf-8 -*-
"""8月拟合诊断:农业/创新药/煤炭 走势形态 + 信号失败原因分析。

目标:理解为什么「扩池 + 现有均线粘合策略」依然选不出轮动板块。
输出:
1. 每只代表股 8月日线(阶段划分:8月初/中/下)
2. 关键信号日 analyze_snaps 各检查项通过情况
3. 板块代理过滤(sector_ret20)对信号的影响
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS, get_index_dir, triple_vote
from _pool_filters import extra_filter, set_filters, build_sector_ret_table, sector_ret20

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
EXTRA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_aug_extra.json")

AGRI = ["sh601952", "sh600598", "sz000998", "sz002041", "sh600354", "sz002385"]
COAL = ["sh601001", "sh600985", "sh601898", "sh601666"]
MEDI = ["sh603259", "sh600276", "sz300122", "sz300142"]


def load_cache():
    with open(CACHE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    with open(EXTRA, "r", encoding="utf-8") as f:
        for code, ent in json.load(f).items():
            cache[code] = ent
    return cache


def phase_stats(snaps):
    """分三段统计8月: 8/1-8/10, 8/11-8/20, 8/21-8/27"""
    segs = [("8月初", "2026-08-01", "2026-08-10"),
            ("8月中", "2026-08-11", "2026-08-20"),
            ("8月下", "2026-08-21", "2026-08-27")]
    out = []
    for label, a, b in segs:
        sub = [s for s in snaps if a <= s["date"] <= b]
        if not sub:
            out.append(f"{label}:无数据")
            continue
        c0, c1 = sub[0]["close"], sub[-1]["close"]
        peak = max(s["high"] for s in sub)
        low = min(s["low"] for s in sub)
        chg = (c1 / c0 - 1) * 100
        m = max((s["high"] - s["close"]) / s["close"] * 100 for s in sub)
        out.append(f"{label}:{chg:+.1f}% (峰{peak:.2f} 低{low:.2f} 日内最大冲高{m:.1f}%)")
    return "  ".join(out)


def main():
    cache = load_cache()
    all_dates = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    build_sector_ret_table(cache)

    for gname, codes in [("农业种植", AGRI), ("煤炭", COAL), ("创新药", MEDI)]:
        print(f"\n{'='*78}\n【{gname}】8月走势与策略信号诊断\n{'='*78}")
        for code in codes:
            ent = cache.get(code) or {}
            snaps = ent.get("snaps", [])
            name = ent.get("name") or code
            aug = [s for s in snaps if s["date"] >= "2026-08-01"]
            if not aug:
                print(f"  {code} {name}: 无8月数据")
                continue
            print(f"\n  {code} {name} ({len(aug)}根)")
            print(f"    走势: {phase_stats(snaps)}")
            # 整个8月最高点日期(确认暴涨发生时间)
            peak = max(aug, key=lambda s: s["high"])
            print(f"    8月最高: {peak['date']} {peak['high']:.2f} 收盘{peak['close']:.2f}")
            # 8月中旬(8/15)的5/10/20日动量
            ref = "2026-08-14"
            ref_snaps = [s for s in snaps if s["date"] <= ref]
            if len(ref_snaps) >= 20:
                c = [s["close"] for s in ref_snaps]
                c5 = c[-1] / c[-6] - 1
                c10 = c[-1] / c[-11] - 1
                c20 = c[-1] / c[-21] - 1
                print(f"    8/14动量: 5日{c5*100:+.1f}% 10日{c10*100:+.1f}% 20日{c20*100:+.1f}%")
            # 板块代理过滤:8月中下旬各日板块20日平均涨幅
            for d in ("2026-08-07", "2026-08-14", "2026-08-21", "2026-08-27"):
                sr = sector_ret20(cache, all_dates, date_to_idx, code, name, d)
                print(f"    板块20日涨幅[{d}]: {sr if sr is None else round(sr,2)}%")


if __name__ == "__main__":
    main()
