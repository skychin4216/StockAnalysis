# -*- coding: utf-8 -*-
"""
SSA 情景风控回测 —— 通达信 .day 数据源（严格复刻 app/.../strategy/ssa/SsaEngine.kt）
用法:
  python smalltools/_ssa_tdx.py 600176
  python smalltools/_ssa_tdx.py 600176 --L 30 --N 20 --cm 1.0 --nm 1.0 --bars 500 --png
数据根目录默认 D:\\download\\hsjday（sh/sz/bj 各含 lday/*.day）。
"""
import argparse
import os
import struct
import sys

import numpy as np

SCN_WARM, SCN_A, SCN_B, SCN_C = 0, 1, 2, 3
INITIAL_CAPITAL = 100_000.0


# ───────────────────────── 通达信日线读取 ─────────────────────────
def load_tdx(code, root=r"D:\download\hsjday"):
    code = str(code).zfill(6)
    if code.startswith(("60", "68", "11", "13", "50", "51", "56", "58")):
        p = os.path.join(root, "sh", "lday", "sh" + code + ".day")
    elif code.startswith(("00", "30", "12", "15", "16", "18")):
        p = os.path.join(root, "sz", "lday", "sz" + code + ".day")
    else:
        p = os.path.join(root, "bj", "lday", "bj" + code + ".day")
    if not os.path.exists(p):
        # 兜底：全目录搜索
        for mkt in ("sh", "sz", "bj"):
            q = os.path.join(root, mkt, "lday", mkt + code + ".day")
            if os.path.exists(q):
                p = q
                break
        else:
            raise FileNotFoundError(code)
    bars = []
    with open(p, "rb") as f:
        buf = f.read()
    for i in range(0, len(buf) - 31, 32):
        date, o, h, l, c, amount, vol, _ = struct.unpack("<IIIIIfII", buf[i:i + 32])
        if c <= 0:
            continue
        bars.append(dict(date=str(date), open=o / 100.0, high=h / 100.0,
                         low=l / 100.0, close=c / 100.0, volume=vol))
    return bars


# ───────────────────────── SSA 分解（同 SsaEngine.decompose） ─────────────────────────
def diag_indices_fix(Y, ns, K, L):
    out = np.zeros(ns)
    for k in range(ns):
        i0, i1 = max(0, k - L + 1), min(k, K - 1)
        s = 0.0
        for i in range(i0, i1 + 1):
            s += Y[i, k - i]
        out[k] = s / (i1 - i0 + 1)
    return out


def decompose2(x, L):
    """与 decompose 等价，对角平均用显式实现（避免花式索引歧义）。"""
    x = np.asarray(x, dtype=np.float64)
    ns = len(x)
    K = ns - L + 1
    X = np.lib.stride_tricks.sliding_window_view(x, L)
    G = X.T @ X
    eigvals, eigvecs = np.linalg.eigh(G)
    order = np.argsort(-eigvals)
    sigma = np.sqrt(np.maximum(0.0, eigvals[order]))

    def recon(ranks):
        Y = np.zeros((K, L))
        for rank in ranks:
            col = order[rank]
            if sigma[rank] < 1e-12:
                continue
            v = eigvecs[:, col]
            a = X @ v
            Y += np.outer(a, v)
        return diag_indices_fix(Y, ns, K, L)

    trend = recon([0])
    cycle = recon([1, 2]) if L >= 3 else np.zeros(ns)
    noise = x - trend - cycle
    return trend, cycle, noise, sigma


def tail_std(v, n):
    seg = v[-n:]
    seg = seg[~np.isnan(seg)]
    if len(seg) < 2:
        return 0.0
    return float(seg.std(ddof=1))


# ───────────────────────── 主流程（同 SsaEngine.run） ─────────────────────────
def _worst(scenario, i0, i1):
    w = SCN_A
    for k in range(i0, i1 + 1):
        if scenario[k] != SCN_WARM and scenario[k] > w:
            w = scenario[k]
    return w


