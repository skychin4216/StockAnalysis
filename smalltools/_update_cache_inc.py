# -*- coding: utf-8 -*-
"""增量更新 _kline_cache.json 到最新交易日（仅补缺失日期，保留原有数据）。

同步写公共数据库 StockAnalysis/data/market_data.db（_market_db），
保证 exe / smalltools 使用同一数据源。
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import fetch_east, fetch_tencent
import _market_db

CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")


def _probe_end(beg):
    """探测最近可用交易日：从今天往前最多退 7 天，取第一根能拉到日K的日期。"""
    import datetime as _dt
    d = _dt.date.today()
    for _ in range(8):
        end = d.strftime("%Y%m%d")
        try:
            _, snaps = fetch_east("sh000001", beg, end)
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


def main():
    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    conn = _market_db.get_conn()
    codes = list(cache.keys())
    ds = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    end0 = _probe_end(_beg_for(ds[-1]))
    # 端日期必须不早于当前缓存末端，否则无新数据可拉
    if _cache_date(end0) <= ds[-1]:
        print(f"缓存已是最新({ds[-1]}), 无需更新")
        _market_db.set_meta(conn, "latest_date", ds[-1])
        conn.close()
        return
    BEG = _beg_for(end0)
    END = end0
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
        # 同步写公共数据库
        _market_db.upsert_kline(conn, secid, snaps, src=src, name=name or cache[secid].get("name"))
        if (i + 1) % 10 == 0:
            print(f"  [{i+1}/{len(codes)}] 完成, 最新 {snaps[-1]['date']} ({len(snaps)}根)")
        time.sleep(0.2)
    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(tmp, CACHE_FILE)
    nd = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    _market_db.set_meta(conn, "latest_date", nd[-1])
    conn.close()
    print(f"\n完成。失败 {len(fail)} 只: {fail[:10]}")
    print(f"新日期范围: {nd[0]} ~ {nd[-1]} 共 {len(nd)} 交易日")


if __name__ == "__main__":
    main()
