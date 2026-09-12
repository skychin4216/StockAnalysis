# -*- coding: utf-8 -*-
"""将 exe 龙头池（AutoQuant/autoquant/hot_sector_config）缺失的标的补入
_kline_cache.json + 公共数据库（四年 K 线）。

背景：统一数据源后，公共库需覆盖 exe 的 101 只 leaders；
_kline_cache.json 目前只覆盖其中一部分，本脚本补齐缺失标的。

用法：python _add_leaders.py
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import fetch_east, fetch_tencent  # noqa: E402
import _market_db  # noqa: E402
from _extend_cache import merge_snaps, SEG1_END  # noqa: E402

CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
BEG, END = "20220801", "20260820"


def load_leaders_secids():
    """从 AutoQuant 龙头池读取代码，转 secid 列表（找不到 AutoQuant 时返回空）。"""
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for base in (os.path.join(root, "AutoQuant"), root):
        mod = os.path.join(base, "autoquant", "hot_sector_config.py")
        if os.path.exists(mod):
            sys.path.insert(0, base)
            from autoquant.hot_sector_config import get_all_leaders  # noqa: E402
            leaders = get_all_leaders()
            secids = []
            for k in leaders.keys():
                code, sep, mkt = k.rpartition(".")
                secids.append((mkt.lower() + code) if sep else k.lower())
            return secids
    return []


def fetch_full(secid, beg=BEG, end=END):
    """东财一次全量（默认四年）；失败用腾讯分段拼接。返回 (name, snaps, src)。

    beg/end 可覆盖（如 `_add_rotation_sectors.py` 需要拉到今日最新交易日）。
    """
    name, snaps = fetch_east(secid, beg, end)
    if snaps:
        return name, snaps, "east"
    name1, seg1 = fetch_tencent(secid, beg, SEG1_END)
    name2, seg2 = fetch_tencent(secid, SEG1_END, end)
    snaps = merge_snaps([seg1, seg2])
    if snaps:
        return name1 or name2 or "", snaps, "tencent"
    return None, [], "none"


def main():
    leaders = load_leaders_secids()
    if not leaders:
        print("未找到 AutoQuant/autoquant/hot_sector_config.py，无法获取龙头池，退出")
        return
    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    missing = [s for s in leaders if s not in cache]
    print(f"龙头池 {len(leaders)} 只 | 已在库 {len(leaders) - len(missing)} | 需补齐 {len(missing)}")
    if not missing:
        print("无需补齐。")
        return
    conn = _market_db.get_conn()
    fail = []
    for i, secid in enumerate(missing):
        try:
            name, snaps, src = fetch_full(secid)
        except Exception:
            name, snaps, src = None, [], "err"
        if not snaps:
            fail.append(secid)
            print(f"  [{i+1}/{len(missing)}] {secid}: 拉取失败")
            continue
        cache[secid] = {"name": name or secid, "snaps": snaps, "src": src}
        _market_db.upsert_kline(conn, secid, snaps, src=src, name=name or secid)
        print(f"  [{i+1}/{len(missing)}] {secid} {cache[secid]['name']} 拉取 {len(snaps)} 根"
              f" ({snaps[0]['date']} ~ {snaps[-1]['date']})")
        time.sleep(0.25)
    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(tmp, CACHE_FILE)
    covered = sum(1 for s in leaders if s in cache)
    print(f"\n完成。失败 {len(fail)} 只: {fail[:10]}")
    print(f"龙头池覆盖: {covered}/{len(leaders)} | 库内标的总数: {len(cache)}")
    print("K线统计:", _market_db.kline_stats(conn))
    conn.close()


if __name__ == "__main__":
    main()
