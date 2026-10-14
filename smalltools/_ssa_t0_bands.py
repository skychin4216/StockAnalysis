# -*- coding: utf-8 -*-
"""两层 SSA 稳态做T —— 动态扰动区间可视化（PC 端）。

中线口径（本次修正）：
    中线 = trend + cycle  （滚动 SSA 末端值之和，价格尺度、平滑跟随趋势，即该段趋势的中位价）
    —— 以前用「前一根收盘」当中线会逐K跳动、触轨信号虚多。
扰动空间（cycle 已并入中线，故激进带只由残差噪声构成，避免把周期波动算两遍）：
    激进区间 = 中线 ± noise振幅
    保守区间 = 中线 ± (noise振幅 + cycle振幅)   # 外包围与旧版一致，只是重新居中

数据来源：App 同款 SsaT0Engine 导出的 CSV（app/src/test 的 SsaT0PcBacktest 生成）。

产出：一张图，上面板=日线层，下面板=小时线层（已去掉周线层）。
用法:
  python smalltools/_ssa_t0_bands.py 600176 [--csv path] [--d 600] [--h 0]
"""
import argparse
import csv
import os

import numpy as np

SCN_A, SCN_B, SCN_C = 1, 2, 3
RED, GREEN = "#E53935", "#43A047"
BLUE, ORANGE, TEAL = "#1976D2", "#FF6F00", "#00897B"


def load(csv_path):
    layers = {"W": [], "D": [], "H": []}
    with open(csv_path, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            layers[row["layer"]].append(row)
    out = {}
    for k, rows in layers.items():
        if not rows:
            continue
        d = {}
        for col in ("open", "high", "low", "close", "trend", "cycle", "mid",
                    "nUp", "nDn", "eUp", "eDn"):
            d[col] = np.array([float(r[col]) for r in rows], dtype=float)
        d["scn"] = np.array([int(r["scn"]) for r in rows], dtype=int)
        d["date"] = [r["date"] for r in rows]
        d["time"] = [r["time"] for r in rows]
        out[k] = d
    return out


def candles(ax, x, o, h, l, c):
    for i in range(len(x)):
        col = RED if c[i] >= o[i] else GREEN
        ax.vlines(x[i], l[i], h[i], color=col, lw=0.6)
        ax.vlines(x[i], min(o[i], c[i]), max(o[i], c[i]), color=col, lw=2.0)


def roll_std(v, n, minp=5):
    """滚动标准差（只用 [i-n+1, i] 历史，无未来函数）。"""
    out = np.full(len(v), np.nan)
    for i in range(len(v)):
        lo = max(0, i - n + 1)
        seg = v[lo:i + 1]
        seg = seg[~np.isnan(seg)]
        if len(seg) >= minp:
            out[i] = float(seg.std(ddof=1))
    return out


def draw_panel(ax, d, n_show, title, use_candle=True, lookback=20):
    n = len(d["close"])
    lo = max(0, n - n_show)
    sl = slice(lo, n)
    x = np.arange(n - lo)

    def s(col):
        return d[col][sl]

    close = s("close")
    # 中线与区间直接取引擎输出（引擎已按无未来函数口径生成）
    mid2 = s("mid")                       # 中线 = trend + cycle（价格尺度平滑线）
    aggUp, aggDn = s("nUp"), s("nDn")     # 正常区间 = 中线 ± 1.5σ（触轨做T阈值）
    consUp, consDn = s("eUp"), s("eDn")   # 极限区间 = 中线 ± 2.0σ（止损阈值）
    valid = ~np.isnan(mid2)

    # 扰动空间（避免逐根着色造成密集噪声，只画带与中线）
    ax.fill_between(x[valid], consDn[valid], consUp[valid],
                    color=RED, alpha=0.05, zorder=0, label="极限区间 = 中线±2.0σ")
    ax.fill_between(x[valid], aggDn[valid], aggUp[valid],
                    color="gray", alpha=0.18, zorder=1, label="正常区间 = 中线±1.5σ")
    ax.plot(x, consUp, color=RED, lw=0.7, ls="--", zorder=2, label="极限上下沿(2.0σ)")
    ax.plot(x, consDn, color=RED, lw=0.7, ls="--", zorder=2)
    ax.plot(x, aggUp, color=BLUE, lw=0.8, zorder=2, label="正常上下沿(1.5σ)")
    ax.plot(x, aggDn, color=BLUE, lw=0.8, zorder=2)
    ax.plot(x, mid2, color=ORANGE, lw=1.8, zorder=3, label="中线=SSA趋势+周期分量(中位价)")

    if use_candle:
        candles(ax, x, s("open"), s("high"), s("low"), close)
        ax.plot([], [], color=RED, lw=2, label="阳线")
        ax.plot([], [], color=GREEN, lw=2, label="阴线")
    else:
        ax.plot(x, close, color="#222", lw=0.9, label="收盘")
    ax.plot(x, s("trend"), color=TEAL, lw=0.9, alpha=0.8, label="SSA趋势分量(单看)")

    step = max(1, len(x) // 12)
    ticks = list(range(0, len(x), step))
    ax.set_xticks(ticks)
    ax.set_xticklabels([d["date"][lo + t][2:8] for t in ticks], fontsize=7)
    ax.set_title(title, fontsize=10)
    ax.legend(fontsize=6.5, ncol=4, loc="upper left")
    ax.grid(alpha=0.15)
    ax.margins(x=0.005)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("code")
    ap.add_argument("--csv", default="app/build/ssa_t0_layers.csv")
    ap.add_argument("--d", type=int, default=600, help="日线显示根数")
    ap.add_argument("--h", type=int, default=0, help="小时线显示根数(0=全部)")
    ap.add_argument("--out", default="", help="输出图片路径(默认 data/review_tables/ssa_t0_bands_{code}_DH.png)")
    args = ap.parse_args()

    d = load(args.csv)
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
    plt.rcParams["axes.unicode_minus"] = False

    outdir = os.path.join("data", "review_tables")
    os.makedirs(outdir, exist_ok=True)

    fig, axes = plt.subplots(2, 1, figsize=(14, 10))
    draw_panel(axes[0], d["D"], args.d,
               f"{args.code} 日线层：K线 + 中线(trend+cycle) + 扰动空间 + A/B/C", True)
    draw_panel(axes[1], d["H"], args.h if args.h > 0 else len(d["H"]["close"]),
               f"{args.code} 小时线层：收盘 + 中线(trend+cycle) + 扰动空间 + A/B/C", False)
    plt.tight_layout()
    out = args.out or os.path.join(outdir, f"ssa_t0_bands_{args.code}_DH.png")
    plt.savefig(out, dpi=115); plt.close(fig)
    print(f"图已保存: {out}")


if __name__ == "__main__":
    main()