# -*- coding: utf-8 -*-
"""亏损归因诊断：为什么选股 pipeline 会选到亏损的票？

对任意区间（默认近半年）四周期回溯的每笔交易，回放信号日快照：
- 大盘状态（BULLISH/OSCILLATION/BEARISH/CRASH）
- 个股 13 项检查明细（passCount / 粘合度 / 跌幅 / 量比 / 方向…）
- 行业标签（名称关键词）
- 买入后 20 日走势（forward_ret20，评估选股时点质量）

并按 大盘状态 / 行业 / passCount / 买入月份 聚合盈亏，定位问题环节：
- 若亏损集中在 BEARISH/CRASH 状态信号 → 大盘集体下跌，市场状态未做硬过滤
- 若亏损集中在某几个行业 → 板块/行业维度弱（回测无板块节点）
- 若亏损票多为「刚好及格」(pass==minPassCount) → 个股筛选过松/边缘信号
- 注意：本回测只模拟「均线粘合策略」节点，pipeline 的大盘研判/板块强弱/
  主力资金/新闻拦截/AI精选等节点未参与 —— 这正是要说明的差距。

用法（在 smalltools 目录下）:
  python _loss_diagnose.py                  # 近半年全周期
  python _loss_diagnose.py --months 3       # 近 3 个月
  python _loss_diagnose.py --periods 长线   # 只看长线
  python _loss_diagnose.py --top 200        # 亏损明细最多 200 笔
"""
import argparse
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, PARAMS  # noqa: E402
from _full_cycle_backtest import load_cache, market_state, simulate_trade, stats, SELL_RULES  # noqa: E402
from _pool_filters import extra_filter  # noqa: E402

PERIODS = ["超短", "短线", "中线", "长线"]
RULES_KEY = {"超短": "超短线", "短线": "短线", "中线": "中线", "长线": "长线"}
INDEXES = ["sh000001", "sz399001", "sz399006"]
STATES = ["BULLISH", "OSCILLATION", "BEARISH", "CRASH"]
DEFAULT_START = "2026-02-20"

# 行业关键词 → 板块（近似分类，仅用于亏损聚集性观察）
SECTOR_RULES = [
    ("稀土有色", ["稀土", "钨", "铜", "铝", "钴", "镍", "锂", "钛", "锡", "锌", "铅", "钼", "有色", "资源", "黄金", "矿业"]),
    ("半导体电子", ["半导体", "芯片", "中芯", "澜起", "卓胜微", "长电", "新阳", "至纯", "联创", "容大", "彤程", "电子", "京瓷", "积电"]),
    ("光通信", ["光", "仕佳", "烽火", "铭普", "中际", "旭创", "新易盛", "天孚", "光迅"]),
    ("光伏新能源", ["光伏", "隆基", "东方日升", "阳光电源", "锦浪", "正泰", "晶澳", "通威", "德业"]),
    ("电网设备", ["电网", "南瑞", "风范", "宝胜", "积成", "平高", "特变", "许继", "思源", "国电南自"]),
    ("电力公用", ["电力", "华能", "长电", "明星", "水电", "华电", "大唐", "三峡"]),
    ("医药生物", ["医药", "沃森", "迈瑞", "白药", "生物", "长春", "百济", "恒瑞", "智飞", "康泰", "药明"]),
    ("化工材料", ["化工", "索普", "新材", "昊华", "万华", "华鲁", "恒力", "荣盛", "三友", "中泰"]),
    ("钢铁", ["钢", "西宁", "宝钢", "鞍钢", "太钢", "华菱"]),
    ("消费", ["茅台", "五粮液", "海天", "伊利", "泸州", "美的", "格力", "海尔"]),
]
DEFAULT_SECTOR = "其他"


def sector_of(name):
    for sec, kws in SECTOR_RULES:
        if any(k in name for k in kws):
            return sec
    return DEFAULT_SECTOR


