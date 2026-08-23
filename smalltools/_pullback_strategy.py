# -*- coding: utf-8 -*-
"""回踩低吸策略验证（超短/短线专用买点重构候选）。

现有超短/短线节点是「放量突破追涨」，一年实证胜率 41%/37.5%，无法靠过滤提到 90%。
本脚本验证替代买点「趋势中的缩量回踩低吸」，先宽松收集一次候选（含特征），
再在内存中对 回踩幅度/量比/涨幅/板块 网格重放，找高胜率参数。

  趋势前提：MA20 > MA60 且 MA60 上升；收盘站上 MA20
  买入信号：距 10 日高点回踩 2%~maxPull；缩量 vol<=volCap；今日不再创新低；涨幅温和
  排除：大盘 CRASH；ST/科创/创业（主板开关）；板块20日涨幅 < sectorMin

性能（M1 修复）：
  1) build_sector_ret_table 预计算板块涨幅表 → sector_ret20 O(1) 查表
  2) collect 按股票外层遍历，预取 closes/highs/lows/vols/chgs 数组，
     信号日判定用索引定位（零切片），全程无逐日全量快照过滤
"""
import argparse
import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _full_cycle_backtest import load_cache, market_state, simulate_trade, SELL_RULES  # noqa: E402
from _pool_filters import build_sector_ret_table, sector_ret20  # noqa: E402
import _pool_filters as _pf  # noqa: E402

INDEXES = ["sh000001", "sz399001", "sz399006"]
RULES_KEY = {"超短": "超短线", "短线": "短线"}
NON_MAIN = ("sh688", "sh689", "sz300", "sz301", "bj8", "sh51", "sh56", "sz15", "sz16")

# 宽松基础条件（收集用）
BASE = dict(maxPull=14.0, volCap=1.5, chgCap=5.0, sectorMin=-99.0)
GRID = {
    "超短": dict(maxPull=[6.0, 8.0, 10.0], volCap=[0.8, 1.0, 1.2], chgCap=[2.0, 3.0], sectorMin=[0.0, -3.0]),
    "短线": dict(maxPull=[8.0, 10.0, 12.0], volCap=[0.8, 1.0, 1.2], chgCap=[2.0, 3.0], sectorMin=[0.0, -3.0]),
}


def base_pullback(A, end, p, market_regime, sector20):
    """宽松回踩判定。A=(closes,highs,lows,vols,chgs) 为该股全量数组，
    end 为信号日索引（含当日，snaps 按时间升序）。返回特征 dict 或 None。"""
    if end < 65 or market_regime == "CRASH":
        return None
    if sector20 is not None and sector20 < p["sectorMin"]:
        return None
    closes, highs, lows, vols, chgs = A
    c0 = closes[end]
    ma20 = sum(closes[end - 19:end + 1]) / 20.0
    ma60 = sum(closes[end - 59:end + 1]) / 60.0
    ma60_prev = sum(closes[end - 64:end - 4]) / 60.0
    if not (ma20 > ma60 and ma60 > ma60_prev):
        return None
    if c0 < ma20:
        return None
    hi10 = max(highs[end - 9:end + 1])
    pull = (hi10 - c0) / hi10 * 100.0
    if not (2.0 <= pull <= p["maxPull"]):
        return None
    vol5 = sum(vols[end - 5:end]) / 5.0 if end >= 5 else 0.0
    vr = vols[end] / vol5 if vol5 > 0 else 1.0
    if vr > p["volCap"]:
        return None
    if end >= 3:
        ref_low = max(lows[end - 3:end])
        if lows[end] < ref_low:
            return None
    if chgs[end] > p["chgCap"]:
        return None
    # 涨停基因：近 5/10/20 日内 changePct >= 9.5 的涨停日数（强势确认）
    zt5 = sum(1 for c in chgs[max(0, end - 4):end + 1] if c >= 9.5)
    zt10 = sum(1 for c in chgs[max(0, end - 9):end + 1] if c >= 9.5)
    zt20 = sum(1 for c in chgs[max(0, end - 19):end + 1] if c >= 9.5)
    return dict(pull=pull, vr=vr, chg=chgs[end], sector20=sector20,
                zt5=zt5, zt10=zt10, zt20=zt20)


