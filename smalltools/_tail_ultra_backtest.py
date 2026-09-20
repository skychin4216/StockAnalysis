# -*- coding: utf-8 -*-
"""尾盘超短战法 walk-forward 回溯验证 —— 2026-09-20 用户需求。

## 判定口径（用户指定）

    买入价 base = 信号日**收盘价**（战法原文"14:50 满仓买入"，用收盘价近似）
    ✅ 成功     = T+1 ~ T+5 内**任一日最高价 ≥ base × 1.03**（给过 3%+ 空间即算不错）
    分层输出    = T+1 / T+2 / T+3 / T+4 / T+5 各日**累计**命中率

用户原话："只要 T+1 T+2 ... T+5 股价是原价的 1.03 倍以上都是不错的"——
只看有没有给出可兑现的 3% 空间，不纠结卖点。这比 tp/sl 结算口径更能反映选股质量。

## 性能

`_tail_ultra.detect()` 是单日函数，嵌套调用会退化成 679 只 × 4500 天 ≈ 300 万次
Python 循环（跑不动）。这里实现**批量版**：每只票先 O(n) 预计算均线/量比，再逐日判定，
阈值与 `detect()` 完全一致（同一 TH 表）。

## 用法

    python _tail_ultra_backtest.py                  # 近 3 年、严格档
    python _tail_ultra_backtest.py --years 18       # 全历史（2008 起）
    python _tail_ultra_backtest.py --loose          # 宽松档
    python _tail_ultra_backtest.py --compare        # 严格 vs 宽松 对比
    python _tail_ultra_backtest.py --compare --json data/tail_ultra_bt.json
"""
import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

TARGET = 1.03
HOLD = 5
TH = {
    False: {"burst": 2.5, "shrink": 0.70, "band": 0.012, "gap": 0.02, "min_hit": 5},
    True: {"burst": 2.0, "shrink": 0.85, "band": 0.025, "gap": 0.04, "min_hit": 4},
}


def _roll(vals, w):
    n = len(vals)
    out = [None] * n
    s = 0.0
    for i, x in enumerate(vals):
        s += x
        if i >= w:
            s -= vals[i - w]
        if i >= w - 1:
            out[i] = s / w
    return out


def scan_one(snaps, loose=False):
    """批量判定一只票 → [(idx, n_hit), ...]（信号日索引）。"""
    n = len(snaps)
    if n < 40:
        return []
    th = TH[loose]
    closes = [float(s.get("close") or 0) for s in snaps]
    opens = [float(s.get("open") or 0) for s in snaps]
    highs = [float(s.get("high") or 0) for s in snaps]
    lows = [float(s.get("low") or 0) for s in snaps]
    vols = [float(s.get("volume") or 0) for s in snaps]
    ma10, ma20 = _roll(closes, 10), _roll(closes, 20)
    v5, v20 = _roll(vols, 5), _roll(vols, 20)

    res = []
    for i in range(25, n):
        if not (ma10[i] and ma20[i] and v5[i - 1] and v20[i - 1] and ma10[i - 3]):
            continue
        c, o, lo, h, v = closes[i], opens[i], lows[i], highs[i], vols[i]
        if not (c and o and v):
            continue
        burst, bx = False, 0.0
        for k in range(max(0, i - 20), i):
            if v20[i - 1] > 0 and vols[k] / v20[i - 1] >= th["burst"]:
                burst = True
        shrink = v5[i - 1] > 0 and v <= v5[i - 1] * th["shrink"]
        touch = abs(lo - ma10[i]) / ma10[i] <= th["band"] or (lo <= ma10[i] <= max(h, c))
        bear = c < o and (loose or c < closes[i - 1])
        ma_up = (ma10[i] > ma10[i - 3]) if loose else \
            (ma10[i] > ma10[i - 1] and (ma10[i] / ma10[i - 3] - 1) > 0.0015)
        gap = abs(ma10[i] - ma20[i]) / ma20[i] if ma20[i] else 9.9
        near = gap <= th["gap"]
        n_hit = sum([burst, shrink, touch, bear, ma_up, near])
        if touch and bear and n_hit >= th["min_hit"]:
            res.append((i, n_hit))
    return res


def eval_signals(snaps, sigs):
    """按用户口径评估每笔信号（T+1~T+5 最高价 / base，及累计是否达标）。"""
    closes = [float(s.get("close") or 0) for s in snaps]
    highs = [float(s.get("high") or 0) for s in snaps]
    out = []
    for i, n_hit in sigs:
        base = closes[i]
        if not base or i + HOLD >= len(snaps):
            continue
        reach, cum, best = [], [], 0.0
        for k in range(1, HOLD + 1):
            r = highs[i + k] / base
            reach.append(round(r, 4))
            best = max(best, r)
            cum.append(best >= TARGET)
        out.append({"date": snaps[i].get("date"), "n_hit": n_hit,
                    "reach": reach, "cum": cum, "best": round(best, 4)})
    return out


