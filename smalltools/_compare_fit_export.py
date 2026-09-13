# -*- coding: utf-8 -*-
"""
对比「PC 端 K线拟合参数」与「APK 导出合并参数」→ 判定两者是否收敛
==================================================================
用途（用户问题 2）：
- 方式 A：PC 端用三年 K 线缓存 walk-forward 拟合（backtest_params.json）
- 方式 B：APK 本机买卖记录/历史选中记录拟合 → 「📤 导出拟合」→ 合并进 PC 基座
  （backtest_params_merged.json）
- 本脚本对比两者 sell_rules 是否一致：若一致率接近 100%，说明 APK 本机拟合
  与 PC 全量历史拟合收敛（同一底层信号 + 同一卖出逻辑），可放心增量回传。

用法：
  python _compare_fit_export.py \
      --pc   app/src/main/assets/backtest_params.json \
      --merged app/src/main/assets/backtest_params_merged.json
"""
import argparse
import json
import os

PERIODS = ["超短", "短线", "中线", "长线"]
STATES = ["BULLISH", "OSCILLATION", "BEARISH", "CRASH"]

FIELDS = {
    "nextday": ["maxHold", "tp", "sl"],
    "streak":  ["streakDays", "maBreak", "maxHold", "tp", "sl"],
    "hold":    ["maxHold", "tp", "sl", "tRatio"],
}


def norm(rule):
    """数值字段归一化，缺失字段不参与对比（记为 None）。"""
    r = rule or {}
    style = r.get("style") or "hold"
    return style, {k: r.get(k) for k in FIELDS.get(style, FIELDS["hold"])}


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def compare_rule(a, b):
    """返回 (完全一致?, 字段差异列表[(字段, a, b)])。"""
    if not a or not b:
        return False, [("missing", a, b)]
    _, fa = norm(a)
    _, fb = norm(b)
    diffs = []
    for k in fa:
        va, vb = num(fa[k]), num(fb[k])
        if va is None and vb is None:
            continue
        if va is None or vb is None or abs(va - vb) > 1e-9:
            diffs.append((k, fa[k], fb[k]))
    return len(diffs) == 0, diffs


def main():
    ap = argparse.ArgumentParser(description="对比 PC K线拟合 vs APK 合并参数")
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--pc", default=os.path.join(root, "app", "src", "main", "assets",
                                                 "backtest_params.json"))
    ap.add_argument("--merged", default=os.path.join(root, "app", "src", "main", "assets",
                                                     "backtest_params_merged.json"))
    args = ap.parse_args()

    for f in (args.pc, args.merged):
        if not os.path.exists(f):
            print(f"❌ 找不到文件: {f}")
            raise SystemExit(1)

    pc = json.load(open(args.pc, encoding="utf-8"))["sell_rules"]
    mg = json.load(open(args.merged, encoding="utf-8"))["sell_rules"]

    total, same = 0, 0
    diffs_all = []
    print("=" * 78)
    print("sell_rules 逐周期×状态对比  |  左=PC K线拟合  →  右=APK 合并")
    print("=" * 78)
    for period in PERIODS:
        if period not in pc and period not in mg:
            continue
        states = list(dict.fromkeys(
            list((pc.get(period) or {}).get("by_state", {}).keys())
            + list((mg.get(period) or {}).get("by_state", {}).keys())))
        for st in states:
            a = (pc.get(period) or {}).get("by_state", {}).get(st)
            b = (mg.get(period) or {}).get("by_state", {}).get(st)
            if a is None and b is None:
                continue
            if a is None or b is None:
                total += 1
                diffs_all.append((period, st, a, b))
                print(f"  {period:<4} {st:<12}  {'缺失' if a is None else 'PC规则'} → "
                      f"{'缺失' if b is None else 'APK规则'}   ✗")
                continue
            ok, diffs = compare_rule(a, b)
            total += 1
            if ok:
                same += 1
                print(f"  {period:<4} {st:<12}  {fmt(a)}  ✓ 一致")
            else:
                diffs_all.append((period, st, a, b))
                print(f"  {period:<4} {st:<12}  {fmt(a)}  →  {fmt(b)}  ✗ 差异")
                for k, va, vb in diffs:
                    print(f"      · {k}: {va} → {vb}")

    # 汇总统计
    print("=" * 78)
    rate = same / total * 100 if total else 100.0
    print(f"统计: 对比 {total} 个 周期×状态，完全一致 {same} 个，一致率 {rate:.1f}%")
    if diffs_all:
        max_abs = 0.0
        worst = None
        for period, st, a, b in diffs_all:
            for k, va, vb in compare_rule(a, b)[1]:
                if num(va) is not None and num(vb) is not None:
                    d = abs(num(va) - num(vb))
                    if d > max_abs:
                        max_abs, worst = d, (period, st, k, va, vb)
        if worst:
            print(f"最大数值差: {worst[0]}/{worst[1]}/{worst[2]} = "
                  f"{worst[3]} → {worst[4]}  (abs {max_abs:.2f})")

    print("-" * 78)
    if rate >= 99.0:
        print("结论: 一致率 ≥99%，PC K线拟合 与 APK 导出合并 相差无限小，"
              "两者收敛于同一卖出纪律 → 可放心增量回传")
    elif rate >= 80:
        print("结论: 一致率 ≥80%，主体规则一致，局部状态（样本少）有偏移 → "
              "建议以样本量 n 更大的来源为准，或保留 PC 基座")
    else:
        print("结论: 差异显著 → 检查 APK 本地 K线/订单数据是否补齐、"
              "拟合矩阵是否跑过「📐 状态矩阵拟合」、导出是否最新")


def fmt(r):
    if not r:
        return "(无)"
    style = r.get("style") or "hold"
    if style == "nextday":
        return f"隔{r.get('maxHold')}日卖"
    if style == "streak":
        return (f"连跌{r.get('streakDays')}日/破{r.get('maBreak')}日线/"
                f"最多{r.get('maxHold')}天")
    return (f"持有{r.get('maxHold')}天 止盈{r.get('tp')}% "
            f"止损{r.get('sl')}%")


if __name__ == "__main__":
    main()
