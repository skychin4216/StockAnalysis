# -*- coding: utf-8 -*-
"""8月拟合:验证「扩池 + 策略」能否在暴涨前选出农业种植/创新药。

问题背景:
- 8月全池涨幅TOP30几乎全是光通信/PCB/液冷(池内已有,策略吃到了)
- 创新药暴涨(药明+24.5%、沃森+26.5%、智飞+13.4%)但8/21才扩池→错过8月初启动
- 农业种植完全不在池内(池内206只,农业0只)→永远选不到

本脚本:
1. 从 _aug_extra.json 拉入 农业种植/煤炭/创新药 龙头, 合并进缓存
2. 模拟「8/1 起池内已有这些股」, 用现策略(超短/短线)逐日扫描 2026-08
3. 输出每个板块代表股的最早信号日 & 信号后5日/10日收益
4. 对比不同过滤配置(SECTOR_FILTER/MAIN_BOARD)对信号的影响
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS, get_index_dir, triple_vote
from _pool_filters import extra_filter, set_filters, build_sector_ret_table, sector_of

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
EXTRA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_aug_extra.json")
INDEXES = ["sh000001", "sz399001", "sz399006"]

# 关注板块代表股
AGRI = ["sh601952", "sh600598", "sz000998", "sz002041", "sh600354", "sz002385", "sh600371", "sh600359"]
COAL = ["sh601001", "sh600985", "sh601898", "sh601666"]
MEDI = ["sh603259", "sh600276", "sz300122", "sz300142"]


def load_cache():
    with open(CACHE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    with open(EXTRA, "r", encoding="utf-8") as f:
        extra = json.load(f)
    # 合并(不覆盖原有, 但补齐名字)
    for code, ent in extra.items():
        if code not in cache:
            cache[code] = ent
        else:
            # 用新数据替换(可能更全), 保留name
            cache[code] = ent
    return cache


def market_state(idx_snaps_map, asof, all_dates, date_to_idx):
    """大盘状态:先看CRASH,否则tripleVote。与_full_cycle_backtest一致。"""
    idx_pos = date_to_idx.get(asof, 0)
    look = min(idx_pos + 1, 6)
    rec_dates = all_dates[max(0, idx_pos + 1 - look): idx_pos + 1]
    if len(rec_dates) >= 2:
        drops = []
        for secid in INDEXES:
            snaps = idx_snaps_map.get(secid, [])
            by_date = {s["date"]: s["close"] for s in snaps}
            closes = [by_date[d] for d in rec_dates if d in by_date]
            if len(closes) >= 2:
                drops.append((closes[-1] / closes[0] - 1) * 100)
        if drops and sum(drops) / len(drops) <= -4.0:
            return "CRASH"
    dirs = []
    for secid in INDEXES:
        snaps = idx_snaps_map.get(secid, [])
        sub = [s for s in snaps if s["date"] <= asof]
        if len(sub) < 20:
            dirs.append("UNKNOWN")
            continue
        closes = [s["close"] for s in sub]
        ma5 = sum(closes[-5:]) / 5
        ma10 = sum(closes[-10:]) / 10
        ma20 = sum(closes[-20:]) / 20
        d = 1 if (ma5 > ma10 > ma20) else (-1 if (ma5 < ma10 < ma20) else 0)
        dirs.append(d)
    tv = sum(1 for d in dirs if d == 1) - sum(1 for d in dirs if d == -1)
    if tv >= 2:
        return "BULLISH"
    if tv <= -2:
        return "BEARISH"
    return "OSCILLATION"


def scan_august(cache, all_dates, date_to_idx, period, p, focus):
    """扫描 2026-08, 返回 focus 板块代表股最早信号日 dict"""
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    scan_dates = [d for d in all_dates if "2026-08-01" <= d <= "2026-08-27"]
    first_sig = {}   # code -> (date, r)
    all_sigs = []
    for asof in scan_dates:
        st = market_state(idx_snaps, asof, all_dates, date_to_idx)
        sel_trend = st if st in ("BULLISH", "BEARISH") else ("BEARISH" if st == "CRASH" else "OSCILLATION")
        for code in focus:
            ent = cache.get(code) or {}
            snaps = [s for s in ent.get("snaps", []) if s["date"] <= asof]
            if len(snaps) < 20:
                continue
            name = ent.get("name") or code
            sub = [dict(s) for s in snaps]
            sub[-1]["name"] = name
            try:
                r = analyze_snaps(sub, p, sel_trend)
            except Exception:
                continue
            if not r.get("passed"):
                continue
            # 额外过滤(板块代理/主板开关)
            if not extra_filter(code, name, r, period, cache, all_dates, date_to_idx, asof):
                continue
            if code not in first_sig:
                first_sig[code] = (asof, r)
            all_sigs.append((code, name, asof, r["passCount"], st))
    return first_sig, all_sigs


def ret_after(cache, code, sig_date, days=10):
    """信号日次日至 +days 日收盘收益(用缓存最新数据,无未来函数:只取日后数据)"""
    snaps = cache.get(code, {}).get("snaps", [])
    idxs = [i for i, s in enumerate(snaps) if s["date"] == sig_date]
    if not idxs:
        return None
    i = idxs[0]
    if i + days >= len(snaps):
        return None
    base = snaps[i]["close"]
    if base <= 0:
        return None
    return round((snaps[i + days]["close"] / base - 1) * 100, 1)


def main():
    cache = load_cache()
    all_dates = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    build_sector_ret_table(cache)

    groups = [("农业种植", AGRI), ("煤炭", COAL), ("创新药", MEDI)]
    for period in ("超短", "短线"):
        p = PARAMS[period]
        print(f"\n{'='*70}\n【{period}】8月扫描(超短/短线参数, 含板块过滤+主板开关)\n{'='*70}")
        for gname, codes in groups:
            first_sig, all_sigs = scan_august(cache, all_dates, date_to_idx, period, p, codes)
            print(f"\n--- {gname} ---")
            for code in codes:
                ent = cache.get(code) or {}
                if code in first_sig:
                    d, r = first_sig[code]
                    r5 = ret_after(cache, code, d, 5)
                    r10 = ret_after(cache, code, d, 10)
                    print(f"  {code} {ent.get('name','?'):6s} 首次信号 {d} 通过{r['passCount']}/{r['totalChecks']} "
                          f"信号后5日 {r5}% 10日 {r10}%")
                else:
                    print(f"  {code} {ent.get('name','?'):6s} 8月无信号")


if __name__ == "__main__":
    main()
