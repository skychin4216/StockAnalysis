# -*- coding: utf-8 -*-
import requests

TESTS = [
    ("tencent", "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sz300308,day,2026-08-01,2026-08-12,60,qfq"),
    ("east90", "https://90.push2his.eastmoney.com/api/qt/stock/kline/get?secid=0.300308&klt=101&fqt=1&end=20260812&lmt=60&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"),
    ("east_main", "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=0.300308&klt=101&fqt=1&end=20260812&lmt=60&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"),
]

for name, url in TESTS:
    for attempt in range(3):
        try:
            r = requests.get(url, timeout=12, headers={"User-Agent": "Mozilla/5.0"},
                             proxies={"http": None, "https": None})
            print(f"{name}[try{attempt}] -> {r.status_code}: {r.text[:150]}")
            break
        except Exception as e:
            print(f"{name}[try{attempt}] -> ERR {type(e).__name__}: {str(e)[:100]}")
