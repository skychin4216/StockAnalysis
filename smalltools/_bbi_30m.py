# -*- coding: utf-8 -*-
"""
方向C：30min 短周期数据辅助 回测
================================
思路：30min BBI12 拐点比 1H 提前约 1-2 根（平滑窗口在更细粒度上）。
三口径对照（同一时间窗 ≈ 30min 1023 根 ≈ 128 交易日）：
  A. 1H  BBI12拐点 + ER70 + 止损（基线，1H 截断到同窗）
  B. 30min BBI12拐点 + ER70 + 止损（纯 30min，ER 也在 30min 横截面算）
  C. 30min BBI12拐点 + 1H ER70 闸门 + 止损（30min 出入场，1H 定环境）
止损均为「收盘跌破买入价」。费 0.13%/边，long-only。
数据：新浪 scale=30 抓取 → data/_kalman_30m_cache.json（d/c/v）。
"""
import json, os, sys, time, urllib.request
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

CACHE_1H = os.path.join(HERE, "data", "_kalman_1h_cache.json")
CACHE_30 = os.path.join(HERE, "data", "_kalman_30m_cache.json")
REPORT = os.path.join(ROOT, "reports", "bbi_30m.html")


def fetch30(code, datalen=1023):
    sym = ("sh" if code[0] in "659" else "sz") + code
    url = ("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
           f"CN_MarketData.getKLineData?symbol={sym}&scale=30&ma=no&datalen={datalen}")
    for _ in range(2):
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": "Mozilla/5.0", "Referer": "https://finance.sina.com.cn/"})
            arr = json.loads(urllib.request.urlopen(req, timeout=20).read().decode())
            d, c, v = [], [], []
            for o in arr:
                day = o.get("day", "")
                if len(day) < 16:
                    continue
                cl = float(o.get("close", 0))
                if cl <= 0:
                    continue
                d.append(day[:16]); c.append(cl); v.append(float(o.get("volume", 0)))
            return {"d": d, "c": c, "v": v}
        except Exception:
            time.sleep(1)
    return None


def ensure_cache(codes):
    if os.path.exists(CACHE_30):
        old = json.load(open(CACHE_30, encoding="utf-8"))
    else:
        old = {}
    out = dict(old)
    todo = [c for c in codes if c not in out or len(out[c].get("c", [])) < 300]
    print(f"[fetch] 30min 需抓 {len(todo)}/{len(codes)}", file=sys.stderr)
    for i, c in enumerate(todo, 1):
        r = fetch30(c)
        if r and len(r["c"]) >= 300:
            out[c] = r
        if i % 20 == 0:
            print(f"[fetch] {i}/{len(todo)}", file=sys.stderr)
            json.dump(out, open(CACHE_30, "w", encoding="utf-8"))
        time.sleep(0.25)
    json.dump(out, open(CACHE_30, "w", encoding="utf-8"))
    return out


def er_pct_pool(closes_map):
    """横截面 ER 分位（同 _ssa_causal_check 口径）"""
    all_er = {c: er_series(closes_map[c]) for c in closes_map}
    nb = min(len(closes_map[c]) for c in closes_map)
    pct = {c: np.full(nb, np.nan) for c in closes_map}
    for t in range(ER_W, nb):
        es, vl = [], []
        for c in closes_map:
            e = all_er[c][t] if t < len(all_er[c]) else np.nan
            if np.isfinite(e):
                es.append(e); vl.append(c)
        if not es:
            continue
        arr = np.array(es)
        for c, e in zip(vl, es):
            pct[c][t] = float((arr < e).sum() / len(arr) * 100)
    return pct


def mdd_of(nv):
    pk = np.maximum.accumulate(nv)
    return float((nv / pk - 1).min()) * 100


