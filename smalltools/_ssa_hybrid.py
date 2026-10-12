# -*- coding: utf-8 -*-
"""混合策略：trend 金叉定方向 + cycle 拐点做波段
  入场: trend 短Ls上穿长Ll（金叉，t收盘确认→t+1开盘买）；
        持仓卖出后若 trend 仍多头排列，cycle Lc 谷底确认→再买回（波段上车）。
  离场: cycle Lc 峰顶确认→t+1开盘卖；trend 死叉→t+1开盘强制卖（兜底，先到先走）。
  扫描: trend 组合 {5,10,20,30,60} 两两 × cycle Lc 同集 = 50 组穷尽。
用法: python smalltools\\_ssa_hybrid.py 600176 [--top 15]
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
LS = cr.LS
COLORS = cr.COLORS
COM = cr.COM
STAMP = cr.STAMP
CAP = cr.CAP


def ema(arr, span):
    """EMA 平滑（只用历史值，无未来函数）；NaN 段跳过，首个有效值作种子。"""
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


def sim_hybrid(bars, tr_s, tr_l, cy):
    n = len(bars)
    cash = max(CAP, bars[0]["open"] * 100 * 1.2)   # 高价股本金不足一手时自动放大
    cap0 = cash
    shares = 0
    bi = -1
    bp = 0.0
    bwhy = ""
    trades = []
    equity = np.zeros(n)
    pend = None
    in_bull = False
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
        if t >= 1:
            s0, l0, s1, l1 = tr_s[t - 1], tr_l[t - 1], tr_s[t], tr_l[t]
            gold = dead = False
            if not (np.isnan(s0) or np.isnan(l0) or np.isnan(s1) or np.isnan(l1)):
                gold = s0 <= l0 and s1 > l1
                dead = s0 >= l0 and s1 < l1
                in_bull = s1 > l1
            trough = peak_ = False
            if t >= 2 and not (np.isnan(cy[t - 2]) or np.isnan(cy[t - 1]) or np.isnan(cy[t])):
                trough = cy[t - 2] > cy[t - 1] and cy[t - 1] < cy[t]
                peak_ = cy[t - 2] < cy[t - 1] and cy[t - 1] > cy[t]
            if shares > 0:
                if dead:
                    pend, pend_why = "S", "trend死叉"
                elif peak_:
                    pend, pend_why = "S", "cycle峰顶"
            else:
                if gold:
                    pend, pend_why = "B", ""
                    bwhy = "金叉"
                elif in_bull and trough:
                    pend, pend_why = "B", ""
                    bwhy = "谷底买回"
        equity[t] = cash + shares * bars[t]["close"]
    if shares > 0:
        px = bars[-1]["close"]
        trades.append(dict(bi=bi, bp=bp, si=n - 1, sp=px, why=bwhy,
                           ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                           hold=n - 1 - bi, reason="期末按收盘价"))
    return trades, equity


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("code")
    ap.add_argument("--dstart", default="20230101")
    ap.add_argument("--top", type=int, default=15)
    ap.add_argument("--tf", choices=["d", "1h"], default="d", help="时间级别：d=日线 1h=小时")
    ap.add_argument("--smooth", type=int, default=1, help="trend/cycle 的 EMA 平滑 span，1=不平滑")
    ap.add_argument("--only", default="", help="只跑指定组合，如 '10/20:60,20/60:60'（trend短/trend长:cycleL）")
    args = ap.parse_args()

    if args.tf == "1h":
        h1 = _load("_ssa_tdx_1h")
        bars = h1.fetch_hourly(args.code, lmt=2000)
        unit = "根1H"
    else:
        bars = cr.load_daily(args.code, args.dstart)
        unit = "根日K"
    n = len(bars)
    close = np.array([b["close"] for b in bars])
    dates = [b.get("dt") or b["date"] for b in bars]
    tc = cr.get_trends_cycles(bars)
    if args.smooth > 1:
        tc = {L: (ema(tr, args.smooth), ema(cy, args.smooth)) for L, (tr, cy) in tc.items()}

    combos = []
    if args.only:
        for part in args.only.split(","):
            sl, lc = part.split(":")
            s, l = sl.split("/")
            combos.append((int(s), int(l), int(lc)))
    else:
        for s in LS:
            for l in LS:
                if s >= l:
                    continue
                for lc in LS:
                    combos.append((s, l, lc))

    print("=" * 100)
    print(f"{args.code} 混合策略扫描[{args.tf}|EMA{args.smooth}]  {dates[0]} ~ {dates[-1]}  {n}{unit}  共{len(combos)}组")
    print("规则: trend金叉买 / 多头中cycle谷底买回 / cycle峰顶卖 / trend死叉兜底")
    print("=" * 100)
    results = []
    for s, l, lc in combos:
        trades, equity = sim_hybrid(bars, tc[s][0], tc[l][0], tc[lc][1])
        total, mdd, bench, wr, pf, ah = cr.perf(trades, equity, close)
        results.append(dict(s=s, l=l, lc=lc, total=total, mdd=mdd, wr=wr, pf=pf,
                            ah=ah, cnt=len(trades), trades=trades, equity=equity))
    results.sort(key=lambda r: -r["total"])
    print(f"{'rank':>4}{'trendL':>8}{'cycL':>5}{'总收益':>9}{'回撤':>8}{'笔数':>5}{'胜率':>7}{'盈亏比':>7}{'均持仓':>7}{'收益/回撤':>9}")
    for rk, r in enumerate(results, 1):
        if rk <= args.top or rk > len(results) - 3:
            rr = abs(r["total"] / r["mdd"]) if r["mdd"] < -0.01 else float("inf")
            print(f"{rk:>4}{r['s']:>4}/{r['l']:<4}{r['lc']:>5}{r['total']:>+8.2f}%{r['mdd']:>7.2f}%"
                  f"{r['cnt']:>5}{r['wr']:>6.1f}%{r['pf']:>7.2f}{r['ah']:>6.1f}日{rr:>9.2f}")
        elif rk == args.top + 1:
            print("  ...")
    bench = (close[-1] / close[0] - 1) * 100
    print(f"(买持基准 {bench:+.2f}%)")

    show = results if args.only else results[:1]
    for best in show:
        s, l, lc = best["s"], best["l"], best["lc"]
        trades, equity = best["trades"], best["equity"]
        print("-" * 100)
        print(f"组合: trend L{s}/L{l} + cycle L{lc}  收益 {best['total']:+.2f}%  回撤 {best['mdd']:.2f}%"
              f"  {best['cnt']} 笔  胜率 {best['wr']:.1f}%  盈亏比 {best['pf']:.2f}")
        print(f"{'#':>3} {'买入':>17} {'买价':>8} {'方式':>8} {'卖出':>17} {'卖价':>8}{'收益':>9}{'持仓':>6} 离场原因")
        for i, t in enumerate(trades, 1):
            print(f"{i:>3} {dates[t['bi']]:>17} {t['bp']:>8.2f} {t['why']:>8} "
                  f"{dates[min(t['si'], n - 1)]:>17} {t['sp']:>8.2f}{t['ret']:>+8.2f}%{t['hold']:>5} {t['reason']}")

    # ── 图：K线+trend两线+买卖点 / cycle+拐点 / 净值 ──
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams["font.sans-serif"] = ["SimHei", "Microsoft YaHei"]
    plt.rcParams["axes.unicode_minus"] = False
    xs = np.arange(n)
    for best in show:
        s, l, lc = best["s"], best["l"], best["lc"]
        trades, equity = best["trades"], best["equity"]
        fig, axes = plt.subplots(3, 1, figsize=(17, 14), sharex=True,
                                 gridspec_kw={"height_ratios": [3, 2, 2]})
        ax = axes[0]
        ax.plot(xs, close, lw=1.0, color="k", label="收盘")
        ax.plot(xs, tc[s][0], lw=1.1, color=COLORS[s], label=f"trend L{s}(短)")
        ax.plot(xs, tc[l][0], lw=1.1, color=COLORS[l], label=f"trend L{l}(长)")
        for t in trades:
            ax.scatter(t["bi"], t["bp"], marker="^", color="darkgreen", s=110, zorder=5, edgecolors="k", linewidths=0.6)
            ax.scatter(t["si"], t["sp"], marker="v", color="darkred", s=110, zorder=5, edgecolors="k", linewidths=0.6)
        ax.set_title(f"{args.code}[{args.tf}] 混合：trend L{s}/L{l} 金叉定方向 + cycle L{lc} 拐点波段   "
                     f"收益{best['total']:+.1f}% 回撤{best['mdd']:.1f}% {best['cnt']}笔")
        ax.legend(loc="upper left", fontsize=9)
        ax.grid(alpha=0.3)
        axm = axes[1]
        cy = tc[lc][1]
        axm.plot(xs, cy, lw=1.0, color="tab:blue", label=f"cycle L{lc}")
        axm.axhline(0, color="gray", lw=0.5)
        for t in range(2, n):
            if np.isnan(cy[t - 2]) or np.isnan(cy[t - 1]) or np.isnan(cy[t]):
                continue
            if cy[t - 2] > cy[t - 1] and cy[t - 1] < cy[t]:
                axm.scatter(t, cy[t - 1], marker="^", color="gold", s=60, zorder=5, edgecolors="k", linewidths=0.5)
            elif cy[t - 2] < cy[t - 1] and cy[t - 1] > cy[t]:
                axm.scatter(t, cy[t - 1], marker="v", color="navy", s=60, zorder=5, edgecolors="k", linewidths=0.5)
        axm.set_title(f"cycle L{lc} 拐点（金▲谷底 蓝▼峰顶）")
        axm.legend(loc="upper left", fontsize=9)
        axm.grid(alpha=0.3)
        ax2 = axes[2]
        ax2.plot(xs, equity, lw=1.1, color="tab:red", label="策略净值")
        ax2.plot(xs, CAP * close / close[0], lw=0.9, color="gray", label="买持基准")
        ax2.legend(loc="upper left", fontsize=9)
        ax2.grid(alpha=0.3)
        ax2.set_title("净值对比")
        step = max(n // 14, 1)
        axes[2].set_xticks(xs[::step])
        axes[2].set_xticklabels([dates[i] for i in range(0, n, step)], rotation=30, ha="right", fontsize=8)
        plt.tight_layout()
        out = os.path.join("data", "review_tables", f"ssa_hybrid_{args.code}_{args.tf}_t{s}_{l}_c{lc}.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        plt.savefig(out, dpi=110)
        plt.close()
        print("图:", out)


if __name__ == "__main__":
    main()
