# -*- coding: utf-8 -*-
"""
2+3 效果对比回测（PC 端）：
  无 2+3（baseline）：IC 排序（不含方向/季节/龙头伪因子），全部信号按分参与组合
  有 2+3（with23）：  IC 排序 + ①先判方向（中线剔除下降/震荡，长线仅剔下降）
                      ②行业季节日历加成 ③龙头股优先 → 组合模拟（并发持仓上限）

端口自 Kotlin：
  - DirectionAnalyzer           → direction_of()
  - IndustrySeasonalityCalendar → SEASONALITY + seasonality_bonus()
  - LeaderTracker               → leader_bonus()
  - UnifiedStockClassifier.icRank → 因子百分位秩加权（含 direction/seasonality 伪因子）

统计口径与 _full_cycle_backtest.py 一致：固定本金（每笔等额、收益相加不复利）。
组合模拟：并发持仓 ≤ MAX_POSITIONS，信号按得分降序优先买入（体现排序选股价值）。
重点对比：中线 / 长线；超短/短线仅作参考。
"""
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps
from _full_cycle_backtest import (load_cache, market_state, filter_asof, simulate_trade,
                                   stats, WINDOWS, SELL_RULES, INDEXES)

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")

# 并发持仓上限（近似 AutoTradePortfolioEngine.maxHoldings）
MAX_POSITIONS = {"超短线": 3, "短线": 3, "中线": 3, "长线": 2}

# 与 backtest_params.json seasonality.anchors 一致
ANCHORS = {"锂价": "down", "油价": "up", "铜价": "up", "金价": "up"}

# 行业归属（按名称关键词，覆盖缓存 111 只核心龙头）
SECTOR_KEYWORDS = {
    "半导体": ["半导体", "芯片", "中芯", "华创", "韦尔", "紫光", "澜起", "兆易", "长电", "通富", "华天", "北方", "睿创", "晶方"],
    "光通信/算力": ["光模块", "光通信", "旭创", "新易盛", "天孚", "光迅", "烽火", "中天", "亨通", "浪潮", "曙光", "神州", "海光"],
    "AI应用/软件": ["讯飞", "昆仑", "三六零", "拓尔思", "万兴", "金山", "用友"],
    "PCB/消费电子": ["沪电", "深南", "生益", "景旺", "东山", "鹏鼎", "立讯", "歌尔", "蓝思", "京东方", "工业富联"],
    "电网设备": ["特变", "平高", "南瑞", "许继", "思源", "金盘", "华明", "长高", "西电", "东方电气"],
    "光伏": ["隆基", "通威", "晶澳", "阳光", "天合", "晶科", "福斯特", "锦浪", "固德威"],
    "新能源车/电池": ["宁德", "比亚迪", "亿纬", "国轩", "孚能", "欣旺达", "拓普", "三花", "汇川"],
    "锂矿/锂电材料": ["赣锋", "天齐", "华友", "格林美", "永兴", "天赐", "多氟多", "当升"],
    "风电/电力": ["明阳", "金风", "运达", "湘电", "日月", "新强联", "华能", "中国核电", "长江电力"],
    "军工": ["中航", "航发", "航天", "洪都", "沈飞", "成飞", "内蒙一机"],
    "化工/材料": ["万华", "华鲁", "巨化", "昊华", "索普", "三棵树", "中材科技"],
    "有色/金属": ["紫金", "江西铜业", "铜陵", "北方稀土", "盛和", "锡业", "中钨", "华峰"],
    "油气/石化": ["中国石化", "中国石油", "中海油", "荣盛", "恒力", "东方盛虹"],
    "消费/医药": ["茅台", "五粮液", "恒瑞", "迈瑞", "药明", "爱尔", "片仔癀", "云南白药"],
}


def sector_of(name):
    for sector, kws in SECTOR_KEYWORDS.items():
        if any(k in name for k in kws):
            return sector
    return None


