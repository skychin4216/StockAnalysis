# -*- coding: utf-8 -*-
"""ETF 选股思路实验室 · PC/exe 引擎（2026-09-11 新增）

与 APK 侧 `app/src/main/java/com/chin/stockanalysis/strategy/trade/EtfStrategyLab.kt`
**同源同口径**的 Python 移植：把 `选股思路/ETF选股思路.txt` 的方法论落成可执行、
可回测、可拟合、可修正的闭环，供 exe（AutoQuant GUI）与 APK 两端读取同一份结论。

一、选股五条件（日线底仓信号）
  ① RAS 相对强度(ETF/沪深300) 20 日斜率「绿转红」（相对走强）
  ② MACD 金叉 / DIF 底部拐头向上
  ③ OBV 在 OBV_MA20 上方（资金进场）
  ④ RSI(6) ∈ [rsi_lo, rsi_hi]（低位未超买）
  ⑤ 距 250 日高点回撤 ∈ [dd_lo, dd_hi]%（成长赛道低位区，可放宽）

二、买卖 / 做T 信号
  · 底仓买入：日线五条件全中（T 日收盘判定，T+1 开盘可买）
  · 底仓清仓：RAS 转绿 / RSI6 > rsi_overbought / MACD 死叉
  · 正T 低吸：RSI6 ≤ t_buy_rsi 且 MACD 绿柱缩短，OBV 不创新低
  · 反T 高抛：RSI6 ≥ t_sell_rsi 且 MACD 红柱缩短

三、回测：与 `_etf_buy.py` 同口径 —— T 日收盘出信号、T+1 开盘成交、单仓状态机、
  平仓后冷却 cool 天、沪深300 结构多头门控（close>MA20>MA60）、tp/sl/hold 离场。

四、网格拟合：搜索「信号阈值 × 离场参数」，用 `_etf_buy.exit_score` 同口径评分
  （胜率×2.5 + 盈亏比×4 + 收益×0.6 − 回撤惩罚）选最优，可一键应用为生效参数。

产物：
  smalltools/data/_etf_lab_params.json   生效参数（可应用拟合结果 / 恢复默认）
  smalltools/data/_etf_lab_report.json   完整实验室报告（供 exe GUI / APK 读取）

用法：
  python _etf_strategy_lab.py --live          # 当日选股判定 + 买卖/做T 信号（默认）
  python _etf_strategy_lab.py --fit           # 网格拟合并打印 Top 候选
  python _etf_strategy_lab.py --apply-best    # 拟合最佳 → 写回生效参数
  python _etf_strategy_lab.py --reset         # 恢复默认参数
  python _etf_strategy_lab.py --set rsi_hi=55 --set tp=3
  python _etf_strategy_lab.py --json          # 输出完整报告 JSON
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _etf_buy import (ETF_POOL, IDX_300, ensure_data, load_cache,  # noqa: E402
                      max_drawdown, rsi, sma)

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(HERE, "data")
PARAMS_FILE = os.path.join(DATA_DIR, "_etf_lab_params.json")
REPORT_FILE = os.path.join(DATA_DIR, "_etf_lab_report.json")

MIN_SNAPS = 260          # 判定/回测所需最少根数（250 日高 + 余量）

# ── 默认参数：源自 ETF选股思路.txt 原文口径，离场与 _etf_buy v0.3 定稿一致 ──
DEFAULT_PARAMS = {
    # 信号口径：strict=五条件全中（思路原文，最严格）；core=④⑤③ 且 ①或②；
    # rsi_pos=仅 ④RSI区间 ∧ ⑤回撤区间（最宽松）。拟合会在三档里挑最优 → 修正选股思路。
    "signal": "strict",
    "use_gate": True,
    "rsi_lo": 30.0,
    "rsi_hi": 55.0,
    "dd_lo": 40.0,           # 距250日高回撤下限%（成长ETF 40%~60%）
    "dd_hi": 60.0,
    "rsi_overbought": 75.0,  # 底仓清仓超买线
    "t_buy_rsi": 30.0,
    "t_sell_rsi": 70.0,
    "tp": 2.0,
    "sl": -6.0,
    "hold": 30,
    "cool": 30,
}

# ── 拟合网格（与 APK EtfStrategyLab 一致）──
GRID_RSI_HI = [50.0, 55.0, 60.0]
GRID_DD_LO = [20.0, 30.0, 40.0]
GRID_DD_HI = [50.0, 60.0]
GRID_TP = [2.0, 3.0, 4.0]
GRID_SL = [-4.0, -6.0]
GRID_HOLD = [20, 30]
FIT_MIN_TRADES = 8
FIT_TOP_N = 12

SIGNAL_VARIANTS = ["strict", "core", "rsi_pos"]
SIGNAL_LABEL = {"strict": "五条件全中", "core": "④⑤③+①或②", "rsi_pos": "④RSI+⑤回撤"}

MARK = ["①", "②", "③", "④", "⑤"]


def signal_ok(hits, variant):
    """按信号口径判断五条件命中是否构成「底仓买入」。"""
    if variant == "strict":
        return all(hits)
    if variant == "core":
        return hits[2] and hits[3] and hits[4] and (hits[0] or hits[1])
    return hits[3] and hits[4]          # rsi_pos


# ═══════════════════════════ 参数存取 ═══════════════════════════

def load_params():
    p = dict(DEFAULT_PARAMS)
    if os.path.exists(PARAMS_FILE):
        try:
            with open(PARAMS_FILE, encoding="utf-8") as f:
                p.update({k: v for k, v in json.load(f).items() if k in DEFAULT_PARAMS})
        except Exception:  # noqa: BLE001
            pass
    return p


def save_params(p):
    os.makedirs(DATA_DIR, exist_ok=True)
    with open(PARAMS_FILE, "w", encoding="utf-8") as f:
        json.dump(p, f, ensure_ascii=False, indent=1)
    print(f"[params] 已写回生效参数 {PARAMS_FILE}")


def reset_params():
    if os.path.exists(PARAMS_FILE):
        os.remove(PARAMS_FILE)
    return dict(DEFAULT_PARAMS)


# ═══════════════════════════ 指标 ═══════════════════════════

def ema(vals, n):
    out = []
    k = 2.0 / (n + 1)
    prev = None
    for v in vals:
        prev = v if prev is None else v * k + prev * (1 - k)
        out.append(prev)
    return out


def slope(vals, end, win):
    """最小二乘斜率（窗口末点 = end，长度 win）。"""
    start = end - win + 1
    if start < 0:
        return 0.0
    seg = vals[start:end + 1]
    n = len(seg)
    xm = (n - 1) / 2.0
    ym = sum(seg) / n
    num = den = 0.0
    for k, v in enumerate(seg):
        dx = k - xm
        num += dx * (v - ym)
        den += dx * dx
    return 0.0 if den == 0 else num / den


def build_ind(ent, bench):
    """把一只 ETF 的 snaps 预计算成指标序列字典（拟合网格复用，避免重复计算）。"""
    snaps = ent.get("snaps") or []
    if len(snaps) < MIN_SNAPS:
        return None
    dates = [s["date"] for s in snaps]
    closes = [s["close"] for s in snaps]
    opens = [s.get("open", s["close"]) for s in snaps]
    vols = [s.get("volume") or 0 for s in snaps]
    ma20 = sma(closes, 20)
    ma250 = sma(closes, 250)
    r6 = rsi(closes, 6)
    e12, e26 = ema(closes, 12), ema(closes, 26)
    dif = [a - b for a, b in zip(e12, e26)]
    dea = ema(dif, 9)
    hist = [(a - b) * 2 for a, b in zip(dif, dea)]

    obv = [0.0]
    for i in range(1, len(closes)):
        if closes[i] > closes[i - 1]:
            obv.append(obv[-1] + vols[i])
        elif closes[i] < closes[i - 1]:
            obv.append(obv[-1] - vols[i])
        else:
            obv.append(obv[-1])
    obv20 = sma(obv, 20)

    dd250, win, peak = [], [], None
    for c in closes:
        win.append(c)
        if len(win) > 250:
            win.pop(0)
        peak = max(win)
        dd250.append((1 - c / peak) * 100.0 if peak > 0 else 0.0)

    # RAS 相对强度：ETF/沪深300 按日期对齐后前向填充
    ras_slope = [0.0] * len(closes)
    g2r = [False] * len(closes)
    r2g = [False] * len(closes)
    if bench:
        bmap = dict(zip(bench["dates"], bench["closes"]))
        rs, last = [], None
        for i, d in enumerate(dates):
            b = bmap.get(d)
            cur = closes[i] / b if (b and b > 0) else None
            if cur is not None:
                last = cur
            rs.append(last)
        for i in range(len(closes)):
            if i >= 20 and rs[i] is not None and rs[i - 20] is not None:
                sl = slope(rs, i, 20)
                ras_slope[i] = sl
                if i >= 1:
                    prev = ras_slope[i - 1]
                    g2r[i] = prev < 0 < sl
                    r2g[i] = prev > 0 > sl
    return {
        "code": ent.get("code") or "", "name": ent.get("name") or "",
        "dates": dates, "opens": opens, "closes": closes,
        "ma20": ma20, "ma250": ma250, "rsi6": r6,
        "obv": obv, "obv20": obv20, "dd250": dd250,
        "dif": dif, "dea": dea, "hist": hist,
        "ras_slope": ras_slope, "g2r": g2r, "r2g": r2g,
    }


def build_bench(cache):
    ent = cache.get(IDX_300[0])
    if not ent or len(ent.get("snaps") or []) < 60:
        return None
    snaps = ent["snaps"]
    return {"dates": [s["date"] for s in snaps], "closes": [s["close"] for s in snaps]}


def gate_flags(cache):
    """沪深300 结构多头门控：{date: bool}（close>MA20>MA60）。"""
    b = build_bench(cache)
    if not b:
        return {}, "沪深300 数据不足"
    m20, m60 = sma(b["closes"], 20), sma(b["closes"], 60)
    flags = {b["dates"][i]: b["closes"][i] > m20[i] and m20[i] > m60[i]
             for i in range(60, len(b["closes"]))}
    i = len(b["closes"]) - 1
    c, a20, a60 = b["closes"][i], m20[i], m60[i]
    state = ("多头排列 ✅ 可执行低吸" if c > a20 > a60
             else "空头排列 ⛔ 暂停低吸" if c < a20 < a60 else "均线纠缠 ⚠ 谨慎")
    note = (f"沪深300 {b['dates'][i]} 收{c:.0f} / MA20 {a20:.0f} / MA60 {a60:.0f} · {state}")
    return flags, note


# ═══════════════════════════ 五条件 ═══════════════════════════

def hit_at(ind, i, p):
    """返回五条件布尔列表 [①RAS绿转红, ②MACD金叉/拐头, ③OBV上行, ④RSI区间, ⑤回撤区间]。"""
    c1 = ind["g2r"][i]
    c2 = (ind["dif"][i] > ind["dea"][i]
          or (i >= 2 and ind["dif"][i] > ind["dif"][i - 1] and ind["dif"][i - 1] <= ind["dif"][i - 2]))
    c3 = i >= 20 and ind["obv"][i] > ind["obv20"][i]
    c4 = p["rsi_lo"] <= ind["rsi6"][i] <= p["rsi_hi"]
    c5 = p["dd_lo"] <= ind["dd250"][i] <= p["dd_hi"]
    return [bool(c1), bool(c2), bool(c3), bool(c4), bool(c5)]


def hit_text(hits):
    s = "".join(MARK[i] for i, h in enumerate(hits) if h)
    return s or "—"


def pick_card(ind, p):
    i = len(ind["closes"]) - 1
    hits = hit_at(ind, i, p)
    ras = ("绿转红" if ind["g2r"][i] else "红转绿" if ind["r2g"][i]
           else "强势" if ind["ras_slope"][i] > 0 else "弱势")
    if i >= 1 and ind["dif"][i] > ind["dea"][i] and ind["dif"][i - 1] <= ind["dea"][i - 1]:
        macd = "金叉"
    elif ind["dif"][i] > ind["dea"][i]:
        macd = "多头"
    elif i >= 1 and ind["dif"][i] < ind["dea"][i] and ind["dif"][i - 1] >= ind["dea"][i - 1]:
        macd = "死叉"
    elif i >= 2 and ind["dif"][i] > ind["dif"][i - 1]:
        macd = "DIF拐头"
    else:
        macd = "空头"
    dd_mid = (p["dd_lo"] + p["dd_hi"]) / 2
    return {
        "code": ind["code"], "name": ind["name"], "close": round(ind["closes"][i], 3),
        "dd250": round(ind["dd250"][i], 1), "rsi6": round(ind["rsi6"][i], 1),
        "ras": ras, "macd": macd,
        "obv": "上行" if hits[2] else "下行",
        "hits": hit_text(hits), "pass": sum(hits),
        "selected": signal_ok(hits, p.get("signal", "strict")),
        "score": round(sum(hits) * 100 - abs(ind["dd250"][i] - dd_mid) + (8 if ras == "绿转红" else 0), 1),
    }


def build_signals(inds, p):
    out = []
    for ind in inds:
        i = len(ind["closes"]) - 1
        if i < 2:
            continue
        nm, code, close = ind["name"], ind["code"], ind["closes"][i]
        if signal_ok(hit_at(ind, i, p), p.get("signal", "strict")):
            out.append({"code": code, "name": nm, "close": round(close, 3), "kind": "底仓买入",
                        "detail": f"[{SIGNAL_LABEL.get(p.get('signal'), p.get('signal'))}] "
                                  f"回撤{ind['dd250'][i]:.0f}% RSI6 {ind['rsi6'][i]:.0f}（次日开盘可买）"})
        death = ind["dif"][i] < ind["dea"][i] and ind["dif"][i - 1] >= ind["dea"][i - 1]
        if ind["r2g"][i]:
            out.append({"code": code, "name": nm, "close": round(close, 3), "kind": "底仓清仓",
                        "detail": "RAS 相对强度红转绿 · 主趋势转弱"})
        elif ind["rsi6"][i] > p["rsi_overbought"]:
            out.append({"code": code, "name": nm, "close": round(close, 3), "kind": "底仓清仓",
                        "detail": f"RSI6 {ind['rsi6'][i]:.0f} > {p['rsi_overbought']:.0f} 超买"})
        elif death:
            out.append({"code": code, "name": nm, "close": round(close, 3), "kind": "底仓清仓",
                        "detail": "MACD 死叉"})
        if (ind["rsi6"][i] <= p["t_buy_rsi"] and ind["hist"][i] > ind["hist"][i - 1] < 0
                and ind["obv"][i] >= ind["obv"][i - 1]):
            out.append({"code": code, "name": nm, "close": round(close, 3), "kind": "正T低吸",
                        "detail": f"RSI6 {ind['rsi6'][i]:.0f} ≤ {p['t_buy_rsi']:.0f} · MACD绿柱缩短 · OBV不创新低（先买后卖）"})
        if (ind["rsi6"][i] >= p["t_sell_rsi"] and ind["hist"][i] < ind["hist"][i - 1] > 0):
            out.append({"code": code, "name": nm, "close": round(close, 3), "kind": "反T高抛",
                        "detail": f"RSI6 {ind['rsi6'][i]:.0f} ≥ {p['t_sell_rsi']:.0f} · MACD红柱缩短（先卖后买）"})
    return out


# ═══════════════════════════ 回测 / 统计 ═══════════════════════════

def _simulate(ind, sig_idx, tp, sl, hold, cool):
    s, n = ind, len(ind["closes"])
    trades, next_allowed = [], 0
    for i in sig_idx:
        if i < next_allowed:
            continue
        ei = i + 1
        if ei >= n:
            continue
        epx = s["opens"][ei]
        if epx <= 0:
            continue
        exit_idx = -1
        end = min(ei + hold, n - 1)
        for j in range(ei + 1, end + 1):
            chg = (s["closes"][j] / epx - 1) * 100
            if chg >= tp or chg <= sl:
                exit_idx = j
                break
        if exit_idx < 0:
            exit_idx = end
        if exit_idx <= ei:
            continue
        xpx = s["closes"][exit_idx]
        trades.append({"code": s["code"], "name": s["name"],
                       "entry_d": s["dates"][ei], "entry_px": round(epx, 3),
                       "exit_d": s["dates"][exit_idx], "exit_px": round(xpx, 3),
                       "pnl_pct": round((xpx / epx - 1) * 100, 2), "days": exit_idx - ei})
        next_allowed = exit_idx + max(cool, 1)
    return trades


def _signal_idx(ind, p, gate_flags):
    idx = []
    for i in range(250, len(ind["closes"]) - 1):
        if p["use_gate"] and not gate_flags.get(ind["dates"][i], False):
            continue
        if signal_ok(hit_at(ind, i, p), p.get("signal", "strict")):
            idx.append(i)
    return idx


def backtest(inds, p, gate_flags):
    trades = []
    for ind in inds:
        trades.extend(_simulate(ind, _signal_idx(ind, p, gate_flags), p["tp"], p["sl"], p["hold"], p["cool"]))
    trades.sort(key=lambda t: (t["entry_d"], t["code"]))
    return trades


def stats_of(trades):
    if not trades:
        return {"n": 0, "win_rate": 0.0, "avg_win": 0.0, "avg_loss": 0.0,
                "profit_factor": 0.0, "total_ret": 0.0, "cagr": 0.0, "mdd": 0.0, "avg_days": 0.0}
    wins = [t for t in trades if t["pnl_pct"] > 0]
    losses = [t for t in trades if t["pnl_pct"] <= 0]
    eq, acc = [], 1.0
    for t in trades:
        acc *= 1 + t["pnl_pct"] / 100
        eq.append(acc)
    win_sum = sum(t["pnl_pct"] for t in wins)
    loss_sum = sum(t["pnl_pct"] for t in losses)
    from datetime import date as _d
    sd = min(t["entry_d"] for t in trades)
    ed = max(t["exit_d"] for t in trades)
    yrs = max((_d(*map(int, ed.split("-"))) - _d(*map(int, sd.split("-")))).days / 365.25, 0.1)
    return {
        "n": len(trades),
        "win_rate": round(len(wins) / len(trades) * 100, 1),
        "avg_win": round(win_sum / len(wins), 2) if wins else 0.0,
        "avg_loss": round(loss_sum / len(losses), 2) if losses else 0.0,
        "profit_factor": round(win_sum / -loss_sum, 2) if losses and loss_sum else 99.0,
        "total_ret": round((acc - 1) * 100, 1),
        "cagr": round((acc ** (1 / yrs) - 1) * 100, 1) if acc > 0 else -100.0,
        "mdd": round(max_drawdown(eq) * 100, 1),
        "avg_days": round(sum(t["days"] for t in trades) / len(trades), 1),
    }


def exit_score(st):
    """与 _etf_buy.exit_score 同口径。"""
    pf = max(min(st["profit_factor"], 6.0), -6.0)
    return st["win_rate"] * 2.5 + pf * 4 + max(st["total_ret"] * 0.6, -60.0) + st["mdd"] * 0.3


# ═══════════════════════════ 网格拟合 ═══════════════════════════

def fit(inds, base, gate_flags):
    rows = []
    for variant in SIGNAL_VARIANTS:
        for rsi_hi in GRID_RSI_HI:
            for dd_lo in GRID_DD_LO:
                for dd_hi in GRID_DD_HI:
                    if dd_lo >= dd_hi:
                        continue
                    sp = dict(base, signal=variant, rsi_hi=rsi_hi, dd_lo=dd_lo, dd_hi=dd_hi)
                    sig = {id(ind): _signal_idx(ind, sp, gate_flags) for ind in inds}
                    sig = {k: v for k, v in sig.items() if v}
                    if not sig:
                        continue
                    for tp in GRID_TP:
                        for sl in GRID_SL:
                            for hold in GRID_HOLD:
                                trades = []
                                for ind in inds:
                                    idx = sig.get(id(ind))
                                    if idx:
                                        trades.extend(_simulate(ind, idx, tp, sl, hold, base["cool"]))
                                if len(trades) < FIT_MIN_TRADES:
                                    continue
                                trades.sort(key=lambda t: (t["entry_d"], t["code"]))
                                st = stats_of(trades)
                                rows.append({
                                    "signal": variant,
                                    "rsi_hi": rsi_hi, "dd_lo": dd_lo, "dd_hi": dd_hi,
                                    "tp": tp, "sl": sl, "hold": hold,
                                    "stats": st, "score": round(exit_score(st), 1),
                                })
    rows.sort(key=lambda r: r["score"], reverse=True)
    return rows[:FIT_TOP_N]


# ═══════════════════════════ 主流程 ═══════════════════════════

def run(cache, p):
    gate_flags, gate_note = gate_flags_safe(cache)
    bench = build_bench(cache)
    inds, skipped = [], []
    for code, name in ETF_POOL:
        ent = cache.get(code)
        if not ent:
            skipped.append((code, name, "无缓存"))
            continue
        ent = dict(ent, code=code)
        ind = build_ind(ent, bench)
        if ind is None:
            skipped.append((code, name, f"样本<{MIN_SNAPS}"))
            continue
        ind["code"] = code
        ind["name"] = ent.get("name") or name
        inds.append(ind)
    if not inds:
        return {"error": "缓存内 ETF 样本不足，请先运行 _etf_strategy_lab.py 拉取行情"}

    picks = sorted([pick_card(ind, p) for ind in inds],
                   key=lambda r: (r["selected"], r["pass"], r["score"]), reverse=True)
    signals = build_signals(inds, p)
    trades = backtest(inds, p, gate_flags)
    stats = stats_of(trades)
    fits = fit(inds, p, gate_flags)
    asof = max((ind["dates"][-1] for ind in inds), default="-")
    return {
        "as_of": asof,
        "pool_size": len(inds),
        "skipped": [{"code": c, "name": n, "reason": r} for c, n, r in skipped],
        "gate_ok": bool(gate_flags.get(asof, False)),
        "gate_note": gate_note,
        "params": p,
        "picks": picks,
        "signals": signals,
        "stats": stats,
        "recent_trades": trades[-8:],
        "fits": fits,
        "engine": "EtfStrategyLab v1.0 (PC) — 五条件选股 + 买卖/做T + 回测 + 网格拟合",
    }


def gate_flags_safe(cache):
    try:
        return gate_flags(cache)
    except Exception:  # noqa: BLE001
        return {}, "沪深300 数据不足"


def ensure_cache():
    """复用 _etf_buy 的断点续拉（max_stale_days=0：追到最近工作日，避免跑在过期 K 线上）。"""
    return ensure_data(list(ETF_POOL) + [IDX_300], max_stale_days=0)


def main():
    ap = argparse.ArgumentParser(description="ETF 选股思路实验室（PC）")
    ap.add_argument("--live", action="store_true", help="当日选股判定 + 买卖/做T 信号（默认）")
    ap.add_argument("--fit", action="store_true", help="网格拟合并打印 Top 候选")
    ap.add_argument("--apply-best", action="store_true", help="拟合最佳 → 写回生效参数")
    ap.add_argument("--reset", action="store_true", help="恢复默认参数")
    ap.add_argument("--json", action="store_true", help="输出完整报告 JSON")
    ap.add_argument("--no-fetch", action="store_true", help="不拉行情，直接用本地缓存")
    ap.add_argument("--set", action="append", default=[], metavar="K=V", help="临时覆盖参数，可多次")
    args = ap.parse_args()

    p = reset_params() if args.reset else load_params()
    if args.reset:
        print("[params] 已恢复默认参数")
    for kv in args.set:
        k, _, v = kv.partition("=")
        if k not in DEFAULT_PARAMS:
            continue
        default = DEFAULT_PARAMS[k]
        if isinstance(default, bool):
            p[k] = v.strip().lower() in ("1", "true", "yes", "on")
        elif isinstance(default, str):
            p[k] = v.strip()
        elif isinstance(default, int):
            p[k] = int(float(v))
        else:
            p[k] = float(v)
    if args.set:
        save_params(p)

    cache = load_cache() if args.no_fetch else ensure_cache()
    if not cache:
        print("无本地缓存，且拉取失败。请检查网络后重试：python _etf_strategy_lab.py --live")
        return 1

    report = run(cache, p)
    if report.get("error"):
        print("错误:", report["error"])
        return 2
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=1))

    if args.apply_best:
        if not report["fits"]:
            print("无可应用拟合结果")
            return 3
        best = report["fits"][0]
        for k in ("signal", "rsi_hi", "dd_lo", "dd_hi", "tp", "sl", "hold"):
            p[k] = best[k]
        save_params(p)
        print(f"[apply] 已应用最佳拟合 score={best['score']} "
              f"信号={best['signal']} RSI≤{best['rsi_hi']:.0f} 回撤{best['dd_lo']:.0f}~{best['dd_hi']:.0f}% "
              f"tp{best['tp']} sl{best['sl']} hold{best['hold']}")

    if not args.json:
        print(f"\nETF 选股思路实验室  asof={report['as_of']}  池={report['pool_size']} 只")
        print(f"门控: {report['gate_note']}")
        print("=" * 96)
        print(f"② 选股判定（五条件）  ①RAS绿转红 ②MACD金叉/拐头 ③OBV上行 ④RSI区间 ⑤回撤区间"
              f"  [当前信号口径 {p.get('signal')} = {SIGNAL_LABEL.get(p.get('signal'), '-')}]")
        print(f"{'名称':<12}{'代码':<10}{'收盘':>8}{'回撤250':>9}{'RSI6':>7}  "
              f"{'RAS':<5}{'MACD':<8}{'OBV':<4}{'命中':<6}{'状态'}")
        for r in report["picks"]:
            print(f"{r['name']:<12}{r['code']:<10}{r['close']:>8.3f}{r['dd250']:>8.0f}%"
                  f"{r['rsi6']:>7.0f}  {r['ras']:<5}{r['macd']:<8}{r['obv']:<4}"
                  f"{r['hits']:<6}{'★入选' if r['selected'] else str(r['pass']) + '/5'}")
        print("\n③ 买卖 / 做T 信号")
        if not report["signals"]:
            print("  （今日无信号。底仓五条件全中才买入 —— 空仓多数时间是正确行为）")
        for s in report["signals"]:
            print(f"  [{s['kind']}] {s['name']} {s['code']} 收{s['close']:.3f}  {s['detail']}")
        st = report["stats"]
        print(f"\n④ 回测（当前参数 · 全历史）  {st['n']} 笔")
        if st["n"]:
            print(f"  胜率 {st['win_rate']:.1f}%  盈亏比 {st['profit_factor']:.2f}  "
                  f"累计 {st['total_ret']:+.1f}%  年化 {st['cagr']:+.1f}%  "
                  f"最大回撤 {st['mdd']:.1f}%  平均持有 {st['avg_days']:.0f} 日")
        print("\n⑤ 网格拟合 Top（点行可应用）")
        for f in report["fits"]:
            print(f"  [{f['signal']:<8}] RSI≤{f['rsi_hi']:.0f} 回撤{f['dd_lo']:.0f}~{f['dd_hi']:.0f}%  "
                  f"tp{f['tp']}/sl{f['sl']}/{f['hold']}日  "
                  f"笔数{f['stats']['n']:>3}  胜率{f['stats']['win_rate']:>5.1f}%  "
                  f"收益{f['stats']['total_ret']:>+7.1f}%  评分{f['score']:>6.1f}")

    os.makedirs(DATA_DIR, exist_ok=True)
    with open(REPORT_FILE, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    print(f"\n[report] 已落盘 {REPORT_FILE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
