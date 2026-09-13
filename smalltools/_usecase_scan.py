# -*- coding: utf-8 -*-
"""周期×环境 Usecase 矩阵扫描 —— 为 Kotlin 端「周期×环境=独立 Usecase」架构提供调参证据。

架构决策（与 Kotlin usecases/<id>_usecase.xml 对齐）：
  每周期 3 个独立 Usecase：<period>_BULLISH / <period>_OSCILLATION / <period>_BEARISH
  入口按 market_state(三指数MA排列) 路由一次，选择对应 usecase；
  每个 usecase 内部是固定 pipeline（无需动态切换节点，独立调参、独立回归）。

本脚本任务：对 3 周期 × 3 环境的每一格，扫描「形态策略 × 确认条件 × 卖出规则」，
寻找 90~98% 胜率（n≥8、avg>0）的可落地组合，输出 Usecase 卡 + JSON 定义。

数据：_kline_cache.json（126 只核心龙头 + 3 指数，2022-08 ~ 2026-08）
无未来函数：特征只看 asof 当日及之前，买入=次日开盘价，预计算路径 O(hold) 推导。
"""
import argparse
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _idea_scan import (  # noqa: E402
    load_cache, collect, build_paths, sim_path, SELL_GRID, ENVS,
    INDEXES, ZT,
)
from _pool_filters import build_sector_ret_table  # noqa: E402

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_records")
USECASE_CARD = os.path.join(OUT_DIR, "usecase_matrix.json")


# ══════════════════════════════════════════════════════════════════
# 条件库：每个条件是一个函数 f(feature) -> bool
# ══════════════════════════════════════════════════════════════════

def c_bull3(f): return bool(f["bull3"])
def c_ma60_up(f): return bool(f["ma60_up"])


def mk_spread(max_v):
    return lambda f: f["spread3"] <= max_v


def mk_stick(min_d):
    return lambda f: f["stick"] >= min_d


def mk_vr(min_v):
    return lambda f: f["vr"] >= min_v


def mk_chg(min_c):
    return lambda f: f["chg"] >= min_c


def mk_dd20(max_v):
    return lambda f: f["dd20"] <= max_v


def mk_vr_le(max_v):
    return lambda f: f["vr"] <= max_v


def mk_cmf_gt(v=0.0):
    return lambda f: f["cmf"] is not None and f["cmf"] > v


def mk_mfi_ge(v):
    return lambda f: f["mfi"] is not None and f["mfi"] >= v


def mk_zt(key, n):
    return lambda f: f.get(key, 0) >= n


def mk_sector_ge(v):
    return lambda f: f["sector20"] is not None and f["sector20"] >= v


def mk_sector_le(v):
    return lambda f: f["sector20"] is not None and f["sector20"] <= v


def c_ad_rising(f): return bool(f["ad_rising"])
def c_hammer(f): return bool(f["hammer"])
def c_engulf(f): return bool(f["eng"])
def c_morning(f): return bool(f["ms"])


def mk_regime_age(min_days):
    """环境已持续 ≥ min_days 个交易日（过滤环境切换初期噪声）。"""
    return lambda f: f.get("regime_age", 999) >= min_days


def c_regime_age5(f): return f.get("regime_age", 999) >= 5
def c_regime_age3(f): return f.get("regime_age", 999) >= 3


def mk_combo(conds):
    """把条件列表打包成策略函数。"""
    def fn(f):
        for c in conds:
            if not c(f):
                return False
        return True
    return fn


def add_regime_age(rows, all_dates):
    """为候选补充 regime_age：当前环境已连续持续的交易日数。
    rows 中同一 asof 的 regime 相同，取每日期首条即可构建序列。"""
    if not rows:
        return
    daily = {}
    for f in rows:
        daily.setdefault(f["asof"], f["regime"])
    # 沿 all_dates 计算每个交易日的环境持续天数
    age_map = {}
    last_regime = None
    age = 0
    for d in all_dates:
        if d in daily:
            r = daily[d]
            if r == last_regime:
                age += 1
            else:
                age = 1
                last_regime = r
            age_map[d] = age
    for f in rows:
        f["regime_age"] = age_map.get(f["asof"], 1)