def collect(cache, all_dates, date_to_idx, w_start, w_end, periods):
    """收集窗口内每信号：信号日状态 + 个股检查明细 + 行业 + 交易结果"""
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    scan_dates = [d for d in all_dates if w_start <= d < w_end]
    out = {}
    for period in periods:
        p = PARAMS[period]
        rule = SELL_RULES[RULES_KEY[period]]
        items = []
        for asof in scan_dates:
            st = market_state(idx_snaps, asof, all_dates, date_to_idx)
            for code, ent in cache.items():
                if code.startswith("sh000") or code.startswith("sz399"):
                    continue
                snaps = ent.get("snaps") or []
                sub = [s for s in snaps if s["date"] <= asof]
                if len(sub) < 20:
                    continue
                name = ent.get("name") or code
                sub2 = [dict(s) for s in sub]
                sub2[-1]["name"] = name
                sel_trend = st if st in ("BULLISH", "BEARISH") else ("BEARISH" if st == "CRASH" else "OSCILLATION")
                try:
                    r = analyze_snaps(sub2, p, sel_trend)
                except Exception:
                    continue
                if not r.get("passed"):
                    continue
                # 与 _walk_forward/_full_cycle_backtest 同步的硬过滤（ST/主板/粘合/短线/板块代理）
                if not extra_filter(code, name, r, period, cache, all_dates, date_to_idx, asof):
                    continue
                # 交易模拟
                sig = [code, name, asof, date_to_idx.get(asof, 0) + 1, st]
                t = simulate_trade(cache, all_dates, sig, rule)
                if not t:
                    continue
                # 买入后 20 交易日走势（不借未来：只用于诊断，非决策输入）
                fwd = None
                bi = date_to_idx.get(asof, 0) + 1
                bd = all_dates[bi] if bi < len(all_dates) else None
                if bd:
                    k20 = [s for s in snaps if s["date"] > bd][:20]
                    if k20:
                        fwd = (k20[-1]["close"] / t["entry"] - 1) * 100 if t["entry"] > 0 else None
                items.append(dict(
                    code=code, name=name, asof=asof, state=st, sector=sector_of(name),
                    ret=t["ret"], reason=t["reason"], entry=t["entry"], fwd=fwd,
                    passCount=r["passCount"], totalChecks=r["totalChecks"],
                    checks={k: r["checks"][k][1] for k in r["checks"]},
                    passed_names=[k for k in r["activeChecks"] if r["checks"][k][0]],
                ))
        out[period] = items
    return out


