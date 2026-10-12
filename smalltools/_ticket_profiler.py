# -*- coding: utf-8 -*-
"""票性自动识别器 + 最优/稳健双方案扫描
用法: python _ticket_profiler.py CODE1 CODE2 ... [--smooth N] [--top K]
输出: 每只票的票性标签 + 最优方案 + 稳健方案 + 噪声增强测试"""
import argparse, importlib.util, os, sys
import numpy as np

def _load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(os.path.dirname(__file__), name + ".py"))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

tdx = _load("_ssa_tdx"); cr = _load("_ssa_cross"); hy = _load("_ssa_hybrid")
LS = cr.LS; COM = cr.COM; STAMP = cr.STAMP; CAP = cr.CAP

def ema(arr, span):
    if span <= 1:
        return arr
    alpha = 2.0 / (span + 1)
    out = np.full(len(arr), np.nan)
    last = np.nan
    for i, v in enumerate(arr):
        if np.isnan(v):
            continue
        last = v if np.isnan(last) else alpha * v + (1 - alpha) * last
        out[i] = last
    return out

def classify_ticket(bars, L=30, smooth=2, tr=None, cy=None):
    """票性识别器：返回 dict 含 label/hi3/hi5/lo5/cyc_amp。tr/cy 可传入复用（须已 EMA 平滑）"""
    n = len(bars)
    close = np.array([b["close"] for b in bars])
    if tr is None or cy is None:
        r = tdx.run(bars, L=L, strat="cycle", stop_side="none", ampK=0.0)
        tr = ema(r["trendAt"], smooth); cy = ema(r["cycleAt"], smooth)
    noise = close - tr - cy
    nz = np.full(n, np.nan)
    for t in range(n):
        if not np.isnan(noise[t]):
            sd = tdx.tail_std(noise[:t + 1], 20)
            if sd > 1e-9:
                nz[t] = noise[t] / sd
    # 关键指标1: z>1 样本的前向3日/5日平均收益（hi5 更能区分趋势延续 vs 脉冲回吐）
    idx_hi = [t for t in range(n - 6) if not np.isnan(nz[t]) and nz[t] >= 1.0]
    hi3 = np.mean([(close[t + 3] / close[t] - 1) * 100 for t in idx_hi]) if idx_hi else 0.0
    hi5 = np.mean([(close[t + 5] / close[t] - 1) * 100 for t in idx_hi]) if idx_hi else 0.0
    # 关键指标2: z<-2 样本的前向5日平均收益（负=破位，正=超跌反弹）
    idx_lo = [t for t in range(n - 6) if not np.isnan(nz[t]) and nz[t] <= -2.0]
    lo5 = np.mean([(close[t + 5] / close[t] - 1) * 100 for t in idx_lo]) if idx_lo else 0.0
    # 关键指标3: cycle 振幅 / 价格（波动率 proxy）
    valid_cy = cy[~np.isnan(cy)]
    cyc_amp = np.std(valid_cy) / np.mean(close) if len(valid_cy) > 20 else 0.0

    if hi5 > 0.6 and cyc_amp > 0.04:
        label = "TREND"
    elif hi5 < -0.2 and cyc_amp < 0.03:
        label = "OSCILLATION"
    elif -0.2 <= hi5 < 0.4 and cyc_amp < 0.025:
        label = "SLOWBULL"
    else:
        label = "MIXED"
    return dict(label=label, hi3=hi3, hi5=hi5, lo5=lo5, cyc_amp=cyc_amp,
                n_hi=len(idx_hi), n_lo=len(idx_lo), noise=nz, tr=tr, cy=cy)

def sim_hybrid(bars, tr_s, tr_l, cy):
    """hybrid 混合策略模拟（同 _ssa_hybrid.sim_hybrid）"""
    N = len(bars); cash = max(CAP, bars[0]["open"] * 100 * 1.2); shares = 0; bi = -1; bp = 0.0
    trades = []; equity = np.zeros(N); pend = None; pend_why = ""; bwhy = ""; in_bull = False
    for t in range(N):
        px = bars[t]["open"]
        if pend == "B" and shares == 0:
            lot = int(cash / (1 + COM) / px / 100) * 100
            if lot > 0:
                cash -= lot * px * (1 + COM); shares = lot; bi, bp = t, px
        elif pend == "S" and shares > 0:
            cash += shares * px * (1 - COM - STAMP)
            trades.append(dict(bi=bi, bp=bp, si=t, sp=px, why=bwhy,
                               ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                               hold=t - bi, reason=pend_why))
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
            if shares > 0:
                if dead: pend, pend_why = "S", "死叉"
                elif peak_: pend, pend_why = "S", "峰顶"
            else:
                if gold: pend = "B"; bwhy = "金叉"
                elif in_bull and trough: pend = "B"; bwhy = "谷底"
        equity[t] = cash + shares * bars[t]["close"]
    if shares > 0:
        px = bars[-1]["close"]
        trades.append(dict(bi=bi, bp=bp, si=N - 1, sp=px, why=bwhy,
                           ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                           hold=N - 1 - bi, reason="期末"))
    return trades, equity

