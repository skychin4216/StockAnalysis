# -*- coding: utf-8 -*-
"""
自动合并 APK「📤 导出拟合」JSON 与现有 backtest_params.json → 合并版参数文件
============================================================================
背景（边买卖边完善 PC 拟合的闭环 ④）：
- APK 工作台「📐 状态矩阵拟合」用本机买卖记录/历史选中记录拟合卖出矩阵 → backtest_meta
- 工作台「📤 导出拟合」把矩阵导出为 sell_rules 结构 JSON（pc_fit_export/fit_matrix_*.json）
- 本脚本把该导出合并进 PC 端三年 walk-forward 生成的 backtest_params.json：
  * sell_rules   → 以 APK 导出为准（反映实盘/实仓的最新拟合），缺失字段（streakDays/
    maBreak/tRatio 等，APK 导出只带 maxHold/tp/sl）用 PC 基座同周期同状态规则补全
  * select_params / rank_factors / seasonality / meta → 保留 PC 基座（IC 排序与
    选股参数需要全量历史，PC 端数据更全）

用法：
  python _merge_fit_export.py \
      --export smalltools/_records/export_sample.json \
      --base   app/src/main/assets/backtest_params.json \
      --out    app/src/main/assets/backtest_params_merged.json
  # --base/--out 缺省时 base 指向 assets 默认参数文件，out 生成合并版（不覆盖原文件）
"""
import argparse
import json
import os

PERIODS = ["超短", "短线", "中线", "长线"]
STATES = ["BULLISH", "OSCILLATION", "BEARISH", "CRASH"]

# 周期 → 风格（与 PC _full_cycle_backtest.SELL_RULES / Kotlin AutoSellEngine 对齐）
STYLE_OF = {"超短": "nextday", "短线": "streak", "中线": "hold", "长线": "hold"}
# 各风格缺失字段的默认值（补全用，优先级：APK 导出 > PC 基座 > 默认）
DEFAULTS = {
    "nextday": dict(style="nextday", maxHold=2, tp=0.0, sl=0.0),
    "streak":  dict(style="streak", streakDays=3, maBreak=5, maxHold=10, tp=0.0, sl=0.0),
    "hold":    dict(style="hold", maxHold=15, tp=20.0, sl=-10.0, tRatio=0.4),
}


def normalize_rule(period, rule):
    """把 APK 导出 rule（可能缺 streakDays/maBreak/tRatio）补全为完整规则。"""
    style = rule.get("style") or STYLE_OF.get(period, "hold")
    base = dict(DEFAULTS.get(style, DEFAULTS["hold"]))
    out = dict(base)
    for k, v in rule.items():
        if k in ("avg", "wr", "n"):     # 参考统计量原样保留
            out[k] = v
        else:
            out[k] = v
    out["style"] = style
    return out


def complete_rule(period, state, apk_rule, pc_rule):
    """字段补全：APK 导出 > PC 基座（同周期同状态）> 默认值。"""
    base = dict(DEFAULTS.get(apk_rule.get("style") or STYLE_OF.get(period, "hold"),
                             DEFAULTS["hold"]))
    if isinstance(pc_rule, dict):
        for k, v in pc_rule.items():
            if k not in ("avg", "wr", "n"):
                base[k] = v
    out = dict(base)
    for k, v in apk_rule.items():
        out[k] = v
    out["style"] = apk_rule.get("style") or out.get("style") or STYLE_OF.get(period, "hold")
    return out


