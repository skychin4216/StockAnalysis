# -*- coding: utf-8 -*-
"""板块闸门（2026-09-21 用户需求 · Q3）

起因：短线选到**万科A**——房地产长期下跌/横盘、资金不流入，只是偶尔被消息刺激
动一下。这类「冷板块」里的个股不该进选股池。

## 规则
板块级判定（成分股等权合成板块走势）：
  · `up_streak`  = 板块**连续上涨天数**（成分股等权日涨幅 > 0 记 1 天）
  · `mom20`      = 板块近 20 日动量 %
  · `flow_yi`    = 板块当日主力净流入（亿元，取不到为 None）

判定：
  1. **热板块**（mom20 ≥ HOT_MOM 或 当日资金流入 ≥ MIN_FLOW_YI）→ 直接通过
  2. **冷板块**（两者都不满足）→ 必须 `up_streak ≥ COLD_MIN_UP_STREAK`（默认 3）才通过
  3. **长线豁免**（沿用 `_pool_filters.py` 既有约定：长线不做板块过滤）

## 数据来源
  · 板块成分：`data/board_index.json`（`{industry: {板块名: [secid...]}, concept: {...}}`）
  · 板块走势：`_kline_store.load_store()`（688 只，2008 起全历史日K）
  · 板块资金流：`_sector_fundflow.fetch_board_flow()`（**当日实时**，可选）

用法：
    from _sector_gate import judge, filter_candidates
    ok, reason = judge("房地产", asof="2026-09-18")
    kept, dropped = filter_candidates(cands, period="短线", asof="2026-09-18")
"""
from __future__ import annotations

import os
from typing import Any, Dict, List, Optional, Tuple

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BOARD_INDEX = os.path.join(ROOT, "data", "board_index.json")

HOT_MOM = 3.0              # 20日动量 ≥ +3% 视为「热板块」
MIN_FLOW_YI = 5.0          # 当日主力净流入 ≥ 5 亿 视为「有资金」
COLD_MIN_UP_STREAK = 3     # 冷板块至少需要连续上涨 3 天（用户口径）
MOM_WINDOW = 20
MAX_MEMBERS = 30           # 每个板块最多取多少只成分股参与合成（控耗时）
MIN_MEMBERS = 3            # 成分少于此数 → 板块走势无统计意义，判定为「样本不足、不误杀」

_store = None
_board_cache: Optional[Dict[str, Any]] = None
_gate_cache: Dict[Tuple[str, str], Tuple[bool, str]] = {}


def _store_data():
    global _store
    if _store is None:
        from _kline_store import load_store       # noqa: PLC0415
        _store = load_store()
    return _store


def _board_index() -> Dict[str, Any]:
    global _board_cache
    if _board_cache is None:
        import json                                # noqa: PLC0415
        try:
            _board_cache = json.load(open(BOARD_INDEX, encoding="utf-8"))
        except Exception:                          # noqa: BLE001
            _board_cache = {}
    return _board_cache


_ind_rev_cache: Optional[Dict[str, List[str]]] = None


def _industry_reverse() -> Dict[str, List[str]]:
    """{东财行业名: [secid...]} —— **权威源**：`_industry_map.build_industry()`。

    它的行业取自 `AutoQuant/data/{code}.{SH|SZ}_{行业}.csv` 的文件名，
    覆盖整个 K 线池（688 只），比 `board_index.json`（只有池中活跃票的申万二级）更全，
    且不会出现「万科A 查不到行业」的问题。
    """
    global _ind_rev_cache
    if _ind_rev_cache is None:
        rev: Dict[str, List[str]] = {}
        try:
            import _industry_map                              # noqa: PLC0415
            for c6, ind in (_industry_map.build_industry() or {}).items():
                if not ind or not c6:
                    continue
                pre = "sh" if str(c6)[0] == "6" or str(c6).startswith("688") else "sz"
                rev.setdefault(str(ind), []).append(pre + str(c6))
        except Exception:                                     # noqa: BLE001
            rev = {}
        _ind_rev_cache = rev
    return _ind_rev_cache


def members(sector: str) -> List[str]:
    """板块成分股 secid 列表。

    匹配顺序：① 东财行业精确 → ② 东财行业子串模糊 → ③ board_index 精确 → ④ 其模糊
    （申万名比口语更细，如「房地产」实际可能是「房地产开发/房地产服务」，故需模糊）
    """
    # ① board_index 精确 → ② board_index 模糊（申万二级，成分真实可靠）
    b = _board_index()
    for key in ("industry", "subindustry", "concept"):
        m = (b.get(key) or {}).get(sector)
        if m:
            return list(m)[:MAX_MEMBERS]
    out: List[str] = []
    for key in ("industry", "subindustry", "concept"):
        for name, m in (b.get(key) or {}).items():
            if sector in name or name in sector:
                out.extend(m)
    if out:
        return _uniq(out)[:MAX_MEMBERS]
    # ③ 东财行业映射（build_industry）兜底 —— 注意其成分可能很少，故 metrics 有下限校验
    rev = _industry_reverse()
    m = rev.get(sector)
    if m:
        return list(m)[:MAX_MEMBERS]
    out = []
    for name, mm in rev.items():
        if sector in name or name in sector:
            out.extend(mm)
    return _uniq(out)[:MAX_MEMBERS]


def _uniq(seq: List[str]) -> List[str]:
    """去重保序。"""
    seen, out = set(), []
    for s in seq:
        if s not in seen:
            seen.add(s)
            out.append(s)
    return out


