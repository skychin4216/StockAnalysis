# -*- coding: utf-8 -*-
"""获取微盘相关指数 + 今日涨跌家数，确认微盘跳水"""
import sys, re, time, random
sys.path.insert(0, "e:/Android/work/dev/StockAnalysis/smalltools")
import requests
from backtest_guangmo import fetch_tencent, get_index_dir

HEADERS = {"User-Agent": "Mozilla/5.0"}
PROXIES = {"http": None, "https": None}


def log(m):
    print(m, flush=True)
    sys.stdout.flush()


# 微盘/小盘相关指数
idx = {
    "sh932000": "中证2000",
    "sh000852": "中证1000",
    "sh000905": "中证500",
    "sh000300": "沪深300",
    "sh000016": "上证50",
}
for code, label in idx.items():
    try:
        name, snaps = fetch_tencent(code, "20250601", "20260813")
        if not snaps:
            log(f"{label}({code}): 拉取失败")
            continue
        last = snaps[-1]
        closes = [s['close'] for s in snaps]
        ma5 = sum(closes[-5:]) / 5
        ma20 = sum(closes[-20:]) / 20
        log(f"{label}({code}) 最新{last['date']} 收盘{last['close']:.2f} "
            f"涨{last['changePct']:+.2f}% MA5={ma5:.1f} MA20={ma20:.1f}")
    except Exception as e:
        log(f"{label}: 异常 {e}")

# 今日涨跌家数（用东财市场统计接口）
log("\n── 今日市场宽度(涨跌家数) ──")
try:
    r = requests.get("https://push2.eastmoney.com/api/qt/clist/get",
                     params={"pn": "1", "pz": "1", "po": "1", "np": "1",
                             "fltt": "2", "invt": "2", "fid": "f3",
                             "fs": "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23",
                             "fields": "f3,f6"}, timeout=12, headers=HEADERS, proxies=PROXIES)
    d = r.json()
    total = d["data"]["total"]
    log(f"全市场股票总数: {total}")
except Exception as e:
    log(f"市场宽度获取失败: {e}")
