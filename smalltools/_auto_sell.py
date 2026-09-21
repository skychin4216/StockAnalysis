# -*- coding: utf-8 -*-
"""T+1 自动止盈止损守护（2026-09-21 用户需求 · Q2）

规则（**2026-09-21 模拟交易实证后定为 ATR 模式，见 _paper_sim.py**）：

    · **T+1 才可卖**（A 股规则；买入当天只监控不下单）
    · 出场模式 `--mode`：
        **atr**（默认）止损 = 买入价 − 2×ATR14，最长持有 10 日到期卖出
        trail  移动止盈：自最高价回撤 X% 卖出（默认 8%）
        hold   固定持有 N 日后卖出
    · 只在交易时段运行；收盘后不动作

    ⚠️ 「固定 +3%/-5%」模式已于 2026-09-21 **删除**（用户确认）。原因：18 年
    样本外模拟（已扣全部交易成本）显示它是**负期望**：
        固定+3%/-5%   胜率58.7%  均收益 -0.918%  PF 0.61   ← 已删
        ATR止损2x     胜率47.1%  均收益 +2.553%  PF 1.55   ← 默认
        持有10日      胜率51.0%  均收益 +2.973%  PF 1.66
    高胜率掩盖了糟糕盈亏比（赚2.46% 亏5.71%）；且固定百分比止损在 A 股日频
    易被**日内噪音打掉**，杀掉本可继续上涨的票（持有10日均盈 +14.71%）。

持仓来源 `data/_auto_positions.json`（`_remote_cmd.py` 下单后写入）。
下单走 `_trade_gateway`（默认 dry_run；`TRADE_LIVE=1` 才实盘）。

CLI:
    python _auto_sell.py --once                 # 跑一轮
    python _auto_sell.py --daemon               # 常驻（默认 30s 一轮）
    python _auto_sell.py --take-profit 0.03 --stop-loss 0.05
"""
from __future__ import annotations

import datetime as dt
import json
import os
import sys
import time
from typing import Any, Dict, List, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _trade_gateway import Order, build as build_gateway   # noqa: E402
import _remote_cmd as RC                                    # noqa: E402

try:
    import _trade_calendar as TC                # noqa: PLC0415
except Exception:                               # noqa: BLE001
    TC = None

STOP_LOSS = 0.05         # ATR 取不到时的兜底止损比例
ATR_K = 2.0              # atr 模式：买入价 − k×ATR14
TRAIL_PCT = 0.08         # trail 模式：自最高价回撤 8%
MAX_HOLD = 10            # 最长持有交易日（到期强制卖出）
SESSION = ((9 * 60 + 25), (15 * 60))    # 09:25~15:00


def atr14(secid: str, k: float = ATR_K) -> float:
    """近 14 日 ATR（用于 ATR 止损位）。取不到返回 0。"""
    try:
        from _kline_store import load_store        # noqa: PLC0415
        snaps = (load_store().get(secid) or {}).get("snaps") or []
        if len(snaps) < 16:
            return 0.0
        trs = []
        for j in range(len(snaps) - 14, len(snaps)):
            h = float(snaps[j].get("high") or 0)
            l = float(snaps[j].get("low") or 0)
            pc = float(snaps[j - 1].get("close") or 0)
            trs.append(max(h - l, abs(h - pc), abs(l - pc)))
        return k * (sum(trs) / len(trs)) if trs else 0.0
    except Exception:                              # noqa: BLE001
        return 0.0


def in_session() -> bool:
    now = dt.datetime.now()
    if TC is not None:
        try:
            if not TC.is_trading_day(now.date()):
                return False
        except Exception:                        # noqa: BLE001
            pass
    hm = now.hour * 60 + now.minute
    return SESSION[0] <= hm <= SESSION[1]


def last_price(secid: str) -> float:
    """当前价：优先当天分时最后一根，回退日K收盘。"""
    try:
        import _intraday as I                    # noqa: PLC0415
        bars = I.fetch_m5(RC.PC.code6({"secid": secid}), 48, scale="5") or []
        if bars:
            return float(bars[-1].get("close") or 0)
    except Exception:                            # noqa: BLE001
        pass
    try:
        from _kline_store import load_store      # noqa: PLC0415
        snaps = (load_store().get(secid) or {}).get("snaps") or []
        return float(snaps[-1].get("close") or 0) if snaps else 0.0
    except Exception:                            # noqa: BLE001
        return 0.0


