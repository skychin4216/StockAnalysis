# -*- coding: utf-8 -*-
"""底部反转形态检测器（2026-09-16 用户需求：双锤子抄底 + 类似抄底逻辑）。

缘由：现有 _oversold_alert.py 是「接飞刀预警」（仍在阴跌，未确认反转），
本工具做「**形态反转确认**」——已经在底部出现反转信号，是**真正可抄底**的标的。

判定模式（6 种多根K线组合形态，按出现顺序遍历，给每个匹配打分）：
  R1 双锤子反转（光迅案例）   连续阴跌 + 第一根放量锤子 + 第二根缩量锤子 + 当日不破双底
  R2 早晨之星            长阴 + 十字星 + 长阳（底部最经典）
  R3 看涨吞没            阴 + 大阳包阴（强反转）
  R4 底部岛形反转         向下跳空 → 平台 → 向上跳空（罕见但强）
  R5 V 形底              急跌后急涨（5 根K线内 maxDD≥-15% & rebound≥+8%）
  R6 圆弧底              60日内低点先降后抬，振幅先收后放（中期底部）

输出：每只票命中的形态列表 + 总分（多重命中加权）。

用法：
    python smalltools/_reversal_patterns.py --asof 2026-09-15      # 单日扫描
    python smalltools/_reversal_patterns.py --asof 2026-09-15 --json
    python smalltools/_reversal_patterns.py --code sz002281        # 单股诊断（光迅）

数据源：data/kline_store.json（统一K线库，2026-09-16 全链路迁移完成）
"""
from __future__ import annotations
import argparse
import json
import math
import os
import sys
from typing import Any, Dict, List, Optional, Tuple

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _kline_store  # noqa: E402


def _f(x: Dict[str, Any], key: str, default: float = 0.0) -> float:
    v = x.get(key)
    return float(v) if v is not None else default


def _is_hammer(s: Dict[str, Any]) -> bool:
    """底部反转K线（锤子 + 长腿十字线统称）。

    两种形态都接受：
      ① 标准锤子：上影 ≤30%振幅，下影 ≥2×实体（收阳）/ ≥3×实体（收阴）
      ② 长腿十字线：实体 ≤30%振幅，上下影都 ≥1.5×实体，**且上下影对称**
         （上影 ≤ 2×下影）——否则是「流星线/射击之星」（上影长下影短），
         属**看跌**形态，不能算底部反转。
         2026-09-16 修：锦浪科技 09-04 上影1.24/下影0.29（4.3倍）曾被误判。

    注：「近 10 日低区」不作必要条件——下跌中继的下影较短，
    只要下影足够长 + 实体小，出现在下跌尾段的概率高。
    """
    o, c, h, l = (_f(s, "open"), _f(s, "close"), _f(s, "high"), _f(s, "low"))
    body = abs(c - o)
    rng = max(h - l, 1e-9)
    up_sh, lo_sh = h - max(o, c), min(o, c) - l
    # 长腿十字线：上下影都长 + 实体小 + 上下对称（排除流星线）
    if (body <= rng * 0.30 and up_sh >= body * 1.5 and lo_sh >= body * 1.5
            and up_sh <= lo_sh * 2.0):
        return True
    # 标准锤子
    if up_sh > rng * 0.30:
        return False
    if c >= o:
        return lo_sh >= 2 * body
    return lo_sh >= 3 * body


def _is_doji(s: Dict[str, Any]) -> bool:
    o, c, h, l = (_f(s, "open"), _f(s, "close"), _f(s, "high"), _f(s, "low"))
    return abs(c - o) <= max(h - l, 1e-9) * 0.03


def _down_streak(snaps: List[Dict[str, Any]], end_idx: int, max_n: int = 5) -> int:
    """连续阴线天数（从 end_idx 往前数，最多 max_n 根）。

    end_idx 为负数索引（如 -3 表示从倒数第 3 根往前数）。
    """
    cnt = 0
    for i in range(end_idx - 1, end_idx - 1 - max_n, -1):
        if abs(i) > len(snaps):
            break
        s = snaps[i]
        if _f(s, "close") < _f(s, "open"):
            cnt += 1
        else:
            break
    return cnt


