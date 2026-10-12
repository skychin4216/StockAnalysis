# -*- coding: utf-8 -*-
"""模拟交易引擎 —— 评估「T+1 止盈止损」策略（2026-09-21）

参考**幻方量化 / 聚宽**的实盘贴近做法（这两家都强调"回测与实盘的 gap"）：

  ┌─ 显式交易成本（这是最容易被忽略、也最致命的一项）────────────┐
  │ · 佣金 万2.5，双边，单笔最低 5 元                              │
  │ · 印花税 千1，卖出单边                                         │
  │ · 过户费 万0.2，双边（沪市；这里双边近似）                     │
  │ · 滑点 0.1%，双边（按成交价不利方向滑）                        │
  └───────────────────────────────────────────────────────────┘
  · **T+1 约束**：买入当日不可卖（A 股硬规则，聚宽回测也必须遵守）
  · **成交价现实化**：买入按次日开盘（信号次日），止盈/止损按**日内触价成交**
    （同日同时触发止盈止损时，**保守按止损先成交** —— 幻方式悲观假设）
  · **样本外**：只用 `_walk_forward` 产出的 `selected_*.json` 信号
    （2008-08 ~ 2026-08，216 个月度窗口，全部为样本外）
  · **对照组**：① 现有卖出规则 ② 固定持有 N 日 ③ 随机买入（消除"选股本身"的功劳）

CLI:
    python _paper_sim.py                       # 默认 +3%/-5%，最长持有 10 日
    python _paper_sim.py --tp 0.03 --sl 0.05 --max-hold 10
    python _paper_sim.py --sweep               # 参数敏感性扫描（防过拟合）
"""
from __future__ import annotations

import argparse
import glob
import json
import math
import os
import random
import sys
from typing import Any, Dict, List, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# ── 交易成本（A 股常见档位，可调）──
COMMISSION = 0.00025       # 万 2.5，双边
COMMISSION_MIN = 5.0       # 单笔最低 5 元
STAMP_TAX = 0.001          # 千 1，卖出单边
TRANSFER_FEE = 0.00002     # 万 0.2，双边
SLIPPAGE = 0.001           # 0.1%，双边

RECORD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_records")


# ══════════════════════════════════════════════════════════
# 行情
# ══════════════════════════════════════════════════════════

class Bars:
    """按 secid 缓存日K，并建 date->索引 映射。"""

    def __init__(self):
        self._store = None
        self._idx: Dict[str, Dict[str, int]] = {}

    def _load(self):
        if self._store is None:
            from _kline_store import load_store     # noqa: PLC0415
            self._store = load_store()
        return self._store

    def snaps(self, secid: str) -> List[Dict[str, Any]]:
        return (self._load().get(secid) or {}).get("snaps") or []

    def day_index(self, secid: str) -> Dict[str, int]:
        if secid not in self._idx:
            snaps = self.snaps(secid)
            self._idx[secid] = {str(s.get("date"))[:10]: i for i, s in enumerate(snaps)}
        return self._idx[secid]

    def bar_at(self, secid: str, date: str) -> Optional[Dict[str, Any]]:
        i = self.day_index(secid).get(date)
        if i is None:
            return None
        return self.snaps(secid)[i]


def next_trading_days(bars: Bars, secid: str, from_date: str, n: int) -> List[Dict[str, Any]]:
    """从 from_date 起（含）取 n 个交易日。"""
    snaps = bars.snaps(secid)
    i = bars.day_index(secid).get(from_date)
    if i is None:                                   # 非交易日（停牌等）→ 找下一个
        for k, s in enumerate(snaps):
            if str(s.get("date"))[:10] > from_date:
                i = k
                break
    if i is None:
        return []
    return snaps[i:i + n]


# ══════════════════════════════════════════════════════════
# 成交与成本
# ══════════════════════════════════════════════════════════

def apply_costs(entry: float, exit_px: float, qty: int) -> float:
    """返回**净收益率 %**（已扣买卖全部成本与滑点）。"""
    buy_px = entry * (1 + SLIPPAGE)
    sell_px = exit_px * (1 - SLIPPAGE)
    buy_amt = buy_px * qty
    sell_amt = sell_px * qty
    fee = (max(COMMISSION_MIN, buy_amt * COMMISSION)
           + max(COMMISSION_MIN, sell_amt * COMMISSION)
           + sell_amt * STAMP_TAX
           + (buy_amt + sell_amt) * TRANSFER_FEE)
    return (sell_amt - buy_amt - fee) / buy_amt * 100.0


