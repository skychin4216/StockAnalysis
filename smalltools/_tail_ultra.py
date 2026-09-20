# -*- coding: utf-8 -*-
"""尾盘超短战法（隔日短线）判定核心 —— 2026-09-20 用户需求。

## 战法原文（E:\\Android\\work\\dev\\选股思路\\尾盘超短线思路.txt）

> 1. 操作节奏：每天 **14:30** 用自制指标筛股，**14:50 满仓买入**，**次日 10 点前全部清仓**，
>    单票持仓从不超 2 天
> 2. 买入规则：只选**前期有爆量、缩量回踩十日线的阴线**，要求**十日线向上**、
>    **和二十日线距离近**，不看板块、不打板、不追涨
> 3. 卖出纪律：开盘低于十日线直接割肉，盈利到 **2 个点左右**就止盈，绝不格局等待
>
> 核心逻辑：机械化操作，**博弈次日抄底资金带来的反弹收益**。

## 为什么加它

用户反馈"最近超短线和短线选出的股票都不太好"——原 pipeline 的选股思路偏"多头强势"
（均线粘合突破、量价齐升、板块热度），而本战法是**反向的**：趁**洗盘缩量回踩**介入，
赚**次日反弹**的钱。两者信号来源互补，正好补上"回调型"这一块。

## 六条件（原文逐条落地，全部可在宽松/严格两档切换）

| # | 条件 | 原文依据 | 宽松档 | 严格档 |
|---|---|---|---|---|
| 1 | 前期有爆量 | "前期有爆量" | 20日内 max量 ≥ 2.0×20日均量 | ≥ 2.5× |
| 2 | 当日缩量 | "缩量回踩" | 当日量 ≤ 0.85×5日均量 | ≤ 0.7× |
| 3 | 回踩十日线 | "回踩十日线" | 最低价触及 MA10 的 ±2.5% 带内 | ±1.2% 带内 |
| 4 | **阴线** | "的阴线" | close < open | close < open 且 close < 昨收 |
| 5 | 十日线向上 | "十日线向上" | MA10 > MA10(3日前) | MA10 > MA10(1日前) 且斜率>0.15% |
| 6 | 贴近二十日线 | "和二十日线距离近" | abs(MA10-MA20)/MA20 ≤ 4% | ≤ 2% |

## 用法

    python _tail_ultra.py 600000 300308 601872      # 指定票（终端表格）
    python _tail_ultra.py --loose 600000            # 宽松档
    python _tail_ultra.py --scan 300                # 扫全池前 300 只，列出命中
    python _tail_ultra.py --scan 300 --asof 2026-09-18   # 指定某天（回测用）
    python _tail_ultra.py --json out.json           # 落盘供 pipeline / 回测消费

## 设计约束

- **只读** `_kline_store`，不新增数据依赖；
- 判定函数 `detect(snaps, loose=False)` 为**纯函数**（输入日K列表 → 输出结果），
  便于 pipeline 节点、Kotlin 移植、以及 walk-forward 回测**三处复用同一口径**。
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


def _ma(closes, n, back=0):
    """n 日均线（back=0 为最新一根，back=k 为 k 根之前）。不足返回 None。"""
    end = len(closes) - back
    if end < n:
        return None
    seg = closes[end - n:end]
    return sum(seg) / n


def detect(snaps, loose=False, asof=None):
    """尾盘超短战法判定（单一事实源）。

    Args:
        snaps: 日K列表（升序），每项含 date/open/high/low/close/volume
        loose: True = 宽松档（阈值放宽，让更多票流出）
        asof:  指定评估日（回测用）；None = 用最后一根
    Returns:
        dict(hit=bool, score=0~1, conds={名:bool}, reasons=[...], detail={...})
    """
    if not snaps or len(snaps) < 25:
        return {"hit": False, "score": 0.0, "conds": {}, "reasons": ["K线不足25根"],
                "detail": {}}

    if asof:
        idx = None
        for i, s in enumerate(snaps):
            if s.get("date") == asof:
                idx = i
                break
        if idx is None:
            return {"hit": False, "score": 0.0, "conds": {}, "reasons": ["无该日数据"],
                    "detail": {}}
        win = snaps[:idx + 1]
    else:
        win = snaps

    if len(win) < 25:
        return {"hit": False, "score": 0.0, "conds": {}, "reasons": ["窗口不足25根"],
                "detail": {}}

    cur = win[-1]
    closes = [float(s.get("close") or 0) for s in win]
    vols = [float(s.get("volume") or 0) for s in win]
    o = float(cur.get("open") or 0)
    h = float(cur.get("high") or 0)
    low = float(cur.get("low") or 0)
    c = closes[-1]
    v = vols[-1]

    if not c or not o or not v:
        return {"hit": False, "score": 0.0, "conds": {}, "reasons": ["当日字段缺失"],
                "detail": {}}

    ma10 = _ma(closes, 10)
    ma20 = _ma(closes, 20)
    ma10_prev = _ma(closes, 10, back=1)
    ma10_prev3 = _ma(closes, 10, back=3)
    if not ma10 or not ma20 or not ma10_prev or not ma10_prev3:
        return {"hit": False, "score": 0.0, "conds": {}, "reasons": ["均线不足"],
                "detail": {}}

    # ── 阈值（宽松/严格两档）──────────────────────────────
    TH = {
        "burst_x": 2.0 if loose else 2.5,      # 前期爆量倍数
        "shrink_x": 0.85 if loose else 0.70,   # 当日缩量上限
        "touch_band": 0.025 if loose else 0.012,   # 回踩 MA10 允许带宽
        "ma10_gap": 0.04 if loose else 0.02,   # MA10 与 MA20 距离上限
    }

    # 条件1：前期有爆量（近 20 日内出现单日量 ≥ N×20日均量）
    avg20v = (sum(vols[-21:-1]) / 20) if len(vols) >= 21 else (sum(vols) / len(vols))
    burst = False
    burst_x = 0.0
    for i in range(max(0, len(vols) - 21), len(vols) - 1):
        if avg20v > 0 and vols[i] / avg20v >= TH["burst_x"]:
            burst = True
            burst_x = max(burst_x, vols[i] / avg20v)

    # 条件2：当日缩量
    avg5v = sum(vols[-6:-1]) / 5 if len(vols) >= 6 else v
    shrink = avg5v > 0 and v <= avg5v * TH["shrink_x"]

    # 条件3：回踩十日线（最低价触及 MA10 附近带内）
    lo_ratio = abs(low - ma10) / ma10 if ma10 else 9.9
    touch = lo_ratio <= TH["touch_band"] or (low <= ma10 <= max(h, c))

    # 条件4：阴线
    bear = c < o
    if not loose:
        bear = bear and c < closes[-2]

    # 条件5：十日线向上
    if loose:
        ma_up = ma10 > ma10_prev3
    else:
        ma_up = ma10 > ma10_prev and (ma10 / ma10_prev3 - 1) > 0.0015

    # 条件6：贴近二十日线
    gap = abs(ma10 - ma20) / ma20 if ma20 else 9.9
    near = gap <= TH["ma10_gap"]

    conds = {"前期爆量": burst, "当日缩量": shrink, "回踩十日线": touch,
             "阴线": bear, "十日线向上": ma_up, "贴近二十日线": near}
    hit_n = sum(1 for x in conds.values() if x)

    # ── 评分：六条各占权重，核心三条（爆量/回踩/阴线）权重更高 ──
    W = {"前期爆量": 0.22, "当日缩量": 0.13, "回踩十日线": 0.20,
         "阴线": 0.15, "十日线向上": 0.15, "贴近二十日线": 0.15}
    score = sum(W[k] for k, ok in conds.items() if ok)
    # 硬性要求：回踩十日线 + 阴线（战法灵魂），二者缺一不算命中
    hit = conds["回踩十日线"] and conds["阴线"] and hit_n >= (5 if not loose else 4)

    reasons = []
    if burst:
        reasons.append("前期爆量%.1f倍" % burst_x)
    if shrink:
        reasons.append("当日缩量%.0f%%" % (v / avg5v * 100 if avg5v else 0))
    if touch:
        reasons.append("回踩MA10(%.2f)" % ma10)
    if bear:
        reasons.append("阴线")
    if ma_up:
        reasons.append("MA10向上")
    if near:
        reasons.append("贴MA20(%.1f%%)" % (gap * 100))

    return {
        "hit": hit, "score": round(score, 3), "conds": conds, "reasons": reasons,
        "detail": {"close": c, "ma10": round(ma10, 3), "ma20": round(ma20, 3),
                   "ma10_gap_pct": round(gap * 100, 2),
                   "vol_ratio5": round(v / avg5v, 2) if avg5v else 0,
                   "burst_x": round(burst_x, 2),
                   "touch_pct": round(lo_ratio * 100, 2), "n_hit": hit_n},
    }


def main():
    ap = argparse.ArgumentParser(description="尾盘超短战法（隔日短线）判定")
    ap.add_argument("codes", nargs="*", help="6 位代码或 sh600000")
    ap.add_argument("--loose", action="store_true", help="宽松档（阈值放宽）")
    ap.add_argument("--scan", type=int, default=0, help="扫全池前 N 只")
    ap.add_argument("--asof", default="", help="指定评估日（回测用）")
    ap.add_argument("--json", default="", help="结果落盘路径")
    ap.add_argument("--limit", type=int, default=60, help="最多显示条数")
    a = ap.parse_args()

    from _kline_store import load_store  # noqa: PLC0415
    store = load_store()

    targets = []
    for c in a.codes:
        c6 = c
        if len(c) == 6 and c.isdigit():
            secid = ("sh" if c[0] == "6" else "sz") + c
        else:
            secid = c[:2].lower() + c[2:]
        targets.append(secid)
    if a.scan:
        pool = [k for k in store if not k.startswith(("sh000", "sz399"))]
        targets = pool[:a.scan]

    hits, all_rows = [], []
    for secid in targets:
        ent = store.get(secid) or {}
        snaps = ent.get("snaps") or []
        if len(snaps) < 25:
            continue
        r = detect(snaps, loose=a.loose, asof=a.asof or None)
        row = {"secid": secid, "name": ent.get("name") or "", "date": snaps[-1].get("date"),
               "close": r["detail"].get("close"), "score": r["score"],
               "hit": r["hit"], "n_hit": r["detail"].get("n_hit", 0),
               "reasons": r["reasons"]}
        all_rows.append(row)
        if r["hit"]:
            hits.append(row)

    tag = "宽松档" if a.loose else "严格档"
    print("=== 尾盘超短战法（%s）| 评估 %d 只 | 命中 %d 只 ===" % (tag, len(all_rows), len(hits)))
    print("%-11s %-9s %-12s %7s %6s %s" % ("代码", "名称", "日期", "收盘", "评分", "理由"))
    for r in sorted(hits, key=lambda x: -x["score"])[:a.limit]:
        print("%-11s %-9s %-12s %7.2f %6.3f %s" % (
            r["secid"], (r["name"] or "")[:8], r["date"], r["close"] or 0,
            r["score"], " ".join(r["reasons"])))
    if not hits:
        print("（无命中）")

    if a.json:
        os.makedirs(os.path.dirname(os.path.abspath(a.json)) or ".", exist_ok=True)
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump({"asof": a.asof, "loose": a.loose, "n": len(all_rows),
                       "hits": hits, "rows": all_rows}, f, ensure_ascii=False, indent=1)
        print("\n已落盘:", a.json)
    return 0


if __name__ == "__main__":
    sys.exit(main())
