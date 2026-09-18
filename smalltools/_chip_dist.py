# -*- coding: utf-8 -*-
"""移动筹码分布（2026-09-16 用户需求·Q4）

数据源：data/kline_store.json（日线，含 ohlc + volume + turnover）。
精度说明：日线无法精确反映"主力大单 vs 散户小单"，本模块只给出 **近似估算**：
  - 平均成本（按"每日换手率衰减"的加权平均，越远的日子权重越低）
  - 90% 集中区间（5%..95% 分位）
  - 当前价 vs 平均成本 = 获利比例 / 套牢深度
  - 主力成本估算（大成交日均价的加权）

输出：JSON + 文字摘要。可单独调用，亦可被 _publish_candidates 推送表读取。

用法：
    from _chip_dist import summary, fmt_chip
    s = summary("sz002281")       # 光迅科技
    print(fmt_chip(s))

也支持批量（全部票 → data/chip_dist/<secid>.json）：
    python smalltools/_chip_dist.py --all
"""
from __future__ import annotations
import json
import math
import os
import sys
from typing import Any, Dict, List, Optional

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _kline_store  # noqa: E402

OUT_DIR = os.path.normpath(os.path.join(HERE, "..", "data", "chip_dist"))


def _daily_decay_weights(n: int, half_life: int = 60) -> List[float]:
    """换手率衰减权重：half_life 个交易日前的筹码权重 = 0.5。越远的日子权重越低。

    借鉴同花顺「N 日内成本分布」思想：用户在某价位建仓后，只有未换手的部分继续作为"成本筹码"。
    这里用指数衰减近似日换手率为 1/half_life 的连续换手模型。
    """
    if n <= 0:
        return []
    w = [math.pow(0.5, (n - 1 - i) / half_life) for i in range(n)]
    s = sum(w) or 1.0
    return [wi / s for wi in w]


def _weighted_quantile(prices: List[float], weights: List[float],
                       q: float) -> float:
    """加权分位数（q∈[0,1]）。先按价格升序排列累积权重。"""
    pairs = sorted(zip(prices, weights), key=lambda x: x[0])
    total = sum(w for _, w in pairs)
    if total <= 0:
        return float("nan")
    cum = 0.0
    target = q * total
    for p, w in pairs:
        cum += w
        if cum >= target:
            return p
    return pairs[-1][0]


def compute(snaps: List[Dict[str, Any]],
            window: int = 120,
            main_vol_q: float = 0.7) -> Dict[str, Any]:
    """对一只股票近 window 个交易日快照算移动筹码分布。

    main_vol_q: 大单定义 = 当日成交量在 window 内的分位数（≥此分位数视为"主力成交日"）
    """
    if not snaps:
        return {"error": "数据不足"}
    n = min(len(snaps), window)
    sub = snaps[-n:]
    closes = [float(s["close"]) for s in sub]
    highs = [float(s["high"]) for s in sub]
    lows = [float(s["low"]) for s in sub]
    vols = [float(s.get("volume", 0) or 0) for s in sub]
    last = float(sub[-1]["close"])
    last_date = sub[-1]["date"][:10]
    if sum(vols) <= 0:
        return {"error": "成交量全零"}

    weights = _daily_decay_weights(n, half_life=60)
    # 加权平均成本（典型价格 = (H+L+C)/3，越接近"筹码成交均价"）
    typical = [(h + l + c) / 3.0 for h, l, c in zip(highs, lows, closes)]
    avg_cost = sum(p * w for p, w in zip(typical, weights))

    # 90% 集中区间
    p05 = _weighted_quantile(typical, weights, 0.05)
    p25 = _weighted_quantile(typical, weights, 0.25)
    p50 = _weighted_quantile(typical, weights, 0.50)
    p75 = _weighted_quantile(typical, weights, 0.75)
    p95 = _weighted_quantile(typical, weights, 0.95)

    # 获利比例 = 现价 ≥ 典型价的权重和（用插值近似：典型价 ≥ 现价的权重全部套牢）
    trapped_w = sum(w for p, w in zip(typical, weights) if p > last)
    profit_w = 1.0 - trapped_w

    # 套牢深度 = avg_cost / last - 1（last < avg_cost 时为正）
    trapped_depth = (avg_cost / last - 1.0) if last > 0 else 0.0

    # 主力成本估算：取成交量前 30% 的大成交日，按金额加权（成交均价）
    sorted_by_vol = sorted(zip(vols, typical), key=lambda x: x[0], reverse=True)
    k = max(1, int(n * (1 - main_vol_q)))
    big = sorted_by_vol[:k]
    if big:
        v_sum = sum(v for v, _ in big)
        if v_sum > 0:
            main_cost = sum(t * v for v, t in big) / v_sum
        else:
            main_cost = avg_cost
    else:
        main_cost = avg_cost

    return {
        "asof": last_date,
        "close": last,
        "window": n,
        "avg_cost": round(avg_cost, 2),
        "main_cost": round(main_cost, 2),
        "cost_p05": round(p05, 2),
        "cost_p25": round(p25, 2),
        "cost_p50": round(p50, 2),
        "cost_p75": round(p75, 2),
        "cost_p95": round(p95, 2),
        "profit_ratio": round(profit_w, 3),       # 0~1
        "trapped_ratio": round(trapped_w, 3),     # 0~1
        "trapped_depth": round(trapped_depth, 3),
        "main_vs_now": round((main_cost / last - 1.0) if last > 0 else 0.0, 3),
        "main_at_above_now": main_cost > last,    # 主力是否在现价上方
    }


