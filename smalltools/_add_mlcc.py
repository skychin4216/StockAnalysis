# -*- coding: utf-8 -*-
"""将 MLCC 产业链标的补入 data/kline_store.json + 公共数据库（四年 K 线）。

背景（2026-09-14）：开源证券 09-14 研报《泛射频龙头，卫星、MLCC、端侧AI共驱增长》
（信维通信）+ 元件板块轮动动量 +9.5，但选股链路选不到任何 MLCC 股 —— 根因是
公共 K 线池（约 243 只，被动累积）从未覆盖 MLCC 链标的，候选池结构性缺失。
本脚本仿 _add_leaders.py 一次性补齐（同口径：东财全量，失败用腾讯分段拼接）。

用法：python _add_mlcc.py
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import fetch_east, fetch_tencent  # noqa: E402
import _market_db  # noqa: E402
from _extend_cache import merge_snaps, SEG1_END  # noqa: E402
import _kline_store

CACHE_FILE = _kline_store.store_path()
BEG, END = "20220801", "20260914"

# MLCC 产业链（上游粉体/载带 → 中游制造 → 军品/高Q → 泛射频平台）
MLCC_SECIDS = [
    ("sz300136", "信维通信"),   # 研报标的：泛射频+MLCC+卫星+端侧AI
    ("sz000636", "风华高科"),   # MLCC 龙头
    ("sz300408", "三环集团"),   # MLCC 龙头
    ("sz002859", "洁美科技"),   # MLCC 载带/离型膜
    ("sz301566", "达利凯普"),   # 高Q MLCC
    ("sh603678", "火炬电子"),   # 军品 MLCC
    ("sz300716", "鸿远电子"),   # 军品 MLCC
    ("sz002138", "顺络电子"),   # 电感等被动元件
    ("sz300285", "国瓷材料"),   # MLCC 陶瓷粉体上游
    ("sz300476", "胜宏科技"),   # PCB/元件链对照（元件板块轮动龙头）
]


def fetch_full(secid, beg=BEG, end=END):
    """东财一次全量；失败用腾讯分段拼接。返回 (name, snaps, src)。"""
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
    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    missing = [(s, n) for s, n in MLCC_SECIDS if s not in cache]
    print("MLCC 链 %d 只 | 已在库 %d | 需补齐 %d" % (
        len(MLCC_SECIDS), len(MLCC_SECIDS) - len(missing), len(missing)))
    if not missing:
        print("无需补齐。")
        return
    conn = _market_db.get_conn()
    fail = []
    for i, (secid, want_name) in enumerate(missing):
        name, snaps, src = None, [], "none"
        for attempt in range(3):  # 限频偶发失败重试（间隔递增）
            try:
                name, snaps, src = fetch_full(secid)
            except Exception:
                name, snaps, src = None, [], "err"
            if snaps:
                break
            time.sleep(1.5 * (attempt + 1))
        if not snaps:
            fail.append(secid)
            print("  [%d/%d] %s %s: 拉取失败(重试3次)" % (i + 1, len(missing), secid, want_name))
            continue
        cache[secid] = {"name": name or want_name, "snaps": snaps, "src": src}
        _market_db.upsert_kline(conn, secid, snaps, src=src, name=name or want_name)
        print("  [%d/%d] %s %s 拉取 %d 根 (%s ~ %s)" % (
            i + 1, len(missing), secid, cache[secid]["name"], len(snaps),
            snaps[0]["date"], snaps[-1]["date"]))
        time.sleep(0.25)
    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(tmp, CACHE_FILE)
    covered = sum(1 for s, _ in MLCC_SECIDS if s in cache)
    print("\n完成。失败 %d 只: %s" % (len(fail), fail[:10]))
    print("MLCC 链覆盖: %d/%d | 库内标的总数: %d" % (covered, len(MLCC_SECIDS), len(cache)))
    print("K线统计:", _market_db.kline_stats(conn))
    conn.close()


if __name__ == "__main__":
    main()