def collect(cache, all_dates, date_to_idx, period, w_start, w_end):
    """按股票外层遍历收集宽松回踩候选（含特征字段），主板/非主板标记随行保留。"""
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    st_by_date = {d: market_state(idx_snaps, d, all_dates, date_to_idx)
                  for d in all_dates if w_start <= d < w_end}
    rows = []
    for code, ent in cache.items():
        if code.startswith(("sh000", "sz399")):
            continue
        name = ent.get("name") or code
        if "ST" in (name or "").upper():
            continue
        snaps = ent.get("snaps") or []
        if len(snaps) < 65:
            continue
        closes = [s["close"] for s in snaps]
        highs = [s["high"] for s in snaps]
        lows = [s["low"] for s in snaps]
        vols = [s["volume"] for s in snaps]
        chgs = [s.get("changePct", 0.0) for s in snaps]
        A = (closes, highs, lows, vols, chgs)
        for end in range(len(snaps)):
            asof = snaps[end]["date"]
            if asof >= w_end:
                break
            if asof < w_start:
                continue
            st = st_by_date[asof]
            r20 = sector_ret20(cache, all_dates, date_to_idx, code, name, asof)
            f = base_pullback(A, end, BASE, st, r20)
            if f is None:
                continue
            f.update(code=code, name=name, asof=asof, idx=date_to_idx.get(asof, 0) + 1, st=st)
            f["main_board"] = not code.startswith(NON_MAIN)
            rows.append(f)
    return rows


def replay(cache, all_dates, rows, period, p, main_on):
    rule = SELL_RULES[RULES_KEY[period]]
    rets = []
    for f in rows:
        if main_on and not f["main_board"]:
            continue
        if not (2.0 <= f["pull"] <= p["maxPull"]):
            continue
        if f["vr"] > p["volCap"]:
            continue
        if f["chg"] > p["chgCap"]:
            continue
        if f["sector20"] is not None and f["sector20"] < p["sectorMin"]:
            continue
        t = simulate_trade(cache, all_dates, (f["code"], f["name"], f["asof"], f["idx"], f["st"]), rule)
        if t:
            rets.append(t["ret"])
    n = len(rets)
    if n == 0:
        return 0, 0.0, 0.0
    wr = 100.0 * len([r for r in rets if r > 0]) / n
    return n, wr, sum(rets)


def sim_tp_sl(cache, all_dates, f, tp, sl, max_hold):
    """小止盈/宽止损模拟。买入=信号次日开盘；持有期内逐日：
    盘中触及 +tp% 止盈（优先）或 -sl% 止损；到期未触发按最后一日收盘卖。
    返回 ret% 或 None（无后续数据）。"""
    buy_idx = f["idx"]
    if buy_idx >= len(all_dates):
        return None
    snaps = cache.get(f["code"], {}).get("snaps") or []
    by_date = {s["date"]: s for s in snaps}
    bs = by_date.get(all_dates[buy_idx])
    if not bs or bs["open"] <= 0:
        return None
    entry = bs["open"]
    last_close = None
    for k in range(1, max_hold + 1):
        di = buy_idx + k
        if di >= len(all_dates):
            break
        day = by_date.get(all_dates[di])
        if not day:
            continue
        hi = day.get("high", day["close"])
        lo = day.get("low", day["close"])
        last_close = day["close"]
        if tp > 0 and hi >= entry * (1 + tp / 100.0):
            return tp
        if sl < 0 and lo <= entry * (1 + sl / 100.0):
            return sl
    if last_close is not None:
        return (last_close / entry - 1) * 100.0
    return None


# 卖出规则扫描网格（tp/sl 单位为 %，hold 为持有交易日上限）
SELL_GRID = {
    "超短": dict(tp=[1.0, 1.5, 2.0, 2.5], sl=[-1.5, -2.0, -3.0], hold=[1, 2]),
    "短线": dict(tp=[2.0, 3.0, 4.0, 6.0], sl=[-2.0, -3.0, -4.0], hold=[3, 5]),
}


