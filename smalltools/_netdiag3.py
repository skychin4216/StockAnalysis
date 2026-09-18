# -*- coding: utf-8 -*-
import requests

PROXIES = {"http": None, "https": None}
HEADERS = {"User-Agent": "Mozilla/5.0"}

CASES = [
    ("stock_beg2025", "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sz300308,day,20250101,20260812,640,qfq"),
    ("index_beg2025", "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sh000001,day,20250101,20260812,640,qfq"),
    ("index_short", "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sh000001,day,2026-07-01,2026-08-12,60,qfq"),
]

for name, url in CASES:
    try:
        r = requests.get(url, timeout=15, headers=HEADERS, proxies=PROXIES)
        d = r.json()
        node = (d.get("data") or {}).get(name.split("_")[-1]) or {}
        rows = node.get("qfqday") or node.get("day") or []
        print(f"=== {name} -> code={d.get('code')} msg={d.get('msg')} rows={len(rows)} keys={list((d.get('data') or {}).keys())}")
        if rows:
            print("   first:", rows[0][:6], "last:", rows[-1][:6])
    except Exception as e:
        print(f"=== {name} -> ERR {type(e).__name__}: {str(e)[:120]}")
