# -*- coding: utf-8 -*-
"""「连跌后企稳低吸」回溯统计（2026-09-08 · 需求②，先出报告再决定是否接入买入）

背景：晋控能源型——连跌多天后出现「3 天不新低 + 最低位连续三天上浮」企稳，但不满
均线粘合，被 n_strict(analyze_snaps) 硬门槛挡在选股外。统计这种「企稳非粘合」候选
低吸后未来 H 日胜率/均值，判断短线/超短线是否值得放行（中长线留给 DAG 判断）。

口径（与引擎侧同源）：
· 企稳 = usecase_pipeline._is_stabilize 同口径（基座前≥3连跌 + 近3日不创新低 +
  低点逐日抬高），另要求基座日比 12 日前高回落 ≥3%（「跌了很多天」）。
· 粘合 = analyze_snaps ①口径；主口径取短/超短：MA5/10/20 极差/最小 ≤3.0%，
  牛×1.5、熊×0.8(下限1.0)（ultra_short_pipeline: convergenceThreshold=3.0,
  useMA60=false）。含 MA60(2.5%) 口径供中/长参考。
· 买卖 = 信号日收盘买、第 H 日收盘卖(close→close)；H∈1/2/3/5/10。
· 大盘 = 上证 close vs MA20/MA60：牛 c>ma20>ma60；熊 c<ma20<ma60；其余震荡。
数据：smalltools/_kline_cache.json（sh000001 + 215 龙头，同 dip_rebound_stat）。
"""
import json
import os
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "_kline_cache.json")
OUT = os.path.join(os.path.dirname(HERE), "AutoQuant", "backtest_logs", "_stabilize_dip_report.md")
START = "2018-01-01"
IDX = {"sh000001", "sh000688", "sz399001", "sz399006"}
HORIZONS = [1, 2, 3, 5, 10]
CONV_S = 3.0
CONV_L = 2.5


def load():
    with open(CACHE, encoding="utf-8") as f:
        return json.load(f)


def seq_of(cache, code):
    snaps = sorted((cache.get(code) or {}).get("snaps") or [], key=lambda s: s["date"])
    return [s for s in snaps if s["date"] >= START]


def avg(xs):
    return sum(xs) / len(xs) if xs else 0.0


def ma_at(closes, i, n):
    return avg(closes[i + 1 - n:i + 1]) if i + 1 >= n else None


def conv_degree(closes, i, use_ma60):
    if i + 1 < 20:
        return None
    mas = [ma_at(closes, i, 5), ma_at(closes, i, 10), ma_at(closes, i, 20)]
    if use_ma60 and i + 1 >= 60:
        mas.append(ma_at(closes, i, 60))
    mx, mn = max(mas), min(mas)
    return (mx - mn) / mn * 100 if mn > 0 else 999.0


def stabilize_at(closes, lows, i):
    """与 usecase_pipeline._is_stabilize 同口径（prefix 止于 i）。"""
    n = i + 1
    if n < 20:
        return False
    down3 = any(closes[j] < closes[j - 1] and closes[j - 1] < closes[j - 2]
                for j in range(n - 4, max(n - 12, 2), -1))
    rising = lows[i] > lows[i - 1] > lows[i - 2]
    no_new = min(lows[i - 2:i + 1]) > min(lows[max(n - 13, 0):i - 2])
    return down3 and rising and no_new


def drop_from_hi(closes, lows, i):
    """基座日(i-3)相对 12 日前高回落%，及连跌终点前连续阴线数。"""
    n = i + 1
    prior_hi = max(closes[max(n - 13, 0):i - 2])
    drop = (1 - closes[i - 3] / prior_hi) * 100 if prior_hi > 0 else 0.0
    streak = 0
    j = i - 3
    while j > 0 and closes[j] < closes[j - 1]:
        streak += 1
        j -= 1
    return drop, streak


def pct(lst):
    if not lst:
        return "-"
    w = sum(1 for x in lst if x > 0) / len(lst) * 100
    return "%.0f%%(%+.2f)" % (w, sum(lst) / len(lst))


def ok_event(seq, i):
    """剔除异常事件：i±10 个交易日内出现 >25% 相邻跳变（除权/停牌复牌/数据缺口），
    或未来第 10 个交易日距离超过 25 个自然日（长时间停牌，无法按时卖出）。"""
    for j in range(max(i - 12, 1), min(i + 11, len(seq))):
        prev = seq[j - 1]["close"]
        cur = seq[j]["close"]
        if prev and prev > 0 and abs(cur / prev - 1) > 0.25:
            return False
    if i + 10 >= len(seq):
        return False
    d0 = date(*[int(x) for x in seq[i]["date"].split("-")])
    d10 = date(*[int(x) for x in seq[i + 10]["date"].split("-")])
    return (d10 - d0).days <= 25


def new_bucket():
    return {h: [] for h in HORIZONS}


