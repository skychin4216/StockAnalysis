# -*- coding: utf-8 -*-
"""板块资金活跃度热力图（2026-09-21 用户需求 · Q5）

## 设计（参考业界常见做法：量价背离 + 资金活跃度）
**活跃度分 activity**（无量纲，0 轴为界）：
    activity = 0.6 × 量能偏离 + 0.4 × 涨幅强度
      量能偏离 = (当日板块成交额 / 近20日均额 - 1) × 100   （截断 ±100）
      涨幅强度 = 板块当日等权涨幅 × 放大系数(3)            （截断 ±100）
    ⇒ **0 轴上方（暖色/红）= 资金活跃；0 轴下方（冷色/绿）= 资金冷清**

## 布局
    ┌───────────────────────────────────┬──────────┐
    │ 板块 × 最近 N 日 的活跃度热力块     │ 当日活跃度 │
    │（行=板块，列=日期，颜色=RdBu_r）    │ 横向条形   │
    └───────────────────────────────────┴──────────┘

## 数据缺口（如实说明）
真「资金净流入」目前**只有当日实时**（`_sector_fundflow.fetch_board_flow`，
无历史落盘），所以历史维度用**成交额/量能**做活跃度代理——这是业界通用做法
（成交额本身就是资金参与度的直接体现）。若要真资金维度，需每日落盘
`data/_sector_flow_daily.json`（本模块提供 `save_flow_daily()` 供守护每日调用，
落盘后热力图会自动优先使用真资金流）。

CLI:
    python _sector_heatmap.py                 # 出图 + 打印 Top/Bottom
    python _sector_heatmap.py --days 30 --top 25
"""
from __future__ import annotations

import json
import os
from typing import Any, Dict, List, Optional, Tuple

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt                     # noqa: E402
import numpy as np                                  # noqa: E402

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "PingFang SC",
                                   "Noto Sans CJK SC", "WenQuanYi Zen Hei"]
plt.rcParams["axes.unicode_minus"] = False

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "data", "charts")
FLOW_DAILY = os.path.join(ROOT, "data", "_sector_flow_daily.json")

DEFAULT_DAYS = 20
DEFAULT_TOP = 22

_store = None


def _store_data():
    global _store
    if _store is None:
        from _kline_store import load_store          # noqa: PLC0415
        _store = load_store()
    return _store


# ══════════════════════════════════════════════════════════
# 板块构成与活跃度
# ══════════════════════════════════════════════════════════

def sector_members() -> Dict[str, List[str]]:
    """板块 → 成分 secid（复用 _sector_gate 的权威映射）。"""
    try:
        import _sector_gate as SG                     # noqa: PLC0415
        b = SG._board_index()
        out: Dict[str, List[str]] = {}
        for key in ("industry", "subindustry", "concept"):
            for name, m in (b.get(key) or {}).items():
                if len(m) >= 3:
                    out.setdefault(name, []).extend(m)
        return out
    except Exception:                                 # noqa: BLE001
        return {}


def board_daily(sector: str, secids: List[str], days: int) -> Tuple[List[str], List[float], List[float]]:
    """板块每日 (日期, 等权涨幅%, 成交额亿元)。"""
    store = _store_data()
    per: Dict[str, List[Tuple[float, float]]] = {}
    prev: Dict[str, float] = {}
    for sid in secids[:30]:
        snaps = (store.get(sid) or {}).get("snaps") or []
        for s in snaps[-days - 1:]:
            d = str(s.get("date"))[:10]
            c = float(s.get("close") or 0)
            amt = float(s.get("amount") or 0) or (float(s.get("volume") or 0) * c)
            if d in prev and prev[d] > 0 and c > 0:
                per.setdefault(d, []).append(((c / prev[d] - 1) * 100, amt))
            prev[d] = c
    if not per:
        return [], [], []
    dates = sorted(per)[-days:]
    chg = [sum(x[0] for x in per[d]) / len(per[d]) for d in dates]
    amt = [sum(x[1] for x in per[d]) / 1e8 for d in dates]
    return dates, chg, amt


def activity(chg: List[float], amt: List[float]) -> List[float]:
    """活跃度分：0.6×量能偏离 + 0.4×涨幅强度，截断 ±100，0 轴为界。"""
    out = []
    base = sum(amt) / len(amt) if amt else 0.0
    for i, c in enumerate(chg):
        dev = ((amt[i] / base - 1) * 100) if base > 0 else 0.0
        dev = max(-100.0, min(100.0, dev))
        strength = max(-100.0, min(100.0, c * 3.0))
        out.append(round(0.6 * dev + 0.4 * strength, 1))
    return out


# ══════════════════════════════════════════════════════════
# 出图
# ══════════════════════════════════════════════════════════

