# -*- coding: utf-8 -*-
"""
SSA mid 拐点策略 · 前视偏差检验 + 参数扫描
============================================
背景：_bbi_er_vs_ssa.py 里 SSA 对**整条序列**一次性 SVD，t 时刻的 mid=trend+cycle
      用到了 t 之后的未来数据（对角线平均把未来窗口混进当前重构）→ 前视偏差，
      +471% 的收益是假的。本脚本：

1) 因果版 SSA：每个 t 只用 [t-W+1, t] 滚动窗口做 SVD，取窗口右端 mid 值。
   —— 右端对角线平均只有唯一贡献项 Y[L-1,K-1]，直接用闭式取，无需重构全窗。
2) 对比：非因果(全序列) vs 因果(滚动 W=4L) vs BBI12+ER≥70 基线。
3) 参数扫描 L=20/30/40（非因果 + 因果两版，验证假象是否随 L 保持）。
数据：复用 _kalman_1h_cache.json（1H）。
"""
import json, os, sys, base64
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
REPORT = os.path.join(ROOT, "reports", "ssa_causal_check.html")
FEE = 0.0013
ER_W = 60


# ── SSA 全序列（非因果）──────────────────────────────────────────────────────
def ssa_mid_full(x, L, nr=3):
    """整条序列一次性 SVD + 对角线平均，取前 nr 个分量之和（=trend+cycle）。非因果。"""
    z = np.asarray(x, float)
    N = len(z)
    K = N - L + 1
    X = np.lib.stride_tricks.sliding_window_view(z, L).T.copy()  # L×K
    G = X @ X.T
    eigvals, eigvecs = np.linalg.eigh(G)
    idx = np.argsort(eigvals)[::-1]
    V = eigvecs[:, idx]
    Y = np.zeros((L, K))
    for r in range(min(nr, L)):
        v = V[:, r]
        Y += np.outer(v, X.T @ v)
    # 快速对角线平均：out[i:i+K] += Y[i]
    out = np.zeros(N)
    cnt = np.zeros(N)
    for i in range(L):
        out[i:i + K] += Y[i]
        cnt[i:i + K] += 1
    cnt[cnt == 0] = 1
    return out / cnt


# ── SSA 滚动窗口（因果）──────────────────────────────────────────────────────
def ssa_mid_causal(x, L, W, nr=3):
    """每个 t 只用 [t-W+1, t] 做 SVD，取窗口右端 mid 值。右端对角平均仅 Y[L-1,K-1]
    一项 → 闭式：mid = Σ_r v_r[L-1] · (win[-L:] · v_r)。无未来数据。"""
    z = np.asarray(x, float)
    N = len(z)
    mid = np.full(N, np.nan)
    if W < L + 1 or W > N:
        W = min(N, max(L + 1, W))
    if W > N:
        return mid
    K = W - L + 1
    for t in range(W - 1, N):
        win = z[t - W + 1:t + 1]
        X = np.lib.stride_tricks.sliding_window_view(win, L).T  # L×K
        G = X @ X.T
        eigvals, eigvecs = np.linalg.eigh(G)
        idx = np.argsort(eigvals)[::-1]
        V = eigvecs[:, idx]
        lastcol = win[-L:]  # = X[:, K-1]
        val = 0.0
        for r in range(min(nr, L)):
            v = V[:, r]
            val += v[L - 1] * float(lastcol @ v)
        mid[t] = val
    return mid


# ── ER / BBI12 / 拐点（与主脚本同口径）────────────────────────────────────────
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
    ok = np.isfinite(m3) & np.isfinite(m6) & np.isfinite(m12) & np.isfinite(m24)
    bbi = np.where(ok, (m3 + m6 + m12 + m24) / 4.0, np.nan)
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
    z = np.asarray(closes, float)
    n = len(z)
    pv_at = dict(pivots(s))
    pos = False
    n_tr = win = 0
    nav_i = nav_buy = 1.0
    nav = np.ones(n)
    for t in range(n):
        if t > 0 and pos:
            nav_i *= z[t] / z[t - 1]
        if t in pv_at:
            is_b = pv_at[t]
            if er_pct is not None and t < len(er_pct):
                if not np.isfinite(er_pct[t]) or er_pct[t] < er_gate:
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


