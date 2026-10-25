# -*- coding: utf-8 -*-
"""
穿越曲线 + 跌破买入价止损 回测
==============================
规则：价格从下向上穿越曲线 → 买入；从上向下穿越曲线 → 卖出；持仓中跌破买入价 → 止损。
对照两条曲线：KF反向（预测=BBI12，测量=SSA mid 因果） 与 SSA mid 因果。
口径同前：1H、long-only、费 0.0013/边（run_strategy 同款往返 (1−FEE)/(1+FEE)）。
额外加「+ ER≥70 闸门（仅锁定买入）」变体，检验闸门是否仍有效。
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
sys.path.insert(0, HERE)
from _ssa_causal_check import (ssa_mid_causal, bbi12_line, er_series,
                              run_strategy, FEE, ER_W, fig_b64)
from _kf_swap import kf_recur

CACHE = os.path.join(HERE, "data", "_kalman_1h_cache.json")
REPORT = os.path.join(ROOT, "reports", "kf_cross.html")
L_MAIN, W_MAIN = 30, 120


def run_cross(closes, line, er_pct=None, er_gate=70, stoploss=True):
    """穿越信号 + 可选跌破买入价止损。返回 nav, 交易数, 胜率, 总收益, 交易明细"""
    z = np.asarray(closes, float); s = np.asarray(line, float)
    n = len(z)
    pos = False; nav_i = nav_buy = 1.0; buy_px = np.nan
    n_tr = win = 0; trades = []
    nav = np.ones(n)
    for t in range(1, n):
        if pos:
            nav_i *= z[t] / z[t - 1]
        if not (np.isfinite(s[t]) and np.isfinite(s[t - 1])):
            nav[t] = nav_i; continue
        if pos and stoploss and z[t] < buy_px:
            nav_i *= (1 - FEE) / (1 + FEE)
            n_tr += 1; win += nav_i / nav_buy > 1
            trades.append((t, "SL")); pos = False
        else:
            up = z[t - 1] <= s[t - 1] and z[t] > s[t]
            dn = z[t - 1] >= s[t - 1] and z[t] < s[t]
            if up and not pos:
                ok = True
                if er_pct is not None:
                    ok = t < len(er_pct) and np.isfinite(er_pct[t]) and er_pct[t] >= er_gate
                if ok:
                    pos, nav_buy, buy_px = True, nav_i, z[t]
                    trades.append((t, "B"))
            elif dn and pos:
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

    keys = ("bbi_er", "kf_nosl", "kf_sl", "kf_sl_er",
            "ssa_nosl", "ssa_sl", "ssa_sl_er")
    res = {k: [] for k in keys}
    bh = []; detail = []; ch = {}
    for i, c in enumerate(codes, 1):
        z = np.asarray(closes[c], float)
        bb = bbi12_line(closes[c])
        ss = ssa_mid_causal(closes[c], L_MAIN, W_MAIN)
        fr, kr = kf_recur(bb, ss)          # KF反向：预测=BBI，测量=SSA

        _, _, _, t_bbi = run_strategy(closes[c], bb, er_pct=er_pct[c], er_gate=70)
        n1, c1, w1, r1, tr1 = run_cross(closes[c], fr, stoploss=False)
        n2, c2, w2, r2, tr2 = run_cross(closes[c], fr, stoploss=True)
        n3, c3, w3, r3, tr3 = run_cross(closes[c], fr, er_pct=er_pct[c], er_gate=70, stoploss=True)
        m1, d1, v1, s1, ts1 = run_cross(closes[c], ss, stoploss=False)
        m2, d2, v2, s2, ts2 = run_cross(closes[c], ss, stoploss=True)
        m3, d3, v3, s3, ts3 = run_cross(closes[c], ss, er_pct=er_pct[c], er_gate=70, stoploss=True)

        res["bbi_er"].append(t_bbi)
        res["kf_nosl"].append(r1); res["kf_sl"].append(r2); res["kf_sl_er"].append(r3)
        res["ssa_nosl"].append(s1); res["ssa_sl"].append(s2); res["ssa_sl_er"].append(s3)
        bh.append((z[-1] / z[W_MAIN] - 1) if len(z) > W_MAIN + 5 else np.nan)
        detail.append((c, t_bbi, r1, r2, r3, s1, s2, s3))
        ch[c] = (z, ss, fr, tr2, tr3)
        if i % 40 == 0:
            print(f"[cross] {i}/{len(codes)}", file=sys.stderr)

    case_img = ""
    c6 = "600176"
    if c6 in ch:
        z, ss, fr, tr2, tr3 = ch[c6]
        tail = 260; a = len(z) - tail
        fig, ax = plt.subplots(figsize=(12.5, 5.5))
        xs = np.arange(tail)
        ax.plot(xs, z[a:], color="#999", lw=0.9, label="收盘")
        ax.plot(xs, ss[a:], color="#E65100", lw=1.0, alpha=0.8, label="SSA mid 因果")
        ax.plot(xs, fr[a:], color="#00897B", lw=1.5, label="KF反向(预测=BBI,测量=SSA)")
        for t, k in tr2:
            if t >= a:
                ax.scatter(t - a, z[t], marker="^" if k == "B" else "v",
                           color="#C62828" if k == "B" else "#2E7D32", s=32, zorder=5)
        ax.legend(fontsize=8); ax.grid(alpha=0.2)
        ax.set_title(f"{c6} · KF反向 穿越+止损 买卖点（▲买 ▼卖/止损）· 最近 {tail} 根 1H", fontsize=11)
        case_img = fig_b64(fig)

    m = {k: pf(v) for k, v in res.items()}
    m_bh = pf([x for x in bh if np.isfinite(x)])
    rows = [
        ("BBI12 + ER≥70（基线）", m["bbi_er"]),
        ("KF反向 穿越（无止损）", m["kf_nosl"]),
        ("KF反向 穿越 + 跌破买入价止损", m["kf_sl"]),
        ("KF反向 穿越 + 止损 + ER≥70", m["kf_sl_er"]),
        ("SSA mid 穿越（无止损）", m["ssa_nosl"]),
        ("SSA mid 穿越 + 跌破买入价止损", m["ssa_sl"]),
        ("SSA mid 穿越 + 止损 + ER≥70", m["ssa_sl_er"]),
    ]
    row_html = "".join(
        f"<tr><td>{n}</td><td class='{('pos' if mn>0 else 'neg')}'>{mn:+.2f}%</td>"
        f"<td class='{('pos' if md>0 else 'neg')}'>{md:+.2f}%</td></tr>" for n, (mn, md) in rows)
    det_rows = "".join(
        "<tr><td>%s</td>" % c + "".join(
            f"<td class='{('pos' if v>0 else 'neg')}'>{v*100:+.1f}%</td>" for v in vs) + "</tr>"
        for c, *vs in sorted(detail))

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>穿越曲线 + 止损 回测</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1150px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #00897B;padding-left:8px;margin-top:26px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0;font-size:13.5px;line-height:1.7}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
</style></head><body>
<h1>穿越曲线 + 跌破买入价止损（1H · {len(detail)} 只）</h1>
<div class="box">规则：价格<b>从下向上穿越曲线→买入</b>；<b>从上向下穿越→卖出</b>；持仓中<b>跌破买入价→止损</b>。<br>
对照曲线：<b>KF反向</b>（预测=BBI12，测量=SSA mid 因果）与 <b>SSA mid 因果</b>。
long-only，费 0.13%/边（同款往返口径）。额外加「ER≥70 仅锁买入」变体。</div>

<h2>① 汇总（均值 / 中位总收益）</h2>
<table><tr><th>策略</th><th>均值</th><th>中位</th></tr>
{row_html}
<tr><td>（参考）买入持有 全池</td><td class='{('pos' if m_bh[0]>0 else 'neg')}'>{m_bh[0]:+.2f}%</td><td class='{('pos' if m_bh[1]>0 else 'neg')}'>{m_bh[1]:+.2f}%</td></tr>
</table>
<div class="box">止损作用：KF反向 无止损 {m['kf_nosl'][0]:+.2f}% → 有止损 {m['kf_sl'][0]:+.2f}%（差 {m['kf_sl'][0]-m['kf_nosl'][0]:+.2f}pp）；
SSA 无止损 {m['ssa_nosl'][0]:+.2f}% → 有止损 {m['ssa_sl'][0]:+.2f}%（差 {m['ssa_sl'][0]-m['ssa_nosl'][0]:+.2f}pp）。</div>

<h2>② 600176 买卖点（KF反向 穿越+止损）</h2>
<img src="data:image/png;base64,{case_img}"/>

<h2>③ 全池明细（收益%）</h2>
<table><tr><th>代码</th><th>BBI+ER70</th><th>KF无止损</th><th>KF+止损</th><th>KF+止损+ER70</th>
<th>SSA无止损</th><th>SSA+止损</th><th>SSA+止损+ER70</th></tr>
{det_rows}</table>
<div class="note">生成：smalltools/_kf_cross.py ｜ 数据：smalltools/data/_kalman_1h_cache.json</div>
</body></html>"""
    open(REPORT, "w", encoding="utf-8").write(html)
    print(f"[done] {REPORT}", file=sys.stderr)
    for n, (mn, md) in rows:
        print(f"  {n}: mean {mn:+.2f}% / median {md:+.2f}%", file=sys.stderr)
    print(f"  BH: mean {m_bh[0]:+.2f}% / median {m_bh[1]:+.2f}%", file=sys.stderr)


if __name__ == "__main__":
    main()
