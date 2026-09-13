# -*- coding: utf-8 -*-
"""中/长线 止损线 sl 扫描调优（真实候选，2026-09-06）。

样本：_records/selected_YYYY-MM.json 里逐日真实信号（walk-forward 期间每天实际选出的候选），
结算：_full_cycle_backtest.simulate_trade（与生产一致，含做T现金账户/持有到期/数据末端）。
扫描：保持该窗口实际应用的参数不变，仅替换 hold 规则 sl ∈ {-6,-8,-10,-12}，对比“该窗口实际
应用的 sl（原值）”。统计固定本金口径 avg/wr/cum、亏损笔均、P(亏≤-8/-15)。

用法： python _sl_tune.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
REC_DIR = os.path.join(HERE, "_records")

from _full_cycle_backtest import load_cache, simulate_trade, stats  # noqa: E402

PERIODS = ["中线", "长线"]
SLS = [-6, -8, -10, -12]
PERIOD_FILES = [p for p in ("中线", "长线")]


def files():
    fs = sorted(f for f in os.listdir(REC_DIR)
                if f.startswith("selected_") and f.endswith(".json"))
    return [os.path.join(REC_DIR, f) for f in fs]


def main():
    cache = load_cache()
    all_dates = sorted({d for e in cache.values()
                        for s in e.get("snaps", []) for d in [s["date"]]})
    files_ = files()
    print("缓存 %d 只 | 交易日 %s~%s | 月度真实候选窗口 %d 个"
          % (len(cache), all_dates[0], all_dates[-1], len(files_)))

    for period in PERIODS:
        # 桶: sl -> rets; 以及“窗口实际应用规则(原sl)”基准
        agg = {sl: [] for sl in SLS}
        agg["orig"] = []
        agg["none"] = []
        n_sig = 0
        for fp in files_:
            rec = json.load(open(fp, encoding="utf-8"))
            sigs = rec.get("signals", {}).get(period, [])
            used = rec.get("params_used", {}).get(period, {})
            for sig in sigs:
                st = sig[4]
                rule = used.get(st)
                if not isinstance(rule, dict) or rule.get("style") != "hold":
                    continue
                n_sig += 1
                base = simulate_trade(cache, all_dates, tuple(sig), rule)
                if base:
                    agg["orig"].append(base["ret"])
                r2 = dict(rule)
                r2["sl"] = 0.0   # 引擎约定：sl<0 才触发止损 → 0 表示无止损
                t0 = simulate_trade(cache, all_dates, tuple(sig), r2)
                if t0:
                    agg["none"].append(t0["ret"])
                for sl in SLS:
                    r2 = dict(rule)
                    r2["sl"] = sl
                    t = simulate_trade(cache, all_dates, tuple(sig), r2)
                    if t:
                        agg[sl].append(t["ret"])
        print("\n" + "=" * 96)
        print("【%s】真实候选 %d 笔信号（仅 hold 风格、与结算同口径）" % (period, n_sig))
        print("=" * 96)
        print("%-8s %7s %9s %9s %9s %12s %11s %9s %9s" % (
            "sl", "笔数", "平均%", "中位%", "胜率%", "累计%", "亏损笔均%",
            "P(≤-8%)", "P(≤-15%)"))
        rows = [("orig", agg["orig"]), ("none", agg["none"])] + \
               [(("sl=%d" % sl), agg[sl]) for sl in SLS]
        for label, rets in rows:
            if not rets:
                continue
            n, avg, wr, cum, pf, mdd = stats(rets)
            srt = sorted(rets)
            med = srt[len(srt) // 2] if srt else 0.0
            loss = [r for r in rets if r <= 0]
            loss_avg = sum(loss) / len(loss) if loss else 0.0
            p8 = sum(1 for r in rets if r <= -8) / n * 100
            p15 = sum(1 for r in rets if r <= -15) / n * 100
            print("%-8s %7d %+8.2f %+8.2f %8.1f %+11.1f %+10.2f %8.1f%% %8.1f%%" % (
                label, n, avg, med, wr, cum, loss_avg, p8, p15))
        # 相对“原值”净变化（最优 sl 的差分）
        if agg["orig"]:
            base_avg = sum(agg["orig"]) / len(agg["orig"])
            best = None
            for sl in SLS:
                if agg[sl]:
                    av = sum(agg[sl]) / len(agg[sl])
                    if best is None or av > best[1]:
                        best = (sl, av)
            if best:
                print("→ 最优扫描 sl=%d（均%+.2f%%），较窗口实际原参数(均%+.2f%%) %s%+.2fpp"
                      % (best[0], best[1], base_avg,
                         "提升" if best[1] >= base_avg else "下降", best[1] - base_avg))


if __name__ == "__main__":
    main()
