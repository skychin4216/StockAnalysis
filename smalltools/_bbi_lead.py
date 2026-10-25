# -*- coding: utf-8 -*-
"""
方向A：BBI12 + 一阶速度项外推（曲线平移/相位超前）回测
=====================================================
思路：lead_k[t] = bbi[t] + k·(bbi[t] − bbi[t−1])，等价于把 BBI12 向前平移 k 根
      （用当前斜率外推下一根位置），拐点在 lead 曲线上确认 → 拐点判定提前约 k 根。
对照：k=0（= 原 BBI12+ER70+止损 基线）、k=0.5/1/2，均带 ER≥70 闸门 + 跌破买入价止损；
      另附 k=1 不带止损版，分离平移与止损的贡献。
口径同前：1H、long-only、费 0.13%/边、171 只池。
"""
import json, os, sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
matplotlib.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
matplotlib.rcParams["axes.unicode_minus"] = False
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from _ssa_causal_check import bbi12_line, er_series, ER_W, fig_b64
from _kf_pivot_sl import run_pivot_sl, pf

CACHE = os.path.join(HERE, "data", "_kalman_1h_cache.json")
REPORT = os.path.join(ROOT, "reports", "bbi_lead.html")
W_MAIN = 120
KS = (0.0, 0.5, 1.0, 2.0)


def lead_line(bbi, k):
    """一阶泰勒外推：lead[t] = bbi[t] + k·(bbi[t]−bbi[t−1])"""
    b = np.asarray(bbi, float)
    out = np.full(b.size, np.nan)
    d = np.full(b.size, np.nan)
    d[1:] = b[1:] - b[:-1]
    m = np.isfinite(b) & np.isfinite(d)
    out[m] = b[m] + k * d[m]
    return out


