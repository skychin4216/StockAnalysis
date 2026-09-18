# -*- coding: utf-8 -*-
"""双创超跌反抽策略回测（2026-09-15，Step 2 拟合第一步）。

口径（用户 2026-09-15 反馈修正后的 v2 思路）：
  - 不能只看 PE：要看【板块内相对地位】——长飞/巨石这类深跌龙头是赢家主力，
    市值阈值按行业动态划分（板内市值排名/中位数），不是全市场统一线；
  - 工业富联类（大市值 + 无技术壁垒 + 非结构牛主线）急跌后排除——用
    「行业主线热度」条件自然排除（其板块近20日不强势）；
  - 大盘下跌时找【弹性大】（日均振幅 ATR20 高）+ 深跌后企稳（chg20 不再创新低）
    的票，当天买次日卖，拿一个晚上。

回测：近一年全池逐日回放（_cybc_cache.json，1707 只双创+3 指数）。
  信号日 t 满足条件 → close(t) 买，close(t+1) 卖（另记 t+1 最高价冲高卖）。
  因子全部用截至 t 的数据（无未来函数）；
  已知近似/偏差（结果解读时注意）：
    ① 市值/行业排名用当前快照（历史市值不可得，行业地位近似稳定）；
    ② 股票池 = 当前上市股票（退市股缺失，幸存者偏差，收益偏乐观）；
    ③ PE 为当前值，v1 口径仅作参考不做硬过滤。

输出：基准 vs v1（小市值+超跌+量比）vs v2A（龙头修复）vs v2B（弹性小票）
      + 参数敏感性网格（dd 阈值 × 企稳阈值 × 量比 × 主线条件）→ 为正式
      接入 XML DAG 拟合定参数带。

用法：python _backtest_dip_rebound.py
"""
import datetime
import json
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

SNAP = os.path.join(os.path.dirname(HERE), "data", "_market_snapshot.json")
CACHE_FILE = os.path.join(HERE, "data", "_kline_cybc.json")
FEE_RT = 0.0015  # 一次进出：佣金×2 + 印花税(卖) ≈ 0.15%

HOT_TOP_PCT = 0.30       # 行业主线 = 行业动量榜前 30%
LEADER_TOPN = 3          # 龙头 = 板内市值前 3
ATR_MIN = 5.0            # 通道B 弹性门槛（日均振幅 %）
_HOT = {}                # date -> 主线行业集合（main 里填充，供 topk/month_table 用）


# ───────────────────────── 数据与因子 ─────────────────────────

def load_all():
    with open(CACHE_FILE, encoding="utf-8") as f:
        cache = json.load(f)
    with open(SNAP, encoding="utf-8") as f:
        snap = json.load(f)
    caps = {}
    for secid, ent in cache.items():
        if secid.startswith(("sh000", "sz399")):
            continue
        caps[secid] = float(snap.get(secid, {}).get("mv_total_yi") or 0)  # 亿
    return cache, caps


