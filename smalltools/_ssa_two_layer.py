# -*- coding: utf-8 -*-
"""双层 SSA 周期策略：
  日线层：cycle 拐点定方向 —— 谷底确认买（满仓进场）、峰顶确认卖（清仓离场），无轨道止损。
  1H 层：仅在日线持仓窗口内，cycle 拐点低买高卖做 T（0/满仓切换），窗口外空仓不做 T。
无未来函数：信号 t 收盘确认，t+1（日线下个交易日首根 1H / 1H 下一根）开盘价成交。
用法: python smalltools\\_ssa_two_layer.py 600176 --png
"""
import argparse
import importlib.util
import os
from datetime import datetime

import numpy as np


def _load(name):
    spec = importlib.util.spec_from_file_location(
        name, os.path.join(os.path.dirname(__file__), name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


tdx = _load("_ssa_tdx")        # load_tdx / run
h1 = _load("_ssa_tdx_1h")      # fetch_hourly

COM = 0.00025
STAMP = 0.0005
CAP = 100000.0


def sim_two_layer(h_bars, d_events, first_bar, sig_buy_h, sig_sell_h, base=0.0):
    """h_bars: 1H K；d_events: {yyyyMMdd: +1/-1} 日线事件（当日首根 1H 开盘执行）；
    sig_buy_h/sig_sell_h: 1H 拐点信号数组（t 收盘确认，t+1 开盘成交）。
    base: 窗口内底仓比例（0~1）。底仓进场后窗口内不动，剩余 (1-base) 仓位做 1H T。
    base=1.0 等价于日线层窗口满仓不做T；base=0 为纯做T（原模式）。"""
    n = len(h_bars)
    cash = CAP
    sh_base = 0                # 底仓股数（窗口内不动）
    sh_t = 0                   # T仓股数
    allow = False
    equity = np.zeros(n)
    d_trades = []
    t_trades = []
    cur = None
    allow_span = []
    t_budget = 0.0             # T仓可用资金额度（进场时按 1-base 分配）

    def buy_lot(px, money):
        lot = int(money / (1 + COM) / px / 100) * 100
        return lot

    win_start = None
    for i in range(n):
        b = h_bars[i]
        acted_daily = False
        if first_bar.get(b["date"]) == i and b["date"] in d_events:
            ev = d_events[b["date"]]
            acted_daily = True
            if ev > 0 and not allow:
                allow = True
                win_start = i
                # 底仓：base 比例资金一次性买入，窗口内不动
                if base > 0:
                    lb = buy_lot(b["open"], cash * base)
                    if lb > 0:
                        cash -= lb * b["open"] * (1 + COM)
                        sh_base = lb
                # T仓：base=0 时立即满仓（原模式）；0<base<1 时留现金等 1H 谷底
                t_budget = cash
                if base == 0.0:
                    lt = buy_lot(b["open"], t_budget)
                    if lt > 0:
                        cash -= lt * b["open"] * (1 + COM)
                        sh_t = lt
                        cur = dict(bi=i, bp=b["open"], sh=lt)
                d_trades.append(dict(side="B", i=i, dt=b["dt"], px=b["open"]))
            elif ev < 0 and allow:
                allow = False
                if win_start is not None:
                    allow_span.append((win_start, i))
                    win_start = None
                if sh_base > 0:
                    cash += sh_base * b["open"] * (1 - COM - STAMP)
                    sh_base = 0
                if sh_t > 0:
                    cash += sh_t * b["open"] * (1 - COM - STAMP)
                    if cur is not None:
                        t_trades.append(dict(bi=cur["bi"], bp=cur["bp"], si=i, sp=b["open"],
                                             ret=(b["open"] * (1 - COM - STAMP) / (cur["bp"] * (1 + COM)) - 1) * 100,
                                             hold=i - cur["bi"], reason="日线峰顶离场"))
                        cur = None
                    sh_t = 0
                d_trades.append(dict(side="S", i=i, dt=b["dt"], px=b["open"]))
            elif ev < 0 and not allow:
                pass
        if allow and not acted_daily and i >= 1:
            if sig_sell_h[i - 1] and sh_t > 0:
                cash += sh_t * b["open"] * (1 - COM - STAMP)
                t_budget = cash if base == 0.0 else t_budget  # 纯T模式滚动全部资金
                t_trades.append(dict(bi=cur["bi"], bp=cur["bp"], si=i, sp=b["open"],
                                     ret=(b["open"] * (1 - COM - STAMP) / (cur["bp"] * (1 + COM)) - 1) * 100,
                                     hold=i - cur["bi"], reason="1H峰顶"))
                cur = None
                sh_t = 0
            elif sig_buy_h[i - 1] and sh_t == 0:
                money = cash if base == 0.0 else min(cash, t_budget)
                lt = buy_lot(b["open"], money)
                if lt > 0:
                    cash -= lt * b["open"] * (1 + COM)
                    sh_t = lt
                    cur = dict(bi=i, bp=b["open"], sh=lt)
        equity[i] = cash + (sh_base + sh_t) * b["close"]
    if win_start is not None:
        allow_span.append((win_start, n - 1))
    if (sh_base + sh_t) > 0:
        px = h_bars[-1]["close"]
        if cur is not None and sh_t > 0:
            t_trades.append(dict(bi=cur["bi"], bp=cur["bp"], si=n - 1, sp=px,
                                 ret=(px * (1 - COM - STAMP) / (cur["bp"] * (1 + COM)) - 1) * 100,
                                 hold=n - 1 - cur["bi"], reason="期末按收盘价"))
    return dict(equity=equity, d_trades=d_trades, t_trades=t_trades,
                allow_span=allow_span, cash=cash)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("code")
    ap.add_argument("--Ld", type=int, default=30, help="日线 SSA 窗口")
    ap.add_argument("--Lh", type=int, default=20, help="1H SSA 窗口")
    ap.add_argument("--ampK", type=float, default=0.0)
    ap.add_argument("--base", type=float, default=0.0, help="窗口内底仓比例 0~1，0=纯做T")
    ap.add_argument("--dstart", default="20230101", help="日线截取起点（含预热）")
    ap.add_argument("--png", action="store_true")
    args = ap.parse_args()

    # ── 1H 层 ──
    h_bars = h1.fetch_hourly(args.code, lmt=2000)
    n = len(h_bars)
    rh = tdx.run(h_bars, L=args.Lh, strat="cycle", stop_side="none", ampK=args.ampK)

    # ── 日线层（.day 滞后时用新浪日K补齐到 1H 最新日期）──
    d_all = tdx.load_tdx(args.code)
    d_bars = [b for b in d_all if b["date"] >= args.dstart]
    if d_bars[-1]["date"] < h_bars[-1]["date"]:
        last = d_bars[-1]["date"]
        add = [b for b in h1.fetch_daily_sina(args.code) if b["date"] > last]
        d_bars = d_all + [b for b in add if b["date"] > d_all[-1]["date"]]
        d_bars = [b for b in d_bars if b["date"] >= args.dstart]
        print(f"[补齐] 通达信.day 止于 {d_all[-1]['date']}，新浪日K补 {len(add)} 根 → {d_bars[-1]['date']}")
    rd = tdx.run(d_bars, L=args.Ld, strat="cycle", stop_side="none", ampK=args.ampK)
    d_dates = [b["date"] for b in d_bars]
    d_events = {}
    for t in range(len(d_bars)):
        if t + 1 >= len(d_bars):
            break
        if rd["buySig"][t]:
            d_events[d_dates[t + 1]] = 1
        elif rd["sellSig"][t]:
            d_events[d_dates[t + 1]] = -1

    # ── 1H 层 ──
    h_bars = h1.fetch_hourly(args.code, lmt=2000)
    n = len(h_bars)
    rh = tdx.run(h_bars, L=args.Lh, strat="cycle", stop_side="none", ampK=args.ampK)

    first_bar = {}
    for i, b in enumerate(h_bars):
        first_bar.setdefault(b["date"], i)

    sim = sim_two_layer(h_bars, d_events, first_bar, rh["buySig"], rh["sellSig"], base=args.base)
    equity = sim["equity"]; d_trades = sim["d_trades"]; t_trades = sim["t_trades"]
    close_h = np.array([b["close"] for b in h_bars])

    # ── 统计 ──
    peak = CAP; mdd = 0.0
    for e in equity:
        peak = max(peak, e)
        mdd = min(mdd, (e / peak - 1) * 100)
    total = (equity[-1] / CAP - 1) * 100
    bench = (close_h[-1] / close_h[0] - 1) * 100
    wins = [t for t in t_trades if t["ret"] > 0]
    gl = sum(abs(t["ret"]) for t in t_trades if t["ret"] <= 0)
    gw = sum(t["ret"] for t in wins)
    span_txt = f"{h_bars[0]['dt']} ~ {h_bars[-1]['dt']}"
    print("=" * 92)
    print(f"双层SSA  {args.code}  日线L{args.Ld}拐点定方向 × 1H L{args.Lh}拐点做T（均无轨道）")
    print(f"区间 {span_txt}   1H共{n}根")
    print("=" * 92)
    print(f"总收益 {total:+.2f}%   最大回撤 {mdd:.2f}%   买持 {bench:+.2f}%   超额 {total - bench:+.2f}%")
    if t_trades:
        wr = len(wins) / len(t_trades) * 100
        pf = gw / gl if gl > 1e-9 else float("inf")
        ah = sum(t["hold"] for t in t_trades) / len(t_trades)
        print(f"1H T单 {len(t_trades)} 笔  胜率 {wr:.1f}%  盈亏比 {pf:.2f}  平均持仓 {ah:.1f} 根1H")
    print(f"日线事件 {len(d_trades)} 次（买{sum(1 for t in d_trades if t['side']=='B')}"
          f"/卖{sum(1 for t in d_trades if t['side']=='S')}）  持仓窗口 {len(sim['allow_span'])} 段")
    inwin = sum(i1 - i0 + 1 for i0, i1 in sim["allow_span"])
    print(f"窗口覆盖 {inwin}/{n} 根1H（{inwin / n * 100:.0f}% 时间在场内）")
    # 对照
    print("-" * 92)
    print(f"对照: 纯日线拐点(L{args.Ld}) {rd['total']:+.2f}% / 纯1H拐点(L{args.Lh}) {rh['total']:+.2f}% / 买持 {bench:+.2f}%")
    print("-" * 92)
    print("日线事件（t+1日首根1H开盘成交）:")
    for t in d_trades:
        print(f"  {'买入' if t['side'] == 'B' else '卖出'} {t['dt']}  @{t['px']:.2f}")
    print("-" * 92)
    print(f"{'#':>3} {'买入时间':>17} {'买价':>8} {'卖出时间':>17} {'卖价':>8}{'收益':>8}{'持':>4} 原因")
    for i, t in enumerate(t_trades, 1):
        print(f"{i:>3} {h_bars[t['bi']]['dt']:>17} {t['bp']:>8.2f} {h_bars[min(t['si'], n - 1)]['dt']:>17}"
              f" {t['sp']:>8.2f}{t['ret']:>+7.2f}%{t['hold']:>4} {t['reason']}")

    # ── 图 ──
    if args.png:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        plt.rcParams["font.sans-serif"] = ["SimHei", "Microsoft YaHei"]
        plt.rcParams["axes.unicode_minus"] = False
        xs = np.arange(n)
        fig, axes = plt.subplots(4, 1, figsize=(17, 17), sharex=True,
                                 gridspec_kw={"height_ratios": [3, 2, 2, 2]})
        ax = axes[0]
        ax.plot(xs, close_h, lw=0.8, color="k")
        for i0, i1 in sim["allow_span"]:
            ax.axvspan(i0, i1, color="orange", alpha=0.10)
        for t in d_trades:
            ax.scatter(t["i"], t["px"], marker="^" if t["side"] == "B" else "v",
                       color="darkgreen" if t["side"] == "B" else "darkred", s=160, zorder=6,
                       edgecolors="k", linewidths=0.8)
        for t in t_trades:
            ax.scatter(t["bi"], t["bp"], marker="^", color="limegreen", s=36, zorder=5)
            ax.scatter(t["si"], t["sp"], marker="v", color="salmon", s=36, zorder=5)
        ax.set_title(f"{args.code} 双层：橙带=日线持仓窗口  大▲▼=日线进出  小▲▼=1H做T   (日L{args.Ld}/1H L{args.Lh})")
        ax.grid(alpha=0.3)

        # 周期分量叠加（双y轴）：1H cycleAt + 日线 cycleAt（阶梯，仅用已收盘日线）
        ax2 = axes[1]
        d_cycle_by_date = {}
        for t in range(len(d_bars)):
            if not np.isnan(rd["cycleAt"][t]):
                d_cycle_by_date[d_dates[t]] = rd["cycleAt"][t]
        d_sorted = sorted(d_cycle_by_date.items())
        d_cy = np.full(n, np.nan)
        di = -1
        for i in range(n):
            # 只用前一交易日及以前的日线（盘中不用未收盘日线 → 无未来函数）
            while di + 1 < len(d_sorted) and d_sorted[di + 1][0] < h_bars[i]["date"]:
                di += 1
            if di >= 0:
                d_cy[i] = d_sorted[di][1]
        ax2.plot(xs, rh["cycleAt"], lw=0.9, color="tab:blue", label="1H cycle", alpha=0.85)
        ax2.axhline(0, color="gray", lw=0.5)
        ax2.set_ylabel("1H cycle", color="tab:blue")
        ax2b = ax2.twinx()
        ax2b.plot(xs, d_cy, lw=1.8, color="tab:red", label="日线 cycle", drawstyle="steps-post")
        ax2b.set_ylabel("日线 cycle", color="tab:red")
        for i0, i1 in sim["allow_span"]:
            ax2.axvspan(i0, i1, color="orange", alpha=0.10)
        ax2.set_title("周期分量叠加：蓝=1H cycle（左轴） 红=日线 cycle（右轴，阶梯=已收盘日线）")
        ax2.grid(alpha=0.3)

        # 日线层：价格+拐点
        ax3 = axes[2]
        d_x = np.arange(len(d_bars))
        d_close = np.array([b["close"] for b in d_bars])
        ax3.plot(d_x, d_close, lw=0.9, color="k")
        for t in range(len(d_bars)):
            if rd["buySig"][t]:
                ax3.scatter(t, d_close[t], marker="^", color="darkgreen", s=90, zorder=5)
            if rd["sellSig"][t]:
                ax3.scatter(t, d_close[t], marker="v", color="darkred", s=90, zorder=5)
        ax3.set_title(f"日线层（L{args.Ld}）：▲谷底买 ▼峰顶卖")
        ax3.grid(alpha=0.3)

        # 净值
        ax4 = axes[3]
        ax4.plot(xs, equity, lw=1.1, color="tab:red", label="双层策略")
        base = close_h[0]
        ax4.plot(xs, CAP * close_h / base, lw=0.9, color="gray", label="买持基准")
        for i0, i1 in sim["allow_span"]:
            ax4.axvspan(i0, i1, color="orange", alpha=0.10)
        ax4.set_title("净值对比")
        ax4.legend(loc="upper left", fontsize=9)
        ax4.grid(alpha=0.3)

        step = max(n // 12, 1)
        tl = [h_bars[i]["dt"] for i in range(0, n, step)]
        axes[3].set_xticks(xs[::step])
        axes[3].set_xticklabels(tl, rotation=30, ha="right", fontsize=8)
        plt.tight_layout()
        out = os.path.join("data", "review_tables",
                           f"ssa2_{args.code}_dL{args.Ld}_hL{args.Lh}_a{args.ampK:g}.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        plt.savefig(out, dpi=110)
        print("图已保存:", out)


if __name__ == "__main__":
    main()