def fig_b64(fig):
    buf = BytesIO()
    fig.savefig(buf, format="png", dpi=110, bbox_inches="tight")
    plt.close(fig)
    return base64.b64encode(buf.getvalue()).decode()


def s(v):
    a = np.array(v)
    return f"{a.mean() * 100:+.1f}% / 中位 {np.median(a) * 100:+.1f}%"


def main():
    cache = json.load(open(CACHE, encoding="utf-8"))
    codes = [c for c in cache if len(cache[c].get("c", [])) > 300]
    print(f"[data] {len(codes)} 只", file=sys.stderr)

    all_closes = {c: cache[c]["c"] for c in codes}

    # ── ER 横截面分位（供 BBI12+ER 基线）──
    all_er = {c: er_series(all_closes[c]) for c in codes}
    n_bars = min(len(all_closes[c]) for c in codes)
    er_pct = {c: np.full(n_bars, np.nan) for c in codes}
    for t in range(ER_W, n_bars):
        ers, valid = [], []
        for c in codes:
            e = all_er[c][t] if t < len(all_er[c]) else np.nan
            if np.isfinite(e):
                ers.append(e); valid.append(c)
        if not ers:
            continue
        arr = np.array(ers)
        for c, e in zip(valid, ers):
            er_pct[c][t] = float((arr < e).sum() / len(arr) * 100)

    L_MAIN = 30
    W_MAIN = 4 * L_MAIN
    res_bbi_er, res_full, res_causal = [], [], []
    detail = []
    for i, c6 in enumerate(codes, 1):
        closes = all_closes[c6]
        _, _, _, t_er = run_strategy(closes, bbi12_line(closes),
                                     er_pct=er_pct[c6], er_gate=70)
        _, ntr_f, wr_f, t_full = run_strategy(closes, ssa_mid_full(closes, L_MAIN))
        _, ntr_c, wr_c, t_caus = run_strategy(closes, ssa_mid_causal(closes, L_MAIN, W_MAIN))
        res_bbi_er.append(t_er); res_full.append(t_full); res_causal.append(t_caus)
        detail.append((c6, t_er, t_full, t_caus, ntr_f, ntr_c, wr_c))
        if i % 40 == 0:
            print(f"[bt-main] {i}/{len(codes)}", file=sys.stderr)

    # ── L 扫描 ──
    scan = []
    for L in (20, 30, 40):
        W = 4 * L
        vf, vc = [], []
        for c6 in codes:
            closes = all_closes[c6]
            _, _, _, tf = run_strategy(closes, ssa_mid_full(closes, L))
            _, _, _, tc = run_strategy(closes, ssa_mid_causal(closes, L, W))
            vf.append(tf); vc.append(tc)
        scan.append((L, W, s(vf), s(vc)))
        print(f"[scan] L={L} W={W} done", file=sys.stderr)

    # ── 600176 案例图 ──
    case_img = ""
    c6 = "600176"
    if c6 in all_closes:
        closes = all_closes[c6]
        mf = ssa_mid_full(closes, L_MAIN)
        mc = ssa_mid_causal(closes, L_MAIN, W_MAIN)
        bb = bbi12_line(closes)
        tail = 200
        z = np.asarray(closes, float)[-tail:]
        xs = np.arange(len(z))
        fig, ax = plt.subplots(figsize=(12, 5))
        ax.plot(xs, z, color="#999", lw=0.8, label="收盘(1H)")
        ax.plot(xs, bb[-tail:], color="#1565C0", lw=1.0, ls="--", label="BBI12")
        ax.plot(xs, mf[-tail:], color="#E65100", lw=1.3, label="SSA mid 非因果(全序列)")
        ax.plot(xs, mc[-tail:], color="#43A047", lw=1.1, label="SSA mid 因果(滚动 W=120)")
        ax.axvline(tail - W_MAIN, color="#bbb", lw=0.8, ls=":")
        ax.legend(fontsize=8)
        ax.set_title(f"600176 · 非因果 vs 因果 SSA mid（最近 {tail} 根 1H）", fontsize=11)
        ax.grid(alpha=0.2)
        case_img = fig_b64(fig)

    det_rows = "".join(
        f"<tr><td>{c}</td><td class='{('pos' if r1>0 else 'neg')}'>{r1*100:+.1f}%</td>"
        f"<td class='{('pos' if r2>0 else 'neg')}'>{r2*100:+.1f}%</td>"
        f"<td class='{('pos' if r3>0 else 'neg')}'>{r3*100:+.1f}%</td>"
        f"<td>{nf}</td><td>{nc}</td><td>{wc*100:+.1f}%</td></tr>"
        for c, r1, r2, r3, nf, nc, wc in detail)

    scan_rows = "".join(
        f"<tr><td>L={L} (W={W})</td><td>{f}</td><td>{c}</td></tr>"
        for L, W, f, c in scan)

    # 因果衰减幅度
    m_full = np.mean(res_full) * 100
    m_caus = np.mean(res_causal) * 100
    m_er = np.mean(res_bbi_er) * 100
    verdict = ("<b style='color:#C62828'>因果版收益大幅坍塌 → 确认前视偏差是 +471% 的来源</b>"
               if (m_full - m_caus) > 100 else
               "因果版与非因果版接近 → 前视偏差影响有限")

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>SSA 前视偏差检验 + L 扫描</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1100px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #1565C0;padding-left:8px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0}}
</style></head><body>
<h1>SSA mid 拐点策略 · 前视偏差检验 + 参数扫描（1H）</h1>
<div class="box note">口径：1H，{len(detail)} 只，费 0.13%，long-only，mid=trend+cycle 拐点（谷底买/峰顶卖）。
<b>非因果</b>=整条序列一次性 SVD（原 _bbi_er_vs_ssa.py 口径）；
<b>因果</b>=每个 t 只用 [t-W+1,t] 滚动窗口 SVD，取窗口右端 mid 值（无未来数据）。</div>