# ── ① 个股方向（与 Kotlin DirectionAnalyzer 同口径） ──
def direction_of(sub):
    closes = [s["close"] for s in sub]
    n = len(closes)
    if n < 30:
        return "OSCILLATION"
    ma5 = sum(closes[-5:]) / 5
    ma10 = sum(closes[-10:]) / 10
    ma20 = sum(closes[-20:]) / 20
    ma60 = sum(closes[-60:]) / 60 if n >= 60 else None
    latest = sub[-1]
    win = sub[-20:]
    hi = max(s["high"] for s in win)
    lo = min(s["low"] for s in win)
    conv = (hi - lo) / lo * 100 if lo > 0 else 999.0
    conv_ok = 0.1 <= conv <= 6.0
    v5 = sum(s["volume"] for s in sub[-5:]) / 5
    v20 = sum(s["volume"] for s in sub[-20:]) / 20
    vr = v5 / v20 if v20 > 0 else 1.0
    above_top = latest["close"] >= hi * 0.995
    above_all = latest["close"] > ma20
    ma60_rising = ma60 is not None and n >= 61 and closes[-61] < ma60
    if conv_ok and above_top and vr >= 1.15 and above_all:
        return "BREAKOUT"
    if conv_ok:
        return "ACCUMULATION"
    if ma5 > ma10 > ma20 and latest["close"] > ma5 and ma60_rising:
        return "UPTREND"
    if ma5 < ma10 < ma20 and latest["close"] < ma5 and not ma60_rising:
        return "DOWNTREND"
    return "OSCILLATION"


DIRECTION_SCORE = {"BREAKOUT": 4.0, "UPTREND": 3.0, "ACCUMULATION": 2.0,
                   "OSCILLATION": 0.0, "DOWNTREND": -2.0}

# 有2+3 时的方向过滤（先判方向再定周期）：
#   中线 只买 蓄势/上升/突破（剔除 下降+震荡）
#   长线 只剔 下降趋势（龙头池长期持有时震荡后仍易上行，保留震荡）
EXCLUDE_DIR = {
    "超短线": set(),
    "短线": set(),
    "中线": {"DOWNTREND", "OSCILLATION"},
    "长线": {"DOWNTREND"},
}


# ── ② 行业季节/周期日历（与 Kotlin IndustrySeasonalityCalendar 同口径） ──
# (主题, 关键词, 起始月, 结束月, 权重, 锚定品种, 锚定上涨时利好)
SEASONALITY = [
    ("春耕备耕", ["化肥", "农药", "种业", "农机", "尿素", "钾肥", "磷肥"], 2, 4, 1.2, None, True),
    ("年报预增季", ["预增"], 1, 4, 0.8, None, True),
    ("汛期防汛", ["水利", "防汛", "管网"], 5, 7, 0.9, None, True),
    ("夏季用电高峰", ["电力", "电网", "特高压", "变压器", "光伏", "储能"], 6, 8, 1.1, None, True),
    ("光伏装机旺季", ["光伏", "逆变器", "多晶硅"], 6, 11, 0.9, None, True),
    ("中报预增季", ["预增"], 7, 8, 0.8, None, True),
    ("金九银十", ["消费电子", "汽车电子", "家电", "PCB", "铜缆"], 9, 10, 1.0, None, True),
    ("年底备货", ["半导体", "算力", "存储", "光模块", "光通信", "PCB"], 11, 12, 1.0, None, True),
    ("供暖季", ["燃气", "煤炭", "供热", "电力"], 11, 1, 0.9, None, True),
    ("锂价上涨", ["锂", "盐湖"], 1, 12, 1.0, "锂价", True),
    ("锂价下跌(电池受益)", ["电池", "正极", "负极", "电解液"], 1, 12, 0.8, "锂价", False),
    ("油价上涨", ["石油", "油气", "油服", "石化"], 1, 12, 1.0, "油价", True),
    ("铜价上涨", ["铜", "电缆"], 1, 12, 1.0, "铜价", True),
    ("金价上涨", ["黄金", "贵金属"], 1, 12, 0.9, "金价", True),
]


