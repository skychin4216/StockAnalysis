# -*- coding: utf-8 -*-
"""对照参考-非主线 精选≤3 的收益/正确率校验（2026-09-06）。

样本：_records/selected_YYYY-MM.json 真实候选（日级信号）。
背景：历史未存档每票 ratio/命中率排序，故用“每交易日按引擎输出顺序取前3”作精选代理，
对比 全量候选 vs 每日≤3 精选 的 OOS（规则=该窗口实际应用 params_used，结算同 simulate_trade）。
若收敛后每笔平均/胜率提升 → 支持“对照只推前3”更聚焦；无提升 → 说明精选需要 ratio 排序
（自下一次起已随账本记录，供之后校验）。

用法： python _sel_top3_check.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
REC_DIR = os.path.join(HERE, "_records")

from _full_cycle_backtest import load_cache, simulate_trade, stats  # noqa: E402

DISPLAY_PERIODS = ["超短", "短线", "中线", "长线"]


def main():
    cache = load_cache()
    all_dates = sorted({d for e in cache.values()
                        for s in e.get("snaps", []) for d in [s["date"]]})
    files_ = sorted(os.path.join(REC_DIR, f)
                    for f in os.listdir(REC_DIR)
                    if f.startswith("selected_") and f.endswith(".json"))
    full, top3 = [], []
    n_day = 0
    for fp in files_:
        rec = json.load(open(fp, encoding="utf-8"))
        # 汇总各周期的参数规则
        used = {}
        for per in DISPLAY_PERIODS:
            used[per] = rec.get("params_used", {}).get(per, {})
        day = {}
        for per in DISPLAY_PERIODS:
            for sig in rec.get("signals", {}).get(per, []):
                d = sig[2]
                day.setdefault(d, []).append((per, sig))
        for d, lst in sorted(day.items()):
            n_day += 1
            for i, (per, sig) in enumerate(lst):
                rule = used[per].get(sig[4])
                if not isinstance(rule, dict):
                    continue
                t = simulate_trade(cache, all_dates, tuple(sig), rule)
                if not t:
                    continue
                full.append(t["ret"])
                if i < 3:  # 精选≤3（顺序代理 ratio 排序）
                    top3.append(t["ret"])
    def line(name, rets):
        if not rets:
            print("  %-12s 样本0" % name)
            return
        n, avg, wr, cum, pf, mdd = stats(rets)
        loss = [r for r in rets if r <= 0]
        print("  %-12s %5d笔 平均%+6.2f%% 胜率%4.1f%% 累计%+8.1f%% | 亏损笔均%+5.2f%%"
              % (name, n, avg, wr, cum, sum(loss) / len(loss) if loss else 0))
    print("真实候选(对照口径四周期) | 覆盖交易日 %d 个 / 窗口 %d 个" % (n_day, len(files_)))
    line("全量候选", full)
    line("每日精选≤3", top3)


if __name__ == "__main__":
    main()
