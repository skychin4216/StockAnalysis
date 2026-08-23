# -*- coding: utf-8 -*-
"""探针：测试雅虎/stooq 美股指数历史日K。"""
import requests

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
PROXIES = {"http": None, "https": None}


def yahoo(symbol):
    """雅虎财经。symbol: ^IXIC, ^INX, ^DJI, ^NDX"""
    url = ("https://query1.finance.yahoo.com/v8/finance/chart/" +
           symbol.replace("^", "%5E") + "?range=3y&interval=1d")
    try:
        r = requests.get(url, headers=HEADERS, proxies=PROXIES, timeout=15)
        d = r.json()
        res = (d.get("chart") or {}).get("result") or []
        if not res:
            print("  %s → 无result: %s" % (symbol, str(d)[:200]))
            return
        ts = res[0].get("timestamp") or []
        closes = (res[0].get("indicators") or {}).get("quote") or [{}]
        cl = (closes[0] or {}).get("close") or []
        import datetime
        ds = [datetime.datetime.utcfromtimestamp(t).strftime("%Y-%m-%d") for t in ts if t]
        print("  %s → %d 根 %s~%s" % (symbol, len(cl), ds[0] if ds else "-", ds[-1] if ds else "-"))
    except Exception as e:
        print("  %s → 异常: %s" % (symbol, e))


def stooq(symbol):
    """stooq.com 免费历史CSV。symbol: ^ndx, ^ixic, ^spx, ^dji"""
    url = "https://stooq.com/q/d/l/?s=" + symbol.replace("^", "%5E") + "&i=d"
    try:
        r = requests.get(url, headers=HEADERS, proxies=PROXIES, timeout=15)
        if r.status_code == 200 and r.text and not r.text.startswith("Exceeded"):
            lines = [x for x in r.text.strip().splitlines() if x]
            print("  %s → %d 行 首=%s 末=%s" % (symbol, len(lines), lines[0], lines[-1]))
        else:
            print("  %s → %s %s" % (symbol, r.status_code, r.text[:100]))
    except Exception as e:
        print("  %s → 异常: %s" % (symbol, e))


def main():
    print("== 雅虎 ==")
    for s in ("^NDX", "^IXIC", "^INX", "^DJI"):
        yahoo(s)
    print("== stooq ==")
    for s in ("^ndx", "^ixic", "^spx", "^dji"):
        stooq(s)


if __name__ == "__main__":
    main()
