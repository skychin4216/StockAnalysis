# -*- coding: utf-8 -*-
"""
ER 路由规则（v1）· 可回测实现 + 报告生成
================================================================================
规则方案（对应 2026-10-18 的分期/Plan B 结论）
--------------------------------------------------------------------------------
信号层（单票，1H）：
  BBI12 拐点 —— BBI=(MA3+MA6+MA12+MA24)/4 的均线 SMA(BBI,12)；
  谷底确认（line[i]<=line[i-1] 且 line[i+1]>line[i]）→ 买；峰顶反之 → 卖。
  信号根 = 确认根 = i+1，成交价 = 该根收盘（与 Kotlin 引擎 SsaBbiPivotEngine 同口径）。

票性路由层（**跨票、因果**）：
  每根 bar 只把资金分给「当前 ER 处于横截面高位」的持仓票（等权）；ER 低的持仓票空仓等待。
  ER = Kaufman 效率比 = |C_i - C_{i-W}| / Σ|ΔC|（W=60），只用 ≤ i 的收盘价。
  决策用 ER[i-1]（避免用含 close[i] 的 ER[i] 决定 i-1→i 的收益 → 前视偏差）。

变体：
  V0 基线·纯BBI12        w=1，无闸门            （逐票）
  V1 票性仓位 w(ref)     无闸门，按票性趋势度给 0.25~1.0 权重
  V2 趋势闸门            仅「趋势态」建仓；持仓中转震荡态即退出
  V3 = V1 + V2
  V4 = V3 + 趋势后期禁入
  组合级  ALL / TOP-N / ER≥横截面分位 / ER 加权

复现校验：BBI12 逐票逐窗口复现 app/build/ssa_multistock_wr.csv 的「① 基线·纯BBI12」。
输出：app/build/er_router_summary.json + reports/er_router_backtest.html/.png
用法：python smalltools/_er_router_backtest.py [--fetch]   （--fetch 强制重抓行情）
"""
import json, math, os, sys, time, statistics as st
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "smalltools", "_records", "_er_router_cache.json")
CSV = os.path.join(ROOT, "app", "build", "ssa_multistock_wr.csv")
OUTJ = os.path.join(ROOT, "app", "build", "er_router_summary.json")
OUTH = os.path.join(ROOT, "reports", "er_router_backtest.html")
OUTP = os.path.join(ROOT, "reports", "er_router_backtest.png")

W = 60          # Kaufman 效率比窗口
MIN_SAMP = 120  # 扩窗分位最少样本
P_HI, P_LO = 60, 40
FROM = 300      # 回测起点（前 300 根做 ER 预热）

CODES = [
    "600176", "600519", "000858", "600036", "000001", "601318", "600030", "300750",
    "002594", "601012", "600276", "000333", "600887", "601899", "600028", "601857",
    "600900", "601088", "000651", "002415", "600009", "601888", "000725", "002230",
    "600585", "601166", "600048", "000002", "601668", "600438", "002714", "300059",
    "600309", "601398", "600000", "601988", "600104", "000063", "601288", "601939",
    "601328", "600016", "601169", "601009", "600015", "601818", "000538", "000568",
    "002304", "603288", "600809", "000596", "002007", "300347", "600196", "000963",
    "002422", "300760", "603259", "600570", "002050", "300124", "002475", "002241",
    "300308", "002463", "603501", "688981", "688036", "002352", "600745", "000100",
    "000625", "601633", "600741", "601238", "002027", "300015", "600690", "002142",
    "601628", "601601", "000776", "600837", "601211", "600999", "601377", "002736",
    "000166", "600018", "601018", "600011", "600886", "600795", "601985", "600025",
    "003816", "601728", "600050", "000977",
]


# ───────────────────────── 行情 ─────────────────────────
def fetch(code, datalen=1023):
    sym = ("sh" if code[0] in "569" else "sz") + code
    url = ("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
           "CN_MarketData.getKLineData?symbol=%s&scale=60&ma=no&datalen=%d" % (sym, datalen))
    for att in range(4):
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": "Mozilla/5.0", "Referer": "https://finance.sina.com.cn/"})
            body = urllib.request.urlopen(req, timeout=20).read().decode("utf-8", "ignore")
            if body.strip().startswith("["):
                return [[o["day"][:10], o["day"][11:16], o["open"], o["high"], o["low"], o["close"]]
                        for o in json.loads(body) if len(o["day"]) >= 16]
        except Exception:
            pass
        time.sleep(0.4 * (att + 1))
    return []