def render(days: int = DEFAULT_DAYS, top: int = DEFAULT_TOP,
           out_path: str = "") -> Optional[str]:
    members = sector_members()
    rows: List[Tuple[str, List[str], List[float]]] = []
    for sec, m in members.items():
        dates, chg, amt = board_daily(sec, m, days)
        if len(chg) < max(5, days // 2):
            continue
        rows.append((sec, dates, activity(chg, amt)))
    if not rows:
        print("板块数据不足，无法出图")
        return None
    # 按**近期活跃度均值**排序：取最活跃的 top-5 个 + 最冷清的 5 个（有对照才有意义）
    rows.sort(key=lambda r: sum(r[2]) / len(r[2]), reverse=True)
    n_hot = max(1, top - 5)
    rows = rows[:n_hot] + rows[-5:]
    seen, uniq = set(), []
    for r in rows:
        if r[0] not in seen:
            seen.add(r[0])
            uniq.append(r)
    rows = uniq[:top + 5]

    dates = max((r[1] for r in rows), key=len)
    mat = np.full((len(rows), len(dates)), np.nan)
    for i, (_s, ds, act) in enumerate(rows):
        m = {d: v for d, v in zip(ds, act)}
        for j, d in enumerate(dates):
            mat[i, j] = m.get(d, np.nan)

    os.makedirs(OUT_DIR, exist_ok=True)
    if not out_path:
        out_path = os.path.join(OUT_DIR, "sector_heatmap.png")

    fig = plt.figure(figsize=(max(11, len(dates) * 0.42), max(5.5, len(rows) * 0.34)), dpi=110)
    gs = fig.add_gridspec(1, 2, width_ratios=[5.2, 1.3], wspace=0.06)
    ax = fig.add_subplot(gs[0, 0])
    axb = fig.add_subplot(gs[0, 1])

    # 0 轴为中心的发散色阶：**正值=暖红(活跃)**、负值=冷绿(清淡)（A 股红涨绿跌习惯）
    # 注意 RdYlGn 原生是 低=红 高=绿，与 A 股习惯相反，故用 _r 反转
    im = ax.imshow(mat, aspect="auto", cmap="RdYlGn_r", vmin=-60, vmax=60,
                   interpolation="nearest")
    ax.set_yticks(range(len(rows)))
    ax.set_yticklabels([r[0] for r in rows], fontsize=8)
    step = max(1, len(dates) // 12)
    ax.set_xticks(range(0, len(dates), step))
    ax.set_xticklabels([d[5:] for d in dates[::step]], fontsize=7, rotation=0)
    ax.set_title("板块资金活跃度热力图（上暖=活跃 / 下冷=清淡，0 轴为界）", fontsize=12)
    cb = fig.colorbar(im, ax=ax, fraction=0.025, pad=0.02)
    cb.ax.axhline(0, color="k", linewidth=0.8)
    cb.set_label("活跃度分", fontsize=8)

    # 右侧：当日活跃度横向条
    today = [r[2][-1] if r[2] else 0.0 for r in rows]
    colors = ["#C62828" if v > 0 else "#1B7A3D" for v in today]
    axb.barh(range(len(rows)), today, color=colors, alpha=0.85)
    axb.set_yticks(range(len(rows)))
    axb.set_yticklabels([""] * len(rows))
    axb.axvline(0, color="k", linewidth=0.8)
    axb.invert_yaxis()
    axb.set_title("当日", fontsize=10)
    axb.set_xlim(-60, 60)
    axb.grid(axis="x", alpha=0.2)

    fig.savefig(out_path, dpi=110, bbox_inches="tight")
    plt.close(fig)
    print("热力图已出：%.0f KB -> %s" % (os.path.getsize(out_path) / 1024, out_path))
    return out_path


# ══════════════════════════════════════════════════════════
# 真资金流落盘（可选，落盘后热力图优先使用）
# ══════════════════════════════════════════════════════════

def save_flow_daily() -> int:
    """抓取当日板块资金流并追加落盘 data/_sector_flow_daily.json。

    由守护每日收盘后调用；积累若干天后即可用「真资金净流入」画热力图。
    """
    try:
        import datetime as dt
        import _sector_fundflow as SF                # noqa: PLC0415
        flow = SF.fetch_board_flow() or {}
        if not flow:
            return 0
        rec = {k: (v.get("main_yi") if isinstance(v, dict) else None) for k, v in flow.items()}
        day = dt.date.today().isoformat()
        data: Dict[str, Any] = {}
        if os.path.exists(FLOW_DAILY):
            data = json.load(open(FLOW_DAILY, encoding="utf-8"))
        data[day] = rec
        tmp = FLOW_DAILY + ".tmp"
        json.dump(data, open(tmp, "w", encoding="utf-8"), ensure_ascii=False)
        os.replace(tmp, FLOW_DAILY)
        print("已落盘 %s：%d 个板块" % (day, len(rec)))
        return len(rec)
    except Exception as e:                           # noqa: BLE001
        print("资金流落盘失败:", e)
        return 0


if __name__ == "__main__":
    import sys
    a = sys.argv[1:]

    def _o(n, d):
        return type(d)(a[a.index(n) + 1]) if n in a else d

    if "--save-flow" in a:
        save_flow_daily()
    else:
        p = render(days=_o("--days", DEFAULT_DAYS), top=_o("--top", DEFAULT_TOP))
        if p:
            members = sector_members()
            rank = []
            for sec, m in members.items():
                ds, chg, amt = board_daily(sec, m, DEFAULT_DAYS)
                if not chg:
                    continue
                rank.append((sec, activity(chg, amt)[-1]))
            rank.sort(key=lambda x: x[1], reverse=True)
            print("\n当日最活跃 Top5:", [(s, round(v, 1)) for s, v in rank[:5]])
            print("当日最冷清 Top5:", [(s, round(v, 1)) for s, v in rank[-5:]])
