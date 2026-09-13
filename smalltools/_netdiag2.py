# -*- coding: utf-8 -*-
import requests

PROXIES = {"http": None, "https": None}
HEADERS = {"User-Agent": "Mozilla/5.0"}

URLS = [
    ("proxy.kline", "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/kline/kline?param=sz300308,day,2026-07-01,2026-08-12,60,qfq"),
    ("fqkline_noqfq", "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sz300308,day,2026-07-01,2026-08-12,60"),
    ("fqkline_qfq", "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sz300308,day,2026-07-01,2026-08-12,60,qfq"),
    ("sina", "https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20_=/CN_MarketDataService.getKLineData?symbol=sz300308&scale=240&ma=no&datalen=60"),
]

for name, url in URLS:
    try:
        r = requests.get(url, timeout=12, headers=HEADERS, proxies=PROXIES)
        txt = r.text
        print(f"=== {name} -> {r.status_code} ===")
        print(txt[:600])
        print()
    except Exception as e:
        print(f"=== {name} -> ERR {type(e).__name__}: {str(e)[:100]} ===")
        print()
