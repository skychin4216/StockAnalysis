# -*- coding: utf-8 -*-
"""
十字星分歧信号 两场景回溯统计（2015-01 ~ 2026-09-07）
=====================================================
数据：smalltools/_kline_cache.json（上证 sh000001 / 科创50 sh000688 + 龙头股池，含 name）

用户思路：
  大盘(上证/科创50) 连续跌几天(下跌末端) → 出现十字星(分歧) →
    场景1: 之后出现【放量大阴线】(恐慌释放) → 大阴线当天低吸【下跌比较多∩前期热门】股票
    场景2: 之后出现【阳线(小阳/大阳)】(转强确认) → 阳线日收盘买入，看后续继续上升概率

口径：
  - 买入=信号日收盘，卖出=第 H 日收盘(close→close)。
  - 十字星 = |收盘-开盘| ≤ 0.2×振幅；可选"缩量"= 星日量 ≤ 前5日均量×0.9。
  - 放量大阴线 = 当日涨幅 ≤ -1.5%，且量 ≥ 前5日均量×1.2；出现在十字星后 1..7 交易日(取最先满足者)。
  - 阳线日 = 十字星后第一个 涨幅>0 的交易日(1..7 日内)。
  - 热门 = 全池个股在事件日前 60 日涨幅 前30% 分位。
  - 跌得多 = 个股当日涨幅阈值 -2.5% / -3.5%。
"""
import json
import os
import random

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "_kline_cache.json")
OUT = os.path.join(os.path.dirname(HERE), "AutoQuant", "backtest_logs", "_dip_crossstar_report.md")
IDX = {"sh000688": "科创50", "sh000001": "上证指数"}
START = "2015-01-01"
HORIZONS = [1, 2, 3, 5, 10]


def load():
    with open(CACHE, encoding="utf-8") as f:
        return json.load(f)


def seq_of(cache, code):
    snaps = sorted((cache.get(code) or {}).get("snaps") or [], key=lambda s: s["date"])
    return [s for s in snaps if s["date"] >= START]


def is_doji(s, thr=0.20):
    hi, lo = s.get("high"), s.get("low")
    if not hi or not lo or hi <= lo:
        return False
    return abs(s.get("close", 0) - s.get("open", 0)) <= thr * (hi - lo)


def ma_vol(seq, i, n=5):
    if i < n:
        return None
    vals = [seq[j].get("volume") for j in range(i - n, i) if seq[j].get("volume")]
    return sum(vals) / len(vals) if len(vals) == n else None


def down_days(seq, i, cap=12):
    """第 i 日(含)往回连续下跌天数"""
    n = 0
    for j in range(i, max(i - cap, -1), -1):
        c = seq[j].get("changePct")
        if c is None or c >= 0:
            break
        n += 1
    return n


def fmt(rets):
    if not rets:
        return "-"
    w = sum(1 for x in rets if x > 0) / len(rets) * 100
    avg = sum(rets) / len(rets)
    gains = [x for x in rets if x > 0]
    loss = [x for x in rets if x <= 0]
    pl = "-"
    if gains and loss:
        pl = "%.2f" % (sum(gains) / len(gains) / (-sum(loss) / len(loss)))
    return "%d %.0f%% %+.2f%% %s" % (len(rets), w, avg, pl)


def esc_cell(arr):
    """H日内最高价≥买入成本的占比 / 该日内平均最高点收益(相对成本)——反弹出逃视角"""
    if not arr:
        return "-"
    p = 100.0 * sum(1 for x in arr if x >= 0) / len(arr)
    return "%d %.0f%% %+.2f%%" % (len(arr), p, sum(arr) / len(arr))


