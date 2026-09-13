# -*- coding: utf-8 -*-
"""今日大盘综合分析：实时涨跌 + 分时走势 + 日线技术指标(MA/MACD/RSI/量能)"""
import sys, json, datetime
sys.path.insert(0, "e:/Android/work/dev/StockAnalysis/smalltools")
import requests

HEADERS = {"User-Agent": "Mozilla/5.0"}
PROXIES = {"http": None, "https": None}

INDEXES = {
    "1.000001": "上证指数",
    "0.399001": "深证成指",
    "0.399006": "创业板指",
}


def fetch_realtime():
    """腾讯实时行情：现价/昨收/今开/涨跌/涨跌%/成交额(万)"""
    codes = ["sh000001", "sz399001", "sz399006"]
    try:
        r = requests.get("https://qt.gtimg.cn/q=" + ",".join(codes),
                         timeout=10, headers=HEADERS, proxies=PROXIES)
        r.encoding = "gbk"
        out = []
        for line in r.text.strip().split(";"):
            if "=" not in line:
                continue
            payload = line.split("=", 1)[1].strip().strip('"')
            f = payload.split("~")
            if len(f) < 38:
                continue
            out.append({
                "name": f[1], "price": f[3], "prev": f[4], "open": f[5],
                "chg": f[31], "pct": f[32], "high": f[33], "low": f[34],
                "amount": f[37],
            })
        return out
    except Exception:
        return []


def to_east_secid(secid):
    if secid.startswith("sz"):
        return "0." + secid[2:]
    return "1." + secid[2:]


def fetch_m5(secid, end):
    params = {
        "secid": to_east_secid(secid), "klt": "5", "fqt": "1",
        "beg": "20260820", "end": end, "lmt": "600",
        "fields1": "f1,f2,f3,f4,f5,f6",
        "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
    }
    for host in ["https://push2his.eastmoney.com", "https://90.push2his.eastmoney.com",
                 "https://92.push2his.eastmoney.com"]:
        try:
            r = requests.get(host + "/api/qt/stock/kline/get", params=params,
                             timeout=10, headers=HEADERS, proxies=PROXIES)
            data = r.json()
            if data.get("data") and data["data"].get("klines"):
                return data["data"]["klines"]
        except Exception:
            pass
    return []


def calc_indicators(snaps):
    """基于日线快照算 MA5/MA10/MA20、MACD(12,26,9)、RSI14、量能比"""
    if not snaps:
        return None
    closes = [s["close"] for s in snaps]
    vols = [s.get("volume", 0) for s in snaps]
    n = len(closes)
    res = {}
    for label, w in [("ma5", 5), ("ma10", 10), ("ma20", 20)]:
        res[label] = sum(closes[-w:]) / w if n >= w else None
    # MACD
    ema12, ema26, dif, dea, macd = None, None, 0.0, 0.0, 0.0
    for i, c in enumerate(closes):
        ema12 = c if ema12 is None else ema12 * 11 / 13 + c * 2 / 13
        ema26 = c if ema26 is None else ema26 * 25 / 27 + c * 2 / 27
        if ema12 is not None and ema26 is not None:
            dif = ema12 - ema26
            dea = dea * 8 / 10 + dif * 2 / 10
            macd = (dif - dea) * 2
    res["dif"], res["dea"], res["macd"] = dif, dea, macd
    # RSI14
    if n > 14:
        gains, losses = [], []
        for i in range(n - 14, n):
            diff = closes[i] - closes[i - 1]
            gains.append(max(diff, 0))
            losses.append(max(-diff, 0))
        ag = sum(gains) / 14
        al = sum(losses) / 14
        res["rsi14"] = 100 - 100 / (1 + ag / al) if al > 0 else 100.0
    else:
        res["rsi14"] = None
    # 量能比：近5日均量 / 前20日均量
    if n >= 25:
        res["vol_ratio"] = sum(vols[-5:]) / 5 / max(sum(vols[-25:-5]) / 20, 1)
    else:
        res["vol_ratio"] = None
    return res


def main():
    print("=" * 58)
    print(f"今日大盘综合分析  时间: {datetime.datetime.now():%Y-%m-%d %H:%M}")
    print("=" * 58)
    # 1) 实时行情
    diffs = fetch_realtime()
    print("\n【实时行情】")
    for d in diffs or []:
        print(f"  {d['name']:<8} 现价 {d['price']:>10}  昨收 {d['prev']:>10}  "
              f"涨跌 {d['chg']:>9}  {d['pct']}%  成交额 {d['amount']}万")
    if not diffs:
        print("  (实时行情获取失败)")

    # 2) 分时走势（上证今日5分钟）
    today = datetime.date.today().strftime("%Y%m%d")
    klines = fetch_m5("sh000001", today)
    if klines:
        print("\n【上证指数 今日5分钟分时】")
        day = klines[0].split(",")[0][:10]
        today_ks = [k for k in klines if k.split(",")[0].startswith(day)]
        if today_ks:
            prev_close = float(today_ks[0].split(",")[2])
            first = float(today_ks[0].split(",")[1])
            print(f"  昨收 {prev_close:.2f}  今开 {first:.2f} ({(first - prev_close) / prev_close * 100:+.2f}%)")
            # 用分时高低点还原盘中轨迹
            seq = today_ks[::12] or today_ks[-1:]
            for k in seq[-6:]:
                p = k.split(",")
                t, o, c, h, l = p[0][11:16], float(p[1]), float(p[2]), float(p[3]), float(p[4])
                print(f"    {t}  O {o:.2f} C {c:.2f} H {h:.2f} L {l:.2f} ({(c - prev_close) / prev_close * 100:+.2f}%)")
            last = float(today_ks[-1].split(",")[2])
            print(f"  最新 {last:.2f} ({(last - prev_close) / prev_close * 100:+.2f}%)  5min数={len(today_ks)}")
    else:
        print("\n【分时】今日5分钟K线暂无（开盘前或接口无数据）")

    # 3) 日线技术指标（缓存）
    cache = json.load(open("_kline_cache.json", encoding="utf-8"))
    print("\n【日线技术指标】(截至缓存最新)")
    for code, label in {"sh000001": "上证指数", "sz399001": "深证成指", "sz399006": "创业板指"}.items():
        ent = cache.get(code)
        if not ent:
            continue
        snaps = ent.get("snaps", [])
        ind = calc_indicators(snaps)
        if not ind:
            continue
        last = snaps[-1]
        print(f"  {label:<6} 最新 {last['date']} 收 {last['close']:.2f} "
              f"MA5 {ind['ma5']:.2f} MA10 {ind['ma10']:.2f} MA20 {ind['ma20']:.2f} "
              f"MACD {ind['macd']:.3f} DIF {ind['dif']:.3f} DEA {ind['dea']:.3f} "
              f"RSI14 {ind['rsi14']:.1f} 量比 {ind['vol_ratio']:.2f}")

    print("=" * 58)


if __name__ == "__main__":
    main()
