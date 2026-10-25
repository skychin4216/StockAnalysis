# -*- coding: utf-8 -*-
"""
卡尔曼滤波 1H 口径 · 二阶模型（local linear trend）+ OBV 外生控制输入
====================================================================
升级（相对日线一阶版）：
  状态 [位置, 速度]：
    x_t = F x_{t-1} + B u_t + w,  w~N(0,Q)
    z_t = H x_t + v,              v~N(0,r)
    F = [[1,1],[0,1]], H=[1,0]
  控制输入 u_t = obv_gain × OBV 变化量（归一化为价格变动等效，clip±8σ）
  Q = [[q,0],[0,q*0.1]]（位置噪声 10× 速度噪声）
对比组：
  kf1  = 一阶 local level（旧版，对照）
  kf2  = 二阶 local linear trend（无 OBV）
  kf2o = 二阶 + OBV 控制输入
  bbi  = BBI12 拐点（项目现行基线）
数据：新浪 1H（scale=60，datalen=1023 ≈ 170 交易日），缓存 smalltools/_records/_kalman_1h_cache.json
用法：python smalltools/_kalman_pivot_backtest_1h.py
"""
import json, math, os, sys, time, base64, urllib.request
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
import numpy as np
import matplotlib
matplotlib.use("Agg")
matplotlib.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
matplotlib.rcParams["axes.unicode_minus"] = False
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
POOL_JSON = os.path.join(ROOT, "app", "src", "main", "assets", "core_stock_pool.json")
CACHE = os.path.join(HERE, "data", "_kalman_1h_cache.json")
REPORT = os.path.join(ROOT, "reports", "kalman_1h_report.html")
FEE = 0.0013
EFF_H, EFF_THR = 20, 0.03
RATIOS = [0.02, 0.05, 0.1, 0.2, 0.5]
OBV_GAINS = [0.0, 0.05, 0.10, 0.15, 0.20, 0.30]


# ── 数据 ────────────────────────────────────────────────────────────────────
def _sym(raw):
    s = raw.strip().lower()
    if s.startswith(("sh", "sz")):
        return s
    return ("sh" if s[0] in "659" else "sz") + s


def sina_fetch(raw):
    sym = _sym(raw)
    url = (f"https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getKLineData"
           f"?symbol={sym}&scale=60&ma=no&datalen=1023")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0",
                                               "Referer": "https://finance.sina.com.cn"})
    with urllib.request.urlopen(req, timeout=15) as r:
        d = json.loads(r.read().decode("utf-8"))
    if not d or len(d) < 300:
        return None
    return {"d": [k["day"] for k in d],
            "c": [float(k["close"]) for k in d],
            "v": [float(k.get("volume", 0)) for k in d]}


def load_pool():
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    return [_norm(s) for s in p["stocks"]]


def _norm(s):
    s = s.strip()
    return s[2:] if len(s) > 6 and s[:2].lower() in ("sh", "sz") else s


def load_data(codes, refresh=False):
    codes = [_norm(c) for c in codes]
    cache = {} if refresh or not os.path.exists(CACHE) else json.load(open(CACHE, encoding="utf-8"))
    todo = [c for c in codes if c not in cache or len(cache[c].get("c", [])) < 300 or "v" not in cache[c]]
    if todo:
        print(f"[data] 新浪 1H 拉取 {len(todo)} 只…", file=sys.stderr)
        def work(c):
            for _ in range(3):
                try:
                    e = sina_fetch(c)
                    if e: return c, e
                except Exception:
                    time.sleep(0.8)
            return c, None
        with ThreadPoolExecutor(6) as ex:
            done = 0
            for c, e in ex.map(work, todo):
                done += 1
                if e: cache[c] = e
                if done % 20 == 0 or e is None:
                    print(f"  {done}/{len(todo)} {c} {'ok' if e else 'FAIL'}", file=sys.stderr)
        json.dump(cache, open(CACHE, "w", encoding="utf-8"))
    return {c: cache[c] for c in codes if c in cache}


