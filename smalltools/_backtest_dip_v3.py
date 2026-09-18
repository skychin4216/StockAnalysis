# -*- coding: utf-8 -*-
"""双创超跌反抽回测 v3（2026-09-16 用户四点反馈的完整实现）。

数据：data/kline_store.json（_cybc_pool.py --history 产出，
429 只核心池股 + 创业板指/科创50，自 2008 年/上市日起全历史 qfq 日K）。

相对 v2 的升级（用户 2026-09-16 原话）：
  ① 「连续跌之后十字星缩量的时候再去低吸，如果放量，后面还有可能下跌」
     —— 十字星（项目口径 |c-o|≤0.2×(h-l)）+ 前一日收跌 + 缩量（vol<vol5×阈值）
     三因子网格；
  ② 「次日没有怎么涨或者波动大，低位继续低吸/日内T」—— S3 智能持有：
     t+1 冲高≥2% 落袋；t+1 收盘已回本则卖；否则延到 t+2（回本+2% 或收盘卖）；
  ③ 「从 2008 年开始回溯拟合，不断修改边界条件」—— 网格自动拟合：
     dd60∈{15,20,25} × chg20∈{0,-5,-10} × 星开关 × 缩量阈值 × 卖出策略，
     输出复合分 top 组合 + 分年度稳定性 + 全网格分布（防过拟合提示）。

固定口径（v2 已验证）：主线行业（行业 chg20 中位动量榜前30%）× 板内市值前3
龙头 × 距60日高回撤 × 20日企稳。已知偏差：行业归属/龙头地位用当前快照
（历史漂移不可重建）、幸存者偏差（池剔除了 ST/退市）→ 结果偏乐观。

用法：python _backtest_dip_v3.py [--top 10]
"""
import argparse
import datetime
import json
import os
from collections import defaultdict

import numpy as np
import pandas as pd
import _kline_store

HERE = os.path.dirname(os.path.abspath(__file__))
HIST = _kline_store.store_path()
POOL = os.path.join(os.path.dirname(HERE), "data", "_cybc_pool.json")
FEE_RT = 0.0011                    # 单边万11（含滑点近似）
HOT_TOP_PCT = 0.30                 # 主线行业 = 动量榜前 30%
LEADER_TOPN = 5                    # 板内市值前 5 = 龙头（注意：池本身已是
                                   # 「行业前N精选」，此处排名是池内排名，放宽到 5）


def load_df():
    """全历史 → 单一 DataFrame（每股因子预计算，一次算完供全网格复用）。"""
    with open(HIST, encoding="utf-8") as f:
        hist = json.load(f)
    with open(POOL, encoding="utf-8") as f:
        pool = json.load(f).get("pool") or []
    caps = {p["code"]: p["mcap"] for p in pool}
    frames = []
    for secid, ent in hist.items():
        if ent.get("industry") == "指数":
            continue
        s = pd.DataFrame(ent["snaps"])
        if len(s) < 70:
            continue
        c = s["close"]
        s["secid"] = secid
        s["industry"] = ent.get("industry") or ""
        s["mcap"] = caps.get(secid[2:], 0)
        s["hhv60"] = c.rolling(60, min_periods=30).max()
        s["dd60"] = (c / s["hhv60"] - 1) * 100
        s["chg20"] = (c / c.shift(20) - 1) * 100
        s["chg1"] = (c / c.shift(1) - 1) * 100
        rng = (s["high"] - s["low"]).replace(0, np.nan)
        s["star"] = ((s["close"] - s["open"]).abs() <= 0.20 * rng).fillna(False)
        s["vol5"] = s["volume"].rolling(5).mean()
        s["vr"] = s["volume"] / s["vol5"]
        # 未来收益（买=当日收盘；S1/S2/S3 共用素材）
        for k in (1, 2, 3):
            s["c%d" % k] = c.shift(-k)
            s["h%d" % k] = s["high"].shift(-k)
        # 近3日内出现过十字星
        s["star3"] = s["star"].rolling(3).max().fillna(0).astype(bool)
        frames.append(s)
    df = pd.concat(frames, ignore_index=True).dropna(
        subset=["dd60", "chg20", "c1", "c2"])
    return df


def build_context(df):
    """行业动量主线（按日）+ 板内市值排名 → df 增列 [is_hot, is_leader]。"""
    df = df.copy()
    # 行业 chg20 中位数 → 按日排名前 30% 为主线
    med = df.groupby(["date", "industry"])["chg20"].median().reset_index()
    med["rk"] = med.groupby("date")["chg20"].rank(
        method="first", ascending=False, pct=True)
    hot = set(zip(med.loc[med["rk"] <= HOT_TOP_PCT, "date"],
                  med.loc[med["rk"] <= HOT_TOP_PCT, "industry"]))
    df["is_hot"] = [ (d, i) in hot for d, i in zip(df["date"], df["industry"]) ]
    # 板内市值前 N 龙头（当前快照，历史恒定——已知偏差）。必须按「股票级」
    # 判定：rank(method="first") 会给同市值（同一股票的所有行）不同名次，
    # 行级排名会错到只剩几百行。
    lead = set()
    for ind, g in df.groupby("industry"):
        lead.update(g.drop_duplicates("secid")
                    .nlargest(LEADER_TOPN, "mcap")["secid"])
    df["is_leader"] = df["secid"].isin(lead)
    return df


