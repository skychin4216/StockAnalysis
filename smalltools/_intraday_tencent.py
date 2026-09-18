# -*- coding: utf-8 -*-
"""腾讯分时数据确认今日下午跳水时点"""
import sys
sys.path.insert(0, "e:/Android/work/dev/StockAnalysis/smalltools")
import requests

HEADERS = {"User-Agent": "Mozilla/5.0"}
PROXIES = {"http": None, "https": None}


def fetch_tencent_m1(secid):
    # 腾讯分钟线：param=sh000001,m5,,30 未来30个5分钟
    url = "https://web.ifzq.gtimg.cn/appstock/app/kline/mkline"
    params = {"param": f"{secid},m5,,48"}
    try:
        r = requests.get(url, params=params, timeout=12, headers=HEADERS, proxies=PROXIES)
        data = r.json()
        node = (data.get("data") or {}).get(secid) or {}
        m5 = node.get("m5") or []
        return m5
    except Exception as e:
        return None


def log(m):
    print(m, flush=True)
    sys.stdout.flush()


for code in ["sh000001", "sh000852", "sz399006"]:
    rows = fetch_tencent_m1(code)
    if not rows:
        log(f"{code}: 分时拉取失败")
        continue
    log(f"═══ {code} 今日分时(收盘~) ═══")
    # 每行: [时间, 开盘, 收盘, 高, 低, 成交量]
    for row in rows:
        t = row[0]
        c = float(row[2])
        chg = 0.0
        # 找昨收：用近20根算基准较难，直接用当日首根推算
        log(f"  {t} C={c}")
