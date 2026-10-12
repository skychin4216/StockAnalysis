# -*- coding: utf-8 -*-
"""
SSA 底买顶卖 —— 东财 1 小时K（复用 _ssa_tdx.py 的分解/回测，保证同一套算法）
用法:
  python smalltools/_ssa_tdx_1h.py 600176
  python smalltools/_ssa_tdx_1h.py 600176 --L 30 --stop-side down --stop-band red --stop-confirm close --png
"""
import argparse
import json
import os
import sys
import urllib.request
from datetime import datetime

import numpy as np

import importlib.util
spec = importlib.util.spec_from_file_location("tdx", os.path.join(os.path.dirname(__file__), "_ssa_tdx.py"))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


def fetch_hourly(code, lmt=1023):
    """新浪 60 分钟K（东财 push2his 本机不可达；腾讯仅 320 根）。
    返回原始价（未复权），除权日附近会有跳空，解读时留意。"""
    code = str(code).zfill(6)
    sym = ("sh" if code.startswith(("60", "68", "11", "13", "50", "51", "56", "58")) else "sz") + code
    url = ("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
           f"CN_MarketData.getKLineData?symbol={sym}&scale=60&ma=no&datalen={lmt}")
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0",
        "Referer": "https://finance.sina.com.cn/"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        rows = json.loads(resp.read().decode("utf-8", "ignore"))
    bars = []
    for p in rows:
        dt = datetime.strptime(p["day"], "%Y-%m-%d %H:%M:%S")
        bars.append(dict(date=dt.strftime("%Y%m%d"), time=dt.strftime("%H%M"),
                         dt=p["day"][:16],
                         open=float(p["open"]), close=float(p["close"]),
                         high=float(p["high"]), low=float(p["low"]),
                         volume=float(p["volume"])))
    return bars


def fetch_daily_sina(code, lmt=1023):
    """新浪日K（scale=240，未复权），用于补齐通达信 .day 缺失的近期数据。"""
    code = str(code).zfill(6)
    sym = ("sh" if code.startswith(("60", "68", "11", "13", "50", "51", "56", "58")) else "sz") + code
    url = ("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
           f"CN_MarketData.getKLineData?symbol={sym}&scale=240&ma=no&datalen={lmt}")
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0",
        "Referer": "https://finance.sina.com.cn/"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        rows = json.loads(resp.read().decode("utf-8", "ignore"))
    bars = []
    for p in rows:
        dt = datetime.strptime(p["day"], "%Y-%m-%d")
        bars.append(dict(date=dt.strftime("%Y%m%d"),
                         open=float(p["open"]), close=float(p["close"]),
                         high=float(p["high"]), low=float(p["low"]),
                         volume=float(p["volume"])))
    return bars


