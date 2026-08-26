# -*- coding: utf-8 -*-
"""上证指数周K全景图(2012-2026):拉取缺失历史 + 拼接缓存,标注历次牛熊顶底"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from backtest_guangmo import fetch_east  # noqa: E402

CACHE = os.path.join(os.path.dirname(__file__), "_kline_cache.json")
OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "上证指数周K_2012-2026.png")


def load_history():
    """腾讯分段拉 2012-06 ~ 2022-07(640根/段),拼接缓存 2022-08 ~ 2026-08"""
    from backtest_guangmo import fetch_kline
    segs = [("2012-06-01", "2014-12-31"), ("2015-01-01", "2017-06-30"),
            ("2017-07-01", "2019-12-31"), ("2020-01-01", "2022-07-31")]
    hist = []
    for beg, end in segs:
        name, snaps, src = fetch_kline("sh000001", beg=beg, end=end)
        if snaps:
            print(f"  腾讯拉取 {beg} ~ {end}: {len(snaps)} 根 ({src})")
            hist.extend(snaps)
    cache = json.load(open(CACHE, encoding="utf-8"))
    snaps2 = cache["sh000001"]["snaps"]
    if hist and snaps2:
        cut = [s for s in hist if s["date"] < snaps2[0]["date"]]
        return cut + snaps2
    return hist or snaps2


def weekly(snaps):
    """日K聚合为周K"""
    from collections import OrderedDict
    wk = OrderedDict()
    for s in snaps:
        y, m, d = s["date"].split("-")
        iso = __import__("datetime").date(int(y), int(m), int(d)).isocalendar()
        key = f"{iso[0]}-W{iso[1]:02d}"
        if key not in wk:
            wk[key] = {"date": s["date"], "open": s["open"], "high": s["high"],
                       "low": s["low"], "close": s["close"], "volume": s.get("volume", 0)}
        else:
            w = wk[key]
            w["high"] = max(w["high"], s["high"])
            w["low"] = min(w["low"], s["low"])
            w["close"] = s["close"]
            w["volume"] += s.get("volume", 0)
    return list(wk.values())


def main():
    snaps = load_history()
    print(f"历史K线 {snaps[0]['date']} ~ {snaps[-1]['date']} 共 {len(snaps)} 根")
    wk = weekly(snaps)
    print(f"周K {len(wk)} 根")

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Rectangle
    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
    plt.rcParams["axes.unicode_minus"] = False

    dates = [w["date"] for w in wk]
    opens = [w["open"] for w in wk]
    highs = [w["high"] for w in wk]
    lows = [w["low"] for w in wk]
    closes = [w["close"] for w in wk]

    def sma(xs, n):
        return [sum(xs[max(0, i - n + 1):i + 1]) / min(n, i + 1) for i in range(len(xs))]

    ma20 = sma(closes, 20)
    ma60 = sma(closes, 60)

    fig, ax = plt.subplots(figsize=(20, 11), dpi=110)
    n = len(wk)
    width = 0.6
    for i in range(n):
        up = closes[i] >= opens[i]
        color = "#d93636" if up else "#0a7a42"
        ax.bar(i, max(highs[i] - lows[i], 0.01), bottom=lows[i], width=width,
               color=color, edgecolor=color, linewidth=0.5)
        ax.bar(i, max(abs(closes[i] - opens[i]), 0.01), bottom=min(opens[i], closes[i]),
               width=width, color=color, edgecolor=color)
    ax.plot(ma20, color="#f5a623", lw=1.3, label="MA20(周)")
    ax.plot(ma60, color="#1f4fd8", lw=1.3, label="MA60(周)")

    # 关键牛熊顶底标注
    marks = [
        ("2015-06-12", 5178, "2015杠杆牛顶\n5178", "top"),
        ("2016-01-29", 2638, "股灾+熔断\n低2638", "bot"),
        ("2018-01-26", 3587, "2018顶\n3587", "top"),
        ("2019-01-04", 2440, "2019低\n2440", "bot"),
        ("2021-02-19", 3731, "2021抱团牛顶\n3731", "top"),
        ("2024-02-05", 2635, "2024大底\n2635", "bot"),
        ("2026-08-24", 3882, "当前\n3882", "cur"),
    ]
    idx_of = {w["date"]: i for i, w in enumerate(wk)}
    for date, price, label, kind in marks:
        for key in list(idx_of.keys()):
            if key >= date:
                i = idx_of[key]
                break
        if kind in ("top", "cur"):
            ax.plot(i, price, "v", color="#c0392b", ms=9, zorder=5)
            ax.annotate(label, xy=(i, price), xytext=(i + 2, price * 1.02),
                        fontsize=10, color="#c0392b", fontweight="bold")
        else:
            ax.plot(i, price, "^", color="#1e8449", ms=9, zorder=5)
            ax.annotate(label, xy=(i, price), xytext=(i + 2, price * 0.94),
                        fontsize=10, color="#1e8449", fontweight="bold")

    # 牛熊区间色带
    bands = [
        ("2014-06-01", "2015-06-30", "#c0392b", "杠杆牛"),
        ("2015-06-30", "2016-03-01", "#7f8c8d", "股灾/熔断"),
        ("2019-01-01", "2021-03-01", "#c0392b", "结构牛"),
        ("2021-03-01", "2024-03-01", "#7f8c8d", "三年阴跌"),
        ("2024-09-01", "2026-08-24", "#e67e22", "本轮行情"),
    ]
    for beg, end, color, label in bands:
        for k in idx_of:
            if k >= beg:
                i0 = idx_of[k]
                break
        for k in reversed(list(idx_of.keys())):
            if k <= end:
                i1 = idx_of[k]
                break
        ax.axvspan(i0, i1, color=color, alpha=0.06)

    step = max(1, n // 12)
    ax.set_xticks(range(0, n, step))
    ax.set_xticklabels([dates[i][:7] for i in range(0, n, step)], rotation=45, fontsize=9)
    ax.set_title("上证指数 周K 2012-06 ~ 2026-08  (牛熊全景)", fontsize=16, fontweight="bold")
    ax.legend(loc="upper left", fontsize=11)
    ax.grid(alpha=0.25)
    fig.tight_layout()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    fig.savefig(OUT)
    print("已保存:", os.path.abspath(OUT))


if __name__ == "__main__":
    main()
