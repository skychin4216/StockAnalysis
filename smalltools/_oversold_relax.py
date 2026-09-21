# -*- coding: utf-8 -*-
"""超跌反转 + 政策维稳 → **板块闸门放宽**（2026-09-21 用户思路总结）

## 用户原话（万科A 案例）
> 万科A 16 号 17 号分别出现**锤子**和**刺穿**的星星，最关键的是他已经**连续 8 天下跌**，
> 加上现在是**缩量**，美元加息，国家可能要**维护指数**（因为如果指数下跌，资金就跑到美国那边），
> 加上前段时间政策故意释放…我不觉得这是房地产反转，而是我们国家的政策…
> **久不久拉一把下跌太多的板块**。
> 你打开闸门也没有错，但是连续下跌 8-9 天的股票，加上大环境，**我们可以稍微放松闸门**。

## 量化后的判定（四个条件同时满足 → 放宽）
  C1 超跌     ：连跌天数 ≥ `MIN_DOWN_STREAK`（默认 8）
  C2 反转形态 ：命中底部反转形态（锤子 / **刺穿线** / 早晨之星 / 看涨吞没 …）
  C3 缩量     ：当日量比 ≤ `SHRINK_VR`（默认 0.85）—— 下跌末端抛压枯竭
  C4 维稳环境 ：大盘处于**超跌或弱势**（指数近 20 日 ≤ `ENV_MOM`，默认 -3%）
                —— 指数越弱，政策"拉一把"的动机越强（资金外流压力）

输出：`{relax, score, reasons[], pattern, down_streak, vol_ratio}`

## 与板块闸门的关系
`_sector_gate.judge()` 对「冷板块」默认剔除。本模块提供**豁免凭证**：
冷板块里若存在这类「超跌+反转+缩量」的票，在大环境需要维稳时**放行**。

CLI:
    python _oversold_relax.py sz000002          # 单票诊断
    python _oversold_relax.py sz000002 sh600606 # 多票
"""
from __future__ import annotations

import os
import sys
from typing import Any, Dict, List, Optional

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

MIN_DOWN_STREAK = 8         # C1-a 严格连跌天数下限
CUM_WINDOW = 10             # C1-b 累计跌幅观察窗口（交易日）
CUM_DROP = -12.0            # C1-b 近 10 日累计跌幅 ≤ 此值 → 也算超跌
SHRINK_VR = 0.85            # C3 缩量线（量比 ≤ 此值视为缩量）
ENV_MOM = -3.0              # C4 指数近 20 日动量 ≤ 此值 → 维稳动机强

_store = None


def _store_data():
    global _store
    if _store is None:
        from _kline_store import load_store          # noqa: PLC0415
        _store = load_store()
    return _store


def snaps_of(secid: str) -> List[Dict[str, Any]]:
    return (_store_data().get(secid) or {}).get("snaps") or []


# ══════════════════════════════════════════════════════════
# 形态：刺穿线（用户明确提到，原 _reversal_patterns.py 里没有 R7）
# ══════════════════════════════════════════════════════════

def _f(s: Dict[str, Any], k: str, d: float = 0.0) -> float:
    try:
        v = s.get(k)
        return float(v) if v is not None else d
    except (TypeError, ValueError):
        return d


def is_piercing(snaps: List[Dict[str, Any]]) -> bool:
    """刺穿线（Piercing Line）—— 底部看涨反转两K组合。

    条件（经典定义，日落强度 ≥ 前阴实体 50%）：
      ① 前一根为阴线，且实体够长（|收-开| ≥ 2% 的收盘价）
      ② 当根为阳线，开盘低于前收（向下跳空）
      ③ 当根收盘**刺入前阴实体过半**：close > (前开 + 前收)/2
      ④ 当根收盘 < 前开（未完全吞没，区别于看涨吞没）
    """
    if len(snaps) < 2:
        return False
    a, b = snaps[-2], snaps[-1]                      # a=前阴, b=当阳
    ao, ac = _f(a, "open"), _f(a, "close")
    bo, bc = _f(b, "open"), _f(b, "close")
    if ao <= 0 or ac <= 0 or ac >= ao:               # 前一根不是阴线
        return False
    body = ao - ac
    if body < ac * 0.02:                             # 前阴实体太小 → 不算
        return False
    if bo >= ac:                                     # 未低开
        return False
    if bc <= (ao + ac) / 2:                          # 未刺入过半
        return False
    if bc >= ao:                                     # 完全吞没 → 归看涨吞没
        return False
    return True


def is_hammer(snaps: List[Dict[str, Any]]) -> bool:
    """锤子线（下影长、实体小、上影极短）。"""
    if not snaps:
        return False
    s = snaps[-1]
    o, c, h, l = _f(s, "open"), _f(s, "close"), _f(s, "high"), _f(s, "low")
    if min(o, c, h, l) <= 0 or h <= l:
        return False
    rng = h - l
    body = abs(c - o)
    lower = min(o, c) - l
    upper = h - max(o, c)
    return (upper <= rng * 0.3) and (lower >= max(body, rng * 0.15) * 2)


def down_streak(snaps: List[Dict[str, Any]]) -> int:
    """从最后一根往前数连续下跌（收 < 前收）天数。"""
    n = 0
    for i in range(len(snaps) - 1, 0, -1):
        if _f(snaps[i], "close") < _f(snaps[i - 1], "close"):
            n += 1
        else:
            break
    return n


