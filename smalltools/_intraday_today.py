# -*- coding: utf-8 -*-
"""获取今日分时(5分钟)K线，确认下午跳水时点"""
import sys, time, random
sys.path.insert(0, "e:/Android/work/dev/StockAnalysis/smalltools")
import requests

HEADERS = {"User-Agent": "Mozilla/5.0"}
PROXIES = {"http": None, "https": None}

HOSTS = ["https://push2his.eastmoney.com", "https://90.push2his.eastmoney.com"]


def to_east_secid(secid):
    if secid.startswith("sz"):
        return "0." + secid[2:]
    if secid.startswith("sh"):
        return "1." + secid[2:]
    return secid


def fetch_m5(secid, end="20260813"):
    params = {
        "secid": to_east_secid(secid), "klt": "5", "fqt": "1",
        "beg": "20260813", "end": end, "lmt": "100",
        "fields1": "f1,f2,f3,f4,f5,f6",
        "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
    }
    for host in HOSTS:
        try:
            r = requests.get(host + "/api/qt/stock/kline/get", params=params,
                             timeout=12, headers=HEADERS, proxies=PROXIES)
            data = r.json()
            if data.get("data") and data["data"].get("klines"):
                return data["data"]["name"], data["data"]["klines"]
        except Exception:
            pass
        time.sleep(0.3)
    return None, None


def log(m):
    print(m, flush=True)
    sys.stdout.flush()


targets = {
    "sh000001": "上证指数",
    "sh000852": "中证1000",   # 小盘代表
    "sz399006": "创业板指",
}
for code, label in targets.items():
    name, klines = fetch_m5(code)
    if not klines:
        log(f"{label}: 分时拉取失败")
        continue
    log(f"═══ {label}({code}) {name} 今日5分钟K线 ═══")
    for k in klines:
        p = k.split(",")
        t = p[0]          # 时间
        o, c, h, l = p[1], p[2], p[3], p[4]
        chg = float(p[8]) if len(p) > 8 else 0.0
        hm = t[11:16]
        log(f"  {hm} O={o} C={c} H={h} L={l} 涨跌={chg:+.2f}%")
