# -*- coding: utf-8 -*-
"""东财个股历史新闻抓取（《板块&舆情评分智能体》的数据基础）。

接口：https://search-api-web.eastmoney.com/search/jsonp
按股票名关键字搜索，返回全站文章（新闻/公告/研报），含日期/标题/摘要。

用法：
  python -X utf8 -u _news_fetch.py --pilot 5      # 试点：抓前5只股票，打印结构+日期覆盖
  python -X utf8 -u _news_fetch.py --all          # 全量抓取缓存股票池
  python -X utf8 -u _news_fetch.py --all --max-pages 5 --sleep 0.4

输出：_news_cache.json（并同步写公共数据库 news 表，exe/smalltools 共用同一数据源）
  {code: {"name": ..., "items": [{"date": "2026-08-20 09:15", "title": ..., "summary": ...}, ...]}}
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
import _market_db  # noqa: E402

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
PROXIES = {"http": None, "https": None}
API = "https://search-api-web.eastmoney.com/search/jsonp"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_news_cache.json")


def _parse_jsonp(text):
    """解析 JSONP: cb({...}) 或纯 JSON。"""
    text = text.strip()
    if text.startswith("cb(") or text.startswith("("):
        m = re.search(r"\(\s*(\{.*\})\s*\)\s*;?\s*$", text, re.S)
        if m:
            return json.loads(m.group(1))
    return json.loads(text)


def build_param(keyword, page, page_size=10):
    return {
        "uid": "", "keyword": keyword,
        "type": ["cmsArticleWebOld"],
        "client": "web", "clientType": "web", "clientVersion": "curr",
        "param": {
            "cmsArticleWebOld": {
                "searchScope": "default", "sort": "time",
                "pageIndex": page, "pageSize": page_size,
                "preTag": "<em>", "postTag": "</em>",
            }
        },
    }


def fetch_news(keyword, max_pages=3, page_size=10, sleep=0.5):
    """返回 [{"date","title","summary"}]，按时间倒序（最新在前）。"""
    items = []
    for page in range(1, max_pages + 1):
        param = build_param(keyword, page, page_size)
        try:
            r = requests.get(API,
                             params={"cb": "cb", "param": json.dumps(param, ensure_ascii=False)},
                             headers=HEADERS, proxies=PROXIES, timeout=15)
            data = _parse_jsonp(r.text)
        except Exception as e:
            print("  [warn] %s 第%d页失败: %s" % (keyword, page, e))
            break
        result = (data.get("result") or {}).get("cmsArticleWebOld") or {}
        lst = result.get("list") or []
        if not lst:
            break
        for it in lst:
            items.append({
                "date": it.get("date", ""),
                "title": re.sub(r"</?em>", "", it.get("title", "") or ""),
                "summary": re.sub(r"</?em>", "", it.get("content", "") or "")[:200],
            })
        if len(lst) < page_size:
            break
        time.sleep(sleep)
    return items


def pilot(cache, n=5, max_pages=3):
    codes = [c for c in cache if not c.startswith(("sh000", "sz399"))][:n]
    for code in codes:
        name = cache[code].get("name") or code
        print("== %s %s ==" % (code, name))
        items = fetch_news(name, max_pages=max_pages)
        print("  条数=%d" % len(items))
        for it in items[:5]:
            print("   - %s | %s" % (it["date"][:10], it["title"][:50]))
        if items:
            dates = sorted(it["date"][:10] for it in items)
            print("  覆盖 %s ~ %s (%d 天)" % (dates[0], dates[-1], len(set(dates))))
        time.sleep(0.5)


def run_all(cache, max_pages, sleep):
    db = {}
    if os.path.exists(OUT):
        with open(OUT, "r", encoding="utf-8") as f:
            db = json.load(f)
    codes = [c for c in cache if not c.startswith(("sh000", "sz399"))]
    for i, code in enumerate(codes):
        name = cache[code].get("name") or code
        if code in db and db[code].get("items"):
            print("  [%d/%d] %s 已有%d条，跳过" % (i + 1, len(codes), name, len(db[code]["items"])))
            continue
        items = fetch_news(name, max_pages=max_pages, sleep=sleep)
        db[code] = {"name": name, "items": items}
        print("  [%d/%d] %s → %d 条" % (i + 1, len(codes), name, len(items)))
        if (i + 1) % 10 == 0:
            with open(OUT, "w", encoding="utf-8") as f:
                json.dump(db, f, ensure_ascii=False)
            print("  ... 已存 %d 只" % len(db))
        time.sleep(sleep)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False)
    # 同步写公共数据库 news 表
    conn = _market_db.get_conn()
    n_stock, n_row = _market_db.import_news_json(conn, cache=db)
    conn.close()
    print("完成：%d 只股票新闻 → %s" % (len(db), OUT))
    print("已同步公共数据库 news 表：%d 只 / %d 条" % (n_stock, n_row))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pilot", type=int, default=0, help="试点抓取前N只股票")
    ap.add_argument("--all", action="store_true", help="全量抓取缓存股票池")
    ap.add_argument("--max-pages", type=int, default=3)
    ap.add_argument("--sleep", type=float, default=0.5)
    args = ap.parse_args()

    cache = load_cache()
    if args.pilot:
        pilot(cache, n=args.pilot, max_pages=args.max_pages)
    elif args.all:
        run_all(cache, args.max_pages, args.sleep)
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
