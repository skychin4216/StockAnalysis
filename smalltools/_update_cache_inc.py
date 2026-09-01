# -*- coding: utf-8 -*-
"""增量更新 _kline_cache.json 到最新交易日（仅补缺失日期，保留原有数据）。

同步写公共数据库 StockAnalysis/data/market_data.db（_market_db），
保证 exe / smalltools 使用同一数据源。

v2: 并发拉取（默认 10 workers）——手机端 HistoricalDataFetcher 用并发 10 拉
K 线 + 批量实时接口，比电脑端原先的串行 for+time.sleep(0.2) 快一个数量级，
此处将串行改为 ThreadPoolExecutor 并发，并新增 --workers 参数。
"""
import json
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import fetch_east, fetch_tencent
import _market_db

CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
_LOCK = threading.Lock()  # 保护 SQLite 串行写（并发网络拉取 + 串行落库）


def _probe_end(beg):
    """探测最近可用交易日：优先东财，失败自动回退腾讯。从今天往前最多退 7 天。"""
    import datetime as _dt
    d = _dt.date.today()
    for _ in range(8):
        end = d.strftime("%Y%m%d")
        snaps = []
        try:
            _, snaps = fetch_east("sh000001", beg, end)
        except Exception:
            snaps = []
        if not snaps:
            try:
                _, snaps = fetch_tencent("sh000001", beg, end)
            except Exception:
                snaps = []
        if snaps:
            return end
        d -= _dt.timedelta(days=1)
    return d.strftime("%Y%m%d")


def _beg_for(end_str):
    """BEG 动态取 END 前 10 个自然日，只补缺失区间。兼容 'YYYY-MM-DD' / 'M-D' / 'YYYYMMDD'。"""
    import datetime as _dt
    parts = end_str.split("-")
    if len(parts) == 3:
        y, m, d = int(parts[0]), int(parts[1]), int(parts[2])
    elif len(parts) == 2:
        y, m, d = _dt.date.today().year, int(parts[0]), int(parts[1])
    else:
        y, m, d = int(end_str[:4]), int(end_str[4:6]), int(end_str[6:])
    beg = _dt.date(y, m, d) - _dt.timedelta(days=10)
    return beg.strftime("%Y%m%d")


def _cache_date(end_ymd):
    """YYYYMMDD -> 缓存 'YYYY-MM-DD' 格式。"""
    y, m, d = end_ymd[:4], int(end_ymd[4:6]), int(end_ymd[6:])
    return f"{y}-{m:02d}-{d:02d}"


def _fetch_one(secid, beg, end):
    """拉取单只（东财优先，失败回退腾讯）。返回 (secid, name, snaps, src) 或 (secid, None, [], 'err')。"""
    try:
        name, snaps = fetch_east(secid, beg, end)
        src = "east"
        if not snaps:
            name, snaps = fetch_tencent(secid, beg, end)
            src = "tencent"
        return secid, name, snaps, src
    except Exception:
        return secid, None, [], "err"


def main():
    import argparse
    ap = argparse.ArgumentParser(description="并发增量更新日线缓存")
    ap.add_argument("--workers", type=int, default=10, help="并发数（默认 10）")
    ap.add_argument("--no-db", action="store_true", help="不写公共数据库（仅缓存 JSON）")
    args = ap.parse_args()

    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    conn = _market_db.get_conn() if not args.no_db else None
    codes = list(cache.keys())
    ds = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    end0 = _probe_end(_beg_for(ds[-1]))
    # 端日期必须不早于当前缓存末端，否则无新数据可拉
    if _cache_date(end0) <= ds[-1]:
        print(f"缓存已是最新({ds[-1]}), 无需更新")
        if conn:
            _market_db.set_meta(conn, "latest_date", ds[-1])
            conn.close()
        return
    BEG = _beg_for(end0)
    END = end0
    print(f"当前缓存末端: {ds[-1]} 共 {len(ds)} 交易日, 更新 {len(codes)} 只至 {END} (workers={args.workers})")
    fail = []
    done = 0
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(_fetch_one, c, BEG, END): c for c in codes}
        for fut in as_completed(futures):
            secid = futures[fut]
            try:
                secid, name, snaps, src = fut.result()
            except Exception:
                secid, name, snaps, src = secid, None, [], "err"
            if not snaps:
                fail.append(secid)
                with _LOCK:
                    print(f"  {secid}: 拉取失败")
                continue
            old = {s["date"]: s for s in cache[secid].get("snaps", [])}
            old.update({s["date"]: s for s in snaps})
            cache[secid]["snaps"] = sorted(old.values(), key=lambda x: x["date"])
            if name:
                cache[secid]["name"] = name
            cache[secid]["src"] = src
            # 同步写公共数据库（锁内串行）
            if conn:
                with _LOCK:
                    _market_db.upsert_kline(conn, secid, snaps, src=src,
                                            name=name or cache[secid].get("name"))
            done += 1
            if done % 20 == 0 or done == len(codes):
                with _LOCK:
                    print(f"  [{done}/{len(codes)}] 完成, 最新 {snaps[-1]['date']} ({len(snaps)}根) "
                          f"耗时 {time.time()-t0:.0f}s")
    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(tmp, CACHE_FILE)
    nd = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    if conn:
        _market_db.set_meta(conn, "latest_date", nd[-1])
        conn.close()
    print(f"\n完成。失败 {len(fail)} 只: {fail[:10]}")
    print(f"新日期范围: {nd[0]} ~ {nd[-1]} 共 {len(nd)} 交易日, 总耗时 {time.time()-t0:.0f}s")


if __name__ == "__main__":
    main()