def cum_drop(snaps: List[Dict[str, Any]], window: int = CUM_WINDOW) -> float:
    """近 window 个交易日累计涨跌幅（%）。"""
    if len(snaps) < 2:
        return 0.0
    seg = snaps[-window - 1:] if len(snaps) > window else snaps
    c0, c1 = _f(seg[0], "close"), _f(seg[-1], "close")
    return (c1 / c0 - 1) * 100 if c0 > 0 else 0.0


def vol_ratio(snaps: List[Dict[str, Any]], n: int = 5) -> float:
    """当日量 / 前 n 日均量。"""
    if len(snaps) < n + 1:
        return 1.0
    v = _f(snaps[-1], "volume")
    prev = [_f(s, "volume") for s in snaps[-n - 1:-1]]
    avg = sum(prev) / len(prev) if prev else 0.0
    return (v / avg) if avg > 0 else 1.0


def detect_pattern(snaps: List[Dict[str, Any]]) -> str:
    """命中的底部反转形态名（空串=无）。优先复用 `_reversal_patterns`。"""
    if is_piercing(snaps):
        return "刺穿线"
    if is_hammer(snaps):
        return "锤子"
    try:
        import _reversal_patterns as RP              # noqa: PLC0415
        for fn, name in (("_r2_morning_star", "早晨之星"),
                         ("_r3_bullish_engulf", "看涨吞没"),
                         ("_r1_double_hammer", "双锤子")):
            f = getattr(RP, fn, None)
            if f is None:
                continue
            try:
                if f(snaps):
                    return name
            except Exception:                        # noqa: BLE001
                continue
    except Exception:                                # noqa: BLE001
        pass
    return ""


# ══════════════════════════════════════════════════════════
# 大环境：维稳动机
# ══════════════════════════════════════════════════════════

def env_need_support(asof: str = "") -> Dict[str, Any]:
    """大盘是否弱到「需要维稳」。用上证(000001_SH)近 20 日动量近似。"""
    for secid in ("sh000001", "000001_SH"):
        sn = snaps_of(secid)
        if not sn:
            continue
        if asof:
            sn = [s for s in sn if str(s.get("date"))[:10] <= asof]
        if len(sn) < 21:
            continue
        c0 = _f(sn[-20], "close") or _f(sn[-21], "close")
        c1 = _f(sn[-1], "close")
        if c0 <= 0:
            continue
        mom = (c1 / c0 - 1) * 100
        return {"ok": True, "mom20": round(mom, 2), "need": mom <= ENV_MOM}
    return {"ok": False, "mom20": 0.0, "need": False}


def evaluate(secid: str, asof: str = "") -> Dict[str, Any]:
    """四条件评估。返回 {relax, reasons[], pattern, down_streak, vol_ratio, env}。"""
    sn = snaps_of(secid)
    if asof:
        sn = [s for s in sn if str(s.get("date"))[:10] <= asof]
    if len(sn) < 25:
        return {"relax": False, "reasons": ["K线不足(<25根)"], "pattern": "",
                "down_streak": 0, "vol_ratio": 1.0, "env": {}}

    ds = down_streak(sn)
    vr = vol_ratio(sn)
    pat = detect_pattern(sn)
    env = env_need_support(asof)

    cd = cum_drop(sn)
    # C1 双路径：严格连跌 ≥N 天 **或** 近 10 日累计跌幅 ≤ -12%
    # （用户说的「连续 8 天下跌」在实践中常含中途小阳线，只认严格连跌会漏掉）
    c1a = ds >= MIN_DOWN_STREAK
    c1b = cd <= CUM_DROP
    c1 = c1a or c1b
    c2 = bool(pat)
    c3 = vr <= SHRINK_VR
    c4 = bool(env.get("need"))

    reasons = [
        "C1 超跌：连跌 %d 天%s ／ 近%d日 %+.2f%%%s（任一满足即可）%s" % (
            ds, "✓" if c1a else "✗", CUM_WINDOW, cd, "✓" if c1b else "✗",
            "✓" if c1 else "✗"),
        "C2 反转形态：%s %s" % (pat or "无", "✓" if c2 else "✗"),
        "C3 缩量：量比 %.2f（阈值 ≤%.2f）%s" % (vr, SHRINK_VR, "✓" if c3 else "✗"),
        "C4 维稳环境：指数20日 %+.2f%%（阈值 ≤%.1f%%）%s" % (
            env.get("mom20", 0.0), ENV_MOM, "✓" if c4 else "✗"),
    ]
    # **必须同时满足**（用户口径：超跌 + 反转 + 缩量 + 大环境）
    relax = c1 and c2 and c3 and c4
    return {"relax": relax, "reasons": reasons, "pattern": pat,
            "down_streak": ds, "cum_drop": round(cd, 2), "vol_ratio": round(vr, 3), "env": env,
            "conditions": {"c1_oversold": c1, "c1a_streak": c1a, "c1b_cumdrop": c1b,
                           "c2_pattern": c2, "c3_shrink": c3, "c4_policy_env": c4}}


def should_relax(secid: str, asof: str = "") -> bool:
    """供 `_sector_gate` 调用的简化入口。"""
    try:
        return bool(evaluate(secid, asof).get("relax"))
    except Exception:                                # noqa: BLE001
        return False


if __name__ == "__main__":
    args = sys.argv[1:] or ["sz000002"]
    for sid in args:
        r = evaluate(sid)
        print("=== %s  放宽=%s  形态=%s ===" % (sid, "是" if r["relax"] else "否",
                                                r["pattern"] or "无"))
        for line in r["reasons"]:
            print("   ", line)