def in_window(month, f, t):
    return (f <= month <= t) if f <= t else (month >= f or month <= t)


def seasonality_bonus(name, asof):
    month = int(asof[5:7])
    total = 0.0
    hits = []
    for theme, kws, f, t, w, anchor, up in SEASONALITY:
        if not in_window(month, f, t):
            continue
        if anchor is not None:
            if ANCHORS.get(anchor) != ("up" if up else "down"):
                continue
        if any(k in name for k in kws):
            total += w
            hits.append(theme)
    return round(total * 10, 1), hits


# ── IC 因子（来自 factor_ic.json；direction/seasonality/leader 为 v6 伪因子） ──
IC_FACTORS = {
    "超短线": [("convergence", -0.386), ("change", 0.339), ("direction", 0.4), ("seasonality", 0.15)],
    "短线": [("change", -0.331), ("volume", 0.291), ("direction", 0.4), ("seasonality", 0.15)],
    "中线": [("ma250bias", -0.57), ("momentum5", -0.396), ("direction", 0.35), ("seasonality", 0.3)],
    "长线": [("momentum5", -0.685), ("drawdown", 0.505), ("direction", 0.35), ("seasonality", 0.3)],
}
LEADER_WEIGHT = 0.4  # 龙头伪因子权重


def factor_value(sub, f):
    closes = [s["close"] for s in sub]
    n = len(closes)
    latest = sub[-1]
    if f == "convergence":
        win = sub[-20:]
        hi = max(s["high"] for s in win)
        lo = min(s["low"] for s in win)
        return (hi - lo) / lo * 100 if lo > 0 else 999.0
    if f == "change":
        prev = closes[-2] if n >= 2 else latest["close"]
        return (latest["close"] / prev - 1) * 100 if prev > 0 else 0.0
    if f == "volume":
        v5 = sum(s["volume"] for s in sub[-5:]) / 5
        v20 = sum(s["volume"] for s in sub[-20:]) / 20
        return v5 / v20 if v20 > 0 else 1.0
    if f == "momentum5":
        ma5 = sum(closes[-5:]) / 5
        return (latest["close"] / ma5 - 1) * 100 if ma5 > 0 else 0.0
    if f == "ma60bias":
        if n < 60:
            return float("nan")
        ma60 = sum(closes[-60:]) / 60
        return (latest["close"] / ma60 - 1) * 100
    if f == "ma250bias":
        if n < 250:
            return float("nan")
        ma250 = sum(closes[-250:]) / 250
        return (latest["close"] / ma250 - 1) * 100
    if f == "drawdown":
        peak = max(s["close"] for s in sub[-60:])
        return (latest["close"] / peak - 1) * 100 if peak > 0 else 0.0
    if f == "direction":
        return DIRECTION_SCORE.get(direction_of(sub), 0.0)
    if f == "seasonality":
        bonus, _ = seasonality_bonus(sub[-1].get("name", ""), sub[-1]["date"])
        return bonus
    return 0.0


def pct_rank(values):
    """平均秩法百分位秩 0..1；NaN 视为缺失 → 0.5 中性"""
    n = len(values)
    present = [(i, v) for i, v in enumerate(values) if v == v]  # NaN != NaN
    ranks = [0.5] * n
    m = len(present)
    if m < 2:
        return ranks
    order = sorted(present, key=lambda x: x[1])
    i = 0
    while i < m:
        j = i
        while j + 1 < m and order[j + 1][1] == order[i][1]:
            j += 1
        avg = (i + j) / 2.0 / (m - 1)
        for k in range(i, j + 1):
            ranks[order[k][0]] = avg
        i = j + 1
    return ranks