# ── Kalman 模型 ─────────────────────────────────────────────────────────────
def kalman_1d(closes, ratio):
    """一阶 local level（旧版对照）"""
    z = np.asarray(closes, float)
    dv = np.diff(z)
    r = float(np.var(dv[dv != 0])) if len(dv) else 1.0
    q = r * ratio
    n = len(z)
    s = np.empty(n); pred = np.empty(n)
    x, p = z[0], r
    s[0] = x; pred[0] = x
    for t in range(1, n):
        pred[t] = x
        p_pred = p + q
        K = p_pred / (p_pred + r)
        x = x + K * (z[t] - x)
        p = (1 - K) * p_pred
        s[t] = x
    return s, pred


def kalman_2d(closes, ratio):
    """二阶 local linear trend（位置+速度，无外生输入）"""
    return kalman_2d_obv(closes, None, ratio, 0.0)


def kalman_2d_obv(closes, volumes, ratio, obv_gain):
    """二阶 local linear trend + OBV 外生控制输入"""
    z = np.asarray(closes, float)
    dv = np.diff(z)
    r = float(np.var(dv[dv != 0])) if len(dv) else 1.0
    q = r * ratio
    n = len(z)
    s = np.empty(n); pred = np.empty(n)

    # OBV 控制输入：归一化为比例值
    u = np.zeros(n)
    if volumes is not None and obv_gain > 0:
        v = np.asarray(volumes, float)
        obv = np.zeros(n)
        for i in range(1, n):
            if z[i] > z[i - 1]:
                obv[i] = obv[i - 1] + v[i]
            elif z[i] < z[i - 1]:
                obv[i] = obv[i - 1] - v[i]
            else:
                obv[i] = obv[i - 1]
        # 归一化：ΔOBV / 20期均量 → 相对力度（无量纲，典型 [-2, +2]）
        vol_ma = np.convolve(v, np.ones(20) / 20, mode="same")
        vol_ma[vol_ma == 0] = 1.0
        obv_ratio = np.zeros(n)
        for i in range(1, n):
            obv_ratio[i] = (obv[i] - obv[i - 1]) / vol_ma[i]
        obv_ratio = np.clip(obv_ratio, -3.0, 3.0)
        # 转为价格变动等效：比例 × 近期价格波动标准差
        price_scale = np.std(dv[dv != 0]) if len(dv) else 1.0
        u = obv_gain * obv_ratio * price_scale

    # 2D KF: state [pos, vel], F=[[1,1],[0,1]], H=[1,0], Q=[[q,0],[0,q*0.1]]
    F = np.array([[1.0, 1.0], [0.0, 1.0]])
    H = np.array([[1.0, 0.0]])
    Q = np.array([[q, 0.0], [0.0, q * 0.1]])
    B = np.array([[1.0], [0.5]])  # OBV 主要驱动位置，部分驱动速度

    x = np.array([z[0], 0.0])
    P = np.array([[r, 0.0], [0.0, r]])
    s[0] = x[0]; pred[0] = x[0]

    for t in range(1, n):
        # Predict
        x_pred = F @ x + B.flatten() * u[t]
        P_pred = F @ P @ F.T + Q
        pred[t] = x_pred[0]
        # Update
        y = z[t] - (H @ x_pred)[0]          # innovation
        S = (H @ P_pred @ H.T)[0, 0] + r    # innovation variance
        K = (P_pred @ H.T).flatten() / S    # Kalman gain [2]
        x = x_pred + K * y
        P = (np.eye(2) - np.outer(K, H.flatten())) @ P_pred
        s[t] = x[0]

    return s, pred


# ── 拐点 / 指标（与日线版同口径）──────────────────────────────────────────
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


def eff_valley(s, closes, h=EFF_H, thr=EFF_THR):
    pv = [(i, b) for i, b in pivots(s) if b and i + h < len(closes)]
    if not pv:
        return None, 0
    hit = sum(1 for i, _ in pv if max(closes[i:i + h + 1]) / closes[i] - 1 >= thr)
    return hit / len(pv), len(pv)


