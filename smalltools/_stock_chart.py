# -*- coding: utf-8 -*-
"""个股「日K + 当天分时」并排图（2026-09-21 用户需求 · Q1）

布局：
    左半：近 N 个交易日（默认 60 ≈ 3 个月）日K蜡烛 + MA5/MA20 + 成交量
    右半：当天分时（5 分钟收盘价连线）+ 均价线(VWAP) + 昨收基准线 + 成交量

产出**单张 PNG**（`push_channel.send_image` 只支持单张、≤2MB，见 :242/:45），
用于：
  · 微信群推送（当前）
  · 后续 APK 通过 URL 拉取显示（用户明确要求「不要在消息里传数据，APK 自己取」）

配色沿用 A 股习惯：红涨绿跌。
"""
from __future__ import annotations

import os
from typing import Any, Dict, List, Optional

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt                      # noqa: E402
from matplotlib.patches import Rectangle             # noqa: E402

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "PingFang SC",
                                   "Noto Sans CJK SC", "WenQuanYi Zen Hei"]
plt.rcParams["axes.unicode_minus"] = False

UP_COLOR = "#E53935"      # 涨=红
DOWN_COLOR = "#2E7D32"    # 跌=绿
MA5_COLOR = "#F9A825"
MA20_COLOR = "#1E88E5"

DATA_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data")
OUT_DIR = os.path.join(DATA_DIR, "pick_charts")

_IMAGE_LIMIT = 2 * 1024 * 1024      # 企微 image 消息硬上限


def code6_of(secid: str) -> str:
    s = (secid or "").lower()
    return s[2:] if s[:2] in ("sh", "sz") else s


# ══════════════════════════════════════════════════════════
# 数据
# ══════════════════════════════════════════════════════════

def daily_snaps(secid: str, days: int = 60) -> List[Dict[str, Any]]:
    """近 N 个交易日日K（升序）。"""
    try:
        from _kline_store import load_store          # noqa: PLC0415
        ent = (load_store() or {}).get(secid) or {}
        return list(ent.get("snaps") or [])[-days:]
    except Exception:                                # noqa: BLE001
        return []


def today_bars(secid: str) -> List[Dict[str, Any]]:
    """当天 5 分钟K（升序）；取不到当天就用最后一个有数据的交易日。"""
    try:
        import _intraday as I                        # noqa: PLC0415
        bars = I.fetch_m5(code6_of(secid), 100, scale="5") or []
        if not bars:
            return []
        last_day = str(bars[-1].get("day", ""))[:10]
        return [b for b in bars if str(b.get("day", ""))[:10] == last_day]
    except Exception:                                # noqa: BLE001
        return []


# ══════════════════════════════════════════════════════════
# 绘图
# ══════════════════════════════════════════════════════════

def _sma(vals: List[float], n: int) -> List[Optional[float]]:
    out: List[Optional[float]] = []
    for i in range(len(vals)):
        out.append(sum(vals[i - n + 1:i + 1]) / n if i + 1 >= n else None)
    return out


def _draw_daily(ax, axv, snaps: List[Dict[str, Any]], name: str, code: str):
    closes = [float(s.get("close") or 0) for s in snaps]
    highs = [float(s.get("high") or 0) for s in snaps]
    lows = [float(s.get("low") or 0) for s in snaps]
    vols = [float(s.get("volume") or 0) for s in snaps]
    xs = list(range(len(snaps)))
    w = 0.62

    for i, s in enumerate(snaps):
        o = float(s.get("open") or 0)
        c = closes[i]
        hi, lo = highs[i], lows[i]
        col = UP_COLOR if c >= o else DOWN_COLOR
        ax.plot([i, i], [lo, hi], color=col, linewidth=0.8, zorder=2)
        if abs(hi - lo) < 1e-6:                       # 一字/十字星
            ax.plot([i - w / 2, i + w / 2], [c, c], color=col, linewidth=1.2, zorder=3)
        else:
            ax.add_patch(Rectangle((i - w / 2, min(o, c)), w, abs(c - o),
                                   facecolor=col, edgecolor=col, zorder=3))

    for n, col in ((5, MA5_COLOR), (20, MA20_COLOR)):
        ma = _sma(closes, n)
        ys = [v for v in ma if v is not None]
        if ys:
            ax.plot(xs[len(xs) - len(ys):], ys, color=col, linewidth=1.0,
                    label="MA%d" % n, zorder=4)

    ax.set_xlim(-1, len(snaps) + 1)
    if closes:
        pad = (max(highs) - min(lows)) * 0.08 or 0.1
        ax.set_ylim(min(lows) - pad, max(highs) + pad)
    ax.set_title("%s %s · 日K(%d日)" % (name, code, len(snaps)), fontsize=11)
    ax.grid(alpha=0.25, linewidth=0.5)
    ax.legend(loc="upper left", fontsize=8, framealpha=0.5)

    axv.bar(xs, vols, width=w, color=[UP_COLOR if i == 0 or closes[i] >= closes[i - 1]
                                      else DOWN_COLOR for i in xs], alpha=0.55)
    axv.set_xlim(-1, len(snaps) + 1)
    axv.grid(alpha=0.2, linewidth=0.5)
    axv.set_ylabel("量", fontsize=8)