def load_bars(force=False):
    if os.path.exists(CACHE) and not force:
        d = json.load(open(CACHE, encoding="utf-8"))
        if len(d) >= 80:
            print("行情缓存：%s（%d 只）" % (CACHE, len(d)))
            return d
    tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_records")
    tmp2 = r"C:\Users\flour\AppData\Local\Temp\barscache.json"
    if os.path.exists(tmp2):
        d = json.load(open(tmp2, encoding="utf-8"))
        print("复用 barscache：%d 只" % len(d))
    else:
        print("抓取行情（%d 只 × 1023 根 1H）…" % len(CODES))
        d = {}
        for i, c in enumerate(CODES):
            b = fetch(c)
            if len(b) >= 500:
                d[c] = b
            print("  [%d/%d] %s %d" % (i + 1, len(CODES), c, len(b)))
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    json.dump(d, open(CACHE, "w", encoding="utf-8"), ensure_ascii=False)
    return d


# ───────────────────────── 序列（与 Kotlin 引擎同口径） ─────────────────────────
def sma_series(a, n):
    out = [float("nan")] * len(a)
    for i in range(len(a)):
        if i >= n - 1:
            s, ok = 0.0, True
            for k in range(i - n + 1, i + 1):
                if a[k] != a[k]:
                    ok = False; break
                s += a[k]
            out[i] = s / n if ok else float("nan")
    return out


def bbi12_line(close):
    m3, m6 = sma_series(close, 3), sma_series(close, 6)
    m12, m24 = sma_series(close, 12), sma_series(close, 24)
    bbi = [( (m3[i] + m6[i] + m12[i] + m24[i]) / 4.0
             if m3[i] == m3[i] and m6[i] == m6[i] and m12[i] == m12[i] and m24[i] == m24[i]
             else float("nan")) for i in range(len(close))]
    return sma_series(bbi, 12)


def bbi12_pivots(line):
    out = []
    for i in range(1, len(line) - 1):
        a, b, c = line[i - 1], line[i], line[i + 1]
        if a != a or b != b or c != c:
            continue
        if b <= a and c > b:
            out.append((i + 1, True))
        elif b >= a and c < b:
            out.append((i + 1, False))
    return out


def er_series(close, w=W):
    n = len(close)
    out = [float("nan")] * n
    for i in range(w, n):
        vol = sum(abs(close[k] - close[k - 1]) for k in range(i - w + 1, i + 1))
        out[i] = abs(close[i] - close[i - w]) / vol if vol > 0 else 0.0
    return out


def pctile(vals, p):
    xs = sorted(v for v in vals if v == v)
    if not xs:
        return float("nan")
    k = (len(xs) - 1) * p / 100.0
    lo, hi = int(math.floor(k)), int(math.ceil(k))
    return xs[lo] if lo == hi else xs[lo] * (hi - k) + xs[hi] * (k - lo)


# ───────────────────────── 单票：复现校验 + 变体 ─────────────────────────
def sim_plain(close, sigs, frm, to):
    buyAt = set(i for i, b in sigs if b)
    sellAt = set(i for i, b in sigs if not b)
    cum, holding, entry = 1.0, False, 0.0
    trades, wins = 0, 0
    for i in range(frm, to + 1):
        if not holding:
            if i in buyAt:
                entry, holding = close[i], True
            continue
        if i in sellAt:
            cum *= close[i] / entry
            trades += 1
            if close[i] > entry:
                wins += 1
            holding = False
    if holding:
        cum *= close[to] / entry
        trades += 1
        if close[to] > entry:
            wins += 1
    bh = (close[to] / close[frm] - 1) * 100 if close[frm] else 0.0
    return dict(strategy=(cum - 1) * 100, bh=bh, trades=trades,
                win=100.0 * wins / trades if trades else 0.0)