# 形态策略模板：每周期给出一组候选组合（基础形态 × 确认 × 板块）
# 每种都从宽松候选里筛出 → 再用卖出网格扫描
TEMPLATES = {
    "超短": [
        # (名称, 条件列表)
        ("S1粘合突破", [c_bull3, c_ma60_up, mk_spread(3.0), mk_stick(3), mk_vr(1.5), mk_chg(1.0)]),
        ("S1严选", [c_bull3, c_ma60_up, mk_spread(2.0), mk_stick(5), mk_vr(2.0), mk_chg(2.0)]),
        ("S1极致粘合", [c_bull3, c_ma60_up, mk_spread(1.5), mk_stick(8), mk_vr(2.5), mk_chg(2.5)]),
        ("S1超强", [c_bull3, c_ma60_up, mk_spread(1.2), mk_stick(10), mk_vr(3.0), mk_chg(3.0), mk_zt("zt5", 1)]),
        ("S4K线反转", [c_ma60_up, c_engulf, mk_chg(0.5)]),
        ("S4强反转", [c_ma60_up, c_engulf, mk_chg(1.0), mk_vr(1.5), mk_zt("zt10", 1)]),
        ("S2资金流", [mk_mfi_ge(55), mk_cmf_gt(0)]),
        ("S2b吸筹背离", [c_ad_rising, mk_dd20(-8.0)]),
        ("S6缩量回踩5日线", [c_ma60_up, mk_vr_le(0.8), mk_dd20(-6.0), mk_cmf_gt(0)]),
    ],
    "短线": [
        ("S1粘合突破", [c_bull3, c_ma60_up, mk_spread(4.0), mk_stick(5), mk_vr(1.5), mk_chg(1.0)]),
        ("S1严选", [c_bull3, c_ma60_up, mk_spread(2.5), mk_stick(6), mk_vr(2.0), mk_chg(1.5)]),
        ("S4K线反转", [c_ma60_up, c_engulf, mk_chg(0.5)]),
        ("S2资金流", [mk_mfi_ge(55), mk_cmf_gt(0)]),
        ("S2b吸筹背离", [c_ad_rising, mk_dd20(-8.0)]),
        ("S3龙头深跌低吸", [mk_dd20(-15.0), mk_vr_le(0.8), mk_cmf_gt(0)]),
    ],
    "中线": [
        ("S5深回踩+资金流", [c_ma60_up, mk_dd20(-12.0), mk_vr_le(0.9), mk_cmf_gt(0), mk_chg(-4.0)]),
        ("S5深回踩严", [c_ma60_up, mk_dd20(-15.0), mk_vr_le(0.7), mk_cmf_gt(0), mk_chg(-3.0)]),
        ("S5深回踩极致", [c_ma60_up, mk_dd20(-18.0), mk_vr_le(0.6), mk_cmf_gt(0), mk_chg(-2.0)]),
        ("S1粘合突破", [c_bull3, c_ma60_up, mk_spread(4.0), mk_stick(5), mk_vr(1.5), mk_chg(1.0)]),
        ("S5+AD背离", [c_ma60_up, mk_dd20(-12.0), c_ad_rising, mk_cmf_gt(0)]),
    ],
}

# 确认条件（可叠加到形态上）
CONFIRMS = {
    "无": [],
    "涨停基因zt20": [mk_zt("zt20", 1)],
    "涨停基因zt10": [mk_zt("zt10", 1)],
    "基因强zt20≥2": [mk_zt("zt20", 2)],
    "资金流确认": [mk_mfi_ge(50), mk_cmf_gt(0)],
    "资金流强MFI60": [mk_mfi_ge(60), mk_cmf_gt(0)],
    "基因+资金流": [mk_zt("zt20", 1), mk_mfi_ge(50), mk_cmf_gt(0)],
    "基因+资金流强": [mk_zt("zt20", 1), mk_mfi_ge(60), mk_cmf_gt(0)],
    "板块热门≥0": [mk_sector_ge(0)],
    "板块热门≥3": [mk_sector_ge(3)],
    "板块热门≥5": [mk_sector_ge(5)],
    "板块弱剔除≤-3": [mk_sector_le(-3.0)],
}

# 双确认（在 CONFIRMS 基础上再叠加一层，形成"形态×确认×附加"三层过滤）
CONFIRMS2 = {
    "无": [],
    "基因zt20": [mk_zt("zt20", 1)],
    "资金流": [mk_mfi_ge(50), mk_cmf_gt(0)],
    "板块≥3": [mk_sector_ge(3)],
}

# 更细卖出网格（覆盖 _idea_scan.SELL_GRID）
SELL_GRID_FINE = {
    "超短": dict(tp=[0.4, 0.6, 0.8, 1.0, 1.2, 1.5, 2.0], sl=[-0.8, -1.0, -1.5, -2.0, -3.0], hold=[1, 2, 3]),
    "短线": dict(tp=[1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0], sl=[-1.5, -2.0, -3.0, -4.0, -5.0], hold=[3, 5, 8]),
    "中线": dict(tp=[3.0, 5.0, 8.0, 10.0, 12.0], sl=[-3.0, -4.0, -6.0, -8.0], hold=[10, 15, 20]),
}

