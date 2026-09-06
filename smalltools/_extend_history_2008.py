# -*- coding: utf-8 -*-
"""
把 _kline_cache.json 中 2015-06 前已上市的老票/指数补齐到 2008-01-02。

背景：腾讯 qfq(前复权) 对 2008 段部分股票(高分红/高送转)产生负价 → 不可用；
方案：历史段用腾讯 hfq(后复权，价格恒正、区间内除权已调整、成交量与 qfq 完全一致)，
拼接时按"qfq 首根 close / hfq 末根 close"等比缩放对齐基准，无缝接续 2015-06 后的 qfq 段。
指数无除权，直接用 qfq。

分 4 段拉取（每段 <640 根）。用量小、可重复运行（已含 2008 的自动跳过）。
"""
import json
import os
import sys
import time

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import backtest_guangmo as bg  # noqa: E402

CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
HEADERS = {"User-Agent": "Mozilla/5.0"}
PROXIES = {"http": None, "https": None}

# 2008-01-02 ~ 2015-06-01，每段 ~480 交易日（<640 上限）
SEGS_BACK = [
    ("2008-01-02", "2009-12-31"),
    ("2010-01-04", "2011-12-30"),
    ("2012-01-03", "2013-12-31"),
    ("2014-01-02", "2015-06-01"),
]


def fetch_tencent_raw(secid, beg, end, fq):
    """腾讯 fqkline 单段；fq='hfq'/'qfq'/'day'。返回 list[dict] 或 []。"""
    url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
    params = {"param": "%s,day,%s,%s,640,%s" % (secid, beg, end, fq)}
    for _ in range(3):
        try:
            r = requests.get(url, params=params, timeout=15, headers=HEADERS, proxies=PROXIES)
            node = (r.json().get("data") or {}).get(secid) or {}
            rows = (node.get("hfqday") or node.get("qfqday")
                    or node.get("day") or [])
            if not rows:
                return []
            snaps = []
            for p in rows:
                snaps.append({
                    "date": p[0],
                    "open": float(p[1]), "close": float(p[2]),
                    "high": float(p[3]), "low": float(p[4]),
                    "volume": float(p[5]),
                    "changePct": 0.0,
                    "turnover": 0.0,
                })
            for i in range(1, len(snaps)):
                prev = snaps[i - 1]["close"]
                if prev > 0:
                    snaps[i]["changePct"] = (snaps[i]["close"] / prev - 1) * 100
            fs = bg.fetch_tencent_float_shares(secid)
            if fs and fs > 0:
                for s in snaps:
                    s["turnover"] = s["volume"] * 100 / fs * 100
            return snaps
        except Exception:
            pass
        time.sleep(0.5)
    return []


def merge(snaps_list):
    m = {}
    for seg in snaps_list or []:
        for s in seg or []:
            m[s["date"]] = s
    return sorted(m.values(), key=lambda x: x["date"])


def main():
    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    codes = list(cache.keys())
    done = skipped = 0
    print("补拉 %d 只 -> 2008-01-02 起（老票 hfq 等比对齐接续 qfq；指数 qfq）..." % len(codes), flush=True)
    for i, secid in enumerate(codes):
        prev = (cache.get(secid) or {}).get("snaps") or []
        if not prev:
            continue
        if prev[0]["date"] < "2009-01-01":
            skipped += 1  # 已含 2008 段
            continue
        if prev[0]["date"] > "2016-01-01":
            skipped += 1  # 2016 后才上市，无 2008-2015 段
            continue
        is_index = secid.startswith("sh000") or secid.startswith("sz399")
        fq = "qfq" if is_index else "hfq"
        front = []
        for beg, end in SEGS_BACK:
            seg = fetch_tencent_raw(secid, beg, end, fq)
            if seg:
                front.append(seg)
            time.sleep(0.08)
        if not front:
            continue  # 该段未上市
        front = merge(front)
        # 等比缩放对齐：hfq 末根 close -> qfq 首根 close 同一价格基准
        if not is_index and prev and front:
            ref = front[-1]["close"]
            if ref > 0:
                scale = prev[0]["close"] / ref
                for s in front:
                    s["open"] *= scale
                    s["close"] *= scale
                    s["high"] *= scale
                    s["low"] *= scale
        snaps = merge([front, prev])
        cache[secid]["snaps"] = snaps
        done += 1
        if done % 10 == 0:
            print("  [%d/%d] %s n=%d %s~%s" % (
                i + 1, len(codes), secid, len(snaps),
                snaps[0]["date"], snaps[-1]["date"]), flush=True)
    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(tmp, CACHE_FILE)
    ds = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    print("完成。补齐 %d 只，跳过 %d 只" % (done, skipped), flush=True)
    print("日期范围 %s ~ %s 共 %d 交易日" % (ds[0], ds[-1], len(ds)), flush=True)


if __name__ == "__main__":
    main()