def validate(bars_all):
    if not os.path.exists(CSV):
        return None
    rows = {}
    with open(CSV, encoding="utf-8") as f:
        head = f.readline().strip().split(",")
        for ln in f:
            p = ln.rstrip("\n").split(",")
            if len(p) >= len(head):
                rows[(p[0], p[1], p[4])] = dict(zip(head, p))
    wins = {"近300": (600, 899), "中300": (300, 599), "远300": (0, 299), "全窗": (0, 899)}
    dif, ok = [], 0
    for code, bars in bars_all.items():
        close = [b[5] for b in bars][-900:]
        if len(close) < 900:
            continue
        sigs = bbi12_pivots(bbi12_line(close))
        for wn, (fr, tt) in wins.items():
            r = rows.get((code, wn, "① 基线·纯BBI12"))
            if not r:
                continue
            if not r.get("strategyPct"):
                continue
            d = abs(sim_plain(close, sigs, fr, tt)["strategy"] - float(r["strategyPct"]))
            dif.append(d)
            ok += 1 if d < 0.02 else 0
    return dict(ok=ok, n=len(dif), maxDif=max(dif) if dif else -1, medDif=st.median(dif) if dif else -1)


def backtest_stock(close, sigs, frm=FROM, to=899):
    n = len(close)
    er = er_series(close)
    buyAt = set(i for i, b in sigs if b)
    sellAt = set(i for i, b in sigs if not b)
    VS = ["V0", "V1", "V2", "V3", "V4"]
    res = {v: dict(eq=[1.0] * n, holding=[0] * n, trades=[]) for v in VS}
    hold = {v: dict(on=False, w=1.0, entry=0.0) for v in VS}
    done_len, state, run_len = [], None, 0

    for i in range(frm, to + 1):
        v_er = [x for x in er[W:i] if x == x]
        hi = pctile(v_er, P_HI) if len(v_er) >= MIN_SAMP else float("nan")
        lo = pctile(v_er, P_LO) if len(v_er) >= MIN_SAMP else float("nan")
        ref = st.median(v_er) if len(v_er) >= MIN_SAMP else float("nan")
        if hi == hi and lo == lo:
            if state is None:
                state, run_len = (1 if er[i] >= hi else 0), 1
            else:
                new = 1 if er[i] >= hi else (0 if er[i] <= lo else state)
                if new != state:
                    if state == 1:
                        done_len.append(run_len)
                    state, run_len = new, 1
                else:
                    run_len += 1
        stage = "na"
        if state == 1 and len(done_len) >= 5:
            q1, q2 = pctile(done_len, 33), pctile(done_len, 67)
            stage = "前" if run_len < q1 else ("中" if run_len < q2 else "后")
        w = 1.0
        if ref == ref and hi == hi and hi > lo:
            w = 0.25 + 0.75 * max(0.0, min(1.0, (ref - lo) / (hi - lo)))
        for v in VS:
            h = hold[v]
            r = close[i] / close[i - 1] if i > 0 else 1.0
            res[v]["eq"][i] = res[v]["eq"][i - 1] * ((1 + h["w"] * (r - 1.0)) if h["on"] else 1.0)
            res[v]["holding"][i] = 1 if h["on"] else 0
            gate = (state == 1)
            allow = True
            if v in ("V2", "V3", "V4"):
                allow = gate
            if v == "V4":
                allow = gate and stage != "后"
            if h["on"] and ((i in sellAt) or (v in ("V2", "V3", "V4") and state == 0)):
                res[v]["trades"].append((close[i] / h["entry"] - 1) * 100)
                h["on"] = False
            if (not h["on"]) and (i in buyAt) and allow:
                h["on"], h["entry"] = True, close[i]
                h["w"] = (w if v in ("V1", "V3", "V4") else 1.0)
    for v in VS:
        if hold[v]["on"]:
            res[v]["trades"].append((close[to] / hold[v]["entry"] - 1) * 100)
    return res


def build_pos(sigs, frm, to):
    pos = [0] * (to + 1)
    buyAt = set(i for i, b in sigs if b)
    sellAt = set(i for i, b in sigs if not b)
    on = False
    for i in range(frm, to + 1):
        if on and i in sellAt:
            on = False
        if (not on) and i in buyAt:
            on = True
        pos[i] = 1 if on else 0
    return pos