def summary(secid: str, store: Optional[Dict[str, Any]] = None,
            window: int = 120) -> Dict[str, Any]:
    """便捷接口：拿 store→snap→compute。"""
    store = store if store is not None else _kline_store.load_store()
    ent = store.get(secid)
    if not ent:
        return {"error": f"{secid} 不在 store 中"}
    snaps = ent.get("snaps") or []
    name = ent.get("name") or secid
    res = compute(snaps, window=window)
    res["code"] = secid
    res["name"] = name
    return res


def fmt_chip(s: Dict[str, Any]) -> str:
    """人话摘要。"""
    if "error" in s:
        return f"[{s.get('code','')}] {s['error']}"
    code = s.get("code", "")
    name = s.get("name", "")
    last = s["close"]
    avg = s["avg_cost"]
    main = s["main_cost"]
    profit = s["profit_ratio"]
    trapped = s["trapped_ratio"]
    depth = s["trapped_depth"]
    main_vs = s["main_vs_now"]
    main_above = "主力在现价上方（套牢中）" if s["main_at_above_now"] else "主力在现价下方（获利中）"
    arrow = "↑盈利" if avg <= last else "↓套牢"
    # 30/70 阈值：套牢 >70% → 重套牢；套牢 50~70 → 中度；30~50 → 轻度；<30 → 浮筹轻
    if trapped > 0.70:
        zone = "重套牢区"
    elif trapped > 0.50:
        zone = "中度套牢"
    elif trapped > 0.30:
        zone = "轻度套牢"
    else:
        zone = "浮筹轻"
    return (
        f"📊 {name} {code} @ {s['asof']} 现价 {last:.2f}\n"
        f"  近 {s['window']} 日加权平均成本 {avg:.2f}（{arrow} {abs(avg/last-1)*100:.1f}%）\n"
        f"  90% 集中区间 [{s['cost_p05']:.2f}, {s['cost_p95']:.2f}]（中位 {s['cost_p50']:.2f}）\n"
        f"  主力成本 {main:.2f}（相对现价 {main_vs*100:+.1f}%，{main_above}）\n"
        f"  获利盘 {profit*100:.1f}% / 套牢盘 {trapped*100:.1f}%（{zone}）\n"
        f"  套牢深度 {depth*100:+.1f}%"
    )


def build_all(window: int = 120, only_codes: Optional[List[str]] = None) -> Dict[str, Any]:
    """批量：所有票 → data/chip_dist/<secid>.json。返回汇总。"""
    store = _kline_store.load_store()
    codes = only_codes or list(store.keys())
    os.makedirs(OUT_DIR, exist_ok=True)
    n_ok = n_skip = 0
    avg_profit = 0.0
    avg_trapped = 0.0
    for code in codes:
        s = summary(code, store=store, window=window)
        if "error" in s:
            n_skip += 1
            continue
        with open(os.path.join(OUT_DIR, f"{code}.json"), "w", encoding="utf-8") as f:
            json.dump(s, f, ensure_ascii=False, indent=1)
        avg_profit += s["profit_ratio"]
        avg_trapped += s["trapped_ratio"]
        n_ok += 1
    return {
        "ok": n_ok,
        "skip": n_skip,
        "avg_profit_ratio": round(avg_profit / max(n_ok, 1), 3),
        "avg_trapped_ratio": round(avg_trapped / max(n_ok, 1), 3),
        "out_dir": OUT_DIR,
    }


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--code", help="单只 secid")
    ap.add_argument("--all", action="store_true", help="批量全池")
    ap.add_argument("--window", type=int, default=120)
    args = ap.parse_args()
    if args.code:
        s = summary(args.code, window=args.window)
        print(fmt_chip(s))
    elif args.all:
        r = build_all(window=args.window)
        print(json.dumps(r, ensure_ascii=False, indent=1))
    else:
        ap.print_help()