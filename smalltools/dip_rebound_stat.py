# -*- coding: utf-8 -*-
"""
大盘抄底信号回溯统计（2015-01 ~ 2026-09-07）
================================================
数据：smalltools/_kline_cache.json（上证 sh000001 / 深成 sz399001 / 创业板 sz399006 /
      科创50 sh000688 + 215 只龙头股，含 name 全量）

回答：
  1. 上证/科创50 连跌 D 天后次日买入(close→close) 未来 H 日反弹概率/幅度 → 找拐点。
  2. 「下跌中十字星(分歧)→ 次日大阴线(恐慌)」信号后指数反弹 vs 直接恐慌(无星)。
  3. 恐慌日个股回弹：前期热门(前30%)且自身连跌 vs 全体/冷门。

口径：买入=信号日收盘，卖出=第 H 日收盘。十字星 |实体|≤0.2×振幅；
缩量=量≤前5日均量×0.9。样本 2015-01-05 起。
"""
import json
import os
import random
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "_kline_cache.json")
OUT = os.path.join(os.path.dirname(HERE), "AutoQuant", "backtest_logs", "_dip_rebound_report.md")
IDX = {"sh000001": "上证指数", "sh000688": "科创50", "sz399001": "深证成指", "sz399006": "创业板指"}
START = "2015-01-01"
HORIZONS = [1, 2, 3, 5, 10]


def load():
    with open(CACHE, encoding="utf-8") as f:
        return json.load(f)


def seq_of(cache, code):
    snaps = sorted((cache.get(code) or {}).get("snaps") or [], key=lambda s: s["date"])
    return [s for s in snaps if s["date"] >= START]


def ma_n(seq, i, n=5):
    if i < n:
        return None
    vals = [seq[j].get("volume") for j in range(i - n, i) if seq[j].get("volume")]
    return sum(vals) / len(vals) if len(vals) == n else None


def chg(s):
    return s.get("changePct")


def is_doji(s, thr=0.20):
    hi, lo = s.get("high"), s.get("low")
    if not hi or not lo or hi <= lo:
        return False
    return abs(s.get("close", 0) - s.get("open", 0)) <= thr * (hi - lo)


def down_days(seq, i, cap=12):
    """从第 i 日(含)往回连续下跌天数"""
    n = 0
    for j in range(i, max(i - cap, -1), -1):
        c = chg(seq[j])
        if c is None or c >= 0:
            break
        n += 1
    return n


def fwd_ret(seq, i, h):
    if i + h >= len(seq):
        return None
    return (seq[i + h]["close"] / seq[i]["close"] - 1) * 100


def pct(lst):
    if not lst:
        return "-"
    w = sum(1 for x in lst if x > 0) / len(lst) * 100
    return "%.0f%%(%+.2f)" % (w, sum(lst) / len(lst))


