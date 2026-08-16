# -*- coding: utf-8 -*-
"""
导出固化参数 → app/src/main/assets/backtest_params.json
========================================================================
从 walk-forward 拟合历史中提取「稳健最优」卖出参数：
- 每个周期 × 每个大盘状态：统计各月拟合 rule 的众数（出现最多的，稳定性优先）
- 样本不足的状态 → 回退该周期默认规则
同时固化当前选股参数（PARAMS V8）。

APK 启动时由 BacktestParamsLoader 读取，覆盖默认配置 ——
新用户无需导入历史 K 线，直接代入参数即可选股/评估；
已有用户可随时重新导出参数升级。

用法：
  python _export_params.py [--out app/src/main/assets/backtest_params.json]
  python _export_params.py --fit-cache   # 从 _refit_experiment.py 的全量拟合缓存导出
"""
import argparse
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import PARAMS
from _full_cycle_backtest import SELL_RULES
from _walk_forward import PERIODS, RULES_KEY, STATES, RECORD_DIR

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "backtest_params.json")

# 中文因子名 → Kotlin StockCheckResult 字段名（UnifiedStockClassifier.factorValueOf 对齐）
FACTOR_FIELD = {
    "粘合度(越低越紧)": "convergenceDegree",
    "量比": "volumeRatio",
    "距高点跌幅%": "drawdownPct",
    "换手率%": "turnoverRate",
    "当日涨幅%": "changePct",
    "距MA60乖离%": "ma60Bias",
    "距MA250乖离%": "ma250Bias",
    "近5日动量%": "momentum5",
    "粘合持续天数": "convergenceDays",
}
IC_THRESHOLD = 0.2  # |ICIR| 筛选阈值：只保留稳定信号，避免噪声因子


def load_rank_factors():
    """从 _records/factor_ic.json 提取各周期 |ICIR|≥0.2 的排序因子（ICIR 带符号）"""
    ic_path = os.path.join(RECORD_DIR, "factor_ic.json")
    if not os.path.exists(ic_path):
        print(f"警告：未找到 {ic_path}，跳过 rank_factors（请先运行 python _factor_ic.py）")
        return {}
    ic = json.load(open(ic_path, encoding="utf-8"))
    out = {}
    for period in PERIODS:
        data = ic.get(period, {})
        factors = {}
        for f in data.get("factors", []):
            field = FACTOR_FIELD.get(f["name"])
            icir = f.get("icir", 0.0)
            if field and abs(icir) >= IC_THRESHOLD:
                factors[field] = round(icir, 3)
        if factors:
            out[period] = factors
    return out


def load_fitted_history(use_cache=False):
    if not os.path.isdir(RECORD_DIR):
        return []
    files = sorted(f for f in os.listdir(RECORD_DIR)
                   if f.startswith("selected_") and f.endswith(".json"))
    if not use_cache:
        out = []
        for f in files:
            with open(os.path.join(RECORD_DIR, f), encoding="utf-8") as fh:
                rec = json.load(fh)
            out.append(rec.get("fitted", {}))
        return out
    # 从 _refit_experiment.py 的拟合缓存取「全量三年」结果（key: {period}|{i}|all）
    cache_path = os.path.join(RECORD_DIR, "refit_fitted_cache.json")
    if not os.path.exists(cache_path):
        print(f"警告：未找到 {cache_path}，请先运行 python _refit_experiment.py")
        return []
    cache = json.load(open(cache_path, encoding="utf-8"))
    out = []
    for i in range(len(files)):
        fitted = {}
        for period in PERIODS:
            key = f"{period}|{i}|all"
            if key in cache:
                fitted[period] = cache[key]
        out.append(fitted)
    return out


def majority_rules(history):
    """每周期×每状态：众数规则（稳定性优先）；无样本回退默认"""
    out = {}
    for period in PERIODS:
        votes = {st: [] for st in STATES}
        n_used = 0
        for fitted in history:
            pfit = fitted.get(period, {})
            if pfit:
                n_used += 1
            for st, v in pfit.items():
                votes.setdefault(st, []).append(json.dumps(v["rule"], sort_keys=True))
        by_state = {}
        for st in STATES:
            if votes.get(st):
                rule_str, cnt = Counter(votes[st]).most_common(1)[0]
                by_state[st] = json.loads(rule_str)
        out[period] = {"default": SELL_RULES[RULES_KEY[period]], "by_state": by_state,
                       "fitted_months": n_used}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--fit-cache", action="store_true",
                    help="从 _refit_experiment.py 全量拟合缓存导出（推荐，免重跑）")
    args = ap.parse_args()

    history = load_fitted_history(use_cache=args.fit_cache)
    if not history:
        print(f"警告：{RECORD_DIR} 没有拟合记录，将导出默认参数。")
    sell_rules = majority_rules(history)
    rank_factors = load_rank_factors()

    payload = {
        "version": 3,
        "generated": "2026-08-16",
        "source": "smalltools/_walk_forward.py (三年 walk-forward) + _factor_ic.py (IC 排序器)",
        "period": "2023-08-15 ~ 2026-08-15",
        "select_params": {p: PARAMS[p] for p in PERIODS},
        "rank_factors": rank_factors,
        "sell_rules": sell_rules,
        "meta": {
            "fitted_months": len(history),
            "note": "by_state 为各大盘状态的拟合众数规则；无样本状态回退 default；"
                    "rank_factors 为 IC/ICIR 全量检验的排序权重（负=值越小越优先）",
        },
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)
    print(f"已导出 → {args.out}")
    for period in PERIODS:
        by_state = {st: fmt_rule(sell_rules[period]["by_state"][st])
                    for st in sell_rules[period]["by_state"]}
        print(f"  {period}: 默认[{fmt_rule(sell_rules[period]['default'])}] "
              f"拟合月数{sell_rules[period]['fitted_months']} 按状态 {by_state}")
        if period in rank_factors:
            print(f"    IC排序 {rank_factors[period]}")


def fmt_rule(rule):
    if rule["style"] == "nextday":
        return f"隔{rule['maxHold']}日卖"
    if rule["style"] == "streak":
        return f"连跌{rule['streakDays']}日/破{rule['maBreak']}日线/最多{rule['maxHold']}天"
    return f"持有{rule['maxHold']}天 止盈{rule['tp']}% 止损{rule['sl']}% 做T{int(rule['tRatio'] * 100)}%"


if __name__ == "__main__":
    main()