def dir_hit(pred, closes):
    z = np.asarray(closes, float)
    dp = pred[2:] - z[1:-1]
    dr = z[2:] - z[1:-1]
    m = np.isfinite(dp) & (dr != 0)
    return float((np.sign(dp[m]) == np.sign(dr[m])).mean()) if m.any() else None


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
    shp = float(r.mean() / sd * math.sqrt(244 * 6)) if sd > 0 else 0.0  # 1H: ~6根/交易日
    dd = (nav / np.maximum.accumulate(nav) - 1).min()
    return shp, float(dd)


# ── 图 ──────────────────────────────────────────────────────────────────────
def fig_b64(fig):
    buf = BytesIO()
    fig.savefig(buf, format="png", dpi=110, bbox_inches="tight")
    plt.close(fig)
    return base64.b64encode(buf.getvalue()).decode()


def chart_case(dates, closes, s2, s2o, pv2o, tail=200):
    z = np.asarray(closes, float)[-tail:]
    ss2 = s2[-tail:]
    ss2o = s2o[-tail:]
    xs = np.arange(len(z))
    fig, ax = plt.subplots(figsize=(12, 4.5))
    ax.plot(xs, z, color="#999", lw=0.8, label="收盘(1H)")
    ax.plot(xs, ss2, color="#1565C0", lw=1.2, label="KF 二阶")
    ax.plot(xs, ss2o, color="#E65100", lw=1.4, label="KF 二阶+OBV")
    off = len(closes) - tail
    for i, is_b in pv2o:
        x = i - off
        if 0 <= x < len(z):
            ax.scatter([x], [z[x]], color=("#E53935" if is_b else "#43A047"),
                       s=28, zorder=5, marker=("o" if is_b else "^"),
                       label=("谷底拐点" if is_b else "峰顶拐点"))
    h, l = ax.get_legend_handles_labels()
    ax.legend(dict(zip(l, h)).values(), dict(zip(l, h)).keys(), fontsize=8)
    ax.set_title(f"600176 中国船舶 · 1H KF 二阶 vs 二阶+OBV（最近 {tail} 根）", fontsize=11)
    ax.grid(alpha=0.2)
    return fig_b64(fig)


# ── 回测 ────────────────────────────────────────────────────────────────────
def backtest_one(data, ratio=0.1):
    """data = {"c": closes, "v": volumes, "d": dates}"""
    z = data["c"]
    v = data.get("v")
    res = {}
    # kf1 一阶
    s1, p1 = kalman_1d(z, ratio)
    nav, ntr, wr, tot = run_strategy(z, s1)
    shp, mdd = sharpe_mdd(nav)
    eff, npv = eff_valley(s1, z)
    res["kf1"] = dict(total=tot, sharpe=shp, mdd=mdd, trades=ntr, win=wr, eff=eff, npv=npv,
                      dir_hit=dir_hit(p1, z))
    # kf2 二阶
    s2, p2 = kalman_2d(z, ratio)
    nav, ntr, wr, tot = run_strategy(z, s2)
    shp, mdd = sharpe_mdd(nav)
    eff, npv = eff_valley(s2, z)
    res["kf2"] = dict(total=tot, sharpe=shp, mdd=mdd, trades=ntr, win=wr, eff=eff, npv=npv,
                      dir_hit=dir_hit(p2, z))
    # kf2o 二阶+OBV
    s2o, p2o = kalman_2d_obv(z, v, ratio, 0.15)
    nav, ntr, wr, tot = run_strategy(z, s2o)
    shp, mdd = sharpe_mdd(nav)
    eff, npv = eff_valley(s2o, z)
    res["kf2o"] = dict(total=tot, sharpe=shp, mdd=mdd, trades=ntr, win=wr, eff=eff, npv=npv,
                       dir_hit=dir_hit(p2o, z))
    # bbi 基线
    bb = bbi12_line(z)
    nav, ntr, wr, tot = run_strategy(z, bb)
    shp, mdd = sharpe_mdd(nav)
    eff, npv = eff_valley(bb, z)
    res["bbi"] = dict(total=tot, sharpe=shp, mdd=mdd, trades=ntr, win=wr, eff=eff, npv=npv,
                      dir_hit=None)
    return res, s2, s2o