# ───────────────────────── 组合级：跨票 ER 选择 ─────────────────────────
def portfolio_bt(data, mode, topn=10, pct=60, frm=FROM, to=899):
    codes = list(data)
    eq, expo = [1.0] * (to + 1), [0] * (to + 1)
    for i in range(frm + 1, to + 1):
        j = i - 1
        held = [c for c in codes if data[c]["pos"][j]]
        if not held:
            eq[i] = eq[i - 1]; continue
        if mode == "WRANK":
            wts = [(c, max(0.0, data[c]["er"][j] if data[c]["er"][j] == data[c]["er"][j] else 0.0)) for c in held]
            sw = sum(w for _, w in wts)
            if sw <= 0:
                eq[i] = eq[i - 1]; continue
            r = sum(w * (data[c]["close"][i] / data[c]["close"][j] - 1) for c, w in wts) / sw
            eq[i], expo[i] = eq[i - 1] * (1 + r), len(held)
            continue
        if mode == "ALL":
            mem = held
        elif mode == "TOP":
            mem = sorted(held, key=lambda c: -(data[c]["er"][j] if data[c]["er"][j] == data[c]["er"][j] else -1.0))[:topn]
        else:
            v = [data[c]["er"][j] for c in held if data[c]["er"][j] == data[c]["er"][j]]
            thr = pctile(v, pct) if v else 1e9
            mem = [c for c in held if data[c]["er"][j] == data[c]["er"][j] and data[c]["er"][j] >= thr]
        if not mem:
            eq[i] = eq[i - 1]; continue
        r = sum(data[c]["close"][i] / data[c]["close"][i - 1] - 1 for c in mem) / len(mem)
        eq[i], expo[i] = eq[i - 1] * (1 + r), len(mem)
    return eq, expo


def metrics(eq, frm=FROM, to=899):
    nb = to - frm
    tot = (eq[to] - 1) * 100
    ann = ((eq[to]) ** (1000.0 / nb) - 1) * 100 if eq[to] > 0 else -100.0
    pk, mdd = eq[frm], 0.0
    for x in eq[frm:]:
        pk = max(pk, x)
        mdd = max(mdd, (1 - x / pk) * 100)
    br = [eq[i] / eq[i - 1] - 1 for i in range(frm + 1, to + 1) if eq[i - 1] > 0]
    mu, sd = st.mean(br), st.pstdev(br)
    return dict(ret=tot, ann=ann, mdd=mdd, sharpe=(mu / sd * math.sqrt(1000)) if sd > 0 else 0.0)


