# -*- coding: utf-8 -*-
"""国家队/大基金共振 —— 逐笔明细 + 见顶时间 + 峰值后回撤 + 分市场环境（探针）。

回答 `docs/national-flow-fit-report.md` 无法回答的四件事：
  1. **每一笔买到什么**（日期 / 代码 / 名称 / 买入价 / 所属ETF主线），可逐笔核账
  2. **多久见顶、顶点后回落多少**：峰值日（第几个交易日）+ 峰值后最大回撤 + 末尾收益
  3. **分市场环境**（BULLISH / OSCILLATION / BEARISH / CRASH）各自成功率
  4. **宽基口径**（上证50/沪深300/创业板/科创50 ETF 前五重仓）

口径与 `_national_flow_fit.py` 完全同源（直接 import 复用其 trading_days / top5_covered /
层定义 / fly 阈值 / 北向判定），只在此基础上"多记几列"，不改判定逻辑。

市场环境：`market_state` 表只覆盖 2024 起，故按 `backtest_guangmo.get_index_dir` +
`triple_vote` 的原规则，用三大指数日线（2008 起齐全）自行复算。

用法：
  python _national_flow_detail.py --step 40    # 快速试跑（估时）
  python _national_flow_detail.py              # 全量 step5
产物：data/_national_flow_detail.csv（逐笔明细）/ data/_national_flow_detail.json（分层汇总）
"""
import argparse
import csv
import datetime as dt
import json
import os
import sqlite3
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import _etf_holdings as EH              # noqa: E402
import _national_flow as NF             # noqa: E402
import _national_flow_fit as NFIT       # noqa: E402   ← 口径单一源

DATA_DIR = os.path.join(ROOT, "data")
MAX_H = NFIT.MAX_H
IDX = ("sh000001", "sz399001", "sz399006")
REGIMES = ("BULLISH", "OSCILLATION", "BEARISH", "CRASH")


# ───────────────────────── 市场环境（复刻项目规则） ─────────────────────────

def load_index_series():
    con = sqlite3.connect(EH.MARKET_DB_PATH)
    try:
        out = {}
        for s in IDX:
            rows = con.execute(
                "SELECT date, close FROM kline WHERE secid=? ORDER BY date", (s,)).fetchall()
            out[s] = ([r[0] for r in rows], [float(r[1]) for r in rows])
    finally:
        con.close()
    return out


def build_regime(series, days_all):
    """复刻 `_full_cycle_backtest.market_state`（CRASH 优先 → 三指数 MA 排列投票）。"""
    pos = {s: {d: i for i, d in enumerate(ds)} for s, (ds, _c) in series.items()}
    gp = {d: i for i, d in enumerate(days_all)}
    out = {}
    for d in days_all:
        p = gp[d]
        rec = days_all[max(0, p + 1 - min(p + 1, 6)): p + 1]
        drops = []
        for s in IDX:
            ds, cs = series[s]
            cl = [cs[pos[s][x]] for x in rec if x in pos[s]]
            if len(cl) >= 2:
                drops.append((cl[-1] / cl[0] - 1) * 100)
        if drops and sum(drops) / len(drops) <= -4.0:
            out[d] = "CRASH"
            continue
        dirs = []
        for s in IDX:
            _ds, cs = series[s]
            i = pos[s].get(d)
            if i is None or i < 19:
                dirs.append("UNKNOWN")
                continue
            c = cs[i - 19:i + 1]
            ma5, ma10, ma20 = sum(c[-5:]) / 5, sum(c[-10:]) / 10, sum(c[-20:]) / 20
            dirs.append("BULLISH" if ma5 > ma10 > ma20
                        else "BEARISH" if ma5 < ma10 < ma20 else "OSCILLATION")
        v = [x for x in dirs if x != "UNKNOWN"]
        if len(v) < 2:
            out[d] = "UNKNOWN"
        elif v.count("BULLISH") >= 2:
            out[d] = "BULLISH"
        elif v.count("BEARISH") >= 2:
            out[d] = "BEARISH"
        else:
            out[d] = "OSCILLATION"
    return out


# ───────────────────────── 逐笔探针（NFIT.probe 的超集） ─────────────────────────

