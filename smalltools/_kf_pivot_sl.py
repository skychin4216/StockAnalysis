# -*- coding: utf-8 -*-
"""
拐点策略 + 跌破买入价止损
==========================
在原「曲线拐点确认 + 可选 ER≥70 闸门」策略上增加止损：持仓中价格跌破买入价 → 立即止损。
对照：BBI12 / KF反向(预测=BBI,测量=SSA) / SSA mid 因果 三条曲线，
各跑「+ER70 无止损」「+ER70 有止损」，BBI 另附纯拐点两版。
口径同前：1H、long-only、费 0.13%/边。
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
from _ssa_causal_check import (ssa_mid_causal, bbi12_line, er_series, pivots,
                              run_strategy, FEE, ER_W, fig_b64)
from _kf_swap import kf_recur

CACHE = os.path.join(HERE, "data", "_kalman_1h_cache.json")
REPORT = os.path.join(ROOT, "reports", "kf_pivot_sl.html")
L_MAIN, W_MAIN = 30, 120


def run_pivot_sl(closes, s, er_pct=None, er_gate=70, stoploss=True):
    """拐点策略 + 跌破买入价止损。与 run_strategy 同口径，仅多一条止损线。"""
    z = np.asarray(closes, float)
    n = len(z)
    pv_at = dict(pivots(s))
    pos = False; nav_i = nav_buy = 1.0; buy_px = np.nan
    n_tr = win = 0; trades = []
    nav = np.ones(n)
    for t in range(n):
        if t > 0 and pos:
            nav_i *= z[t] / z[t - 1]
        # 止损优先于拐点信号
        if pos and stoploss and np.isfinite(buy_px) and z[t] < buy_px:
            nav_i *= (1 - FEE) / (1 + FEE)
            n_tr += 1; win += nav_i / nav_buy > 1
            trades.append((t, "SL")); pos = False
        elif t in pv_at:
            is_b = pv_at[t]
            if er_pct is not None and t < len(er_pct):
                if not np.isfinite(er_pct[t]) or er_pct[t] < er_gate:
                    if pos:
                        nav_i *= (1 - FEE) / (1 + FEE)
                        n_tr += 1; win += nav_i / nav_buy > 1
                        trades.append((t, "S")); pos = False
                    nav[t] = nav_i; continue
            if is_b and not pos:
                pos, nav_buy, buy_px = True, nav_i, z[t]
                trades.append((t, "B"))
            elif (not is_b) and pos:
                nav_i *= (1 - FEE) / (1 + FEE)
                n_tr += 1; win += nav_i / nav_buy > 1
                trades.append((t, "S")); pos = False
        nav[t] = nav_i
    return nav, n_tr, (win / n_tr if n_tr else None), nav[-1] - 1, trades


def pf(v):
    a = np.asarray(v, float)
    return a.mean() * 100, float(np.median(a) * 100)


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

    keys = ("bbi_er", "bbi_er_sl", "bbi_sl", "kf_er", "kf_er_sl", "ssa_er", "ssa_er_sl")
    res = {k: [] for k in keys}
    dd = {k: [] for k in keys}   # 最大回撤
    bh = []; detail = []; ch = {}
    for i, c in enumerate(codes, 1):
        z = np.asarray(closes[c], float)
        bb = bbi12_line(closes[c])
        ss = ssa_mid_causal(closes[c], L_MAIN, W_MAIN)
        fr, _ = kf_recur(bb, ss)

        nv_b, _, _, r_b = run_strategy(closes[c], bb, er_pct=er_pct[c], er_gate=70)
        nv_bs, _, _, r_bs, tr_bs = run_pivot_sl(closes[c], bb, er_pct=er_pct[c], er_gate=70, stoploss=True)
        nv_bp, _, _, r_bp, _ = run_pivot_sl(closes[c], bb, stoploss=True)
        nv_k, _, _, r_k = run_strategy(closes[c], fr, er_pct=er_pct[c], er_gate=70)
        nv_ks, _, _, r_ks, tr_ks = run_pivot_sl(closes[c], fr, er_pct=er_pct[c], er_gate=70, stoploss=True)
        nv_s, _, _, r_s = run_strategy(closes[c], ss, er_pct=er_pct[c], er_gate=70)
        nv_ss, _, _, r_ss, tr_ss = run_pivot_sl(closes[c], ss, er_pct=er_pct[c], er_gate=70, stoploss=True)

        for k, r in zip(keys, (r_b, r_bs, r_bp, r_k, r_ks, r_s, r_ss)):
            res[k].append(r)
        for k, nv in zip(keys, (nv_b, nv_bs, nv_bp, nv_k, nv_ks, nv_s, nv_ss)):
            pk = np.maximum.accumulate(nv)
            dd[k].append(float((nv / pk - 1).min()))
        bh.append((z[-1] / z[W_MAIN] - 1) if len(z) > W_MAIN + 5 else np.nan)
        detail.append((c, r_b, r_bs, r_bp, r_k, r_ks, r_s, r_ss))
        ch[c] = (z, bb, tr_bs)
        if i % 40 == 0:
            print(f"[psl] {i}/{len(codes)}", file=sys.stderr)

    case_img = ""
    c6 = "600176"
    if c6 in ch:
        z, bb, tr = ch[c6]
        tail = 260; a = len(z) - tail
        fig, ax = plt.subplots(figsize=(12.5, 5.5))
        xs = np.arange(tail)
        ax.plot(xs, z[a:], color="#999", lw=0.9, label="收盘")
        ax.plot(xs, bb[a:], color="#1565C0", lw=1.2, label="BBI12")
        mk = {"B": ("^", "#C62828"), "S": ("v", "#2E7D32"), "SL": ("x", "#6A1B9A")}
        for t, k in tr:
            if t >= a:
                mkr, col = mk[k]
                ax.scatter(t - a, z[t], marker=mkr, color=col, s=34, zorder=5)
        from matplotlib.lines import Line2D
        ax.legend(handles=[Line2D([0], [0], color="#999", lw=0.9, label="收盘"),
                           Line2D([0], [0], color="#1565C0", lw=1.2, label="BBI12"),
                           Line2D([0], [0], marker="^", color="w", markerfacecolor="#C62828", markersize=8, label="买"),
                           Line2D([0], [0], marker="v", color="w", markerfacecolor="#2E7D32", markersize=8, label="卖"),
                           Line2D([0], [0], marker="x", color="#6A1B9A", markersize=8, label="止损")],
                  fontsize=8)
        ax.grid(alpha=0.2)
        ax.set_title(f"{c6} · BBI12拐点+ER70+止损 买卖点 · 最近 {tail} 根 1H", fontsize=11)
        case_img = fig_b64(fig)

    m = {k: pf(v) for k, v in res.items()}
    mdd = {k: float(np.mean(dd[k])) * 100 for k in keys}
    m_bh = pf([x for x in bh if np.isfinite(x)])
    rows = [
        ("BBI12 拐点 + ER≥70（基线，无止损）", "bbi_er"),
        ("BBI12 拐点 + ER≥70 + 跌破买入价止损", "bbi_er_sl"),
        ("BBI12 拐点 + 止损（无ER闸门）", "bbi_sl"),
        ("KF反向 拐点 + ER≥70（无止损）", "kf_er"),
        ("KF反向 拐点 + ER≥70 + 止损", "kf_er_sl"),
        ("SSA mid 拐点 + ER≥70（无止损）", "ssa_er"),
        ("SSA mid 拐点 + ER≥70 + 止损", "ssa_er_sl"),
    ]
    row_html = "".join(
        f"<tr><td>{n}</td><td class='{('pos' if m[k][0]>0 else 'neg')}'>{m[k][0]:+.2f}%</td>"
        f"<td class='{('pos' if m[k][1]>0 else 'neg')}'>{m[k][1]:+.2f}%</td>"
        f"<td class='neg'>{mdd[k]:.2f}%</td></tr>" for n, k in rows)
    det_rows = "".join(
        "<tr><td>%s</td>" % c + "".join(
            f"<td class='{('pos' if v>0 else 'neg')}'>{v*100:+.1f}%</td>" for v in vs) + "</tr>"
        for c, *vs in sorted(detail))

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>拐点策略 + 跌破买入价止损</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1150px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #1565C0;padding-left:8px;margin-top:26px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0;font-size:13.5px;line-height:1.7}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
</style></head><body>
<h1>拐点策略 + 跌破买入价止损（1H · {len(detail)} 只）</h1>
<div class="box">规则：曲线<b>拐点确认</b>（次根收盘确认）买/卖；持仓中<b>收盘跌破买入价 → 立即止损</b>；
ER≥70 闸门口径同前（拐点时 ER 不达标则不买/强制退出）。long-only，费 0.13%/边。</div>

<h2>① 汇总（均值 / 中位总收益 / 平均最大回撤）</h2>
<table><tr><th>策略</th><th>均值</th><th>中位</th><th>平均最大回撤</th></tr>
{row_html}
<tr><td>（参考）买入持有 全池</td><td class='{('pos' if m_bh[0]>0 else 'neg')}'>{m_bh[0]:+.2f}%</td><td class='{('pos' if m_bh[1]>0 else 'neg')}'>{m_bh[1]:+.2f}%</td><td>—</td></tr>
</table>
<div class="box">止损贡献：BBI+ER70 {m['bbi_er'][0]:+.2f}% → {m['bbi_er_sl'][0]:+.2f}%（{m['bbi_er_sl'][0]-m['bbi_er'][0]:+.2f}pp）；
KF反向+ER70 {m['kf_er'][0]:+.2f}% → {m['kf_er_sl'][0]:+.2f}%（{m['kf_er_sl'][0]-m['kf_er'][0]:+.2f}pp）；
SSA+ER70 {m['ssa_er'][0]:+.2f}% → {m['ssa_er_sl'][0]:+.2f}%（{m['ssa_er_sl'][0]-m['ssa_er'][0]:+.2f}pp）。</div>

<h2>② 600176 买卖点（BBI12 拐点+ER70+止损）</h2>
<img src="data:image/png;base64,{case_img}"/>

<h2>③ 全池明细（收益%）</h2>
<table><tr><th>代码</th><th>BBI+ER70</th><th>BBI+ER70+止损</th><th>BBI纯+止损</th>
<th>KF反+ER70</th><th>KF反+ER70+止损</th><th>SSA+ER70</th><th>SSA+ER70+止损</th></tr>
{det_rows}</table>
<div class="note">生成：smalltools/_kf_pivot_sl.py ｜ 数据：smalltools/data/_kalman_1h_cache.json</div>
</body></html>"""
    open(REPORT, "w", encoding="utf-8").write(html)
    print(f"[done] {REPORT}", file=sys.stderr)
    for n, k in rows:
        print(f"  {n}: mean {m[k][0]:+.2f}% / median {m[k][1]:+.2f}% / mdd {mdd[k]:.2f}%", file=sys.stderr)
    print(f"  BH: mean {m_bh[0]:+.2f}% / median {m_bh[1]:+.2f}%", file=sys.stderr)


if __name__ == "__main__":
    main()