def fmt(x, suffix="%"):
    return "None" if x is None else f"{x:+.2f}{suffix}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", default="")
    ap.add_argument("--end", default="")
    ap.add_argument("--months", type=int, default=0)
    ap.add_argument("--periods", default="")
    ap.add_argument("--top", type=int, default=100)
    args = ap.parse_args()

    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    cache_end = all_dates[-1]
    w_start = args.start or DEFAULT_START
    w_end = args.end or cache_end
    if args.months:
        from datetime import date, timedelta
        ed = date.fromisoformat(args.end or cache_end)
        y, m = ed.year, ed.month - args.months
        y += (m - 1) // 12
        m = (m - 1) % 12 + 1
        w_start = date(y, m, ed.day).isoformat() if ed.day <= 28 else date(y, m, 1).isoformat()
    periods = [p for p in PERIODS if p in args.periods.split(",")] if args.periods else PERIODS
    print(f"股票池 {len(cache)} 只 | 交易日 {all_dates[0]} ~ {cache_end}")
    print(f"诊断窗口: {w_start} ~ {w_end} | 周期: {','.join(periods)}\n")

    data = collect(cache, all_dates, date_to_idx, w_start, w_end, periods)

    grand_loss = 0
    for period in periods:
        items = data[period]
        losses = [i for i in items if i["ret"] < 0]
        wins = [i for i in items if i["ret"] >= 0]
        rets = [i["ret"] for i in items]
        n, avg, wr, cum, pf, mdd = stats(rets)
        grand_loss += len(losses)
        print("=" * 90)
        print(f"【{period}】成交 {n} 笔 | 平均 {avg:+.2f}% | 胜率 {wr:.1f}% | 累计 {cum:+.2f}% | 亏损 {len(losses)} 笔")
        print("=" * 90)

        # ── 1. 大盘状态 × 盈亏 ──
        print("\n[1] 按大盘状态分组的盈亏（判断是否大盘集体下跌）")
        by_st = defaultdict(list)
        for i in items:
            by_st[i["state"]].append(i)
        for st in STATES:
            sub = by_st.get(st)
            if not sub:
                continue
            rl = [i["ret"] for i in sub]
            sn, savg, swr, scum, spf, _ = stats(rl)
            loss_n = sum(1 for x in rl if x < 0)
            print(f"    {st:<12} 成交{sn:<4} 亏损{loss_n:<3} 平均{savg:+.2f}% 胜率{swr:.1f}% 累计{scum:+.2f}%")

        # ── 2. 行业 × 盈亏 ──
        print("\n[2] 按行业分组的盈亏（判断是否板块选错/板块弱）")
        by_sec = defaultdict(list)
        for i in items:
            by_sec[i["sector"]].append(i)
        for sec in sorted(by_sec, key=lambda s: -len(by_sec[s])):
            sub = by_sec[sec]
            rl = [i["ret"] for i in sub]
            sn, savg, swr, scum, spf, _ = stats(rl)
            loss_n = sum(1 for x in rl if x < 0)
            print(f"    {sec:<8} 成交{sn:<4} 亏损{loss_n:<3} 平均{savg:+.2f}% 胜率{swr:.1f}% 累计{scum:+.2f}%")

        # ── 3. passCount 分布（边缘信号 vs 强信号）──
        print("\n[3] 按信号强度(passCount/totalChecks)分组的胜率")
        by_pass = defaultdict(list)
        for i in items:
            by_pass[i["passCount"]].append(i)
        for pc in sorted(by_pass, reverse=True):
            sub = by_pass[pc]
            rl = [i["ret"] for i in sub]
            sn, savg, swr, scum, spf, _ = stats(rl)
            loss_n = sum(1 for x in rl if x < 0)
            print(f"    pass={pc}/{items[0]['totalChecks'] if items else 0}  成交{sn:<4} 亏损{loss_n:<3} 平均{savg:+.2f}% 胜率{swr:.1f}% 累计{scum:+.2f}%")

        # ── 4. 买入月份聚集性 ──
        print("\n[4] 按买入月份分组的盈亏（看是否某个时段集中踩雷）")
        by_mon = defaultdict(list)
        for i in items:
            by_mon[i["asof"][:7]].append(i)
        for mon in sorted(by_mon):
            sub = by_mon[mon]
            rl = [i["ret"] for i in sub]
            sn, savg, swr, scum, spf, _ = stats(rl)
            loss_n = sum(1 for x in rl if x < 0)
            print(f"    {mon}  成交{sn:<4} 亏损{loss_n:<3} 平均{savg:+.2f}% 胜率{swr:.1f}% 累计{scum:+.2f}%")

        # ── 5. 亏损交易明细 ──
        print(f"\n[5] 亏损交易明细（{len(losses)} 笔，按亏损额升序，最多 {args.top} 笔）")
        for i in sorted(losses, key=lambda x: x["ret"])[: args.top]:
            ck = i["checks"]
            print(f"    {i['asof']} {i['name']:<6}[{i['sector']}] 大盘={i['state']} "
                  f"pass={i['passCount']}/{i['totalChecks']} 收益={i['ret']:+.2f}% 买后20日={fmt(i['fwd'])}")
            print(f"        粘合={ck.get('①粘合度','')} 持续={ck.get('③粘合持续','')} "
                  f"量能={ck.get('④量能','')} 跌幅={ck.get('⑤跌幅','')} "
                  f"多头={ck.get('②多头排列','')} 站上={ck.get('⑨站上均线','')} "
                  f"三日不新低={ck.get('⑬三日不新低','')}")
        if losses:
            # 亏损票共性：买入后 20 日走势分布
            fwds = [i["fwd"] for i in losses if i["fwd"] is not None]
            if fwds:
                print(f"    亏损票买后20日: 平均{sum(fwds)/len(fwds):+.2f}%  中位{sorted(fwds)[len(fwds)//2]:+.2f}%  "
                      f"max{max(fwds):+.2f}% min{min(fwds):+.2f}%")
        print()

    print("=" * 90)
    print(f"合计亏损交易 {grand_loss} 笔。诊断要点见上表：")
    print("  · 若某状态（BEARISH/CRASH）成交多且全亏 → 大盘过滤不足")
    print("  · 若亏损集中在少数行业 → 板块维度未参与回测（缺 sector 节点模拟）")
    print("  · 若亏损票多为 pass=minPassCount → 边缘信号过多，应提高门槛或加排名裁切")
    print("  · 注意：本回测仅模拟「均线粘合策略」节点；大盘研判/板块强弱/主力资金/")
    print("    新闻拦截/AI精选等 pipeline 节点未参与，真实 App 链路过一遍后信号会大幅收窄")
    print("=" * 90)


if __name__ == "__main__":
    main()
