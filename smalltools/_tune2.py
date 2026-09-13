# -*- coding: utf-8 -*-
"""因子阈值网格扫描（基于 _feature_gap 收集的特征）。

对 超短/短线/中线，在特征层做阈值组合扫描，寻找 胜率≥目标 且样本≥min_n 的组合。
每个周期可自定义因子方向与网格：
  ("drawdown", ">=", -4.0)  表示保留 drawdown >= -4.0 的信号
"""
import argparse
import os
import sys
from itertools import product

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _feature_gap import collect, stats  # noqa: E402

GRIDS = {
    "超短": [
        ("drawdown", ">=", [-99.0, -5.0, -4.0, -3.0, -2.0]),
        ("vol_ratio", "<=", [99.0, 4.0, 3.5, 3.0, 2.5]),
        ("sector20", "<=", [99.0, 12.0, 10.0, 8.0]),
        ("rel_sector", ">=", [-99.0, -10.0, -8.0, -6.0]),
    ],
    "短线": [
        ("drawdown", ">=", [-99.0, -5.0, -4.0, -3.0]),
        ("chg", ">=", [-99.0, 4.0, 6.0, 8.0]),
        ("sector20", ">=", [-99.0, 6.0, 9.0, 12.0]),
        ("mom60", ">=", [-99.0, 0.05, 0.10, 0.15]),
    ],
    "中线": [
        ("drawdown", ">=", [-99.0, -5.0, -4.0, -3.0]),
        ("sector20", ">=", [-99.0, 5.0, 8.0]),
    ],
}


def apply(rows, conds):
    out = []
    for r in rows:
        ok = True
        for feat, op, thr in conds:
            v = r[feat]
            if v is None:
                ok = False
                break
            if op == ">=" and not (v >= thr):
                ok = False
                break
            if op == "<=" and not (v <= thr):
                ok = False
                break
        if ok:
            out.append(r)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--months", type=int, default=12)
    ap.add_argument("--min-n", type=int, default=8, help="最小样本数")
    ap.add_argument("--target-wr", type=float, default=85.0, help="目标胜率%")
    args = ap.parse_args()

    cache, all_dates, date_to_idx = None, None, None
    from _full_cycle_backtest import load_cache
    from datetime import date
    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    ed = date.fromisoformat(all_dates[-1])
    y, m = ed.year, ed.month - args.months
    y += (m - 1) // 12
    m = (m - 1) % 12 + 1
    w_start = date(y, m, ed.day).isoformat() if ed.day <= 28 else date(y, m, 1).isoformat()
    w_end = all_dates[-1]
    print(f"窗口 {w_start} ~ {w_end} | 目标胜率≥{args.target_wr}% 且 n≥{args.min_n}")

    for period, grid in GRIDS.items():
        rows = collect(cache, all_dates, date_to_idx, period, w_start, w_end)
        base_n = len(rows)
        base_wr = 100.0 * len([r for r in rows if r["ret"] > 0]) / base_n if base_n else 0
        print("\n" + "=" * 100)
        print("[%s] 基线 n=%d 胜率=%.1f%%" % (period, base_n, base_wr))
        print("=" * 100)

        # 对每个因子的每档阈值独立扫描（单因子效果）
        print("  -- 单因子效果 --")
        for feat, op, thrs in grid:
            line = "  %-10s %s: " % (feat, op)
            for thr in thrs[1:]:
                conds = [(feat, op, thr)]
                out = apply(rows, conds)
                n = len(out)
                wr = 100.0 * len([r for r in out if r["ret"] > 0]) / n if n else 0
                line += "[%5.1f→n%2d wr%3.0f%%] " % (thr, n, wr)
            print(line)

        # 组合搜索：每个因子取一档（含 -99/99 表示不限），找满足目标的最优组合
        print("  -- 组合搜索（按胜率降序，显示达标前 12 组）--")
        candidates = [(-99.0, 99.0) for _ in grid]
        choices = []
        for i, (feat, op, thrs) in enumerate(grid):
            for thr in thrs:
                choices.append((i, thr))
        results = []
        for combo in product(*[grid[i][2] for i in range(len(grid))]):
            conds = [(grid[i][0], grid[i][1], combo[i]) for i in range(len(grid))]
            out = apply(rows, conds)
            n = len(out)
            if n < args.min_n:
                continue
            wr = 100.0 * len([r for r in out if r["ret"] > 0]) / n
            if wr >= args.target_wr:
                results.append((wr, n, combo))
        results.sort(key=lambda x: (-x[0], -x[1]))
        for wr, n, combo in results[:12]:
            desc = ", ".join("%s %s %.1f" % (grid[i][0], grid[i][1], combo[i]) for i in range(len(grid)))
            print("    wr=%5.1f%% n=%-3d  %s" % (wr, n, desc))


if __name__ == "__main__":
    main()