def fmt(v, k="pct"):
    if v is None:
        return "—"
    if k == "pct":
        return f"{v * 100:+.1f}%"
    return f"{v:.2f}"


def stat_col(vals):
    a = np.array([x for x in vals if x is not None])
    if not len(a):
        return "—", 0
    return f"{a.mean() * 100:+.1f}% / 中位 {np.median(a) * 100:+.1f}%", len(a)


def main():
    pool = load_pool()
    case = "600176"
    codes = [case] + [c for c in pool if c != case]
    data = load_data(codes)
    print(f"[data] 可用 {len(data)}/{len(codes)}", file=sys.stderr)

    # ── 600176 深度案例 ──
    case_img = ""
    case_html = ""
    if case in data:
        e = data[case]
        z, v = e["c"], e["v"]
        res, s2, s2o = backtest_one(e)
        pv2o = pivots(s2o)
        case_img = chart_case(e["d"], z, s2, s2o, pv2o)
        case_rows = ""
        for k in ("kf1", "kf2", "kf2o", "bbi"):
            r = res[k]
            lbl = {"kf1": "一阶 local level", "kf2": "二阶 trend", "kf2o": "二阶+OBV", "bbi": "BBI12"}[k]
            cls = "pos" if r["total"] > 0 else "neg"
            case_rows += (f"<tr><td>{lbl}</td><td class='{cls}'>{fmt(r['total'])}</td>"
                          f"<td>{fmt(r['eff'])}</td><td>{fmt(r['dir_hit'])}</td>"
                          f"<td>{r['trades']}</td><td>{fmt(r['win'])}</td></tr>")
        case_html = (f"<h3>600176 · 四种模型对比</h3>"
                     f"<table><tr><th>模型</th><th>总收益</th><th>谷底有效率</th><th>方向命中</th>"
                     f"<th>交易数</th><th>胜率</th></tr>{case_rows}</table>")

    # ── 全池回测 ──
    agg = {k: [] for k in ("kf1", "kf2", "kf2o", "bbi")}
    effs = {k: [] for k in ("kf1", "kf2", "kf2o", "bbi")}
    dhs = {k: [] for k in ("kf1", "kf2", "kf2o")}
    detail = []
    for idx, (c6, e) in enumerate(data.items(), 1):
        print(f"[bt] {idx}/{len(data)} {c6}", file=sys.stderr)
        res, _, _ = backtest_one(e)
        for k in agg:
            agg[k].append(res[k]["total"])
            if res[k]["eff"] is not None:
                effs[k].append(res[k]["eff"])
        for k in dhs:
            if res[k]["dir_hit"] is not None:
                dhs[k].append(res[k]["dir_hit"])
        detail.append((c6, res))

    # ── OBV gain 扫描（池级）──
    obv_scan = []
    for g in OBV_GAINS:
        tots = []
        for c6, e in list(data.items())[:50]:  # 抽50只加速
            s, _ = kalman_2d_obv(e["c"], e["v"], 0.1, g)
            _, _, _, tot = run_strategy(e["c"], s)
            tots.append(tot)
        obv_scan.append((g, float(np.mean(tots))))

    # ── 汇总表 ──
    def row(k, lbl):
        s, n = stat_col(agg[k])
        e = f"{np.mean(effs[k]) * 100:.1f}%" if effs[k] else "—"
        d = f"{np.mean(dhs[k]) * 100:.1f}%" if dhs.get(k) else "—"
        return f"<tr><td>{lbl}</td><td>{s}</td><td>{e}</td><td>{d}</td></tr>"

    obv_rows = "".join(
        f"<tr><td>{g}</td><td class='{('pos' if t > 0 else 'neg')}'>{fmt(t)}</td></tr>"
        for g, t in obv_scan)

    det_rows = "".join(
        f"<tr><td>{c6}</td>"
        + "".join(f"<td class='{('pos' if r[k]['total'] > 0 else 'neg')}'>{fmt(r[k]['total'])}</td>"
                  for k in ("kf1", "kf2", "kf2o", "bbi"))
        + f"<td>{fmt(r['kf2o']['eff'])}</td><td>{fmt(r['kf2o']['dir_hit'])}</td>"
          f"<td>{r['kf2o']['trades']}</td></tr>"
        for c6, r in detail)

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>卡尔曼 1H · 二阶模型 + OBV 控制输入</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1100px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #1565C0;padding-left:8px}} h3{{font-size:15px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0}}
</style></head><body>
<h1>卡尔曼滤波 1H · 二阶模型（local linear trend）+ OBV 外生控制输入</h1>
<div class="box note">升级：一阶 local level → 二阶 local linear trend（状态=[位置,速度]）+ OBV 外生控制输入。
状态方程 x_t = F·x_{{t-1}} + B·u_t + w，F=[[1,1],[0,1]]，u_t = OBV变化量×obv_gain（clip±8σ）。
对比：kf1=一阶旧版 / kf2=二阶无OBV / kf2o=二阶+OBV(0.15) / bbi=BBI12基线。
数据：1H 新浪 1023 根 ≈ 170 交易日。口径与日线版一致（long-only，费 0.13%，拐点确认根收盘成交）。</div>