def collect_day_signals(cache, all_dates, date_to_idx, period, use23):
    """逐日收集信号：方向过滤(有23) + 因子百分位秩打分；返回携带 score 的信号 dict 列表"""
    start, end, _ = WINDOWS[period]
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    scan_dates = [d for d in all_dates if start <= d <= end]
    factors = IC_FACTORS[period]
    if not use23:
        factors = [(f, w) for f, w in factors if f not in ("direction", "seasonality", "leader")]
    if not factors:
        factors = [("momentum5", 1.0)]

    signals = []
    state_counter = Counter()
    for asof in scan_dates:
        st = market_state(idx_snaps, asof, all_dates, date_to_idx)
        state_counter[st] += 1
        cands = []
        for code, ent in cache.items():
            if code.startswith("sh000") or code.startswith("sz399"):
                continue
            snaps = ent.get("snaps") or []
            sub = filter_asof(snaps, asof)
            if len(sub) < 20:
                continue
            name = ent.get("name") or code
            sub2 = [dict(s) for s in sub]
            sub2[-1]["name"] = name
            sel_trend = st if st in ("BULLISH", "BEARISH") else ("BEARISH" if st == "CRASH" else "OSCILLATION")
            try:
                ok = bool(analyze_snaps(sub2, WINDOWS[period][2], sel_trend).get("passed"))
            except Exception:
                continue
            if not ok:
                continue
            if use23 and EXCLUDE_DIR.get(period):
                if direction_of(sub2) in EXCLUDE_DIR[period]:
                    continue
            cands.append(dict(code=code, name=name, asof=asof, buy_idx=date_to_idx.get(asof, 0) + 1,
                              state=st, sub=sub2))
        if not cands:
            continue
        # 因子百分位秩打分
        n_c = len(cands)
        scores = [0.0] * n_c
        for f, w in factors:
            pr = pct_rank([factor_value(c["sub"], f) for c in cands])
            for i in range(n_c):
                scores[i] += w * pr[i]
        # 龙头伪因子：同板块最高分者为龙头，加分
        if use23:
            leader_idx = {}
            for i, c in enumerate(cands):
                sec = sector_of(c["name"])
                if sec and (sec not in leader_idx or scores[i] > scores[leader_idx[sec]]):
                    leader_idx[sec] = i
            for sec, i in leader_idx.items():
                scores[i] += LEADER_WEIGHT
        for i, c in enumerate(cands):
            c["score"] = scores[i]
            signals.append(c)
    return signals, state_counter


def run_mode(cache, all_dates, date_to_idx, period, use23):
    """组合模拟：并发持仓 ≤ MAX_POSITIONS，信号按(买入日, 得分降序)优先买入。
    资金占满时跳过后续信号（机会成本），体现排序选股 + 容量的真实收益。"""
    sigs, state_counter = collect_day_signals(cache, all_dates, date_to_idx, period, use23)
    label = "有2+3" if use23 else "无2+3"
    print(f"\n{'─' * 78}")
    print(f"【{period} · {label}】信号 {len(sigs)} 个（并发持仓 ≤{MAX_POSITIONS[period]} 只）")
    if not sigs:
        return None
    sigs.sort(key=lambda s: (s["buy_idx"], -s["score"]))
    max_pos = MAX_POSITIONS[period]
    active = []  # (end_idx, ...)
    trades = []
    skipped = 0
    for sig in sigs:
        buy_idx = sig["buy_idx"]
        # 释放已到期持仓（卖出日索引 < 当前买入日）
        active = [a for a in active if a[0] > buy_idx]
        if len(active) >= max_pos:
            skipped += 1
            continue
        t = simulate_trade(cache, all_dates,
                           (sig["code"], sig["name"], sig["asof"], sig["buy_idx"], sig["state"]),
                           SELL_RULES[period])
        if not t:
            continue
        trades.append(t)
        end_idx = date_to_idx.get(t["sell"], buy_idx + SELL_RULES[period]["maxHold"])
        active.append((end_idx, t["ret"]))
    rets = [t["ret"] for t in trades]
    n, avg, wr, cum, pf, mdd = stats(rets)
    per_opp = cum / len(sigs) if sigs else 0.0  # 每机会收益（归一化交易数量影响）
    print(f"  已买入: {n}/{len(sigs)} (跳过 {skipped})   平均 {avg:+.2f}%   胜率 {wr:.1f}%   "
          f"累计 {cum:+.2f}%   每机会 {per_opp:+.2f}%   "
          f"盈亏因子 {'∞' if pf == float('inf') else f'{pf:.2f}'}   回撤 {mdd:.2f}%")
    return dict(period=period, label=label, sigs=len(sigs), n=n, skipped=skipped,
                avg=avg, wr=wr, cum=cum, per_opp=per_opp, pf=pf, mdd=mdd,
                trades=trades, state_counter=state_counter)