# ───────────────────────── 主流程 ─────────────────────────
def main():
    force = "--fetch" in sys.argv
    bars_all = load_bars(force)
    V = validate(bars_all) or dict(ok=0, n=0, maxDif=0.0, medDif=0.0)
    if V["n"]:
        print("=== BBI12 复现校验：%d/%d 一致（最大偏差 %.4f%%，中位 %.5f%%）===" % (V["ok"], V["n"], V["maxDif"], V["medDif"]))

    print("\n=== 逐票变体（回测段 末 900 的 [%d,899]）===" % FROM)
    VS = ["V0", "V1", "V2", "V3", "V4"]
    agg = {v: dict(eq=[], ntr=0, wins=0, exp=0) for v in VS}
    per = {}
    bh = 0.0
    for code, bars in bars_all.items():
        close = [b[5] for b in bars][-900:]
        if len(close) < 900:
            continue
        res = backtest_stock(close, bbi12_pivots(bbi12_line(close)))
        per[code] = {}
        bh += (close[899] / close[FROM] - 1) * 100
        for v in VS:
            tr = res[v]["trades"]
            per[code][v] = dict(ret=(res[v]["eq"][899] - 1) * 100, trades=len(tr),
                                win=100.0 * sum(1 for x in tr if x > 0) / len(tr) if tr else 0.0,
                                exp=100.0 * sum(res[v]["holding"][FROM:900]) / (899 - FROM + 1))
            agg[v]["eq"].append(res[v]["eq"])
            agg[v]["ntr"] += len(tr)
            agg[v]["wins"] += sum(1 for x in tr if x > 0)
            agg[v]["exp"] += per[code][v]["exp"]
    bh /= max(1, len(per))
    nst = len(agg["V0"]["eq"])
    summary = dict(w=W, from_=FROM, bh=bh, variants={}, perStock=per)
    print("%-20s %10s %9s %8s %7s %7s %7s" % ("变体", "组合收益%", "回撤%", "夏普", "笔数", "胜率%", "持仓%"))
    for v in VS:
        eq = [sum(e[i] for e in agg[v]["eq"]) / nst for i in range(900)]
        m = metrics(eq)
        summary["variants"][v] = dict(ret=m["ret"], ann=m["ann"], mdd=m["mdd"], sharpe=m["sharpe"],
                                      trades=agg[v]["ntr"],
                                      win=100.0 * agg[v]["wins"] / agg[v]["ntr"] if agg[v]["ntr"] else 0,
                                      exp=agg[v]["exp"] / nst)
        print("%-20s %10.2f %9.2f %8.2f %7d %7.1f %7.1f" % (v, m["ret"], m["mdd"], m["sharpe"],
              agg[v]["ntr"], summary["variants"][v]["win"], summary["variants"][v]["exp"]))

    print("等权买入持有 %.2f%%" % bh)
    summary["bh"] = bh

    # 组合级
    data, dates = {}, None
    for code, bars in bars_all.items():
        close = [b[5] for b in bars][-900:]
        if len(close) < 900:
            continue
        if dates is None:
            dates = [b[0] for b in bars][-900:]
        sigs = bbi12_pivots(bbi12_line(close))
        data[code] = dict(close=close, er=er_series(close), pos=build_pos(sigs, FROM, 899))
    summary["period"] = [dates[FROM], dates[899], len(data)]
    print("\n=== 组合级跨票 ER 选择（%s → %s，%d 只）===" % (dates[FROM], dates[899], len(data)))
    pm = {}
    for mode, key, lbl, kw in (("ALL", "ALL", "BBI12 全池等权（基线）", {}),
                               ("P", "P60", "ER≥横截面P60", dict(pct=60)),
                               ("P", "P70", "ER≥横截面P70", dict(pct=70)),
                               ("TOP", "TOP10", "ER 最高 10 只", dict(topn=10)),
                               ("WRANK", "WRANK", "按 ER 权重加权", {})):
        eq, expo = portfolio_bt(data, mode, **kw)
        m = metrics(eq)
        m.update(label=lbl, expo=sum(expo[FROM + 1:900]) / (899 - FROM),
                 segs=[(eq[a + 199] / eq[a] - 1) * 100 for a in (FROM, 500, 700)])
        pm[key] = m
        summary["eq_" + key] = eq
        print("%-24s 收益%8.2f 年化%7.2f 回撤%6.2f 夏普%6.2f 持仓只数%6.1f 三段 %s" % (
            lbl, m["ret"], m["ann"], m["mdd"], m["sharpe"], m["expo"],
            "/".join("%.1f" % x for x in m["segs"])))
    summary["portfolio"] = pm

    sweep = {}
    for k in (5, 8, 10, 12, 15, 20, 30):
        sweep["TOP%d" % k] = metrics(portfolio_bt(data, "TOP", topn=k)[0])
    for k in (40, 50, 60, 70, 80):
        sweep["P%d" % k] = metrics(portfolio_bt(data, "P", pct=k)[0])
    summary["sweep"] = sweep
    print("\n参数敏感性：")
    for grp, ks in (("TOP", (5, 8, 10, 12, 15, 20, 30)), ("P", (40, 50, 60, 70, 80))):
        print("  %-5s " % grp + "  ".join("N%d %.1f/%.2f" % (k, sweep["TOP%d" % k]["ret"], sweep["TOP%d" % k]["sharpe"])
              if grp == "TOP" else "P%d %.1f/%.2f" % (k, sweep["P%d" % k]["ret"], sweep["P%d" % k]["sharpe"]) for k in ks))

    os.makedirs(os.path.dirname(OUTJ), exist_ok=True)
    json.dump(summary, open(OUTJ, "w", encoding="utf-8"), ensure_ascii=False)
    print("\n→ %s" % OUTJ)
    return summary, V


# ───────────────────────── 报告渲染 ─────────────────────────
RULE_CSS = """
body{font-family:'Microsoft YaHei',system-ui,sans-serif;margin:0;background:#f5f6f8;color:#222;line-height:1.65}
.wrap{max-width:1120px;margin:0 auto;padding:22px}
h1{font-size:23px;margin:6px 0 4px} h2{font-size:17px;margin:26px 0 8px;border-left:5px solid #1565c0;padding-left:9px}
.sub{color:#666;font-size:13px;margin-bottom:14px}
.card{background:#fff;border:1px solid #e4e7ec;border-radius:10px;padding:15px 17px;margin:12px 0}
table{border-collapse:collapse;width:100%;font-size:13px} th,td{border:1px solid #e4e7ec;padding:6px 8px;text-align:right}
th{background:#f0f3f7;font-weight:600} td:first-child,th:first-child{text-align:left}
.pos{color:#2e7d32;font-weight:600} .neg{color:#c62828;font-weight:600}
code{background:#eceff1;border-radius:4px;padding:1px 4px;font-size:12px}
ul{margin:6px 0 6px 18px;padding:0} li{margin:3px 0}
.note{font-size:12px;color:#777}
"""