def run_once(gw, stop_loss: float = STOP_LOSS,
             mode: str = "atr", max_hold: int = MAX_HOLD,
             trail: float = TRAIL_PCT, atr_k: float = ATR_K) -> Dict[str, Any]:
    """扫一遍持仓并执行 T+1 止盈/止损。返回统计。"""
    positions = RC.load_positions()
    today = dt.date.today().strftime("%Y-%m-%d")
    stats = {"checked": 0, "skipped_t1": 0, "tp": 0, "sl": 0, "errors": 0}
    if not positions:
        return stats

    orders: List[Order] = []
    hits: List[Dict[str, Any]] = []
    for p in positions:
        if p.get("state") != "open":
            continue
        stats["checked"] += 1
        if p.get("buy_date") == today:
            stats["skipped_t1"] += 1             # T+1：当天买入不可卖
            continue
        px = last_price(p["symbol"])
        if px <= 0:
            stats["errors"] += 1
            continue
        p["last_price"] = px
        buy = float(p.get("buy_price") or 0)
        if buy <= 0:
            continue
        chg = (px / buy - 1) * 100
        p["pnl_pct"] = round(chg, 2)
        held = (dt.date.fromisoformat(today) - dt.date.fromisoformat(p["buy_date"])).days

        tag: Optional[str] = None
        if held >= max_hold:                      # ① 到期优先（所有模式共用）
            tag = "到期%+.2f%%(%d日)" % (chg, held)
            stats["expired"] = stats.get("expired", 0) + 1
        elif mode == "trail":                     # ② 移动止盈（自最高价回撤）
            peak = max(float(p.get("peak") or buy), px)
            p["peak"] = peak
            if px <= peak * (1 - trail):
                tag = "移动止盈%+.2f%%" % chg
                stats["tp"] += 1
        else:                                     # ④ atr（默认）：ATR 止损
            width = atr14(p["symbol"], atr_k)
            sl_px = buy - width if width > 0 else buy * (1 - stop_loss)
            p["sl_price"] = round(sl_px, 3)
            if px <= sl_px:
                tag = "ATR止损%+.2f%%" % chg
                stats["sl"] += 1
        if tag is None:                           # 未触发 → 只更新浮盈，不下单
            continue
        p["exit_tag"] = tag
        orders.append(Order(symbol=p["symbol"], side="sell", qty=int(p["qty"]),
                            price=px, order_type="limit", reason=tag))
        hits.append(p)

    if orders:
        res = gw.place(orders, reason="T+1自动止盈止损")
        oks = res.get("ok") or []
        ok_syms = {o.get("symbol") for o in oks}
        for p in hits:
            if p["symbol"] in ok_syms:
                p["state"] = "closed"
                p["exit_price"] = p.get("last_price")
                p["exit_date"] = today
        try:
            import push_channel as PC2           # noqa: PLC0415
            cfg = PC2.load_notify_cfg()
            body = "\n".join("%s %s %s @%.2f（成本%.2f）"
                             % (h.get("name"), h["symbol"], h.get("exit_tag"),
                                h.get("last_price"), h.get("buy_price")) for h in hits)
            PC2.push("自动止盈止损触发（%d 笔）" % len(hits), body, cfg, kind="notice")
        except Exception:                        # noqa: BLE001
            pass

    RC.save_positions(positions)
    return stats


if __name__ == "__main__":
    a = sys.argv[1:]

    def _opt(name: str, dflt):
        return type(dflt)(a[a.index(name) + 1]) if name in a else dflt

    mode = _opt("--mode", "atr")
    sl = _opt("--stop-loss", STOP_LOSS)
    trail = _opt("--trail", TRAIL_PCT)
    atr_k = _opt("--atr-k", ATR_K)
    mh = _opt("--max-hold", MAX_HOLD)
    gw = build_gateway("paper")                  # 默认模拟盘
    kw = dict(mode=mode, max_hold=mh, trail=trail, atr_k=atr_k)
    if "--daemon" in sys.argv:
        itv = _opt("--interval", 30)
        print("自动卖出守护：mode=%s / ATR%.1fx / 最长%d日 / %ds 轮询 / %s"
              % (mode, atr_k, mh, itv, "实盘" if not gw.dry_run else "dry_run"))
        while True:
            if in_session():
                try:
                    s = run_once(gw, stop_loss=sl, **kw)
                    if s.get("tp") or s.get("sl") or s.get("expired"):
                        print(time.strftime("%H:%M:%S"), s, flush=True)
                except Exception as e:           # noqa: BLE001
                    print("守护异常:", e)
            time.sleep(itv)
    else:
        print("交易时段内" if in_session() else "非交易时段（只统计不下单）")
        print(run_once(gw, stop_loss=sl, **kw))
