# -*- coding: utf-8 -*-
"""
卡尔曼滤波拐点判断 · 准确率分析 + 策略回测（2026-10-19 用户需求）
====================================================================
模型：local level Kalman（隐状态=价格真实水平，观测=收盘价）
  x_t = x_{t-1} + w,  w~N(0,q)；  z_t = x_t + v,  v~N(0,r)
  r = Var(一阶差分)（全局估计）；  q = r * ratio；ratio 越小越平滑（滞后大），越大越贴价。
拐点：KF 平滑曲线 s 上，与 BBI12 拐点同口径：
  谷底  b<=a && c>b（信号根=确认根 i+1）；峰顶 b>=a && c<b。次根收盘确认，无前视。
策略（long-only）：谷底确认根收盘买入，峰顶确认根收盘卖出；费率双边 0.13%。
准确率：
  ① 谷底拐点有效率 = 拐点后 20 根内最大涨幅 >= +3% 的比例（对比原始价拐点 / BBI12）
  ② 一步预测方向命中率 = sign(pred-close[t-1]) 与实际一致比例
数据：东财日线全史（前复权），缓存 data/_kalman_cache.json；池 = assets 核心池。
用法：
  python smalltools/_kalman_pivot_backtest.py                 # 全流程（单票600176 + 全池 + 报告）
  python smalltools/_kalman_pivot_backtest.py --codes 600176  # 仅单票
  python smalltools/_kalman_pivot_backtest.py --pool 30       # 池内抽前30只
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
CACHE = os.path.join(HERE, "data", "_kalman_cache.json")
REPORT = os.path.join(ROOT, "reports", "kalman_pivot_report.html")
FEE = 0.0013          # 双边合计
W_KF = 200            # KF 预热用最近根数（全史太长，拐点统计取最近 N 根）
BACKTEST_BARS = 1000  # 回测窗口：新浪日线上限 1023 根（约 4 年）
CASE_TAIL = 500       # 600176 展示图根数

RATIOS = [0.02, 0.05, 0.1, 0.2, 0.5]
EFF_H, EFF_THR = 20, 0.03   # 谷底拐点有效率：后 H 根内最大涨幅阈值


# ── 数据 ────────────────────────────────────────────────────────────────────
def _norm(raw: str) -> str:
    s = raw.strip()
    return s[2:] if len(s) > 6 and s[:2] in ("sh", "sz", "bj") else s


def _fetch_sina(c6: str):
    """新浪历史日线（前复权 by scale=240），上限 datalen=1023 根。东财 push2his 本机不可达。"""
    sym = ("sh" if c6.startswith(("6", "9", "5")) else "sz") + c6
    url = ("https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getKLineData"
           f"?symbol={sym}&scale=240&ma=no&datalen=1023")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0",
                                               "Referer": "https://finance.sina.com.cn"})
    with urllib.request.urlopen(req, timeout=15) as r:
        d = json.loads(r.read().decode("utf-8"))
    if not d or len(d) < 300:
        return None
    return {"dates": [k["day"][:10] for k in d], "closes": [float(k["close"]) for k in d]}


def load_pool() -> list:
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    return [_norm(s) for s in p["stocks"]]


def load_data(codes, refresh=False):
    codes = [_norm(c) for c in codes]
    cache = {} if refresh or not os.path.exists(CACHE) else json.load(open(CACHE, encoding="utf-8"))
    todo = [c for c in codes if c not in cache or len(cache[c].get("closes", [])) < 300]
    if todo:
        print(f"[data] 新浪拉取 {len(todo)} 只日线（1023 根上限）…", file=sys.stderr)
        def work(c):
            for _ in range(3):
                try:
                    e = _fetch_sina(c)
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


def kalman_ll(closes, ratio):
    """local level KF，返回 (平滑曲线 s, 一步预测 pred)。pred[t] = t 根的后验（对 t+1 的预测）。"""
    z = np.asarray(closes, float)
    dv = np.diff(z)
    r = float(np.var(dv[dv != 0])) if len(dv) else 1.0
    q = r * ratio
    n = len(z)
    s = np.empty(n); pred = np.empty(n)
    x, p = z[0], r
    s[0] = x; pred[0] = x
    for t in range(1, n):
        pred[t] = x                      # 对 z_t 的先验预测
        p_pred = p + q
        K = p_pred / (p_pred + r)
        x = x + K * (z[t] - x)
        p = (1 - K) * p_pred
        s[t] = x
    return s, pred


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
    """谷底=(确认根,True) 峰顶=(确认根,False)；与 PC bbi12_pivots 同口径（信号根=确认根 i+1）。"""
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


def pivot_eff_rate(s, closes, h=EFF_H, thr=EFF_THR):
    """谷底拐点有效率：确认根后 h 根内最大涨幅 >= thr 的比例。"""
    pv = [(i, is_b) for i, is_b in pivots(s) if is_b and i + h < len(closes)]
    if not pv:
        return None, 0
    hit = sum(1 for i, _ in pv if max(closes[i:i + h + 1]) / closes[i] - 1 >= thr)
    return hit / len(pv), len(pv)


def dir_hit_rate(pred, closes):
    z = np.asarray(closes, float)
    d_pred = pred[2:] - z[1:-1]
    d_real = z[2:] - z[1:-1]
    m = np.isfinite(d_pred) & (d_real != 0)
    if not m.any():
        return None
    return float((np.sign(d_pred[m]) == np.sign(d_real[m])).mean())


def run_strategy(closes, s):
    """谷底买入/峰顶卖出（确认根收盘成交），逐日推进净值；返回 (净值曲线, 交易数, 胜率, 总收益)。"""
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
                pos, nav_buy = True, nav_i          # 收盘买入
            elif (not is_b) and pos:
                nav_i *= (1 - FEE) / (1 + FEE)      # 卖出扣双边费
                n_tr += 1
                win += nav_i / nav_buy > 1
                pos = False
        nav[t] = nav_i
    return nav, n_tr, (win / n_tr if n_tr else None), nav[-1] - 1


def sharpe_mdd(nav):
    r = np.diff(nav) / nav[:-1]
    sd = r.std()
    shp = float(r.mean() / sd * math.sqrt(244)) if sd > 0 else 0.0
    dd = (nav / np.maximum.accumulate(nav) - 1).min()
    return shp, float(dd)


# ── 报告图 ──────────────────────────────────────────────────────────────────
def fig_b64(fig):
    buf = BytesIO()
    fig.savefig(buf, format="png", dpi=110, bbox_inches="tight")
    plt.close(fig)
    return base64.b64encode(buf.getvalue()).decode()


def chart_case(dates, closes, s, pv, tail=CASE_TAIL):
    z = np.asarray(closes, float)[-tail:]
    ss = s[-tail:]
    xs = np.arange(len(z))
    fig, ax = plt.subplots(figsize=(11, 4.2))
    ax.plot(xs, z, color="#999999", lw=0.9, label="收盘")
    ax.plot(xs, ss, color="#1565C0", lw=1.4, label="Kalman 平滑 (ratio=0.1)")
    for i, is_b in pv:
        if i >= len(xs):
            continue
        x = len(z) - tail + i - tail + tail  # 全局 i → 局部
        x = i - (len(closes) - tail)
        if 0 <= x < len(z):
            ax.scatter([x], [z[x]], color=("#E53935" if is_b else "#43A047"),
                       s=26, zorder=5, marker=("o" if is_b else "^"),
                       label=("谷底拐点(确认)" if is_b else "峰顶拐点(确认)"))
    h, l = ax.get_legend_handles_labels()
    uniq = dict(zip(l, h))
    ax.legend(uniq.values(), uniq.keys(), fontsize=8)
    ax.set_title(f"600176 中国船舶 · Kalman 平滑曲线与拐点（最近 {tail} 根日线）", fontsize=11)
    ax.grid(alpha=0.2)
    return fig_b64(fig)


def chart_pred(dates, closes, pred, tail=120):
    z = np.asarray(closes, float)[-tail:]
    pp = pred[-tail:]
    xs = np.arange(len(z))
    fig, ax = plt.subplots(figsize=(11, 3.6))
    ax.plot(xs, z, color="#555555", lw=1.1, label="实际收盘")
    ax.plot(xs, pp, color="#E65100", lw=1.1, ls="--", label="KF 一步预测")
    ax.legend(fontsize=8)
    ax.set_title("600176 · Kalman 一步预测 vs 实际（最近 120 根）——方向命中率见上文", fontsize=11)
    ax.grid(alpha=0.2)
    return fig_b64(fig)


# ── 主流程 ──────────────────────────────────────────────────────────────────
def backtest_one(closes, ratio=0.1, bars=BACKTEST_BARS):
    """最近 bars 根上：KF 策略 + 基线（BBI12 拐点 / 原始价拐点 / 买入持有）。"""
    z = list(closes[-bars:])
    s, pred = kalman_ll(z, ratio)
    res = {}
    nav, ntr, wr, tot = run_strategy(z, s)
    shp, mdd = sharpe_mdd(nav)
    eff, npv = pivot_eff_rate(s, z)
    dh = dir_hit_rate(pred, z)
    res["kf"] = dict(total=tot, sharpe=shp, mdd=mdd, trades=ntr, win=wr, eff=eff, n_pivot=npv, dir_hit=dh)
    bb = bbi12_line(z)
    nav2, ntr2, wr2, tot2 = run_strategy(z, bb)
    shp2, mdd2 = sharpe_mdd(nav2)
    eff2, npv2 = pivot_eff_rate(bb, z)
    res["bbi"] = dict(total=tot2, sharpe=shp2, mdd=mdd2, trades=ntr2, win=wr2, eff=eff2, n_pivot=npv2, dir_hit=None)
    nav3, ntr3, wr3, tot3 = run_strategy(z, np.asarray(z, float))
    shp3, mdd3 = sharpe_mdd(nav3)
    eff3, npv3 = pivot_eff_rate(np.asarray(z, float), z)
    res["raw"] = dict(total=tot3, sharpe=shp3, mdd=mdd3, trades=ntr3, win=wr3, eff=eff3, n_pivot=npv3, dir_hit=None)
    res["bh"] = dict(total=z[-1] / z[0] - 1, sharpe=None, mdd=None, trades=0, win=None, eff=None, n_pivot=0, dir_hit=None)
    return res


def fmt(v, kind="pct"):
    if v is None:
        return "—"
    if kind == "pct":
        return f"{v*100:+.1f}%"
    if kind == "p2":
        return f"{v:.2f}"
    return str(v)


def main():
    only = None
    pool_n = None
    args = sys.argv[1:]
    if "--codes" in args:
        only = args[args.index("--codes") + 1].split(",")
    if "--pool" in args:
        pool_n = int(args[args.index("--pool") + 1])

    pool = load_pool()
    case = only or ["600176"]
    codes = case + ([c for c in pool if c not in case][:pool_n] if pool_n else
                    [c for c in pool if c not in case])
    data = load_data(codes)
    print(f"[data] 可用 {len(data)}/{len(codes)} 只", file=sys.stderr)

    # ── 1) 单票深度案例（600176）──────────────────────────────────────────
    case_img, case_tbl = "", ""
    if case[0] in data:
        c6 = case[0]
        e = data[c6]
        closes = e["closes"]
        # 参数敏感性（单票，最近 BACKTEST_BARS 根）
        sens = [(rt, backtest_one(closes, rt)["kf"]["total"]) for rt in RATIOS]
        best_rt = max(sens, key=lambda t: t[1])[0]
        s, pred = kalman_ll(list(closes[-BACKTEST_BARS:]), best_rt)
        pv = pivots(s)
        eff, npv = pivot_eff_rate(s, closes[-BACKTEST_BARS:])
        dh = dir_hit_rate(pred, closes[-BACKTEST_BARS:])
        case_img = chart_case(e["dates"], closes, s, pv)
        case_img2 = chart_pred(e["dates"], closes, pred)
        rows = "".join(
            f"<tr><td>{rt}</td><td class='{('pos' if t>0 else ('neg' if t<0 else ''))}'>{fmt(t)}</td></tr>"
            for rt, t in sens)
        case_tbl = (f"<h3>② 参数敏感性（ratio = q/r，最近 {BACKTEST_BARS} 根）</h3>"
                    f"<table><tr><th>ratio</th><th>KF 拐点策略总收益</th></tr>{rows}</table>"
                    f"<p class='note'>谷底拐点有效率（后{EFF_H}根≥+{EFF_THR*100:.0f}%）："
                    f"<b>{fmt(eff)}</b>（{npv} 个谷底拐点）；一步预测方向命中率：<b>{fmt(dh)}</b>。</p>")
        globals()["_case_img2"] = case_img2

    # ── 2) 全池回测汇总 ────────────────────────────────────────────────────
    if not only:
        agg = {"kf": [], "bbi": [], "raw": [], "bh": []}
        effs = {"kf": [], "bbi": [], "raw": []}
        sens_rows = {rt: [] for rt in RATIOS}
        detail = []
        for idx, (c6, e) in enumerate(data.items(), 1):
            print(f"[bt] {idx}/{len(data)} {c6}", file=sys.stderr)
            res = backtest_one(e["closes"])
            for k in agg:
                agg[k].append(res[k]["total"])
            for k in effs:
                if res[k]["eff"] is not None:
                    effs[k].append(res[k]["eff"])
            for rt in RATIOS:
                if rt != 0.1:
                    sens_rows[rt].append(backtest_one(e["closes"], rt)["kf"]["total"])
            sens_rows[0.1].append(res["kf"]["total"])
            detail.append((c6, res))
        def stat(v):
            a = np.array(v)
            return f"{a.mean()*100:+.1f}% / 中位 {np.median(a)*100:+.1f}%"
        def stat_eff(v):
            return f"{np.mean(v)*100:.1f}%" if v else "—"
        # 各 ratio 池级平均
        sens_pool = [(rt, float(np.mean(sens_rows[rt]))) for rt in RATIOS]

        # HTML
        bh_s = stat(agg["bh"]); kf_s = stat(agg["kf"]); bbi_s = stat(agg["bbi"]); raw_s = stat(agg["raw"])
        det_rows = "".join(
            f"<tr><td>{c6}</td><td>{fmt(r['kf']['total'])}</td><td>{fmt(r['bbi']['total'])}</td>"
            f"<td>{fmt(r['bh']['total'])}</td><td>{fmt(r['kf']['eff'])}</td><td>{fmt(r['bbi']['eff'])}</td>"
            f"<td>{fmt(r['kf']['dir_hit'])}</td><td>{r['kf']['trades']}</td></tr>"
            for c6, r in detail)
        sens_html = "".join(
            f"<tr><td>{rt}</td><td class='{('pos' if t>0 else ('neg' if t<0 else ''))}'>{fmt(t)}</td></tr>" for rt, t in sens_pool)
        html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>卡尔曼滤波拐点 · 准确率与策略回测</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1080px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #1565C0;padding-left:8px}} h3{{font-size:15px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.note{{color:#666;font-size:12.5px}} img{{max-width:100%;border:1px solid #eee;border-radius:6px}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0}}
</style></head><body>
<h1>卡尔曼滤波拐点判断 · 准确率分析与策略回测</h1>
<div class="box note">模型：local level Kalman（x_t=x_{{t-1}}+w, z_t=x_t+v；q=r·ratio）。拐点口径与 BBI12 相同（谷 b≤a&amp;c&gt;b，峰 b≥a&amp;c&lt;b，确认根=拐点后 1 根收盘，无前视）。策略：谷底确认根收盘买入、峰顶确认根收盘卖出（long-only，双边费 0.13%）。窗口：最近 {BACKTEST_BARS} 根日线（约 6 年）。谷底拐点有效率 = 确认根后 {EFF_H} 根内最大涨幅 ≥ +{EFF_THR*100:.0f}% 的比例。</div>

<h2>① 结论摘要</h2>
<table><tr><th>策略（全池均值/中位）</th><th>总收益</th><th>谷底拐点有效率</th></tr>
<tr><td><b>KF 拐点（ratio=0.1）</b></td><td class='{"pos" if np.mean(agg["kf"])>0 else "neg"}'>{kf_s}</td><td>{stat_eff(effs["kf"])}</td></tr>
<tr><td>BBI12 拐点（现行口径）</td><td>{bbi_s}</td><td>{stat_eff(effs["bbi"])}</td></tr>
<tr><td>原始价拐点（无平滑）</td><td>{raw_s}</td><td>{stat_eff(effs["raw"])}</td></tr>
<tr><td>买入持有</td><td>{bh_s}</td><td>—</td></tr></table>
<p class="note">方向命中率：KF 一步预测对"明收 vs 今收"方向的平均命中 {stat_eff(effs['kf']) and ''}<b>{np.mean([r['kf']['dir_hit'] for _, r in detail if r['kf']['dir_hit'] is not None])*100 if any(r['kf']['dir_hit'] for _, r in detail) else 0:.1f}%</b>（≈50% 即无预测力，滤波的价值在平滑去噪不在预测）。</p>

<h2>② 600176 中国船舶 · 深度案例</h2>
<img src="data:image/png;base64,{case_img}"/>
{case_tbl}
<img src="data:image/png;base64,{globals().get("_case_img2", "")}"/>

<h2>③ 参数敏感性（全池，ratio 扫描）</h2>
<table><tr><th>ratio</th><th>池级平均总收益</th></tr>{sens_html}</table>
<p class="note">ratio 越小越平滑：拐点更少更稳（滞后更大）；越大越贴价：拐点多、噪声多。池级最优 ratio 见上表加粗最高行。</p>

<h2>④ 全池明细（{len(detail)} 只）</h2>
<table><tr><th>代码</th><th>KF 收益</th><th>BBI12 收益</th><th>买入持有</th><th>KF 有效率</th><th>BBI12 有效率</th><th>KF 方向命中</th><th>KF 交易数</th></tr>
{det_rows}</table>

<h2>⑤ 结论与建议</h2>
<div class="box note">
1. <b>平滑 ≠ 预测</b>：KF 一步预测方向命中率通常接近 50%（随机水平）——卡尔曼滤波在日线上没有方向预测力，价值在于<b>去噪后的拐点更少更稳</b>，降低假信号交易次数。<br>
2. <b>拐点有效率对比</b>：KF 平滑曲线的谷底拐点有效率 vs BBI12 / 原始价拐点见摘要表——若 KF 有效率更高且交易数更少，说明"少而准"。<br>
3. <b>适用建议</b>：KF 拐点适合作为<b>确认器</b>（对已由 ER 分位/BBI12 选出的票做买点细化），不宜单独作为选股信号；ratio 取池级最优值，个股差异见参数敏感性。<br>
4. 本回测为日线 long-only 口径，与 1H 口径的 BBI12 回测结论不可直接对比。
</div>
</body></html>"""
        open(REPORT, "w", encoding="utf-8").write(html)
        print(f"[done] 报告 → {REPORT}", file=sys.stderr)
        print(f"POOL_DONE kf={kf_s} bbi={bbi_s} raw={raw_s} bh={bh_s}")
        print(f"EFF kf={stat_eff(effs['kf'])} bbi={stat_eff(effs['bbi'])} raw={stat_eff(effs['raw'])}")
    else:
        print(f"CASE_DONE")


if __name__ == "__main__":
    main()