def main():
    cache = load()
    L = []

    def out(*a):
        L.append(" ".join(str(x) for x in a))

    out("# 大盘抄底信号回溯统计（2015-01 ~ 2026-09-07）")
    out("")
    out("> 买入口径=信号日收盘买入、第 H 日收盘卖出(close→close)。十字星=|实体|≤0.2×振幅；")
    out("> 缩量=量≤前5日均量×0.9。")
    out("")

    stock_pool = None
    for code, cname in IDX.items():
        seq = seq_of(cache, code)
        n = len(seq)
        if n < 80:
            continue
        out("## %s %s（%d 交易日 %s→%s）" % (cname, code, n, seq[0]["date"], seq[-1]["date"]))
        out("")

        # ---------- A 连跌 D 天后次日买入 ----------
        out("### A. 连跌 D 天 → 次日买入 未来 H 日反弹（胜率/均值）")
        out("| 连跌天数 | 样本 | H=1 | H=2 | H=3 | H=5 | H=10 |")
        out("|---|--:|--:|--:|--:|--:|--:|")
        for D in range(1, 9):
            rets = {h: [] for h in HORIZONS}
            cnt = 0
            for i in range(D, n - 10):
                if down_days(seq, i) >= D:
                    cnt += 1
                    for h in HORIZONS:
                        r = fwd_ret(seq, i, h)
                        if r is not None:
                            rets[h].append(r)
            row = [str(D), str(cnt)] + [pct(rets[h]) for h in HORIZONS]
            out("| " + " | ".join(row) + " |")
        out("")

        # ---------- B 恐慌信号（大阴线）事件，含十字星前置变体 ----------
        out("### B. 恐慌日(大阴线)信号 → 之后反弹（形态×跌幅阈值×前置十字星）")
        out("| 形态 | 样本 | H=1 | H=2 | H=3 | H=5 | H=10 |")
        out("|---|--:|--:|--:|--:|--:|--:|")
        confs = []
        for thr in (-1.5, -2.0, -3.0):
            confs.append(("大阴线≤%.1f%%" % thr, thr, False, False))
            confs.append(("≤%.1f%%且前日十字星" % thr, thr, True, False))
            confs.append(("≤%.1f%%且前日十字星+缩量" % thr, thr, True, True))
        ev_meta = {}
        for ename, thr, need_doji, need_shrink in confs:
            rets = {h: [] for h in HORIZONS}
            days = []
            for i in range(6, n - 10):
                c = chg(seq[i])
                if c is None or c > thr:
                    continue
                if need_doji:
                    sp = seq[i - 1]
                    if not is_doji(sp):
                        continue
                    if need_shrink:
                        vp = ma_n(seq, i - 1)
                        if not vp or sp.get("volume", 0) > vp * 0.9:
                            continue
                for h in HORIZONS:
                    r = fwd_ret(seq, i, h)
                    if r is not None:
                        rets[h].append(r)
                days.append((seq[i]["date"], c, down_days(seq, i - 1)))
            ev_meta[ename] = (days, rets)
            row = [ename, "%d" % len(days)] + [pct(rets[h]) for h in HORIZONS]
            out("| " + " | ".join(row) + " |")
        out("")
        # 逐年分布 for 关键形态
        for key in ("≤-3.0%%且前日十字星+缩量", "≤-1.5%%且前日十字星+缩量"):
            key = key.replace("%%", "%")
            if key not in ev_meta:
                continue
            days, rets = ev_meta[key]
            by_year = Counter(d[:4] for d, _, _ in days)
            if days:
                out("  「%s」共 %d 次，按年: %s" % (key, len(days), dict(sorted(by_year.items()))))
                out("  其中后3日样本: 胜率=%s 均值=%+.2f%%" %
                    (pct(rets[3]), sum(rets[3]) / len(rets[3]) if rets[3] else 0))
                out("  事件日(近15): %s" % ", ".join("%s(%.1f%%)" % (d, c) for d, c, _ in days[-15:]))
                out("")
        # ---------- C 恐慌日个股回弹（热门 vs 冷门 vs 连跌）----------
        ev_days = []
        for i in range(6, n - 10):
            c = chg(seq[i])
            if c is None or c > -1.5:
                continue
            if down_days(seq, i - 1) >= 3:
                ev_days.append((i, seq[i]["date"]))
        out("### C. 恐慌日(≤-1.5%% 且连跌≥3)后个股回弹：热门(前60日强势前30%%) vs 冷门(后50%%) vs 自身连跌")
        out("样本上限 80 事件/指数（随机抽样）")
        out("| 分组 | 样本 | H=1 | H=2 | H=3 | H=5 | H=10 |")
        out("|---|--:|--:|--:|--:|--:|--:|")
        if stock_pool is None:
            stock_pool = [(k, e) for k, e in cache.items()
                          if k not in IDX and e.get("snaps") and len(e["snaps"]) > 200]
        random.seed(7)
        pick = random.sample(ev_days, min(len(ev_days), 80))
        agg = {"全体": {h: [] for h in HORIZONS},
               "热门(前30%)": {h: [] for h in HORIZONS},
               "热门且自身连跌≥2": {h: [] for h in HORIZONS},
               "热门且自身连跌≥3": {h: [] for h in HORIZONS},
               "冷门(后50%)": {h: [] for h in HORIZONS},
               "非热门连跌≥3": {h: [] for h in HORIZONS}}
        got_stock_ev = 0
        for (i, d) in pick:
            per = {k: [] for k in agg}  # code → ret
            codes = []
            for k, e in stock_pool:
                sd = [s for s in e["snaps"] if s["date"] >= START]
                if len(sd) < 75:
                    continue
                pos = next((j for j, s in enumerate(sd) if s["date"] == d), None)
                if pos is None or pos < 65 or pos + 10 >= len(sd):
                    continue
                codes.append((k, e.get("name", ""), sd, pos))
            if not codes:
                continue
            got_stock_ev += 1
            ret60 = [ (sd[pos]["close"] / sd[pos - 60]["close"] - 1) * 100 for _, _, sd, pos in codes]
            ret60 = sorted(ret60)
            hi = ret60[max(0, int(len(ret60) * 0.7) - 1)]
            lo = ret60[max(0, int(len(ret60) * 0.5) - 1)]
            hot = {}
            cold = {}
            for k, nm, sd, pos in codes:
                r60 = (sd[pos]["close"] / sd[pos - 60]["close"] - 1) * 100
                if r60 >= hi:
                    hot[k] = True
                if r60 < lo:
                    cold[k] = True
            for k, nm, sd, pos in codes:
                base = sd[pos]["close"]
                dk = 0
                for j in range(pos, max(pos - 12, -1), -1):
                    if chg(sd[j]) is not None and chg(sd[j]) < 0:
                        dk += 1
                    else:
                        break
                r60 = (sd[pos]["close"] / sd[pos - 60]["close"] - 1) * 100
                grp = []
                if k in hot:
                    grp.append("热门(前30%)")
                    if dk >= 2:
                        grp.append("热门且自身连跌≥2")
                    if dk >= 3:
                        grp.append("热门且自身连跌≥3")
                if k in cold:
                    grp.append("冷门(后50%)")
                if dk >= 3 and k not in hot:
                    grp.append("非热门连跌≥3")
                if not grp:
                    continue
                for h in HORIZONS:
                    if pos + h < len(sd):
                        r = (sd[pos + h]["close"] / base - 1) * 100
                        for g in grp:
                            agg[g][h].append(r)
                            agg["全体"][h].append(r)
        for g in agg:
            row = [g, "%d" % len(agg[g][HORIZONS[0]])] + [pct(agg[g][h]) for h in HORIZONS]
            out("| " + " | ".join(row) + " |")
        out("")
        out("（事件=%d 有数据日=%d）" % (len(ev_days), got_stock_ev))
        out("---")
        out("")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(L))
    print("已写出: %s (%d 行)" % (OUT, len(L)))
    print("\n".join(L))


if __name__ == "__main__":
    main()