def sim_noise(bars, tr_s, tr_l, cy, nz, v1=False, v3=False):
    N = len(bars); cash = max(CAP, bars[0]["open"] * 100 * 1.2); shares = 0; bi = -1; bp = 0.0
    trades = []; equity = np.zeros(N); pend = None; pend_why = ""; bwhy = ""; in_bull = False
    for t in range(N):
        px = bars[t]["open"]
        if pend == "B" and shares == 0:
            lot = int(cash / (1 + COM) / px / 100) * 100
            if lot > 0:
                cash -= lot * px * (1 + COM); shares = lot; bi, bp = t, px
        elif pend == "S" and shares > 0:
            cash += shares * px * (1 - COM - STAMP)
            trades.append(dict(bi=bi, bp=bp, si=t, sp=px, why=bwhy,
                               ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                               hold=t - bi, reason=pend_why))
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
                           ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                           hold=N - 1 - bi, reason="期末"))
    return trades, equity

def sim_hybrid_adv(bars, tr_s, tr_l, cy, base=0.0):
    """增强模拟：T 子账户跑混合策略，base 子账户金叉买/死叉买持有到底仓。
    同时在每根已收盘K给出次日建议 BUY/SELL/HOLD_POS/HOLD_EMPTY，并用 t+1 开盘成交、
    t+1/3/5 收盘评估命中率。返回 trades, equity, stats(dict), latest(dict)"""
    N = len(bars)
    cap0 = max(CAP, bars[0]["open"] * 100 * 1.2)
    capT, capB = cap0 * (1 - base), cap0 * base
    cashT, shT, biT, bpT = capT, 0, -1, 0.0
    cashB, shB, biB, bpB = capB, 0, -1, 0.0
    trades = []; equity = np.zeros(N)
    pendT = None; whyT = ""; bwhyT = ""; in_bull = False
    pendB = None
    # 建议记录: 每个 bar t -> 建议（对 t+1）
    rec = [None] * N
    for t in range(N):
        px = bars[t]["open"]
        # ── T 账户成交 ──
        if pendT == "B" and shT == 0:
            lot = int(cashT / (1 + COM) / px / 100) * 100
            if lot > 0:
                cashT -= lot * px * (1 + COM); shT = lot; biT, bpT = t, px
        elif pendT == "S" and shT > 0:
            cashT += shT * px * (1 - COM - STAMP)
            trades.append(dict(bi=biT, bp=bpT, si=t, sp=px, why=bwhyT,
                               ret=(px * (1 - COM - STAMP) / (bpT * (1 + COM)) - 1) * 100,
                               hold=t - biT, reason=whyT))
            shT = 0
        # ── 底仓账户成交（只认金叉/死叉）──
        if pendB == "B" and shB == 0:
            lot = int(cashB / (1 + COM) / px / 100) * 100
            if lot > 0:
                cashB -= lot * px * (1 + COM); shB = lot; biB, bpB = t, px
        elif pendB == "S" and shB > 0:
            cashB += shB * px * (1 - COM - STAMP); shB = 0
        pendT = None; whyT = ""; pendB = None
        # ── 信号（t 收盘）──
        if t >= 1:
            s0, l0, s1, l1 = tr_s[t - 1], tr_l[t - 1], tr_s[t], tr_l[t]
            gold = dead = False
            if not (np.isnan(s0) or np.isnan(l0) or np.isnan(s1) or np.isnan(l1)):
                gold = s0 <= l0 and s1 > l1; dead = s0 >= l0 and s1 < l1; in_bull = s1 > l1
            trough = peak_ = False
            if t >= 2 and not (np.isnan(cy[t - 2]) or np.isnan(cy[t - 1]) or np.isnan(cy[t])):
                trough = cy[t - 2] > cy[t - 1] and cy[t - 1] < cy[t]
                peak_ = cy[t - 2] < cy[t - 1] and cy[t - 1] > cy[t]
            if shT > 0:
                if dead: pendT, whyT, pendB = "S", "死叉", "S"
                elif peak_: pendT, whyT = "S", "峰顶"
            else:
                if gold: pendT, bwhyT, pendB = "B", "金叉", "B"
                elif in_bull and trough: pendT, bwhyT = "B", "谷底"
        # ── 对 t+1 的建议 ──
        if pendT == "B":
            rec[t] = "BUY"
        elif pendT == "S":
            rec[t] = "SELL"
        else:
            rec[t] = "HOLD_POS" if (shT > 0 or shB > 0) else "HOLD_EMPTY"
        equity[t] = cashT + shT * bars[t]["close"] + cashB + shB * bars[t]["close"]
    if shT > 0:
        px = bars[-1]["close"]
        trades.append(dict(bi=biT, bp=bpT, si=N - 1, sp=px, why=bwhyT,
                           ret=(px * (1 - COM - STAMP) / (bpT * (1 + COM)) - 1) * 100,
                           hold=N - 1 - biT, reason="期末"))

    # ── 建议命中率：t+1 开盘成交 → t+1/t+3/t+5 收盘 ──
    def hit(label, want_up):
        idx = [t for t in range(N) if rec[t] == label]
        r1s, r3s, r5s = [], [], []
        for t in idx:
            if t + 1 >= N:
                continue
            op = bars[t + 1]["open"]
            r1s.append((bars[min(t + 1, N - 1)]["close"] / op - 1) * 100)
            r3s.append((bars[min(t + 3, N - 1)]["close"] / op - 1) * 100)
            r5s.append((bars[min(t + 5, N - 1)]["close"] / op - 1) * 100)
        w = lambda a: (sum(1 for x in a if (x > 0) == want_up) / len(a) * 100) if a else 0.0
        return dict(n=len(idx), w1=w(r1s), w3=w(r3s), r5=float(np.mean(r5s)) if r5s else 0.0)

    stats = dict(buy=hit("BUY", True), sell=hit("SELL", False),
                 hpos=hit("HOLD_POS", True), hemp=hit("HOLD_EMPTY", False))
    # ── 建议明细：每根已收盘K给出的建议 + t+1开盘成交后的实际1/3/5日收益 ──
    detail = []
    for t in range(N):
        r1 = r3 = r5 = float("nan")
        if t + 1 < N:
            op = bars[t + 1]["open"]
            r1 = (bars[min(t + 1, N - 1)]["close"] / op - 1) * 100
            r3 = (bars[min(t + 3, N - 1)]["close"] / op - 1) * 100
            r5 = (bars[min(t + 5, N - 1)]["close"] / op - 1) * 100
        detail.append(dict(
            idx=t, date=bars[t].get("dt") or bars[t]["date"],
            close=bars[t]["close"], rec=rec[t], r1=r1, r3=r3, r5=r5
        ))
    last = rec[N - 1]
    latest = dict(rec=last, date=bars[N - 1].get("dt") or bars[N - 1]["date"],
                  close=bars[N - 1]["close"])
    return trades, equity, stats, latest, detail

