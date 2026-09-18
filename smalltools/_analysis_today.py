# -*- coding: utf-8 -*-
"""今日(2026-08-13)大盘/微盘跳水诊断 + 回测验证"""
import sys, time, random, math
sys.path.insert(0, "e:/Android/work/dev/StockAnalysis/smalltools")
import requests
from backtest_guangmo import fetch_kline, get_index_dir, triple_vote

HEADERS = {"User-Agent": "Mozilla/5.0"}
PROXIES = {"http": None, "https": None}

# 指数代码：上证、深成、创业板、微盘股
IDX = {
    "sh000001": "上证指数",
    "sz399001": "深证成指",
    "sz399006": "创业板指",
    "sz399005": "中小100",
    "sh000852": "中证1000",
    "sh000688": "科创50",
}

def fetch_rt(secid):
    """实时快照：价/涨跌/成交量"""
    try:
        r = requests.get("https://qt.gtimg.cn/q=" + secid, timeout=10, headers=HEADERS, proxies=PROXIES)
        r.encoding = "gbk"
        import re
        m = re.search(r'="([^"]*)"', r.text)
        if not m: return None
        f = m.group(1).split("~")
        return {
            "name": f[1], "price": float(f[3]),
            "yest": float(f[4]), "open": float(f[5]),
            "high": float(f[33]), "low": float(f[34]),
            "chg_pct": float(f[32]) if len(f) > 32 and f[32] else 0.0,
            "ts": f[30], "vol": f[6],
        }
    except Exception as e:
        return {"err": str(e)}

def fetch_rt_tencent_multi(codes):
    try:
        r = requests.get("https://qt.gtimg.cn/q=" + ",".join(codes), timeout=10, headers=HEADERS, proxies=PROXIES)
        r.encoding = "gbk"
        out = {}
        for line in r.text.strip().split(";"):
            line = line.strip()
            if not line or "=" not in line: continue
            import re
            code = line.split("=")[0].replace("v_", "").strip()
            m = re.search(r'="([^"]*)"', line)
            if not m: continue
            f = m.group(1).split("~")
            try:
                out[code] = {
                    "name": f[1], "price": float(f[3]),
                    "yest": float(f[4]), "open": float(f[5]),
                    "high": float(f[33]), "low": float(f[34]),
                    "chg_pct": float(f[32]) if len(f) > 32 and f[32] else 0.0,
                }
            except Exception:
                pass
        return out
    except Exception as e:
        return {"err": str(e)}

print("=" * 60)
print("今日(2026-08-13) 大盘实时快照")
print("=" * 60)
rt = fetch_rt_tencent_multi(list(IDX.keys()))
for code, meta in IDX.items():
    d = rt.get(code)
    if not d: continue
    print(f"  {meta}({code}) {d['name']} 现价={d['price']:.2f} "
          f"涨幅={d['chg_pct']:+.2f}% 开={d['open']:.2f} 高={d['high']:.2f} 低={d['low']:.2f}")

print()
print("=" * 60)
print("大盘日K趋势（截至今日）")
print("=" * 60)
for code, meta in IDX.items():
    name, snaps, src = fetch_kline(code, "20250101", "20260813")
    if not snaps:
        print(f"  {meta}: 拉取失败")
        continue
    last = snaps[-1]
    dirn = get_index_dir(name, snaps)
    closes = [s["close"] for s in snaps]
    ma5 = sum(closes[-5:])/5
    ma10 = sum(closes[-10:])/10
    ma20 = sum(closes[-20:])/20
    print(f"  {meta}({code}) 最新日={last['date']} 收盘={last['close']:.2f} 涨={last['changePct']:+.2f}% 方向={dirn} "
          f"MA5={ma5:.2f} MA10={ma10:.2f} MA20={ma20:.2f}")