<h2>① 结论</h2>
<div class="box">{verdict}<br>
非因果均值 <b>{m_full:+.1f}%</b> → 因果均值 <b>{m_caus:+.1f}%</b>（坍塌 {m_full-m_caus:.1f} 个百分点）；
BBI12+ER≥70 基线均值 <b>{m_er:+.1f}%</b>。</div>

<h2>② 三策略汇总（L=30, W=120）</h2>
<table><tr><th>策略</th><th>总收益（均值/中位）</th></tr>
<tr><td>BBI12 + ER≥70</td><td>{s(res_bbi_er)}</td></tr>
<tr><td>SSA mid 非因果（全序列）</td><td>{s(res_full)}</td></tr>
<tr><td>SSA mid 因果（滚动 W=120）</td><td>{s(res_causal)}</td></tr></table>

<h2>③ 参数扫描 L=20/30/40（非因果 vs 因果）</h2>
<table><tr><th>L / 窗口</th><th>非因果（均值/中位）</th><th>因果（均值/中位）</th></tr>
{scan_rows}</table>

<h2>④ 600176 案例（非因果 mid 明显贴合未来走势，因果 mid 滞后）</h2>
<img src="data:image/png;base64,{case_img}"/>

<h2>⑤ 全池明细</h2>
<table><tr><th>代码</th><th>BBI12+ER</th><th>SSA非因果</th><th>SSA因果</th><th>非因果交易数</th><th>因果交易数</th><th>因果胜率</th></tr>
{det_rows}</table>
</body></html>"""
    open(REPORT, "w", encoding="utf-8").write(html)
    print(f"[done] → {REPORT}", file=sys.stderr)
    print(f"VERDICT full={s(res_full)} causal={s(res_causal)} bbi_er={s(res_bbi_er)}", file=sys.stderr)
    for L, W, f, c in scan:
        print(f"SCAN L={L}: noncausal {f} | causal {c}", file=sys.stderr)


if __name__ == "__main__":
    main()