def sim_abc(bars, close, scenario, buySig, sellSig, nUp, nDn,
            commission, stamp, stop_a, stop_b):
    """A/B/C 门禁版：A才开仓、B减半收紧止损、C清仓停手。"""
    n = len(bars)
    cash = INITIAL_CAPITAL
    shares = 0; buyPrice = 0.0; buyIndex = -1; firstFill = -1
    halved = False
    tight = cClear = bHalve = 0
    trades = []; markers = []; events = []
    equity = np.zeros(n)
    for t in range(n):
        if t + 1 < n:
            fill = bars[t + 1]["open"]
            scn = scenario[t]
            if shares > 0:
                reason = None; exitPrice = fill
                if scn == SCN_C:
                    reason = "C类结构突破·无条件清仓"; cClear += 1
                    events.append((t + 1, SCN_C, "无条件清仓离场，暂停开仓直到重新收敛回A", fill))
                elif sellSig[t]:
                    reason = "趋势走弱·周期高位拐头"
                else:
                    pct_ = stop_b if scn == SCN_B else stop_a
                    stop = buyPrice * (1 - pct_ / 100.0)
                    if fill <= stop:
                        reason = (f"B类扰动·收紧止损({int(stop_b)}%)" if scn == SCN_B
                                  else f"A类止损({int(stop_a)}%)")
                        exitPrice = fill
                        if scn == SCN_B:
                            tight += 1
                            events.append((t + 1, SCN_B, f"收紧止损({int(stop_b)}%)清仓剩余仓位", fill))
                    elif bars[t + 1]["low"] <= stop:
                        reason = (f"B类扰动·收紧止损({int(stop_b)}%)" if scn == SCN_B
                                  else f"A类止损({int(stop_a)}%)")
                        exitPrice = stop
                        if scn == SCN_B:
                            tight += 1
                            events.append((t + 1, SCN_B, f"收紧止损({int(stop_b)}%)清仓剩余仓位", stop))
                if reason is not None:
                    proceeds = shares * exitPrice * (1 - commission - stamp)
                    cost = shares * buyPrice * (1 + commission)
                    trades.append(dict(bi=buyIndex, bd=bars[buyIndex]["date"], bp=buyPrice,
                                       si=t + 1, sd=bars[t + 1]["date"], sp=exitPrice,
                                       ret=(proceeds - cost) / cost * 100, hold=t + 1 - buyIndex,
                                       reason=reason, worst=_worst(scenario, buyIndex, t + 1)))
                    markers.append((t + 1, exitPrice, False))
                    cash += proceeds; shares = 0; buyIndex = -1; halved = False
                elif scn == SCN_B and not halved:
                    halved = True
                    sellShares = (shares // 200) * 100
                    if sellShares >= 100:
                        proceeds = sellShares * fill * (1 - commission - stamp)
                        cash += proceeds; shares -= sellShares; bHalve += 1
                        markers.append((t + 1, fill, False))
                        events.append((t + 1, SCN_B,
                                       f"禁止新开仓；持仓减半 {sellShares} 股，止损收紧至{int(stop_b)}%", fill))
                    else:
                        events.append((t + 1, SCN_B,
                                       f"禁止新开仓；持仓不足2手无法整手减半，止损收紧至{int(stop_b)}%", fill))
            elif buySig[t] and scn == SCN_A and fill > 0:
                lots = int((cash * (1 - commission) / fill / 100))
                if lots > 0:
                    shares = lots * 100
                    cash -= shares * fill * (1 + commission)
                    buyPrice = fill; buyIndex = t + 1; halved = False
                    if firstFill < 0:
                        firstFill = t + 1
                    markers.append((t + 1, fill, True))
        equity[t] = cash + shares * close[t]
    if shares > 0:
        last = n - 1; px = bars[last]["close"]
        proceeds = shares * px * (1 - commission - stamp)
        cost = shares * buyPrice * (1 + commission)
        trades.append(dict(bi=buyIndex, bd=bars[buyIndex]["date"], bp=buyPrice,
                           si=last, sd=bars[last]["date"], sp=px,
                           ret=(proceeds - cost) / cost * 100, hold=last - buyIndex,
                           reason="期末强制平仓", worst=_worst(scenario, buyIndex, last)))
        markers.append((last, px, False)); cash += proceeds; shares = 0
        equity[last] = cash
    return dict(cash=cash, equity=equity, trades=trades, markers=markers, events=events,
                firstFill=firstFill, tight=tight, cClear=cClear, bHalve=bHalve)


def sim_cycle(bars, close, scenario, buySig, sellSig, nUp, nDn, eUp, eDn,
              commission, stamp, stop_side="both", stop_band="blue",
              stop_confirm="tick"):
    """纯周期拐点版：
       买：cy[2]>cy[1]<cy[0] 谷底确认，次日开盘买（无持仓）；
       卖：cy[2]<cy[1]>cy[0] 峰顶确认，次日开盘卖（有持仓）；
       兜底止损：stop_band=blue 用正常轨 / red 用极限轨（结构崩盘才走，给周期波动留空间）；
                 stop_confirm=tick 盘中触轨即走 / close 收盘确认破位、次日开盘走（防插针）；
                 stop_side=down 只保留下轨兜底。
       所有判断只用 t 收盘已知信息，成交在 t+1。"""
    n = len(bars)
    cash = INITIAL_CAPITAL
    shares = 0; buyPrice = 0.0; buyIndex = -1; firstFill = -1
    bandDn = bandUp = 0
    trades = []; markers = []; events = []
    equity = np.zeros(n)

    def band_at(k):
        if stop_band == "red":
            return eDn[k], eUp[k]
        return nDn[k], nUp[k]

    for t in range(n):
        # ── 收盘确认式止损：t 收盘已破位 → t+1 开盘走（先于一切信号判定）──
        if shares > 0 and stop_confirm == "close":
            bd0, bu0 = band_at(t)
            c0 = close[t]
            below = not np.isnan(bd0) and c0 < bd0 and stop_side != "none"
            above = not np.isnan(bu0) and c0 > bu0 and stop_side == "both"
            if below or above:
                if t + 1 >= n:
                    break
                op = bars[t + 1]["open"]
                reason = "收盘跌破" + ("红轨" if stop_band == "red" else "蓝轨") + "下沿·兜底止损" \
                    if below else "收盘突破上沿·区间失效"
                proceeds = shares * op * (1 - commission - stamp)
                cost = shares * buyPrice * (1 + commission)
                trades.append(dict(bi=buyIndex, bd=bars[buyIndex]["date"], bp=buyPrice,
                                   si=t + 1, sd=bars[t + 1]["date"], sp=op,
                                   ret=(proceeds - cost) / cost * 100, hold=t + 1 - buyIndex,
                                   reason=reason, worst=_worst(scenario, buyIndex, t + 1)))
                markers.append((t + 1, op, False))
                if below:
                    bandDn += 1
                else:
                    bandUp += 1
                events.append((t + 1, 0, f"收盘{c0:.2f}破{('红' if stop_band=='red' else '蓝')}轨"
                                          f"[{bd0:.2f},{bu0:.2f}]，次日开盘{op:.2f}离场", op))
                cash += proceeds; shares = 0; buyIndex = -1
                equity[t] = cash
                continue
        if t + 1 >= n:
            equity[t] = cash + shares * close[t]
            continue
        nb = bars[t + 1]
        op, hi, lo = nb["open"], nb["high"], nb["low"]
        bd, bu = band_at(t + 1)
        band_ok = not (np.isnan(bd) or np.isnan(bu))
        if shares > 0:
            reason = None; exitPrice = op
            if stop_confirm == "tick":
                # 1) 开盘已在轨道外
                op_below = band_ok and op < bd and stop_side != "none"
                op_above = band_ok and op > bu and stop_side == "both"
                if op_below or op_above:
                    reason = "开盘跳出轨道·兜底止损全平"
                    exitPrice = op
                    if op_below:
                        bandDn += 1
                    else:
                        bandUp += 1
                    events.append((t + 1, 0,
                                   f"开盘价{op:.2f}位于{('红' if stop_band=='red' else '蓝')}轨"
                                   f"[{bd:.2f},{bu:.2f}]之外，止损全平", op))
                # 2) cycle 峰顶确认 → 次日开盘止盈
                elif sellSig[t]:
                    reason = "周期峰顶确认·止盈"
                # 3) 盘中击穿轨道
                elif band_ok and lo <= bd and stop_side != "none":
                    reason = "跌破下轨·兜底止损全平"
                    exitPrice = min(bd, op)  # 跳空低开取更差的开盘价
                    bandDn += 1
                    events.append((t + 1, 0, f"盘中最低{lo:.2f}击穿下轨{bd:.2f}，止损全平", exitPrice))
                elif stop_side == "both" and band_ok and hi >= bu:
                    reason = "突破上沿·离场"
                    exitPrice = max(bu, op)
                    bandUp += 1
                    events.append((t + 1, 0, f"盘中最高{hi:.2f}突破上沿{bu:.2f}，区间失效离场", exitPrice))
            else:
                # 收盘确认模式：这里只剩 cycle 峰顶止盈
                if sellSig[t]:
                    reason = "周期峰顶确认·止盈"
            if reason is not None:
                proceeds = shares * exitPrice * (1 - commission - stamp)
                cost = shares * buyPrice * (1 + commission)
                trades.append(dict(bi=buyIndex, bd=bars[buyIndex]["date"], bp=buyPrice,
                                   si=t + 1, sd=nb["date"], sp=exitPrice,
                                   ret=(proceeds - cost) / cost * 100, hold=t + 1 - buyIndex,
                                   reason=reason, worst=_worst(scenario, buyIndex, t + 1)))
                markers.append((t + 1, exitPrice, False))
                cash += proceeds; shares = 0; buyIndex = -1
        elif buySig[t] and op > 0:
            lots = int((cash * (1 - commission) / op / 100))
            if lots > 0:
                shares = lots * 100
                cash -= shares * op * (1 + commission)
                buyPrice = op; buyIndex = t + 1
                if firstFill < 0:
                    firstFill = t + 1
                markers.append((t + 1, op, True))
        equity[t] = cash + shares * close[t]
    # 最后一根没有 t+1，按当时持仓盯市
    equity[n - 1] = cash + shares * close[n - 1]
    if shares > 0:
        last = n - 1; px = bars[last]["close"]
        proceeds = shares * px * (1 - commission - stamp)
        cost = shares * buyPrice * (1 + commission)
        trades.append(dict(bi=buyIndex, bd=bars[buyIndex]["date"], bp=buyPrice,
                           si=last, sd=bars[last]["date"], sp=px,
                           ret=(proceeds - cost) / cost * 100, hold=last - buyIndex,
                           reason="期末强制平仓", worst=_worst(scenario, buyIndex, last)))
        markers.append((last, px, False)); cash += proceeds
        equity[last] = cash
    return dict(cash=cash, equity=equity, trades=trades, markers=markers, events=events,
                firstFill=firstFill, bandDn=bandDn, bandUp=bandUp)


# ───────────────────────── 主流程（同 SsaEngine.run） ─────────────────────────
def run(bars, L=30, Nstd=20, cm=1.0, nm=1.0, stop_a=8.0, stop_b=4.0,
        commission=0.00025, stamp=0.0005, strat="cycle", stop_side="both",
        stop_band="blue", stop_confirm="tick", ampK=0.0):
    n = len(bars)
    close = np.array([b["close"] for b in bars], dtype=np.float64)
    nan = np.nan
    nUp = np.full(n, nan); nDn = np.full(n, nan)
    eUp = np.full(n, nan); eDn = np.full(n, nan)
    mid = np.full(n, nan)
    trendAt = np.full(n, nan); cycleAt = np.full(n, nan)
    bench = np.zeros(n)

    for t in range(L, n):
        seg = close[0:t + 1]
        tr, cy, nz, _ = decompose2(seg, L)
        trendAt[t] = tr[-1]
        cycleAt[t] = cy[-1]
        if t + 1 < n:
            cs = tail_std(cy, Nstd)
            ns_ = tail_std(nz, Nstd)
            md = tr[-1]
            mid[t + 1] = md
            nUp[t + 1] = md + cm * cs
            nDn[t + 1] = md - cm * cs
            eUp[t + 1] = md + cm * cs + nm * ns_
            eDn[t + 1] = md - cm * cs - nm * ns_

    scenario = np.full(n, SCN_WARM, dtype=int)
    for t in range(1, n):
        if np.isnan(nUp[t]):
            continue
        c = close[t]
        scenario[t] = SCN_A if nDn[t] <= c <= nUp[t] else (SCN_B if eDn[t] <= c <= eUp[t] else SCN_C)

    buySig = np.zeros(n, dtype=bool); sellSig = np.zeros(n, dtype=bool)
    buyTurn = np.zeros(n, dtype=bool); sellTurn = np.zeros(n, dtype=bool)
    for t in range(2, n):
        if np.isnan(trendAt[t]) or np.isnan(trendAt[t - 1]) or np.isnan(cycleAt[t]) \
                or np.isnan(cycleAt[t - 1]) or np.isnan(cycleAt[t - 2]):
            continue
        # 纯周期拐点（不看趋势方向、不看A/B/C）
        # ampK>0 时要求拐点本身有深度：谷底 cy[t-1] ≤ -ampK·σcycle，峰顶 ≥ +ampK·σcycle
        # （过滤趋势段里贴着零轴的小抖动，只做有完整起伏的周期）
        cy_hist = cycleAt[max(0, t - Nstd):t + 1]
        cy_hist = cy_hist[~np.isnan(cy_hist)]
        csig = cy_hist.std(ddof=1) if len(cy_hist) >= 3 else 0.0
        deep_enough_buy = ampK <= 0 or cycleAt[t - 1] <= -ampK * csig
        deep_enough_sell = ampK <= 0 or cycleAt[t - 1] >= ampK * csig
        if cycleAt[t - 2] > cycleAt[t - 1] and cycleAt[t - 1] < cycleAt[t] and deep_enough_buy:
            buyTurn[t] = True
        if cycleAt[t - 2] < cycleAt[t - 1] and cycleAt[t - 1] > cycleAt[t] and deep_enough_sell:
            sellTurn[t] = True
        # ABC 门禁版：额外要求趋势方向 + 零轴位置
        if trendAt[t] > trendAt[t - 1] and cycleAt[t - 1] <= cycleAt[t - 2] \
                and cycleAt[t] > cycleAt[t - 1] and cycleAt[t - 1] < 0:
            buySig[t] = True
        if trendAt[t] < trendAt[t - 1] and cycleAt[t - 1] >= cycleAt[t - 2] \
                and cycleAt[t] < cycleAt[t - 1] and cycleAt[t - 1] > 0:
            sellSig[t] = True

    # 最终整窗（仅画图）
    trFull, cyFull, nzFull, sigma = decompose2(close, L)

    if strat == "abc":
        sim = sim_abc(bars, close, scenario, buySig, sellSig, nUp, nDn,
                      commission, stamp, stop_a, stop_b)
    else:
        sim = sim_cycle(bars, close, scenario, buyTurn, sellTurn, nUp, nDn, eUp, eDn,
                        commission, stamp, stop_side, stop_band, stop_confirm)
    cash = sim["cash"]; equity = sim["equity"]; trades = sim["trades"]
    markers = sim["markers"]; events = sim["events"]; firstFill = sim["firstFill"]
    cClear = sim.get("cClear", 0); bHalve = sim.get("bHalve", 0); tight = sim.get("tight", 0)
    bandDn = sim.get("bandDn", 0); bandUp = sim.get("bandUp", 0)

    start = firstFill if 0 <= firstFill < n else 0
    base = bars[start]["open"] if bars[start]["open"] > 0 else bars[start]["close"]
    for i in range(n):
        bench[i] = INITIAL_CAPITAL if i < start or base <= 0 else INITIAL_CAPITAL * close[i] / base
    peak = INITIAL_CAPITAL
    dd = np.zeros(n)
    for i in range(n):
        peak = max(peak, equity[i])
        dd[i] = (equity[i] - peak) / peak * 100
    finalEquity = cash

    # ── eval ──
    valid = a = b = c = 0
    biasSum = nwSum = ewSum = maxDev = 0.0
    for t in range(1, n):
        if np.isnan(nUp[t]) or np.isnan(mid[t]) or mid[t] <= 0:
            continue
        valid += 1
        bias = abs(close[t] - mid[t]) / mid[t] * 100
        biasSum += bias; maxDev = max(maxDev, bias)
        nwSum += (nUp[t] - nDn[t]) / mid[t] * 100
        ewSum += (eUp[t] - eDn[t]) / mid[t] * 100
        if scenario[t] == SCN_A:
            a += 1
        elif scenario[t] == SCN_B:
            b += 1
        elif scenario[t] == SCN_C:
            c += 1

    holdBars = {SCN_A: 0, SCN_B: 0, SCN_C: 0}
    holdRet = {SCN_A: [], SCN_B: [], SCN_C: []}
    trByWorst = {SCN_A: [], SCN_B: [], SCN_C: []}
    for trd in trades:
        for k in range(trd["bi"], trd["si"] + 1):
            s = scenario[k]
            if s == SCN_WARM:
                continue
            holdBars[s] += 1
            if k > trd["bi"]:
                holdRet[s].append((close[k] / close[k - 1] - 1) * 100)
        trByWorst[trd["worst"]].append(trd["ret"])

    # ── stats ──
    totalPct = (finalEquity / INITIAL_CAPITAL - 1) * 100
    active = max(n - start, 1)
    annual = ((finalEquity / INITIAL_CAPITAL) ** (250.0 / active) - 1) * 100
    wins = [t for t in trades if t["ret"] > 0]
    losses = [t for t in trades if t["ret"] <= 0]
    gw = sum(abs(t["ret"]) for t in wins); gl = sum(abs(t["ret"]) for t in losses)
    pf = gw / gl if gl > 1e-9 else (float("inf") if gw > 0 else 0.0)
    benchPct = (bench[-1] / INITIAL_CAPITAL - 1) * 100
    mdd = dd.min()

    return dict(bars=bars, close=close, nUp=nUp, nDn=nDn, eUp=eUp, eDn=eDn, mid=mid,
                scenario=scenario, trFull=trFull, cyFull=cyFull,
                trendAt=trendAt, cycleAt=cycleAt,
                buySig=(buyTurn if strat == "cycle" else buySig),
                sellSig=(sellTurn if strat == "cycle" else sellSig),
                strat=strat,
                trades=trades, markers=markers, events=events, equity=equity, bench=bench, dd=dd,
                valid=valid, a=a, b=b, c=c,
                bias=biasSum / max(valid, 1), nw=nwSum / max(valid, 1), ew=ewSum / max(valid, 1),
                maxDev=maxDev, tight=tight, cClear=cClear, bHalve=bHalve,
                bandDn=bandDn, bandUp=bandUp,
                holdBars=holdBars, holdRet=holdRet, trByWorst=trByWorst,
                total=totalPct, annual=annual, mdd=mdd, wins=wins, losses=losses, pf=pf,
                benchPct=benchPct, avgHold=(sum(t["hold"] for t in trades) / len(trades) if trades else 0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("code")
    ap.add_argument("--L", type=int, default=30)
    ap.add_argument("--N", type=int, default=20)
    ap.add_argument("--cm", type=float, default=1.0)
    ap.add_argument("--nm", type=float, default=1.0)
    ap.add_argument("--bars", type=int, default=500, help="取最后N根日K（App默认区间）")
    ap.add_argument("--root", default=r"D:\download\hsjday")
    ap.add_argument("--png", action="store_true")
    ap.add_argument("--strat", choices=["cycle", "abc"], default="cycle",
                    help="cycle=纯周期拐点+蓝轨硬止损(默认)；abc=A/B/C门禁版")
    ap.add_argument("--stop-side", choices=["both", "down", "none"], default="both",
                    help="兜底方向：both=上下轨都离场；down=仅下轨兜底；none=纯周期买卖无轨道止损")
    ap.add_argument("--stop-band", choices=["blue", "red"], default="blue",
                    help="兜底止损参考轨：blue=正常轨(紧)；red=极限轨(结构崩盘才走)")
    ap.add_argument("--stop-confirm", choices=["tick", "close"], default="tick",
                    help="tick=盘中触轨即走；close=收盘确认破位次日开盘走(防插针)")
    ap.add_argument("--ampK", type=float, default=0.0,
                    help="拐点深度过滤(倍σ)：0=不过滤；1=只做≥1σ深度的周期底/顶")
    args = ap.parse_args()

    allbars = load_tdx(args.code, args.root)
    bars = allbars[-args.bars:]
    if len(bars) < args.L + 20:
        print(f"K线不足: {len(bars)} < L+20={args.L + 20}")
        sys.exit(1)

    r = run(bars, L=args.L, Nstd=args.N, cm=args.cm, nm=args.nm,
            strat=args.strat, stop_side=args.stop_side, stop_band=args.stop_band,
            stop_confirm=args.stop_confirm, ampK=args.ampK)
    n = len(bars)
    v = r["valid"]

    def pct100(x):
        return f"{x:+.2f}%"

    print("=" * 78)
    sname = "纯周期拐点 + 蓝轨硬止损" if args.strat == "cycle" else "A/B/C 情景门禁"
    print(f"SSA 回测 [{sname}]  {args.code}   {bars[0]['date']} ~ {bars[-1]['date']}  共 {n} 根日K")
    print(f"参数  L={args.L}  N={args.N}  正常倍数={args.cm}  极限倍数={args.nm}  佣金万2.5+印花万5")
    print("=" * 78)
    print("【区间预测准确度】")
    print(f"  有效样本 {v} 根（仅丢弃前 {args.L} 根暖机）")
    if v:
        print(f"  A 正常稳态  {r['a']:4d}  ({r['a'] / v * 100:5.1f}%)")
        print(f"  B 强扰动    {r['b']:4d}  ({r['b'] / v * 100:5.1f}%)")
        print(f"  C 结构突破  {r['c']:4d}  ({r['c'] / v * 100:5.1f}%)")
        print(f"  平均偏差|C-mid|/mid = {r['bias']:.2f}%   正常区间均宽 {r['nw']:.2f}%   "
              f"极限区间均宽 {r['ew']:.2f}%   最大单根偏离 {r['maxDev']:.2f}%")
    print("-" * 78)
    print("【中枢一致性诊断】")
    msk = ~np.isnan(r["mid"])
    gap = np.abs(r["mid"][msk] - r["trFull"][msk]) / r["trFull"][msk] * 100
    lag = np.nanmean(r["close"][msk] - r["mid"][msk]) / np.nanmean(r["mid"][msk]) * 100
    # 轨道相对 mid 是否严格对称
    asym = np.nanmean(np.abs((r["nUp"] - r["mid"]) - (r["mid"] - r["nDn"])))
    print(f"  滚动mid 与 整窗趋势 平均偏离 {np.nanmean(gap):.2f}%  最大 {np.nanmax(gap):.2f}%")
    print(f"  收盘相对滚动mid 平均位置 {lag:+.2f}%（正=价格系统性在中轨上方=中轨滞后）")
    print(f"  上下轨相对mid不对称度(应≈0) {asym:.2e}")
    print("-" * 78)
    print("【买卖信号诊断】（t 收盘确认、t+1 开盘成交）")
    buy_idx = [t for t in range(2, n) if r["buySig"][t]]
    sell_idx = [t for t in range(2, n) if r["sellSig"][t]]
    gate = {SCN_WARM: "暖机", SCN_A: "A", SCN_B: "B", SCN_C: "C"}
    print(f"  周期谷底买点 {len(buy_idx)} 个   周期峰顶卖点 {len(sell_idx)} 个")
    if args.strat == "abc":
        from collections import Counter
        bc = Counter(gate[r["scenario"][t]] for t in buy_idx)
        print("  买点当根情景分布: " + "  ".join(f"{k} {v}" for k, v in bc.items()))
    print(f"  {'信号日':>10}{'cycle[t-2]':>11}{'cycle[t-1]':>11}{'cycle[t]':>10}  情景")
    for t in buy_idx:
        print(f"  {bars[t]['date']:>10}{r['cycleAt'][t - 2]:>11.3f}{r['cycleAt'][t - 1]:>11.3f}"
              f"{r['cycleAt'][t]:>10.3f}  {gate[r['scenario'][t]]:>4}")
    print("-" * 78)
    print("【回测绩效】")
    print(f"  总收益 {pct100(r['total'])}   年化 {pct100(r['annual'])}   最大回撤 {pct100(r['mdd'])}")
    wr = len(r["wins"]) / len(r["trades"]) * 100 if r["trades"] else 0
    aw = np.mean([t["ret"] for t in r["wins"]]) if r["wins"] else 0
    al = np.mean([t["ret"] for t in r["losses"]]) if r["losses"] else 0
    pf = r["pf"]
    print(f"  交易 {len(r['trades'])} 笔  胜率 {wr:.1f}%  平均盈利 {aw:+.2f}%  平均亏损 {al:+.2f}%  "
          f"盈亏比 {'∞' if np.isinf(pf) else f'{pf:.2f}'}  平均持仓 {r['avgHold']:.0f} 根")
    print(f"  买持基准 {pct100(r['benchPct'])}   超额 {pct100(r['total'] - r['benchPct'])}   "
          f"期末权益 ¥{INITIAL_CAPITAL * (1 + r['total'] / 100):,.0f}")
    print("-" * 78)
    print("【分情景策略绩效】")
    print(f"  {'情景':<8}{'全样本K线':>8}{'持仓K线':>8}{'持仓均涨跌':>11}{'阳线率':>8}{'平仓笔数':>8}{'胜率':>8}{'均收益':>9}")
    names = {SCN_A: "A 正常", SCN_B: "B 扰动", SCN_C: "C 突破"}
    for s in (SCN_A, SCN_B, SCN_C):
        rr = r["holdRet"][s]; trs = r["trByWorst"][s]
        mr = np.mean(rr) if rr else 0
        up = sum(1 for x in rr if x > 0) / len(rr) * 100 if rr else 0
        tw = sum(1 for x in trs if x > 0) / len(trs) * 100 if trs else 0
        ta = np.mean(trs) if trs else 0
        bars_all = {SCN_A: r['a'], SCN_B: r['b'], SCN_C: r['c']}[s]
        print(f"  {names[s]:<8}{bars_all:>8}{r['holdBars'][s]:>8}{mr:>10.2f}%{up:>7.0f}%"
              f"{len(trs):>8}{tw:>7.0f}%{ta:>8.2f}%")
    print("-" * 78)
    if args.strat == "cycle":
        print(f"【兜底止损】 参考轨={'红色极限轨' if args.stop_band == 'red' else '蓝色正常轨'}"
              f"  方向={'上下两侧' if args.stop_side == 'both' else ('仅下轨' if args.stop_side == 'down' else '无')}"
              f"  确认={'盘中触轨' if args.stop_confirm == 'tick' else '收盘确认'}"
              f"  深度过滤={args.ampK}σ")
        print(f"  跌破下沿 {r['bandDn']} 次   突破上沿 {r['bandUp']} 次   日志共 {len(r['events'])} 条")
        for idx, _scn, act, px in r["events"][-40:]:
            tag = "红轨" if args.stop_band == "red" else "蓝轨"
            print(f"  {bars[idx]['date']}  {tag}止损  {px:8.2f}  {act}")
    else:
        print(f"【风控触发】 C清仓 {r['cClear']} 次   B减半 {r['bHalve']} 次   B收紧止损 {r['tight']} 次   "
              f"日志共 {len(r['events'])} 条")
        for idx, scn, act, px in r["events"][-40:]:
            print(f"  {bars[idx]['date']}  {'C突破' if scn == SCN_C else 'B扰动'}  {px:8.2f}  {act}")
    print("-" * 78)
    print(f"【交易明细】共 {len(r['trades'])} 笔")
    print(f"  {'#':>3} {'买入日':>10}{'买价':>9} {'卖出日':>10}{'卖价':>9}{'收益':>9}{'持仓':>5} {'最差':>4}  离场原因")
    for i, t in enumerate(r["trades"], 1):
        print(f"  {i:>3} {t['bd']:>10}{t['bp']:>9.2f} {t['sd']:>10}{t['sp']:>9.2f}"
              f"{t['ret']:>+8.2f}%{t['hold']:>5} {'ABC'[t['worst'] - 1]:>4}  {t['reason']}")
    print("=" * 78)

    if args.png:
        try:
            import matplotlib
            matplotlib.use("Agg")
            import matplotlib.pyplot as plt
            plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
            plt.rcParams["axes.unicode_minus"] = False
            x = np.arange(n)
            fig, axes = plt.subplots(3, 1, figsize=(13, 11))
            ax = axes[0]
            ax.plot(x, r["close"], color="#222", lw=1.0, label="收盘")
            ax.plot(x, r["trFull"], color="#FF8800", lw=1.3, label="SSA趋势")
            for idx, px, buy in r["markers"]:
                ax.plot(idx, px, marker="^" if buy else "v",
                        color="red" if buy else "green", ms=8)
            ax.set_title(f"{args.code} ① 主交易图（末尾{args.L}根边缘效应）"); ax.legend(fontsize=8)

            ax = axes[1]
            validx = ~np.isnan(r["nUp"])
            ax.fill_between(x[validx], r["nDn"][validx], r["nUp"][validx],
                            color="gray", alpha=0.18, label="正常扰动区间")
            ax.plot(x, r["nUp"], color="#1976D2", lw=0.8, label="正常上轨")
            ax.plot(x, r["nDn"], color="#1976D2", lw=0.8)
            ax.plot(x, r["eUp"], color="red", lw=0.7, ls="--", label="极限上轨")
            ax.plot(x, r["eDn"], color="red", lw=0.7, ls="--")
            ax.plot(x, r["close"], color="#222", lw=1.1, label="收盘价(左轴)")
            # 轨道中心有两条：滚动 mid（逐K仅用历史，轨道真正中心）vs 整窗 trFull（含未来仅画图）
            ax.plot(x, r["mid"], color="#FF6F00", lw=1.5, ls="--",
                    label="滚动中枢mid=轨道中心(无未来)")
            ax.plot(x, r["trFull"], color="#00897B", lw=1.3, alpha=0.9,
                    label="整窗SSA趋势(含未来·仅参照)")
            colors = {SCN_A: "#1976D2", SCN_B: "#FB8C00", SCN_C: "#E53935"}
            for s in (SCN_A, SCN_B, SCN_C):
                msk = validx & (r["scenario"] == s)
                ax.scatter(x[msk], r["close"][msk], s=7, c=colors[s], zorder=3,
                           label={SCN_A: "A", SCN_B: "B", SCN_C: "C"}[s])
            # 信号/成交标记：cycle 策略直接画实际成交(markers)；abc 策略画信号(实心=放行/空心=拦截)
            if args.strat == "cycle":
                for idx, px, is_buy in r["markers"]:
                    ax.plot(idx, px, marker="^" if is_buy else "v", ms=11,
                            mfc="red" if is_buy else "#2E7D32",
                            mec="red" if is_buy else "#2E7D32", mew=1.2, zorder=5)
            else:
                for t in range(2, n):
                    if r["buySig"][t]:
                        ok = r["scenario"][t] == SCN_A
                        ax.plot(t, r["close"][t], marker="^", ms=11 if ok else 8,
                                mfc="red" if ok else "none", mec="red", mew=1.4, zorder=5)
                    if r["sellSig"][t]:
                        ax.plot(t, r["close"][t], marker="v", ms=9,
                                mfc="#2E7D32", mec="#2E7D32", mew=1.0, zorder=4)
            # 周期分量右轴叠加（逐K滚动末端值）
            ax2 = ax.twinx()
            cy = np.where(np.isnan(r["cycleAt"]), np.nan, r["cycleAt"])
            ax2.plot(x, cy, color="#7B1FA2", lw=1.1, alpha=0.85, label="cycle周期分量(右轴)")
            ax2.axhline(0, color="#7B1FA2", lw=0.5, ls=":", alpha=0.5)
            ax2.set_ylabel("cycle", color="#7B1FA2", fontsize=8)
            ax2.tick_params(axis="y", colors="#7B1FA2", labelsize=7)
            ax.axvline(n - args.L, color="#999", ls=":")
            ax.set_title("③ 动态区间 + cycle周期分量(右轴) + 拐点信号（▲实=放行 ▲空=门禁拦截 ▼=卖点）")
            h1, l1 = ax.get_legend_handles_labels()
            h2, l2 = ax2.get_legend_handles_labels()
            ax.legend(h1 + h2, l1 + l2, fontsize=6.5, ncol=3, loc="upper left")

            ax = axes[2]
            ax.plot(x, r["equity"], color="#D81B60", lw=1.2, label="策略净值")
            ax.plot(x, r["bench"], color="#777", lw=1.0, label="买持基准")
            ax.set_title("⑥ 净值对比"); ax.legend(fontsize=8)
            out = os.path.join("data", "review_tables",
                               f"ssa_{args.code}_{args.strat}_L{args.L}_{args.stop_side}_"
                               f"{args.stop_band}_{args.stop_confirm}_a{args.ampK}.png")
            os.makedirs(os.path.dirname(out), exist_ok=True)
            plt.tight_layout(); plt.savefig(out, dpi=110)
            print(f"图已保存: {out}")
        except Exception as e:
            print(f"绘图跳过: {e}")


if __name__ == "__main__":
    main()