def _vol_ratio(snaps: List[Dict[str, Any]], idx: int = -1) -> float:
    """第 idx 根K线量比（与前 5 均量比）。

    2026-09-16 修 bug：负索引时 max(0, idx-5) 会把 -7 变成 0，
    导致取 snaps[0:-2]（从 2008 到 9-13 全部历史）当 base，
    海康 vr(-2) = 0.83 其实是全历史均量稀释的结果，并非"相对前 5 日"。
    直接用 idx-5（负索引天然支持「最近 5 根」）。
    """
    if len(snaps) < 6:
        return 1.0
    cur = _f(snaps[idx], "volume")
    # 注意：负索引 idx-5 也可能是负数（如 idx=-2 → idx-5=-7），Python 列表支持
    pre5 = [_f(s, "volume") for s in snaps[idx - 5:idx]]
    base = sum(pre5) / max(len(pre5), 1)
    return cur / base if base > 0 else 1.0


# ───────────────────────── 形态检测 ─────────────────────────

def _r1_double_hammer(snaps: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """R1 双锤子反转：连续阴跌 + 第一根放量锤子 + 第二根缩量锤子 + 当日不破双底。

    光迅科技 2026-09-15 案例：09-11放量锤子+09-14缩量锤子+09-15收167.59未破前低。
    两根锤子**不必相邻**（中间允许 1-5 根普通K线），最新一根视作"second hammer"。
    """
    if len(snaps) < 8:
        return None
    k = snaps[-1]
    # 在最近 7 根（不含今日）内找所有锤子
    hammers: List[Tuple[int, Dict[str, Any]]] = []
    for i in range(-2, -8, -1):
        if abs(i) > len(snaps):
            break
        s = snaps[i]
        if _is_hammer(s):
            vr = _vol_ratio(snaps, i)
            hammers.append((i, s, vr))
    if len(hammers) < 2:
        return None
    # 取**最新**一根作为"second"（缩量锤子），前面找一根作为"first"（放量锤子）
    second_idx, second_s, vr_second = hammers[0]
    first_hit = None
    for cand in hammers[1:]:
        ci, cs, cvr = cand
        # 间距 1-5 根（含 1 根——K 线相邻但跨周末，实际日历可能差 3 天）
        if second_idx - ci < 1 or second_idx - ci > 5:
            continue
        # 第一根 vr≥0.9（不算萎缩即可，「放量」放宽到「不缩量」）；
        # 第二根相对第一根的量必须缩量（vr_second / cvr ≤ 0.85）
        # — 2026-09-16 放宽：原 1.3 + 0.7 太严，
        #   光迅 9-11 真实 vr=1.01（5 日均量被 9-08 大涨稀释），0.85 更贴近实盘
        if cvr < 0.9:
            continue
        if vr_second > cvr * 0.85:
            continue
        first_hit = cand
        break
    if not first_hit:
        return None
    first_idx, first_s, vr_first = first_hit
    # 第一根之前（不含第一根）5 天内至少 3 天阴线
    # 2026-09-16 修 bug：原 max(0, first_idx-5) 在负索引时退化为 0，
    # 会把 [0:first_idx] 整段历史当窗口（与 _vol_ratio 同源 bug），
    # 导致 streak 可能数到多年前的连续阴线（曾出现"连跌1923天"）。
    streak = _down_streak(snaps, first_idx, max_n=5)
    if streak < 3:
        return None
    # 当日不破双底（两根锤子最低价 min）
    double_bottom = min(_f(first_s, "low"), _f(second_s, "low"))
    if _f(k, "low") < double_bottom * 0.99:
        return None
    chg_k = _f(k, "changePct")
    if chg_k < -3.0:
        return None
    return {
        "type": "R1_双锤子反转",
        "score": 4.5 + min(streak - 3, 2) * 0.4,
        "detail": "连跌%d天+放量锤子(%s vr=%.2f)+缩量锤子(%s vr=%.2f)+双底%.2f" %
                  (streak, first_s["date"][:10], vr_first,
                   second_s["date"][:10], vr_second, double_bottom),
    }


def _r2_morning_star(snaps: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """R2 早晨之星：长阴 + 十字星 + 长阳（前三根）。"""
    if len(snaps) < 4:
        return None
    k, p1, p2 = snaps[-1], snaps[-2], snaps[-3]
    if not _is_doji(p1):
        return None
    o2, c2 = _f(p2, "open"), _f(p2, "close")
    rng1 = max(_f(p1, "high") - _f(p1, "low"), 1e-9)
    if not (c2 < o2 and abs(_f(p1, "close") - _f(p1, "open")) <= max(abs(c2 - o2) * 0.35, rng1 * 0.15)):
        return None
    o, c = _f(k, "open"), _f(k, "close")
    if not (c > o and c >= (o2 + c2) / 2):
        return None
    # 强化：长阴+小十字+长阳 = 强反转
    long_bear = (c2 - o2) / max(_f(p2, "low"), 1e-9) <= -0.025  # 跌幅≥2.5%
    long_bull = (c - o) / max(_f(k, "low"), 1e-9) >= 0.025       # 涨幅≥2.5%
    if not (long_bear and long_bull):
        return None
    return {
        "type": "R2_早晨之星",
        "score": 4.0,
        "detail": "长阴+十字星+长阳 强反转",
    }


def _r3_bullish_engulf(snaps: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """R3 看涨吞没：阴 + 大阳包阴 + 阳实体覆盖阴实体。"""
    if len(snaps) < 3:
        return None
    k, p1 = snaps[-1], snaps[-2]
    o1, c1 = _f(p1, "open"), _f(p1, "close")
    o, c = _f(k, "open"), _f(k, "close")
    if not (c1 < o1 and c > o and c >= o1 and o <= c1):
        return None
    # 强化：阳实体比阴实体大 ≥30%
    bear_body = o1 - c1
    bull_body = c - o
    if bull_body < bear_body * 1.3:
        return None
    # 阳线涨幅 ≥3%
    chg = _f(k, "changePct")
    if chg < 3.0:
        return None
    return {
        "type": "R3_看涨吞没",
        "score": 3.5,
        "detail": "阴→大阳包阴(实体+%.1f%%)" % chg,
    }


def _r4_island_bottom(snaps: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """R4 底部岛形反转：向下跳空 → **窄幅**平台 → 向上跳空。

    2026-09-16 修误判：原版只数"两根跳空之间隔了几根"，不验证中间是否横盘，
    导致麦捷科技（8-28 向上跳空 + 更早某处向下跳空，中间价格从 17 涨到 22，+30%）
    被误判成"7根平台"。真实岛形要求**中间窄幅震荡**（蓄势），不能是趋势段。

    约束：
      · 两次跳空间隔 2-10 根；
      · 平台期 (high_max / low_min - 1) ≤ 12%（窄幅蓄势）；
      · 向上跳空日 low 必须站在平台高点附近（不回补缺口）。
    """
    # i 最远 -15，需要 snaps[i-1] 合法 → 至少 16 根
    if len(snaps) < 17:
        return None
    # 倒序扫描：找一个向上跳空（k.low > p1.high）
    for i in range(-1, -16, -1):
        k = snaps[i]
        p1 = snaps[i - 1]
        if _f(k, "low") <= _f(p1, "high"):
            continue
        # 找到向上跳空前，再找向下跳空
        for j in range(i - 1, max(-30, i - 15), -1):
            k2 = snaps[j]
            p2 = snaps[j - 1]
            if _f(p2, "low") <= _f(k2, "high"):
                continue
            span = i - j
            if not (3 <= span <= 10):               # 平台至少 3 根才谈得上"蓄势"
                continue
            # 平台期（j..i 之间，不含两端跳空日）必须窄幅
            mid = snaps[j + 1:i]
            if not mid:
                continue
            mid_hi = max(_f(s, "high") for s in mid)
            mid_lo = min(_f(s, "low") for s in mid)
            if mid_lo <= 0:
                continue
            amp = mid_hi / mid_lo - 1.0
            if amp > 0.10:                          # 平台振幅 >10% → 是趋势段
                continue
            # 平台高点不得突破向上跳空日的 low（否则孤岛浮在缺口上方，不是"被缺口夹住"）
            # 海看股份 8-31 单日 +20% 到 22.33，平台高点 22.40 远高于跳空位 19.18 → 排除
            up_gap_low = _f(k, "low")
            if mid_hi > up_gap_low * 1.05:
                continue
            # 平台期不得有单日暴涨暴跌（>9%，含涨跌停），否则不是"蓄势"
            if any(abs(_f(s, "changePct")) > 9.0 for s in mid):
                continue
            return {
                "type": "R4_底部岛形",
                # 2026-09-16 从 5.0 降到 3.5：A 股跳空形态常见（688 只中曾 39 只命中，
                # 两轮收紧后仍 22 只），误报率高于 R1 双锤子（含量能确认）。
                # 降分保证 R1（4.5）在推送排序中优先于 R4。
                "score": 3.5,
                "detail": "向下跳空→%d根窄幅平台(振幅%.1f%%)→向上跳空"
                          % (span, amp * 100),
            }
        break
    return None


def _r5_v_bottom(snaps: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """R5 V 形底：近 8 根内「先跌 ≥15% 再有反弹 ≥8%」。

    2026-09-16 补全（原注释写了约束但没实现）：
      · 最低点必须落在窗口后半段（argmin ≥ 3），即"跌在前、涨在后"，
        否则只是"冲高回落"，不是 V 形底；
      · 最高点必须在最低点之前（argmax < argmin），确保 V 的左臂在右臂前。
    """
    if len(snaps) < 8:
        return None
    window = snaps[-8:]
    closes = [_f(s, "close") for s in window]
    min_c = min(closes)
    if min_c <= 0:
        return None
    argmin = closes.index(min_c)
    # 最高点必须出现在最低点之前（左臂先于右臂）
    pre = closes[:argmin] if argmin > 0 else []
    if not pre:
        return None
    max_c = max(pre)
    # 最低点需落在后半段，才是"刚发生的底部"而非"持续阴跌"
    if argmin < 3:
        return None
    drop_pct = (min_c / max_c - 1.0) * 100
    rebound_pct = (closes[-1] / min_c - 1.0) * 100
    if drop_pct > -15.0 or rebound_pct < 8.0:
        return None
    return {
        "type": "R5_V形底",
        "score": 3.0,
        "detail": "8根内跌%.1f%%→反弹%.1f%%" % (-drop_pct, rebound_pct),
    }


def _r6_round_bottom(snaps: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """R6 圆弧底：60 日内低点先降后抬 + 振幅先收后放。"""
    if len(snaps) < 60:
        return None
    win = snaps[-60:]
    lows = [_f(s, "low") for s in win]
    amps = [(_f(s, "high") - _f(s, "low")) / max(_f(s, "low"), 1e-9) * 100 for s in win]
    # 分 3 段（前 20 / 中 20 / 后 20）
    seg1_low, seg2_low, seg3_low = min(lows[:20]), min(lows[20:40]), min(lows[40:])
    seg1_amp, seg2_amp, seg3_amp = (sum(amps[:20]) / 20, sum(amps[20:40]) / 20, sum(amps[40:]) / 20)
    # 圆弧底：seg1 > seg2(最低) < seg3（低点后抬）
    if not (seg2_low < seg1_low * 0.97 and seg3_low > seg2_low * 1.04):
        return None
    # 振幅：seg2 < seg1（收敛） 且 seg3 > seg2（放量启动）
    if not (seg2_amp < seg1_amp * 0.85 and seg3_amp > seg2_amp * 1.15):
        return None
    return {
        "type": "R6_圆弧底",
        "score": 3.5,
        "detail": "60日低点先降后抬+振幅收敛→放大",
    }


_DETECTORS = (
    _r1_double_hammer,
    _r2_morning_star,
    _r3_bullish_engulf,
    _r4_island_bottom,
    _r5_v_bottom,
    _r6_round_bottom,
)


def detect(snaps: List[Dict[str, Any]]) -> Dict[str, Any]:
    """对单股最新 K 线做组合形态检测。返回 {patterns: [...], score: float, label: str, errors: [...]}。

    label 取总分最高者；多重命中叠加（每多一个 +0.5）。
    2026-09-16 优化：detector 异常不再静默吞掉，收集到 errors 便于排查
    （原先 `except Exception: r = None` 会让 detector 内部 bug 完全无声）。
    """
    hits: List[Dict[str, Any]] = []
    errors: List[str] = []
    for fn in _DETECTORS:
        try:
            r = fn(snaps)
        except Exception as e:
            errors.append("%s: %s: %s" % (fn.__name__, type(e).__name__, e))
            continue
        if r:
            hits.append(r)
    if not hits:
        return {"patterns": [], "score": 0.0, "label": "—", "errors": errors}
    base = max(h["score"] for h in hits)
    bonus = (len(hits) - 1) * 0.5
    total = round(base + bonus, 2)
    main_idx = max(range(len(hits)), key=lambda i: hits[i]["score"])
    main_hit = hits[main_idx]
    label = main_hit["type"]
    if len(hits) > 1:
        others = ",".join(h["type"] for i, h in enumerate(hits) if i != main_idx)
        label = "%s+%s" % (main_hit["type"], others)
    return {
        "patterns": hits,
        "score": total,
        "label": label,
        "main_detail": main_hit["detail"],
        "errors": errors,
    }


def scan(store: Dict[str, Any], asof: str = "", top: int = 30,
         min_score: float = 3.0) -> List[Dict[str, Any]]:
    """扫所有票，挑命中底部反转形态的标的。

    Args:
        store: kline_store 字典 {secid: {name, snaps, ...}}
        asof:  "YYYY-MM-DD" 截止日期（默认取各股最后交易日）
        top:   输出前 N
        min_score: 最低形态总分阈值（默认 3.0）
    """
    out: List[Dict[str, Any]] = []
    for code, ent in store.items():
        if code.startswith("sh000") or code.startswith("sz399"):
            continue
        snaps = ent.get("snaps") or []
        if len(snaps) < 70:
            continue
        # 2026-09-16 修：asof 原为「精确等于最后交易日」，盘中（数据仍是昨日）
        # 或传入未来日期时会把所有票过滤掉 → 推送显示"无形态命中"。
        # 改为「截止日期」语义：取 date <= asof 的最后一根做诊断。
        if asof and snaps[-1]["date"][:10] > asof:
            cut = None
            for i in range(len(snaps) - 1, -1, -1):
                if snaps[i]["date"][:10] <= asof:
                    cut = i
                    break
            if cut is None:
                continue
            snaps = snaps[:cut + 1]
            if len(snaps) < 70:
                continue
        last = snaps[-1]
        date_last = last["date"][:10]
        d = detect(snaps)
        if d["score"] < min_score:
            continue
        cur = _f(last, "close")
        highs60 = max(_f(s, "high") for s in snaps[-60:])
        dd60 = round((cur / highs60 - 1.0) * 100, 1)
        out.append({
            "code": code,
            "name": ent.get("name") or code,
            "asof": date_last,
            "close": round(cur, 2),
            "dd60": dd60,
            "label": d["label"],
            "patterns": [h["type"] for h in d["patterns"]],
            "score": d["score"],
            "detail": d.get("main_detail") or "",
        })
    out.sort(key=lambda x: (-x["score"], x["dd60"]))
    return out[:top]


# ───────────────────────── 单股诊断（CLI 用） ─────────────────────────

def diagnose(code: str, store: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """单股形态诊断（CLI 演示用）。"""
    if store is None:
        store = _kline_store.load_store()
    secid = code if code.startswith(("sz", "sh")) else (
        "sz" + code if code.startswith(("0", "3")) else "sh" + code)
    ent = store.get(secid) or {}
    snaps = ent.get("snaps") or []
    if not snaps:
        return {"error": "无K线数据", "code": secid}
    d = detect(snaps)
    return {
        "code": secid,
        "name": ent.get("name") or secid,
        "asof": snaps[-1]["date"][:10],
        "close": _f(snaps[-1], "close"),
        "patterns": d["patterns"],
        "score": d["score"],
        "label": d["label"],
    }


# ───────────────────────── CLI ─────────────────────────

if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="底部反转形态检测器（6种组合形态）")
    ap.add_argument("--asof", default="", help="YYYY-MM-DD 截止日期")
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--code", default="", help="单股诊断（如 sz002281）")
    args = ap.parse_args()

    if args.code:
        r = diagnose(args.code)
        print(json.dumps(r, ensure_ascii=False, indent=1))
        sys.exit(0)

    store = _kline_store.load_store()
    rows = scan(store, asof=args.asof, top=args.top)
    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=1))
    else:
        print("🔁 底部反转形态扫描（%d 只，按形态总分排序）" % len(rows))
        print("  R1 双锤子反转(光迅案例) | R2 早晨之星 | R3 看涨吞没")
        print("  R4 底部岛形 | R5 V 形底 | R6 圆弧底")
        print("  条件: 形态命中 + score ≥ 3.0（多重命中叠加）")
        print("  意义: **形态反转确认**——可以抄底的标的（区别于 _oversold_alert 接飞刀预警）")
        print()
        print(f"  {'代码':<10}{'名称':<10}{'日期':<12}{'现价':>8}{'60日回撤':>10}"
              f"{'形态':>16}{'分数':>6}")
        for r in rows:
            print("  %-10s%-10s%-12s%8.2f%+9.1f%%%16s%6.2f" %
                  (r["code"], r["name"][:8], r["asof"], r["close"],
                   r["dd60"], r["label"], r["score"]))