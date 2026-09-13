# -*- coding: utf-8 -*-
"""临时脚本：把 hot_sector_config 新增板块龙头加入 _kline_cache.json + 公共库 market_data.db。

用于补齐 军工/机器人/低空经济/券商金融/白酒消费/传媒游戏 6 个中等以上板块。
拉取全量历史(2020-01-01 起)，之后由 _update_cache_inc.py 增量维护。
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "AutoQuant", "autoquant"))
from backtest_guangmo import fetch_east, fetch_tencent
import _market_db
from hot_sector_config import HOT_SECTOR_CONFIG

CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
END = "20260909"


def secid2key(secid):
    code, exch = secid.split(".")
    return ("sh" if exch.upper() == "SH" else "sz") + code


def main():
    cache = json.load(open(CACHE_FILE, encoding="utf-8"))
    keys = set(cache.keys())
    add = []
    for board, bv in HOT_SECTOR_CONFIG.items():
        for sub, sv in bv.get("sub_sectors", {}).items():
            for secid, meta in sv.get("leaders", {}).items():
                k = secid2key(secid)
                if k not in keys:
                    add.append((k, secid, meta["name"]))
    if not add:
        print("无新增股票")
        return
    print(f"新增 {len(add)} 只板块龙头: {[a[0] for a in add]}")
    conn = _market_db.get_conn()
    ok = 0
    for i, (k, secid, name) in enumerate(add):
        src = "east"
        try:
            n, snaps = fetch_east(k, "20200101", END)
            if not snaps:
                n, snaps = fetch_tencent(k, "20240101", END)
                src = "tencent"
        except Exception:
            n, snaps = None, []
        if not snaps:
            print(f"  [{i+1}/{len(add)}] {k} 无数据(东财/腾讯均失败)")
            continue
        display = n or name
        cache[k] = {"name": display, "snaps": snaps, "src": src}
        _market_db.upsert_kline(conn, k, snaps, src=src, name=display)
        print(f"  [{i+1}/{len(add)}] {k} {display} {len(snaps)}根 最新{snaps[-1]['date']}")
        ok += 1
        time.sleep(0.2)
    with open(CACHE_FILE + ".tmp", "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(CACHE_FILE + ".tmp", CACHE_FILE)
    print(f"完成：新增 {ok}/{len(add)} 只，池子现有 {len(cache)} 只")


if __name__ == "__main__":
    main()
