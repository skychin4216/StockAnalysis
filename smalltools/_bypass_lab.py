# -*- coding: utf-8 -*-
"""选股胜率归因实验（2026-09-19 用户需求：成功率仅 ~32%，立刻优化）。

思路：不重跑历史 DAG（太慢），而是对**已入选台账**（_pick_ledger.jsonl）按可历史复现的
维度分组，算 T+1/T+3/T+5 胜率与均值 → 找出显著拖累胜率的分组 → 据此决定
「bypass 哪些 node / 加哪道闸」，并模拟「剔除该分组后」的胜率改善。

维度（全部可由 data/kline_store.json 复现，无未来函数）：
  · period  周期（超短/短线/中线/长线）
  · src     来源（dag / smalltool / etf / round / prepared）
  · 追高    入选当日涨幅（>5% / 0~5% / <0）
  · 位置    距60日高（≥-5% / -5~-20% / <-20%）
  · 趋势    入选前20日涨幅（>10% / 0~10% / <0，追强 vs 抄底）
  · 大盘    入选日上证涨跌（上涨日 / 下跌日）

用法：
  python _bypass_lab.py --days 12
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "assets", "usecases"))

LEDGER = os.path.join(HERE, "_records", "_pick_ledger.jsonl")


def _load_ledger(days):
    """近 days 条台账 → [{asof, picks:[{secid, period, close, src, name}]}]"""
    if not os.path.exists(LEDGER):
        return []
    lines = [l for l in open(LEDGER, encoding="utf-8").read().splitlines() if l.strip()]
    out = []
    for l in lines[-days:]:
        try:
            out.append(json.loads(l))
        except ValueError:
            continue
    return out


def _snaps_of(store, secid):
    e = store.get(secid) or store.get(secid[2:]) or {}
    return e.get("snaps") or []


def _bar(kmap, d):
    return kmap.get(d)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=12)
    args = ap.parse_args()

    from _kline_store import load_store
    store = load_store() or {}
    idx = "sh000001"
    bench = {str(s.get("date"))[:10]: s for s in _snaps_of(store, idx)}

    ledgers = _load_ledger(args.days)
    if not ledgers:
        print("台账为空:", LEDGER)
        return

    recs = []          # 每条入选记录 + 特征 + T+n 涨跌
    for lg in ledgers:
        asof = lg.get("asof") or ""
        b = bench.get(asof) or {}
        mkt_up = (b.get("changePct") or 0) > 0
        for p in (lg.get("picks") or []):
            sid = p.get("secid") or ""
            snaps = _snaps_of(store, sid)
            if not snaps:
                continue
            kmap = {str(s.get("date"))[:10]: s for s in snaps}
            days = sorted(kmap)
            if asof not in kmap:
                continue
            i0 = days.index(asof)
            base = p.get("close") or kmap[asof].get("close")
            if not base:
                continue
            after = days[i0 + 1:i0 + 6]
            fwd = {}
            for n in (1, 3, 5):
                fwd[n] = ((kmap[after[n - 1]]["close"] / base - 1) * 100
                          if len(after) >= n else None)
            # 特征（只用入选日及之前的数据）
            d0 = kmap[asof]
            chg0 = d0.get("changePct") or 0.0
            win60 = days[max(0, i0 - 59):i0 + 1]
            hi60 = max((kmap[d].get("high") or kmap[d].get("close") or 0) for d in win60)
            pos60 = ((d0.get("close") / hi60 - 1) * 100) if hi60 else 0.0
            d20 = days[max(0, i0 - 20):i0 + 1]
            r20 = ((d0.get("close") / kmap[d20[0]]["close"] - 1) * 100
                   if d20 and kmap[d20[0]].get("close") else 0.0)
            recs.append({
                "asof": asof, "sid": sid, "name": p.get("name") or sid,
                "period": p.get("period") or "—", "src": p.get("src") or "—",
                "chg0": chg0, "pos60": pos60, "r20": r20, "mkt_up": mkt_up,
                "fwd1": fwd[1], "fwd3": fwd[3], "fwd5": fwd[5],
            })

    def stat(rows, n):
        key = "fwd%d" % n
        v = [r[key] for r in rows if r.get(key) is not None]
        if not v:
            return (0, 0.0, 0.0)
        win = sum(1 for x in v if x > 0) / len(v) * 100
        return (len(v), win, sum(v) / len(v))

    print("样本 %d 条（近%d个台账日）" % (len(recs), len(ledgers)))
    print("\n=== 总体 ===")
    for n in (1, 3, 5):
        c, w, a = stat(recs, n)
        print("  T+%d  样本%d  胜率 %.0f%%  均值 %+.2f%%" % (n, c, w, a))

    def group(title, fn):
        print("\n=== %s ===" % title)
        g = {}
        for r in recs:
            g.setdefault(fn(r), []).append(r)
        for k in sorted(g, key=lambda x: str(x)):
            c1, w1, a1 = stat(g[k], 1)
            c3, w3, a3 = stat(g[k], 3)
            c5, w5, a5 = stat(g[k], 5)
            print("  %-14s n=%-4d T+1 %3.0f%%/%+.2f%%  T+3 %3.0f%%/%+.2f%%  T+5 %3.0f%%/%+.2f%%"
                  % (str(k)[:14], len(g[k]), w1, a1, w3, a3, w5, a5))

    group("周期 period", lambda r: r["period"])
    group("来源 src", lambda r: r["src"])
    group("入选当日涨幅（追高）",
          lambda r: ">5% 急拉" if r["chg0"] > 5 else ("0~5%" if r["chg0"] > 0 else "≤0 下跌中接"))
    group("距60日高位置",
          lambda r: "≥-5% 高位" if r["pos60"] >= -5 else ("-5~-20%" if r["pos60"] >= -20 else "<-20% 深跌"))
    group("入选前20日涨幅（趋势）",
          lambda r: ">10% 强势" if r["r20"] > 10 else ("0~10%" if r["r20"] > 0 else "<0 弱势"))
    group("大盘环境", lambda r: "上涨日" if r["mkt_up"] else "下跌日")

    # ── 模拟「加闸」：排除低胜率特征组后的改善 ──
    print("\n=== 模拟优化（排除拖累组） ===")
    rules = [
        ("排除急拉(当日>5%)", lambda r: r["chg0"] <= 5),
        ("排除高位(距60高≥-5%)", lambda r: r["pos60"] < -5),
        ("排除弱势(r20<0)", lambda r: r["r20"] >= 0),
        ("排除下跌日入选", lambda r: r["mkt_up"]),
        ("急拉+高位同时排除", lambda r: r["chg0"] <= 5 and r["pos60"] < -5),
        ("急拉+弱势同时排除", lambda r: r["chg0"] <= 5 and r["r20"] >= 0),
        ("急拉+高位+弱势", lambda r: r["chg0"] <= 5 and r["pos60"] < -5 and r["r20"] >= 0),
    ]
    base1, base3, base5 = stat(recs, 1), stat(recs, 3), stat(recs, 5)
    print("  %-20s 剩%4d  T+1 %3.0f%%(%+.1fpp)  T+3 %3.0f%%(%+.1fpp)  T+5 %3.0f%%(%+.1fpp)"
          % ("基线", base1[0], base1[1], 0.0, base3[1], 0.0, base5[1], 0.0))
    for name, keep in rules:
        sub = [r for r in recs if keep(r)]
        c1, w1, a1 = stat(sub, 1)
        c3, w3, a3 = stat(sub, 3)
        c5, w5, a5 = stat(sub, 5)
        print("  %-20s 剩%4d  T+1 %3.0f%%(%+.1fpp)  T+3 %3.0f%%(%+.1fpp)  T+5 %3.0f%%(%+.1fpp)"
              % (name, len(sub), w1, w1 - base1[1], w3, w3 - base3[1], w5, w5 - base5[1]))


if __name__ == "__main__":
    main()
