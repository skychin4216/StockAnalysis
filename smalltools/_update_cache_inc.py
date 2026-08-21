# -*- coding: utf-8 -*-
"""增量更新 _kline_cache.json 到最新交易日（仅补缺失日期，保留原有数据）。"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import fetch_east, fetch_tencent

CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
BEG = "20260813"
END = "20260820"


def main():
    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    codes = list(cache.keys())
    ds = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    print(f"当前缓存末端: {ds[-1]} 共 {len(ds)} 交易日, 更新 {len(codes)} 只至 {END}")
    fail = []
    for i, secid in enumerate(codes):
        try:
            name, snaps = fetch_east(secid, BEG, END)
            src = "east"
            if not snaps:
                name, snaps = fetch_tencent(secid, BEG, END)
                src = "tencent"
        except Exception:
            name, snaps, src = None, [], "err"
        if not snaps:
            fail.append(secid)
            print(f"  [{i+1}/{len(codes)}] {secid}: 拉取失败")
            continue
        old = {s["date"]: s for s in cache[secid].get("snaps", [])}
        old.update({s["date"]: s for s in snaps})
        cache[secid]["snaps"] = sorted(old.values(), key=lambda x: x["date"])
        if name:
            cache[secid]["name"] = name
        cache[secid]["src"] = src
        if (i + 1) % 10 == 0:
            print(f"  [{i+1}/{len(codes)}] 完成, 最新 {snaps[-1]['date']} ({len(snaps)}根)")
        time.sleep(0.2)
    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(tmp, CACHE_FILE)
    nd = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    print(f"\n完成。失败 {len(fail)} 只: {fail[:10]}")
    print(f"新日期范围: {nd[0]} ~ {nd[-1]} 共 {len(nd)} 交易日")


if __name__ == "__main__":
    main()