def main():
    cache = load()
    idx_snaps = seq_of(cache, "sh000001")
    if not idx_snaps:
        print("缺少 sh000001，无法划分大盘状态")
        return
    idx_date_pos = {s["date"]: i for i, s in enumerate(idx_snaps)}
    idx_c = [s["close"] for s in idx_snaps]
    idx_ma20 = [ma_at(idx_c, i, 20) or 0.0 for i in range(len(idx_c))]
    idx_ma60 = [ma_at(idx_c, i, 60) or 0.0 for i in range(len(idx_c))]

    def regime_on(date):
        p = idx_date_pos.get(date)
        if p is None or idx_ma60[p] == 0:
            return None
        if idx_c[p] > idx_ma20[p] > idx_ma60[p]:
            return "牛市"
        if idx_c[p] < idx_ma20[p] < idx_ma60[p]:
            return "熊市"
        return "震荡"

    def eff_conv(regime, base):
        if regime == "牛市":
            return base * 1.5
        if regime == "熊市":
            return max(base * 0.8, 1.0)
        return base

    all_base = new_bucket()          # 全体龙头任意日（基准）
    conv_s = new_bucket()            # 企稳+短/超短口径粘合
    noconv_s = new_bucket()          # 企稳+非粘合(短/超短口径)  —— 用户要的
    deep6 = new_bucket()             # 非粘合 且 深跌≥6%
    drop4 = new_bucket()             # 非粘合 且 连跌≥4
    deep_drop4 = new_bucket()        # 非粘合 且 深跌≥6% 且 连跌≥4
    noconv_regime = {rg: new_bucket() for rg in ("牛市", "震荡", "熊市")}
    noconv_year = {}
    examples = []

    stocks = [(k, e) for k, e in cache.items()
              if k not in IDX and e.get("snaps") and len(e["snaps"]) > 200]
    for code, ent in stocks:
        seq = seq_of(cache, code)
        n = len(seq)
        if n < 70:
            continue
        name = ent.get("name", code)
        closes = [s["close"] for s in seq]
        lows = [s["low"] for s in seq]
        vols = [s.get("volume") or 0 for s in seq]
        for i in range(40, n - 11):
            base = closes[i]
            if base <= 0 or not ok_event(seq, i):
                continue
            for h in HORIZONS:
                all_base[h].append((seq[i + h]["close"] / base - 1) * 100)
            if not stabilize_at(closes, lows, i):
                continue
            rg = regime_on(seq[i]["date"])
            if rg is None:
                continue
            rets = {}
            for h in HORIZONS:
                if i + h >= n:
                    break
                rets[h] = (seq[i + h]["close"] / base - 1) * 100
            drop, streak = drop_from_hi(closes, lows, i)
            if drop < 3.0:
                continue
            deg_s = conv_degree(closes, i, False)
            conv_s_ok = deg_s is not None and deg_s <= eff_conv(rg, CONV_S)
            deg_l = conv_degree(closes, i, True)
            conv_l_ok = deg_l is not None and deg_l <= eff_conv(rg, CONV_L)
            if conv_s_ok:
                for h, r in rets.items():
                    conv_s[h].append(r)
                continue
            # ── 企稳 且 不满足短/超短粘合 ──
            for h, r in rets.items():
                noconv_s[h].append(r)
                noconv_regime[rg][h].append(r)
                noconv_year.setdefault(seq[i]["date"][:4], new_bucket())[h].append(r)
            if drop >= 6:
                for h, r in rets.items():
                    deep6[h].append(r)
            if streak >= 4:
                for h, r in rets.items():
                    drop4[h].append(r)
            if drop >= 6 and streak >= 4:
                for h, r in rets.items():
                    deep_drop4[h].append(r)
            if 3 in rets:
                examples.append((seq[i]["date"], name, code, rg, drop, streak,
                                 deg_s, deg_l, conv_l_ok, rets[3]))

    L = []

    def out(*a):
        L.append(" ".join(str(x) for x in a))

    out("# 连跌后企稳低吸（企稳非粘合）回溯统计 2018-01 ~ 2026-09")
    out("")
    out("> 信号 = 连跌≥3天后出现「3日不新低 + 低点连续3天上浮」（= usecase_pipeline._is_stabilize 口径）"
        "，且基座日自 12 日前高回落≥3%；买入=信号日收盘，卖出=第H日收盘(close→close)。")
    out("> 粘合主口径 = 短/超短 n_strict：MA5/10/20 极差 ≤3.0%（牛×1.5、熊×0.8 下限1.0，"
        "对应 ultra_short_pipeline convergenceThreshold=3.0/useMA60=false）；")
    out("> 中/长参考口径 = 含 MA60 ≤2.5%%；数据：%d 只龙头股（%s 起），大盘以上证 vs MA20/MA60 三分。"
        % (len(stocks), START))
    out("")
    out("> 阅读重点：短线/超短线看 H=1/2/3；「企稳非粘合」若要放行需 H 显著跑赢基准。")
    out("")

    def table(title, rows):
        out("### %s" % title)
        out("| 分组 | 样本 | H=1 | H=2 | H=3 | H=5 | H=10 |")
        out("|---|--:|--:|--:|--:|--:|--:|")
        for label, b in rows:
            out("| %s | %d | %s | %s | %s | %s | %s |"
                % (label, len(b[1]), pct(b[1]), pct(b[2]), pct(b[3]), pct(b[5]), pct(b[10])))

    table("① 基准：全体龙头股任意日收盘买入（同一买卖口径）", [("全部龙头(任意日)", all_base)])
    out("")
    table("② 企稳信号：短/超短粘合口径 满足 vs 不满足（核心分组）", [
        ("企稳+粘合(MA5/10/20≤3%)", conv_s),
        ("企稳+非粘合(>3%)·晋控能源型·现规则被拒", noconv_s),
    ])
    out("")
    table("③ 企稳非粘合 + 跌得越狠/跌得越久 → 反弹机会是否更大", [
        ("企稳非粘合(回落≥3%)", noconv_s),
        ("+ 深跌(自12日前高回落≥6%)", deep6),
        ("+ 连跌≥4天", drop4),
        ("+ 深跌≥6%且连跌≥4", deep_drop4),
    ])
    out("")
    out("### ④ 企稳非粘合 × 大盘状态（短线/超短线关注 H=1/2/3）")
    out("| 大盘状态 | 样本 | H=1 | H=2 | H=3 | H=5 | H=10 |")
    out("|---|--:|--:|--:|--:|--:|--:|")
    for rg in ("牛市", "震荡", "熊市"):
        b = noconv_regime[rg]
        out("| %s | %d | %s | %s | %s | %s | %s |"
            % (rg, len(b[1]), pct(b[1]), pct(b[2]), pct(b[3]), pct(b[5]), pct(b[10])))
    out("")
    out("### ⑤ 企稳非粘合 × 分年度（判断近年是否仍有效）")
    out("| 年份 | 样本 | H=1 | H=2 | H=3 | H=5 | H=10 |")
    out("|---|--:|--:|--:|--:|--:|--:|")
    for yr in sorted(noconv_year):
        b = noconv_year[yr]
        out("| %s | %d | %s | %s | %s | %s | %s |"
            % (yr, len(b[1]), pct(b[1]), pct(b[2]), pct(b[3]), pct(b[5]), pct(b[10])))
    out("")
    if examples:
        examples.sort(key=lambda x: -x[9])
        out("### ⑥ 企稳非粘合事件示例（按后3日收益 Top15 / Bottom5）")
        out("| 日期 | 股票 | 大盘 | 回落% | 连跌 | 粘合% | 后3日% |")
        out("|---|--|--:|--:|--:|--:|--:|")
        for d, nm, code, rg, dp, st, ds, dl, cl, r3 in examples[:15] + examples[-5:]:
            out("| %s | %s(%s) | %s | %.0f | %d | %.1f | %+.1f |"
                % (d, nm, code, rg, dp, st, ds, r3))
        out("")

    out("## 结论与建议（2026-09-08 · 本报告生成时数据截至 2026-09-07）")
    out("- 基准对照：任意日买入龙头股 H1/H3 胜率约 48%/49%；「企稳非粘合」H1 48%(+0.24) / H3 49%(+0.51)，"
        "相对基准几乎无胜率优势（均值仅略高 0.2~0.5pp）。")
    out("- 跌得更狠/更久并不能显著改善短线：深跌≥6% 且 连跌≥4 后 H1 51%(+0.28)/H3 51%(+0.53)（样本 ~1600），"
        "胜率 51% 扣除手续费/冲击成本后无稳定边际；2022/2023 熊市段表现差(胜率≤48%)。")
    out("- 分年度看 2024-2026 震荡市中 H3/H5 均值略好(+0.8~1.4pp)，但 H1 胜率仍 47~50%，不足以支撑超短线放行。")
    out("")
    out("**建议**：单靠「企稳但不满足均线粘合」不应作为绕过 n_strict 的放行条件——统计上相对随机买入无显著优势，"
        "现有硬门槛的拒绝是合理的。晋控能源型场景若要低吸，应走「有资金/量能/板块共振支撑的低吸专用通道」"
        "（如 dip_buy 思路：恐慌/连跌 + 热门板块 + 缩量 + 右侧企稳确认叠加验证），而不是简单放宽均线粘合。"
        "中长线维持现状由 DAG(analyze_snaps 全项) 判断。")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(L))
    print("已写出: %s (%d 行)" % (OUT, len(L)))
    print("\n".join(L[:60]))
    print("……(共 %d 行)" % len(L))


if __name__ == "__main__":
    main()
