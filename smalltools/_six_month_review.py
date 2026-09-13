# -*- coding: utf-8 -*-
"""四周期任意区间回溯（买什么股+收益）+ 指定周选股 —— 可复用工具。

引擎复用 smalltools 中与 Kotlin 对齐的选股/交易模拟：
- 选股: analyze_snaps（对齐 Kotlin StockCheckPipeline）
- 交易: simulate_trade（超短隔日卖/短线连跌卖/中长线做T+止盈止损）
- 大盘: market_state（三指数 MA 排列 + 暴跌检测）

用法（在 smalltools 目录下执行）:
  python _six_month_review.py                            # 默认：近半年回溯 + 最近一周选股
  python _six_month_review.py --months 5                 # 近 5 个月回溯（信号窗口）
  python _six_month_review.py --months 4 --no-week       # 近 4 个月，跳过周选股
  python _six_month_review.py --start 2026-03-20 --end 2026-08-20   # 自定义起止
  python _six_month_review.py --periods 超短,长线         # 只跑指定周期
  python _six_month_review.py --top 15                   # 每周期成交明细最多 15 笔
  python _six_month_review.py --json out.json            # 同时导出各周期交易明细

说明:
- start/end 是「信号扫描窗口」，半开区间 [start, end)，end 默认取缓存最新交易日；
- 卖出按周期规则使用信号日之后的 K 线（信号扫描不借未来）；
- 统计口径：固定本金（每笔等额投入、收益相加不复利）。
"""
import argparse
import json
import os
import sys
from calendar import monthrange
from datetime import date, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _full_cycle_backtest import load_cache, simulate_trade, stats, SELL_RULES  # noqa: E402
from _walk_forward import collect_signals_window  # noqa: E402
import _pool_filters as pf  # noqa: E402

PERIODS = ["超短", "短线", "中线", "长线"]
RULES_KEY = {"超短": "超短线", "短线": "短线", "中线": "中线", "长线": "长线"}
DEFAULT_START = "2026-02-20"  # 无参数时默认近半年起点


def shift_months(d, n):
    """d 往前/后推 n 个自然月，月末取目标月最后一天，避免溢出。"""
    y, m = d.year, d.month - n
    y += (m - 1) // 12
    m = (m - 1) % 12 + 1
    day = min(d.day, monthrange(y, m)[1])
    return date(y, m, day)


def default_week(end_iso):
    """从 end 推断「最近一周」：返回 [上周一, 上周一+5天)，即周一~周五信号窗口。"""
    e = date.fromisoformat(end_iso)
    mon = e - timedelta(days=e.weekday())   # 本周周一
    last_mon = mon - timedelta(days=7)      # 上周一
    return last_mon.isoformat(), (last_mon + timedelta(days=5)).isoformat()


def review_window(cache, all_dates, date_to_idx, w_start, w_end, periods):
    """回溯 [w_start, w_end) 内各周期信号与成交。返回 {period: dict(sigs, dist, trades)}"""
    out = {}
    for period in periods:
        sigs, dist = collect_signals_window(cache, all_dates, date_to_idx, period, w_start, w_end)
        rule = SELL_RULES[RULES_KEY[period]]
        trades = []
        for sig in sigs:
            t = simulate_trade(cache, all_dates, sig, rule)
            if t:
                trades.append(t)
        out[period] = dict(sigs=sigs, dist=dict(dist), trades=trades)
    return out


def print_review(out, top=99):
    """打印回溯结果，返回汇总统计 {period: (n, avg, wr, cum, pf, mdd)}"""
    grand = {}
    for period, r in out.items():
        trades = r["trades"]
        rets = [t["ret"] for t in trades]
        n, avg, wr, cum, pf, mdd = stats(rets)
        grand[period] = dict(n=n, avg=avg, wr=wr, cum=cum, pf=pf, mdd=mdd)
        print("\n【%s】信号 %d | 已成交 %d | 大盘分布 %s" % (period, len(r["sigs"]), len(trades), r["dist"]))
        print("  平均 %+.2f%% | 胜率 %.1f%% | 固定本金累计 %+.2f%% | 盈亏因子 %s | 最大回撤 %.2f%%"
              % (avg, wr, cum, "INF" if pf == float("inf") else "%.2f" % pf, mdd))
        if trades:
            shown = sorted(trades, key=lambda x: -x["ret"])[:top]
            print("  买到的股票（%d 笔，按收益降序，最多显示 %d 笔）:" % (len(trades), top))
            for t in shown:
                tp = " (做T净+%.2f%%)" % t["tProfit"] if t.get("tProfit") is not None else ""
                print("    %-8s 买%s@%.2f 卖%s@%.2f  %+6.2f%%  [%s]%s"
                      % (t["name"], t["buy"], t["entry"], t["sell"], t["exit"], t["ret"], t["reason"], tp))
    return grand