def probe_detail(hist, code, day):
    ent = hist.get(code)
    if not ent:
        return None
    snaps = ent.get("snaps") or []
    i = -1
    for k in range(len(snaps) - 1, -1, -1):
        if snaps[k]["date"] <= day:
            i = k
            break
    if i < 0 or i + 1 >= len(snaps):
        return None
    buy = float(snaps[i + 1].get("open") or 0)
    if buy <= 0:
        return None
    seg = snaps[i + 1:i + 1 + MAX_H]
    if len(seg) < MAX_H:
        return None
    highs = [float(s.get("high") or 0) for s in seg]
    lows = [float(s.get("low") or 0) for s in seg]
    if not highs or not lows:
        return None
    mx = max(highs)
    pk = max(range(len(highs)), key=lambda k: highs[k])
    mn = min(lows)
    dk = min(range(len(lows)), key=lambda k: lows[k])
    post = lows[pk + 1:] or [lows[pk]]
    end_close = float(seg[-1].get("close") or buy)
    return {
        "buy_date": seg[0]["date"], "buy": round(buy, 3),
        "g20": round((max(highs[:20]) / buy - 1) * 100, 2),
        "g60": round((max(highs[:60]) / buy - 1) * 100, 2),
        "g120": round((mx / buy - 1) * 100, 2),
        "maxgain": round((mx / buy - 1) * 100, 2),
        "peak_off": pk + 1, "peak_date": seg[pk]["date"],
        "dd_off": dk + 1, "maxdd": round((mn / buy - 1) * 100, 2),
        "post_peak_dd": round((min(post) / mx - 1) * 100, 2),
        "end_ret": round((end_close / buy - 1) * 100, 2),
        "r10": round((float(seg[9].get("close") or buy) / buy - 1) * 100, 2),
        "dip_first": dk < pk,          # 先跌后涨 = 最低点早于最高点
    }


def wide_covered(hold):
    """宽基 ETF 前五重仓覆盖的 A 股（用户点名的 沪深300/中证500 等宽基口径）。"""
    s = set()
    for f in (hold.get("funds") or []):
        if (f.get("theme") or "") != "宽基":
            continue
        for x in (f.get("top") or [])[:5]:
            s.add(x["code"])
    return s


# ───────────────────────── 统计 ─────────────────────────

def agg(rows):
    n = len(rows)
    if not n:
        return {"n": 0}
    o = {"n": n}
    for win, th in NFIT.FLY:
        o["fly%d" % win] = round(sum(1 for r in rows if r["g%d" % win] >= th) / n * 100, 1)
    o["avg_maxgain"] = round(statistics.mean(r["maxgain"] for r in rows), 1)
    o["med_maxgain"] = round(statistics.median(r["maxgain"] for r in rows), 1)
    o["avg_maxdd"] = round(statistics.mean(r["maxdd"] for r in rows), 1)
    o["med_maxdd"] = round(statistics.median(r["maxdd"] for r in rows), 1)
    o["avg_postpeak"] = round(statistics.mean(r["post_peak_dd"] for r in rows), 1)
    o["med_peak_off"] = round(statistics.median(r["peak_off"] for r in rows), 1)
    o["avg_end_ret"] = round(statistics.mean(r["end_ret"] for r in rows), 1)
    o["med_end_ret"] = round(statistics.median(r["end_ret"] for r in rows), 1)
    o["win_end"] = round(sum(1 for r in rows if r["end_ret"] > 0) / n * 100, 1)
    o["win10"] = round(sum(1 for r in rows if r["r10"] > 0) / n * 100, 1)
    o["dip_first_pct"] = round(sum(1 for r in rows if r["dip_first"]) / n * 100, 1)
    return o


PEAK_BUCKETS = ((1, 5), (6, 10), (11, 20), (21, 40), (41, 60), (61, 120))


def peak_hist(rows):
    """见顶时间分布（第几个交易日摸到 120 日最高价）。"""
    n = len(rows)
    if not n:
        return []
    return [("%d-%d日" % b, round(sum(1 for r in rows if b[0] <= r["peak_off"] <= b[1]) / n * 100, 1))
            for b in PEAK_BUCKETS]


def has(row, layer):
    # ★ 必须按逗号切分：「big」是「natbig」的子串，子串匹配会串层
    return layer in row["layers"].split(",")