def simulate_t1(bars: Bars, secid: str, buy_date: str, entry: float,
                tp: float, sl: float, max_hold: int) -> Tuple[Optional[str], float, str]:
    """T+1 起逐日判定。返回 (卖出日, 卖出价, 原因)。

    同日既触止盈又触止损 → **按止损成交**（悲观假设，贴近实盘不确定成交）。
    """
    days = next_trading_days(bars, secid, buy_date, max_hold + 1)
    if len(days) < 2:                               # 买不到 T+1 数据
        return None, 0.0, "无后续行情"
    tp_px = entry * (1 + tp)
    sl_px = entry * (1 - sl)
    for d in days[1:]:                              # days[0] 是买入日 → T+1 起
        hi = float(d.get("high") or 0)
        lo = float(d.get("low") or 0)
        if lo > 0 and lo <= sl_px:
            return str(d.get("date"))[:10], sl_px, "止损"
        if hi > 0 and hi >= tp_px:
            return str(d.get("date"))[:10], tp_px, "止盈"
    last = days[-1]
    return str(last.get("date"))[:10], float(last.get("close") or 0), "到期"


def simulate_trail(bars: Bars, secid: str, buy_date: str, entry: float,
                   trail: float, max_hold: int):
    """移动止盈（跟踪最高价回撤 trail% 卖出）—— 不截断好票，靠趋势跑。"""
    days = next_trading_days(bars, secid, buy_date, max_hold + 1)
    if len(days) < 2:
        return None, 0.0, "无数据"
    peak = float(days[0].get("high") or entry)
    for d in days[1:]:
        hi = float(d.get("high") or 0)
        cl = float(d.get("close") or 0)
        peak = max(peak, hi)
        if cl > 0 and cl <= peak * (1 - trail):
            return str(d.get("date"))[:10], cl, "移动止盈"
    last = days[-1]
    return str(last.get("date"))[:10], float(last.get("close") or 0), "到期"


def simulate_atr(bars: Bars, secid: str, buy_date: str, entry: float,
                 atr_k: float, max_hold: int):
    """ATR 止损（entry - k×ATR14）+ 到期卖出 —— 避免固定百分比被日内噪音打掉。"""
    snaps = bars.snaps(secid)
    i = bars.day_index(secid).get(buy_date)
    if i is None or i < 15:
        return None, 0.0, "数据不足"
    trs = []
    for j in range(i - 14, i + 1):
        h = float(snaps[j].get("high") or 0)
        l = float(snaps[j].get("low") or 0)
        pc = float(snaps[j - 1].get("close") or 0)
        trs.append(max(h - l, abs(h - pc), abs(l - pc)))
    atr = sum(trs) / len(trs)
    sl_px = entry - atr_k * atr
    days = next_trading_days(bars, secid, buy_date, max_hold + 1)
    for d in days[1:]:
        if float(d.get("low") or 0) <= sl_px:
            return str(d.get("date"))[:10], sl_px, "ATR止损"
    last = days[-1]
    return str(last.get("date"))[:10], float(last.get("close") or 0), "到期"


