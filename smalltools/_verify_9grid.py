# -*- coding: utf-8 -*-
"""
12 格 usecase 矩阵（4 周期 × 3 环境）↔ backtest_params.json ↔ sell_rule_for 三端参数一致性验证。

单一事实源检查（smalltools / exe(AutoQuant) / APK 共用同一套回测参数）：
  1. usecase_matrix.json 的 12 格 sell（smalltools 扫描拟合产物）
  2. backtest_params.json 的 sell_rules.<周期>.by_state（APK 回测引擎读取源）
  3. sell_rule_for()（smalltools/_full_cycle_backtest.py 提供的状态路由，exe walk-forward 使用）

用法:
    python _verify_9grid.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _full_cycle_backtest import sell_rule_for, SELL_RULES, SELL_RULES_BY_STATE

HERE = os.path.dirname(os.path.abspath(__file__))
MATRIX = os.path.join(HERE, "_records", "usecase_matrix.json")
APK_PARAMS = os.path.normpath(os.path.join(
    HERE, "..", "app", "src", "main", "assets", "backtest_params.json"))

PERIODS = ["超短", "短线", "中线", "长线"]
STATES = ["BULLISH", "OSCILLATION", "BEARISH"]
# usecase_matrix sell 字段 → backtest_params sell_rules 字段
SELL_FIELD_MAP = {"tp": "tp", "sl": "sl", "hold": "maxHold"}


def main():
    with open(MATRIX, "r", encoding="utf-8") as f:
        matrix = json.load(f)
    with open(APK_PARAMS, "r", encoding="utf-8") as f:
        params = json.load(f)

    sr = params.get("sell_rules") or {}
    fails, checks = [], 0

    print("=" * 78)
    print("9 格 usecase 矩阵 ↔ backtest_params.json ↔ sell_rule_for 对齐检查")
    print("=" * 78)
    for p in PERIODS:
        pj = sr.get(p) or {}
        bstate = pj.get("by_state") or {}
        for st in STATES:
            mcell = (matrix.get(p) or {}).get(st)
            jcell = bstate.get(st)
            checks += 1
            if not mcell:
                fails.append(f"[{p}][{st}] usecase_matrix 缺失")
                continue
            if not jcell:
                fails.append(f"[{p}][{st}] backtest_params.by_state 缺失")
                continue
            # 1) 矩阵 ↔ JSON
            for mf, jf in SELL_FIELD_MAP.items():
                mv = mcell.get("sell", {}).get(mf)
                jv = jcell.get(jf)
                if mv is not None and jv is not None and abs(float(mv) - float(jv)) > 1e-9:
                    fails.append(f"[{p}][{st}] 参数不一致 {mf}: 矩阵={mv} JSON={jv}")
            # 2) sell_rule_for 路由（exe 侧）
            routed = sell_rule_for(p, st)
            for jf, rv in jcell.items():
                if jf in ("avg", "wr", "n", "style") or rv is None:
                    continue
                if routed.get(jf) != rv:
                    fails.append(f"[{p}][{st}] sell_rule_for 字段 {jf}: JSON={rv} 路由={routed.get(jf)}")
            # 3) CRASH 回退 BEARISH（仅在 BEARISH 格内比较）
            if st == "BEARISH":
                crash = sell_rule_for(p, "CRASH")
                if crash.get("maxHold") != jcell.get("maxHold") or crash.get("sl") != jcell.get("sl"):
                    fails.append(f"[{p}][CRASH] 未回退 BEARISH: {crash.get('maxHold')} vs {jcell.get('maxHold')}")
            matrix_wr = mcell.get("winrate")
            mark = "PASS" if not any(f.startswith(f"[{p}][{st}]") for f in fails) else "FAIL"
            print(f"  [{p}][{st}] matrix wr={matrix_wr:>5}%  sell={dict((k, jcell.get(k)) for k in ('style','maxHold','tp','sl'))}  {mark}")

    # 汇总
    print("-" * 78)
    if fails:
        print(f"FAILED {len(fails)} 处不一致：")
        for x in fails:
            print("  - " + x)
        sys.exit(1)
    print(f"全部 {checks} 格参数一致（矩阵 sell ↔ JSON by_state ↔ sell_rule_for ↔ CRASH 回退）。")
    print("默认规则（代码模板兜底）: 超短/短线/中线 =",
          {k: (SELL_RULES.get(k) or {}).get("style") for k in SELL_RULES if k in PERIODS})
    sys.exit(0)


if __name__ == "__main__":
    main()
