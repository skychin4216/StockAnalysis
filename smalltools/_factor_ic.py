# -*- coding: utf-8 -*-
"""
选中信号因子 IC/ICIR 检验（Spearman 秩相关）
========================================================================
目的：在四周期【已选中】信号上，检验各候选因子的排序预测力，
找到"选出来之后还能进一步排序择优"的因子（AutoQuant IC/ICIR 方法论）。

方法（无未来函数）：
- 信号：复用 _records/selected_*.json 已持久化的选中记录（code/asof/大盘状态）
- 因子：对信号日重新跑 analyze_snaps 提取：粘合度/量比/跌幅/粘合天数/当日涨幅/
        换手率/近5日动量/距MA60乖离/距MA250乖离
- 未来收益：信号次日开盘买入，持有 H 个交易日（超短5/短10/中20/长30）收盘卖出
- 按月分组计算 Spearman IC → IC 序列 → mean(IC)/ICIR=mean/std/IC>0占比
- 判定：|ICIR|>=0.3 稳定可用；0.1~0.3 弱预测；<0.1 无预测力

产出：_records/factor_ic.json + 控制台排名表

用法：
  python _factor_ic.py                 # 全部
  python _factor_ic.py --periods 中线,长线
"""
import argparse
import json
import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS
from _full_cycle_backtest import load_cache

RECORD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_records")
PERIODS = ["超短", "短线", "中线", "长线"]
STATES = ["BULLISH", "OSCILLATION", "BEARISH", "CRASH"]
HORIZON = {"超短": 5, "短线": 10, "中线": 20, "长线": 30}

FACTORS = [
    ("convergenceDegree", "粘合度(越低越紧)", -1),
    ("volumeRatio", "量比", 1),
    ("drawdownPct", "距高点跌幅%", 1),
    ("convergenceDays", "粘合持续天数", 1),
    ("changePctReal", "当日涨幅%", 1),
    ("turnover", "换手率%", 1),
    ("momentum5", "近5日动量%", 1),
    ("ma60Bias", "距MA60乖离%", 1),
    ("ma250Bias", "距MA250乖离%", 1),
]


def avg(xs):
    return sum(xs) / len(xs) if xs else 0.0


def rankdata(vals):
    """平均秩排名（处理并列），返回与输入等长的排名列表"""
    order = sorted(range(len(vals)), key=lambda i: vals[i])
    ranks = [0.0] * len(vals)
    i = 0
    n = len(vals)
    while i < n:
        j = i
        while j + 1 < n and vals[order[j + 1]] == vals[order[i]]:
            j += 1
        avg_rank = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            ranks[order[k]] = avg_rank
        i = j + 1
    return ranks


def pearson(x, y):
    n = len(x)
    if n < 3:
        return 0.0
    mx, my = avg(x), avg(y)
    cov = sum((x[i] - mx) * (y[i] - my) for i in range(n))
    vx = sum((v - mx) ** 2 for v in x)
    vy = sum((v - my) ** 2 for v in y)
    if vx == 0 or vy == 0:
        return 0.0
    return cov / math.sqrt(vx * vy)


def spearman(x, y):
    return pearson(rankdata(x), rankdata(y))


def ma(closes, n):
    return avg(closes[-n:]) if len(closes) >= n else None


def extract_factors(sub, p, sel_trend):
    """调用 analyze_snaps 提取因子；分析失败返回 None"""
    try:
        r = analyze_snaps(sub, p, sel_trend)
    except Exception:
        return None
    if "error" in r:
        return None
    closes = [s["close"] for s in sub]
    ma5 = avg(closes[-5:]) if len(closes) >= 5 else None
    ma60 = ma(closes, 60)
    ma250 = ma(closes, 250)
    f = {
        "convergenceDegree": r["convergenceDegree"],
        "volumeRatio": r["volumeRatio"],
        "drawdownPct": r["drawdownPct"],
        "convergenceDays": r["convergenceDays"],
        "changePctReal": r["changePctReal"],
        "turnover": r["turnover"],
        "momentum5": (closes[-1] / ma5 - 1) * 100 if ma5 else None,
        "ma60Bias": (closes[-1] / ma60 - 1) * 100 if ma60 else None,
        "ma250Bias": (closes[-1] / ma250 - 1) * 100 if ma250 else None,
    }
    return f


def fwd_return(snaps, buy_date, horizon, date_list):
    """信号次日开盘买入，持有 horizon 交易日收盘卖出。返回百分比收益或 None"""
    by_date = {s["date"]: s for s in snaps}
    if buy_date not in by_date or by_date[buy_date]["open"] <= 0:
        return None
    buys = [s for s in snaps if s["date"] >= buy_date]
    if len(buys) < horizon + 1:
        return None
    buy_open = buys[0]["open"]
    sell_close = buys[horizon]["close"]
    if buy_open <= 0:
        return None
    return (sell_close / buy_open - 1) * 100


def load_signals():
    if not os.path.isdir(RECORD_DIR):
        return []
    out = []
    for f in sorted(os.listdir(RECORD_DIR)):
        if not (f.startswith("selected_") and f.endswith(".json")):
            continue
        rec = json.load(open(os.path.join(RECORD_DIR, f), encoding="utf-8"))
        for p in PERIODS:
            for sig in rec.get("signals", {}).get(p, []):
                out.append((p, sig[0], sig[1], sig[2], sig[4]))
    return out


