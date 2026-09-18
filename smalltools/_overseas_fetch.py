# -*- coding: utf-8 -*-
"""外围市场历史日K采集（A股跨境 ETF 作为代理，腾讯接口稳定可用）。

为什么用 A股 ETF：
  - 雅虎 403 / stooq 反爬 / 东财直连被断，直接抓美股指数历史不可靠
  - 纳指100ETF(sh513100)、标普500ETF(sh513500)、纳斯达克ETF(sh513300)
    T+0 实时跟踪外围，日线涨跌即「隔夜外围 + 当日盘中」的联动代理
  - 腾讯 fqkline 接口对 A 股标的稳定返回 800 根历史

产出：smalltools/_overseas_cache.json
  {"sh513100": {"name": "纳指100ETF", "snaps": [{"date":..,"open":..,"high":..,"low":..,"close":..}, ...]}, ...}

用法：
  python _overseas_fetch.py            # 全量拉取（增量更新）
  python _overseas_fetch.py --check    # 仅检查缓存状态
"""
import argparse
import json
import os
import sys

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_FILE = os.path.join(HERE, "_overseas_cache.json")

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
PROXIES = {"http": None, "https": None}  # 直连，不读系统代理

# 外围代理：code(腾讯) → 说明。权重按对A股科技链影响力（参考 APK OVERSEAS_WEIGHTS）
OVERSEAS_PROXIES = {
    "sh513100": "纳指100ETF(NDX)",    # 科技/半导体联动，权重最高
    "sh513310": "中韩半导体ETF(KS11)", # 韩股半导体链（三星/SK海力士），2024-03 起
    "sh513500": "标普500ETF(SPX)",    # 广义情绪
    "sh513300": "纳斯达克ETF(NDX)",   # 备用科技代理
}

START = "2022-01-01"
END = "2099-12-31"


def fetch_kline(code, count=800):
    """腾讯 fqkline：A股标的返回 count 根（约 3 年日K）。"""
    url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
    r = requests.get(url, params={"param": f"{code},day,{START},{END},{count},qfq"},
                     timeout=15, headers=HEADERS, proxies=PROXIES)
    node = (r.json().get("data") or {}).get(code, {})
    kl = node.get("qfqday") or node.get("day") or []
    rows = []
    for it in kl:
        try:
            rows.append({"date": it[0], "open": float(it[1]), "close": float(it[2]),
                         "high": float(it[3]), "low": float(it[4]), "volume": float(it[5])})
        except (IndexError, ValueError):
            continue
    return rows


def load_cache():
    if os.path.exists(CACHE_FILE):
        try:
            return json.load(open(CACHE_FILE, encoding="utf-8"))
        except Exception:
            return {}
    return {}


def fetch_all():
    cache = load_cache()
    for code, name in OVERSEAS_PROXIES.items():
        rows = fetch_kline(code)
        if not rows:
            print("  %s %s 拉取失败" % (code, name))
            continue
        # 增量合并：按 date 去重
        old = {s["date"]: s for s in cache.get(code, {}).get("snaps", [])}
        for s in rows:
            old[s["date"]] = s
        snaps = [old[d] for d in sorted(old)]
        cache[code] = {"name": name, "snaps": snaps}
        print("  %s %s → %d 根 %s ~ %s"
              % (code, name, len(snaps), snaps[0]["date"], snaps[-1]["date"]))
    with open(CACHE_FILE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=1)
    print("已写入 %s" % CACHE_FILE)
    return cache


def check():
    cache = load_cache()
    if not cache:
        print("缓存为空，先执行 python _overseas_fetch.py")
        return
    for code, ent in cache.items():
        snaps = ent.get("snaps") or []
        print("  %s %-20s %d 根 %s ~ %s"
              % (code, ent.get("name", ""), len(snaps),
                 snaps[0]["date"] if snaps else "-", snaps[-1]["date"] if snaps else "-"))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    if args.check:
        check()
    else:
        fetch_all()
