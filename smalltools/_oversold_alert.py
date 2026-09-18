# -*- coding: utf-8 -*-
"""超跌预警池（2026-09-16 用户需求·Q4 漏选补救）

缘由：现有「低位埋伏(v9)」「企稳低吸(v11)」都对"连跌后不再新低"有硬要求；
光迅科技 2026-09-15 案例为「高位回调阴跌 + 继续新低」，不在任何低位通道。
本工具加一层「超跌预警」——**底部模糊判别**，即使当日还在新低也入观察名单，
给用户/推送的"反共识"机会。

判定（60 日窗口）：
  跌幅 ≥ 25%              高位回撤幅度
  现价 ≤ MA250 × 1.05     在年线附近（不远离下方）
  振幅 < MA60 振幅均值    缩量/振幅收敛
  RS14 ≤ 35               超卖
  量比 ≤ 0.9              非放量下跌
  且当日 changePct < 0    继续下行（不在已企稳后才进）
结果：预警池 = 接近底部 + 仍在阴跌 的「**接飞刀候选**」。
用途：推送的"⚠️ 超跌预警"段；选股后置过滤（候选池仅做参考，不替代主选股通道）。

用法：
    python smalltools/_oversold_alert.py                  # 默认全池扫
    python smalltools/_oversold_alert.py --asof 2026-09-15  # 指定日期
    python smalltools/_oversold_alert.py --top 20         # 输出前 20
    python smalltools/_oversold_alert.py --json           # JSON 输出
"""
from __future__ import annotations
import argparse
import json
import math
import os
import sys
from typing import Any, Dict, List

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _kline_store  # noqa: E402


def _avg(xs):
    return sum(xs) / len(xs) if xs else 0.0


def _ma(arr, n):
    if len(arr) < n:
        return None
    return sum(arr[-n:]) / n


def _rsi14(closes):
    if len(closes) < 15:
        return None
    gains, losses = [], []
    for i in range(1, 15):
        d = closes[-i] - closes[-i - 1]
        if d >= 0:
            gains.append(d)
            losses.append(0.0)
        else:
            gains.append(0.0)
            losses.append(-d)
    avg_g = _avg(gains)
    avg_l = _avg(losses)
    if avg_l == 0:
        return 100.0
    rs = avg_g / avg_l
    return 100.0 - 100.0 / (1.0 + rs)


def scan(store: Dict[str, Any], asof: str = "", top: int = 30) -> List[Dict[str, Any]]:
    """扫所有票，挑超跌预警。"""
    out: List[Dict[str, Any]] = []
    for code, ent in store.items():
        if code.startswith("sh000") or code.startswith("sz399"):
            continue
        snaps = ent.get("snaps") or []
        if len(snaps) < 70:
            continue
        last = snaps[-1]
        if asof and last["date"][:10] != asof:
            continue
        closes = [float(s["close"]) for s in snaps[-70:]]
        highs = [float(s["high"]) for s in snaps[-70:]]
        lows = [float(s["low"]) for s in snaps[-70:]]
        vols = [float(s.get("volume", 0) or 0) for s in snaps[-70:]]

        cur = closes[-1]
        hi60 = max(highs)
        dd = (cur / hi60 - 1.0) * 100            # % 高点回撤
        if dd > -25.0:                           # 回撤不到 25% 不算超跌
            continue
        ma250 = _ma(closes, 60)                  # 70 根内最长 MA=60 近似
        if ma250 is None or cur > ma250 * 1.05:
            continue

        amp = [(h - l) / l * 100 if l > 0 else 0 for h, l in zip(highs, lows)]
        amp_avg = _avg(amp[:-1]) if len(amp) > 1 else 0
        amp_last = amp[-1]
        if amp_avg <= 0 or amp_last > amp_avg * 1.5:    # 不收敛
            continue

        rsi = _rsi14(closes)
        if rsi is None or rsi > 35:
            continue

        vol_avg5 = _avg(vols[-5:])
        vol_avg20 = _avg(vols[-20:])
        if vol_avg20 <= 0:
            continue
        vol_ratio = vol_avg5 / vol_avg20
        if vol_ratio > 0.9:                      # 仍在放量下跌
            continue

        chg = float(last.get("changePct", 0) or 0)
        if chg >= 0:                             # 当日企稳/反弹 → 走 v11 通道，不进预警
            continue

        score = (abs(dd) + abs(35 - rsi) + abs(1.0 - vol_ratio) * 10
                 + (1.0 - amp_last / max(amp_avg, 0.01)) * 5)
        out.append({
            "code": code,
            "name": ent.get("name") or code,
            "asof": last["date"][:10],
            "close": round(cur, 2),
            "dd60": round(dd, 1),
            "rsi14": round(rsi, 1),
            "vol_ratio5_20": round(vol_ratio, 2),
            "chg": round(chg, 2),
            "score": round(score, 2),
        })
    out.sort(key=lambda x: -x["score"])
    return out[:top]


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--asof", default="")
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    store = _kline_store.load_store()
    rows = scan(store, asof=args.asof, top=args.top)
    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=1))
    else:
        print("⚠️  超跌预警（%d 只，按底部模糊判别打分）" % len(rows))
        print("  条件: 60日回撤≥25% & 现价≤MA60×1.05 & 振幅收敛 & RSI14≤35 & 量比≤0.9 & 当日仍阴跌")
        print("  意义: 接近底部但**还在新低**，传统企稳通道抓不到，给个反共识参考")
        print()
        print(f"  {'代码':<10}{'名称':<10}{'日期':<12}{'现价':>8}{'60日回撤':>10}"
              f"{'RSI14':>7}{'量比5/20':>10}{'今日%':>8}")
        for r in rows:
            print("  %-10s%-10s%-12s%8.2f%+9.1f%%%6.1f%10.2f%+7.2f%%" %
                  (r["code"], r["name"][:8], r["asof"], r["close"],
                   r["dd60"], r["rsi14"], r["vol_ratio5_20"], r["chg"]))