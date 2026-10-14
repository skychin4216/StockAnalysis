# -*- coding: utf-8 -*-
"""三状态机做T策略验证（PC 原型）。

状态划分（用户口径）：因 mid = trend + cycle，「中位线 vs 趋势线」等价于 cycle 符号。
  UP   : cycle>0（中位线在趋势线上方）且非震荡 -> 持仓(核心80%) + 极限带反向T（超上限卖30%、破下限买回）
  DOWN : cycle<0 且非震荡 -> 空仓；仅做「收盘上穿中位线 -> 触及正常上沿(mid+1.5σ)」的反弹单
  CHOP : 近 W 根 cycle 符号翻转 >= k 次 -> 中位穿越买卖（上穿买、下穿卖）

无未来函数：t 收盘决策，t+1 开盘成交。
用法: python smalltools\\_ssa_t0_regime.py 600176 [--layer D|H] [--w 12] [--k 3]
"""
import argparse
import csv
import os

import numpy as np

CAP = 100_000.0
COM, STAMP, SLIP = 0.00025, 0.0005, 0.0005
CORE_PCT = 0.8     # UP 段核心仓位
T_PCT = 0.3        # 极限带反向T 摆动比例


def load(csv_path, layer):
    rows = [r for r in csv.DictReader(open(csv_path, encoding="utf-8")) if r["layer"] == layer]
    d = {}
    for c in ("open", "high", "low", "close", "trend", "cycle", "mid", "nUp", "nDn", "eUp", "eDn"):
        d[c] = np.array([float(r[c]) for r in rows])
    d["date"] = [r["date"] for r in rows]
    return d


def regimes(d, w, k):
    """三态划分（用户口径）：
       1=UP     : cycle>0（中位线在趋势线上方）→ 核心做多，cycle 下穿 0 全仓离场
       3=WINDOW : cycle<0 且 买入窗口开着（cycle 谷底反转后）→ 中位穿越买卖
       2=FLAT   : cycle<0 且 窗口已关（cycle 峰顶回落后）→ 空仓，只做极限突破 T
       窗口开关：cycle 谷底反转(cy[i-2]>cy[i-1]<cy[i] 且 cy[i-1]<0) 开；峰顶反转(cy[i-2]<cy[i-1]>cy[i] 且 cy[i-1]>0) 关。
    """
    cy = d["mid"] - d["trend"]
    n = len(cy)
    reg = np.zeros(n, int)
    W = False
    for i in range(n):
        if i < 2 or np.isnan(cy[i]) or np.isnan(cy[i - 1]) or np.isnan(cy[i - 2]):
            continue
        if cy[i - 2] < cy[i - 1] > cy[i] and cy[i - 1] > 0:
            W = False
        if cy[i - 2] > cy[i - 1] < cy[i] and cy[i - 1] < 0:
            W = True
        reg[i] = 1 if cy[i] > 0 else (3 if W else 2)
    return reg


def stats(reg):
    from collections import Counter
    valid = reg[reg > 0]
    cnt = Counter(valid.tolist())
    tot = len(valid)
    dur = {1: [], 2: [], 3: []}
    cur, run = 0, 0
    for r in reg:
        if r == 0:
            continue
        if r == cur:
            run += 1
        else:
            if cur:
                dur[cur].append(run)
            cur, run = r, 1
    if cur:
        dur[cur].append(run)
    switches = int(np.sum(reg[1:] != reg[:-1]))
    names = {1: "UP    ", 2: "FLAT  ", 3: "WINDOW"}
    print("  状态占比/平均持续期：")
    for s in (1, 2, 3):
        if cnt[s]:
            print("    %s %5.1f%%  %d段  平均%.1f根" % (names[s], cnt[s] * 100.0 / tot,
                  len(dur[s]), np.mean(dur[s])))
    print("    状态切换次数 %d（共 %d 根，%.3f 次/根）" % (switches, tot, switches / tot))


