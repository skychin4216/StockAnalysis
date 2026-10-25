# -*- coding: utf-8 -*-
"""
卡尔曼融合：SSA mid(因果)=预测曲线 ，BBI12=测量曲线 → 中间融合曲线 → 拐点策略回测
=================================================================================
思路（用户口径）：
  把 SSA mid 因果版当作「预测」、BBI12 当作「测量」，用卡尔曼在两者之间取加权融合，
  得到误差更短的「中间曲线」。权重用**滚动方差最优权重**：
      K_t = Ra_t / (Ra_t + Rb_t)
      Ra = 滚动均方误差(SSA - 真实收盘)，Rb = 滚动均方误差(BBI - 真实收盘)
      融合 fused_t = SSA_t + K_t · (BBI_t − SSA_t)
  （误差大的曲线权重低；等价于两观测按方差反比的最优线性融合。）
然后在融合曲线上跑「拐点谷买峰卖」策略，分别出：纯融合 / 融合+ER≥70，
并与 BBI12+ER70、SSA-causal(纯拐点) 同口径对照。
数据复用 _kalman_1h_cache.json（171 只 1H）；因果 SSA 与 _ssa_causal_check 同口径。
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
REPORT = os.path.join(ROOT, "reports", "kf_ssa_bbi_fuse.html")

L_MAIN, W_MAIN = 30, 120     # 因果 SSA 嵌入/滚动窗口（与 _ssa_causal_check 一致）
KF_WIN = 60                  # 滚动误差方差窗口
WARM = 180                   # 买入持有起算位（对齐引擎暖机）


def roll_mse(x, ref, w=KF_WIN):
    """滚动均方误差（含当前点），端点不足/含 NaN → NaN"""
    e = (np.asarray(x, float) - np.asarray(ref, float)) ** 2
    n = len(e)
    out = np.full(n, np.nan)
    c = np.concatenate([[0.0], np.nancumsum(e)])
    cnt = np.concatenate([[0], np.cumsum(np.isfinite(e).astype(int))])
    for i in range(w, n):
        if cnt[i + 1] - cnt[i - w + 1] == w:
            out[i] = (c[i + 1] - c[i - w + 1]) / w
    return out


def kf_weight_fuse(ssa, bbi, close, w=KF_WIN):
    """滚动方差最优权重融合：fused = ssa + K·(bbi − ssa)，K = Ra/(Ra+Rb)"""
    Ra = roll_mse(ssa, close, w)
    Rb = roll_mse(bbi, close, w)
    den = Ra + Rb
    K = np.where(np.isfinite(den) & (den > 0), Ra / den, np.nan)
    ok = np.isfinite(ssa) & np.isfinite(bbi) & np.isfinite(K)
    return np.where(ok, ssa + K * (bbi - ssa), np.nan), K


def pf(v):
    a = np.asarray(v, float)
    return a.mean() * 100, float(np.median(a) * 100)


def main():
    cache = json.load(open(CACHE, encoding="utf-8"))
    codes = [c for c in cache if len(cache[c].get("c", [])) > 300]
    closes = {c: cache[c]["c"] for c in codes}
    print(f"[data] {len(codes)} 只", file=sys.stderr)

    # ER 横截面分位
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

    res = {k: [] for k in ("bbi_er", "ssa_raw", "ssa_er", "kf_raw", "kf_er")}
    bh = []
    detail = []
    kf_cache = {}   # 供作图
    for i, c in enumerate(codes, 1):
        z = np.asarray(closes[c], float)
        bb = bbi12_line(closes[c])
        ss = ssa_mid_causal(closes[c], L_MAIN, W_MAIN)
        fu, K = kf_weight_fuse(ss, bb, z, KF_WIN)
        kf_cache[c] = (z, bb, ss, fu, K)

        _, _, _, t_bbi = run_strategy(closes[c], bb, er_pct=er_pct[c], er_gate=70)
        _, _, _, t_ssa = run_strategy(closes[c], ss)
        _, _, _, t_ssae = run_strategy(closes[c], ss, er_pct=er_pct[c], er_gate=70)
        _, _, _, t_kf = run_strategy(closes[c], fu)
        _, _, _, t_kfe = run_strategy(closes[c], fu, er_pct=er_pct[c], er_gate=70)
        res["bbi_er"].append(t_bbi); res["ssa_raw"].append(t_ssa); res["ssa_er"].append(t_ssae)
        res["kf_raw"].append(t_kf); res["kf_er"].append(t_kfe)

        b = (z[-1] / z[WARM] - 1) if len(z) > WARM + 5 else np.nan
        bh.append(b)
        km = np.nanmean(K) * 100
        detail.append((c, t_bbi, t_ssa, t_kf, t_kfe, t_kfe - t_bbi, km))
        if i % 40 == 0:
            print(f"[fuse] {i}/{len(codes)}", file=sys.stderr)

    # 案例图 600176
    case_img = ""
    c6 = "600176"
    if c6 in kf_cache:
        z, bb, ss, fu, K = kf_cache[c6]
        tail = 220
        xs = np.arange(tail)
        fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(12, 7), height_ratios=[3, 1], sharex=True)
        ax1.plot(xs, z[-tail:], color="#aaa", lw=0.8, label="收盘")
        ax1.plot(xs, bb[-tail:], color="#1565C0", lw=1.0, ls="--", label="BBI12（测量）")
        ax1.plot(xs, ss[-tail:], color="#E65100", lw=1.0, alpha=0.85, label="SSA mid 因果（预测）")
        ax1.plot(xs, fu[-tail:], color="#7B1FA2", lw=1.6, label="KF 融合曲线")
        ax1.legend(fontsize=8); ax1.grid(alpha=0.2)
        ax1.set_title(f"{c6} · SSA(预测) + BBI12(测量) 卡尔曼融合 · 最近 {tail} 根 1H", fontsize=11)
        ax2.plot(xs, K[-tail:] * 100, color="#00897B", lw=1.0)
        ax2.set_ylabel("K %\n(权重←BBI)", fontsize=8); ax2.grid(alpha=0.2)
        ax2.set_xlabel("最近 220 根 1H")
        case_img = fig_b64(fig)

    m = {k: pf(v) for k, v in res.items()}
    m_bh = pf([x for x in bh if np.isfinite(x)])

    rows = [
        ("BBI12 + ER≥70（现基线）", m["bbi_er"]),
        ("SSA mid 因果 · 纯拐点", m["ssa_raw"]),
        ("SSA mid 因果 + ER≥70", m["ssa_er"]),
        ("KF 融合 · 纯拐点", m["kf_raw"]),
        ("KF 融合 + ER≥70", m["kf_er"]),
    ]
    row_html = "".join(
        f"<tr><td>{n}</td><td class='{('pos' if mn>0 else 'neg')}'>{mn:+.2f}%</td>"
        f"<td class='{('pos' if md>0 else 'neg')}'>{md:+.2f}%</td></tr>"
        for n, (mn, md) in rows)

    best = max(rows, key=lambda r: r[1][1])
    km_all = np.nanmean([d[6] for d in detail])
    det_rows = "".join(
        f"<tr><td>{c}</td>"
        f"<td class='{('pos' if a>0 else 'neg')}'>{a*100:+.1f}%</td>"
        f"<td class='{('pos' if b>0 else 'neg')}'>{b*100:+.1f}%</td>"
        f"<td class='{('pos' if e>0 else 'neg')}'>{e*100:+.1f}%</td>"
        f"<td class='{('pos' if g>0 else 'neg')}'>{g*100:+.1f}%</td>"
        f"<td>{km:.0f}%</td></tr>"
        for c, a, b, e, g, _, km in sorted(detail))

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>SSA(预测)+BBI12(测量) 卡尔曼融合回测</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1150px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #7B1FA2;padding-left:8px;margin-top:26px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0;font-size:13.5px;line-height:1.7}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
.hl{{background:#fff8f8;border-left:5px solid #7B1FA2}}
</style></head><body>
<h1>SSA mid（预测）× BBI12（测量）· 卡尔曼融合回测（1H）</h1>
<div class="box note">
口径：核心池 <b>{len(detail)} 只</b>，1H（≈2022–2026），long-only，费 0.13%。<br>
<b>融合</b>：以 SSA mid 因果为预测、BBI12 为测量，滚动方差最优权重
K_t = Ra/(Ra+Rb)，Ra=滚动{KF_WIN}根均方误差(SSA−收盘)，Rb=(BBI−收盘)；<b>fused = SSA + K·(BBI−SSA)</b>。<br>
策略：融合曲线拐点「谷买峰卖」，另出 +ER 横截面分位≥70 闸门版。
</div>

<h2>① 汇总（均值 / 中位总收益）</h2>
<table><tr><th>策略</th><th>均值</th><th>中位</th></tr>
{row_html}
<tr><td>（参考）买入持有 全池</td><td class='{('pos' if m_bh[0]>0 else 'neg')}'>{m_bh[0]:+.2f}%</td><td class='{('pos' if m_bh[1]>0 else 'neg')}'>{m_bh[1]:+.2f}%</td></tr>
</table>
<div class="box hl">最优：<b>{best[0]}</b>（中位 {best[1][1]:+.2f}%）。全池平均融合权重 K≈{km_all:.0f}%（K 越大越偏向 BBI12 测量值）。</div>

<h2>② 600176 融合示意（预测/测量/融合 + K 曲线）</h2>
<img src="data:image/png;base64,{case_img}"/>

<h2>③ 全池明细（收益%）</h2>
<table><tr><th rowspan="2">代码</th><th>BBI+ER70</th><th>SSA因果</th><th>KF融合</th><th>KF+ER70</th><th rowspan="2">平均K</th></tr>
<tr><th>收益</th><th>收益</th><th>收益</th><th>收益</th></tr>
{det_rows}</table>
<div class="note">生成：smalltools/_kf_ssa_bbi.py ｜ 数据：smalltools/data/_kalman_1h_cache.json</div>
</body></html>"""
    open(REPORT, "w", encoding="utf-8").write(html)
    print(f"[done] {REPORT}", file=sys.stderr)
    for n, (mn, md) in rows:
        print(f"  {n}: mean {mn:+.2f}% / median {md:+.2f}%", file=sys.stderr)
    print(f"  BH: mean {m_bh[0]:+.2f}% / median {m_bh[1]:+.2f}% | avg K={km_all:.0f}%", file=sys.stderr)


if __name__ == "__main__":
    main()