def main():
    cache = json.load(open(CACHE, encoding="utf-8"))
    codes = [c for c in cache if len(cache[c].get("c", [])) > 300]
    closes = {c: cache[c]["c"] for c in codes}
    print(f"[data] {len(codes)} 只", file=sys.stderr)

    all_er = {c: er_series(closes[c]) for c in codes}
    nb = min(len(closes[c]) for c in codes)
    er_pct = {c: np.full(nb, np.nan) for c in codes}
    for t in range(ER_W, nb):
        es, vl = [], []
        for c in codes:
            e = all_er[c][t] if t < len(all_er[c]) else np.nan
            if np.isfinite(e):
                es.append(e); vl.append(c)
        if not es:
            continue
        arr = np.array(es)
        for c, e in zip(vl, es):
            er_pct[c][t] = float((arr < e).sum() / len(arr) * 100)

    keys = ["base"] + [f"k{int(k*10)}" for k in KS[1:]] + ["k10_nosl"]
    res = {k: [] for k in keys}
    dd = {k: [] for k in keys}
    ntr = {k: [] for k in keys}
    bh = []; detail = []; ch = {}
    for i, c in enumerate(codes, 1):
        z = np.asarray(closes[c], float)
        bb = bbi12_line(closes[c])
        leads = {k: (bb if k == 0.0 else lead_line(bb, k)) for k in KS}

        def run(k, sl=True):
            nv, nt, _, r, tr = run_pivot_sl(closes[c], leads[k], er_pct=er_pct[c],
                                            er_gate=70, stoploss=sl)
            return nv, nt, r, tr

        nv0, nt0, r0, tr0 = run(0.0)
        r_map = {"base": (nv0, nt0, r0)}
        tr_map = {"base": tr0}
        for k in KS[1:]:
            nv, nt, r, tr = run(k)
            r_map[f"k{int(k*10)}"] = (nv, nt, r)
            tr_map[f"k{int(k*10)}"] = tr
        nv_ns, nt_ns, r_ns, _ = run(1.0, sl=False)
        r_map["k10_nosl"] = (nv_ns, nt_ns, r_ns)

        for k in keys:
            nv, nt, r = r_map[k]
            res[k].append(r); ntr[k].append(nt)
            pk = np.maximum.accumulate(nv)
            dd[k].append(float((nv / pk - 1).min()))
        bh.append((z[-1] / z[W_MAIN] - 1) if len(z) > W_MAIN + 5 else np.nan)
        detail.append((c,) + tuple(r_map[k][2] for k in keys))
        ch[c] = (z, bb, leads[1.0], tr_map["k10"], tr0)
        if i % 40 == 0:
            print(f"[lead] {i}/{len(codes)}", file=sys.stderr)

    case_img = ""
    c6 = "600176"
    if c6 in ch:
        z, bb, ld, tr_ld, tr0 = ch[c6]
        tail = 260; a = len(z) - tail
        fig, ax = plt.subplots(figsize=(12.5, 5.5))
        xs = np.arange(tail)
        ax.plot(xs, z[a:], color="#999", lw=0.9, label="收盘")
        ax.plot(xs, bb[a:], color="#1565C0", lw=1.2, label="BBI12")
        ax.plot(xs, ld[a:], color="#E65100", lw=1.2, ls="--", label="BBI12+k=1 平移线")
        for t, k in tr0:
            if t >= a:
                ax.scatter(t - a, z[t], marker="o", facecolors="none",
                           edgecolors="#1565C0", s=46, zorder=4)
        mk = {"B": ("^", "#C62828"), "S": ("v", "#2E7D32"), "SL": ("x", "#6A1B9A")}
        for t, k in tr_ld:
            if t >= a:
                mkr, col = mk[k]
                ax.scatter(t - a, z[t], marker=mkr, color=col, s=34, zorder=5)
        ax.legend(fontsize=8); ax.grid(alpha=0.2)
        ax.set_title(f"{c6} · k=1 平移线拐点(▲▼✕) vs 原BBI12拐点(○) · 最近 {tail} 根 1H", fontsize=11)
        case_img = fig_b64(fig)

    m = {k: pf(v) for k, v in res.items()}
    mdd = {k: float(np.mean(dd[k])) * 100 for k in keys}
    mtr = {k: float(np.mean(ntr[k])) for k in keys}
    m_bh = pf([x for x in bh if np.isfinite(x)])
    rows = [
        ("k=0 · BBI12+ER70+止损（基线）", "base"),
        ("k=0.5 · 平移半根 + ER70 + 止损", "k5"),
        ("k=1 · 平移一根 + ER70 + 止损", "k10"),
        ("k=2 · 平移两根 + ER70 + 止损", "k20"),
        ("k=1 · 平移一根 + ER70（无止损）", "k10_nosl"),
    ]
    row_html = "".join(
        f"<tr><td>{n}</td><td class='{('pos' if m[k][0]>0 else 'neg')}'>{m[k][0]:+.2f}%</td>"
        f"<td class='{('pos' if m[k][1]>0 else 'neg')}'>{m[k][1]:+.2f}%</td>"
        f"<td class='neg'>{mdd[k]:.2f}%</td><td>{mtr[k]:.1f}</td></tr>" for n, k in rows)
    det_rows = "".join(
        "<tr><td>%s</td>" % c + "".join(
            f"<td class='{('pos' if v>0 else 'neg')}'>{v*100:+.1f}%</td>" for v in vs) + "</tr>"
        for c, *vs in sorted(detail))

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>BBI12 平移（速度项外推）回测</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1150px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #E65100;padding-left:8px;margin-top:26px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0;font-size:13.5px;line-height:1.7}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
</style></head><body>
<h1>BBI12 平移（一阶速度项外推）回测 · 1H · {len(detail)} 只</h1>
<div class="box">lead_k[t] = bbi[t] + k·(bbi[t]−bbi[t−1])，相当于把 BBI12 向前平移 k 根；
拐点在 lead 线上确认（次根收盘），买卖按确认根收盘价执行。全部带 ER≥70 闸门 + 跌破买入价止损
（k=1 另附无止损版）。long-only，费 0.13%/边。</div>

<h2>① 汇总（均值 / 中位总收益 / 平均最大回撤 / 平均交易数）</h2>
<table><tr><th>策略</th><th>均值</th><th>中位</th><th>平均最大回撤</th><th>均交易数</th></tr>
{row_html}
<tr><td>（参考）买入持有 全池</td><td class='{('pos' if m_bh[0]>0 else 'neg')}'>{m_bh[0]:+.2f}%</td><td class='{('pos' if m_bh[1]>0 else 'neg')}'>{m_bh[1]:+.2f}%</td><td>—</td><td>—</td></tr>
</table>

<h2>② 600176 平移线拐点 vs 原拐点</h2>
<img src="data:image/png;base64,{case_img}"/>

<h2>③ 全池明细（收益%）</h2>
<table><tr><th>代码</th><th>k=0基线</th><th>k=0.5</th><th>k=1</th><th>k=2</th><th>k=1无止损</th></tr>
{det_rows}</table>
<div class="note">生成：smalltools/_bbi_lead.py ｜ 数据：smalltools/data/_kalman_1h_cache.json</div>
</body></html>"""
    open(REPORT, "w", encoding="utf-8").write(html)
    print(f"[done] {REPORT}", file=sys.stderr)
    for n, k in rows:
        print(f"  {n}: mean {m[k][0]:+.2f}% / median {m[k][1]:+.2f}% / mdd {mdd[k]:.2f}% / trades {mtr[k]:.1f}", file=sys.stderr)
    print(f"  BH: mean {m_bh[0]:+.2f}% / median {m_bh[1]:+.2f}%", file=sys.stderr)


if __name__ == "__main__":
    main()