def scan_ticket(code, smooth=2, top_k=50):
    bars = cr.load_daily(code, "20230101")
    if len(bars) < 100:
        return None
    n = len(bars)
    close = np.array([b["close"] for b in bars])
    # 混合策略扫描（先分解，票性识别复用同一分解，省一次 tdx.run）
    tc = cr.get_trends_cycles(bars)
    tc = {L: (ema(a, smooth), ema(b, smooth)) for L, (a, b) in tc.items()}
    prof = classify_ticket(bars, L=30, smooth=smooth, tr=tc[30][0], cy=tc[30][1])
    results = []
    for s in LS:
        for l in LS:
            if s >= l:
                continue
            for lc in LS:
                trades, equity = sim_hybrid(bars, tc[s][0], tc[l][0], tc[lc][1])
                total, mdd, bench, wr, pf, ah = cr.perf(trades, equity, close)
                results.append(dict(s=s, l=l, lc=lc, total=total, mdd=mdd, wr=wr, pf=pf,
                                    ah=ah, cnt=len(trades), equity=equity))
    results.sort(key=lambda x: x["total"], reverse=True)
    best = results[0]
    # 稳健方案 = 收益/回撤最高（优先 total > 买持50% 的组合，没有则全量里选）
    pool = [r for r in results if r["total"] > bench * 0.5] or results
    robust = max(pool, key=lambda x: x["total"] / max(abs(x["mdd"]), 0.1))
    # noise 增强测试（只对最优方案）
    nz = prof["noise"]
    base_trades, base_equity = sim_hybrid(bars, tc[best["s"]][0], tc[best["l"]][0], tc[best["lc"]][1])
    btotal, bmdd, _, _, _, _ = cr.perf(base_trades, base_equity, close)
    nv1, _ = sim_noise(bars, tc[best["s"]][0], tc[best["l"]][0], tc[best["lc"]][1], nz, v1=True, v3=False)
    t1, m1, _, _, _, _ = cr.perf(nv1, _, close)
    nv3, _ = sim_noise(bars, tc[best["s"]][0], tc[best["l"]][0], tc[best["lc"]][1], nz, v1=False, v3=True)
    t3, m3, _, _, _, _ = cr.perf(nv3, _, close)
    # 增强：最优方案的建议命中率（base=0）+ 底仓分档
    adv_trades, adv_eq, advice, latest, adv_detail = sim_hybrid_adv(
        bars, tc[best["s"]][0], tc[best["l"]][0], tc[best["lc"]][1], base=0.0)
    bases = {}
    for frac in (0.3, 0.5, 0.7, 1.0):
        _, eqb, _, _, _ = sim_hybrid_adv(bars, tc[best["s"]][0], tc[best["l"]][0], tc[best["lc"]][1], base=frac)
        bt, bm, _, _, _, _ = cr.perf([], eqb, close)
        bases[frac] = (bt, bm)
    # 固定默认参数对照（全池统一口径）
    DEFAULTS = [(10, 20, 60), (5, 10, 60), (20, 60, 60), (30, 60, 60), (5, 10, 20)]
    fixed = {}
    for ds, dl, dlc in DEFAULTS:
        dtr, deq = sim_hybrid(bars, tc[ds][0], tc[dl][0], tc[dlc][1])
        dt, dm, _, dw, _, _ = cr.perf(dtr, deq, close)
        fixed[f"{ds}/{dl}+c{dlc}"] = (dt, dm, len(dtr))
    return dict(code=code, prof=prof, bench=bench, n=n,
                best=best, robust=robust,
                noise_base=(btotal, bmdd), noise_v1=(t1, m1), noise_v3=(t3, m3),
                advice=advice, latest=latest, bases=bases, fixed=fixed,
                adv_detail=adv_detail)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("codes", nargs="+")
    ap.add_argument("--smooth", type=int, default=2)
    ap.add_argument("--top", type=int, default=50)
    args = ap.parse_args()
    rows = []
    for code in args.codes:
        print(f"\n{'='*88}\n  扫描 {code} ...\n{'='*88}")
        r = scan_ticket(code, smooth=args.smooth, top_k=args.top)
        if r is None:
            print(f"  {code} 数据不足，跳过")
            continue
        p = r["prof"]
        print(f"  票性: {p['label']}  |  hi3={p['hi3']:+.3f}%  lo5={p['lo5']:+.3f}%  cyc_amp={p['cyc_amp']:.4f}")
        print(f"  买持: {r['bench']:+.2f}%  |  {r['n']}根日K  高z样本{p['n_hi']}  低z样本{p['n_lo']}")
        b = r["best"]; ro = r["robust"]
        print(f"  最优: t{b['s']}/{b['l']}+c{b['lc']}  {b['total']:+.2f}%  回撤{b['mdd']:.2f}%  {b['cnt']}笔  PF{b['pf']:.2f}")
        print(f"  稳健: t{ro['s']}/{ro['l']}+c{ro['lc']}  {ro['total']:+.2f}%  回撤{ro['mdd']:.2f}%  {ro['cnt']}笔  PF{ro['pf']:.2f}")
        nb = r["noise_base"]; n1 = r["noise_v1"]; n3 = r["noise_v3"]
        print(f"  noise 基线: {nb[0]:+.2f}%/{nb[1]:.2f}%  V1: {n1[0]:+.2f}%/{n1[1]:.2f}%  V3: {n3[0]:+.2f}%/{n3[1]:.2f}%")
        rows.append(r)
    # 汇总表
    print(f"\n{'='*100}")
    print(f"{'票':>7}{'票性':>10}{'hi3':>8}{'cyc_amp':>9}{'买持':>8}{'最优收益':>10}{'最优回撤':>8}{'稳健收益':>10}{'稳健回撤':>8}{'V1有效':>6}{'V3有效':>6}")
    print(f"{'':7}{'':10}{'%':>8}{'':>9}{'%':>8}{'%':>10}{'%':>8}{'%':>10}{'%':>8}{'':>6}{'':>6}")
    for r in rows:
        p = r["prof"]; b = r["best"]; ro = r["robust"]
        n1 = r["noise_v1"]; n3 = r["noise_v3"]
        v1ok = "✓" if n1[0] > b["total"] else "✗"
        v3ok = "✓" if n3[0] > b["total"] else "✗"
        print(f"{r['code']:>7}{p['label']:>10}{p['hi3']:>+7.2f}{p['cyc_amp']:>9.4f}{r['bench']:>+7.2f}{b['total']:>+9.2f}{b['mdd']:>7.2f}{ro['total']:>+9.2f}{ro['mdd']:>7.2f}  {v1ok:>4}  {v3ok:>4}")
    print("="*100)

if __name__ == "__main__":
    main()