def sector_series(sector: str, asof: str = "", days: int = MOM_WINDOW + 6):
    """板块等权日涨幅序列（%）与日期序列。"""
    secids = members(sector)
    if not secids:
        return [], []
    store = _store_data()
    per_day: Dict[str, List[float]] = {}
    for sid in secids:
        snaps = (store.get(sid) or {}).get("snaps") or []
        if asof:
            snaps = [s for s in snaps if str(s.get("date"))[:10] <= asof]
        snaps = snaps[-days:]
        for i in range(1, len(snaps)):
            p0 = float(snaps[i - 1].get("close") or 0)
            p1 = float(snaps[i].get("close") or 0)
            if p0 > 0 and p1 > 0:
                per_day.setdefault(str(snaps[i].get("date"))[:10], []).append((p1 / p0 - 1) * 100)
    if not per_day:
        return [], []
    dates = sorted(per_day)
    series = [sum(per_day[d]) / len(per_day[d]) for d in dates]
    return dates, series


def up_streak(series: List[float]) -> int:
    """从最后一天往前数连续上涨天数。"""
    n = 0
    for v in reversed(series):
        if v > 0:
            n += 1
        else:
            break
    return n


def sector_flow(sector: str) -> Optional[float]:
    """板块当日主力净流入（亿元）；取不到返回 None。"""
    try:
        import _sector_fundflow as SF               # noqa: PLC0415
        flow = SF.fetch_board_flow() or {}
        e = flow.get(sector)
        if isinstance(e, dict):
            return float(e.get("main_yi") or 0)
    except Exception:                              # noqa: BLE001
        pass
    return None


def metrics(sector: str, asof: str = "") -> Dict[str, Any]:
    """板块指标：up_streak / mom20 / flow_yi / n_members。"""
    n = len(members(sector))
    if n < MIN_MEMBERS:                       # 成分太少 → 板块走势无统计意义，不据此下判
        return {"sector": sector, "ok": False, "reason": "板块成分仅%d只<%d，样本不足" % (n, MIN_MEMBERS)}
    dates, series = sector_series(sector, asof)
    if not series:
        return {"sector": sector, "ok": False, "reason": "无板块成分数据"}
    mom = sum(series[-MOM_WINDOW:]) if len(series) >= MOM_WINDOW else sum(series)
    return {"sector": sector, "ok": True,
            "up_streak": up_streak(series),
            "mom20": round(mom, 2),
            "flow_yi": sector_flow(sector),
            "n_members": len(members(sector)),
            "last_date": dates[-1] if dates else ""}


def judge(sector: str, asof: str = "", period: str = "短线") -> Tuple[bool, str]:
    """板块是否放行。返回 (是否通过, 原因)。"""
    if period == "长线":                            # 沿用既有约定：长线不做板块过滤
        return True, "长线豁免"
    # 「其他 / 未知 / 空」是**兜底分类**（什么票都有），拿它的聚合动量去杀票是错的
    if not sector or str(sector).strip() in ("其他", "未知", "无", "-"):
        return True, "板块未知（兜底分类，不误杀）"
    key = (sector, asof)
    if key in _gate_cache:
        return _gate_cache[key]

    m = metrics(sector, asof)
    if not m.get("ok"):
        res = (True, "板块数据不足（不误杀）")
        _gate_cache[key] = res
        return res

    flow = m.get("flow_yi")
    hot = (m["mom20"] >= HOT_MOM) or (flow is not None and flow >= MIN_FLOW_YI)
    if hot:
        res = (True, "热板块(动量%+.1f%%/资金%s)" % (m["mom20"],
                                                    "%.1f亿" % flow if flow is not None else "无"))
    elif m["up_streak"] >= COLD_MIN_UP_STREAK:
        res = (True, "冷板块但连涨%d天≥%d" % (m["up_streak"], COLD_MIN_UP_STREAK))
    else:
        res = (False, "冷板块(动量%+.1f%% 连涨%d<%d) 剔除" %
               (m["mom20"], m["up_streak"], COLD_MIN_UP_STREAK))
    _gate_cache[key] = res
    return res


def filter_candidates(cands: List[Dict[str, Any]], period: str = "短线",
                      asof: str = "", sector_key: str = "industry"
                      ) -> Tuple[List[Dict[str, Any]], List[Tuple[Dict[str, Any], str]]]:
    """按板块闸门过滤候选票。返回 (保留, [(被剔除票, 原因)])。"""
    kept, dropped = [], []
    seen: Dict[str, Tuple[bool, str]] = {}
    for c in cands or []:
        sec = c.get(sector_key) or c.get("board") or ""
        if sec not in seen:
            seen[sec] = judge(sec, asof, period)
        ok, reason = seen[sec]
        if ok:
            kept.append(c)
        else:
            c = dict(c)
            c["_gate_reject"] = reason
            dropped.append((c, reason))
    return kept, dropped


if __name__ == "__main__":
    import sys
    secs = sys.argv[1:] or ["房地产", "半导体", "银行", "电力"]
    print("%-10s %6s %8s %10s  判定" % ("板块", "连涨", "20日动量", "资金(亿)"))
    for s in secs:
        m = metrics(s)
        if not m.get("ok"):
            print("%-10s  数据不足" % s)
            continue
        ok, why = judge(s)
        print("%-10s %6s %+8.2f %10s  %s %s" % (
            s, m["up_streak"], m["mom20"],
            "%.1f" % m["flow_yi"] if m["flow_yi"] is not None else "-",
            "✅" if ok else "❌", why))
