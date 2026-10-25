# -*- coding: utf-8 -*-
"""
BBI12+ER 分位过滤 vs SSA T0 策略 · 1H 对比回测
==================================================
A. BBI12+ER过滤：BBI12 拐点策略，仅当 ER 分位 ≥ 70（趋势段）时才执行交易
B. SSA T0 简化版：SSA 分解（L=30，分量0=趋势，分量1+2=周期），
   mid = trend + cycle，cycle>0 持有，cycle<0 空仓（regime 口径）
对比：同口径（1H，171 只，费 0.13%，long-only）。
数据：复用 _kalman_1h_cache.json。
"""
import json, math, os, sys, base64
from io import BytesIO
import numpy as np
import matplotlib
matplotlib.use("Agg")
matplotlib.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
matplotlib.rcParams["axes.unicode_minus"] = False
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CACHE = os.path.join(HERE, "data", "_kalman_1h_cache.json")
REPORT = os.path.join(ROOT, "reports", "bbi_er_vs_ssa_t0.html")
FEE = 0.0013
ER_W = 60
SSA_L = 30


# ── SSA 分解 ────────────────────────────────────────────────────────────────
def ssa_decompose(x, L=SSA_L):
    """SSA：Hankel → Gram → eigendecomp → 分组重构（trend/cycle/noise）"""
    z = np.asarray(x, float)
    N = len(z)
    K = N - L + 1
    if K < 1:
        return z.copy(), np.zeros(N), np.zeros(N)
    # Hankel trajectory matrix (L×K)
    X = np.lib.stride_tricks.sliding_window_view(z, L).T.copy()
    # Gram matrix (L×L)
    G = X @ X.T
    eigvals, eigvecs = np.linalg.eigh(G)
    idx = np.argsort(eigvals)[::-1]
    sigma = np.sqrt(np.maximum(eigvals[idx], 0))
    V = eigvecs[:, idx]  # L×L, columns sorted by eigenvalue desc

    def reconstruct(group_cols):
        """对指定分量组做秩1重构 + 对角平均"""
        Y = np.zeros((L, K))
        for r in group_cols:
            if r >= L or sigma[r] < 1e-12:
                continue
            v = V[:, r]  # L-vector
            a = X.T @ v  # K-vector (projection)
            Y += np.outer(v, a)  # L×K rank-1
        # Diagonal averaging (Hankelization)
        out = np.zeros(N)
        cnt = np.zeros(N)
        for i in range(L):
            for j in range(K):
                out[i + j] += Y[i, j]
                cnt[i + j] += 1
        cnt[cnt == 0] = 1
        return out / cnt

    trend = reconstruct([0])
    cycle = reconstruct([1, 2]) if L >= 3 else np.zeros(N)
    noise = z - trend - cycle
    return trend, cycle, noise


# ── ER / BBI12 / 拐点 ──────────────────────────────────────────────────────
def er_series(closes, w=ER_W):
    z = np.asarray(closes, float)
    n = len(z)
    er = np.full(n, np.nan)
    for i in range(w, n):
        win = z[i - w:i + 1]
        net = abs(win[-1] - win[0])
        path = np.sum(np.abs(np.diff(win)))
        er[i] = net / path if path > 0 else 0.0
    return er


def sma_series(a, n):
    out = np.full(len(a), np.nan)
    for i in range(n - 1, len(a)):
        w = a[i - n + 1:i + 1]
        if np.all(np.isfinite(w)):
            out[i] = w.mean()
    return out


def bbi12_line(closes):
    z = np.asarray(closes, float)
    m3, m6 = sma_series(z, 3), sma_series(z, 6)
    m12, m24 = sma_series(z, 12), sma_series(z, 24)
    bbi = np.where(np.isfinite(m3) & np.isfinite(m6) & np.isfinite(m12) & np.isfinite(m24),
                   (m3 + m6 + m12 + m24) / 4.0, np.nan)
    return sma_series(bbi, 12)


def pivots(s):
    out = []
    for i in range(1, len(s) - 1):
        a, b, c = s[i - 1], s[i], s[i + 1]
        if not all(np.isfinite([a, b, c])):
            continue
        if b <= a and c > b:
            out.append((i + 1, True))
        elif b >= a and c < b:
            out.append((i + 1, False))
    return out