def baseline(pool, store, years):
    """随机基线：**全池所有交易日**（不筛选）的 T+1~T+5 命中 1.03 比例。

    没有这个对照就无法判断战法是否有 alpha —— 若大盘/池子本身在涨，
    "随便买"也可能有 50%+ 命中。基线算法与战法评估完全同口径
    （同一 TARGET/HOLD/评估函数），只把"选出的信号"换成"全部交易日"。
    """
    n, hits = 0, [0] * HOLD
    step = 5                                    # 抽样步长（避免全量过慢）
    for secid in pool:
        snaps = (store.get(secid) or {}).get("snaps") or []
        if len(snaps) < 40:
            continue
        if years > 0:
            snaps = snaps[-min(len(snaps), years * 250 + 30):]
        sigs = [(i, 0) for i in range(25, len(snaps), step)]
        for r in eval_signals(snaps, sigs):
            n += 1
            for k in range(HOLD):
                if r["cum"][k]:
                    hits[k] += 1
    if not n:
        return None
    return {"n": n, "t_hit_pct": [round(x / n * 100, 1) for x in hits],
            "t5_hit_pct": round(hits[HOLD - 1] / n * 100, 1)}


def run(pool, store, loose, years, quiet=True):
    n_sig = n_eval = 0
    rows = []
    for secid in pool:
        snaps = (store.get(secid) or {}).get("snaps") or []
        if len(snaps) < 40:
            continue
        if years > 0:
            snaps = snaps[-min(len(snaps), years * 250 + 30):]
        sigs = scan_one(snaps, loose=loose)
        if not sigs:
            continue
        n_sig += len(sigs)
        ev = eval_signals(snaps, sigs)
        n_eval += len(ev)
        for r in ev:
            r["secid"] = secid
        rows.extend(ev)
    if not n_eval:
        return None, []
    cum_hit = [0] * HOLD
    for r in rows:
        for k in range(HOLD):
            if r["cum"][k]:
                cum_hit[k] += 1
    pct = [round(x / n_eval * 100, 1) for x in cum_hit]
    avg_reach = [round(sum(r["reach"][k] for r in rows) / n_eval, 4) for k in range(HOLD)]
    return {
        "loose": loose, "n_signal": n_sig, "n_eval": n_eval, "years": years,
        "t_hit_pct": pct, "t_avg_reach": avg_reach,
        "t5_hit_pct": pct[HOLD - 1],
        "avg_best": round(sum(r["best"] for r in rows) / n_eval, 4),
        "rows": rows,
    }, rows


def main():
    ap = argparse.ArgumentParser(description="尾盘超短战法 walk-forward 回溯")
    ap.add_argument("--years", type=int, default=3)
    ap.add_argument("--loose", action="store_true")
    ap.add_argument("--compare", action="store_true")
    ap.add_argument("--json", default="")
    ap.add_argument("--no-baseline", action="store_true", help="跳过随机基线对照")
    a = ap.parse_args()

    from _kline_store import load_store  # noqa: PLC0415
    store = load_store()
    pool = [k for k in sorted(store) if not k.startswith(("sh000", "sz399"))]
    print("池内 %d 只 | 回溯 %s | 口径 T+1~T+5 最高价 ≥ 买入价×%.2f" % (
        len(pool), ("全历史" if a.years >= 18 else "近 %d 年" % a.years), TARGET))

    out_all = {}
    base = None
    if not a.no_baseline:
        base = baseline(pool, store, a.years)
        if base:
            out_all["随机基线"] = base
            print("\n【随机基线】全池全交易日抽样 %d 笔 —— 用于判断战法是否真有 alpha" % base["n"])
            print("  T+1 %s%% ｜ T+1~2 %s%% ｜ T+1~3 %s%% ｜ T+1~4 %s%% ｜ T+1~5 %s%%" % tuple(
                base["t_hit_pct"]))

    modes = [False, True] if a.compare else [a.loose]
    t0 = time.time()
    for loose in modes:
        tag = "宽松" if loose else "严格"
        r, rows = run(pool, store, loose, a.years)
        if not r:
            print("[%s] 无样本" % tag)
            continue
        out_all[tag] = {k: v for k, v in r.items() if k != "rows"}
        print("\n【%s档】信号 %d 笔 → 可评估 %d 笔（丢弃未来不足5日的）  用时 %.0fs" % (
            tag, r["n_signal"], r["n_eval"], time.time() - t0))
        print("  %-8s %10s %10s %10s %10s %10s" % (
            "周期", "T+1", "T+1~2", "T+1~3", "T+1~4", "T+1~5"))
        print("  %-8s %9.1f%% %9.1f%% %9.1f%% %9.1f%% %9.1f%%" % (
            "命中率", *r["t_hit_pct"]))
        print("  %-8s %10.3f %10.3f %10.3f %10.3f %10.3f" % (
            "均最高/买入", *r["t_avg_reach"]))
        print("  整体日均最高: %.3f×买入价  |  T+5 累计命中 %.1f%%" % (
            r["avg_best"], r["t5_hit_pct"]))
        # 样例
        rows.sort(key=lambda x: -x["best"])
        print("  最好 %s %s %.3f× ｜ 最差 %s %s %.3f×" % (
            rows[0]["secid"], rows[0]["date"], rows[0]["best"],
            rows[-1]["secid"], rows[-1]["date"], rows[-1]["best"]))

    if a.json:
        d = os.path.dirname(os.path.abspath(a.json))
        if d:
            os.makedirs(d, exist_ok=True)
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(out_all, f, ensure_ascii=False, indent=1)
        print("\n已落盘:", a.json)
    return 0


if __name__ == "__main__":
    sys.exit(main())