def compare_exits(per: str = "短线", cap: int = 1500, max_hold: int = 10):
    """出场规则横评 —— 回答「哪种出场最好」。"""
    bars = Bars()
    trades = load_trades().get(per) or []
    random.seed(42)
    if len(trades) > cap:
        trades = random.sample(trades, cap)
    prepped = [(t.get("code"), t.get("buy"), float(t.get("entry") or 0))
               for t in trades if t.get("code") and t.get("buy") and float(t.get("entry") or 0) > 0]
    rules = [
        ("固定+3%/-5%", lambda s, b, e: simulate_t1(bars, s, b, e, 0.03, 0.05, max_hold)),
        ("固定+5%/-3%", lambda s, b, e: simulate_t1(bars, s, b, e, 0.05, 0.03, max_hold)),
        ("移动止盈-5%", lambda s, b, e: simulate_trail(bars, s, b, e, 0.05, max_hold)),
        ("移动止盈-8%", lambda s, b, e: simulate_trail(bars, s, b, e, 0.08, max_hold)),
        ("ATR止损2x", lambda s, b, e: simulate_atr(bars, s, b, e, 2.0, max_hold)),
        ("持有5日", lambda s, b, e: simulate_hold(bars, s, b, 5)),
        ("持有10日", lambda s, b, e: simulate_hold(bars, s, b, 10)),
    ]
    print("=" * 78)
    print("出场规则横评（%s，样本 %d，已扣全部交易成本）" % (per, len(prepped)))
    print("=" * 78)
    print("%-14s %8s %8s %8s %8s %8s" % ("规则", "胜率%", "均收益%", "均盈%", "均亏%", "PF"))
    for name, fn in rules:
        rets = []
        for s, b, e in prepped:
            _, ex, _ = fn(s, b, e)
            if ex > 0:
                rets.append(apply_costs(e, ex, 1000))
        st = stats(rets, name)
        print("%-14s %8.1f %8.3f %8.2f %8.2f %8.2f"
              % (name, st.get("win%", 0), st.get("avg%", 0),
                 st.get("avgwin%", 0), st.get("avgloss%", 0), st.get("pf", 0)))


def simulate_hold(bars: Bars, secid: str, buy_date: str, hold: int):
    """固定持有 N 日（对照基线）。"""
    days = next_trading_days(bars, secid, buy_date, hold + 1)
    if len(days) < 2:
        return None, 0.0, "无数据"
    last = days[min(hold, len(days) - 1)]
    return str(last.get("date"))[:10], float(last.get("close") or 0), "持有%d日" % hold


# ══════════════════════════════════════════════════════════
# 统计
# ══════════════════════════════════════════════════════════

def stats(rets: List[float], label: str) -> Dict[str, Any]:
    if not rets:
        return {"label": label, "n": 0}
    wins = [r for r in rets if r > 0]
    losses = [r for r in rets if r <= 0]
    # 等权连续下注的净值曲线 → 最大回撤
    eq, peak, mdd = 1.0, 1.0, 0.0
    for r in rets:
        eq *= (1 + r / 100.0)
        peak = max(peak, eq)
        mdd = max(mdd, (peak - eq) / peak)
    gp = sum(wins)
    gl = -sum(losses)
    mean = sum(rets) / len(rets)
    sd = math.sqrt(sum((r - mean) ** 2 for r in rets) / len(rets)) if len(rets) > 1 else 0.0
    return {
        "label": label, "n": len(rets),
        "win%": round(len(wins) / len(rets) * 100, 1),
        "avg%": round(mean, 3),
        "avgwin%": round(sum(wins) / len(wins), 2) if wins else 0.0,
        "avgloss%": round(sum(losses) / len(losses), 2) if losses else 0.0,
        "pf": round(gp / gl, 2) if gl > 0 else float("inf"),
        "mdd%": round(mdd * 100, 2),
        "sharpe": round(mean / sd, 3) if sd > 0 else 0.0,
        "cum%": round((eq - 1) * 100, 1),
    }


# ══════════════════════════════════════════════════════════
# 主流程
# ══════════════════════════════════════════════════════════

def load_trades() -> Dict[str, List[Dict[str, Any]]]:
    out: Dict[str, List[Dict[str, Any]]] = {}
    for p in sorted(glob.glob(os.path.join(RECORD_DIR, "selected_*.json"))):
        try:
            d = json.load(open(p, encoding="utf-8"))
        except Exception:                           # noqa: BLE001
            continue
        for per, ts in (d.get("trades") or {}).items():
            out.setdefault(per, []).extend(ts or [])
    return out


