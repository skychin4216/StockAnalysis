# -*- coding: utf-8 -*-
"""
Holt-Winters 双指数平滑（含趋势项）拐点策略回测 · 1H 口径
============================================================
Holt's linear trend method（双指数平滑 + 趋势）：
  Level:  L_t = α·z_t + (1−α)·(L_{t−1} + T_{t−1})
  Trend:  T_t = β·(L_t − L_{t−1}) + (1−β)·T_{t−1}
  Smooth: S_t = L_t  （用 Level 做拐点判断）
对比：Holt-Winters 各参数 vs BBI12 基线。
数据复用 _kalman_1h_cache.json（新浪 1H，1023 根）。
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
REPORT = os.path.join(ROOT, "reports", "holt_winters_1h_report.html")
FEE = 0.0013
EFF_H, EFF_THR = 20, 0.03

ALPHAS = [0.1, 0.2, 0.3, 0.5, 0.8]
BETAS = [0.01, 0.05, 0.1, 0.2, 0.3]


def holt_winters(closes, alpha, beta):
    z = np.asarray(closes, float)
    n = len(z)
    L = np.empty(n)
    T = np.empty(n)
    L[0] = z[0]
    T[0] = z[1] - z[0] if n > 1 else 0.0
    for t in range(1, n):
        L[t] = alpha * z[t] + (1 - alpha) * (L[t - 1] + T[t - 1])
        T[t] = beta * (L[t] - L[t - 1]) + (1 - beta) * T[t - 1]
    return L  # Level 序列作为平滑曲线


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


def eff_valley(s, closes, h=EFF_H, thr=EFF_THR):
    pv = [(i, b) for i, b in pivots(s) if b and i + h < len(closes)]
    if not pv:
        return None, 0
    hit = sum(1 for i, _ in pv if max(closes[i:i + h + 1]) / closes[i] - 1 >= thr)
    return hit / len(pv), len(pv)


def run_strategy(closes, s):
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
            if is_b and not pos:
                pos, nav_buy = True, nav_i
            elif (not is_b) and pos:
                nav_i *= (1 - FEE) / (1 + FEE)
                n_tr += 1
                win += nav_i / nav_buy > 1
                pos = False
        nav[t] = nav_i
    return nav, n_tr, (win / n_tr if n_tr else None), nav[-1] - 1


def sharpe_mdd(nav):
    r = np.diff(nav) / nav[:-1]
    sd = r.std()
    shp = float(r.mean() / sd * math.sqrt(244 * 6)) if sd > 0 else 0.0
    dd = (nav / np.maximum.accumulate(nav) - 1).min()
    return shp, float(dd)


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
    print(f"[data] {len(codes)} 只 1H 数据", file=sys.stderr)

    # ── 600176 案例：最佳参数 ──
    case_img = ""
    case_html = ""
    c6 = "600176"
    if c6 in cache:
        closes = cache[c6]["c"]
        # 参数扫描找最优
        best = None
        for a in ALPHAS:
            for b in BETAS:
                s = holt_winters(closes, a, b)
                _, _, _, tot = run_strategy(closes, s)
                if best is None or tot > best[2]:
                    best = (a, b, tot)
        ba, bb, _ = best
        s_best = holt_winters(closes, ba, bb)
        pv = pivots(s_best)
        # 画图
        tail = 200
        z = np.asarray(closes, float)[-tail:]
        ss = s_best[-tail:]
        xs = np.arange(len(z))
        fig, ax = plt.subplots(figsize=(12, 4.2))
        ax.plot(xs, z, color="#999", lw=0.8, label="收盘(1H)")
        ax.plot(xs, ss, color="#1565C0", lw=1.4, label=f"Holt-Winters (α={ba}, β={bb})")
        off = len(closes) - tail
        for i, is_b in pv:
            x = i - off
            if 0 <= x < len(z):
                ax.scatter([x], [z[x]], color=("#E53935" if is_b else "#43A047"),
                           s=28, zorder=5, marker=("o" if is_b else "^"),
                           label=("谷底" if is_b else "峰顶"))
        h, l = ax.get_legend_handles_labels()
        ax.legend(dict(zip(l, h)).values(), dict(zip(l, h)).keys(), fontsize=8)
        ax.set_title(f"600176 中国船舶 · Holt-Winters 拐点（最近 {tail} 根 1H）", fontsize=11)
        ax.grid(alpha=0.2)
        case_img = fig_b64(fig)

    # ── 全池回测 ──
    results = {}  # (alpha,beta) -> list of totals
    for a in ALPHAS:
        for b in BETAS:
            results[(a, b)] = []
    bbi_tots = []
    detail = []
    for idx, c6 in enumerate(codes, 1):
        closes = cache[c6]["c"]
        if idx % 20 == 0:
            print(f"[bt] {idx}/{len(codes)}", file=sys.stderr)
        # BBI12 基线
        bb_line = bbi12_line(closes)
        _, _, _, bbi_tot = run_strategy(closes, bb_line)
        bbi_tots.append(bbi_tot)
        # Holt-Winters 参数扫描
        best_hw = None
        for a in ALPHAS:
            for b in BETAS:
                s = holt_winters(closes, a, b)
                _, _, _, tot = run_strategy(closes, s)
                results[(a, b)].append(tot)
                if best_hw is None or tot > best_hw[2]:
                    best_hw = (a, b, tot)
        # 用全局最优参数跑明细（先扫描完再选）
        detail.append((c6, best_hw[2], bbi_tot))

    # 全局最优参数
    param_avg = {k: float(np.mean(v)) for k, v in results.items()}
    best_param = max(param_avg, key=param_avg.get)
    print(f"[best] α={best_param[0]} β={best_param[1]} avg={param_avg[best_param] * 100:+.1f}%", file=sys.stderr)

    # 用全局最优参数重跑明细
    detail_rows = ""
    hw_tots = []
    hw_effs = []
    for c6, _, bbi_tot in detail:
        closes = cache[c6]["c"]
        s = holt_winters(closes, best_param[0], best_param[1])
        _, ntr, wr, tot = run_strategy(closes, s)
        eff, npv = eff_valley(s, closes)
        hw_tots.append(tot)
        if eff is not None:
            hw_effs.append(eff)
        cls = "pos" if tot > 0 else "neg"
        detail_rows += (f"<tr><td>{c6}</td><td class='{cls}'>{fmt(tot)}</td>"
                        f"<td>{fmt(bbi_tot)}</td><td>{fmt(eff)}</td><td>{ntr}</td></tr>")

    hw_mean = np.mean(hw_tots)
    hw_med = np.median(hw_tots)
    bbi_mean = np.mean(bbi_tots)
    bbi_med = np.median(bbi_tots)
    hw_eff = np.mean(hw_effs) if hw_effs else 0

    # 参数敏感性表
    sens_rows = ""
    for a in ALPHAS:
        row_cells = f"<td>{a}</td>"
        for b in BETAS:
            v = param_avg.get((a, b), 0)
            cls = "pos" if v > 0 else "neg"
            bold = " style='font-weight:700'" if (a, b) == best_param else ""
            row_cells += f"<td class='{cls}'{bold}>{fmt(v)}</td>"
        sens_rows += f"<tr>{row_cells}</tr>"

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>Holt-Winters 双指数平滑 · 1H 拐点回测</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1100px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #1565C0;padding-left:8px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0}}
</style></head><body>
<h1>Holt-Winters 双指数平滑（含趋势项）· 1H 拐点回测</h1>
<div class="box note">Holt's linear trend：Level = α·z + (1−α)(L+T)，Trend = β·ΔL + (1−β)·T。
与 KF 二阶的区别：无协方差矩阵，参数只有 α（水平平滑）和 β（趋势平滑），更稳定不易发散。
数据：1H 新浪 1023 根 ≈ 170 交易日。long-only，费 0.13%，拐点确认根收盘成交。</div>

<h2>① 全池汇总（{len(detail)} 只）</h2>
<table><tr><th>模型</th><th>总收益（均值/中位）</th><th>谷底有效率</th></tr>
<tr><td><b>Holt-Winters（最优 α={best_param[0]}, β={best_param[1]}）</b></td>
<td class='{"pos" if hw_mean > 0 else "neg"}'>{fmt(hw_mean)} / 中位 {fmt(hw_med)}</td>
<td>{hw_eff * 100:.1f}%</td></tr>
<tr><td>BBI12 基线</td><td>{fmt(bbi_mean)} / 中位 {fmt(bbi_med)}</td><td>—</td></tr></table>

<h2>② 600176 中国船舶 · 最优参数案例</h2>
<img src="data:image/png;base64,{case_img}"/>

<h2>③ 参数敏感性（池级平均总收益）</h2>
<table><tr><th>α \\ β</th>{"".join(f"<th>{b}</th>" for b in BETAS)}</tr>{sens_rows}</table>
<p class="note">α 控制水平平滑（越大越贴价），β 控制趋势平滑（越大趋势响应越快）。加粗 = 全局最优。</p>

<h2>④ 全池明细</h2>
<table><tr><th>代码</th><th>HW 收益</th><th>BBI12 收益</th><th>HW 有效率</th><th>HW 交易数</th></tr>
{detail_rows}</table>

<h2>⑤ 结论</h2>
<div class="box note">
1. <b>Holt-Winters vs KF</b>：HW 只有 α、β 两个参数，无协方差矩阵，数值更稳定。
   与 KF 二阶相比，HW 的趋势项更新是确定性的（β·ΔL），不会像 KF 速度项那样在高噪声下发散。<br>
2. <b>vs BBI12</b>：见汇总表。若 HW 优于 BBI12 则值得进一步探索；若仍不及，则 BBI12 在 1H 拐点策略上仍是最佳选择。<br>
3. <b>参数鲁棒性</b>：参数敏感性表展示 α×β 网格的收益分布，若最优区域平坦则参数不过拟合。
</div>
</body></html>"""
    open(REPORT, "w", encoding="utf-8").write(html)
    print(f"[done] → {REPORT}", file=sys.stderr)
    print(f"POOL hw={fmt(hw_mean)}/{fmt(hw_med)} bbi={fmt(bbi_mean)}/{fmt(bbi_med)} eff={hw_eff * 100:.1f}%", file=sys.stderr)


if __name__ == "__main__":
    main()