# 合并后的确认项：单层 + 常见强双确认（避免全笛卡尔积爆炸）
CONFIRMS_ALL = dict(CONFIRMS)
CONFIRMS_ALL.update({
    "基因+板块≥3": [mk_zt("zt20", 1), mk_sector_ge(3)],
    "资金流+板块≥3": [mk_mfi_ge(50), mk_cmf_gt(0), mk_sector_ge(3)],
    "基因+资金流+板块≥3": [mk_zt("zt20", 1), mk_mfi_ge(50), mk_cmf_gt(0), mk_sector_ge(3)],
    "基因+资金流强+板块≥3": [mk_zt("zt20", 1), mk_mfi_ge(60), mk_cmf_gt(0), mk_sector_ge(3)],
    "基因强+资金流强+板块≥5": [mk_zt("zt20", 2), mk_mfi_ge(60), mk_cmf_gt(0), mk_sector_ge(5)],
    "环境持续≥3": [mk_regime_age(3)],
    "环境持续≥5": [mk_regime_age(5)],
    "环境持续≥3+基因": [mk_regime_age(3), mk_zt("zt20", 1)],
    "环境持续≥5+基因": [mk_regime_age(5), mk_zt("zt20", 1)],
    "环境持续≥3+资金流强": [mk_regime_age(3), mk_mfi_ge(60), mk_cmf_gt(0)],
    "环境持续≥5+资金流强": [mk_regime_age(5), mk_mfi_ge(60), mk_cmf_gt(0)],
})


def scan_cell(rows, paths, env, period, sell_grid, min_n=6, wr_target=(90.0, 98.5)):
    """对单格 (周期×环境) 扫描所有形态×确认×卖出规则×主板开关。
    预聚合：对每个形态×确认子集，先算每个候选在全部卖出规则下的收益矩阵，
    再按卖出规则聚合，避免重复重放 sim_path。
    返回候选列表，按「是否命中目标区间 + 胜率 + 样本量」排序。"""
    env_idx = [i for i, f in enumerate(rows) if f["regime"] == env]
    sell_cells = [(tp, sl, hold)
                  for tp in sell_grid["tp"] for sl in sell_grid["sl"] for hold in sell_grid["hold"]]
    results = []
    for tname, conds in TEMPLATES.get(period, []):
        for cname, cconds in CONFIRMS_ALL.items():
            fn = mk_combo(conds + cconds)
            sub = [(i, rows[i], paths[i]) for i in env_idx if fn(rows[i])]
            if len(sub) < min_n:
                continue
            # 主板/全市场两种池子
            pools = {"全市场": sub}
            main_only = [x for x in sub if x[1]["main_board"]]
            if len(main_only) >= min_n:
                pools["主板"] = main_only
            for pool_name, pool in pools.items():
                # 预聚合：每候选 → dict[(tp,sl,hold)] = ret
                mat = []
                for i, f, p in pool:
                    if p is None:
                        continue
                    row = {}
                    for cell in sell_cells:
                        r = sim_path(p, cell[0], cell[1], cell[2])
                        if r is not None:
                            row[cell] = r
                    if row:
                        mat.append(row)
                for cell in sell_cells:
                    rets = [r for row in mat if cell in row for r in [row[cell]]]
                    n = len(rets)
                    if n < min_n:
                        continue
                    wr = 100.0 * len([r for r in rets if r > 0]) / n
                    avg = sum(rets) / n
                    if avg <= 0:
                        continue
                    hit = wr_target[0] <= wr <= wr_target[1]
                    results.append(dict(
                        hit=hit, tpl=tname, conf=cname, pool=pool_name,
                        tp=cell[0], sl=cell[1], hold=cell[2],
                        n=n, wr=wr, avg=avg, cum=sum(rets),
                    ))
    # 排序：命中优先 → wr 降序 → n 降序
    results.sort(key=lambda r: (r["hit"], r["wr"], r["n"]), reverse=True)
    return results


def summarize_cell(period, env, results, top=4, min_n=6):
    if not results:
        return "    [%s] %s: 无满足 n≥%d 的正期望组合" % (period, env, min_n)
    lines = []
    lines.append("    [%s] %s: %d 个组合, 命中目标 %d 个" % (period, env, len(results), sum(1 for r in results if r["hit"])))
    for r in results[:top]:
        mark = " <<<" if r["hit"] else ""
        lines.append("      %s×%s(%s) tp=%+4.1f sl=%+4.1f hold=%d → n=%-4d wr=%5.1f%% avg=%+5.2f%% cum=%+7.1f%%%s"
                     % (r["tpl"], r["conf"], r["pool"], r["tp"], r["sl"], r["hold"], r["n"], r["wr"], r["avg"], r["cum"], mark))
    return "\n".join(lines)


