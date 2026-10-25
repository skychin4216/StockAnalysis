# -*- coding: utf-8 -*-
"""
ER 阶段自适应 BBI 周期 · 1H 拐点回测
========================================
核心逻辑：ER 分位决定 BBI 的 SMA 周期
  趋势段（分位 ≥ 70）→ 慢线（周期 24）：趋势中少假信号
  过渡段（30~70）      → 中线（周期 12）：默认
  震荡段（分位 ≤ 30）→ 快线（周期 6） ：震荡中快进快出
对比：固定 BBI12 vs 自适应 BBI。
数据：复用 _kalman_1h_cache.json（1H，1023 根）。
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
REPORT = os.path.join(ROOT, "reports", "er_adaptive_bbi_1h.html")
FEE = 0.0013
EFF_H, EFF_THR = 20, 0.03
ER_W = 60  # ER 窗口

# 三组周期映射方案
SCHEMES = {
    "A(24/12/6)": {"trend": 24, "mid": 12, "range": 6},
    "B(18/12/6)": {"trend": 18, "mid": 12, "range": 6},
    "C(24/12/8)": {"trend": 24, "mid": 12, "range": 8},
    "D(18/12/8)": {"trend": 18, "mid": 12, "range": 8},
}


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


def bbi_with_period(closes, sma_p):
    z = np.asarray(closes, float)
    m3, m6 = sma_series(z, 3), sma_series(z, 6)
    m12, m24 = sma_series(z, 12), sma_series(z, 24)
    bbi = np.where(np.isfinite(m3) & np.isfinite(m6) & np.isfinite(m12) & np.isfinite(m24),
                   (m3 + m6 + m12 + m24) / 4.0, np.nan)
    return sma_series(bbi, sma_p)


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

    # ── 预计算：所有票的 ER 序列 + 各周期 BBI 线 ──
    all_er = {}
    all_closes = {}
    periods_needed = set()
    for s in SCHEMES.values():
        periods_needed.update(s.values())
    all_bbi = {}  # (code, period) -> bbi line
    for idx, c6 in enumerate(codes, 1):
        closes = cache[c6]["c"]
        all_closes[c6] = closes
        all_er[c6] = er_series(closes)
        for p in periods_needed:
            all_bbi[(c6, p)] = bbi_with_period(closes, p)
        if idx % 40 == 0:
            print(f"[pre] {idx}/{len(codes)}", file=sys.stderr)

    # ── 横截面分位：每个时间点，所有票 ER 的百分位 ──
    n_bars = min(len(all_closes[c]) for c in codes)
    pct_map = {}  # (code, t) -> percentile
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
            pct = float((arr < e).sum() / len(arr) * 100)
            pct_map[(c, t)] = pct

    # ── 构建自适应 BBI 序列 ──
    def adaptive_bbi(c6, scheme):
        """按 ER 分位选周期，拼接自适应 BBI 序列"""
        closes = all_closes[c6]
        n = len(closes)
        s = np.full(n, np.nan)
        for t in range(n):
            pct = pct_map.get((c6, t))
            if pct is None:
                continue
            if pct >= 70:
                p = scheme["trend"]
            elif pct <= 30:
                p = scheme["range"]
            else:
                p = scheme["mid"]
            s[t] = all_bbi[(c6, p)][t]
        return s

    # ── 回测 ──
    results = {}  # scheme_name -> list of totals
    results["BBI12固定"] = []
    for name in SCHEMES:
        results[name] = []

    detail = []
    for idx, c6 in enumerate(codes, 1):
        closes = all_closes[c6]
        # 基线：固定 BBI12
        bb12 = all_bbi[(c6, 12)]
        _, _, _, tot12 = run_strategy(closes, bb12)
        results["BBI12固定"].append(tot12)
        # 各自适应方案
        row = {"code": c6, "bbi12": tot12}
        for name, scheme in SCHEMES.items():
            s = adaptive_bbi(c6, scheme)
            _, ntr, wr, tot = run_strategy(closes, s)
            eff, npv = eff_valley(s, closes)
            results[name].append(tot)
            row[name] = tot
        detail.append(row)
        if idx % 40 == 0:
            print(f"[bt] {idx}/{len(codes)}", file=sys.stderr)

    # ── 600176 案例图 ──
    case_img = ""
    c6 = "600176"
    if c6 in all_closes:
        closes = all_closes[c6]
        best_scheme = max(SCHEMES, key=lambda n: np.mean(results[n]))
        s_ad = adaptive_bbi(c6, SCHEMES[best_scheme])
        s_fx = all_bbi[(c6, 12)]
        pv = pivots(s_ad)
        tail = 200
        z = np.asarray(closes, float)[-tail:]
        xs = np.arange(len(z))
        fig, ax = plt.subplots(figsize=(12, 4.2))
        ax.plot(xs, z, color="#999", lw=0.8, label="收盘(1H)")
        ax.plot(xs, s_fx[-tail:], color="#1565C0", lw=1.0, ls="--", label="BBI12 固定")
        ax.plot(xs, s_ad[-tail:], color="#E65100", lw=1.4, label=f"自适应 BBI ({best_scheme})")
        off = len(closes) - tail
        for i, is_b in pv:
            x = i - off
            if 0 <= x < len(z):
                ax.scatter([x], [z[x]], color=("#E53935" if is_b else "#43A047"),
                           s=28, zorder=5, marker=("o" if is_b else "^"),
                           label=("谷底" if is_b else "峰顶"))
        h, l = ax.get_legend_handles_labels()
        ax.legend(dict(zip(l, h)).values(), dict(zip(l, h)).keys(), fontsize=8)
        ax.set_title(f"600176 · 自适应 BBI vs 固定 BBI12（最近 {tail} 根 1H）", fontsize=11)
        ax.grid(alpha=0.2)
        case_img = fig_b64(fig)

    # ── 汇总 ──
    summary_rows = ""
    for name in ["BBI12固定"] + list(SCHEMES.keys()):
        vals = results[name]
        m, med = np.mean(vals), np.median(vals)
        cls = "pos" if m > 0 else "neg"
        bold = " style='font-weight:700'" if name != "BBI12固定" and m > np.mean(results["BBI12固定"]) else ""
        summary_rows += f"<tr><td{bold}>{name}</td><td class='{cls}'{bold}>{fmt(m)} / 中位 {fmt(med)}</td></tr>"

    det_rows = ""
    for r in detail:
        cells = f"<td>{r['code']}</td><td>{fmt(r['bbi12'])}</td>"
        for name in SCHEMES:
            v = r[name]
            cls = "pos" if v > 0 else "neg"
            cells += f"<td class='{cls}'>{fmt(v)}</td>"
        det_rows += f"<tr>{cells}</tr>"

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>ER 阶段自适应 BBI 周期 · 1H 回测</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1100px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #1565C0;padding-left:8px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0}}
</style></head><body>
<h1>ER 阶段自适应 BBI 周期 · 1H 拐点回测</h1>
<div class="box note">核心逻辑：ER 分位 ≥70（趋势段）用慢线 BBI（周期长，少假信号）；
30~70（过渡）用 BBI12（默认）；≤30（震荡段）用快线 BBI（周期短，快进快出）。
数据：1H 1023 根 ≈ 170 交易日。long-only，费 0.13%。</div>

<h2>① 全池汇总（{len(detail)} 只）</h2>
<table><tr><th>方案</th><th>总收益（均值/中位）</th></tr>{summary_rows}</table>

<h2>② 600176 案例</h2>
<img src="data:image/png;base64,{case_img}"/>

<h2>③ 全池明细</h2>
<table><tr><th>代码</th><th>BBI12固定</th>{"".join(f"<th>{n}</th>" for n in SCHEMES)}</tr>
{det_rows}</table>

<h2>④ 结论</h2>
<div class="box note">自适应 BBI 的核心假设：趋势段用慢线减少假信号，震荡段用快线提高响应。
若自适应方案优于固定 BBI12，说明 ER 阶段信息对拐点策略有增益；若不及，则固定 BBI12 仍是首选。</div>
</body></html>"""
    open(REPORT, "w", encoding="utf-8").write(html)
    print(f"[done] → {REPORT}", file=sys.stderr)
    for name in ["BBI12固定"] + list(SCHEMES.keys()):
        vals = results[name]
        print(f"POOL {name}: {fmt(np.mean(vals))} / 中位 {fmt(np.median(vals))}", file=sys.stderr)


if __name__ == "__main__":
    main()