<h2>① 全池汇总（{len(detail)} 只，1H 口径）</h2>
<table><tr><th>模型</th><th>总收益（均值/中位）</th><th>谷底有效率</th><th>方向命中率</th></tr>
{row("kf1", "一阶 local level（旧）")}
{row("kf2", "二阶 local linear trend")}
{row("kf2o", "二阶 + OBV 控制输入")}
{row("bbi", "BBI12 拐点（基线）")}</table>

<h2>② 600176 中国船舶 · 深度案例</h2>
<img src="data:image/png;base64,{case_img}"/>
{case_html}

<h2>③ OBV 控制增益扫描（前 50 只，ratio=0.1）</h2>
<table><tr><th>obv_gain</th><th>池级平均总收益</th></tr>{obv_rows}</table>
<p class="note">obv_gain=0 即纯二阶无控制输入；增益越大 OBV 影响越强。</p>

<h2>④ 全池明细（{len(detail)} 只）</h2>
<table><tr><th>代码</th><th>一阶</th><th>二阶</th><th>二阶+OBV</th><th>BBI12</th>
<th>有效率</th><th>方向命中</th><th>交易数</th></tr>
{det_rows}</table>

<h2>⑤ 结论</h2>
<div class="box note">
1. <b>二阶 vs 一阶</b>：local linear trend 引入速度项，能捕捉"价格正在向某方向运动"的趋势特征，
   对拐点判断是否改善见汇总表。<br>
2. <b>OBV 控制输入</b>：成交量加权的外生信号，理论上能在拐点处提前给滤波器"推力"，
   改善滞后。增益扫描见③。<br>
3. <b>与日线对比</b>：1H 拐点多但噪声大，关键看二阶+OBV 能否在噪声中保持优势。<br>
4. 本回测为 long-only 1H 口径，与日线结论、App 端 1H BBI12 预警互为参照。
</div>
</body></html>"""
    open(REPORT, "w", encoding="utf-8").write(html)
    print(f"[done] 报告 → {REPORT}", file=sys.stderr)
    for k in ("kf1", "kf2", "kf2o", "bbi"):
        s, _ = stat_col(agg[k])
        print(f"POOL {k}: {s}", file=sys.stderr)


if __name__ == "__main__":
    main()