def median(xs):
    xs = sorted(xs)
    n = len(xs)
    return xs[n // 2] if n % 2 else (xs[n // 2 - 1] + xs[n // 2]) / 2


def build_rows(cache, caps):
    """逐股逐日因子（无未来函数）。返回 {date: [row, ...]}。

    row = (secid, cap, ind, dd60, chg20, atr20, vr5, ret_c, ret_h, leader, below_med)
      dd60/chg20/atr20/vr5 = 截至 t；ret_c/ret_h = t→t+1 收益/冲高收益
      leader/below_med = 板内市值地位（当前快照近似）
    """
    # 行业分组（板内市值排名/中位）
    by_ind = defaultdict(list)
    for secid, cap in caps.items():
        ent = cache.get(secid) or {}
        if cap > 0:
            by_ind[ent.get("industry") or "其他"].append((cap, secid))
    leader_set, below_med_set = set(), set()
    for ind, members in by_ind.items():
        members.sort(reverse=True)
        leader_set.update(s for _, s in members[:LEADER_TOPN])
        med = median([c for c, _ in members])
        below_med_set.update(s for c, s in members if c < med)

    rows = defaultdict(list)
    for secid, ent in cache.items():
        if secid.startswith(("sh000", "sz399")) or secid not in caps:
            continue
        snaps = ent.get("snaps") or []
        n = len(snaps)
        if n < 65:
            continue
        closes = [s["close"] for s in snaps]
        highs = [s["high"] for s in snaps]
        lows = [s["low"] for s in snaps]
        vols = [s["volume"] for s in snaps]
        dates = [s["date"] for s in snaps]
        ind = ent.get("industry") or "其他"
        cap = caps[secid]
        base = (secid, cap, ind, secid in leader_set, secid in below_med_set)
        for t in range(60, n - 1):
            c = closes[t]
            if c <= 0:
                continue
            dd60 = (c / max(closes[t - 59:t + 1]) - 1) * 100
            chg20 = (c / closes[t - 20] - 1) * 100
            atr20 = sum((highs[i] - lows[i]) / closes[i]
                        for i in range(t - 19, t + 1)) / 20 * 100
            v5 = sum(vols[t - 5:t]) / 5
            vr5 = vols[t] / v5 if v5 > 0 else 1.0
            ret_c = (closes[t + 1] / c - 1) * 100
            ret_h = (highs[t + 1] / c - 1) * 100
            rows[dates[t]].append(
                base + (dd60, chg20, atr20, vr5, ret_c, ret_h))
    return rows


# ───────────────────────── 统计 ─────────────────────────

def stats(rets, label, months=None):
    if not rets:
        print("  %-34s  无交易" % label)
        return
    n = len(rets)
    win = sum(1 for r in rets if r > 0)
    mean = sum(rets) / n
    srt = sorted(rets)
    med = srt[n // 2]
    gross = sum(r for r in rets if r > 0)
    loss = -sum(r for r in rets if r <= 0)
    pf = gross / loss if loss > 0 else float("inf")
    net = (mean - FEE_RT * 100)
    print("  %-34s %4d笔 胜率%5.1f%% 均值%+6.2f%% 中位%+6.2f%% "
          "PF %5.2f 净均值%+6.2f%%"
          % (label, n, win / n * 100, mean, med, pf, net))
    if months is not None:
        months.append((label, n, win / n * 100, mean))


def month_table(rows, pred, label):
    by_m = defaultdict(list)
    for d, lst in rows.items():
        hot = _HOT.get(d, set())
        for r in lst:
            if pred(r, hot):
                by_m[d[:7]].append(r[9])   # ret_c（索引9；5dd 6chg 7atr 8vr 9ret_c 10ret_h）
    print("\n  [%s] 月度分布（净均值已扣双边 0.15%%）：" % label)
    for m in sorted(by_m):
        rs = by_m[m]
        print("    %s  %4d笔  胜率%5.1f%%  毛均值%+6.2f%%  净%+6.2f%%"
              % (m, len(rs), sum(1 for x in rs if x > 0) / len(rs) * 100,
                 sum(rs) / len(rs), sum(rs) / len(rs) - FEE_RT * 100))


def topk_nav(rows, pred, k=5, key=None):
    """每天最多 k 只等权、按 key 排序优先，日度复利净值。

    2026-09-15：卖出按回测验证的 +2% 条件单模拟（次日冲高≥2% 按 max(2%,收盘)
    落袋，未达则收盘卖）——比收盘卖口径显著更优（单笔净 +0.62% vs +0.13%）。
    """
    nav = 1.0
    days = 0
    for d in sorted(rows):
        hot = _HOT.get(d, set())
        cands = [r for r in rows[d] if pred(r, hot)]
        if not cands:
            continue
        if key:
            cands.sort(key=key)
        cands = cands[:k]
        rets = []
        for r in cands:
            rc, rh = r[9], r[10]
            rets.append(max(2.0, rc) if rh >= 2.0 else rc)
        avg = sum(rets) / len(rets) - FEE_RT * 100
        nav *= (1 + avg / 100)
        days += 1
    print("    组合模拟（日取前%d，等权复利，+2%%条件单卖出）：%d 个交易日 → 累计 %+.1f%%"
          % (k, days, (nav - 1) * 100))


# ───────────────────────── 主流程 ─────────────────────────

def main():
    print("加载数据…")
    cache, caps = load_all()
    rows = build_rows(cache, caps)
    dates = sorted(rows)
    n_rows = sum(len(v) for v in rows.values())
    print("回放区间 %s ~ %s（%d 个交易日，%d 个股·日样本）\n"
          % (dates[0], dates[-1], len(dates), n_rows))

    # 行业动量（截至 t）：板内成员 chg20 中位 → 行业排名 → 前 30% 为主线
    hot_by_date = {}
    mom_ind_by_date = {}
    for d, lst in rows.items():
        by_ind = defaultdict(list)
        for r in lst:
            by_ind[r[2]].append(r[5])          # chg20
        moms = {i: median(v) for i, v in by_ind.items() if len(v) >= 3}
        ranked = sorted(moms.items(), key=lambda kv: -kv[1])
        cut = max(1, int(len(ranked) * HOT_TOP_PCT))
        hot_by_date[d] = {i for i, _ in ranked[:cut]}
        mom_ind_by_date[d] = moms
    _HOT.update(hot_by_date)

    # 因子列位置：0 secid 1 cap 2 ind 3 leader 4 below_med
    #            5 dd60 6 chg20 7 atr20 8 vr5→(ret_c) ... 注意 build_rows 顺序：
    # base(5) + dd60, chg20, atr20, vr5, ret_c, ret_h → 索引 5..10
    # 修正：5=dd60 6=chg20 7=atr20 8=vr5 9=ret_c 10=ret_h
    DD, CHG, ATR, VR, RC, RH = 5, 6, 7, 8, 9, 10

    def collect(pred, date_ok=None):
        out = []
        for d, lst in rows.items():
            if date_ok and not date_ok(d):
                continue
            hot = hot_by_date[d]
            for r in lst:
                if pred(r, hot):
                    out.append(r[RC])
        return out

    def collect_full(pred, date_ok=None):
        out = []
        for d, lst in rows.items():
            if date_ok and not date_ok(d):
                continue
            hot = hot_by_date[d]
            for r in lst:
                if pred(r, hot):
                    out.append((d, r))
        return out

    print("═" * 86)
    print("口径对比（隔日收盘卖；净均值=毛均值-双边0.15%%）")
    print("═" * 86)
    stats(collect(lambda r, h: True), "v0 基准：全池次日收益")

    stats(collect(lambda r, h: r[1] < 200 and r[DD] <= -15),
          "v1a 小市值<200亿+深跌15%")
    stats(collect(lambda r, h: r[1] < 200 and r[DD] <= -15 and r[VR] > 1.5),
          "v1b v1a+量比>1.5（用户原口径，无PE）")
    stats(collect(lambda r, h: r[1] < 200 and r[DD] <= -15 and r[VR] > 1.5
                  and r[CHG] >= -3),
          "v1c v1b+企稳(chg20≥-3%)")

    print()
    stats(collect(lambda r, h: r[3] and r[DD] <= -15 and r[CHG] >= -3),
          "v2A 龙头修复（板内前3+深跌+企稳）")
    stats(collect(lambda r, h: r[3] and r[DD] <= -15 and r[CHG] >= -3
                  and r[2] in h),
          "v2A+ 主线行业（动量榜前30%）")
    stats(collect(lambda r, h: r[3] and r[DD] <= -15 and r[CHG] >= -3
                  and r[2] in h and r[VR] > 1.5),
          "v2A+主线+量比>1.5")

    stats(collect(lambda r, h: r[4] and r[ATR] >= ATR_MIN
                  and r[DD] <= -15 and r[CHG] >= -3),
          "v2B 弹性小票（板内中位下+ATR≥5）")
    stats(collect(lambda r, h: r[4] and r[ATR] >= ATR_MIN and r[DD] <= -15
                  and r[CHG] >= -3 and r[2] in h and r[VR] > 1.5),
          "v2B+ 主线+量比>1.5")

    # 龙头修复 + 任意企稳 + 主线 —— 用户「巨石/长飞」型
    print()
    print("─" * 86)
    print("参数敏感性（v2A+主线 口径为基座）")
    print("─" * 86)
    for dd in (-12, -15, -20, -25):
        for chg in (-8, -5, -3, 0):
            stats(collect(lambda r, h, dd=dd, chg=chg:
                          r[3] and r[DD] <= dd and r[CHG] >= chg and r[2] in h),
                  "v2A 龙头 dd≤%d chg20≥%d" % (dd, chg))

    print()
    print("─" * 86)
    print("指数环境门控（v2A 龙头 dd≤-20 chg20≥-5 + 主线 为基座，创业板指 sz399006）")
    print("─" * 86)
    idx_snaps = cache.get("sz399006", {}).get("snaps") or []
    idx_dates = [s["date"] for s in idx_snaps]
    idx_closes = [s["close"] for s in idx_snaps]
    idx_pos = {d: i for i, d in enumerate(idx_dates)}

    def idx_chg(d, k):
        i = idx_pos.get(d)
        if i is None or i < k:
            return None
        return (idx_closes[i] / idx_closes[i - k] - 1) * 100

    gates = {
        "g1 指数当日收涨": lambda d: (idx_chg(d, 1) or -99) > 0,
        "g2 指数5日≥-1%": lambda d: (idx_chg(d, 5) or -99) >= -1,
        "g3 指数20日≥-5%": lambda d: (idx_chg(d, 20) or -99) >= -5,
        "g1+g3": lambda d: (idx_chg(d, 1) or -99) > 0 and (idx_chg(d, 20) or -99) >= -5,
        "g2+g3": lambda d: (idx_chg(d, 5) or -99) >= -1 and (idx_chg(d, 20) or -99) >= -5,
    }
    base_pred = lambda r, h: (r[3] and r[DD] <= -20 and r[CHG] >= -5 and r[2] in h)
    stats(collect(base_pred), "基座（无门控）")
    for gname, g in gates.items():
        stats(collect(base_pred, g), "基座 + " + gname)

    full = collect_full(base_pred, gates["g1+g3"])
    if full:
        rh = [r[RH] for _, r in full]
        print("  [基座+g1+g3] 次日冲高（high(t+1)/close(t)）均值 %+.2f%% 中位 %+.2f%% "
              "≥2%%占比 %.0f%% —— 若挂 +2%% 止盈单可改善落袋"
              % (sum(rh) / len(rh), sorted(rh)[len(rh) // 2],
                 sum(1 for x in rh if x >= 2) / len(rh) * 100))
        days = sorted({d for d, _ in full})
        print("  信号日 %d 天 / 共 %d 天（无信号即空仓，不硬买）" % (len(days), len(dates)))

    # +2% 条件单模拟（基座无门控）：次日冲高≥2% 按 +2% 落袋，否则收盘价卖
    full_base = collect_full(base_pred)
    if full_base:
        rc = [r[RC] for _, r in full_base]
        rh2 = [r[RH] for _, r in full_base]
        # 次日冲高≥2% → 挂单按 max(2%, close) 落袋（高开高走直接超 2% 时以收盘更优），
        # 未达 2% → 收盘价卖
        cond = [max(2.0, c) if h >= 2.0 else c for c, h in zip(rc, rh2)]
        n = len(cond)
        win = sum(1 for x in cond if x > 0)
        print("  [基座无门控·%d笔] 收盘卖: 毛%+.2f%% → +2%%条件单: 毛%+.2f%% 胜率%.1f%% 净%+.2f%%"
              % (n, sum(rc) / n, sum(cond) / n, win / n * 100,
                 sum(cond) / n - FEE_RT * 100))

    print()
    print("─" * 86)
    print("组合模拟与月度（基座 + g1+g3 门控）")
    print("─" * 86)
    pred_best = lambda r, h: (r[3] and r[DD] <= -20 and r[CHG] >= -5 and r[2] in h)
    gate_best = gates["g1+g3"]
    topk_nav({d: lst for d, lst in rows.items() if gate_best(d)},
             pred_best, k=5, key=lambda r: -r[DD])   # 跌得最深优先
    topk_nav({d: lst for d, lst in rows.items() if gate_best(d)},
             pred_best, k=5, key=lambda r: r[1])     # 市值最小优先
    topk_nav(rows, pred_best, k=5, key=lambda r: -r[DD])  # 无门控（回测更优）
    topk_nav(rows, pred_best, k=3, key=lambda r: -r[DD])
    month_table({d: lst for d, lst in rows.items() if gate_best(d)},
                pred_best, "基座+g1+g3（dd≤-20 chg20≥-5 主线龙头）")

    # 落盘明细供探针/后续拟合引用
    outp = os.path.join(HERE, "data", "_dip_backtest_summary.json")
    best = [d for d in dates]
    with open(outp, "w", encoding="utf-8") as f:
        json.dump({"dates": [dates[0], dates[-1]],
                   "n_days": len(dates), "n_rows": n_rows,
                   "fee_rt_pct": FEE_RT * 100}, f, ensure_ascii=False, indent=1)
    print("\n落盘：%s" % outp)


if __name__ == "__main__":
    main()
