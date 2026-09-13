# -*- coding: utf-8 -*-
"""临时脚本：把黄金/医药龙头补入 _kline_cache.json（腾讯日K，两段拼接 2022-08 起）。用完即删。"""
import json
import os
import shutil
import sys
import time

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import fetch_tencent_float_shares  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "_kline_cache.json")
HEADERS = {"User-Agent": "Mozilla/5.0"}
PROXIES = {"http": None, "https": None}
BEG, END = "2022-08-01", "2026-08-20"

NEW_STOCKS = [
    ("sh600547", "山东黄金"), ("sh600489", "中金黄金"), ("sh600988", "赤峰黄金"),
    ("sz002155", "湖南黄金"), ("sz000975", "银泰黄金"),
    ("sh600276", "恒瑞医药"), ("sh603259", "药明康德"), ("sh600436", "片仔癀"),
    ("sz000538", "云南白药"), ("sh600196", "复星医药"), ("sz000661", "长春高新"),
    ("sz300760", "迈瑞医疗"), ("sz300122", "智飞生物"), ("sz300142", "沃森生物"),
    ("sh688235", "百济神州"),
]


def fetch_chunk(secid, end_date, count=640):
    url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
    params = {"param": "%s,day,2022-08-01,%s,%d,qfq" % (secid, end_date, count)}
    for attempt in range(3):
        try:
            r = requests.get(url, params=params, timeout=15, headers=HEADERS, proxies=PROXIES)
            node = (r.json().get("data") or {}).get(secid) or {}
            rows = node.get("qfqday") or node.get("day") or []
            if rows:
                return rows
        except Exception as e:
            print("  chunk %s@%s fail: %s" % (secid, end_date, type(e).__name__))
            time.sleep(1)
    return []


def fetch_full(secid):
    """两段拼接 2022-08-01 起的日K，返回 (name, snaps)"""
    raw = []
    end = END
    while len(raw) == 0 or raw[-1][0] > BEG:
        rows = fetch_chunk(secid, end)
        if not rows:
            break
        raw = rows + raw
        if rows[0][0] <= BEG:
            break
        end = rows[0][0]
    name = None
    qt_name = None
    try:
        r = requests.get("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get",
                         params={"param": "%s,day,%s,%s,5,qfq" % (secid, BEG, END)},
                         timeout=12, headers=HEADERS, proxies=PROXIES)
        qt = (r.json().get("data") or {}).get(secid) or {}
        arr = (qt.get("qt") or {}).get(secid)
        if isinstance(arr, list) and len(arr) > 1:
            qt_name = arr[1]
    except Exception:
        pass
    snaps = []
    seen = set()
    for p in raw:
        if p[0] in seen:
            continue
        seen.add(p[0])
        snaps.append({
            "date": p[0], "open": float(p[1]), "close": float(p[2]),
            "high": float(p[3]), "low": float(p[4]), "volume": float(p[5]),
            "changePct": 0.0, "turnover": 0.0,
        })
    snaps.sort(key=lambda s: s["date"])
    for i in range(1, len(snaps)):
        prev = snaps[i - 1]["close"]
        if prev > 0:
            snaps[i]["changePct"] = (snaps[i]["close"] / prev - 1) * 100
    fs = fetch_tencent_float_shares(secid)
    if fs and fs > 0:
        for s in snaps:
            s["turnover"] = s["volume"] * 100 / fs * 100
    return (qt_name or secid), snaps


def main():
    cache = json.load(open(CACHE, encoding="utf-8"))
    print("原池 %d 只" % len(cache))
    added, failed = [], []
    for secid, label in NEW_STOCKS:
        if secid in cache:
            print("已存在 %s %s，跳过" % (secid, label))
            continue
        name, snaps = fetch_full(secid)
        if not snaps:
            print("失败 %s %s" % (secid, label))
            failed.append(secid)
            continue
        cache[secid] = {"name": name, "snaps": snaps, "src": "tencent"}
        added.append((secid, name, len(snaps), snaps[0]["date"], snaps[-1]["date"]))
        print("加入 %s %s(%s) %d 根 %s~%s" % (secid, name, label, len(snaps),
                                            snaps[0]["date"], snaps[-1]["date"]))
        time.sleep(0.3)
    if added:
        shutil.copy2(CACHE, CACHE + ".bak")
        json.dump(cache, open(CACHE, "w", encoding="utf-8"), ensure_ascii=False)
        print("已备份并保存：新池 %d 只（新增 %d，失败 %d）"
              % (len(cache), len(added), len(failed)))
    else:
        print("无新增")


if __name__ == "__main__":
    main()
