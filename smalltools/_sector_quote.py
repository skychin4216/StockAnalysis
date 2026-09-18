# -*- coding: utf-8 -*-
"""板块候选行情扫描工具（2026-09-09 由 _tmp_probe_sector.py 转正，正式工具）。

供 sector-hunter / sector-picks / 每日三次板块挖掘推送使用：
对一批候选股票（默认厄尔尼诺农业主线池），从腾讯拉取：
  - qt.gtimg.cn q= 实时字段：现价/涨跌%/换手/流通市值/总市值/PB/涨停/跌停/量比/静态PE
  - fetch_tencent 日K：5/10/20 日涨幅、MA20/MA60 形态、距 60 日高点回撤
数据源腾讯（东财 push2 被断时的最稳链路）；东财可用时可另配 _sector_fundflow.py 补主力资金 f62。

用法：
  python _sector_quote.py                 # 默认农业主线池
  python _sector_quote.py --codes sz000998,sh600141   # 自定义标的
  python _sector_quote.py --kw 化肥,草甘膦             # 只在默认池里显示含关键词行（后置过滤）
"""
import argparse
import datetime
import re
import sys
import time

import requests

sys.path.insert(0, r"e:\Android\work\dev\StockAnalysis\smalltools")
from backtest_guangmo import fetch_tencent  # noqa: E402

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
      "Referer": "https://gu.qq.com/"}
PX = {"http": None, "https": None}

# 腾讯 q= 字段公认映射：f3现价 f4昨收 f32涨跌% f38换手 f39 PE(TTM)
# f44流通市值(亿) f45总市值(亿) f46 PB f47涨停 f48跌停 f49量比 f53静态PE

DEFAULT_POOL = [
    # 厄尔尼诺农业主线（2026-09-09 建）
    ("sz000998", "隆平高科", "水稻/玉米种"),
    ("sz002041", "登海种业", "玉米种"),
    ("sh600313", "农发种业", "小麦/玉米种"),
    ("sz300087", "荃银高科", "水稻种"),
    ("sz000713", "丰乐种业", "种业+农化"),
    ("sh601952", "苏垦农发", "稻麦种植+米业"),
    ("sh600598", "北大荒", "农垦稻豆"),
    ("sh600108", "亚盛集团", "农垦马铃薯"),
    ("sh600127", "金健米业", "大米加工"),
    ("sz000019", "深粮控股", "粮食贸易"),
    ("sz000505", "京粮控股", "油脂大米"),
    ("sh600141", "兴发集团", "草甘膦磷化工"),
    ("sh603599", "广信股份", "草甘膦小盘"),
    ("sz002258", "利尔化学", "除草剂"),
    ("sh600389", "江山股份", "除草剂"),
    ("sz002215", "诺普信", "农药制剂"),
    ("sz000902", "新洋丰", "磷复肥"),
    ("sz002539", "云图控股", "复合肥"),
    ("sz002588", "史丹利", "复合肥"),
]


