# -*- coding: utf-8 -*-
"""将 backtest_params.json 的 sell_rules 对齐为 smalltool usecase_matrix 9 格卖出参数。

映射（tp/sl 单位 %）：
  超短：所有环境 maxHold=1  tp=+0.4  sl=-0.8
  短线：牛市/震荡 maxHold=3 tp=+1.5 sl=-1.5；熊市 maxHold=5 tp=+1.5 sl=-5.0
  中线：所有环境 maxHold=10 tp=+3.0 sl=-8.0
保留 avg/wr/n 旧统计仅供诊断展示。
"""
import io
import json
import os

ASSET = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "assets", "backtest_params.json"))

NEW_RULES = {
    "超短": {"BULLISH": (1, 0.4, -0.8), "OSCILLATION": (1, 0.4, -0.8), "BEARISH": (1, 0.4, -0.8)},
    "短线": {"BULLISH": (3, 1.5, -1.5), "OSCILLATION": (3, 1.5, -1.5), "BEARISH": (5, 1.5, -5.0)},
    "中线": {"BULLISH": (10, 3.0, -8.0), "OSCILLATION": (10, 3.0, -8.0), "BEARISH": (10, 3.0, -8.0)},
    "长线": {},  # 长线保持原拟合，不覆盖
}

with io.open(ASSET, "r", encoding="utf-8") as f:
    root = json.load(f)

sell = root.setdefault("sell_rules", {})
for period, states in NEW_RULES.items():
    period_obj = sell.setdefault(period, {})
    by_state = period_obj.setdefault("by_state", {})
    for state, (hold, tp, sl) in states.items():
        rule = by_state.setdefault(state, {})
        rule["maxHold"] = hold
        rule["tp"] = tp
        rule["sl"] = sl
        print(f"{period}.{state}: maxHold={hold} tp={tp} sl={sl} (style={rule.get('style')}, wr={rule.get('wr')}, n={rule.get('n')})")

with io.open(ASSET, "w", encoding="utf-8", newline="\n") as f:
    json.dump(root, f, ensure_ascii=False, indent=1)

print("✔ backtest_params.json sell_rules 已更新")