def verify_cell(rows, paths, env, period, tpl, conf, pool, tp, sl, hold, min_n=3):
    """分年稳健性验证：对选定组合按自然年统计胜率/样本/均值。
    用于确认高胜率不是偶然集中在某一年。返回 [dict, ...]。"""
    conds = TEMPLATES.get(period, [])
    tconds = next((c for t, c in conds if t == tpl), None)
    if tconds is None:
        return []
    cconds = CONFIRMS_ALL.get(conf, [])
    fn = mk_combo(tconds + cconds)
    env_idx = [i for i, f in enumerate(rows) if f["regime"] == env]
    sub = [(rows[i], paths[i]) for i in env_idx if fn(rows[i])]
    if pool == "主板":
        sub = [x for x in sub if x[0]["main_board"]]
    years = {}
    for f, p in sub:
        if p is None:
            continue
        r = sim_path(p, tp, sl, hold)
        if r is None:
            continue
        y = f["asof"][:4]
        years.setdefault(y, []).append(r)
    out = []
    for y in sorted(years):
        rets = years[y]
        n = len(rets)
        wr = 100.0 * len([r for r in rets if r > 0]) / n
        avg = sum(rets) / n
        out.append(dict(year=y, n=n, wr=round(wr, 1), avg=round(avg, 2)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--months", type=int, default=36, help="回看窗口月数（默认36=三年）")
    ap.add_argument("--periods", type=str, default="超短,短线,中线")
    ap.add_argument("--env", type=str, default="", help="只扫描指定环境（空=全部）")
    ap.add_argument("--min_n", type=int, default=6, help="最小样本量（默认6，探索高胜率用小样本）")
    args = ap.parse_args()

    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    ed = all_dates[-1]
    y, m = int(ed[:4]), int(ed[5:7])
    m -= args.months
    y += (m - 1) // 12
    m = (m - 1) % 12 + 1
    w_start = "%04d-%02d-%02d" % (y, m, int(ed[8:10]))
    w_end = all_dates[-1]
    print("Usecase 矩阵扫描 | 窗口 %s ~ %s | %s" % (w_start, w_end, args.periods))

    build_sector_ret_table(cache, 20)
    print("板块表已构建")

    envs = [e for e in ENVS if not args.env or e == args.env]
    os.makedirs(OUT_DIR, exist_ok=True)
    card = {}
    for period in [x for x in ("超短", "短线", "中线") if x in args.periods]:
        print("\n" + "=" * 72)
        print("[%s] 收集候选…" % period)
        rows = collect(cache, all_dates, date_to_idx, period, w_start, w_end)
        add_regime_age(rows, all_dates)
        paths = build_paths(cache, all_dates, rows)
        print("  宽松候选 n=%d (有效路径 %d)" % (len(rows), sum(1 for p in paths if p)))
        env_counts = Counter(f["regime"] for f in rows)
        print("  环境分布: %s" % dict(env_counts))
        card[period] = {}
        for env in envs:
            print()
            results = scan_cell(rows, paths, env, period, SELL_GRID_FINE[period], min_n=args.min_n)
            print(summarize_cell(period, env, results, min_n=args.min_n))
            if not results:
                card[period][env] = {"strategy": None, "reason": "无正期望组合(n≥%d)" % args.min_n}
                continue
            best = next((r for r in results if r["hit"]), None) or results[0]
            v = verify_cell(rows, paths, env, period, best["tpl"], best["conf"], best["pool"],
                            best["tp"], best["sl"], best["hold"])
            card[period][env] = {
                "strategy": best["tpl"],
                "confirm": best["conf"],
                "pool": best["pool"],
                "sell": {"tp": best["tp"], "sl": best["sl"], "hold": best["hold"]},
                "n": best["n"], "winrate": round(best["wr"], 1), "avg": round(best["avg"], 2),
                "hit90": best["hit"],
                "env": env,
                "years": v,
            }
            ystr = "  ".join("%s:n=%d wr=%.0f%%" % (x["year"], x["n"], x["wr"]) for x in v)
            print("    [%s]%s %s×%s(%s) 分年: %s" % (period, env, best["tpl"], best["conf"], best["pool"], ystr))
    with open(USECASE_CARD, "w", encoding="utf-8") as f:
        json.dump(card, f, ensure_ascii=False, indent=2)
    print("\nUsecase 卡已写入: %s" % USECASE_CARD)
    print(json.dumps(card, ensure_ascii=False, indent=2))
    print("\n完成")


if __name__ == "__main__":
    main()
