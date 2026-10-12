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

# ── 阈值（均可用环境变量覆盖，便于按实盘反馈微调）──
# 连跌天数：**按最低价**数连续新低（下跌途中允许阳线），且**不含**最后那个「不新低」日
MIN_DOWN_STREAK = int(os.environ.get("RELAX_DOWN_STREAK", "8"))
CUM_WINDOW = 10             # 累计跌幅观察窗口（交易日）
CUM_DROP = float(os.environ.get("RELAX_CUM_DROP", "-12.0"))
# 九转 TD 计数（close[i] < close[i-4] 连续根数）；用户：「连续跌7天以上，符合九转信号之一」
TD_MIN = int(os.environ.get("RELAX_TD_MIN", "7"))
# 下跌段天数（从近 12 日内最高收盘到当前的交易日数；用户：万科「一共 7 天」）
MIN_DECLINE_DAYS = int(os.environ.get("RELAX_DECLINE_DAYS", "7"))

# ── ★ 板块属性：只有「热门赛道」才允许放宽 ──
# 用户明确：「房地产目前是冷门板块，一潭死水，**不能放宽**。
#           如果是其他**热门板块**，则可以考虑放宽。」
# ⇒ 冷门板块（房地产/银行/白酒/煤炭…）即便出现超跌反转也**不放行**，
#   因为那是资金持续流出、没有承接的「死水」，反弹多为一日游。
#   热门赛道（资金长期关注）短期回调 7 天 + 底部反转形态，才是「上车机会」。
HOT_TRACKS = [
    "半导体", "集成电路", "元件", "消费电子", "光学光电", "通信设备", "通信",
    "人工智能", "AI", "算力", "光模块", "服务器", "软件", "计算机", "互联网",
    "机器人", "自动化设备", "军工", "航天", "国防", "船舶",
    "创新药", "生物制品", "医疗器械", "化学制药",
    "电池", "光伏", "风电", "储能", "电网设备", "电源设备",
    "小金属", "稀有金属", "能源金属", "有色金属", "稀土",
    "汽车零部件", "智能驾驶", "汽车电子",
]


def is_hot_track(sector: str) -> bool:
    """板块是否属于「热门赛道」（可调，见 HOT_TRACKS）。"""
    s = (sector or "").strip()
    if not s:
        return False
    return any(k in s or s in k for k in HOT_TRACKS)
SHRINK_VR = float(os.environ.get("RELAX_SHRINK_VR", "0.85"))
ENV_MOM = float(os.environ.get("RELAX_ENV_MOM", "-3.0"))

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
    """连续下跌天数 —— **按最低价**判定（2026-09-21 用户纠正）。

    ★ 关键口径（用户原话）：「9.16 最低位比 15 低，9.17 最低位比 9.16 高」
      ⇒ 不能用「连续收阴」判，因为下跌途中**允许有阳线**（万科 09-16 收 +0.33%
        却仍创新低）。要用 **low[i] < low[i-1]** 数「连续新低」天数。
      ⇒ 且**最后一天是「不新低」的止跌确认日**，不计入连跌段，
        故从**倒数第二根**开始往前数。
    """
    n = 0
    for i in range(len(snaps) - 2, 0, -1):          # 从倒数第 2 根起（最后那根是不新低日）
        if _f(snaps[i], "low") < _f(snaps[i - 1], "low"):
            n += 1
        else:
            break
    return n


def no_new_low(snaps: List[Dict[str, Any]]) -> bool:
    """最后一天**不新低**（止跌确认）：low[-1] >= low[-2]。

    用户强调：「一定要保证连续跌 ≥8 天 + 最后一天不新低」。
    （注释：此前 `三日不新低` 那套是针对**热门板块**的追涨确认，
      这里是**超跌板块**的止跌确认，二者场景不同。）
    """
    if len(snaps) < 2:
        return False
    return _f(snaps[-1], "low") >= _f(snaps[-2], "low")


def decline_days(snaps: List[Dict[str, Any]], window: int = 12) -> int:
    """**下跌段天数**：近 window 日内「最高收盘价」那一天 到 当前 的交易日数。

    用户口径（万科A）：「9.09 开始跌，9.17 只是缩量阳，**一共 7 天**」。
    最高收盘在 09-08（3.27），其后 09-09…09-17 正好 7 个交易日 → 本函数返回 7。
    """
    seg = snaps[-window:] if len(snaps) > window else snaps
    if len(seg) < 3:
        return 0
    closes = [_f(s, "close") for s in seg]
    i_max = max(range(len(closes)), key=lambda i: closes[i])
    return len(seg) - 1 - i_max


