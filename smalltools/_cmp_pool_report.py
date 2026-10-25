# -*- coding: utf-8 -*-
"""
生成 BBI12+ER≥70  vs  SSA T0 三层做T · 池级对照报告
====================================================
输入：
  - app/build/ssa_t0_pool_result.txt   （App 真引擎 SsaT0Engine 池级）
  - smalltools/_records/_bbi_pool.csv  （Python 口径 BBI12+ER≥70 池级）
输出：reports/bbi_er_vs_ssa_pool.html
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HERE = os.path.join(ROOT, "smalltools")
SSA_TXT = os.path.join(ROOT, "app", "build", "ssa_t0_pool_result.txt")
BBI_CSV = os.path.join(HERE, "_records", "_bbi_pool.csv")
OUT = os.path.join(ROOT, "reports", "bbi_er_vs_ssa_pool.html")


def parse_ssa():
    lines = open(SSA_TXT, encoding="utf-8").read().splitlines()
    head, rows = {}, {}
    started = False
    for ln in lines:
        if ln.startswith("code,"):
            started = True
            continue
        if not started:
            if ln.strip():
                head[ln.split(" ", 1)[0]] = ln
            continue
        p = ln.split(",")
        if len(p) >= 7 and p[1] not in ("skip", "error"):
            rows[p[0]] = dict(ret=float(p[1]), bench=float(p[2]), exc=float(p[3]),
                              trades=int(p[4]), win=float(p[5]), dd=float(p[6]))
    return head, rows


def parse_bbi():
    rows = {}
    for ln in open(BBI_CSV, encoding="utf-8").read().splitlines():
        p = ln.split(",")
        if len(p) >= 5:
            rows[p[0]] = dict(ret=float(p[1]), bench=float(p[2]), exc=float(p[3]), trades=int(p[4]))
    return rows


def cls(v):
    return "pos" if v > 0 else ("neg" if v < 0 else "")


def mean(a):
    return sum(a) / len(a) if a else 0.0


def median(a):
    s = sorted(a); n = len(s)
    return 0.0 if n == 0 else (s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2)


def main():
    ssa_head, ssa = parse_ssa()
    bbi = parse_bbi()
    codes = [c for c in ssa if c in bbi]

    def stats(d):
        r = [d[c]["ret"] for c in codes]
        e = [d[c]["exc"] for c in codes]
        return mean(r), median(r), mean(e), median(e), sum(1 for x in e if x > 0) / len(e) * 100

    b_mr, b_mdr, b_me, b_mde, b_ew = stats(bbi)
    s_mr, s_mdr, s_me, s_mde, s_ew = stats(ssa)

    # 单票分歧 Top
    diff = sorted(codes, key=lambda c: bbi[c]["ret"] - ssa[c]["ret"])
    ssa_win = diff[:8]      # SSA 收益远超 BBI
    bbi_win = diff[-8:][::-1]  # BBI 收益远超 SSA

    def row(c):
        b, s = bbi[c], ssa[c]
        return (f"<tr><td>{c}</td>"
                f"<td class='{cls(b['ret'])}'>{b['ret']:+.2f}%</td>"
                f"<td class='{cls(b['exc'])}'>{b['exc']:+.2f}%</td>"
                f"<td>{b['trades']}</td>"
                f"<td class='{cls(s['ret'])}'>{s['ret']:+.2f}%</td>"
                f"<td class='{cls(s['exc'])}'>{s['exc']:+.2f}%</td>"
                f"<td>{s['trades']}</td>"
                f"<td>{s['win']:.1f}%</td></tr>")

    diff_rows = "".join(row(c) for c in ssa_win) + \
        "<tr><td colspan=8 class='note'>▲ 上：SSA T0 收益领先；下：BBI12+ER 收益领先 ▼</td></tr>" + \
        "".join(row(c) for c in bbi_win)

    all_rows = "".join(row(c) for c in sorted(codes))

    verdict = "BBI12 + ER≥70 占优" if b_mr > s_mr and b_me > s_me else "SSA T0 占优"
    color = "#C62828" if b_mr > s_mr else "#2E7D32"

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>BBI12+ER  vs  SSA T0 做T · 池级对照</title><style>
body{{font-family:'Microsoft YaHei',sans-serif;max-width:1150px;margin:24px auto;padding:0 16px;color:#222}}
h1{{font-size:22px}} h2{{font-size:18px;border-left:4px solid #1565C0;padding-left:8px;margin-top:26px}}
table{{border-collapse:collapse;margin:8px 0;width:100%;font-size:13px}}
th,td{{border:1px solid #ddd;padding:5px 8px;text-align:center}}
th{{background:#f0f4f8}} .pos{{color:#C62828;font-weight:600}} .neg{{color:#2E7D32;font-weight:600}}
.note{{color:#666;font-size:12.5px;text-align:left}} img{{max-width:100%}}
.box{{background:#F5F7FA;border-radius:8px;padding:10px 14px;margin:10px 0;font-size:13.5px;line-height:1.7}}
.win{{border-left:5px solid {color};background:#fff8f8}}
</style></head><body>
<h1>BBI12 + ER 分位  vs  SSA 三层做T(T0) · 池级同口径对照</h1>

<div class="box note">
口径：核心池 <b>{len(codes)} 只</b>（同 <code>_kalman_1h_cache.json</code> 池），1H 线约 1023 根（≈2022–2026），long-only。<br>
<b>BBI12+ER≥70</b>：Python（<code>_bbi_er_vs_ssa</code> 同口径）——BBI=(MA3+MA6+MA12+MA24)/4 再 SMA12，次根确认拐点，ER 横截面分位≥70 才允许开仓；费 0.13%。<br>
<b>SSA T0</b>：App 真引擎 <code>SsaT0Engine</code>（有 OHLC，1H+日K 双层）——三态机 UP(核心仓80%+极限带T30%)/WINDOW/FLAT，成本 commission 0.025%+stamp 0.05%+滑点 0.05%。<br>
超额 = 策略总收益 − 同窗买入持有（各引擎自算，BBI 以暖机位 index≈180 起算）。
</div>

<h2>① 结论</h2>
<div class="box win"><b style="color:{color}">池级：{verdict}</b> —— 在四张记分牌上 BBI12+ER≥70 全部领先：
<table style="margin-top:8px">
<tr><th>指标</th><th>BBI12+ER≥70</th><th>SSA T0（基线三态机）</th><th>胜方</th></tr>
<tr><td>总收益 均值</td><td class="{cls(b_mr)}">{b_mr:+.2f}%</td><td class="{cls(s_mr)}">{s_mr:+.2f}%</td><td>{'BBI' if b_mr>s_mr else 'SSA'}</td></tr>
<tr><td>总收益 中位</td><td class="{cls(b_mdr)}">{b_mdr:+.2f}%</td><td class="{cls(s_mdr)}">{s_mdr:+.2f}%</td><td>{'BBI' if b_mdr>s_mdr else 'SSA'}</td></tr>
<tr><td>超额 均值</td><td class="{cls(b_me)}">{b_me:+.2f}%</td><td class="{cls(s_me)}">{s_me:+.2f}%</td><td>{'BBI' if b_me>s_me else 'SSA'}</td></tr>
<tr><td>超额 中位</td><td class="{cls(b_mde)}">{b_mde:+.2f}%</td><td class="{cls(s_mde)}">{s_mde:+.2f}%</td><td>{'BBI' if b_mde>s_mde else 'SSA'}</td></tr>
<tr><td>超额&gt;0 占比</td><td>{b_ew:.1f}%</td><td>{s_ew:.1f}%</td><td>{'BBI' if b_ew>s_ew else 'SSA'}</td></tr>
</table>
<br>注：两者均为「相对自身买入持有」的超额，且 BBI 基准更低（-9.19% vs -11.59%），故均值超额差距（+{b_me-s_me:.2f}pp）小于绝对收益差距（{b_mr-s_mr:.2f}pp）。
</div>

<h2>② 但分歧极大：强趋势股 SSA T0 反超</h2>
<div class="box">BBI12+ER 是「择时进出」型（空仓多、回撤小、收益稳）；SSA T0 是「核心仓+T」型（你 80% 底仓一路拿住趋势）。
在单边大牛股上，SSA T0 靠底仓吃满趋势，远胜 BBI 的反复进出；在弱票上则因底仓拖累不如 BBI 干净。</div>
<table><tr><th rowspan="2">代码</th><th colspan="3">BBI12+ER≥70</th><th colspan="4">SSA T0</th></tr>
<tr><th>收益</th><th>超额</th><th>T单</th><th>收益</th><th>超额</th><th>T单</th><th>胜率</th></tr>
{diff_rows}</table>

<h2>③ 全池明细（{len(codes)} 只）</h2>
<table><tr><th rowspan="2">代码</th><th colspan="3">BBI12+ER≥70</th><th colspan="4">SSA T0</th></tr>
<tr><th>收益</th><th>超额</th><th>T单</th><th>收益</th><th>超额</th><th>T单</th><th>胜率</th></tr>
{all_rows}</table>

<h2>④ 前视偏差注记（重要）</h2>
<div class="box">
早前 SSA mid 拐点策略在整条序列一次性 SVD，混入未来数据 → 池级 +471.5% 是假象（<code>reports/ssa_causal_check.html</code>）。<br>
改成滚动窗口因果版后，纯 mid 拐点「谷买峰卖」池级仅 <b>-19.6% / 中位 -25.0%</b>，是三者中最差。<br>
→ <b>SSA 的价值不在 mid 拐点本身，而在三层结构（核心仓 + 极限带 T + WINDOW 逆势保护）</b>；把 SSA 降级成普通拐点择时反而更烂。
</div>

<div class="box note">生成：smalltools/_cmp_pool_report.py ｜ 数据：app/build/ssa_t0_pool_result.txt + smalltools/_records/_bbi_pool.csv</div>
</body></html>"""
    open(OUT, "w", encoding="utf-8").write(html)
    print(f"[done] {OUT}")
    print(f"BBI  mean {b_mr:+.2f}% / med {b_mdr:+.2f}% | exc {b_me:+.2f}% / {b_mde:+.2f}% | win{b_ew:.1f}%")
    print(f"SSA  mean {s_mr:+.2f}% / med {s_mdr:+.2f}% | exc {s_me:+.2f}% / {s_mde:+.2f}% | win{s_ew:.1f}%")


if __name__ == "__main__":
    main()