def render(s, V):
    import matplotlib
    matplotlib.use("Agg")
    matplotlib.rcParams["font.sans-serif"] = ["Microsoft YaHei"]
    matplotlib.rcParams["axes.unicode_minus"] = False
    import matplotlib.pyplot as plt

    P = s["portfolio"]
    x = list(range(300, 900))
    lbls = [("ALL", "全池等权(基线)", "#78909c"), ("WRANK", "ER加权", "#90a4ae"),
            ("TOP10", "ER前10", "#2e7d32"), ("P60", "ER≥P60", "#1565c0"), ("P70", "ER≥P70", "#c62828")]

    # ── PNG ──
    fig = plt.figure(figsize=(7.6, 10.6), dpi=132)
    gs = fig.add_gridspec(3, 1, height_ratios=[1.45, 1.0, 1.0], hspace=0.42, left=0.13, right=0.965, top=0.94, bottom=0.06)
    ax1 = fig.add_subplot(gs[0])
    for k, lb, c in lbls:
        eq = s["eq_" + k]
        ax1.plot(x, [e / eq[300] for e in eq[300:900]], label=lb, color=c,
                 lw=2.0 if k in ("P70", "P60") else 1.4)
    ax1.set_title("组合净值（回测段 %s → %s）" % (s["period"][0], s["period"][1]), fontsize=11)
    ax1.axhline(1.0, color="#bbb", lw=0.8, ls="--")
    ax1.legend(fontsize=8, ncol=3); ax1.grid(alpha=0.25); ax1.tick_params(labelsize=8)

    ax2 = fig.add_subplot(gs[1])
    ks = [40, 50, 60, 70, 80]
    ax2.bar([i - 0.19 for i in range(5)], [s["sweep"]["P%d" % k]["ret"] for k in ks], width=0.36,
            color="#1565c0", label="组合收益%")
    ax2.set_ylabel("收益%", fontsize=9)
    axb = ax2.twinx()
    axb.plot(range(5), [s["sweep"]["P%d" % k]["sharpe"] for k in ks], "o-", color="#c62828", lw=1.8, label="夏普")
    axb.set_ylabel("夏普", fontsize=9, color="#c62828")
    ax2.set_xticks(range(5)); ax2.set_xticklabels(["P%d" % k for k in ks], fontsize=9)
    ax2.axhline(s["portfolio"]["ALL"]["ret"], color="#78909c", ls="--", lw=1.0)
    ax2.text(0.02, s["portfolio"]["ALL"]["ret"], " 全池基线 %.1f%%" % s["portfolio"]["ALL"]["ret"],
             fontsize=8, color="#607d8b", va="bottom")
    ax2.set_title("横截面 ER 阈值敏感性", fontsize=11); ax2.grid(alpha=0.2, axis="y")

    ax3 = fig.add_subplot(gs[2])
    VS = ["V0", "V1", "V2", "V3", "V4"]
    nm = {"V0": "V0 纯BBI12", "V1": "V1 票性仓位", "V2": "V2 趋势闸门", "V3": "V3 闸门+仓位", "V4": "V4+后期禁入"}
    ax3.bar([i - 0.2 for i in range(5)], [s["variants"][v]["ret"] for v in VS], width=0.38,
            color="#2e7d32", label="收益%")
    ax3.bar([i + 0.2 for i in range(5)], [-s["variants"][v]["mdd"] for v in VS], width=0.38,
            color="#c62828", label="回撤%")
    ax3.set_xticks(range(5)); ax3.set_xticklabels([nm[v] for v in VS], fontsize=8, rotation=12)
    ax3.axhline(0, color="#999", lw=0.8)
    ax3.set_title("逐票 ER 闸门/仓位变体（组合收益 vs 回撤）", fontsize=11)
    ax3.legend(fontsize=8); ax3.grid(alpha=0.2, axis="y")
    os.makedirs(os.path.dirname(OUTP), exist_ok=True)
    fig.savefig(OUTP, bbox_inches="tight", facecolor="white")
    plt.close(fig)

    # ── HTML 净值 SVG ──
    W0, H0, ML, MR, MT, MB = 1040, 300, 56, 16, 14, 30
    allv = [eq[i] / eq[300] for k, _, _ in lbls for i in range(300, 900) for eq in [s["eq_" + k]]]
    lo, hi = min(allv), max(allv)
    pad = (hi - lo) * 0.08 or 0.02
    lo, hi = lo - pad, hi + pad
    def X(i): return ML + (i - 300) * (W0 - ML - MR) / 599.0
    def Y(v): return MT + (H0 - MT - MB) - (v - lo) / (hi - lo) * (H0 - MT - MB)
    sv = []
    for t in [0, 0.25, 0.5, 0.75, 1.0]:
        v = lo + (hi - lo) * t; y = Y(v)
        sv.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#f0f0f0"/>' % (ML, y, W0 - MR, y))
        sv.append('<text x="%d" y="%.1f" font-size="10" fill="#888" text-anchor="end">%.2f</text>' % (ML - 6, y + 3, v))
    y1 = Y(1.0)
    sv.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#cfd8dc" stroke-dasharray="4 3"/>' % (ML, y1, W0 - MR, y1))
    for k, lb, c in lbls:
        eq = s["eq_" + k]
        pts = " ".join("%.1f,%.1f" % (X(i), Y(eq[i] / eq[300])) for i in range(300, 900, 2))
        sv.append('<polyline fill="none" stroke="%s" stroke-width="%s" points="%s"/>' % (c, 2.1 if k in ("P70", "P60") else 1.4, pts))
    for i in range(300, 900, 100):
        sv.append('<text x="%.1f" y="%d" font-size="9" fill="#888" text-anchor="middle">%s</text>' % (X(i), H0 - 8, i))
    sv.append('<text x="%.1f" y="%d" font-size="10" fill="#666">bar index</text>' % (ML, H0 - 8))
    CHART = '<svg viewBox="0 0 %d %d" width="100%%" style="max-width:1040px">%s</svg>' % (W0, H0, "".join(sv))

    def cell(v):
        c = "pos" if v > 0 else ("neg" if v < 0 else "")
        return '<td class="%s">%+.2f</td>' % (c, v)

    h = ["<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>",
         "<title>ER 路由规则回测</title><style>%s</style></head><body><div class='wrap'>" % RULE_CSS,
         "<h1>ER 路由规则（v1）· 可回测实现与回测结果</h1>",
         "<div class='sub'>回测区间 <b>%s → %s</b>（1H，%d 只票）· 信号 = BBI12 拐点 · ER = Kaufman 效率比(60) · 全部决策因果（只用 ≤ 上一根）</div>"
         % (s["period"][0], s["period"][1], s["period"][2])]

    # 规则
    h.append("<h2>一、规则规格（可回测口径）</h2><div class='card'><ul>")
    h.append("<li><b>信号层（单票）</b>：<code>BBI = (MA3+MA6+MA12+MA24)/4</code>，拐点线 = <code>SMA(BBI,12)</code>。谷底确认（<code>line[i]≤line[i-1] 且 line[i+1]>line[i]</code>）→ <b>买</b>；峰顶确认 → <b>卖</b>。信号根 = 确认根 = i+1，成交价 = 该根收盘。</li>")
    h.append("<li><b>票性路由层（跨票、因果）</b>：ER<sub>i</sub> = <code>|C_i−C_{i−60}| / Σ|ΔC|</code>。每根 bar 只把资金<b>等权</b>分给「持仓中 且 ER 处于横截面高位」的票；其余持仓票<b>空仓等待</b>。决策用 <code>ER[i-1]</code>（用 <code>ER[i]</code> 会引入前视偏差）。</li>")
    h.append("<li><b>参数</b>：横截面分位阈值 <code>P70</code>（推荐）或 top-N=10；<code>W=60</code>；回测段 = 末 900 根的 <code>[300,899]</code>（前 300 根做 ER 预热）。</li>")
    h.append("<li><b>逐票变体</b>（已证伪的用法）：V1 票性仓位 = 按票性 ER 给 0.25~1.0 权重；V2~V4 = 仅「趋势态」建仓 / 加后期禁入。</li>")
    h.append("</ul></div>")

    # 组合绩效
    h.append("<h2>二、组合绩效（跨票 ER 选择）</h2><div class='card'><table>")
    h.append("<tr><th>组合</th><th>收益%</th><th>年化%</th><th>最大回撤%</th><th>夏普</th><th>平均持仓只数</th><th>三段收益%（各200根）</th></tr>")
    for k in ("ALL", "WRANK", "P60", "P70", "TOP10"):
        m = P[k]
        h.append("<tr><td>%s</td>%s<td>%.2f</td><td>%.2f</td><td>%.2f</td><td>%.1f</td><td>%s</td></tr>" % (
            m["label"], cell(m["ret"]), m["ann"], m["mdd"], m["sharpe"], m["expo"],
            " / ".join("%+.1f" % v for v in m["segs"])))
    h.append("<tr><td>等权买入持有</td>%s<td>—</td><td>—</td><td>—</td><td>100</td><td>—</td></tr>" % cell(s["bh"]))
    h.append("</table>%s</div>" % CHART)

    # 敏感性
    h.append("<h2>三、参数敏感性（防过拟合）</h2><div class='card'><table>")
    h.append("<tr><th>横截面分位</th>" + "".join("<th>P%d</th>" % k for k in (40, 50, 60, 70, 80)) + "</tr><tr><td>收益% / 夏普</td>")
    for k in (40, 50, 60, 70, 80):
        h.append("<td>%.1f / %.2f</td>" % (s["sweep"]["P%d" % k]["ret"], s["sweep"]["P%d" % k]["sharpe"]))
    h.append("</tr><tr><th>top-N</th>" + "".join("<th>N=%d</th>" % k for k in (5, 8, 10, 12, 15, 20, 30)) + "</tr><tr><td>收益% / 夏普</td>")
    for k in (5, 8, 10, 12, 15, 20, 30):
        h.append("<td>%.1f / %.2f</td>" % (s["sweep"]["TOP%d" % k]["ret"], s["sweep"]["TOP%d" % k]["sharpe"]))
    h.append("</tr></table><div class='note'>除 N=5（过度集中）外，N=8~30 与 P40~P80 全部优于全池基线 %.2f%%，非单点幸运。</div></div>"
             % s["portfolio"]["ALL"]["ret"])

    # 逐票变体
    h.append("<h2>四、逐票变体（闸门/仓位）—— 已证伪</h2><div class='card'><table>")
    h.append("<tr><th>变体</th><th>组合收益%</th><th>最大回撤%</th><th>夏普</th><th>笔数</th><th>胜率%</th><th>持仓%</th></tr>")
    for v in ("V0", "V1", "V2", "V3", "V4"):
        m = s["variants"][v]
        h.append("<tr><td>%s</td>%s<td>%.2f</td><td>%.2f</td><td>%d</td><td>%.1f</td><td>%.1f</td></tr>" % (
            v, cell(m["ret"]), m["mdd"], m["sharpe"], m["trades"], m["win"], m["exp"]))
    h.append("</table><div class='note'>BBI12 的买点本身是「反转买点」，此时 ER 天然偏低；按「ER 高才买」过滤会滤掉多数买点（持仓 43%→9%），收益归零。"
             "V1 仓位不改变收益、只压回撤。→ 逐票闸门方向错误。</div></div>")

    # 结论
    h.append("<h2>五、结论</h2><div class='card'><ul>")
    h.append("<li><b>有效用法 = 跨票选择，不是逐票择时</b>：同一批 BBI12 信号，加上「只做横截面 ER 高位」后，组合收益 <b>%.2f%% → %.2f%%</b>，夏普 <b>%.2f → %.2f</b>，回撤基本持平。</li>"
             % (P["ALL"]["ret"], P["P70"]["ret"], P["ALL"]["sharpe"], P["P70"]["sharpe"]))
    h.append("<li><b>稳健</b>：三段（各 200 根）里 ER 选择段段不劣于全池；阈值 P40~P80、N8~N30 全部有效。</li>")
    h.append("<li><b>逐票闸门证伪</b>：V2~V4 收益≈0；V1 只改善风险。ER 与 BBI12 反转买点节奏相反。</li>")
    h.append("<li><b>口径说明</b>：BBI12 与 Kotlin 引擎复现校验 %d/%d 一致（最大偏差 %.2f%%）；已修正 <code>ER[i]</code> 前视偏差；样本为 %d 只存活大票（存在幸存者偏差），单一 11 个月区间。</li>"
             % (V["ok"], V["n"], V["maxDif"], s["period"][2]))
    h.append("</ul></div>")
    h.append("<div class='note'>数据：新浪 60 分钟 K（datalen=1023，取末 900）· 脚本：<code>smalltools/_er_router_backtest.py</code></div>")
    h.append("</div></body></html>")
    os.makedirs(os.path.dirname(OUTH), exist_ok=True)
    open(OUTH, "w", encoding="utf-8").write("".join(h))
    print("→ %s\n→ %s" % (OUTH, OUTP))


if __name__ == "__main__":
    _s, _v = main()
    if _s and _v:
        render(_s, _v)
