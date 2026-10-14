# -*- coding: utf-8 -*-
"""三态做T 归因：按「入场条件 × 趋势环境」拆解 T 单盈亏，找出下跌趋势里的亏损买入。

数据来源（App 同款 SsaT0Engine 经 SsaT0PcBacktest 导出）：
  app/build/ssa_t0_layers.csv  三层区间/分量/scn
  app/build/ssa_t0_markers.csv 1H 买卖点（含入场原因）
  app/build/ssa_t0_trades.csv  全部 T 单（含开/平仓指数与收益）
用法: python smalltools\\_ssa_t0_diag.py [--csv ...] [--code 600176]
"""
import argparse
import csv
import os

import numpy as np


def load_layers(path):
    layers = {"W": [], "D": [], "H": []}
    with open(path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            layers[r["layer"]].append(r)
    out = {}
    for k, rows in layers.items():
        if not rows:
            continue
        d = {c: np.array([float(r[c]) for r in rows]) for c in
             ("open", "high", "low", "close", "trend", "cycle", "mid", "nUp", "nDn", "eUp", "eDn")}
        d["date"] = [r["date"] for r in rows]
        d["time"] = [r["time"] for r in rows]
        out[k] = d
    return out


def load_markers(path):
    """返回 {(index, round(price,2)): reason}，仅买入点。"""
    m = {}
    with open(path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if int(r["buy"]) == 1:
                m[(int(r["index"]), round(float(r["price"]), 2))] = r["reason"]
    return m


def load_trades(path):
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def day_idx_for(hd, tm, ddates):
    """1H 决策时可见的最新已收盘日K（与引擎同口径）。"""
    di = -1
    for i, dt in enumerate(ddates):
        if dt < hd or (dt == hd and tm >= "15:00"):
            di = i
        else:
            break
    return di


def regime_at(i, h):
    """小时线三态：cycle = mid - trend，>0 为 UP；<0 由拐点开/关窗定 WINDOW/FLAT。"""
    cy = h["mid"] - h["trend"]
    return 1 if cy[i] > 0 else 0  # 简化：先按符号给 UP/非UP（下方用完整窗口重算）


def regimes_full(h):
    cy = h["mid"] - h["trend"]
    n = len(cy)
    reg = np.zeros(n, int)
    w = False
    for i in range(n):
        if i < 2 or np.isnan(cy[i]) or np.isnan(cy[i - 1]) or np.isnan(cy[i - 2]):
            continue
        if cy[i - 2] < cy[i - 1] > cy[i] and cy[i - 1] > 0:
            w = False
        if cy[i - 2] > cy[i - 1] < cy[i] and cy[i - 1] < 0:
            w = True
        reg[i] = 1 if cy[i] > 0 else (3 if w else 2)
    return reg


REG_NAME = {1: "UP", 2: "FLAT", 3: "WINDOW"}


def agg(rows):
    if not rows:
        return (0, 0.0, 0.0, 0.0)
    r = np.array([x for x in rows])
    return (len(r), (r > 0).mean() * 100, r.mean(), r.sum())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--code", default="600176")
    ap.add_argument("--csv", default="app/build/ssa_t0_layers.csv")
    ap.add_argument("--markers", default="app/build/ssa_t0_markers.csv")
    ap.add_argument("--trades", default="app/build/ssa_t0_trades.csv")
    a = ap.parse_args()

    L = load_layers(a.csv)
    h, d, w = L["H"], L["D"], L["W"]
    reg = regimes_full(h)
    mk = load_markers(a.markers)
    trades = load_trades(a.trades)

    # day trend 斜率（用已收盘日K）+ 5根斜率
    dtr = d["trend"]
    dslope1 = np.full(len(dtr), np.nan)
    dslope5 = np.full(len(dtr), np.nan)
    for i in range(1, len(dtr)):
        if not (np.isnan(dtr[i]) or np.isnan(dtr[i - 1])):
            dslope1[i] = dtr[i] - dtr[i - 1]
    for i in range(5, len(dtr)):
        if not (np.isnan(dtr[i]) or np.isnan(dtr[i - 5])):
            dslope5[i] = dtr[i] - dtr[i - 5]

    recs = []
    for t in trades:
        oi = int(t["openIndex"]); px = round(float(t["openPrice"]), 2)
        cond = mk.get((oi, px), "?")
        hb = h["date"][oi]; tm = h["time"][oi]
        di = day_idx_for(hb, tm, d["date"])
        ddown1 = di >= 1 and not np.isnan(dslope1[di]) and dslope1[di] < 0
        ddown5 = di >= 5 and not np.isnan(dslope5[di]) and dslope5[di] < 0
        wup = t["weeklyUp"] == "1"
        recs.append(dict(cond=cond, ret=float(t["returnPct"]), reg=reg[oi],
                         ddown1=ddown1, ddown5=ddown5, wup=wup,
                         open=t["openTime"], close=t["closeTime"], exit=t["exitReason"],
                         bars=int(t["barsHeld"]), entry_px=px, close_px=float(t["closePrice"])))

    tot = [r["ret"] for r in recs]
    n, wr, avg, s = agg(tot)
    print(f"=== {a.code} T单归因（共 {n} 笔，胜率{wr:.1f}%，笔均{avg:+.2f}%，累计{s:+.1f}%）===")

    def show(title, keyfn):
        print(f"\n-- 按{title} --")
        groups = {}
        for r in recs:
            groups.setdefault(keyfn(r), []).append(r["ret"])
        for k in sorted(groups, key=lambda x: -len(groups[x])):
            n2, wr2, avg2, s2 = agg(groups[k])
            print(f"  {str(k):<26} {n2:>4}笔  胜率{wr2:5.1f}%  笔均{avg2:+6.2f}%  累计{s2:+8.1f}%")

    show("入场条件", lambda r: r["cond"])
    show("入场时小时线状态", lambda r: REG_NAME.get(r["reg"], "?"))
    show("入场时日线趋势(1根斜率方向)", lambda r: "日线下行" if r["ddown1"] else "日线上行")
    show("入场时日线趋势(5根斜率方向)", lambda r: "日线下行" if r["ddown5"] else "日线上行")
    show("入场时周线趋势", lambda r: "周线上行" if r["wup"] else "周线下行")

    # 下跌趋势里的亏损买入明细
    for label, key in (("日线5根下行", "ddown5"), ("日线1根下行", "ddown1")):
        bad = [r for r in recs if r[key] and r["ret"] < 0]
        ok = [r for r in recs if r[key]]
        n2, wr2, avg2, s2 = agg([r["ret"] for r in ok])
        nb = sum(r["ret"] for r in bad)
        print(f"\n== {label} 环境：{len(ok)}笔 胜率{wr2:.1f}% 笔均{avg2:+.2f}% 累计{s2:+.1f}%；"
              f"其中亏损{len(bad)}笔 合计{nb:+.1f}% ==")
        for r in sorted(bad, key=lambda x: x["ret"])[:20]:
            print(f"   {r['open']} 入{r['entry_px']:.2f} 出{r['close_px']:.2f} {r['ret']:+6.2f}% "
                  f"{r['bars']:>3}根 [{REG_NAME.get(r['reg'],'?')}] {r['cond']} → {r['exit']}")

    # 反事实：剔除「日线5根下行」的买入，剩余 T 单表现
    kept = [r["ret"] for r in recs if not r["ddown5"]]
    n3, wr3, avg3, s3 = agg(kept)
    print(f"\n== 反事实：剔除「日线5根下行」的 {len(recs)-n3} 笔买入后，剩余 {n3} 笔 "
          f"胜率{wr3:.1f}% 笔均{avg3:+.2f}% 累计{s3:+.1f}%（原累计{s:+.1f}%）==")


if __name__ == "__main__":
    main()