def main():
    c1h = json.load(open(CACHE_1H, encoding="utf-8"))
    codes = [c for c in c1h if len(c1h[c].get("c", [])) > 300]
    c30 = ensure_cache(codes)
    codes = [c for c in codes if c in c30]
    print(f"[data] 30min 覆盖 {len(codes)} 只", file=sys.stderr)

    # 1H 截断到与 30min 同一时间窗（2 根 30min ≈ 1 根 1H）
    closes30 = {c: c30[c]["c"] for c in codes}
    closes1h = {}
    for c in codes:
        n30 = len(closes30[c]); n1h = len(c1h[c]["c"])
        closes1h[c] = c1h[c]["c"][max(0, n1h - n30 // 2):]

    er30 = er_pct_pool(closes30)
    er1h = er_pct_pool(closes1h)

    keys = ("A_1h", "B_30m", "C_mix")
    res = {k: [] for k in keys}
    dd = {k: [] for k in keys}
    ntr = {k: [] for k in keys}
    detail = []; ch = {}
    for i, c in enumerate(codes, 1):
        z30 = np.asarray(closes30[c], float)
        z1h = np.asarray(closes1h[c], float)
        if len(z30) < 400 or len(z1h) < 200:
            continue
        b30 = bbi12_line(closes30[c])
        b1h = bbi12_line(closes1h[c])

        # A: 1H 基线
        nvA, ntA, _, rA, trA = run_pivot_sl(closes1h[c], b1h, er_pct=er1h[c], er_gate=70)
        # B: 纯 30min
        nvB, ntB, _, rB, trB = run_pivot_sl(closes30[c], b30, er_pct=er30[c], er_gate=70)
        # C: 30min 出入场 + 1H ER 闸门（索引尾部对齐：t30 → t1h）
        n30, n1 = len(z30), len(z1h)
        off = n30 - 2 * n1
        er_map = np.full(n30, np.nan)
        for t in range(n30):
            t1 = (t - off) // 2
            if 0 <= t1 < len(er1h[c]):
                er_map[t] = er1h[c][t1]
        nvC, ntC, _, rC, trC = run_pivot_sl(closes30[c], b30, er_pct=er_map, er_gate=70)

        for k, nv, nt, r in (("A_1h", nvA, ntA, rA), ("B_30m", nvB, ntB, rB), ("C_mix", nvC, ntC, rC)):
            res[k].append(r); ntr[k].append(nt); dd[k].append(mdd_of(nv))
        detail.append((c, rA, rB, rC))
        ch[c] = (z30, b30, trB)
        if i % 40 == 0:
            print(f"[30m] {i}/{len(codes)}", file=sys.stderr)

    case_img = ""
    c6 = "600176"
    if c6 in ch:
        z, b, tr = ch[c6]
        tail = 400; a = len(z) - tail
        fig, ax = plt.subplots(figsize=(12.5, 5.5))
        xs = np.arange(tail)
        ax.plot(xs, z[a:], color="#999", lw=0.8, label="收盘(30min)")
        ax.plot(xs, b[a:], color="#1565C0", lw=1.1, label="BBI12(30min)")
        mk = {"B": ("^", "#C62828"), "S": ("v", "#2E7D32"), "SL": ("x", "#6A1B9A")}
        for t, k in tr:
            if t >= a:
                mkr, col = mk[k]
                ax.scatter(t - a, z[t], marker=mkr, color=col, s=34, zorder=5)
        ax.legend(fontsize=8); ax.grid(alpha=0.2)
        ax.set_title(f"{c6} · 30min BBI12拐点+ER70+止损 · 最近 {tail} 根", fontsize=11)
        case_img = fig_b64(fig)

    m = {k: pf(v) for k, v in res.items()}
    mdd = {k: float(np.mean(dd[k])) for k in keys}
    mtr = {k: float(np.mean(ntr[k])) for k in keys}
    rows = [
        ("A · 1H 拐点+ER70+止损（同窗基线）", "A_1h"),
        ("B · 30min 拐点+ER70+止损（纯30min）", "B_30m"),
        ("C · 30min 出入场 + 1H ER70 闸门 + 止损", "C_mix"),
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
<title>30min 短周期辅助 回测</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1150px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #00897B;padding-left:8px;margin-top:26px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0;font-size:13.5px;line-height:1.7}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
</style></head><body>
<h1>方向C：30min 短周期辅助（{len(detail)} 只 · 同时间窗）</h1>
<div class="box">同一时间窗（30min 1023 根 ≈ 128 交易日，1H 截断对齐）三口径对照：
A=1H 基线；B=纯 30min（ER 也在 30min 横截面算）；C=30min 出入场 + 1H ER 闸门。
均带 ER≥70 + 跌破买入价止损，费 0.13%/边，long-only。<br>
注意：30min 交易频率天然约 2 倍，费用损耗更大；同一时间窗比全窗短（约 128 vs 256 交易日），
A 口径与本窗外的 1H 长窗结果不可直接比。</div>

<h2>① 汇总（均值 / 中位总收益 / 平均最大回撤 / 均交易数）</h2>
<table><tr><th>策略</th><th>均值</th><th>中位</th><th>平均最大回撤</th><th>均交易数</th></tr>
{row_html}</table>

<h2>② 600176 30min 买卖点</h2>
<img src="data:image/png;base64,{case_img}"/>

<h2>③ 全池明细（收益%）</h2>
<table><tr><th>代码</th><th>A·1H</th><th>B·30min</th><th>C·混合</th></tr>
{det_rows}</table>
<div class="note">生成：smalltools/_bbi_30m.py ｜ 数据：smalltools/data/_kalman_30m_cache.json（30min）+ _kalman_1h_cache.json（1H）</div>
</body></html>"""
    open(REPORT, "w", encoding="utf-8").write(html)
    print(f"[done] {REPORT}", file=sys.stderr)
    for n, k in rows:
        print(f"  {n}: mean {m[k][0]:+.2f}% / median {m[k][1]:+.2f}% / mdd {mdd[k]:.2f}% / trades {mtr[k]:.1f}", file=sys.stderr)


if __name__ == "__main__":
    main()
