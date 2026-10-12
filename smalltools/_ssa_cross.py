# -*- coding: utf-8 -*-
"""SSA 趋势线金叉死叉策略：
  图1: 日K + L5/10/20/30/60 五条 SSA 趋势线（仿 MA 多均线）+ 五条周期分量（分两个面板）
  策略: 短 L trend 上穿长 L trend（金叉，t 收盘确认）→ t+1 开盘满仓买；
        下穿（死叉）→ t+1 开盘清仓。无未来函数，成本同前（佣金0.00025双边+印花税0.0005）。
用法: python smalltools\\_ssa_cross.py 600176
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


tdx = _load("_ssa_tdx")
h1 = _load("_ssa_tdx_1h")   # fetch_daily_sina 补齐用

COM = 0.00025
STAMP = 0.0005
CAP = 100000.0
LS = [5, 10, 20, 30, 60]
COLORS = {5: "#e6194b", 10: "#f58231", 20: "#3cb44b", 30: "#4363d8", 60: "#911eb4"}


def load_daily(code, dstart):
    bars = tdx.load_tdx(code)
    if len(bars) < 100:
        raise SystemExit("日线数据不足")
    # .day 滞后时用新浪日K补齐
    try:
        add = h1.fetch_daily_sina(code)
        if add and add[-1]["date"] > bars[-1]["date"]:
            last = bars[-1]["date"]
            ext = [b for b in add if b["date"] > last]
            bars = bars + ext
            print(f"[补齐] .day 止于 {last}，新浪补 {len(ext)} 根 → {bars[-1]['date']}")
    except Exception as e:
        print(f"[补齐失败] {e}，仅用 .day")
    return [b for b in bars if b["date"] >= dstart]


def get_trends_cycles(bars):
    """对每个 L 滚动分解，返回 {L: (trendAt, cycleAt)}"""
    out = {}
    for L in LS:
        r = tdx.run(bars, L=L, strat="cycle", stop_side="none", ampK=0.0)
        out[L] = (r["trendAt"], r["cycleAt"])
    return out


def sim_cross(bars, tr_s, tr_l):
    """金叉买/死叉卖，t 收盘确认 → t+1 开盘成交。返回 trades/equity。"""
    n = len(bars)
    cash = CAP
    shares = 0
    bi = -1
    bp = 0.0
    trades = []
    equity = np.zeros(n)
    pend = None  # 'B'/'S' 待执行（上一根收盘确认）
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
        if t >= 1:
            s0, l0 = tr_s[t - 1], tr_l[t - 1]
            s1, l1 = tr_s[t], tr_l[t]
            if not (np.isnan(s0) or np.isnan(l0) or np.isnan(s1) or np.isnan(l1)):
                if s0 <= l0 and s1 > l1:
                    pend = "B"
                elif s0 >= l0 and s1 < l1:
                    pend = "S"
        equity[t] = cash + shares * bars[t]["close"]
    if shares > 0:
        px = bars[-1]["close"]
        trades.append(dict(bi=bi, bp=bp, si=n - 1, sp=px,
                           ret=(px * (1 - COM - STAMP) / (bp * (1 + COM)) - 1) * 100,
                           hold=n - 1 - bi, reason="期末按收盘价"))
    return trades, equity


def perf(trades, equity, close):
    peak = equity[0] if len(equity) else CAP
    cap0 = peak
    mdd = 0.0
    for e in equity:
        peak = max(peak, e)
        mdd = min(mdd, (e / peak - 1) * 100)
    total = (equity[-1] / cap0 - 1) * 100 if len(equity) else 0.0
    bench = (close[-1] / close[0] - 1) * 100
    wins = [t for t in trades if t["ret"] > 0]
    gl = sum(abs(t["ret"]) for t in trades if t["ret"] <= 0)
    gw = sum(t["ret"] for t in wins)
    wr = len(wins) / len(trades) * 100 if trades else 0
    pf = gw / gl if gl > 1e-9 else float("inf")
    ah = sum(t["hold"] for t in trades) / len(trades) if trades else 0
    return total, mdd, bench, wr, pf, ah


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("code")
    ap.add_argument("--dstart", default="20230101")
    args = ap.parse_args()

    bars = load_daily(args.code, args.dstart)
    n = len(bars)
    close = np.array([b["close"] for b in bars])
    dates = [b["date"] for b in bars]
    tc = get_trends_cycles(bars)

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams["font.sans-serif"] = ["SimHei", "Microsoft YaHei"]
    plt.rcParams["axes.unicode_minus"] = False
    xs = np.arange(n)

    # ── 叠加图：上=K线+5条trend，下=5条cycle ──
    fig, axes = plt.subplots(2, 1, figsize=(17, 11), sharex=True,
                             gridspec_kw={"height_ratios": [3, 2]})
    ax = axes[0]
    ax.plot(xs, close, lw=1.0, color="k", label="收盘")
    for L in LS:
        ax.plot(xs, tc[L][0], lw=1.1, color=COLORS[L], label=f"trend L{L}", alpha=0.9)
    ax.set_title(f"{args.code} 日K + SSA趋势线（L5/10/20/30/60，仿MA多均线）")
    ax.legend(loc="upper left", fontsize=9, ncol=3)
    ax.grid(alpha=0.3)
    ax2 = axes[1]
    for L in LS:
        ax2.plot(xs, tc[L][1], lw=1.0, color=COLORS[L], label=f"cycle L{L}", alpha=0.9)
    ax2.axhline(0, color="gray", lw=0.5)
    ax2.set_title("SSA 周期分量（L5/10/20/30/60）")
    ax2.legend(loc="upper left", fontsize=9, ncol=5)
    ax2.grid(alpha=0.3)
    step = max(n // 14, 1)
    axes[1].set_xticks(xs[::step])
    axes[1].set_xticklabels([dates[i] for i in range(0, n, step)], rotation=30, ha="right", fontsize=8)
    plt.tight_layout()
    out1 = os.path.join("data", "review_tables", f"ssa_cross_overlay_{args.code}.png")
    os.makedirs(os.path.dirname(out1), exist_ok=True)
    plt.savefig(out1, dpi=110)
    plt.close()
    print("叠加图:", out1)

    # ── 金叉死叉回测：trend 与 cycle 各扫一遍 ──
    print("=" * 96)
    print(f"{args.code} SSA 金叉死叉回测   {dates[0]} ~ {dates[-1]}  {n}根日K")

    def scan(series_idx, tag):
        print("=" * 96)
        print(f"[{tag}] 短L上穿长L=金叉买 / 下穿=死叉卖（t收盘确认，t+1开盘成交）")
        print(f"{'短/长L':>8}{'总收益':>9}{'回撤':>8}{'买持':>9}{'笔数':>5}{'胜率':>7}{'盈亏比':>7}{'均持仓':>7}")
        results = []
        for s in LS:
            for l in LS:
                if s >= l:
                    continue
                trades, equity = sim_cross(bars, tc[s][series_idx], tc[l][series_idx])
                total, mdd, bench, wr, pf, ah = perf(trades, equity, close)
                results.append((s, l, total, mdd, wr, pf, ah, len(trades), trades, equity))
                print(f"{s:>4}/{l:<4}{total:>+8.2f}%{mdd:>7.2f}%{bench:>+8.2f}%{len(trades):>5}"
                      f"{wr:>6.1f}%{pf:>7.2f}{ah:>6.1f}日")
        return results

    res_t = scan(0, "trend 趋势线交叉")
    res_c = scan(1, "cycle 周期分量交叉")

    def report_and_plot(results, series_idx, tag, fname_tag):
        results = sorted(results, key=lambda r: -r[2])
        s, l, total, mdd, wr, pf, ah, cnt, trades, equity = results[0]
        print("-" * 96)
        print(f"[{tag}] 最优: L{s}/L{l}  总收益 {total:+.2f}%  回撤 {mdd:.2f}%  {cnt} 笔  胜率 {wr:.1f}%  盈亏比 {pf:.2f}")
        print(f"{'#':>3} {'买入':>10} {'买价':>8} {'卖出':>10} {'卖价':>8}{'收益':>9}{'持仓':>6}")
        for i, t in enumerate(trades, 1):
            bd = dates[t["bi"]]
            sd = dates[min(t["si"], n - 1)]
            print(f"{i:>3} {bd:>10} {t['bp']:>8.2f} {sd:>10} {t['sp']:>8.2f}{t['ret']:>+8.2f}%{t['hold']:>5}日")

        fig, axes = plt.subplots(3, 1, figsize=(17, 14), sharex=True,
                                 gridspec_kw={"height_ratios": [3, 2, 2]})
        ax = axes[0]
        ax.plot(xs, close, lw=1.0, color="k", label="收盘")
        for t in trades:
            ax.scatter(t["bi"], t["bp"], marker="^", color="darkgreen", s=110, zorder=5, edgecolors="k", linewidths=0.6)
            ax.scatter(t["si"], t["sp"], marker="v", color="darkred", s=110, zorder=5, edgecolors="k", linewidths=0.6)
        ax.set_title(f"{args.code} {tag} L{s}/L{l}：▲买 ▼卖   收益{total:+.1f}% 回撤{mdd:.1f}% {cnt}笔")
        ax.legend(loc="upper left", fontsize=9)
        ax.grid(alpha=0.3)
        # 中间面板：两条交叉线 + 交叉点标记
        axm = axes[1]
        ls_s = tc[s][series_idx]; ls_l = tc[l][series_idx]
        axm.plot(xs, ls_s, lw=1.2, color=COLORS[s], label=f"{tag} L{s}(短)")
        axm.plot(xs, ls_l, lw=1.2, color=COLORS[l], label=f"{tag} L{l}(长)")
        axm.axhline(0, color="gray", lw=0.5)
        # 标全部交叉点
        for t in range(1, n):
            a0, b0 = ls_s[t - 1], ls_l[t - 1]
            a1, b1 = ls_s[t], ls_l[t]
            if np.isnan(a0) or np.isnan(b0) or np.isnan(a1) or np.isnan(b1):
                continue
            if a0 <= b0 and a1 > b1:
                axm.scatter(t, a1, marker="^", color="gold", s=90, zorder=5, edgecolors="k", linewidths=0.6)
            elif a0 >= b0 and a1 < b1:
                axm.scatter(t, a1, marker="v", color="navy", s=90, zorder=5, edgecolors="k", linewidths=0.6)
        axm.set_title(f"{tag} 交叉点（金▲=金叉 蓝▼=死叉）")
        axm.legend(loc="upper left", fontsize=9)
        axm.grid(alpha=0.3)
        ax2 = axes[2]
        ax2.plot(xs, equity, lw=1.1, color="tab:red", label="策略净值")
        ax2.plot(xs, CAP * close / close[0], lw=0.9, color="gray", label="买持基准")
        ax2.legend(loc="upper left", fontsize=9)
        ax2.grid(alpha=0.3)
        ax2.set_title("净值对比")
        axes[2].set_xticks(xs[::step])
        axes[2].set_xticklabels([dates[i] for i in range(0, n, step)], rotation=30, ha="right", fontsize=8)
        plt.tight_layout()
        out = os.path.join("data", "review_tables", f"ssa_cross_bt_{args.code}_{fname_tag}_s{s}_l{l}.png")
        plt.savefig(out, dpi=110)
        plt.close()
        print(f"[{tag}] 回测图:", out)

    report_and_plot(res_t, 0, "trend", "trend")
    report_and_plot(res_c, 1, "cycle", "cycle")


if __name__ == "__main__":
    main()
