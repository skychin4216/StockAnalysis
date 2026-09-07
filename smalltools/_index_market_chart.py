# -*- coding: utf-8 -*-
"""大盘4指数涨跌幅叠加图（2026-09-06，开盘前/收盘后推送配图）。

把 上证指数 / 深证成指 / 科创50 / 创业板指 放到同一张图：
各指数以窗口首日收盘为基准转为「累计涨跌幅%」(起点=0)，画在同一百分比
坐标上——这样曲线的相对高低只表达『谁涨得更多』，不会与真实点位混淆
（科创50 点位虽低、若涨得多线就会在上证上方）；图例同时标注各指数最新
实际点位，避免把涨跌幅轴误读为点位。走势口径与 APK「K线趋势→大盘K线」一致。

数据源：东财日K优先、腾讯回退；失败自动回退本地缓存 data/_index_market.json。

用法：
  python _index_market_chart.py --days 55         # 生成高分辨率 PNG + 同名交互 HTML
  python _index_market_chart.py --text            # 只输出强弱结论文本

2026-09-07 三次修订（用户要求按真实点位呈现）：图像从『归一化叠加一条线』改为
「2×2 四面板 日K柱状图」——每个指数独立面板，Y 轴刻度为实际点位（深证≈13700、
上证≈3900、创业板≈3400 各自按真实值绘制，不再归一化），直观反映各指数数值量级；
K线柱红涨绿跌 + MA5 均线，面板标题标注最新实际点位与 5 日涨跌幅。
PNG 高分辨率推企微；HTML(plotly) 支持滚轮缩放/框选放大/拖拽平移/悬停数值。
（注：09-06 的『归一化%=100 起点叠加线』版本因用户要求按实际值绘图而作废。）
"""
import argparse
import datetime as _dt
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from backtest_guangmo import fetch_east, fetch_tencent  # noqa: E402

# 2026-09-07 用户指定组合：上证 + 科创50 + 沪深300 + 创业板
# （原「深证成指」点位过高≈1.3万，与上证/科创/创业约 0.3~0.9万 放在同尺寸面板里观感失衡，
#   换成点位量级相近的沪深300。）
INDEXES = [
    ("sh000001", "上证指数"),
    ("sh000688", "科创50"),
    ("sh000300", "沪深300"),
    ("sz399006", "创业板指"),
]
COLORS = {
    "上证指数": "#E53935",
    "科创50": "#7E57C2",
    "沪深300": "#FB8C00",
    "创业板指": "#43A047",
}
PNG_PATH = os.path.join(HERE, "data", "_index_market.png")
CACHE_PATH = os.path.join(HERE, "data", "_index_market.json")
MAX_KEEP = 150          # 本地缓存每指数最多保留根数
RET_NATURAL_DAYS = 420  # 每次在线拉取的自然日跨度（≈60 个交易日，足够 55 窗口）


def _load_cache():
    try:
        with open(CACHE_PATH, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"codes": {}}


def _save_cache(payload):
    try:
        os.makedirs(os.path.dirname(CACHE_PATH), exist_ok=True)
        tmp = CACHE_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
        os.replace(tmp, CACHE_PATH)
    except OSError:
        pass


def fetch_snaps(code):
    """在线拉最近日K（东财优先，腾讯回退）。失败返回 []。"""
    today = _dt.date.today()
    beg = (today - _dt.timedelta(days=RET_NATURAL_DAYS)).strftime("%Y%m%d")
    end = today.strftime("%Y%m%d")
    try:
        _, snaps = fetch_east(code, beg, end)
        if snaps:
            return snaps
    except Exception:
        pass
    try:
        _, snaps = fetch_tencent(code, beg, end)
        return snaps or []
    except Exception:
        return []


def refresh(force_online=True):
    """拉取4指数并合并本地缓存。返回 {code: {"name", "snaps"}}（snaps 时间升序）。"""
    cache = _load_cache()
    old_codes = cache.get("codes") or {}
    out = {}
    for code, name in INDEXES:
        snaps = fetch_snaps(code) if force_online else []
        old = sorted((old_codes.get(code) or {}).get("snaps", []),
                     key=lambda s: s["date"])
        if snaps:
            merged = {s["date"]: s for s in old}
            merged.update({s["date"]: s for s in snaps})
            snaps = sorted(merged.values(), key=lambda s: s["date"])[-MAX_KEEP:]
        else:
            snaps = old[-MAX_KEEP:]
        out[code] = {"name": name, "snaps": snaps}
    cache["codes"] = out
    _save_cache(cache)
    return out


