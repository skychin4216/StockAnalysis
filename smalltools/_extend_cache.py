# -*- coding: utf-8 -*-
"""扩展 _kline_cache.json 历史到 2023-01-01（长线回溯需要 250 根日K计算 MA250/年线）。
仅刷新 snaps，保留原有 name 字段。东财优先（全量），腾讯兜底（640 根上限）。
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import fetch_east, fetch_tencent

CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
BEG, END = "20220801", "20260813"
# 腾讯单次 640 根上限，4 年需分两段拼接；东财接口当前不可用则直接走腾讯
SEG1_END = "20240601"


def merge_snaps(snaps_list):
    merged = {}
    for snaps in snaps_list:
        for s in snaps or []:
            merged[s["date"]] = s
    return sorted(merged.values(), key=lambda x: x["date"])


def fetch_long(secid):
    """腾讯分段拉取拼接（单次 640 根上限）；东财可用时优先（全量）"""
    name, snaps = fetch_east(secid, BEG, END)
    if snaps:
        return name, snaps, "east"
    name1, seg1 = fetch_tencent(secid, BEG, SEG1_END)
    name2, seg2 = fetch_tencent(secid, SEG1_END, END)
    snaps = merge_snaps([seg1, seg2])
    if snaps:
        return name1 or name2 or "", snaps, "tencent"
    return None, [], "none"


def main():
    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    codes = list(cache.keys())
    print(f"扩展 {len(codes)} 只标的 K线至 {BEG} 起…")

    fail = []
    for i, secid in enumerate(codes):
        try:
            name, snaps, src = fetch_long(secid)
        except Exception:
            name, snaps, src = None, [], "err"
        if not snaps:
            fail.append(secid)
            print(f"  [{i+1}/{len(codes)}] {secid}: 拉取失败")
            continue
        if name:
            cache[secid]["name"] = name
        cache[secid]["snaps"] = snaps
        cache[secid]["src"] = src
        if (i + 1) % 10 == 0:
            print(f"  [{i+1}/{len(codes)}] 完成，最新 {snaps[-1]['date']} ({len(snaps)}根)")
        time.sleep(0.25)

    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(tmp, CACHE_FILE)

    ds = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    print(f"\n完成。失败 {len(fail)} 只: {fail[:10]}")
    print(f"新日期范围: {ds[0]} ~ {ds[-1]} 共 {len(ds)} 交易日")


if __name__ == "__main__":
    main()
