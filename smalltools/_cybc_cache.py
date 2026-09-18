# -*- coding: utf-8 -*-
"""创业板+科创板全池日K缓存（2026-09-15 新增，超跌反抽策略数据底座）。

背景：双创超跌反抽策略（Step 1 探针 → Step 2 拟合）需要全双创 ~1700 只的
日K做「板块内相对地位 / 弹性 / 回撤 / 近一年回测」，而 data/kline_store.json 只有
核心池 ~250 只。本工具维护独立缓存：

    smalltools/data/_kline_cybc.json
    {secid: {"name", "industry", "snaps":[{date,open,close,high,low,volume}]}}

- 股票池：data/_market_snapshot.json 的创业板(300/301/302) + 科创板(688/689)
  非ST全部，外加指数 sh000001(上证)/sz399006(创业板指)/sh000688(科创50)
  （指数供「指数环境」因子：MA 排列 / 距高回撤）。
- 抓取：腾讯 ifzq qfq 日K（单请求/只，前复权），并发 workers；
  首次全量拉 400 自然日（约 270 交易日，覆盖近一年回测），之后 --update 增量。
- 行业字段取自 snapshot（东财行业），避免消费端重复解析。

用法：
    python _cybc_cache.py                # 首次/补建：全量拉 400 自然日
    python _cybc_cache.py --update       # 盘后增量：只补缺失日期
    python _cybc_cache.py --update --workers 16
"""
import argparse
import datetime
import json
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from backtest_guangmo import HEADERS, PROXIES  # noqa: E402

SNAP = os.path.join(os.path.dirname(HERE), "data", "_market_snapshot.json")
CACHE_FILE = os.path.join(HERE, "data", "_kline_cybc.json")
INDEXES = {"sh000001": "上证指数", "sz399006": "创业板指", "sh000688": "科创50"}
FULL_DAYS = 400          # 全量窗口（自然日）
_SESSION = requests.Session()
_LOCK = threading.Lock()


def universe():
    """双创非ST股票池 + 指数 → {secid: {"name","industry"}}。"""
    with open(SNAP, encoding="utf-8") as f:
        snap = json.load(f)
    out = {}
    for secid, v in snap.items():
        code = secid[2:]
        if not (secid.startswith("sz") and code[:3] in ("300", "301", "302")
                or secid.startswith("sh") and code[:3] in ("688", "689")):
            continue
        name = v.get("name") or ""
        if "ST" in name or "退" in name:
            continue
        out[secid] = {"name": name, "industry": v.get("industry") or ""}
    for secid, name in INDEXES.items():
        out.setdefault(secid, {"name": name, "industry": "指数"})
    return out


def load_cache():
    if os.path.isfile(CACHE_FILE):
        try:
            with open(CACHE_FILE, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            print("缓存损坏，忽略重建")
    return {}


def _fmt(d):
    return f"{d[:4]}-{d[4:6]}-{d[6:]}" if len(d) == 8 and d.isdigit() else d


def fetch_k(secid, beg, end):
    """单只拉腾讯 qfq 日K → snaps（date/open/close/high/low/volume）。"""
    url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
    params = {"param": f"{secid},day,{_fmt(beg)},{_fmt(end)},640,qfq"}
    for _ in range(3):
        try:
            r = _SESSION.get(url, params=params, timeout=15,
                             headers=HEADERS, proxies=PROXIES)
            node = (r.json().get("data") or {}).get(secid) or {}
            rows = node.get("qfqday") or node.get("day") or []
            if rows:
                return [{"date": p[0], "open": float(p[1]), "close": float(p[2]),
                         "high": float(p[3]), "low": float(p[4]),
                         "volume": float(p[5])} for p in rows]
            return []
        except Exception:
            time.sleep(0.4)
    return None


def probe_end():
    """最近已收盘交易日（盘中不算今天）。"""
    now = datetime.datetime.now()
    d = now.date()
    if now.time() < datetime.time(15, 0):
        d -= datetime.timedelta(days=1)
    return d.strftime("%Y%m%d")


def main():
    ap = argparse.ArgumentParser(description="双创全池日K缓存维护")
    ap.add_argument("--update", action="store_true", help="增量：只补缺失日期")
    ap.add_argument("--workers", type=int, default=16)
    args = ap.parse_args()

    uni = universe()
    cache = load_cache()
    end = probe_end()
    tasks = []  # (secid, beg)
    for secid, meta in uni.items():
        old = cache.get(secid, {}).get("snaps") or []
        if not old or len(old) < 30:
            beg = (datetime.date.today()
                   - datetime.timedelta(days=FULL_DAYS)).strftime("%Y%m%d")
        else:
            last = old[-1]["date"].replace("-", "")
            if args.update and last >= end:
                continue
            beg = last  # 含当日重拉去重
        tasks.append((secid, beg))
    print("池 %d 只（股票+指数），需抓取 %d 只 → %s（workers=%d）"
          % (len(uni), len(tasks), end, args.workers))

    t0 = time.time()
    done = fail = 0

    def work(item):
        secid, beg = item
        snaps = fetch_k(secid, beg, end)
        return secid, snaps

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for fut in as_completed({pool.submit(work, it): it for it in tasks}):
            secid, snaps = fut.result()
            meta = uni[secid]
            if snaps is None:
                fail += 1
                continue
            if snaps:
                ent = cache.setdefault(secid, {"name": meta["name"],
                                               "industry": meta["industry"]})
                ent.setdefault("snaps", [])
                old = {s["date"]: s for s in ent["snaps"]}
                old.update({s["date"]: s for s in snaps})
                ent["snaps"] = sorted(old.values(), key=lambda x: x["date"])
                ent["name"] = meta["name"] or ent.get("name", "")
                ent["industry"] = meta["industry"]
            done += 1
            if done % 200 == 0:
                with _LOCK:
                    print("  [%d/%d] 失败%d 耗时%.0fs"
                          % (done, len(tasks), fail, time.time() - t0), flush=True)

    # 清掉已退市/快照消失的旧键（保留指数）
    for secid in list(cache.keys()):
        if secid not in uni:
            del cache[secid]

    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, CACHE_FILE)
    n_dates = len(cache.get("sh000001", {}).get("snaps") or [])
    sz = os.path.getsize(CACHE_FILE) / 1e6
    print("完成：%d 只入库，指数交易日 %d 天，文件 %.1fMB，耗时 %.0fs，失败 %d 只"
          % (len(cache), n_dates, sz, time.time() - t0, fail))


if __name__ == "__main__":
    main()