def scan_sell(cache, all_dates, rows, period, main_on, zt_key=None, zt_min=1):
    """固定买点=宽松 BASE，扫描卖出规则 (tp, sl, hold)，看胜率天花板。
    zt_key/zt_min 可选：要求候选满足「近 N 日内涨停 ≥ 1 次」（涨停基因确认）。"""
    best = None
    for tp in SELL_GRID[period]["tp"]:
        for sl in SELL_GRID[period]["sl"]:
            for hold in SELL_GRID[period]["hold"]:
                rets = []
                for f in rows:
                    if main_on and not f["main_board"]:
                        continue
                    if zt_key and f.get(zt_key, 0) < zt_min:
                        continue
                    r = sim_tp_sl(cache, all_dates, f, tp, sl, hold)
                    if r is not None:
                        rets.append(r)
                n = len(rets)
                if n == 0:
                    continue
                wr = 100.0 * len([r for r in rets if r > 0]) / n
                avg = sum(rets) / n
                tag = " <<<" if (90.0 <= wr <= 98.0 and n >= 8) else ""
                print("    tp=%+4.1f%% sl=%+4.1f%% hold=%d → n=%-4d wr=%5.1f%% avg=%+5.2f%% cum=%+7.1f%%%s"
                      % (tp, sl, hold, n, wr, avg, sum(rets), tag))
                if wr >= 85 and n >= 8 and (best is None or (wr, n) > (best[0], best[1])):
                    best = (wr, n, (tp, sl, hold))
    return best


# 涨停基因档位：None=不要求；zt20/zt10/zt5 = 近 20/10/5 日内涨停过
ZT_TIERS = [
    ("全部候选(对照)", None, 0),
    ("近20日涨停过", "zt20", 1),
    ("近10日涨停过", "zt10", 1),
    ("近5日涨停过", "zt5", 1),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--months", type=int, default=12)
    args = ap.parse_args()

    cache = load_cache()
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    ed = date.fromisoformat(all_dates[-1])
    y, m = ed.year, ed.month - args.months
    y += (m - 1) // 12
    m = (m - 1) % 12 + 1
    w_start = date(y, m, ed.day).isoformat() if ed.day <= 28 else date(y, m, 1).isoformat()
    w_end = all_dates[-1]
    print(f"回踩低吸策略验证 | 窗口 {w_start} ~ {w_end}")

    # M1 性能修复：预计算板块涨幅表（全缓存一次），collect 内 sector_ret20 变 O(1) 查表
    build_sector_ret_table(cache, 20)
    print("板块涨幅表已构建：%d 个板块" % len(_pf._SECTOR_TABLE["data"]))

    for period, g in GRID.items():
        rows = collect(cache, all_dates, date_to_idx, period, w_start, w_end)
        print("\n[%s] 宽松候选 n=%d" % (period, len(rows)))
        for main_on in (True, False):
            print("  ---- 主板开关=%s ----" % ("开" if main_on else "关"))
            best = None
            for mp in g["maxPull"]:
                for vc in g["volCap"]:
                    for cc in g["chgCap"]:
                        for sm in g["sectorMin"]:
                            p = dict(maxPull=mp, volCap=vc, chgCap=cc, sectorMin=sm)
                            n, wr, cum = replay(cache, all_dates, rows, period, p, main_on)
                            tag = " <<<" if (wr >= 85 and n >= 8) else ""
                            print("    pull≤%4.1f%% vol≤%.1f chg≤%.1f%% 板块≥%5.1f%% → n=%-3d wr=%5.1f%% cum=%+7.1f%%%s"
                                  % (mp, vc, cc, sm, n, wr, cum, tag))
                            if wr >= 85 and n >= 8 and (best is None or (wr, n) > (best[0], best[1])):
                                best = (wr, n, p)
            print("    BEST: %s" % (best,))

        # ---- 卖出规则扫描：固定买点=宽松 BASE，按涨停基因分档 ----
        print("  == 卖出规则扫描（买点=宽松，按涨停基因分档 × 小止盈宽止损） ==")
        for zt_label, zt_key, zt_min in ZT_TIERS:
            print("   ## %s ##" % zt_label)
            for main_on in (True, False):
                print("   ---- 主板开关=%s ----" % ("开" if main_on else "关"))
                b = scan_sell(cache, all_dates, rows, period, main_on, zt_key, zt_min)
                print("    SELL BEST: %s" % (b,))


if __name__ == "__main__":
    main()