def _build_series(out, days=55):
    """取4指数公共交易日窗口，转『累计涨跌幅%』收盘序列。

    返回 (common_dates, series)：
      common_dates: [str] 升序日期
      series: [{label, color, values, latest, last_close, p5, p10}]
              values 为 (收盘/窗口首日收盘-1)*100（起点=0，表达区间涨幅强弱）；
              last_close 为最新实际点位（供图例标注，避免涨幅轴被误读成点位）。
    公共日期不足 5 根时返回 (None, [])。
    """
    by_code = {c: d["snaps"] for c, d in out.items()}
    date_sets = [set(s["date"] for s in by_code[c]) for c, _ in INDEXES if by_code[c]]
    if not date_sets:
        return None, []
    common = sorted(set.intersection(*date_sets))
    if len(common) < 5:
        return None, []
    window = common[-days:]
    win_set = set(window)
    series = []
    for code, name in INDEXES:
        snaps = [s for s in by_code[code] if s["date"] in win_set]
        base = snaps[0]["close"]
        if base <= 0:
            continue
        series.append({
            "label": name,
            "color": COLORS.get(name, "#1976D2"),
            "values": [(s["close"] / base - 1) * 100.0 for s in snaps],
            "snaps": snaps,   # 窗口内 OHLC 全量，供日K柱状图按真实点位绘制
            "latest": snaps[-1]["close"],
            "last_close": snaps[-1]["close"],
            "p5": (snaps[-1]["close"] / snaps[-6]["close"] - 1) * 100 if len(snaps) > 5 else 0.0,
            "p10": (snaps[-1]["close"] / snaps[-11]["close"] - 1) * 100 if len(snaps) > 10 else 0.0,
        })
    return window, series


def _fmt_pt(v):
    """指数点位千分位，如 3372.14 → '3,372'。"""
    try:
        return "{:,.0f}".format(float(v))
    except (TypeError, ValueError):
        return "-"