def main():
    cache = load()
    L = []
    out = lambda *a: L.append(" ".join(str(x) for x in a))  # noqa: E731

    # ── 预索引（START 过滤一次）：code → snaps 与 date→pos ──
    SN = {}
    for code, ent in cache.items():
        snaps = seq_of(cache, code)
        SN[code] = snaps
    DI = {code: {s["date"]: j for j, s in enumerate(SN[code])} for code in SN}
    stock_codes = [k for k in SN if k not in IDX and len(SN[k]) > 210]

    out("# 十字星分歧信号 两场景回溯统计（2015-01 ~ 2026-09-07）")
    out("")
    out("> 场景1 恐慌低吸：大盘连跌D → 十字星(分歧) → 放量大阴线(≤-1.5%且放量≥1.2×前5日均量，星后1..7日) 当日"
        "低吸「跌得多∩前期热门(前60日涨幅前30%)」；")
    out("> 场景2 转阳确认：大盘连跌D → 十字星 → 首个阳线日 收盘买入，看后续继续上升概率。")
    out("> 指标格式：样本 胜率% 均值% 盈亏比(盈均/亏均)。买入口径=信号日收盘→第H日收盘。")
    out("")

    for code, cname in IDX.items():
        seq = SN[code]
        n = len(seq)
        if n < 200:
            continue
        out("## %s %s（%d 交易日 %s→%s）" % (cname, code, n, seq[0]["date"], seq[-1]["date"]))
        out("")
        # ── 事件收集：(D, shrink, scen) -> [(低吸/买入日 idx, date)] ──
        events = {}
        for i in range(6, n - 16):
            if not is_doji(seq[i]):
                continue
            streak_before = down_days(seq, i - 1)
            if streak_before < 2:
                continue
            vp = ma_vol(seq, i)
            doji_shrink = bool(vp) and (seq[i].get("volume") or 0) <= vp * 0.9
            for D in (2, 3, 4):
                if streak_before < D:
                    continue
                for shrink in (False, True):
                    if shrink and not doji_shrink:
                        continue
                    # 场景1：星后 1..7 日内第一个 放量大阴线(≤-1.5% 且 ≥1.2×均量)
                    for k in range(i + 1, min(i + 8, n - 11)):
                        c = seq[k].get("changePct")
                        if c is None or c > -1.5:
                            continue
                        vm = ma_vol(seq, k)
                        if vm and (seq[k].get("volume") or 0) < vm * 1.2:
                            continue
                        events.setdefault((D, shrink, 1), []).append((k, seq[k]["date"]))
                        break
                    # 场景2：星后 1..7 日内第一个 阳线
                    for k in range(i + 1, min(i + 8, n - 11)):
                        c = seq[k].get("changePct")
                        if c is None or c <= 0:
                            continue
                        events.setdefault((D, shrink, 2), []).append((k, seq[k]["date"]))
                        break

        # ── 场景1 逐事件明细（星→放量大阴线，唯一事件去重，连跌取星前最深）──
        evm = {}
        for i in range(6, n - 16):
            if not is_doji(seq[i]):
                continue
            sb = down_days(seq, i - 1)
            if sb < 2:
                continue
            for k in range(i + 1, min(i + 8, n - 11)):
                c = seq[k].get("changePct")
                if c is None or c > -1.5:
                    continue
                vm = ma_vol(seq, k)
                if vm and (seq[k].get("volume") or 0) < vm * 1.2:
                    continue
                old = evm.get(i)
                if old is None or sb > old[0]:
                    m20 = sum(x["close"] for x in seq[k - 20:k]) / 20
                    evm[i] = (sb, k, seq[k]["date"], c, k - i, (seq[k]["close"] / m20 - 1) * 100)
                break
        if evm:
            out("### 场景1 逐事件明细（十字星→星后≤7日放量大阴线；后续列为指数收盘收益%）")
            out("| 阴线日 | 星前连跌 | 阴线在星后第几天 | 阴线日跌幅% | 指数vs MA20% | D1 | D2 | D3 | D5 | D10 |")
            out("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|")
            for i in sorted(evm):
                sb, k, d, chg, gap, dev20 = evm[i]
                to_fmt = ["%+.1f%%" % ((seq[k + h]["close"] / seq[k]["close"] - 1) * 100)
                          for h in (1, 2, 3, 5, 10)]
                out("| %s | %d | %d | %.1f | %+.1f | %s |" % (d, sb, gap, chg, dev20, " | ".join(to_fmt)))
            out("")

        random.seed(7)
        for (D, shrink, scen), evs in sorted(events.items()):
            key_txt = "连跌≥%d·十字星%s" % (D, "(缩量)" if shrink else "(任意)")
            if scen == 1:
                groups = ("所有个股", "热门前30%", "热门∩当日跌≤-2.5%", "热门∩当日跌≤-3.5%")
            else:
                groups = ("所有个股", "热门前30%", "热门∩当日也收阳")
            ret_h = {g: {h: [] for h in HORIZONS} for g in groups}
            hi_h = {g: {h: [] for h in HORIZONS} for g in groups}
            pick = random.sample(evs, min(len(evs), 150))
            n_day = 0
            for k, d in pick:
                info = []
                for c in stock_codes:
                    pos = DI[c].get(d)
                    sd = SN[c]
                    if pos is None or pos < 65 or pos + 10 >= len(sd):
                        continue
                    info.append((c, sd, pos))
                if not info:
                    continue
                r60_all = sorted((sd[pos]["close"] / sd[pos - 60]["close"] - 1) * 100
                                 for _, sd, pos in info)
                hot_th = r60_all[max(0, int(len(r60_all) * 0.7) - 1)]
                n_day += 1
                for c, sd, pos in info:
                    day_chg = sd[pos].get("changePct") or 0.0
                    r60 = (sd[pos]["close"] / sd[pos - 60]["close"] - 1) * 100
                    is_hot = r60 >= hot_th
                    base = sd[pos]["close"]
                    grp = set()
                    grp.add("所有个股")
                    if is_hot:
                        grp.add("热门前30%")
                        if scen == 1:
                            if day_chg <= -2.5:
                                grp.add("热门∩当日跌≤-2.5%")
                            if day_chg <= -3.5:
                                grp.add("热门∩当日跌≤-3.5%")
                        elif day_chg > 0:
                            grp.add("热门∩当日也收阳")
                    hi_by_h = {}
                    mh = -1e18
                    for h in HORIZONS:
                        if pos + h < len(sd):
                            mh = max(mh, (sd[pos + h]["high"] / base - 1) * 100)
                            hi_by_h[h] = mh
                    for g in grp:
                        for h in HORIZONS:
                            if pos + h < len(sd):
                                ret_h[g][h].append((sd[pos + h]["close"] / base - 1) * 100)
                                hi_h[g][h].append(hi_by_h[h])
            if scen == 1:
                out("### 场景1 恐慌低吸 [%s] → 大阴线当日收盘 低吸（星后≤7日出现放量大阴线）" % key_txt)
            else:
                out("### 场景2 转阳确认 [%s] → 首个阳线日收盘 买入（后续继续上升概率）" % key_txt)
            out("事件日样本=%d（每事件对全池逐股打分，样本数见各行首列）" % n_day)
            out("| 分组 | H=1 | H=2 | H=3 | H=5 | H=10 |")
            out("|---|--:|--:|--:|--:|--:|")
            for g in groups:
                out("| " + g + " | " + " | ".join(fmt(ret_h[g][h]) for h in HORIZONS) + " |")
            if scen == 1:
                out("")
                out("逃顶速查（低吸后 H 日内 最高价≥成本的占比，样本 均最高点%）：")
                out("| 分组 | H=1 | H=2 | H=3 | H=5 | H=10 |")
                out("|---|--:|--:|--:|--:|--:|")
                for g in groups:
                    out("| " + g + " | " + " | ".join(esc_cell(hi_h[g][h]) for h in HORIZONS) + " |")
            out("")
        out("---")
        out("")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(L))
    print("已写出: %s (%d 行)" % (OUT, len(L)))
    print("\n".join(L))


if __name__ == "__main__":
    main()
