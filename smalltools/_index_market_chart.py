# -*- coding: utf-8 -*-
"""大盘4指数归一化K线叠加图（2026-09-06，开盘前/收盘后推送配图）。

把 上证指数 / 深证成指 / 科创50 / 创业板指 放到同一张图：
各指数以窗口首日收盘=100 归一化后绘制收盘曲线（保留完整走势，与
APK「K线趋势 → 大盘K线」同一口径），并输出强弱结论文本。

数据源：东财日K优先、腾讯回退；失败自动回退本地缓存 data/_index_market.json。

用法：
  python _index_market_chart.py --days 55         # 生成 data/_index_market.png
  python _index_market_chart.py --text            # 只输出强弱结论文本
"""
import argparse
import datetime as _dt
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from backtest_guangmo import fetch_east, fetch_tencent  # noqa: E402

INDEXES = [
    ("sh000001", "上证指数"),
    ("sz399001", "深证成指"),
    ("sh000688", "科创50"),
    ("sz399006", "创业板指"),
]
COLORS = {
    "上证指数": "#E53935",
    "深证成指": "#FB8C00",
    "科创50": "#7E57C2",
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
    """取4指数公共交易日窗口，归一化收盘序列。

    返回 (common_dates, series)：
      common_dates: [str] 升序日期
      series: [{label, color, values, latest, p5, p10}] values 为归一化收盘(=100 起点)
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
            "values": [s["close"] / base * 100.0 for s in snaps],
            "latest": snaps[-1]["close"],
            "p5": (snaps[-1]["close"] / snaps[-6]["close"] - 1) * 100 if len(snaps) > 5 else 0.0,
            "p10": (snaps[-1]["close"] / snaps[-11]["close"] - 1) * 100 if len(snaps) > 10 else 0.0,
        })
    return window, series


def _draw_png(window, series, out):
    """把已算好的 (window, series) 绘制成 PNG。成功返回 True。"""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
    plt.rcParams["axes.unicode_minus"] = False

    n = len(window)
    fig, ax = plt.subplots(figsize=(10.2, 4.4), dpi=150)
    x = list(range(n))
    for s in series:
        ax.plot(x, s["values"], color=s["color"], lw=1.6,
                label="%s(%+.2f%%)" % (s["label"], s["p5"]))
    # 末端数值标注
    for s in series:
        ax.annotate("%.1f" % s["values"][-1],
                    xy=(n - 1, s["values"][-1]),
                    xytext=(6, 0), textcoords="offset points",
                    fontsize=8, color=s["color"])
    ax.axhline(100.0, color="#CCCCCC", lw=0.8, ls="--")
    ax.set_xticks(range(0, n, max(1, n // 8)))
    ax.set_xticklabels([window[i][5:] for i in range(0, n, max(1, n // 8))],
                       fontsize=8)
    ax.set_ylim(auto=True)
    ax.grid(alpha=0.25, lw=0.6)
    ax.legend(loc="upper left", fontsize=8, framealpha=0.6)
    ax.set_title("大盘4指数归一化叠加(上证/深证/科创50/创业板) 截至 %s" % window[-1],
                 fontsize=11, pad=8)
    fig.tight_layout()
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print("大盘图已生成: %s (%d 根, %s)" % (out, n, window[-1]))
    return True


def render_png(out=None, days=55):
    """刷新数据并绘制 data/_index_market.png。返回 (ok, png_path)。"""
    out = out or PNG_PATH
    try:
        data = refresh()
        window, series = _build_series(data, days)
        if not window or not series:
            print("指数数据不足，跳过绘图")
            return False, out
        _draw_png(window, series, out)
        return True, out
    except Exception as e:  # noqa: BLE001
        print("大盘图生成失败:", type(e).__name__, e)
        return False, out


def render_from_snaps(index_map, out=None, days=55):
    """离线渲染：直接给 {code: {"name","snaps"}}（snaps 已按日期截断到目标日），
    不联网。回放/历史推图用；口径与 render_png 完全一致。返回 (ok, png_path)。"""
    out = out or PNG_PATH
    try:
        window, series = _build_series(index_map, days)
        if not window or not series:
            print("指数数据不足，跳过绘图")
            return False, out
        _draw_png(window, series, out)
        return True, out
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
        verdict = "科创50 数据不足，暂以上证/深成/创业板判断：%s。" % (
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
    sz5 = p5("sz399001")
    cy5 = p5("sz399006")
    verdict += "（深成5日%+.2f%% · 创业板5日%+.2f%% 佐证）" % (sz5, cy5)
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
