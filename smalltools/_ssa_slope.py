# -*- coding: utf-8 -*-
"""短trend 斜率拐头策略（对照混合策略）：
  买入: 短 trend 斜率由向下转为向上（一阶差分由负转正，t收盘确认）→ t+1开盘买；
  卖出: 短 trend 斜率由向上转为向下（差分由正转负）→ t+1开盘卖。
  对照: 同票同段 hybrid（金叉定方向+cycle拐点，50组取最优）与买持基准。
用法: python smalltools\\_ssa_slope.py 600176 [--smooth 2] [--dstart 20230101] [--top 5]
"""
import argparse
import importlib.util
import os

import numpy as np


def _load(name):
    spec = importlib.util.spec_from_file_location(
        name, os.path.join(os.path.dirname(__file__), name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


cr = _load("_ssa_cross")
hy = _load("_ssa_hybrid")
LS, COLORS, COM, STAMP, CAP = cr.LS, cr.COLORS, cr.COM, cr.STAMP, cr.CAP
ema = hy.ema


def sim_slope(bars, tr_s, tr_l=None, bull_filter=False):
    """斜率拐头回测：t 收盘确认 → t+1 开盘成交，无未来函数。
    bull_filter=True 时买入额外要求多头排列（短trend在长trend上方），卖出不变。"""
    n = len(bars)
    cash = max(CAP, bars[0]["open"] * 100 * 1.2)
    shares = 0
    bi = -1
    bp = 0.0
    trades = []
    equity = np.zeros(n)
    pend = None
    for t in range(n):
        px = bars[t]["open"]
        if pend == "B" and shares == 0:
            lot = int(cash / (1 + COM) / px / 100) * 100
            if lot > 0:
                cash -= lot * px * (1 + COM)
                shares = lot
                bi, bp = t, px
        elif pend == "S" and shares > 0:
            cash += shares * px * (1 - COM - STAMP)
            trades.append(dict(bi=bi, bp=bp, si=t, sp=px,
                               ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                               hold=t - bi))
            shares = 0
        pend = None
        if t >= 2:
            d0 = tr_s[t - 1] - tr_s[t - 2]   # 昨日斜率
            d1 = tr_s[t] - tr_s[t - 1]       # 今日斜率
            if not (np.isnan(d0) or np.isnan(d1)):
                ok_buy = True
                if bull_filter and tr_l is not None:
                    ok_buy = not np.isnan(tr_l[t]) and tr_s[t] > tr_l[t]
                if d0 < 0 and d1 > 0:
                    if ok_buy:
                        pend = "B"           # 斜率由向下转向上
                elif d0 > 0 and d1 < 0:
                    pend = "S"               # 斜率由向上转向下
        equity[t] = cash + shares * bars[t]["close"]
    if shares > 0:
        px = bars[-1]["close"]
        trades.append(dict(bi=bi, bp=bp, si=n - 1, sp=px,
                           ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                           hold=n - 1 - bi, reason="期末按收盘价"))
    return trades, equity


def sim_mid(bars, tr_s, tr_l, cy):
    """折中版混合策略：买入窗口 = 短trend斜率>0（低延迟，替代金叉排列），
    买入触发 = cycle 谷底确认；卖出 = cycle 峰顶 / trend 死叉兜底。
    t 收盘确认 → t+1 开盘成交，无未来函数。"""
    n = len(bars)
    cash = max(CAP, bars[0]["open"] * 100 * 1.2)
    shares = 0
    bi = -1
    bp = 0.0
    bwhy = ""
    trades = []
    equity = np.zeros(n)
    pend = None
    pend_why = ""
    for t in range(n):
        px = bars[t]["open"]
        if pend == "B" and shares == 0:
            lot = int(cash / (1 + COM) / px / 100) * 100
            if lot > 0:
                cash -= lot * px * (1 + COM)
                shares = lot
                bi, bp = t, px
        elif pend == "S" and shares > 0:
            cash += shares * px * (1 - COM - STAMP)
            trades.append(dict(bi=bi, bp=bp, si=t, sp=px, why=bwhy,
                               ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                               hold=t - bi, reason=pend_why))
            shares = 0
        pend = None
        pend_why = ""
        # 信号（t 收盘确认）
        slope_up = False
        if t >= 2 and not (np.isnan(tr_s[t]) or np.isnan(tr_s[t - 1]) or np.isnan(tr_s[t - 2])):
            slope_up = tr_s[t] - tr_s[t - 1] > 0     # 窗口开：斜率向上（替代 短>长 排列）
        dead = False
        if t >= 1 and not (np.isnan(tr_s[t - 1]) or np.isnan(tr_l[t - 1]) or np.isnan(tr_s[t]) or np.isnan(tr_l[t])):
            dead = tr_s[t - 1] >= tr_l[t - 1] and tr_s[t] < tr_l[t]
        trough = peak_ = False
        if t >= 2 and not (np.isnan(cy[t - 2]) or np.isnan(cy[t - 1]) or np.isnan(cy[t])):
            trough = cy[t - 2] > cy[t - 1] and cy[t - 1] < cy[t]
            peak_ = cy[t - 2] < cy[t - 1] and cy[t - 1] > cy[t]
        if shares > 0:
            if dead:
                pend, pend_why = "S", "死叉兜底"
            elif peak_:
                pend, pend_why = "S", "cycle峰顶"
        else:
            if slope_up and trough:
                pend = "B"
                bwhy = "斜率窗口+谷底"
        equity[t] = cash + shares * bars[t]["close"]
    if shares > 0:
        px = bars[-1]["close"]
        trades.append(dict(bi=bi, bp=bp, si=n - 1, sp=px, why=bwhy,
                           ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                           hold=n - 1 - bi, reason="期末按收盘价"))
    return trades, equity


def sim_or(bars, tr_s, tr_l, cy):
    """变体：窗口 = 金叉排列(短>长) OR 短trend斜率向上；窗口由关转开首日=建仓（低延迟入场），
    窗口内 cycle 谷底=买回；卖出 = cycle峰顶 / 死叉兜底。金叉排列兜底保证主升段回调不关窗。"""
    n = len(bars)
    cash = max(CAP, bars[0]["open"] * 100 * 1.2)
    shares = 0
    bi = -1
    bp = 0.0
    trades = []
    equity = np.zeros(n)
    pend = None
    pend_why = ""
    bull = False
    for t in range(n):
        px = bars[t]["open"]
        if pend == "B" and shares == 0:
            lot = int(cash / (1 + COM) / px / 100) * 100
            if lot > 0:
                cash -= lot * px * (1 + COM)
                shares = lot
                bi, bp = t, px
        elif pend == "S" and shares > 0:
            cash += shares * px * (1 - COM - STAMP)
            trades.append(dict(bi=bi, bp=bp, si=t, sp=px,
                               ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                               hold=t - bi, reason=pend_why))
            shares = 0
        pend = None
        pend_why = ""
        bull_prev = bull
        win = False
        if t >= 2 and not (np.isnan(tr_s[t]) or np.isnan(tr_s[t - 1]) or np.isnan(tr_s[t - 2])):
            slope_up = tr_s[t] - tr_s[t - 1] > 0
            bull_arr = (not np.isnan(tr_l[t])) and tr_s[t] > tr_l[t]
            win = bull_arr or slope_up
            bull = win
        reopen = win and not bull_prev
        dead = False
        if t >= 1 and not (np.isnan(tr_s[t - 1]) or np.isnan(tr_l[t - 1]) or np.isnan(tr_s[t]) or np.isnan(tr_l[t])):
            dead = tr_s[t - 1] >= tr_l[t - 1] and tr_s[t] < tr_l[t]
        trough = peak_ = False
        if t >= 2 and not (np.isnan(cy[t - 2]) or np.isnan(cy[t - 1]) or np.isnan(cy[t])):
            trough = cy[t - 2] > cy[t - 1] and cy[t - 1] < cy[t]
            peak_ = cy[t - 2] < cy[t - 1] and cy[t - 1] > cy[t]
        if shares > 0:
            if dead:
                pend, pend_why = "S", "死叉兜底"
            elif peak_:
                pend, pend_why = "S", "cycle峰顶"
        else:
            if reopen:
                pend = "B"                 # 窗口重开首日（排列或斜率先到先开）
            elif win and trough:
                pend = "B"                 # 窗口内谷底买回
        equity[t] = cash + shares * bars[t]["close"]
    if shares > 0:
        px = bars[-1]["close"]
        trades.append(dict(bi=bi, bp=bp, si=n - 1, sp=px,
                           ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                           hold=n - 1 - bi, reason="期末按收盘价"))
    return trades, equity


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("code")
    ap.add_argument("--dstart", default="20230101")
    ap.add_argument("--smooth", type=int, default=2, help="trend/cycle EMA 平滑 span（App 端默认 2）")
    ap.add_argument("--top", type=int, default=5)
    args = ap.parse_args()

    bars = cr.load_daily(args.code, args.dstart)
    n = len(bars)
    close = np.array([b["close"] for b in bars])
    dates = [b.get("dt") or b["date"] for b in bars]
    tc = cr.get_trends_cycles(bars)
    if args.smooth > 1:
        tc = {L: (ema(tr, args.smooth), ema(cy_, args.smooth)) for L, (tr, cy_) in tc.items()}
    tr_of = {L: tc[L][0] for L in LS}

    # hybrid 对照：50 组取最优（与 PC 全池结论一致预期 t5/10+c60）
    best_h = None
    for s in LS:
        for l in LS:
            if s >= l:
                continue
            for lc in LS:
                trades, equity = hy.sim_hybrid(bars, tc[s][0], tc[l][0], tc[lc][1])
                total, mdd, bench, wr, pf, ah = cr.perf(trades, equity, close)
                if best_h is None or total > best_h["total"]:
                    best_h = dict(s=s, l=l, lc=lc, trades=trades, equity=equity,
                                  total=total, mdd=mdd, cnt=len(trades))
    # ── 折中版扫描：窗口=短trend斜率>0，触发=cycle谷底，卖出=峰顶+死叉兜底 ──
    mid_best = None
    mid_rows = []
    for s in LS:
        for l in LS:
            if s >= l:
                continue
            for lc in LS:
                trades, equity = sim_mid(bars, tc[s][0], tc[l][0], tc[lc][1])
                total, mdd, _, wr, _, _ = cr.perf(trades, equity, close)
                mid_rows.append((total, mdd, wr, s, l, lc, len(trades), trades, equity))
                if mid_best is None or total > mid_best["total"]:
                    mid_best = dict(s=s, l=l, lc=lc, trades=trades, equity=equity,
                                    total=total, mdd=mdd, cnt=len(trades))
    mid_rows.sort(key=lambda r: -r[0])
    # ── 变体扫描：窗口=排列OR斜率，重开首日建仓，谷底买回，峰顶/死叉卖出 ──
    or_best = None
    for s in LS:
        for l in LS:
            if s >= l:
                continue
            for lc in LS:
                trades, equity = sim_or(bars, tc[s][0], tc[l][0], tc[lc][1])
                total, mdd, _, wr, _, _ = cr.perf(trades, equity, close)
                if or_best is None or total > or_best["total"]:
                    or_best = dict(s=s, l=l, lc=lc, trades=trades, equity=equity,
                                   total=total, mdd=mdd, cnt=len(trades))
    hs, hl, hlc = best_h["s"], best_h["l"], best_h["lc"]
    print("=" * 96)
    print(f"变体最优（窗口=排列OR斜率） L{or_best['s']}/L{or_best['l']}+c{or_best['lc']}: "
          f"{or_best['total']:+.2f}% / {or_best['mdd']:.2f}% / {or_best['cnt']}笔")
    focus = {(5, 10, 60), (10, 20, 60), (30, 60, 60), (5, 20, 60), (10, 60, 60)}
    print("=" * 96)
    print(f"折中版（窗口=短trend斜率>0 · 触发=cycle谷底 · 卖出=峰顶+死叉兜底）全部组合（★=App推荐口径）：")
    print(f"{'trend短/长':>10}{'cycleL':>7}{'总收益':>9}{'回撤':>8}{'笔数':>5}{'胜率':>7}")
    for total, mdd, wr, s, l, lc, cnt, _, _ in mid_rows:
        star = "★" if (s, l, lc) in focus else " "
        print(f"{star}L{s:>3}/L{l:<4}{lc:>6}{total:>+9.2f}%{mdd:>8.2f}%{cnt:>5}{wr:>7.1f}%")
    print(f"折中最优 L{mid_best['s']}/L{mid_best['l']}+c{mid_best['lc']}: "
          f"{mid_best['total']:+.2f}% / {mid_best['mdd']:.2f}% / {mid_best['cnt']}笔")

    print("=" * 96)
    print(f"{args.code} 短trend斜率拐头策略[EMA{args.smooth}]  {dates[0]} ~ {dates[-1]}  {n}根日K")
    print("规则: 短trend斜率由负转正→次日开盘买；由正转负→次日开盘卖（收盘确认，t+1开盘成交）")
    print("=" * 96)
    print(f"{'trendL':>7}{'裸斜率':>9}{'回撤':>8}{'笔':>4}{'胜率':>7}   "
          f"{'斜率+多头过滤':>12}{'回撤':>8}{'笔':>4}{'胜率':>7}   || hybrid最优 "
          f"L{hs}/L{hl}+c{hlc}: {best_h['total']:+.2f}% / {best_h['mdd']:.2f}% / {best_h['cnt']}笔")
    results = []
    for L in LS:
        tr1, eq1 = sim_slope(bars, tr_of[L])
        t1, m1, _, w1, p1, _ = cr.perf(tr1, eq1, close)
        # 多头过滤长线 = 比当前更长的最小 trend；L60 无更长线，过滤版跳过
        longer = [x for x in LS if x > L]
        if longer:
            tr2, eq2 = sim_slope(bars, tr_of[L], tr_of[longer[0]], bull_filter=True)
            t2, m2, _, w2, p2, _ = cr.perf(tr2, eq2, close)
        else:
            tr2, eq2, t2, m2, w2 = None, None, float("nan"), float("nan"), float("nan")
        results.append(dict(L=L, total=t1, mdd=m1, wr=w1, pf=p1, cnt=len(tr1), trades=tr1, equity=eq1,
                            f_total=t2, f_mdd=m2, f_wr=w2, f_cnt=len(tr2) if tr2 else 0,
                            f_trades=tr2, f_equity=eq2))
        f_txt = f"{t2:>+13.2f}%{m2:>8.2f}%{len(tr2):>4}{w2:>7.1f}%" if longer else f"{'—(无更长线)':>36}"
        print(f"L{L:>5}{t1:>+9.2f}%{m1:>8.2f}%{len(tr1):>4}{w1:>7.1f}%   {f_txt}")
    bench = (close[-1] / close[0] - 1) * 100
    print(f"(买持基准 {bench:+.2f}%)")

    results.sort(key=lambda r: -max(r["total"], r["f_total"]))
    print("-" * 96)
    for r in results[:args.top]:
        print(f"top: L{r['L']}  裸斜率 {r['total']:+.2f}%/{r['mdd']:.2f}%/{r['cnt']}笔  "
              f"过滤后 {r['f_total']:+.2f}%/{r['f_mdd']:.2f}%/{r['f_cnt']}笔")

    # ── 图：最优 slope 组合（价格+trend短线+买卖点 / 斜率+拐点 / 净值对比）──
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams["font.sans-serif"] = ["SimHei", "Microsoft YaHei"]
    plt.rcParams["axes.unicode_minus"] = False
    xs = np.arange(n)
    best = or_best
    Ls, Ll, Lc = best["s"], best["l"], best["lc"]
    ts_, tl_, cy_ = tc[Ls][0], tc[Ll][0], tc[Lc][1]
    trades, equity = best["trades"], best["equity"]

    fig, axes = plt.subplots(3, 1, figsize=(17, 14), sharex=True,
                             gridspec_kw={"height_ratios": [3, 2, 2]})
    ax = axes[0]
    ax.plot(xs, close, lw=1.0, color="k", label="收盘")
    ax.plot(xs, ts_, lw=1.2, color=COLORS[Ls], label=f"trend短 L{Ls}")
    ax.plot(xs, tl_, lw=1.2, color=COLORS[Ll], label=f"trend长 L{Ll}", alpha=0.8)
    for t in trades:
        ax.scatter(t["bi"], t["bp"], marker="^", color="darkgreen", s=110, zorder=5, edgecolors="k", linewidths=0.6)
        ax.scatter(t["si"], t["sp"], marker="v", color="darkred", s=110, zorder=5, edgecolors="k", linewidths=0.6)
    ax.set_title(f"{args.code} 变体策略 L{Ls}/L{Ll}+c{Lc}[EMA{args.smooth}]：窗口=排列OR斜率 · "
                 f"买=重开首日/cycle谷底 · 卖=峰顶+死叉兜底   "
                 f"▲买 ▼卖  收益{best['total']:+.1f}% 回撤{best['mdd']:.1f}% {best['cnt']}笔")
    ax.legend(loc="upper left", fontsize=9)
    ax.grid(alpha=0.3)
    axm = axes[1]
    axm.plot(xs, cy_, lw=1.1, color=COLORS[Lc], label=f"cycle L{Lc}")
    axm.axhline(0, color="gray", lw=0.6)
    for t in range(2, n):
        if np.isnan(cy_[t - 2]) or np.isnan(cy_[t - 1]) or np.isnan(cy_[t]):
            continue
        if cy_[t - 2] > cy_[t - 1] and cy_[t - 1] < cy_[t]:
            axm.scatter(t, cy_[t], marker="^", color="gold", s=70, zorder=5, edgecolors="k", linewidths=0.5)
        elif cy_[t - 2] < cy_[t - 1] and cy_[t - 1] > cy_[t]:
            axm.scatter(t, cy_[t], marker="v", color="navy", s=70, zorder=5, edgecolors="k", linewidths=0.5)
    axm.set_title("cycle分量（金▲=谷底确认→买信号 蓝▼=峰顶确认→卖信号）")
    axm.legend(loc="upper left", fontsize=9)
    axm.grid(alpha=0.3)
    ax2 = axes[2]
    ax2.plot(xs, equity, lw=1.2, color="tab:red",
             label=f"变体(排列OR斜率) L{Ls}/L{Ll}+c{Lc} {best['total']:+.1f}%")
    ax2.plot(xs, best_h["equity"], lw=1.0, color="tab:blue",
             label=f"旧版(金叉) L{hs}/L{hl}+c{hlc} {best_h['total']:+.1f}%")
    ax2.plot(xs, mid_best["equity"], lw=1.0, color="tab:orange",
             label=f"折中(纯斜率窗) L{mid_best['s']}/L{mid_best['l']}+c{mid_best['lc']} {mid_best['total']:+.1f}%")
    ax2.plot(xs, CAP * close / close[0], lw=0.9, color="gray", label=f"买持 {bench:+.1f}%")
    ax2.legend(loc="upper left", fontsize=9)
    ax2.grid(alpha=0.3)
    ax2.set_title("净值对比：变体 vs 旧版(金叉) vs 折中 vs 买持")
    step = max(n // 14, 1)
    axes[2].set_xticks(xs[::step])
    axes[2].set_xticklabels([dates[i] for i in range(0, n, step)], rotation=30, ha="right", fontsize=8)
    plt.tight_layout()
    out = os.path.join("data", "review_tables",
                       f"ssa_or_{args.code}_L{Ls}L{Ll}c{Lc}_ema{args.smooth}.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    plt.savefig(out, dpi=110)
    plt.close()
    print("图:", out)

    # 交易明细
    print("-" * 96)
    print(f"{'#':>3} {'买入':>12} {'买价':>8} {'卖出':>12} {'卖价':>8}{'收益':>9}{'持仓':>6}")
    for i, t in enumerate(trades, 1):
        print(f"{i:>3} {dates[t['bi']]:>12} {t['bp']:>8.2f} "
              f"{dates[min(t['si'], n - 1)]:>12} {t['sp']:>8.2f}{t['ret']:>+8.2f}%{t['hold']:>5}日")


if __name__ == "__main__":
    main()