def compare(cache, all_dates, date_to_idx, period):
    base = run_mode(cache, all_dates, date_to_idx, period, use23=False)
    w23 = run_mode(cache, all_dates, date_to_idx, period, use23=True)
    print(f"\n  → 对比: 有2+3 vs 无2+3")
    if base and w23:
        d_opp = w23["per_opp"] - base["per_opp"]
        d_avg = w23["avg"] - base["avg"]
        d_wr = w23["wr"] - base["wr"]
        d_pf = w23["pf"] - base["pf"]
        d_mdd = w23["mdd"] - base["mdd"]
        tag = "✅ 提升" if (d_avg > 0.3 and d_wr > -2) else ("⚠ 持平" if abs(d_avg) < 0.3 else "❌ 下降")
        print(f"    每机会收益:  {base['per_opp']:+.2f}% → {w23['per_opp']:+.2f}% ({d_opp:+.2f}%)")
        print(f"    平均每笔:    {base['avg']:+.2f}% → {w23['avg']:+.2f}% ({d_avg:+.2f}%)  {tag}")
        print(f"    胜率:        {base['wr']:.1f}% → {w23['wr']:.1f}% ({d_wr:+.1f}%)")
        print(f"    盈亏因子:    {base['pf']:.2f} → {w23['pf']:.2f} ({d_pf:+.2f})")
        print(f"    最大回撤:    {base['mdd']:.2f}% → {w23['mdd']:.2f}% ({d_mdd:+.2f}%)")
    return (base, w23)


def main():
    cache = load_cache()
    dates = set()
    for code, ent in cache.items():
        for s in ent.get("snaps", []):
            dates.add(s["date"])
    all_dates = sorted(dates)
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    print("=" * 78)
    print("2+3 效果对比回测 | 数据: _kline_cache.json | 口径: 固定本金(不复利)")
    print("有2+3 = IC排序 + ①方向过滤(中线剔下降/震荡，长线仅剔下降) + ②季节日历 + ③龙头优先 + 组合容量")
    print("=" * 78)
    results = {}
    for period in ["超短线", "短线", "中线", "长线"]:
        results[period] = compare(cache, all_dates, date_to_idx, period)
    print("\n" + "=" * 78)
    print("汇总表（每机会收益% / 平均每笔% / 胜率% / 盈亏因子 / 最大回撤%）")
    print("=" * 78)
    hdr = f"{'周期':<6} {'指标':<12} {'无2+3':>12} {'有2+3':>12} {'差值':>10}"
    print(hdr)
    for period, (b, w) in results.items():
        if not b or not w:
            continue
        for key, name in [("per_opp", "每机会收益"), ("avg", "平均每笔"), ("wr", "胜率"),
                          ("pf", "盈亏因子"), ("mdd", "最大回撤")]:
            fmt = ".2f"
            print(f"{period:<6} {name:<12} {b[key]:>+11.2f}% {w[key]:>+11.2f}% {w[key]-b[key]:>+9.2f}%")
    print("\n结论参考：中线/长线重点看 平均每笔/胜率/盈亏因子/回撤 的质量提升；")
    print("「每机会收益」已归一化交易数量，避免「少交易=累计低」的假象。")


if __name__ == "__main__":
    main()