def run_strategy(closes, s, er_pct=None, er_gate=70):
    """BBI12 拐点策略，可选 ER 分位过滤（er_pct: 逐根分位数组）"""
    z = np.asarray(closes, float)
    n = len(z)
    nav = np.ones(n)
    pv_at = dict(pivots(s))
    pos = False
    n_tr = win = 0
    nav_i = 1.0
    nav_buy = 1.0
    for t in range(n):
        if t > 0 and pos:
            nav_i *= z[t] / z[t - 1]
        if t in pv_at:
            is_b = pv_at[t]
            # ER 分位过滤：只在趋势段交易
            if er_pct is not None and t < len(er_pct):
                if not np.isfinite(er_pct[t]) or er_pct[t] < er_gate:
                    # 非趋势段：如果持仓则卖出
                    if pos:
                        nav_i *= (1 - FEE) / (1 + FEE)
                        n_tr += 1
                        win += nav_i / nav_buy > 1
                        pos = False
                    nav[t] = nav_i
                    continue
            if is_b and not pos:
                pos, nav_buy = True, nav_i
            elif (not is_b) and pos:
                nav_i *= (1 - FEE) / (1 + FEE)
                n_tr += 1
                win += nav_i / nav_buy > 1
                pos = False
        nav[t] = nav_i
    return nav, n_tr, (win / n_tr if n_tr else None), nav[-1] - 1


def run_ssa_t0(closes, trend, cycle):
    """SSA T0 mid 拐点策略：mid=trend+cycle，谷底拐点买入，峰顶拐点卖出（与 BBI12 同口径）"""
    mid = trend + cycle
    return run_strategy(closes, mid)


def fmt(v):
    if v is None:
        return "—"
    return f"{v * 100:+.1f}%"


def fig_b64(fig):
    buf = BytesIO()
    fig.savefig(buf, format="png", dpi=110, bbox_inches="tight")
    plt.close(fig)
    return base64.b64encode(buf.getvalue()).decode()