def td_count(snaps: List[Dict[str, Any]]) -> int:
    """TD 买入结构计数（**九转**）：连续 `close[i] < close[i-4]` 的根数。

    用户：「这个连续跌 7 天以上，符合九转信号之一」。
    经典 TD Buy Setup 要 9 根；这里把计数暴露出来，阈值由 `TD_MIN` 控制。
    """
    n = 0
    for i in range(len(snaps) - 1, 3, -1):
        if _f(snaps[i], "close") < _f(snaps[i - 4], "close"):
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


def evaluate(secid: str, asof: str = "", sector: str = "") -> Dict[str, Any]:
    """评估是否放宽闸门。返回 {relax, reasons[], pattern, decline_days, …}。

    @param sector 个股所属行业/板块（**必需**）—— 冷门板块一律不放宽。
    """
    sn = snaps_of(secid)
    if asof:
        sn = [s for s in sn if str(s.get("date"))[:10] <= asof]
    if len(sn) < 25:
        return {"relax": False, "reasons": ["K线不足(<25根)"], "pattern": "",
                "down_streak": 0, "vol_ratio": 1.0, "env": {}}

    ds = down_streak(sn)                 # 连续新低天数（按 low，从倒数第2根起）
    nnl = no_new_low(sn)                 # 最后一天不新低（**强制**）
    td = td_count(sn)                    # 九转 TD 计数
    vr = vol_ratio(sn)
    pat = detect_pattern(sn)
    env = env_need_support(asof)
    cd = cum_drop(sn)

    # C1 三选一（任一成立即可）
    c1a = ds >= MIN_DOWN_STREAK          # 连跌(按最低价) ≥ 8 天
    c1b = cd <= CUM_DROP                 # 近 10 日累计跌幅 ≤ -12%
    c1c = td >= TD_MIN                   # 九转 TD 计数 ≥ 7
    c1 = c1a or c1b or c1c
    c2 = bool(pat)
    c3 = vr <= SHRINK_VR
    c4 = bool(env.get("need"))
    c5 = nnl                             # ★ 强制：最后一天不新低
    dd = decline_days(sn)
    c6 = is_hot_track(sector)            # ★ 强制：板块必须是**热门赛道**

    reasons = [
        "C1 超跌：连跌(按低点)%d天%s ／ 近%d日%+.2f%%%s ／ 九转%d%s → %s" % (
            ds, "✓" if c1a else "✗", CUM_WINDOW, cd, "✓" if c1b else "✗",
            td, "✓" if c1c else "✗", "✓" if c1 else "✗"),
        "C2 反转形态：%s %s" % (pat or "无", "✓" if c2 else "✗"),
        "C3 缩量：量比 %.2f（阈值 ≤%.2f）%s" % (vr, SHRINK_VR, "✓" if c3 else "✗"),
        "C4 维稳环境：指数20日 %+.2f%%（阈值 ≤%.1f%%）%s" % (
            env.get("mom20", 0.0), ENV_MOM, "✓" if c4 else "✗"),
        "C5 **最后一天不新低**（强制）：%s %s" % (
            "是（低点抬高）" if nnl else "否（仍在创新低）", "✓" if c5 else "✗"),
        "C6 **热门赛道**（强制）：%s %s" % (
            sector or "-", "✓" if c6 else "✗ 冷门板块不放宽（如房地产=一潭死水）"),
        "　（下跌段 %d 天，阈值 ≥%d %s）" % (
            dd, MIN_DECLINE_DAYS, "✓" if dd >= MIN_DECLINE_DAYS else "✗"),
    ]
    # 用户口径：下跌段≥7天 + 最后一天不新低 + **板块是热门赛道**，
    #           再叠加形态/缩量/维稳环境
    relax = c1 and c2 and c3 and c4 and c5 and c6
    return {"relax": relax, "reasons": reasons, "pattern": pat,
            "down_streak": ds, "td_count": td, "no_new_low": nnl, "decline_days": dd,
            "cum_drop": round(cd, 2), "vol_ratio": round(vr, 3), "env": env,
            "conditions": {"c1_oversold": c1, "c1a_streak": c1a, "c1b_cumdrop": c1b,
                           "c1c_td": c1c, "c2_pattern": c2, "c3_shrink": c3,
                           "c4_policy_env": c4, "c5_no_new_low": c5}}


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
