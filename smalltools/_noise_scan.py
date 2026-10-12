# -*- coding: utf-8 -*-
"""噪声分量分析（通用）：python _noise_scan.py CODE s l lc [--smooth N]
A) noise_z 分箱 → 未来1/3/5日收益；B) 混合策略基线 + V1追高过滤/V3超涨止盈"""
import argparse, importlib.util, os
import numpy as np

def _load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(os.path.dirname(__file__), name + ".py"))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

tdx = _load("_ssa_tdx"); cr = _load("_ssa_cross"); hy = _load("_ssa_hybrid")

ap = argparse.ArgumentParser()
ap.add_argument("code"); ap.add_argument("s", type=int); ap.add_argument("l", type=int); ap.add_argument("lc", type=int)
ap.add_argument("--smooth", type=int, default=2)
args = ap.parse_args()

bars = cr.load_daily(args.code, "20230101")
n = len(bars)
close = np.array([b["close"] for b in bars])
r = tdx.run(bars, L=args.lc, strat="cycle", stop_side="none", ampK=0.0)
tr = hy.ema(r["trendAt"], args.smooth); cy = hy.ema(r["cycleAt"], args.smooth)
noise = close - tr - cy
nz = np.full(n, np.nan)
for t in range(n):
    if not np.isnan(noise[t]):
        sd = tdx.tail_std(noise[:t + 1], 20)
        if sd > 1e-9:
            nz[t] = noise[t] / sd

print("=" * 74)
print(f"{args.code} noise_z 分箱 → 未来收益（cycle L{args.lc}+EMA{args.smooth}）")
print("=" * 74)
print(f"{'noise_z':>10}{'样本':>6}{'次日%':>8}{'3日%':>8}{'5日%':>8}")
for lo, hi in [(-99, -2), (-2, -1), (-1, -0.5), (-0.5, 0), (0, 0.5), (0.5, 1), (1, 2), (2, 99)]:
    idx = [t for t in range(n - 6) if not np.isnan(nz[t]) and lo <= nz[t] < hi]
    if not idx:
        continue
    r1 = np.mean([(close[t + 1] / close[t] - 1) * 100 for t in idx])
    r3 = np.mean([(close[t + 3] / close[t] - 1) * 100 for t in idx])
    r5 = np.mean([(close[t + 5] / close[t] - 1) * 100 for t in idx])
    print(f"{lo:>5}~{hi:<4}{len(idx):>6}{r1:>+8.2f}{r3:>+8.2f}{r5:>+8.2f}")

tc = cr.get_trends_cycles(bars)
tc = {L: (hy.ema(a, args.smooth), hy.ema(b, args.smooth)) for L, (a, b) in tc.items()}

def sim_noise(bars, tr_s, tr_l, cy, nz, v1=False, v3=False):
    N = len(bars); cash = hy.CAP; shares = 0; bi = -1; bp = 0.0
    trades = []; equity = np.zeros(N); pend = None; pend_why = ""; bwhy = ""; in_bull = False
    for t in range(N):
        px = bars[t]["open"]
        if pend == "B" and shares == 0:
            lot = int(cash / (1 + hy.COM) / px / 100) * 100
            if lot > 0:
                cash -= lot * px * (1 + hy.COM); shares = lot; bi, bp = t, px
        elif pend == "S" and shares > 0:
            cash += shares * px * (1 - hy.COM - hy.STAMP)
            trades.append(dict(bi=bi, bp=bp, si=t, sp=px, why=bwhy,
                               ret=(px * (1 - hy.COM - hy.STAMP) / (bp * (1 + hy.COM)) - 1) * 100, hold=t - bi, reason=pend_why))
            shares = 0
        pend = None; pend_why = ""
        if t >= 1:
            s0, l0, s1, l1 = tr_s[t - 1], tr_l[t - 1], tr_s[t], tr_l[t]
            gold = dead = False
            if not (np.isnan(s0) or np.isnan(l0) or np.isnan(s1) or np.isnan(l1)):
                gold = s0 <= l0 and s1 > l1; dead = s0 >= l0 and s1 < l1; in_bull = s1 > l1
            trough = peak_ = False
            if t >= 2 and not (np.isnan(cy[t - 2]) or np.isnan(cy[t - 1]) or np.isnan(cy[t])):
                trough = cy[t - 2] > cy[t - 1] and cy[t - 1] < cy[t]
                peak_ = cy[t - 2] < cy[t - 1] and cy[t - 1] > cy[t]
            z = nz[t] if not np.isnan(nz[t]) else 0.0
            if shares > 0:
                if dead: pend, pend_why = "S", "死叉"
                elif peak_: pend, pend_why = "S", "峰顶"
                elif v3 and z > 2.0: pend, pend_why = "S", "超涨"
            else:
                if gold and not (v1 and z > 1.0): pend = "B"; bwhy = "金叉"
                elif in_bull and trough and not (v1 and z > 1.0): pend = "B"; bwhy = "谷底"
        equity[t] = cash + shares * bars[t]["close"]
    if shares > 0:
        px = bars[-1]["close"]
        trades.append(dict(bi=bi, bp=bp, si=N - 1, sp=px, why=bwhy,
                           ret=(px * (1 - hy.COM - hy.STAMP) / (bp * (1 + hy.COM)) - 1) * 100, hold=N - 1 - bi, reason="期末"))
    return trades, equity

print()
print(f"{args.code} 基线 t{args.s}/{args.l}+c{args.lc}+EMA{args.smooth} 加噪声规则")
for tag, v1, v3 in [("基线", False, False), ("V1 追高过滤(z>1不买)", True, False),
                    ("V3 超涨止盈(z>2卖)", False, True), ("V1+V3", True, True)]:
    trades, equity = sim_noise(bars, tc[args.s][0], tc[args.l][0], tc[args.lc][1], nz, v1, v3)
    total, mdd, bench, wr, pf, ah = cr.perf(trades, equity, close)
    print(f"{tag:<22}{total:>+9.2f}%{mdd:>8.2f}%  {len(trades):>3}笔  胜率{wr:>6.1f}%  PF{pf:>6.2f}  均持仓{ah:>5.1f}日")