def main():
    cache = json.load(open(CACHE, encoding="utf-8"))
    codes = [c for c in cache if len(cache[c].get("c", [])) > 300]
    print(f"[data] {len(codes)} 只", file=sys.stderr)

    # ── 预计算 ER ──
    all_er = {}
    all_closes = {}
    for c6 in codes:
        closes = cache[c6]["c"]
        all_closes[c6] = closes
        all_er[c6] = er_series(closes)

    # 横截面分位
    n_bars = min(len(all_closes[c]) for c in codes)
    er_pct = {}  # code -> array of percentile
    for c6 in codes:
        er_pct[c6] = np.full(n_bars, np.nan)
    for t in range(ER_W, n_bars):
        ers = []
        valid = []
        for c in codes:
            e = all_er[c][t] if t < len(all_er[c]) else np.nan
            if np.isfinite(e):
                ers.append(e)
                valid.append(c)
        if not ers:
            continue
        arr = np.array(ers)
        for c, e in zip(valid, ers):
            er_pct[c][t] = float((arr < e).sum() / len(arr) * 100)

    # ── 回测 ──
    res_bbi_raw = []   # BBI12 无过滤
    res_bbi_er = []    # BBI12 + ER≥70
    res_ssa = []       # SSA T0
    detail = []

    for idx, c6 in enumerate(codes, 1):
        closes = all_closes[c6]
        # A. BBI12 无过滤
        bb = bbi12_line(closes)
        _, _, _, tot_raw = run_strategy(closes, bb)
        res_bbi_raw.append(tot_raw)
        # B. BBI12 + ER≥70
        _, _, _, tot_er = run_strategy(closes, bb, er_pct=er_pct[c6], er_gate=70)
        res_bbi_er.append(tot_er)
        # C. SSA T0
        trend, cycle, noise = ssa_decompose(closes)
        _, ntr_ssa, wr_ssa, tot_ssa = run_ssa_t0(closes, trend, cycle)
        res_ssa.append(tot_ssa)
        detail.append((c6, tot_raw, tot_er, tot_ssa, ntr_ssa, wr_ssa))
        if idx % 40 == 0:
            print(f"[bt] {idx}/{len(codes)}", file=sys.stderr)

    # ── 600176 案例图 ──
    case_img = ""
    c6 = "600176"
    if c6 in all_closes:
        closes = all_closes[c6]
        trend, cycle, _ = ssa_decompose(closes)
        bb = bbi12_line(closes)
        tail = 200
        z = np.asarray(closes, float)[-tail:]
        xs = np.arange(len(z))
        fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(12, 7), sharex=True,
                                        gridspec_kw={"height_ratios": [3, 1]})
        ax1.plot(xs, z, color="#999", lw=0.8, label="收盘(1H)")
        ax1.plot(xs, bb[-tail:], color="#1565C0", lw=1.0, ls="--", label="BBI12")
        ax1.plot(xs, trend[-tail:], color="#E65100", lw=1.2, label="SSA trend")
        ax1.plot(xs, (trend + cycle)[-tail:], color="#43A047", lw=1.0, label="SSA mid(trend+cycle)")
        ax1.legend(fontsize=8)
        ax1.set_title(f"600176 · BBI12 vs SSA（最近 {tail} 根 1H）", fontsize=11)
        ax1.grid(alpha=0.2)
        ax2.plot(xs, cycle[-tail:], color="#7B1FA2", lw=0.9, label="SSA cycle")
        ax2.axhline(0, color="#666", lw=0.5)
        ax2.fill_between(xs, 0, cycle[-tail:], where=cycle[-tail:] > 0, alpha=0.2, color="red")
        ax2.fill_between(xs, 0, cycle[-tail:], where=cycle[-tail:] < 0, alpha=0.2, color="green")
        ax2.legend(fontsize=8)
        ax2.grid(alpha=0.2)
        case_img = fig_b64(fig)

    # ── 汇总 ──
    def s(v):
        a = np.array(v)
        return f"{a.mean() * 100:+.1f}% / 中位 {np.median(a) * 100:+.1f}%"

    det_rows = "".join(
        f"<tr><td>{c6}</td><td class='{('pos' if r1 > 0 else 'neg')}'>{fmt(r1)}</td>"
        f"<td class='{('pos' if r2 > 0 else 'neg')}'>{fmt(r2)}</td>"
        f"<td class='{('pos' if r3 > 0 else 'neg')}'>{fmt(r3)}</td>"
        f"<td>{ntr}</td><td>{fmt(wr)}</td></tr>"
        for c6, r1, r2, r3, ntr, wr in detail)

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>BBI12+ER vs SSA T0 · 1H 对比</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1100px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #1565C0;padding-left:8px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0}}
</style></head><body>
<h1>BBI12+ER 分位过滤 vs SSA T0 策略 · 1H 对比</h1>
<div class="box note">三种策略同口径对比（1H，171 只，费 0.13%，long-only）：
A=BBI12 拐点无过滤；B=BBI12 拐点 + ER≥70 过滤（只在趋势段交易）；
C=SSA mid 拐点（SSA 分解 L=30，mid=trend+cycle，谷底买入峰顶卖出，与 BBI12 同口径）。</div>

<h2>① 全池汇总（{len(detail)} 只）</h2>
<table><tr><th>策略</th><th>总收益（均值/中位）</th></tr>
<tr><td>A. BBI12 无过滤</td><td>{s(res_bbi_raw)}</td></tr>
<tr><td>B. BBI12 + ER≥70</td><td>{s(res_bbi_er)}</td></tr>
<tr><td>C. SSA T0</td><td>{s(res_ssa)}</td></tr></table>

<h2>② 600176 案例</h2>
<img src="data:image/png;base64,{case_img}"/>

<h2>③ 全池明细</h2>
<table><tr><th>代码</th><th>BBI12 无过滤</th><th>BBI12+ER≥70</th><th>SSA T0</th><th>SSA 交易数</th><th>SSA 胜率</th></tr>
{det_rows}</table>
</body></html>"""
    open(REPORT, "w", encoding="utf-8").write(html)
    print(f"[done] → {REPORT}", file=sys.stderr)
    print(f"POOL bbi_raw={s(res_bbi_raw)} bbi_er={s(res_bbi_er)} ssa={s(res_ssa)}", file=sys.stderr)


if __name__ == "__main__":
    main()