def _draw_png(window, series, out, hi_res=True):
    """把已算好的 (window, series) 绘制成「2×2 四面板 日K柱状图」PNG。

    每面板一个指数：红涨绿跌的日K蜡烛柱(按真实点位 OHLC) + MA5 均线，
    Y 轴为实际点位刻度（深证≈13700 / 上证≈3900 各自独立量级，不归一化）。
    hi_res=True：figsize 16×9.2 @ dpi 180 → ≈2880×1656，高像素便于企微放大。
    """
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
    plt.rcParams["axes.unicode_minus"] = False

    n = len(window)
    fig, axes = plt.subplots(2, 2, figsize=(16, 9.2), dpi=180)
    UP, DN = "#E53935", "#2E9E44"   # A股习惯：红涨绿跌
    for ax, s in zip([a for row in axes for a in row], series):
        snaps = s["snaps"]
        xs = list(range(n))
        up = [i for i, k in enumerate(snaps) if k["close"] >= k["open"]]
        dn = [i for i in range(n) if i not in up]
        for grp, col in ((up, UP), (dn, DN)):
            if not grp:
                continue
            gx = [xs[i] for i in grp]
            ax.vlines(gx, [snaps[i]["low"] for i in grp],
                      [snaps[i]["high"] for i in grp],
                      color=col, lw=0.9, alpha=0.95)
            ax.bar(gx, [snaps[i]["close"] - snaps[i]["open"] for i in grp],
                   bottom=[snaps[i]["open"] for i in grp],
                   width=0.72, color=col, alpha=0.95)
        ma5 = []
        for i in range(n):
            w = snaps[max(0, i - 4):i + 1]
            ma5.append(sum(k["close"] for k in w) / len(w))
        ax.plot(xs, ma5, color="#1976D2", lw=1.4, label="MA5")
        tick_step = max(1, n // 8)
        tix = list(range(0, n, tick_step))
        ax.set_xticks(tix)
        ax.set_xticklabels([window[i][5:] for i in tix], fontsize=10)
        ax.set_title("%s %s · 5日%+.2f%%" % (s["label"], _fmt_pt(s["last_close"]), s["p5"]),
                     fontsize=13, loc="left")
        ax.yaxis.set_major_formatter(
            plt.FuncFormatter(lambda v, _: "{:,.0f}".format(v)))
        ax.grid(alpha=0.25, lw=0.7, ls=":")
        ax.legend(loc="upper left", fontsize=9, framealpha=0.6)
    fig.suptitle("大盘4指数 日K柱状图(实际点位, 红涨绿跌, 含MA5) 截至 %s" % window[-1],
                 fontsize=16, y=0.995)
    fig.tight_layout(rect=(0, 0, 1, 0.97))
    fig.savefig(out, dpi=fig.dpi)
    wpx, hpx = fig.get_size_inches()[0] * fig.dpi, fig.get_size_inches()[1] * fig.dpi
    plt.close(fig)
    print("大盘图已生成: %s (%d 根, %s, %dx%d)" % (out, n, window[-1], wpx, hpx))
    return True


def _export_html(window, series, out_html):
    """导出「2×2 四面板 日K蜡烛」交互 HTML（plotly 离线）。

    每个面板按真实点位画蜡烛图(红涨绿跌) + MA5 虚线；支持滚轮/框选缩放、
    拖拽平移、悬停数值。企微推送仍走上面的高分辨率 PNG。
    """
    try:
        import plotly.graph_objects as go
        from plotly.subplots import make_subplots
    except Exception:  # noqa: BLE001 - plotly 缺失仅跳过 HTML
        return False
    n = len(window)
    positions = [(1, 1), (1, 2), (2, 1), (2, 2)]
    fig = make_subplots(
        rows=2, cols=2,
        subplot_titles=["%s %s · 5日%+.2f%%"
                        % (s["label"], _fmt_pt(s["last_close"]), s["p5"])
                        for s in series],
        vertical_spacing=0.13, horizontal_spacing=0.07)
    for s, (r, c) in zip(series, positions):
        snaps = s["snaps"]
        fig.add_trace(go.Candlestick(
            x=[k["date"] for k in snaps],
            open=[k["open"] for k in snaps],
            high=[k["high"] for k in snaps],
            low=[k["low"] for k in snaps],
            close=[k["close"] for k in snaps],
            name=s["label"],
            increasing_line_color="#E53935", increasing_fillcolor="#E53935",
            decreasing_line_color="#2E9E44", decreasing_fillcolor="#2E9E44",
            showlegend=False), row=r, col=c)
        ma = []
        for i in range(n):
            w = snaps[max(0, i - 4):i + 1]
            ma.append(sum(k["close"] for k in w) / len(w))
        fig.add_trace(go.Scatter(
            x=[k["date"] for k in snaps], y=ma, mode="lines",
            line=dict(color="#1976D2", width=1.4, dash="dot"),
            name="MA5", showlegend=False), row=r, col=c)
        fig.update_xaxes(type="category", nticks=8, tickformat="%m-%d",
                         rangeslider_visible=False, row=r, col=c)
    fig.update_layout(
        title=dict(text="大盘4指数 日K柱状图(实际点位, 红涨绿跌, 含MA5) 截至 %s"
                       % window[-1], font=dict(size=18)),
        template="plotly_white",
        height=920,
        hovermode="x unified",
        dragmode="zoom",
        margin=dict(l=40, r=20, t=80, b=40))
    fig.write_html(out_html, include_plotlyjs="inline")
    print("大盘交互HTML已导出: %s" % out_html)
    return True


def _render_artifacts(window, series, out):
    """生成 PNG（高分辨率）+ 同名 .html（交互）。返回 (ok_png, png_path)。"""
    if not window or not series:
        print("指数数据不足，跳过绘图")
        return False, out
    ok = _draw_png(window, series, out)
    html = os.path.splitext(out)[0] + ".html"
    try:
        _export_html(window, series, html)
    except Exception as e:  # noqa: BLE001
        print("交互HTML导出失败:", type(e).__name__, e)
    return ok, out


def render_png(out=None, days=55):
    """刷新数据并绘制 data/_index_market.png。返回 (ok, png_path)。"""
    out = out or PNG_PATH
    try:
        data = refresh()
        window, series = _build_series(data, days)
        return _render_artifacts(window, series, out)
    except Exception as e:  # noqa: BLE001
        print("大盘图生成失败:", type(e).__name__, e)
        return False, out


def render_from_snaps(index_map, out=None, days=55):
    """离线渲染：直接给 {code: {"name","snaps"}}（snaps 已按日期截断到目标日），
    不联网。回放/历史推图用；口径与 render_png 完全一致。返回 (ok, png_path)。"""
    out = out or PNG_PATH
    try:
        window, series = _build_series(index_map, days)
        return _render_artifacts(window, series, out)
    except Exception as e:  # noqa: BLE001
        print("大盘图生成失败:", type(e).__name__, e)
        return False, out


def _state(snaps):
    """与 APK 口径一致的强弱档：强势/震荡/弱势。"""
    if len(snaps) < 6:
        return "数据不足"
    p5 = (snaps[-1]["close"] / snaps[-6]["close"] - 1) * 100
    if len(snaps) < 20:
        return "强势" if p5 > 0.8 else ("弱势" if p5 < -0.8 else "震荡")
    ma5 = sum(s["close"] for s in snaps[-5:]) / 5.0
    ma20 = sum(s["close"] for s in snaps[-20:]) / 20.0
    if p5 > 0.8 and ma5 > ma20:
        return "强势"
    if p5 < -0.8 and ma5 <= ma20:
        return "弱势"
    if p5 > 1.5:
        return "强势"
    if p5 < -1.5:
        return "弱势"
    return "震荡"


def _pct_line(data, code, label):
    snaps = (data.get(code) or {}).get("snaps") or []
    if len(snaps) < 6:
        return None
    p5 = (snaps[-1]["close"] / snaps[-6]["close"] - 1) * 100
    p10 = (snaps[-1]["close"] / snaps[-11]["close"] - 1) * 100 if len(snaps) > 10 else None
    s5 = "+" if p5 >= 0 else ""
    s10 = "+" if (p10 or 0) >= 0 else ""
    p10txt = "%+.2f%%" % p10 if p10 is not None else " —"
    return "%s %s · 5日%s%.2f%% · 10日%s" % (label, _state(snaps), s5, p5, p10txt)


def verdict_text(force_online=True):
    """大盘强弱结论文本（开盘前/收盘后推送正文）。与 APK indexAnalysisText 同规则。"""
    data = refresh(force_online)
    lines = []
    for code, label in INDEXES:
        t = _pct_line(data, code, label)
        if t:
            lines.append(t)

    def p5(code):
        snaps = (data.get(code) or {}).get("snaps") or []
        if len(snaps) < 6:
            return 0.0
        return (snaps[-1]["close"] / snaps[-6]["close"] - 1) * 100

    def st(code):
        return _state((data.get(code) or {}).get("snaps") or [])

    def line(code, label):
        snaps = (data.get(code) or {}).get("snaps") or []
        p = p5(code)
        return "%s5日%+.2f%%" % (label, p) if snaps else "%s无数据" % label

    sh5 = p5("sh000001")
    kc5 = p5("sh000688")
    sh_s = st("sh000001")
    kc_s = st("sh000688")
    if kc_s == "数据不足":
        verdict = "科创50 数据不足，暂以上证/沪深300/创业板判断：%s。" % (
            "偏强，可适度积极" if sh5 > 0.8 else ("偏弱，控制仓位" if sh5 < -0.8 else "震荡，精选个股"))
    elif sh5 > 0.8 and kc5 > 0.8:
        verdict = "共振强势（上证+科创同涨）：历史经验最利于做多，量能配合时普涨概率大，可提高仓位至 6-8 成，围绕强势板块低吸龙头、避免盘中追高。"
    elif sh5 > 0.8 and kc_s == "震荡":
        verdict = "权重搭台、科创休整：指数稳但赚钱效应一般，适合精选低吸而非追涨，仓位 5-6 成；若深成/创业板同步走强视为共振确认。"
    elif kc5 > 0.8 and sh5 <= 0.8:
        verdict = "题材结构行情（科创强、上证弱/震荡）：科技成长活跃但指数不稳，快进快出为主，仓位 3-5 成，严守止损。"
    elif sh5 <= 0.8 and kc5 <= 0.8 and sh_s != "弱势":
        verdict = "弱势整理/存量博弈：控制仓位（3 成内），只做确定性高的强势股，等待上证重新站上 MA20。"
    else:
        verdict = "防御状态（指数偏弱）：宜降低仓位（0-2 成）或空仓等待企稳；反弹站稳 MA5 后再参与。"
    hs5 = p5("sh000300")
    cy5 = p5("sz399006")
    verdict += "（沪深300 5日%+.2f%% · 创业板5日%+.2f%% 佐证）" % (hs5, cy5)
    lines.append("结论：%s" % verdict)
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description="大盘4指数归一化叠加图/文本")
    ap.add_argument("--days", type=int, default=55)
    ap.add_argument("--text", action="store_true", help="只输出强弱文本")
    args = ap.parse_args()
    if args.text:
        print(verdict_text())
        return
    render_png(days=args.days)


if __name__ == "__main__":
    main()