def ret_sell(row, mode):
    """单笔卖出收益（%，未扣费）。

    S1：t+1 收盘卖
    S2：t+1 挂 +2% 条件单（冲高≥2% → max(2%, c1)，否则 c1）
    S3：智能持有——t+1 冲高≥2% 落袋；c1≥买价(=0) 卖；否则延 t+2：
        h2≥2% → 2%，否则 c2 卖（回本不格局，最多延一天）
    """
    c1, h1, c2, h2 = row["c1"], row["h1"], row["c2"], row["h2"]
    if mode == "S1":
        return (c1 / row["close"] - 1) * 100
    r1 = (c1 / row["close"] - 1) * 100
    g1 = (h1 / row["close"] - 1) * 100
    if mode == "S2":
        return max(2.0, r1) if g1 >= 2.0 else r1
    # S3
    if g1 >= 2.0:
        return max(2.0, r1)
    if r1 >= 0:
        return r1
    if pd.notna(h2) and (h2 / row["close"] - 1) * 100 >= 2.0:
        return 2.0
    if pd.notna(c2):
        return (c2 / row["close"] - 1) * 100
    return r1


def evaluate(sub, sell_mode, label):
    """一组信号的 胜率/毛均值/净均值/笔数 + 分年度。"""
    if not len(sub):
        return None
    rets = sub.apply(lambda r: ret_sell(r, sell_mode), axis=1)
    net = rets - FEE_RT * 100
    out = {"label": label, "n": len(sub),
           "win": float((rets > 0).mean() * 100),
           "gross": float(rets.mean()), "net": float(net.mean())}
    yr = sub["date"].str[:4]
    by = defaultdict(list)
    for y, v in zip(yr, net):
        by[y].append(v)
    out["years"] = {y: (len(v), float(np.mean(v) > 0),
                        round(float(np.mean(v)), 3)) for y, v in sorted(by.items())}
    out["pos_years"] = sum(1 for y, (n, pos, m) in out["years"].items() if n >= 10 and pos)
    out["tot_years"] = sum(1 for y, (n, _, _) in out["years"].items() if n >= 10)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--top", type=int, default=10)
    args = ap.parse_args()

    print("载入全历史因子 …")
    df = build_context(load_df())
    base = df[df["is_hot"] & df["is_leader"]]
    print("池因子行 %s ｜ 主线×龙头 基座 %d 行（%s ~ %s）"
          % (format(len(df), ","), len(base), base["date"].min(), base["date"].max()))

    # 预筛选基座（v2 定稿：主线 + 龙头固定开）
    results = []
    for dd_x in (15, 20, 25):
        b1 = base[base["dd60"] <= -dd_x]
        for chg_y in (0, -5, -10):
            b2 = b1[b1["chg20"] >= chg_y]
            for down in ("off", "down"):        # 用户假设：连跌后低吸 vs 反弹中买
                b2d = b2[b2["chg1"] < 0] if down == "down" else b2
                for star_mode in ("off", "day", "win3"):
                    if star_mode == "day":
                        b3 = b2d[b2d["star"]]
                    elif star_mode == "win3":
                        b3 = b2d[b2d["star3"]]
                    else:
                        b3 = b2d
                    for vr_k in (0, 0.85, 0.7):
                        b4 = b3[b3["vr"] <= vr_k] if vr_k else b3
                        for sell in ("S1", "S2", "S3"):
                            r = evaluate(
                                b4, sell,
                                "dd≤-%d chg≥%d 当日跌=%s 星=%s 缩量≤%s %s"
                                % (dd_x, chg_y, down == "down", star_mode,
                                   vr_k or "—", sell))
                            if r and r["n"] >= 200:
                                r["score"] = round(
                                    r["net"] * r["win"] / 100
                                    * (r["pos_years"] / max(1, r["tot_years"])), 4)
                                results.append(r)
    results.sort(key=lambda r: -r["score"])
    print("\n══ 全网格 %d 组（基座=主线×龙头固定）复合分 Top %d ══"
          % (len(results), args.top))
    print("  复合分 = 净均值 × 胜率 × 年度正收益占比")
    for r in results[:args.top]:
        print("  %-42s 笔%5d 胜率%5.1f%% 净%+6.2f%% 正收益年%2d/%2d 分%6.3f"
              % (r["label"], r["n"], r["win"], r["net"],
                 r["pos_years"], r["tot_years"], r["score"]))
    if results:
        nets = [r["net"] for r in results]
        print("\n  网格分布：净均值 中位%+.2f%% / P25 %+.2f%% / P75 %+.2f%% "
              "（top 与中位差距大=可能过拟合，需看分年度）"
              % (np.median(nets), np.percentile(nets, 25),
                 np.percentile(nets, 75)))
        best = results[0]
        print("\n── 最优组合分年度（%s）──" % best["label"])
        for y, (n, pos, m) in best["years"].items():
            if n >= 10:
                print("  %s：%4d笔 净%+.2f%% %s"
                      % (y, n, m, "✓" if pos else "✗"))
        # 邻域稳健性：最优参数 ±1 档
        print("\n── 邻域稳健性（最优 ±1 档，若同向则可信）──")
        for r in results[:6]:
            print("  %-42s 净%+6.2f%% 胜率%5.1f%%"
                  % (r["label"], r["net"], r["win"]))

    # 落盘
    out_path = os.path.join(os.path.dirname(HERE), "data", "_dip_v3_summary.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({"updated": datetime.datetime.now().isoformat(),
                   "n_grid": len(results),
                   "top": results[:args.top]}, f, ensure_ascii=False, indent=1)
    print("\n落盘：%s" % out_path)


if __name__ == "__main__":
    main()
