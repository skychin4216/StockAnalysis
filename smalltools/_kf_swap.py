# -*- coding: utf-8 -*-
"""
卡尔曼「预测/测量」角色互换对照（真递归 KF）
=============================================
背景：上一轮用「滚动方差最优权重」K=Ra/(Ra+Rb) 做融合，这是**反方差对称加权**——
      fused = (1−K)·SSA + K·BBI，把「预测/测量」标签互换后 K'=1−K，
      融合曲线恒等，两种提法**结果完全一样**，无法形成对照。
      因此本轮改用**真递归卡尔曼**：预测步由「预测曲线」提供状态传播（速度项），
      量测步由「测量曲线」修正，角色互换才会真正不同：
          x_pred = x_{t-1} + Δ(预测曲线)          （预测曲线给运动模型）
          x_t    = x_pred + K·( 测量曲线_t − x_pred )
          K      = Pp/(Pp+R)，Pp = P+Q；Q=var(Δ预测)，R=var(测量−预测)
      正向：预测=SSA mid 因果，测量=BBI12
      反向：预测=BBI12，       测量=SSA mid 因果
两者与 BBI12+ER70 同口径对照。数据复用 _kalman_1h_cache.json（171 只 1H）。
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
                              run_strategy, ER_W, fig_b64)

CACHE = os.path.join(HERE, "data", "_kalman_1h_cache.json")
REPORT = os.path.join(ROOT, "reports", "kf_swap.html")
L_MAIN, W_MAIN = 30, 120
WARM = 180


def kf_recur(pred, meas):
    """真递归 KF：预测曲线供速度项，测量曲线做量测修正。返回 (fused, 平均K)"""
    pred = np.asarray(pred, float); meas = np.asarray(meas, float)
    n = len(pred)
    dp = np.full(n, np.nan)
    dp[1:] = pred[1:] - pred[:-1]
    fin = np.isfinite(pred) & np.isfinite(meas)
    Q = np.nanvar(dp[fin]) if fin.sum() > 2 else 1e-6
    R = np.nanvar((meas - pred)[fin]) if fin.sum() > 2 else 1e-6
    Q = Q if Q > 1e-12 else 1e-12
    R = R if R > 1e-12 else 1e-12
    x = np.full(n, np.nan); Ks = []
    prev = np.nan; P = np.nan
    for t in range(n):
        if not fin[t]:
            continue
        v = dp[t] if np.isfinite(dp[t]) else 0.0
        if not np.isfinite(prev):
            x[t] = pred[t]; prev = pred[t]; P = Q
            continue
        xp = prev + v
        Pp = P + Q
        K = Pp / (Pp + R)
        x[t] = xp + K * (meas[t] - xp)
        P = (1 - K) * Pp
        prev = x[t]
        Ks.append(K)
    return x, (np.mean(Ks) if Ks else np.nan)


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

    keys = ("bbi_er", "fwd", "fwd_er", "rev", "rev_er")
    res = {k: [] for k in keys}
    bh = []; detail = []; Kf_all = []; Kr_all = []; ch = {}
    for i, c in enumerate(codes, 1):
        z = np.asarray(closes[c], float)
        bb = bbi12_line(closes[c])
        ss = ssa_mid_causal(closes[c], L_MAIN, W_MAIN)
        ff, kf = kf_recur(ss, bb)      # 正向：预测=SSA，测量=BBI
        fr, kr = kf_recur(bb, ss)      # 反向：预测=BBI，测量=SSA
        ch[c] = (z, bb, ss, ff, fr, kf, kr)

        _, _, _, t_bbi = run_strategy(closes[c], bb, er_pct=er_pct[c], er_gate=70)
        _, _, _, t_f = run_strategy(closes[c], ff)
        _, _, _, t_fe = run_strategy(closes[c], ff, er_pct=er_pct[c], er_gate=70)
        _, _, _, t_r = run_strategy(closes[c], fr)
        _, _, _, t_re = run_strategy(closes[c], fr, er_pct=er_pct[c], er_gate=70)
        res["bbi_er"].append(t_bbi); res["fwd"].append(t_f); res["fwd_er"].append(t_fe)
        res["rev"].append(t_r); res["rev_er"].append(t_re)
        Kf_all.append(kf); Kr_all.append(kr)
        bh.append((z[-1] / z[WARM] - 1) if len(z) > WARM + 5 else np.nan)
        detail.append((c, t_bbi, t_f, t_fe, t_r, t_re, kf))
        if i % 40 == 0:
            print(f"[swap] {i}/{len(codes)}", file=sys.stderr)

    # 案例图
    case_img = ""
    c6 = "600176"
    if c6 in ch:
        z, bb, ss, ff, fr, kf, kr = ch[c6]
        tail = 220
        xs = np.arange(tail)
        fig, ax = plt.subplots(figsize=(12, 5.5))
        ax.plot(xs, z[-tail:], color="#bbb", lw=0.8, label="收盘")
        ax.plot(xs, bb[-tail:], color="#1565C0", lw=1.0, ls="--", label="BBI12")
        ax.plot(xs, ss[-tail:], color="#E65100", lw=1.0, alpha=0.8, label="SSA mid 因果")
        ax.plot(xs, ff[-tail:], color="#7B1FA2", lw=1.6, label="KF正向(预测=SSA,测量=BBI)")
        ax.plot(xs, fr[-tail:], color="#00897B", lw=1.6, ls=":", label="KF反向(预测=BBI,测量=SSA)")
        ax.legend(fontsize=8); ax.grid(alpha=0.2)
        ax.set_title(f"{c6} · 正向 vs 反向 递归KF · 最近 {tail} 根 1H", fontsize=11)
        case_img = fig_b64(fig)

    m = {k: pf(v) for k, v in res.items()}
    m_bh = pf([x for x in bh if np.isfinite(x)])
    rows = [
        ("BBI12 + ER≥70（基线）", m["bbi_er"]),
        ("KF正向 · 纯拐点（预测=SSA）", m["fwd"]),
        ("KF正向 + ER≥70", m["fwd_er"]),
        ("KF反向 · 纯拐点（预测=BBI）", m["rev"]),
        ("KF反向 + ER≥70", m["rev_er"]),
    ]
    row_html = "".join(
        f"<tr><td>{n}</td><td class='{('pos' if mn>0 else 'neg')}'>{mn:+.2f}%</td>"
        f"<td class='{('pos' if md>0 else 'neg')}'>{md:+.2f}%</td></tr>" for n, (mn, md) in rows)

    det_rows = "".join(
        f"<tr><td>{c}</td>"
        f"<td class='{('pos' if a>0 else 'neg')}'>{a*100:+.1f}%</td>"
        f"<td class='{('pos' if b>0 else 'neg')}'>{b*100:+.1f}%</td>"
        f"<td class='{('pos' if d>0 else 'neg')}'>{d*100:+.1f}%</td>"
        f"<td class='{('pos' if e>0 else 'neg')}'>{e*100:+.1f}%</td>"
        f"<td class='{('pos' if g>0 else 'neg')}'>{g*100:+.1f}%</td>"
        f"<td>{km*100:.0f}%</td></tr>"
        for c, a, b, d, e, g, km in sorted(detail))

    ka = np.nanmean([x for x in Kf_all if np.isfinite(x)]) * 100
    kb = np.nanmean([x for x in Kr_all if np.isfinite(x)]) * 100

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>KF 预测/测量角色互换对照</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1150px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #00897B;padding-left:8px;margin-top:26px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0;font-size:13.5px;line-height:1.7}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
</style></head><body>
<h1>卡尔曼「预测/测量」角色互换对照（1H · {len(detail)} 只）</h1>

<div class="box"><b>先说明一个数学事实</b>：上一轮用的是「反方差对称加权融合」
（K=Ra/(Ra+Rb)，fused=(1−K)·SSA+K·BBI）。这种加权<b>对预测/测量的标签是对称的</b>——
把角色互换后 K'=1−K，融合曲线恒等。所以若沿用上轮口径，正/反结果会<b>一模一样</b>，无法对照。<br>
本轮改用<b>真递归卡尔曼</b>：预测步由「预测曲线」提供状态传播的速度项，量测步由另一条曲线修正，
角色互换才真正产生不同曲线。口径：预测步 x_pred=x_prev+Δ(预测曲线)，量测更新
x=x_pred+K·(测量−x_pred)，Q=var(Δ预测)，R=var(测量−预测)；long-only，费 0.13%。</div>

<h2>① 汇总（均值 / 中位总收益）</h2>
<table><tr><th>策略</th><th>均值</th><th>中位</th></tr>
{row_html}
<tr><td>（参考）买入持有 全池</td><td class='{('pos' if m_bh[0]>0 else 'neg')}'>{m_bh[0]:+.2f}%</td><td class='{('pos' if m_bh[1]>0 else 'neg')}'>{m_bh[1]:+.2f}%</td></tr>
</table>
<div class="box">平均卡尔曼增益：正向 K≈{ka:.0f}%，反向 K≈{kb:.0f}%
（K 越大越偏向「测量曲线」修正）。</div>

<h2>② 600176 正向 vs 反向 递归KF</h2>
<img src="data:image/png;base64,{case_img}"/>

<h2>③ 全池明细（收益%）</h2>
<table><tr><th rowspan="2">代码</th><th>BBI+ER70</th><th colspan="2">KF正向(预测=SSA)</th>
<th colspan="2">KF反向(预测=BBI)</th><th rowspan="2">正向K</th></tr>
<tr><th>收益</th><th>纯拐点</th><th>+ER70</th><th>纯拐点</th><th>+ER70</th></tr>
{det_rows}</table>
<div class="note">生成：smalltools/_kf_swap.py ｜ 数据：smalltools/data/_kalman_1h_cache.json</div>
</body></html>"""
    open(REPORT, "w", encoding="utf-8").write(html)
    print(f"[done] {REPORT}", file=sys.stderr)
    for n, (mn, md) in rows:
        print(f"  {n}: mean {mn:+.2f}% / median {md:+.2f}%", file=sys.stderr)
    print(f"  BH: mean {m_bh[0]:+.2f}% / median {m_bh[1]:+.2f}% | K 正向{ka:.0f}% 反向{kb:.0f}%", file=sys.stderr)


if __name__ == "__main__":
    main()
