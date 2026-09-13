# -*- coding: utf-8 -*-
"""将 2026-08-26 四周期回溯的「按大盘状态拟合」结果写回 backtest_params.json sell_rules。

拟合来源: smalltools/_full_cycle_backtest.py fit_by_state(网格搜索 maxHold×tp×sl,
以平均收益优先、胜率次优)。tp/sl 单位与 sell_rules 一致(%)。
仅覆盖本次有拟合输出的状态格, 其余格(含 CRASH)保持不动; style/tRatio 保留。
"""
import io
import json
import os

ASSET = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "assets", "backtest_params.json"))

# period -> state -> {maxHold, tp, sl, avg, wr, n}
FIT = {
    "中线": {
        "BULLISH":     {"maxHold": 20, "tp": 25, "sl": -8,  "avg": 8.50,  "wr": 79.1, "n": 206},
        "OSCILLATION": {"maxHold": 10, "tp": 25, "sl": -6,  "avg": 2.89,  "wr": 63.6, "n": 33},
    },
    "长线": {
        "BULLISH":     {"maxHold": 40, "tp": 50, "sl": -12, "avg": 10.73, "wr": 80.1, "n": 337},
        "OSCILLATION": {"maxHold": 40, "tp": 50, "sl": -12, "avg": 14.54, "wr": 80.0, "n": 90},
        "BEARISH":     {"maxHold": 40, "tp": 50, "sl": -12, "avg": 18.32, "wr": 81.5, "n": 27},
    },
}

with io.open(ASSET, "r", encoding="utf-8") as f:
    root = json.load(f)

sell = root.setdefault("sell_rules", {})
for period, states in FIT.items():
    by_state = sell.setdefault(period, {}).setdefault("by_state", {})
    for state, v in states.items():
        rule = by_state.setdefault(state, {})
        old = (rule.get("maxHold"), rule.get("tp"), rule.get("sl"))
        rule.update({
            "maxHold": v["maxHold"], "tp": v["tp"], "sl": v["sl"],
            "avg": v["avg"], "wr": v["wr"], "n": v["n"],
        })
        print(f"{period}.{state}: maxHold {old[0]}->{v['maxHold']}  "
              f"tp {old[1]}->{v['tp']}  sl {old[2]}->{v['sl']}  "
              f"(avg {v['avg']:+.2f}% wr {v['wr']:.1f}% n {v['n']})")

with io.open(ASSET, "w", encoding="utf-8", newline="\n") as f:
    json.dump(root, f, ensure_ascii=False, indent=1)
print("✔ backtest_params.json sell_rules 拟合结果已写回")
