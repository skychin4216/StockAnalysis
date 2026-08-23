# -*- coding: utf-8 -*-
"""东财个股历史公告抓取（《板块&舆情评分智能体》数据基础）。

接口：https://np-anotice-stock.eastmoney.com/api/security/ann
按股票代码分页拉历史公告（含日期/标题），page_size=50，可翻到任意历史深度。

用法：
  python -X utf8 -u _announce_fetch.py --all              # 全量抓取（增量续传）
  python -X utf8 -u _announce_fetch.py --code 600011      # 抓单只
  python -X utf8 -u _announce_fetch.py --all --max-pages 8 --sleep 0.3

输出：_announce_cache.json
  {code: {"name": ..., "items": [{"date": "2026-08-18", "title": ...}, ...]}}
"""
import argparse
import json
import os
import re
import sys
import time

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _full_cycle_backtest import load_cache  # noqa: E402

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
           "Referer": "https://finance.eastmoney.com/"}
PROXIES = {"http": None, "https": None}
API = "https://np-anotice-stock.eastmoney.com/api/security/ann"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_announce_cache.json")
START_DATE = "2022-08-01"  # 回测窗口起点（三年数据对齐）


def fetch_anns(code, max_pages=20, page_size=50, sleep=0.3):
    """抓取个股历史公告，返回 [{date, title}]，按时间倒序。code 允许带 sh/sz 前缀。"""
    items = []
    pure = code[-6:]  # 接口只接受纯数字代码
    for page in range(1, max_pages + 1):
        params = {"sr": "-1", "page_size": str(page_size), "page_index": str(page),
                  "ann_type": "A", "client_source": "web", "page_number": str(page),
                  "stock_list": pure}
        try:
            r = requests.get(API, params=params, headers=HEADERS, proxies=PROXIES, timeout=15)
            d = r.json()
        except Exception as e:
            print("  [warn] %s page=%d 失败: %s" % (code, page, e))
            break
        lst = (d.get("data") or {}).get("list") or []
        if not lst:
            break
        for it in lst:
            title = (it.get("title") or it.get("title_ch") or "").strip()
            title = re.sub(r"^\d{6}[:：]", "", title)
            dt = (it.get("notice_date") or "")[:10]
            items.append({"date": dt, "title": title})
        # 翻过 START_DATE 即停（已覆盖三年）
        oldest = min((x["date"] for x in items if x["date"]), default="")
        if oldest and oldest <= START_DATE:
            break
        if len(lst) < page_size:
            break
        time.sleep(sleep)
    return items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--code", type=str, default="")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--max-pages", type=int, default=20)
    ap.add_argument("--sleep", type=float, default=0.3)
    args = ap.parse_args()

    cache = load_cache()
    db = {}
    if os.path.exists(OUT):
        with open(OUT, "r", encoding="utf-8") as f:
            db = json.load(f)

    if args.code:
        items = fetch_anns(args.code, args.max_pages, sleep=args.sleep)
        db[args.code] = {"name": cache.get(args.code, {}).get("name") or args.code, "items": items}
        print("%s → %d 条公告" % (args.code, len(items)))
    elif args.all:
        codes = [c for c in cache if not c.startswith(("sh000", "sz399"))]
        for i, code in enumerate(codes):
            name = cache[code].get("name") or code
            if code in db and db[code].get("items"):
                print("  [%d/%d] %s 已有%d条，跳过" % (i + 1, len(codes), name, len(db[code]["items"])))
                continue
            items = fetch_anns(code, args.max_pages, sleep=args.sleep)
            db[code] = {"name": name, "items": items}
            print("  [%d/%d] %s → %d 条" % (i + 1, len(codes), name, len(items)))
            with open(OUT, "w", encoding="utf-8") as f:
                json.dump(db, f, ensure_ascii=False)
            print("  ... 已存 %d 只" % len(db), flush=True)
            time.sleep(args.sleep)
    else:
        ap.print_help()
        return

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False)
    print("保存：%s（%d 只股票）" % (OUT, len(db)))


if __name__ == "__main__":
    main()