def merge_sell_rules(pc_rules, export_rules):
    """合并 sell_rules：APK 导出优先，PC 补字段/补状态，输出四周期完整结构。"""
    merged = {}
    for period in PERIODS:
        pc = pc_rules.get(period) or {}
        ap = (export_rules or {}).get(period) or {}
        pc_by_state = pc.get("by_state") or {}
        ap_by_state = ap.get("by_state") or {}
        pc_default = pc.get("default") or {}
        ap_default = ap.get("default") or {}

        # 状态并集：只合并两边真实存在的状态（PC 三态 + APK 可能多出的状态）
        states = list(dict.fromkeys(list(pc_by_state.keys()) + list(ap_by_state.keys())))
        if not states:  # 两边都空时才兜底三态
            states = STATES[:3]
        by_state = {}
        for st in states:
            if st in ap_by_state and isinstance(ap_by_state[st], dict):
                by_state[st] = complete_rule(period, st, ap_by_state[st],
                                             pc_by_state.get(st))
            elif st in pc_by_state and isinstance(pc_by_state[st], dict):
                by_state[st] = normalize_rule(period, pc_by_state[st])
            else:
                by_state[st] = normalize_rule(period, ap_default or pc_default)

        # default：APK 导出的 default（来自 OSCILLATION）优先，否则 PC default
        if isinstance(ap_default, dict) and ap_default:
            default = complete_rule(period, "OSCILLATION", ap_default, pc_default)
        else:
            default = normalize_rule(period, pc_default)

        # fitted_months：PC 基座保留；无则用 APK 各状态样本数求和
        fitted = pc.get("fitted_months") or 0
        if not fitted:
            for st in ap_by_state.values():
                if isinstance(st, dict):
                    fitted += int(st.get("n", 0))
        merged[period] = {"default": default, "by_state": by_state,
                          "fitted_months": fitted}
    return merged


def main():
    ap = argparse.ArgumentParser(description="合并 APK 导出拟合 → backtest_params.json 合并版")
    ap.add_argument("--export", required=True, help="APK「📤 导出拟合」的 JSON 文件路径")
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--base", default=os.path.join(root, "app", "src", "main", "assets",
                                                   "backtest_params.json"),
                    help="PC 端现有 backtest_params.json（基座）")
    ap.add_argument("--out", default=os.path.join(root, "app", "src", "main", "assets",
                                                  "backtest_params_merged.json"),
                    help="合并版输出路径（默认 backtest_params_merged.json，不覆盖基座）")
    args = ap.parse_args()

    if not os.path.exists(args.export):
        print(f"❌ 找不到导出文件: {args.export}")
        raise SystemExit(1)
    if not os.path.exists(args.base):
        print(f"❌ 找不到基座参数文件: {args.base}")
        raise SystemExit(1)

    base = json.load(open(args.base, encoding="utf-8"))
    export = json.load(open(args.export, encoding="utf-8"))

    export_rules = export.get("sell_rules") or {}
    pc_rules = base.get("sell_rules") or {}
    merged_rules = merge_sell_rules(pc_rules, export_rules)

    merged = {
        "version": max(int(base.get("version", 2)), 3),
        "generated": export.get("exported_at") or base.get("generated", ""),
        "source": (f"{base.get('source', '')} ＋ APK 导出合并"
                   f"({os.path.basename(args.export)})"),
        "period": base.get("period", ""),
        "select_params": base.get("select_params") or {},
        "seasonality": base.get("seasonality") or {},
        "rank_factors": base.get("rank_factors") or {},
        "sell_rules": merged_rules,
        "meta": {
            "fitted_months": base.get("meta", {}).get("fitted_months", 36),
            "note": ("sell_rules 以 APK 本机拟合为准（实盘/实仓最新）；"
                     "select_params/rank_factors 保留 PC 三年 walk-forward + IC 排序；"
                     "APK 缺失字段由 PC 同周期同状态规则补全"),
        },
    }

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, indent=1)
    print(f"✅ 已合并 → {args.out}")
    print(f"   基座: {args.base}")
    print(f"   导出: {args.export}")
    n_ap = sum(1 for p in PERIODS if p in export_rules)
    print(f"   sell_rules 覆盖周期: {n_ap}/4  （APK 未导出的周期沿用 PC 基座）")
    for period in PERIODS:
        rule = merged_rules[period]
        d = rule["default"]
        print(f"  {period}: default={fmt_rule(d)} "
              f"fitted_months={rule['fitted_months']} "
              f"states={ {st: fmt_rule(r) for st, r in rule['by_state'].items()} }")


def fmt_rule(r):
    if r["style"] == "nextday":
        return f"隔{r['maxHold']}日卖"
    if r["style"] == "streak":
        return (f"连跌{r['streakDays']}日/破{r['maBreak']}日线/最多{r['maxHold']}天")
    return (f"持有{r['maxHold']}天 止盈{r['tp']}% 止损{r['sl']}% "
            f"做T{int(float(r.get('tRatio', 0.4)) * 100)}%")


if __name__ == "__main__":
    main()