def ic_summary(ic_by_month, sign_hint):
    """ic_by_month: {month: ic}; sign_hint: 期望符号(+1/-1)"""
    if not ic_by_month:
        return None
    ics = [ic_by_month[m] for m in sorted(ic_by_month)]
    mean_ic = avg(ics)
    std_ic = math.sqrt(sum((v - mean_ic) ** 2 for v in ics) / len(ics)) if len(ics) > 1 else 0.0
    icir = mean_ic / std_ic if std_ic > 0 else 0.0
    pos = sum(1 for v in ics if v > 0) / len(ics)
    return dict(mean_ic=mean_ic, std_ic=std_ic, icir=icir, ic_pos=pos, n_months=len(ics))


def analyze(cache, all_dates, signals):
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    # 预建每股票 snaps 与日期→收盘映射
    stock_snaps = {code: ent.get("snaps") or [] for code, ent in cache.items()}

    rows = []  # (period, month, code, name, asof, state, factors, fwd)
    for period, code, name, asof, st in signals:
        snaps = stock_snaps.get(code)
        if not snaps:
            continue
        sub = [s for s in snaps if s["date"] <= asof]
        if len(sub) < 20:
            continue
        sub = [dict(s) for s in sub]
        sub[-1]["name"] = name
        sel_trend = st if st in ("BULLISH", "BEARISH") else ("BEARISH" if st == "CRASH" else "OSCILLATION")
        f = extract_factors(sub, PARAMS[period], sel_trend)
        if f is None:
            continue
        buy_idx = date_to_idx.get(asof, -1) + 1
        buy_date = all_dates[buy_idx] if 0 <= buy_idx < len(all_dates) else None
        if not buy_date:
            continue
        fwd = fwd_return(snaps, buy_date, HORIZON[period], all_dates)
        if fwd is None:
            continue
        rows.append((period, asof[:7], code, name, asof, st, f, fwd))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--periods", default=",".join(PERIODS),
                    help="逗号分隔周期，默认全部")
    args = ap.parse_args()
    want = set(args.periods.split(","))

    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    signals = load_signals()
    print(f"缓存 {len(cache)} 只 | 信号 {len(signals)} 条")

    rows = analyze(cache, all_dates, signals)
    result = {}
    for period in want:
        sub = [r for r in rows if r[0] == period]
        if not sub:
            print(f"\n【{period}】无有效信号（需有足够未来数据）")
            continue
        # 按月分组算各因子 IC
        by_month = defaultdict(lambda: defaultdict(list))  # month -> factor -> [fwd...] 待分组
        for _, month, code, name, asof, st, f, fwd in sub:
            # 按因子收集 (factor_val, fwd)
            pass
        # 重新组织：month -> factor -> list of (val, fwd)
        month_fac = defaultdict(lambda: defaultdict(list))
        for _, month, code, name, asof, st, f, fwd in sub:
            for fkey, _, _ in FACTORS:
                if f.get(fkey) is not None:
                    month_fac[month][fkey].append((f[fkey], fwd))
        table = []
        for fkey, fname, sign in FACTORS:
            ic_by_month = {}
            for month, facs in month_fac.items():
                pairs = facs.get(fkey)
                if not pairs or len(pairs) < 3:
                    continue
                xs = [p[0] for p in pairs]
                ys = [p[1] for p in pairs]
                if len(set(xs)) < 2:
                    continue
                ic_by_month[month] = spearman(xs, ys)
            s = ic_summary(ic_by_month, sign)
            if not s:
                continue
            # 期望符号对齐：IC 与 sign 同向才说明因子方向符合直觉
            aligned_icir = s["icir"] * sign
            table.append((fname, s["mean_ic"], s["icir"], s["ic_pos"],
                          s["n_months"], aligned_icir, fkey))
        table.sort(key=lambda t: -abs(t[5]))
        print(f"\n{'=' * 96}\n【{period}】样本 {len(sub)} | 持有 {HORIZON[period]} 交易日 | "
              f"因子 IC/ICIR（Spearman，按月滚动）\n{'=' * 96}")
        print(f"{'因子':<18}{'mean IC':>9}{'ICIR':>8}{'IC>0占比':>9}{'月数':>5}  {'判定':<10}")
        for fname, mic, icir, ipos, nmon, aicir, fkey in table:
            if abs(aicir) >= 0.3:
                verdict = "★★ 稳定可排序"
            elif abs(aicir) >= 0.1:
                verdict = "△ 弱预测"
            else:
                verdict = "— 无预测力"
            print(f"{fname:<18}{mic:>+9.3f}{icir:>+8.3f}{ipos:>8.0%}{nmon:>5}  {verdict:<10}")
            if fkey in ("convergenceDegree", "drawdownPct"):
                hint = "（IC为负=值越小收益越高）" if icir < 0 else "（IC为正=值越大收益越高）"
                print(f"    {hint}")
        best = table[0] if table else None
        if best:
            print(f"  >> 最优排序因子: {best[0]}（ICIR {best[2]:+.2f}）")
            result[period] = dict(
                n=len(sub), horizon=HORIZON[period],
                best_factor=best[0], best_icir=best[2],
                factors=[dict(name=fname, mean_ic=mic, icir=icir, ic_pos=ipos, n_months=nmon)
                         for fname, mic, icir, ipos, nmon, _, _ in table])

    with open(os.path.join(RECORD_DIR, "factor_ic.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    print("\n结果已保存 _records/factor_ic.json")


if __name__ == "__main__":
    main()