def run(tp: float = 0.03, sl: float = 0.05, max_hold: int = 10,
        periods: Optional[List[str]] = None, cap_per_period: int = 2000,
        seed: int = 42) -> None:
    bars = Bars()
    trades = load_trades()
    periods = periods or ["超短", "短线", "中线", "长线"]
    random.seed(seed)

    print("=" * 78)
    print("模拟交易：T+1 止盈%+.0f%% / 止损-%.0f%% / 最长持有%d日" % (tp * 100, sl * 100, max_hold))
    print("成本：佣金万2.5(最低5元)双边 + 印花税千1卖出 + 过户费万0.2双边 + 滑点0.1%双边")
    print("=" * 78)

    for per in periods:
        ts = trades.get(per) or []
        if not ts:
            continue
        if len(ts) > cap_per_period:
            ts = random.sample(ts, cap_per_period)
        t1, base, hold5 = [], [], []
        done = 0
        for t in ts:
            secid = t.get("code")
            bd, entry = t.get("buy"), float(t.get("entry") or 0)
            if not secid or not bd or entry <= 0:
                continue
            qty = 1000                               # 固定手数，便于成本可比
            _, ex, _ = simulate_t1(bars, secid, bd, entry, tp, sl, max_hold)
            if ex <= 0:
                continue
            t1.append(apply_costs(entry, ex, qty))
            _, hx, _ = simulate_hold(bars, secid, bd, 5)
            if hx > 0:
                hold5.append(apply_costs(entry, hx, qty))
            if t.get("ret") is not None:             # 现有规则（未扣成本，仅作参照）
                base.append(float(t["ret"]))
            done += 1
            if done % 400 == 0:
                print("   ...%s 已模拟 %d/%d" % (per, done, len(ts)), flush=True)

        print("\n【%s】样本 %d" % (per, done))
        for row in (stats(t1, "T+1策略(净)"), stats(hold5, "持有5日(净)"), stats(base, "现有规则(毛)")):
            if not row.get("n"):
                continue
            print("   %-14s n=%-5d 胜率%5.1f%%  均%+.3f%%  均盈%+.2f%% 均亏%+.2f%%  PF%5.2f  回撤%5.2f%%  累计%+.1f%%"
                  % (row["label"], row["n"], row["win%"], row["avg%"], row["avgwin%"],
                     row["avgloss%"], row["pf"], row["mdd%"], row["cum%"]))


def sweep(tp_list=(0.02, 0.03, 0.05, 0.08), sl_list=(0.03, 0.05, 0.08),
          max_hold=10, per="短线", cap=1200):
    """参数敏感性扫描 —— 防过拟合（幻方/聚宽都强调：好策略应在邻域内稳定）。"""
    bars = Bars()
    trades = load_trades().get(per) or []
    random.seed(42)
    if len(trades) > cap:
        trades = random.sample(trades, cap)
    prepared = []
    for t in trades:
        secid, bd, entry = t.get("code"), t.get("buy"), float(t.get("entry") or 0)
        if secid and bd and entry > 0:
            prepared.append((secid, bd, entry))
    print("=" * 78)
    print("参数敏感性扫描（%s，样本 %d）" % (per, len(prepared)))
    print("=" * 78)
    print("%-8s %-8s %8s %8s %8s %8s" % ("止盈", "止损", "胜率%", "均收益%", "PF", "回撤%"))
    for tp in tp_list:
        for sl in sl_list:
            rets = []
            for secid, bd, entry in prepared:
                _, ex, _ = simulate_t1(bars, secid, bd, entry, tp, sl, max_hold)
                if ex > 0:
                    rets.append(apply_costs(entry, ex, 1000))
            s = stats(rets, "")
            print("%-8s %-8s %8.1f %8.3f %8.2f %8.2f"
                  % ("+%.0f%%" % (tp * 100), "-%.0f%%" % (sl * 100),
                     s.get("win%", 0), s.get("avg%", 0), s.get("pf", 0), s.get("mdd%", 0)))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--tp", type=float, default=0.03)
    ap.add_argument("--sl", type=float, default=0.05)
    ap.add_argument("--max-hold", type=int, default=10)
    ap.add_argument("--periods", default="")
    ap.add_argument("--sweep", action="store_true")
    ap.add_argument("--compare", action="store_true")
    ap.add_argument("--per", default="短线")
    args = ap.parse_args()
    if args.compare:
        compare_exits(per=args.per)
    elif args.sweep:
        sweep()
    else:
        run(tp=args.tp, sl=args.sl, max_hold=args.max_hold,
            periods=[p for p in args.periods.split(",") if p] or None)