def to_f(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def quotes(cands):
    """批量拉腾讯 q= 实时字段。"""
    out = {}
    for i in range(0, len(cands), 12):
        batch = [c for c, _, _ in cands[i:i + 12]]
        try:
            r = requests.get("https://qt.gtimg.cn/q=" + ",".join(batch),
                             timeout=10, headers=UA, proxies=PX)
            r.encoding = "gbk"
        except Exception:
            continue
        for seg in r.text.strip().split(";"):
            m = re.search(r'v_(\w+)="([^"]*)"', seg)
            if not m:
                continue
            f = m.group(2).split("~")
            if len(f) < 54:
                continue
            out[m.group(1)] = {
                "name": f[1], "price": to_f(f[3]), "pct": to_f(f[32]),
                "turn": to_f(f[38]), "pe_ttm": to_f(f[39]),
                "float_yi": to_f(f[44]), "mcap_yi": to_f(f[45]),
                "pb": to_f(f[46]), "vr": to_f(f[49]), "pe_static": to_f(f[53]),
            }
        time.sleep(0.2)
    return out


def kline_stats(secid, end=None, days=150):
    """日K动量/均线/距60日高。end 缺省=今天（动态区间，避免写死日期过期）。"""
    end = end or datetime.date.today()
    beg = end - datetime.timedelta(days=days)
    try:
        _, snaps = fetch_tencent(secid, beg.strftime("%Y%m%d"),
                                 end.strftime("%Y%m%d"))
    except Exception:
        return None
    if not snaps or len(snaps) < 25:
        return None
    closes = [s["close"] for s in snaps]
    vols = [float(s.get("volume", 0)) for s in snaps]
    c = closes[-1]
    if c <= 0:
        return None
    n = len(closes)
    chg = lambda k: (c / closes[-k - 1] - 1) * 100 if n > k else None  # noqa: E731
    ma = lambda k: sum(closes[-k:]) / k if n >= k else None  # noqa: E731
    v5 = sum(vols[-5:]) / 5
    v20 = sum(vols[-20:]) / 20 if n >= 20 else v5
    hi60 = max(closes[-min(n, 60):])
    dd60 = (c / hi60 - 1) * 100
    m20, m60 = ma(20), ma(min(60, n))
    if m20 and m60:
        shape = "多头" if (m20 > m60 and c > m20) else ("空头" if (m20 < m60 and c < m20) else "纠缠")
    else:
        shape = "?"
    return {"date": snaps[-1]["date"], "close": c, "chg5": chg(5), "chg10": chg(10),
            "chg20": chg(20), "dd60": dd60, "m20": m20, "m60": m60,
            "vr": (v5 / v20) if v20 else None, "shape": shape}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--codes", help="自定义标的，逗号分隔 secid 如 sz000998,sh600141")
    ap.add_argument("--kw", help="显示过滤：名称含关键词（支持多词用逗号）")
    args = ap.parse_args()
    if args.codes:
        cands = [(c.strip(), c[2:], "") for c in args.codes.split(",") if c.strip()]
    else:
        cands = DEFAULT_POOL
    q = quotes(cands)
    print("== 板块候选扫描（腾讯快照，数据截至各自最新）==")
    hdr = "%-8s %-8s %-8s %8s %7s %7s %7s %7s %7s %6s %6s %6s %6s  %s"
    print(hdr % ("名称", "代码", "方向", "现价", "5日%", "10日%", "20日%", "距60高%", "总市值", "静态PE", "PB", "换手", "量比", "形态"))
    kws = [k for k in (args.kw or "").split(",") if k]
    for secid, name, tag in cands:
        if kws and not any(k in name for k in kws):
            continue
        qd = q.get(secid)
        ks = kline_stats(secid)
        if not qd and not ks:
            print("%-8s 数据失败" % name)
            continue
        p = (qd or {}).get("price")
        mcap = (qd or {}).get("mcap_yi")
        pe = (qd or {}).get("pe_static")
        pb = (qd or {}).get("pb")
        turn = (qd or {}).get("turn")
        vr = (ks or {}).get("vr") or (qd or {}).get("vr")
        f5 = "%.1f" % ks["chg5"] if ks and ks["chg5"] is not None else "-"
        f10 = "%.1f" % ks["chg10"] if ks and ks["chg10"] is not None else "-"
        f20 = "%.1f" % ks["chg20"] if ks and ks["chg20"] is not None else "-"
        dd = "%.1f" % ks["dd60"] if ks else "-"
        print(hdr % (name, secid[2:], tag[:7],
                     ("%.2f" % p) if p else "-", f5, f10, f20, dd,
                     ("%.0f" % mcap) if mcap else "-",
                     ("%.1f" % pe) if pe and pe > 0 else "亏/失",
                     ("%.2f" % pb) if pb else "-",
                     ("%.1f" % turn) if turn else "-",
                     ("%.1f" % vr) if vr else "-",
                     (ks or {}).get("shape", "-")))


if __name__ == "__main__":
    main()
