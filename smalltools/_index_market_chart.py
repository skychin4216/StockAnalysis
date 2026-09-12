# -*- coding: utf-8 -*-
"""大盘4指数日K蜡烛同轴图（2026-09-08 修订，开盘前/收盘后推送配图）。

把 上证指数 / 科创50 / 沪深300 / 创业板指 的日K蜡烛画在**同一张图、同一个
真实点位 Y 轴（0 ~ 最高）**上：
  - Y 轴 = 真实指数点位，刻度从 0 到窗口内最高值，越高的指数画得越靠上
    （沪深300≈4559 > 上证≈3941 > 创业板≈3360 > 科创50≈1591，高度差一目了然）；
  - 每只指数一条红涨绿跌的蜡烛序列（柱状图，不再是折线），像 APP 个股趋势图
    那样带 MA5/MA20 均线，能看清近期形态；
  - 曲线末端标注『名称 + 最新实际点位 + 5日涨跌%』，图底附 5/10 日强弱清单。
K线默认 3 个月（60 个交易日），--days 可在 55~250 间调整；数据源东财日K优先、
腾讯回退，失败自动回退本地缓存 data/_index_market.json。

用法：
  python _index_market_chart.py --days 60          # 默认3个月 K 线（60 根交易日）
  python _index_market_chart.py --days 250         # 一年 K 线（250 根交易日）
  python _index_market_chart.py --text             # 只输出强弱结论文本
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
PCT_PNG_PATH = os.path.join(HERE, "data", "_index_market_pct.png")
CACHE_PATH = os.path.join(HERE, "data", "_index_market.json")
MAX_KEEP = 600           # 本地缓存每指数最多保留根数（支持 1 年 K 线 250 根）
RET_NATURAL_DAYS = 620   # 每次在线拉取的自然日跨度（≈250+ 个交易日，满足 --days 250）

# K线蜡烛配色（中国习惯：红涨 绿跌）
UP_COLOR = "#E53935"
DOWN_COLOR = "#2E7D32"
DEFAULT_DAYS = 60        # 默认 3 个月（60 个交易日）


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


def _build_series(out, days=60):
    """取4指数公共交易日窗口，返回原始 OHLC 蜡烛数据（真实点位）。

    返回 (common_dates, series)：
      common_dates: [str] 升序日期
      series: [{label, color, snaps, last_close, p5, p10}]
              snaps 为窗口内 {date,open,high,low,close} 全量（真实点位，供画蜡烛）；
              last_close 最新实际点位；p5/p10 为 5/10 日涨跌%。
    公共日期不足 10 根时返回 (None, [])。
    """
    by_code = {c: d["snaps"] for c, d in out.items()}
    date_sets = [set(s["date"] for s in by_code[c]) for c, _ in INDEXES if by_code[c]]
    if not date_sets:
        return None, []
    common = sorted(set.intersection(*date_sets))
    if len(common) < 10:
        return None, []
    window = common[-days:]
    win_set = set(window)
    series = []
    for code, name in INDEXES:
        snaps = [s for s in by_code[code] if s["date"] in win_set]
        if not snaps or snaps[-1]["close"] <= 0:
            continue
        series.append({
            "label": name,
            "color": COLORS.get(name, "#1976D2"),
            "snaps": snaps,   # 窗口内 OHLC 全量（真实点位，画日K蜡烛）
            "last_close": snaps[-1]["close"],
            "p5": (snaps[-1]["close"] / snaps[-6]["close"] - 1) * 100 if len(snaps) > 5 else 0.0,
            "p10": (snaps[-1]["close"] / snaps[-11]["close"] - 1) * 100 if len(snaps) > 10 else 0.0,
        })
    return window, series


def _fmt_pt(v):
    """指数点位千分位，如 4559.14 → '4,559'。"""
    try:
        return "{:,.0f}".format(float(v))
    except (TypeError, ValueError):
        return "-"


def _sma(closes, n):
    """简单均线：不足 n 根返回 None 占位。"""
    out = []
    acc = 0.0
    q = []
    for i, c in enumerate(closes):
        acc += c
        q.append(c)
        if len(q) > n:
            acc -= q.pop(0)
        out.append(acc / len(q) if i >= n - 1 else None)
    return out


def _draw_png(window, series, out, hi_res=True):
    """把 (window, series) 画成「单轴真实点位 0~最高 + 4 指数日K蜡烛叠加」PNG。

    每只指数一段红涨绿跌蜡烛（柱状），Y 轴为真实指数点位（0 ~ 窗口最高），
    点位高的指数画得高——沪深300 会明显高于上证/创业板/科创50；
    蜡烛旁叠加各指数 MA5/MA20（指数色），末端标注『名称+最新点位+5日』。
    """
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Rectangle
    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
    plt.rcParams["axes.unicode_minus"] = False

    n = len(window)
    fig, ax = plt.subplots(figsize=(17, 9), dpi=180)
    xs = list(range(n))

    # 依次画各指数蜡烛（按末位从低到高画，高位的画在上层）
    ordered = sorted(series, key=lambda s: s["last_close"])
    # 用最高价而非收盘价定轴：否则上影线高于最高收盘时会超出 Y 轴被裁掉
    # （HTML 版 _export_html 一直用 high，PNG 版保持一致）
    y_max = max((max(s["high"] for s in ser["snaps"])
                 for ser in series if ser["snaps"]), default=0)
    for ser in ordered:
        col = ser["color"]
        snaps = ser["snaps"]
        opens = [s["open"] for s in snaps]
        closes = [s["close"] for s in snaps]
        highs = [s["high"] for s in snaps]
        lows = [s["low"] for s in snaps]
        # MA5/MA20（指数色，区分趋势）
        ma5 = _sma(closes, 5)
        ma20 = _sma(closes, 20)
        ax.plot(xs, ma5, color=col, lw=1.1, alpha=0.55, zorder=3)
        ax.plot(xs, ma20, color=col, lw=1.1, alpha=0.35, ls="--", zorder=3)
        # 蜡烛：影线 + 实体（红涨 绿跌）
        w = 0.62
        for i in range(n):
            o, c, h, l = opens[i], closes[i], highs[i], lows[i]
            if h <= 0 or l <= 0:
                continue
            body_col = UP_COLOR if c >= o else DOWN_COLOR
            ax.plot([i, i], [l, h], color=body_col, lw=1.0, alpha=0.9, zorder=4)
            lo = min(o, c)
            hi = max(o, c)
            if hi - lo < 1e-6:          # 十字星/一字
                ax.plot([i - w / 2, i + w / 2], [lo, lo], color=body_col,
                        lw=1.6, alpha=0.95, zorder=4)
            else:
                ax.add_patch(Rectangle((i - w / 2, lo), w, hi - lo,
                                       facecolor=body_col, edgecolor=body_col,
                                       lw=0.4, alpha=0.95, zorder=4))
        # 末端标注：名称 + 最新点位 + 5日涨跌
        ly = closes[-1]
        ax.annotate("  %s %s · 5日%+.2f%%" % (ser["label"], _fmt_pt(ly), ser["p5"]),
                    xy=(n - 1, ly), xytext=(8, 0), textcoords="offset points",
                    color=col, fontsize=12.5, fontweight="bold",
                    va="center", ha="left", zorder=6)
        ax.plot([n - 1], [ly], "o", color=col, ms=5, zorder=5)

    # 单轴真实点位：0 ~ 窗口最高
    y_top = y_max * 1.04
    ax.set_ylim(0, y_top if y_top > 0 else 1)
    ax.set_xlim(-1.5, n + 12)

    tick_step = max(1, n // 9)
    tix = list(range(0, n, tick_step))
    ax.set_xticks(tix)
    ax.set_xticklabels([window[i][5:] for i in tix], fontsize=11)
    ystep = _nice_step(y_max)
    ax.yaxis.set_major_formatter(
        matplotlib.ticker.FuncFormatter(lambda v, _p: "{:,.0f}".format(v)))
    ax.set_yticks(range(0, int(y_top) + 1, ystep))
    ax.set_ylabel("指数真实点位（0 ~ 最高 %s）" % _fmt_pt(y_max), fontsize=13)
    ax.set_title("大盘4指数 日K蜡烛同轴图（Y=真实点位，越高越靠上；红涨绿跌，色线=MA5/20）截至 %s"
                 % window[-1], fontsize=16, loc="left")
    ax.grid(alpha=0.25, lw=0.7, ls=":")
    # 图底强弱清单（文字列, 与曲线同色）
    fig.text(0.01, 0.012, "   ".join(
        "%s %s · %s · 5日%+.2f%% · 10日%+.2f%%" % (
            s["label"], _fmt_pt(s["last_close"]),
            _state(s["snaps"]), s["p5"], s["p10"])
        for s in series), fontsize=11.5, color="#424242")
    fig.tight_layout(rect=(0, 0.035, 1, 1))
    fig.savefig(out, dpi=fig.dpi)
    wpx, hpx = fig.get_size_inches()[0] * fig.dpi, fig.get_size_inches()[1] * fig.dpi
    plt.close(fig)
    print("大盘图已生成: %s (%d 根K线, %s, %dx%d)" % (out, n, window[-1], wpx, hpx))
    return True


def _nice_step(maxv):
    """给 0~max 取整齐的 Y 轴步长（约 8~10 格）。"""
    raw = maxv / 9.0
    for base in (100, 200, 250, 500, 1000, 2000, 5000):
        if raw <= base:
            return base
    return 5000


def _export_html(window, series, out_html):
    """导出「单轴真实点位 + 4 指数日K蜡烛」交互 HTML（plotly 离线）。

    红涨绿跌蜡烛，Y 轴真实点位(0~最高)；支持滚轮/框选缩放、平移、悬停。
    """
    try:
        import plotly.graph_objects as go
    except Exception:  # noqa: BLE001 - plotly 缺失仅跳过 HTML
        return False
    fig = go.Figure()
    y_max = 0.0
    for ser in series:
        snaps = ser["snaps"]
        dates = [s["date"] for s in snaps]
        opens = [s["open"] for s in snaps]
        closes = [s["close"] for s in snaps]
        highs = [s["high"] for s in snaps]
        lows = [s["low"] for s in snaps]
        y_max = max(y_max, max(highs))
        fig.add_trace(go.Candlestick(
            x=dates, open=opens, high=highs, low=lows, close=closes,
            name=ser["label"],
            increasing_line_color=UP_COLOR, increasing_fillcolor=UP_COLOR,
            decreasing_line_color=DOWN_COLOR, decreasing_fillcolor=DOWN_COLOR,
            line=dict(width=1)))
        # 末端标注：名称 + 最新点位 + 5日
        fig.add_annotation(
            x=dates[-1], y=closes[-1], text="%s %s · 5日%+.2f%%"
            % (ser["label"], _fmt_pt(closes[-1]), ser["p5"]),
            showarrow=False, xanchor="left", xshift=8,
            font=dict(color=ser["color"], size=14))
    fig.update_layout(
        title=dict(text="大盘4指数 日K蜡烛同轴图(Y=真实点位0~最高; 红涨绿跌) 截至 %s"
                       % window[-1], font=dict(size=18)),
        yaxis=dict(title="指数真实点位", range=[0, y_max * 1.05],
                   tickformat=","),
        xaxis=dict(rangeslider=dict(visible=False)),
        template="plotly_white",
        height=760,
        hovermode="x unified",
        dragmode="zoom",
        legend=dict(orientation="h", yanchor="bottom", y=1.02, x=0),
        margin=dict(l=60, r=190, t=90, b=40))
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


def render_png(out=None, days=DEFAULT_DAYS, data=None):
    """刷新数据并绘制 data/_index_market.png。返回 (ok, png_path)。

    data 传现成 refresh() 结果时不再联网（文本/图共用同一份数据快照，口径一致）。
    """
    out = out or PNG_PATH
    days = min(max(days, 30), 250)
    try:
        if data is None:
            data = refresh()
        window, series = _build_series(data, days)
        return _render_artifacts(window, series, out)
    except Exception as e:  # noqa: BLE001
        print("大盘图生成失败:", type(e).__name__, e)
        return False, out


def render_from_snaps(index_map, out=None, days=DEFAULT_DAYS):
    """离线渲染：直接给 {code: {"name","snaps"}}（snaps 已按日期截断到目标日），
    不联网。回放/历史推图用；口径与 render_png 完全一致。返回 (ok, png_path)。"""
    out = out or PNG_PATH
    days = min(max(days, 30), 250)
    try:
        window, series = _build_series(index_map, days)
        return _render_artifacts(window, series, out)
    except Exception as e:  # noqa: BLE001
        print("大盘图生成失败:", type(e).__name__, e)
        return False, out


def render_pct_from_snaps(index_map, out=None, days=DEFAULT_DAYS):
    """离线渲染「起点归一化涨跌幅折线图」（不联网），口径与 render_pct_png 一致。
    历史回放推图用。返回 (ok, png_path)。"""
    out = out or PCT_PNG_PATH
    days = min(max(days, 30), 250)
    try:
        window, series = _build_series(index_map, days)
        if not window or not series:
            print("指数数据不足，跳过绘图")
            return False, out
        return _draw_pct_png(window, series, out), out
    except Exception as e:  # noqa: BLE001
        print("归一化折线图生成失败:", type(e).__name__, e)
        return False, out


def _draw_pct_png(window, series, out):
    """把 (window, series) 画成「起点归一化涨跌幅折线」PNG（2026-09-09 用户选定）。

    以窗口首日收盘价为 0% 基线，画四指数每日累计涨跌幅(%)折线——起点从 0 对齐，
    谁涨得猛一目了然，也能直接看相对强弱/背离（不像真实点位同轴蜡烛那样低位指数
    柱子被压矮看不清涨跌）。
    """
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
    plt.rcParams["axes.unicode_minus"] = False

    n = len(window)
    fig, ax = plt.subplots(figsize=(17, 9), dpi=180)
    xs = list(range(n))

    # 窗口累计涨跌幅序列（首日收盘 = 0%）
    for ser in series:
        snaps = ser["snaps"]
        base = snaps[0]["close"]
        if base <= 0:
            continue
        rets = [(s["close"] / base - 1) * 100 for s in snaps]
        col = ser["color"]
        ax.plot(xs, rets, color=col, lw=2.0, zorder=3)
        ax.fill_between(xs, 0, rets, color=col, alpha=0.06, zorder=2)
        last = rets[-1]
        ax.annotate("  %s %s · 窗口%+.2f%%" % (ser["label"], _fmt_pt(snaps[-1]["close"]), last),
                    xy=(n - 1, last), xytext=(10, 0), textcoords="offset points",
                    color=col, fontsize=13.5, fontweight="bold",
                    va="center", ha="left", zorder=6)
        ax.plot([n - 1], [last], "o", color=col, ms=6, zorder=5)

    # 0% 基准虚线
    ax.axhline(0, color="#616161", lw=1.2, ls="--", alpha=0.75, zorder=2)

    ax.set_xlim(-1.5, n + 13)
    ax.set_xlabel("交易日（自 %s 起，共 %d 日）" % (window[0], n), fontsize=12)
    ax.yaxis.set_major_formatter(
        matplotlib.ticker.FuncFormatter(lambda v, _p: "%+.1f%%" % v))
    ax.set_ylabel("相对窗口起点涨跌幅 %", fontsize=13)
    ax.set_title("大盘4指数 起点归一化涨跌幅折线（0%% 对齐看相对强弱/背离）截至 %s"
                 % window[-1], fontsize=16, loc="left")
    ax.grid(alpha=0.3, lw=0.7, ls=":")
    tick_step = max(1, n // 9)
    tix = list(range(0, n, tick_step))
    ax.set_xticks(tix)
    ax.set_xticklabels([window[i][5:] for i in tix], fontsize=11)
    # 图底强弱清单（文字列, 与曲线同色）
    fig.text(0.01, 0.012, "   ".join(
        "%s %s · %s · 5日%+.2f%% · 10日%+.2f%%" % (
            s["label"], _fmt_pt(s["last_close"]),
            _state(s["snaps"]), s["p5"], s["p10"])
        for s in series), fontsize=11.5, color="#424242")
    fig.tight_layout(rect=(0, 0.035, 1, 1))
    fig.savefig(out, dpi=fig.dpi)
    wpx, hpx = fig.get_size_inches()[0] * fig.dpi, fig.get_size_inches()[1] * fig.dpi
    plt.close(fig)
    print("归一化涨跌幅折线图已生成: %s (%d 日, %s, %dx%d)" % (out, n, window[-1], wpx, hpx))
    return True


def render_pct_png(out=None, days=DEFAULT_DAYS, data=None):
    """生成「起点归一化涨跌幅折线图」data/_index_market_pct.png。返回 (ok, png_path)。

    data 传现成 refresh() 结果时不再联网（与正文结论共用同一快照）。
    """
    out = out or PCT_PNG_PATH
    days = min(max(days, 30), 250)
    try:
        if data is None:
            data = refresh()
        window, series = _build_series(data, days)
        if not window or not series:
            print("指数数据不足，跳过绘图")
            return False, out
        return _draw_pct_png(window, series, out), out
    except Exception as e:  # noqa: BLE001
        print("归一化折线图生成失败:", type(e).__name__, e)
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


def _disp_w(s):
    """显示宽度：中文/全角按 2 计，ASCII 按 1（等宽对齐用）。"""
    return sum(2 if ord(ch) > 0x2E7F else 1 for ch in s)


def _pad_r(s, w):
    """右侧补空格到显示宽度 w。"""
    return s + " " * max(0, w - _disp_w(s))


def _cell(s, w):
    """单元格：内容补到显示宽度 w 后再加 2 空格分隔。"""
    return _pad_r(s, w) + "  "


def _fmt_pct(v, w=7):
    if v is None:
        return _pad_r("—", w)
    return _pad_r("%+.2f%%" % v, w)


def market_table(data):
    """把四大指数最新收盘合成一张等宽文本表格。

    返回 (asof, table_text)；asof = 各指数最新收盘日期交集（取最早，防某指数滞后
    时标题超前），table_text 含表头与 4 行（最新点位/当日%/5日%/10日%/状态）。
    任一指数 <6 根则跳过该行。
    """
    header = "".join([
        _cell("指数", 8), _cell("最新点位", 9),
        _cell("当日%", 7), _cell("5日%", 7), _cell("10日%", 7), _pad_r("状态", 4),
    ])
    rows = []
    asofs = []
    for code, label in INDEXES:
        snaps = (data.get(code) or {}).get("snaps") or []
        if len(snaps) < 6 or snaps[-1]["close"] <= 0:
            continue
        s = snaps[-1]
        asofs.append(s["date"])
        p5 = (s["close"] / snaps[-6]["close"] - 1) * 100
        p10 = (s["close"] / snaps[-11]["close"] - 1) * 100 if len(snaps) > 10 else None
        rows.append("".join([
            _cell(label, 8),
            _cell(_fmt_pt(s["close"]), 9),
            _cell(_fmt_pct(s.get("changePct")), 7),
            _cell(_fmt_pct(p5), 7),
            _cell(_fmt_pct(p10), 7),
            _pad_r(_state(snaps), 4),
        ]))
    if not rows:
        return "", ""
    asof = min(asofs)
    return asof, "\n".join([header] + rows)


def verdict_text(force_online=True, table=True, data=None):
    """大盘强弱结论文本（开盘前/收盘后推送正文）：四指数收盘表格 + 强弱结论。

    table=True 时正文首部带四指数表格（每行=指数/最新点位/当日%/5日%/10日%/状态）；
    table=False 只输出结论（用于开盘速览正文——表格由 XY 轴图承载）。
    data 传现成 refresh() 结果时不再联网/读盘；结论与 APK indexAnalysisText
    同规则（上证×科创50 优先级 + 沪深300/创业板佐证）。
    """
    if data is None:
        data = refresh(force_online)
    lines = []
    if table:
        asof, tbl = market_table(data)
        if tbl:
            lines.append("📊 四指数速览（截至 %s）：" % asof)
            lines.append(tbl)

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
    ap = argparse.ArgumentParser(description="大盘4指数同轴日K蜡烛图(真实点位)/强弱文本")
    ap.add_argument("--days", type=int, default=DEFAULT_DAYS,
                    help="K线窗口交易日数(55~250，默认60=3个月)")
    ap.add_argument("--text", action="store_true", help="只输出强弱文本")
    args = ap.parse_args()
    if args.text:
        print(verdict_text())
        return
    render_png(days=args.days)


if __name__ == "__main__":
    main()
