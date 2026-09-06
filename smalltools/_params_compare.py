# -*- coding: utf-8 -*-
"""明牌参数 vs 暗牌参数 —— 真实候选逐月窗口对比（2026-09-06）。

术语（用户定义）：
- 明牌参数：结果已发生、事后调参/拟合得到的参数（知道当月选股结果再去拟合 → 样本内/带未来视角）；
  对应 walk_forward 每个窗口 rec["fitted"]（用“当月+之前”信号及其实盘结果拟合，含当月结果）。
- 暗牌参数：不知道将来结果，只用截至当时的 K线/环境/信号拟合出参数、再用于之后选股 → 样本外可用；
  对应窗口 rec["params_used"]（上个月拟合、本月应用，即 rec["trades"] 的生成规则）。
- 静态参数：app backtest_params.json 的 by_state 九格（全历史已知结果拟合的固定规则，明牌特例）。

输出每周期：
- 明牌(样本内天花板) vs 暗牌(样本外真实) 的平均/胜率/固定本金累计 → 过拟合衰减度 = 真实可捕获比例
- 暗牌 vs 静态 by_state 九格 → 选股到底该用哪套（两者都无未来函数，选高的那套给 smalltool）

用法： python _params_compare.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
REC_DIR = os.path.join(HERE, "_records")

from _full_cycle_backtest import (  # noqa: E402
    load_cache, simulate_trade, stats, sell_rule_for,
)

PERIODS = ["超短", "短线", "中线", "长线"]
KEYMAP = {"超短": "超短线", "短线": "短线", "中线": "中线", "长线": "长线"}


def files():
    fs = sorted(f for f in os.listdir(REC_DIR)
                if f.startswith("selected_") and f.endswith(".json"))
    return [os.path.join(REC_DIR, f) for f in fs]


def _sim_rule(cache, all_dates, sigs, rule_of):
    """rule_of(state)->rule|None；对一组信号模拟，返回 (rets, n_sim)"""
    rets = []
    for sig in sigs:
        st = sig[4]
        rule = rule_of(st)
        if not rule:
            continue
        t = simulate_trade(cache, all_dates, tuple(sig), rule)
        if t:
            rets.append(t["ret"])
    return rets


def main():
    cache = load_cache()
    all_dates = sorted({d for e in cache.values()
                        for s in e.get("snaps", []) for d in [s["date"]]})
    fs = files()
    print("缓存 %d 只 | 真实候选窗口 %d 个（%s ~ %s）"
          % (len(cache), len(fs),
             os.path.basename(fs[0]) if fs else "-",
             os.path.basename(fs[-1]) if fs else "-"))

    for period in PERIODS:
        dark, light, stat = [], [], []
        n_sig = 0
        for fp in fs:
            rec = json.load(open(fp, encoding="utf-8"))
            sigs = rec.get("signals", {}).get(period, [])
            if not sigs:
                continue
            n_sig += len(sigs)
            used = rec.get("params_used", {}).get(period, {})   # 暗牌（上月拟合应用）
            fitted = rec.get("fitted", {}).get(period, {})      # 明牌（当月拟合）
            def dark_of(st, _u=used):
                return _u.get(st) if isinstance(_u.get(st), dict) else None
            def light_of(st, _f=fitted):
                d = _f.get(st)
                return d.get("rule") if isinstance(d, dict) and "rule" in d else None
            dark += _sim_rule(cache, all_dates, sigs, dark_of)
            light += _sim_rule(cache, all_dates, sigs, light_of)
            def stat_of(st, _p=period):
                try:
                    return sell_rule_for(KEYMAP[_p], st)
                except Exception:
                    return None
            stat += _sim_rule(cache, all_dates, sigs, stat_of)

        print("\n" + "=" * 100)
        print("【%s】信号 %d 笔" % (period, n_sig))
        print("=" * 100)
        print("%-14s %7s %9s %9s %10s %8s" % ("参数来源", "笔数", "平均%", "胜率%", "累计%", "盈/亏因子"))
        out = {}
        for label, rets in (("明牌(当月拟合/含未来)", light),
                            ("暗牌(上月拟合/无未来)", dark),
                            ("静态九格(全史已知拟合)", stat)):
            if not rets:
                print("%-14s %7d %9s" % (label, 0, "-"))
                continue
            n, avg, wr, cum, pf, mdd = stats(rets)
            out[label] = (n, avg, wr, cum, pf)
            pf_s = "∞" if pf == float("inf") else "%.2f" % pf
            print("%-14s %7d %+8.2f %8.1f %+9.1f %8s" % (
                label, n, avg, wr, cum, pf_s))
        # 两套“无未来函数”参数直接对决：暗牌 vs 静态
        d, s = out.get("暗牌(上月拟合/无未来)"), out.get("静态九格(全史已知拟合)")
        if d and s:
            winner = "暗牌滚动" if d[2] > s[2] else ("静态九格" if s[2] > d[2] else "持平")
            print("→ 无未来函数对决：均收益 暗牌%+.2f vs 静态%+.2f → 推荐 %s"
                  % (d[1], s[1], winner))
        lgt = out.get("明牌(当月拟合/含未来)")
        if lgt and d and lgt[1] > 0:
            print("→ 过拟合衰减：明牌均%+.2f%%，暗牌仅能拿到 %.0f%%"
                  % (lgt[1], max(0.0, d[1]) / lgt[1] * 100 if d[1] > 0 else 0))


if __name__ == "__main__":
    main()