def iso_week(d):
    y, w, _ = datetime.strptime(d, "%Y%m%d").isocalendar()
    return y * 100 + w


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("code")
    ap.add_argument("--L", type=int, default=30)
    ap.add_argument("--N", type=int, default=20)
    ap.add_argument("--cm", type=float, default=1.0)
    ap.add_argument("--nm", type=float, default=1.0)
    ap.add_argument("--lmt", type=int, default=2000)
    ap.add_argument("--stop-side", choices=["both", "down", "none"], default="down")
    ap.add_argument("--stop-band", choices=["blue", "red"], default="red")
    ap.add_argument("--stop-confirm", choices=["tick", "close"], default="close")
    ap.add_argument("--ampK", type=float, default=0.0)
    ap.add_argument("--png", action="store_true")
    args = ap.parse_args()

    bars = fetch_hourly(args.code, args.lmt)
    if len(bars) < args.L + 30:
        print(f"小时K不足: {len(bars)}")
        sys.exit(1)

    r = m.run(bars, L=args.L, Nstd=args.N, cm=args.cm, nm=args.nm,
              strat="cycle", stop_side=args.stop_side, stop_band=args.stop_band,
              stop_confirm=args.stop_confirm, ampK=args.ampK)
    n = len(bars)
    trades = r["trades"]

    # 每周交易次数（按开仓日所在 ISO 周）
    open_weeks = {}
    for t in trades:
        w = iso_week(t["bd"])
        open_weeks[w] = open_weeks.get(w, 0) + 1
    span_weeks = len({iso_week(bars[i]["date"]) for i in range(n)})
    per_week = len(open_weeks) / max(span_weeks, 1)
    # 每天 1H K 数（A股通常4根）
    days = len({b["date"] for b in bars})

    print("=" * 86)
    print(f"1H 底买顶卖  {args.code}  {bars[0]['dt']} ~ {bars[-1]['dt']}")
    print(f"共 {n} 根1H K / {days} 个交易日 / {span_weeks} 个ISO周   "
          f"L={args.L} N={args.N} 兜底={'无' if args.stop_side=='none' else args.stop_band+'轨/'+args.stop_side+'/'+args.stop_confirm}")
    print("=" * 86)
    print(f"总收益 {r['total']:+.2f}%   年化 {r['annual']:+.2f}%   最大回撤 {r['mdd']:.2f}%   "
          f"买持 {r['benchPct']:+.2f}%   超额 {r['total']-r['benchPct']:+.2f}%")
    wr = len(r["wins"]) / len(trades) * 100 if trades else 0
    aw = np.mean([t["ret"] for t in r["wins"]]) if r["wins"] else 0
    al = np.mean([t["ret"] for t in r["losses"]]) if r["losses"] else 0
    pf = r["pf"]
    print(f"T单 {len(trades)} 笔  胜率 {wr:.1f}%  平均盈利 {aw:+.2f}%  平均亏损 {al:+.2f}%  "
          f"盈亏比 {'∞' if np.isinf(pf) else f'{pf:.2f}'}  平均持仓 {r['avgHold']:.1f} 根1H")
    print(f"有开仓的周 {len(open_weeks)}/{span_weeks}  →  平均每周开 T {per_week:.2f} 次"
          f"（有仓周平均 {np.mean(list(open_weeks.values())):.2f} 次/周）")
    reasons = {}
    for t in trades:
        k = "峰顶止盈" if "峰顶" in t["reason"] else ("兜底止损" if ("轨" in t["reason"]) else "其他/期末")
        reasons[k] = reasons.get(k, 0) + 1
    print("离场结构: " + "  ".join(f"{k} {v}笔" for k, v in reasons.items()))

    # 70/30 拆分（按1H根数切，参数全程固定）
    cut = int(n * 0.7)
    for nm_, lo, hi in (("训练集 70%", 0, cut), ("样本外 30%", cut, n)):
        seg_t = [t for t in trades if lo <= t["bi"] < hi]
        if not seg_t:
            print(f"  {nm_}: 无交易")
            continue
        seg_eq = r["equity"][lo:hi]
        seg_dd = ((seg_eq - np.maximum.accumulate(seg_eq)) / np.maximum.accumulate(seg_eq)).min() * 100
        w = sum(1 for t in seg_t if t["ret"] > 0) / len(seg_t) * 100
        avg = np.mean([t["ret"] for t in seg_t])
        wks = len({iso_week(t["bd"]) for t in seg_t})
        span = len({iso_week(bars[i]["date"]) for i in range(lo, hi)})
        print(f"  {nm_} {bars[lo]['dt']}~{bars[hi-1]['dt']}: {len(seg_t)}笔 胜率{w:.0f}% "
              f"笔均{avg:+.2f}% 回撤{seg_dd:.1f}% {wks}/{span}周有T")
    print("-" * 86)
    print(f"{'#':>3} {'买入时间':>17} {'买价':>8} {'卖出时间':>17} {'卖价':>8}{'收益':>8}{'持':>4} 原因")
    for i, t in enumerate(trades, 1):
        bt = bars[t["bi"]]["dt"]; st = bars[min(t["si"], n - 1)]["dt"]
        print(f"{i:>3} {bt:>17} {t['bp']:>8.2f} {st:>17} {t['sp']:>8.2f}"
              f"{t['ret']:>+7.2f}%{t['hold']:>4} {t['reason']}")

    if args.png:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        import matplotlib.dates as mdates
        plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
        plt.rcParams["axes.unicode_minus"] = False
        dts = [datetime.strptime(b["dt"], "%Y-%m-%d %H:%M") for b in bars]
        x = np.arange(n)
        fig, axes = plt.subplots(3, 1, figsize=(15, 10))
        ax = axes[0]
        ax.plot(x, r["close"], color="#222", lw=0.9)
        vx = ~np.isnan(r["nUp"])
        ax.fill_between(x[vx], r["nDn"][vx], r["nUp"][vx], color="gray", alpha=0.15)
        ax.plot(x, r["eUp"], color="red", lw=0.6, ls="--")
        ax.plot(x, r["eDn"], color="red", lw=0.6, ls="--")
        for idx, px, buy in r["markers"]:
            ax.plot(idx, px, marker="^" if buy else "v", ms=8,
                    mfc="red" if buy else "#2E7D32",
                    mec="red" if buy else "#2E7D32", mew=1.0, zorder=5)
        ax.axvline(cut, color="#7B1FA2", ls="--", lw=0.8)
        ax.set_title(f"{args.code} 1H 底买顶卖 L={args.L}（紫虚线=训练/样本外分界）")

        ax = axes[1]
        cy = np.where(np.isnan(r["cycleAt"]), np.nan, r["cycleAt"])
        ax.plot(x, cy, color="#7B1FA2", lw=0.9)
        ax.axhline(0, color="#999", lw=0.5, ls=":")
        for idx, px, buy in r["markers"]:
            ax.plot(idx, r["cycleAt"][idx], marker="^" if buy else "v", ms=7,
                    mfc="red" if buy else "#2E7D32",
                    mec="red" if buy else "#2E7D32", zorder=5)
        ax.set_title("1H cycle 周期分量：▲谷底买 ▼峰顶卖")

        ax = axes[2]
        ax.plot(x, r["equity"], color="#D81B60", lw=1.1, label="策略净值")
        ax.plot(x, r["bench"], color="#777", lw=0.9, label="买持基准")
        ax.axvline(cut, color="#7B1FA2", ls="--", lw=0.8)
        ax.legend(fontsize=8); ax.set_title("净值对比")
        for a in axes:
            tick_idx = np.linspace(0, n - 1, 12).astype(int)
            a.set_xticks(tick_idx)
            a.set_xticklabels([dts[i].strftime("%y-%m-%d\n%H:%M") for i in tick_idx], fontsize=6)
        plt.tight_layout()
        out = os.path.join("data", "review_tables",
                           f"ssa_{args.code}_1h_L{args.L}_{args.stop_side}_{args.stop_band}.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        plt.savefig(out, dpi=120)
        print(f"图已保存: {out}")


if __name__ == "__main__":
    main()