def main():
    ap = argparse.ArgumentParser(description="国家队/大基金共振 逐笔明细 + 分环境统计")
    ap.add_argument("--start", default="2008-01-01")
    ap.add_argument("--end", default=dt.date.today().isoformat())
    ap.add_argument("--step", type=int, default=5)
    ap.add_argument("--csv", default=os.path.join(DATA_DIR, "_national_flow_detail.csv"))
    ap.add_argument("--out", default=os.path.join(DATA_DIR, "_national_flow_detail.json"))
    a = ap.parse_args()

    days_all = NFIT.trading_days(a.start, a.end)
    days = days_all[::max(1, a.step)]
    hist = EH.load_top5_hist().get("codes") or {}
    if len(hist) < 30:
        print("构建 ETF 前五历史K线 ...")
        hist = EH.build_top5_hist()
    nf_stocks = (NF.load_national_hist() or {}).get("stocks") or {}
    if not nf_stocks:
        print("✗ 缺 data/_national_flow_hist.json")
        return 1
    hold = EH.load_holdings() or {}
    t5 = NFIT.top5_covered()
    wide = wide_covered(hold)
    nb = NFIT.load_nb()
    regime = build_regime(load_index_series(), days_all)
    tags = EH.etf_tag_map()

    print("窗口 %s ~ %s（%d 交易日，step=%d → %d 采样日）" % (
        days_all[0], days_all[-1], len(days_all), a.step, len(days)))
    print("候选池: 历史K线 %d 只 | top5覆盖≥2 %d 只 | 宽基前五 %d 只 | 机构资产 %d 只"
          % (len(hist), len(t5), len(wide), len(nf_stocks)))

    rows = []
    for day in days:
        st = EH.screen_picks_asof(day, hist=hist)
        uniq = {}
        for _fc, info in st.items():
            for pk in info["picks"]:
                uniq.setdefault(pk["code"], pk)
        rg = regime.get(day, "UNKNOWN")
        for code in uniq:
            pr = probe_detail(hist, code, day)
            if pr is None:
                continue
            b = NF.flow_bundle(nf_stocks.get(code) or {}, day)
            nat, big, ss = b["natState"], b["bigState"], b["ssState"]
            nbv = NFIT.nb_in_at(nb, code, day)
            natbig = nat == "流入" or big == "流入"
            lay = ["all"]
            if nat == "流入":
                lay.append("nat")
            if big == "流入":
                lay.append("big")
            if natbig:
                lay.append("natbig")
                if ss == "流入":
                    lay.append("natbig_ss")
            if ss == "流入":
                lay.append("ss")
            if nbv is True:
                lay.append("nb")
            if nat == "无" and big == "无" and ss == "无" and nbv is None:
                lay.append("none")
            pr.update({"sig_date": day, "code": code,
                       "name": (hist.get(code) or {}).get("name") or code,
                       "tag": tags.get(code, ""), "regime": rg,
                       "in_top5": code in t5, "in_wide": code in wide,
                       "layers": ",".join(lay)})
            rows.append(pr)

    print("信号 %d 条（明细已采集）" % len(rows))

    res = {"window": "%s ~ %s" % (days[0], days[-1]), "step": a.step, "signals": len(rows),
           "universe": {"hist": len(hist), "top5": len(t5), "wide": len(wide),
                        "nf": len(nf_stocks)}}
    pools = {"industry": lambda r: True,
             "top5": lambda r: r["in_top5"],
             "wide": lambda r: r["in_wide"]}
    for pname, keep in pools.items():
        sub = [r for r in rows if keep(r)]
        res[pname] = {"n": len(sub)}
        for L in NFIT.LAYERS:
            res[pname][L] = agg([r for r in sub if has(r, L)])
        res[pname]["by_regime"] = {
            R: {L: agg([r for r in sub if r["regime"] == R and has(r, L)])
                for L in ("all", "nat", "big", "natbig_ss")}
            for R in REGIMES}
        key = [r for r in sub if has(r, "natbig_ss")]
        res[pname]["natbig_ss_peak"] = peak_hist(key)
        res[pname]["natbig_ss_examples"] = sorted(key, key=lambda r: -r["maxgain"])[:15]

    os.makedirs(DATA_DIR, exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=1, default=str)

    cols = ["sig_date", "code", "name", "tag", "regime", "layers", "in_top5", "in_wide",
            "buy_date", "buy", "g20", "g60", "g120", "maxgain", "peak_off", "peak_date",
            "dd_off", "maxdd", "post_peak_dd", "r10", "end_ret", "dip_first"]
    with open(a.csv, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        w.writerows(sorted(rows, key=lambda r: (r["sig_date"], r["code"])))

    for pname in ("industry", "top5", "wide"):
        d = res[pname]
        if not d["n"]:
            print("\n[%s] 无样本" % pname)
            continue
        print("\n" + "═" * 106)
        print("═══ 池=%s ｜ 信号 %d 条" % (pname, d["n"]))
        print("═" * 106)
        print("%-11s %6s %9s %9s %9s %8s %8s %8s %8s %8s %8s" % (
            "层", "N", "20日≥20%", "60日≥30%", "120日≥50%", "平均最高", "中位最高",
            "均回撤", "峰后回落", "见顶中位", "120末均"))
        for L in NFIT.LAYERS:
            s = d[L]
            if not s.get("n"):
                continue
            print("%-11s %6d %8.1f%% %8.1f%% %8.1f%% %7.1f%% %7.1f%% %7.1f%% %7.1f%% %8.1f %7.1f%%" % (
                L, s["n"], s["fly20"], s["fly60"], s["fly120"], s["avg_maxgain"],
                s["med_maxgain"], s["avg_maxdd"], s["avg_postpeak"], s["med_peak_off"],
                s["avg_end_ret"]))
        print("  共振层见顶时间分布: %s" % (" | ".join("%s %.1f%%" % (k, v)
                                                   for k, v in d["natbig_ss_peak"])))
        print("  分环境:")
        for R in REGIMES:
            g = d["by_regime"][R]
            fa, fn = g["all"], g["natbig_ss"]
            print("    %-12s all n=%-5d fly20=%-6s 均最高=%-6s | natbig_ss n=%-4d fly20=%-6s fly120=%-6s 均最高=%-6s" % (
                R, fa.get("n", 0), fa.get("fly20", "-"), fa.get("avg_maxgain", "-"),
                fn.get("n", 0), fn.get("fly20", "-"), fn.get("fly120", "-"),
                fn.get("avg_maxgain", "-")))

    print("\n✓ 明细 %s\n✓ 汇总 %s" % (a.csv, a.out))
    return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