def simulate(d, reg, xexit="mid"):
    n = len(d["close"])
    op, cl, mid = d["open"], d["close"], d["mid"]
    nUp, eUp, eDn = d["nUp"], d["eUp"], d["eDn"]
    cash, sh = CAP, 0.0
    eq = np.zeros(n)
    trades = []

    def lots(price, money):
        return int(money / (1 + COM) / (1 + SLIP) / price / 100) * 100

    def buy_to(px, target_sh):
        """补仓至 target_sh（受现金约束），返回成交股数"""
        nonlocal cash, sh
        need = int(target_sh - sh) // 100 * 100
        if need <= 0:
            return 0
        afford = lots(px, cash)
        qty = min(need, afford)
        if qty <= 0:
            return 0
        cash -= qty * px * (1 + SLIP) * (1 + COM)
        sh += qty
        return qty

    def sell_qty(px, qty):
        nonlocal cash, sh
        qty = int(min(qty, sh)) // 100 * 100
        if qty <= 0:
            return 0
        cash += qty * px * (1 - SLIP) * (1 - COM - STAMP)
        sh -= qty
        return qty

    xt = False      # FLAT 段的极限突破T仓
    for t in range(1, n - 1):
        r, r0 = reg[t], reg[t - 1]
        px = op[t + 1]
        if px <= 0 or np.isnan(mid[t]) or np.isnan(eUp[t]):
            eq[t] = cash + sh * cl[t]
            continue
        core = lots(px, (cash + sh * cl[t]) * CORE_PCT)
        cross_up = not np.isnan(mid[t - 1]) and cl[t - 1] < mid[t - 1] and cl[t] >= mid[t]
        cross_dn = not np.isnan(mid[t - 1]) and cl[t - 1] > mid[t - 1] and cl[t] <= mid[t]

        if r == 1:                                    # ── UP：核心做多 + 极限带反向T ──
            xt = False
            if sh < core:
                b = buy_to(px, core)
                if b:
                    trades.append(("UP建仓/回补", b))
            elif cl[t] > eUp[t]:
                s = sell_qty(px, sh * T_PCT)
                if s:
                    trades.append(("UP超上限卖T", -s))
            elif cl[t] < eDn[t]:
                b = buy_to(px, core)
                if b:
                    trades.append(("UP破下限买T", b))
        elif r == 3:                                  # ── WINDOW：中位穿越买卖 ──
            xt = False
            if r0 != 3 and sh > 0:                    # cycle 下穿0 进入窗口 -> 全仓离场
                s = sell_qty(px, sh)
                if s:
                    trades.append(("离开UP清仓", -s))
            elif sh == 0 and cross_up:
                b = buy_to(px, lots(px, CAP))
                if b:
                    trades.append(("WINDOW上穿中位买", b))
            elif sh > 0 and ((xexit == "nup" and cl[t] >= nUp[t]) or
                             (xexit == "mid" and cross_dn)):
                s = sell_qty(px, sh)
                if s:
                    trades.append(("WINDOW卖出", -s))
        else:                                          # ── FLAT：空仓 + 极限突破T ──
            if sh > 0 and not xt:                      # 峰顶回落 -> 清仓
                s = sell_qty(px, sh)
                if s:
                    trades.append(("FLAT清仓", -s))
            elif sh == 0 and cl[t] < eDn[t]:           # 破极限下限 -> 买
                b = buy_to(px, lots(px, CAP))
                if b:
                    trades.append(("FLAT破极限买T", b))
                    xt = True
            elif xt and sh > 0 and cl[t] > eUp[t]:     # 超极限上限 -> 卖
                s = sell_qty(px, sh)
                if s:
                    trades.append(("FLAT超极限卖T", -s))
                xt = False
        eq[t] = cash + sh * cl[t]
    eq[n - 1] = cash + sh * cl[n - 1]

    peak, mdd = eq[0], 0.0
    for v in eq:
        if peak > 0:
            mdd = min(mdd, (v - peak) / peak)
        peak = max(peak, v)
    bench = CAP * cl / cl[1]
    return eq, mdd * 100, (eq[-1] / CAP - 1) * 100, (bench[-1] / CAP - 1) * 100, trades


UP, FLAT, WINDOW = 1, 2, 3
REG_EDGE = {UP: "#E53935", WINDOW: "#43A047", FLAT: "#9E9E9E"}


def load_markers(path):
    out = []
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            out.append((int(r["index"]), float(r["price"]), int(r["buy"]) == 1, r["reason"]))
    return out