def _draw_minute(ax, axv, bars: List[Dict[str, Any]], prev_close: float):
    if not bars:
        ax.text(0.5, 0.5, "分时数据不可用", ha="center", va="center", fontsize=10)
        ax.set_axis_off()
        axv.set_axis_off()
        return
    closes = [float(b.get("close") or 0) for b in bars]
    vols = [float(b.get("vol") or 0) for b in bars]
    xs = list(range(len(bars)))

    cum_amt, cum_vol, vwap = 0.0, 0.0, []
    for c, v in zip(closes, vols):
        cum_amt += c * v
        cum_vol += v
        vwap.append(cum_amt / cum_vol if cum_vol else c)

    last = closes[-1]
    col = UP_COLOR if last >= prev_close else DOWN_COLOR
    ax.plot(xs, closes, color=col, linewidth=1.1, label="价格")
    ax.plot(xs, vwap, color=MA5_COLOR, linewidth=1.0, label="均价")
    if prev_close:
        ax.axhline(prev_close, color="#9E9E9E", linewidth=0.8, linestyle="--",
                   label="昨收 %.2f" % prev_close)
    ax.fill_between(xs, closes, prev_close or min(closes), color=col, alpha=0.10)

    lo = min(min(closes), prev_close or min(closes))
    hi = max(max(closes), prev_close or max(closes))
    pad = (hi - lo) * 0.15 or 0.05
    ax.set_ylim(lo - pad, hi + pad)
    ax.set_xlim(0, len(bars))
    pct = ((last / prev_close - 1) * 100) if prev_close else 0.0
    ax.set_title("分时 · %.2f (%+.2f%%)" % (last, pct), fontsize=11, color=col)
    ax.grid(alpha=0.25, linewidth=0.5)
    ax.legend(loc="upper left", fontsize=8, framealpha=0.5)

    axv.bar(xs, vols, width=0.8, color=col, alpha=0.5)
    axv.set_xlim(0, len(bars))
    axv.grid(alpha=0.2, linewidth=0.5)


def render_pair(secid: str, name: str = "", out_path: str = "",
                days: int = 60, dpi: int = 110) -> Optional[str]:
    """日K + 当天分时 并排 PNG。返回输出路径；数据不足返回 None。"""
    snaps = daily_snaps(secid, days)
    if len(snaps) < 5:
        return None
    name = name or (snaps and secid) or secid
    bars = today_bars(secid)
    # 昨收：若 store 最后一根就是今天，则取倒数第二根
    prev_close = float(snaps[-1].get("close") or 0)
    today = str(bars[-1].get("day", ""))[:10] if bars else ""
    if today and str(snaps[-1].get("date", ""))[:10] == today and len(snaps) >= 2:
        prev_close = float(snaps[-2].get("close") or 0)

    os.makedirs(os.path.dirname(out_path) or OUT_DIR, exist_ok=True)
    if not out_path:
        os.makedirs(OUT_DIR, exist_ok=True)
        out_path = os.path.join(OUT_DIR, "%s.png" % secid)

    fig = plt.figure(figsize=(15, 5.6), dpi=dpi)
    gs = fig.add_gridspec(2, 2, height_ratios=[3.2, 1], hspace=0.12, wspace=0.14)
    _draw_daily(fig.add_subplot(gs[0, 0]), fig.add_subplot(gs[1, 0]),
                snaps, name, code6_of(secid))
    _draw_minute(fig.add_subplot(gs[0, 1]), fig.add_subplot(gs[1, 1]), bars, prev_close)
    fig.suptitle("%s %s · 日K / 分时" % (name, code6_of(secid)), fontsize=13, y=0.98)
    fig.savefig(out_path, dpi=dpi, bbox_inches="tight")

    # 超 2MB 自动降 dpi 重出（企微 image 硬限）
    tries = 0
    while os.path.getsize(out_path) > _IMAGE_LIMIT and dpi > 60 and tries < 4:
        dpi = int(dpi * 0.75)
        for ax in fig.axes:
            ax.clear()
        _draw_daily(fig.axes[0], fig.axes[2], snaps, name, code6_of(secid))
        _draw_minute(fig.axes[1], fig.axes[3], bars, prev_close)
        fig.savefig(out_path, dpi=dpi, bbox_inches="tight")
        tries += 1
    plt.close(fig)
    return out_path


if __name__ == "__main__":
    import sys
    args = sys.argv[1:]
    secid = args[0] if args else "sh603986"
    p = render_pair(secid, name=args[1] if len(args) > 1 else "")
    if p:
        print("OK %.1f KB -> %s" % (os.path.getsize(p) / 1024, p))
    else:
        print("数据不足:", secid)