def print_week(cache, all_dates, date_to_idx, periods, wk_start, wk_end):
    """扫描 [wk_start, wk_end) 的选股信号并汇总「股票×日期」触发周期。"""
    print("\n" + "=" * 78)
    print("【周选股】%s ~ %s（周一~周五扫描）" % (wk_start, wk_end))
    print("=" * 78)
    week_dates = [d for d in all_dates if wk_start <= d < wk_end]
    print("窗口交易日: %s" % (week_dates or "（缓存内无该窗口交易日）"))
    all_week = []
    for period in periods:
        sigs, dist = collect_signals_window(cache, all_dates, date_to_idx, period, wk_start, wk_end)
        print("\n【%s】信号 %d 条 | 大盘分布 %s" % (period, len(sigs), dict(dist)))
        for code, name, asof, idx, st in sigs:
            all_week.append((code, name, asof, period, st))
            print("    %-8s(%s)  选中日 %s  大盘=%s" % (name, code, asof, st))
    uniq = {}
    for code, name, asof, period, st in sorted(all_week, key=lambda x: (x[2], x[3], x[0])):
        uniq.setdefault((code, asof), []).append(period)
    print("\n合计：%d 个「股票×日期」信号，涉及 %d 只股票（按日期/代码排序）"
          % (len(uniq), len({c for c, _ in uniq})))
    for (code, asof), plist in sorted(uniq.items(), key=lambda x: (x[0][1], x[0][0])):
        print("    %s  %-8s(%s)  触发周期: %s" % (asof, cache[code]["name"], code, ",".join(plist)))


def main():
    ap = argparse.ArgumentParser(description="四周期任意区间回溯 + 周选股")
    ap.add_argument("--start", default="", help="信号扫描起始日 YYYY-MM-DD（默认近半年起点）")
    ap.add_argument("--end", default="", help="信号扫描截止日 YYYY-MM-DD，半开区间 [start,end)（默认缓存最新交易日）")
    ap.add_argument("--months", type=int, default=0, help="回溯最近 N 个月（与 --start 互斥，--start 优先）")
    ap.add_argument("--periods", default="", help="只跑指定周期，逗号分隔，如 超短,长线（默认全部）")
    ap.add_argument("--week-start", default="", help="周选股起始日（默认按 --end 自动取最近一周）")
    ap.add_argument("--week-end", default="", help="周选股截止日，半开区间")
    ap.add_argument("--no-week", action="store_true", help="跳过周选股")
    ap.add_argument("--top", type=int, default=99, help="每周期成交明细最多打印 N 笔（默认 99）")
    ap.add_argument("--json", default="", help="把各周期交易明细导出到 json 文件")
    ap.add_argument("--no-main", action="store_true", help="关闭主板开关（保留科创/创业板），默认开启（排除科创/创业）")
    ap.add_argument("--sector-threshold", type=float, default=None,
                    help="板块代理过滤阈值（行业近20日平均涨幅%），默认 -3.0")
    ap.add_argument("--pass-count", default="", help="按周期提高门槛，如 短线:7,中线:7（默认不覆盖）")
    args = ap.parse_args()

    # 主板开关 + 周期门槛覆盖（默认：主板开，超短/短线/中线增强过滤生效，长线豁免）
    pf.MAIN_BOARD = not args.no_main
    if args.sector_threshold is not None:
        pf.SECTOR_THRESHOLD = args.sector_threshold
    if args.pass_count:
        pf.PASS_COUNT = dict(kv.split(":") for kv in args.pass_count.split(","))

    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    cache_end = date.fromisoformat(all_dates[-1])
    print("股票池 %d 只 | 交易日 %s ~ %s" % (len(cache), all_dates[0], all_dates[-1]))
    print("过滤口径: 主板开关=%s | 板块阈值=%s | passCount覆盖=%s"
          % ("开(排除科创/创业)" if pf.MAIN_BOARD else "关(含科创/创业)", pf.SECTOR_THRESHOLD, pf.PASS_COUNT or "无"))

    periods = [p for p in PERIODS if p in args.periods.split(",")] if args.periods else PERIODS
    if not periods:
        print("[错误] --periods 未匹配任何有效周期，可用: %s" % ",".join(PERIODS))
        sys.exit(1)

    # ── 回溯窗口 ──
    end_d = date.fromisoformat(args.end) if args.end else cache_end
    if args.start:
        if args.months:
            print("[提示] 已指定 --start，--months 忽略")
        w_start = args.start
    elif args.months:
        w_start = shift_months(end_d, args.months).isoformat()
    else:
        w_start = DEFAULT_START
    w_end = end_d.isoformat()
    if w_start >= w_end:
        print("[错误] start(%s) 必须早于 end(%s)，且缓存内需有该区间交易日" % (w_start, w_end))
        sys.exit(1)

    print("\n" + "=" * 78)
    print("【1】回溯 %s ~ %s（信号日窗口；买入=信号次日开盘，卖出按周期规则）" % (w_start, w_end))
    print("=" * 78)
    out = review_window(cache, all_dates, date_to_idx, w_start, w_end, periods)
    print_review(out, args.top)

    if args.json:
        dump = {p: dict(window=[w_start, w_end], dist=r["dist"], trades=r["trades"])
                for p, r in out.items()}
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(dump, f, ensure_ascii=False, indent=1)
        print("\n交易明细已导出: %s" % args.json)

    # ── 周选股 ──
    if not args.no_week:
        wk_start = args.week_start or default_week(w_end)[0]
        wk_end = args.week_end or default_week(w_end)[1]
        print_week(cache, all_dates, date_to_idx, periods, wk_start, wk_end)


if __name__ == "__main__":
    main()