def plot(d, reg, markers, out, show=0, title="", regime=True):
    """中线/正常带/极限带 + 买卖点；regime=True 时叠加三态分区背景。"""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Patch
    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
    plt.rcParams["axes.unicode_minus"] = False

    n = len(d["close"])
    lo = max(0, n - show) if show and show > 0 else 0
    sl = slice(lo, n)
    x = np.arange(n - lo)

    def s(c):
        return d[c][sl]

    fig, ax = plt.subplots(figsize=(16, 8))
    # 三态背景分区
    if regime:
        rr = reg[sl]
        i = 0
        while i < len(rr):
            r0 = rr[i]; j = i
            while j + 1 < len(rr) and rr[j + 1] == r0:
                j += 1
            if r0 in REG_EDGE:
                ax.axvspan(x[i] - 0.5, x[j] + 0.5, color=REG_EDGE[r0], alpha=0.10, lw=0, zorder=0)
            i = j + 1

    close = s("close"); mid = s("mid")
    nUp, nDn, eUp, eDn = s("nUp"), s("nDn"), s("eUp"), s("eDn")
    valid = ~np.isnan(mid)
    ax.fill_between(x[valid], eDn[valid], eUp[valid], color="#E53935", alpha=0.05, zorder=1)
    ax.fill_between(x[valid], nDn[valid], nUp[valid], color="gray", alpha=0.15, zorder=2)
    ax.plot(x, eUp, color="#E53935", lw=0.6, ls="--", zorder=3)
    ax.plot(x, eDn, color="#E53935", lw=0.6, ls="--", zorder=3)
    ax.plot(x, nUp, color="#1976D2", lw=0.7, zorder=3)
    ax.plot(x, nDn, color="#1976D2", lw=0.7, zorder=3)
    ax.plot(x, close, color="#222", lw=0.7, zorder=4, label="收盘")
    ax.plot(x, mid, color="#FF6F00", lw=1.6, zorder=5, label="中线=trend+cycle")

    nb = ns = 0
    for idx, px, buy, _reason in markers:
        if idx < lo:
            continue
        if buy:
            ax.scatter(idx - lo, px, marker="^", s=46, color="#D32F2F",
                       edgecolors="white", linewidths=0.5, zorder=6); nb += 1
        else:
            ax.scatter(idx - lo, px, marker="v", s=46, color="#2E7D32",
                       edgecolors="white", linewidths=0.5, zorder=6); ns += 1

    handles, labels = ax.get_legend_handles_labels()
    handles += [Patch(facecolor="#D32F2F", label=f"买入({nb})"),
                Patch(facecolor="#2E7D32", label=f"卖出({ns})")]
    if regime:
        handles += [Patch(facecolor=REG_EDGE[UP], alpha=0.25, label="UP 核心做多"),
                    Patch(facecolor=REG_EDGE[WINDOW], alpha=0.25, label="WINDOW 上穿中位买/触上沿卖"),
                    Patch(facecolor=REG_EDGE[FLAT], alpha=0.25, label="FLAT 空仓·极限突破T")]
    ax.legend(handles=handles, fontsize=7.5, ncol=4, loc="upper left")

    step = max(1, len(x) // 14)
    ticks = list(range(0, len(x), step))
    ax.set_xticks(ticks)
    ax.set_xticklabels([f'{d["date"][lo + t][4:6]}/{d["date"][lo + t][6:8]}' for t in ticks],
                       fontsize=7, rotation=25)
    ax.set_title(title or "SSA 三态做T（小时线）", fontsize=11)
    ax.grid(alpha=0.15)
    ax.margins(x=0.004)
    plt.tight_layout()
    plt.savefig(out, dpi=120)
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("code")
    ap.add_argument("--csv", default="app/build/ssa_t0_layers.csv")
    ap.add_argument("--layer", default="D")
    ap.add_argument("--w", type=int, default=12)
    ap.add_argument("--k", type=int, default=3)
    ap.add_argument("--plot", action="store_true", help="输出三态分区+买卖点图")
    ap.add_argument("--show", type=int, default=0, help="图中显示最近N根(0=全部)")
    ap.add_argument("--markers", default="app/build/ssa_t0_markers.csv", help="引擎导出的买卖点CSV")
    ap.add_argument("--out", default="", help="输出图片路径")
    ap.add_argument("--no-regime", action="store_true", help="不画三态背景分区（只显示中线/区间/买卖点）")
    ap.add_argument("--title", default="", help="自定义图标题")
    a = ap.parse_args()
    d = load(a.csv, a.layer)
    reg = regimes(d, a.w, a.k)
    print("=== 三状态机验证 %s 层=%s (W=%d k=%d) ===" % (a.code, a.layer, a.w, a.k))
    stats(reg)
    eq, mdd, ret, bench, trades = simulate(d, reg)
    print("  总收益 %+.2f%%  最大回撤 %.2f%%  基准 %+.2f%%  超额 %+.2f%%" % (ret, mdd, bench, ret - bench))
    print("  交易 %d 笔" % len(trades))
    from collections import Counter
    c = Counter(t[0] for t in trades)
    print("  " + " | ".join("%s×%d" % (kk, vv) for kk, vv in c.most_common()))

    if a.plot:
        outdir = os.path.join("data", "review_tables")
        os.makedirs(outdir, exist_ok=True)
        out = a.out or os.path.join(outdir, "ssa_t0_regime_%s_%s.png" % (a.code, a.layer))
        mk = load_markers(a.markers)
        plot(d, reg, mk, out, a.show,
             title=a.title or "%s SSA三态做T（层=%s）三态分区 + 中线/区间 + 买卖点" % (a.code, a.layer),
             regime=not a.no_regime)
        print("  图已保存: %s（买卖点 %d 个）" % (out, len(mk)))


if __name__ == "__main__":
    main()
