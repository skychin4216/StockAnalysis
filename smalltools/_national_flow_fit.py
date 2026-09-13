# -*- coding: utf-8 -*-
"""国家队/大基金持有进出 —— 回溯拟合（回答「按这个思路选到的股票，后期起飞的概率是多少」）。

规则出处：
  · E:\\Android\\work\\dev\\选股思路\\national_flow_research.md
  · E:\\Android\\work\\dev\\选股思路\\institutional_breakout_guide.md（§8 FundFlowAnalyzer）
  · E:\\Android\\work\\dev\\选股思路\\institutional_breakout.py（NATIONAL_AUTHORITY / 连续两期确认）

口径（与线上 node 同源，全部无未来函数）：
  信号池  ① industry = ETF 全行业扫描（`EH.screen_picks_asof` 的低吸候选，pos60 ≤ -1%）
          ② top5     = 上者 ∩「被 ≥2 只行业/主题 ETF 覆盖」（覆盖矩阵 n≥2，剔除仅宽基）
  买入    T+1 开盘价（信号日收盘后决策）
  机构层  用 `_national_flow.flow_bundle` 判当期状态，**只用 NOTICE_DATE ≤ 信号日 的法定披露**
  起飞    未来 N 日内**最高价**相对买入价的涨幅 ≥ 阈值：
            fly20 = 20 日内 ≥ +20%   fly30 = 60 日内 ≥ +30%   fly50 = 120 日内 ≥ +50%
          不完整窗口（样本未满 N 日）一律丢弃，避免尾部系统性低估。

分层（每层都在同一信号池上做机构过滤，便于横向对比 lift）：
  all 全部低吸 / nat 国家队流入 / big 大基金流入 / natbig 两者之一流入
  natbig_ss (国家队|大基金) 且社保流入 / ss 社保流入 / nb 北向增持 / none 三者均无披露

用法：
  python _national_flow_fit.py                          # 两个 usecase，2008 起，step5
  python _national_flow_fit.py --usecase top5 --step 3
  python _national_flow_fit.py --start 2015-01-01 --end 2026-08-31
产物：data/_national_flow_fit.json
"""
import argparse
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

import _etf_holdings as EH   # noqa: E402
import _national_flow as NF  # noqa: E402

DATA_DIR = os.path.join(ROOT, "data")
OUT_FILE = os.path.join(DATA_DIR, "_national_flow_fit.json")
HOLDER_HIST = os.path.join(DATA_DIR, "_holder_hist.json")

MAX_H = 120                       # 最长观测窗口（须完整，否则丢弃）
FLY = ((20, 20.0), (60, 30.0), (120, 50.0))
LAYERS = ("all", "nat", "big", "natbig", "natbig_ss", "ss", "nb", "none")


# ───────────────────────── 数据准备 ─────────────────────────

def trading_days(start, end):
    """沪深300/上证 指数日线覆盖的交易日（与大盘同频，避免个股停牌日错位）。"""
    con = sqlite3.connect(EH.MARKET_DB_PATH)
    try:
        rows = con.execute(
            "SELECT date FROM kline WHERE secid='sh000001' AND date>=? AND date<=? "
            "ORDER BY date", (start, end)).fetchall()
        if not rows:
            rows = con.execute(
                "SELECT DISTINCT date FROM kline WHERE date>=? AND date<=? ORDER BY date",
                (start, end)).fetchall()
    finally:
        con.close()
    return [r[0] for r in rows]


def top5_covered():
    """覆盖矩阵 n≥2 的个股集合（剔除仅被宽基覆盖）—— 即 top5 usecase 的观察池。"""
    hold = EH.load_holdings() or {}
    cnt = {}
    for f in hold.get("funds") or []:
        if (f.get("theme") or "") in ("宽基",):
            continue
        for s in (f.get("top") or [])[:5]:
            cnt[s["code"]] = cnt.get(s["code"], 0) + 1
    return {c for c, n in cnt.items() if n >= 2}


def load_nb():
    """北向（香港中央结算）逐季记录：{code: [{end,ratio,notice}]}。"""
    try:
        with open(HOLDER_HIST, encoding="utf-8") as f:
            return (json.load(f) or {}).get("nb") or {}
    except (OSError, ValueError):
        return {}


def nb_in_at(nb, code, day):
    """北向最新已披露季较上季占比升 ≥0.35pp → True（NOTICE_DATE≤day，无未来函数）。"""
    rows = sorted([r for r in (nb.get(code) or [])
                   if r.get("notice") and r["notice"] <= day], key=lambda r: r["end"])
    if not rows:
        return None
    if len(rows) >= 2 and rows[-1]["end"] > rows[-2]["end"]:
        return (rows[-1]["ratio"] - rows[-2]["ratio"]) >= 0.35
    return False


# ───────────────────────── 起飞探针 ─────────────────────────

def probe(hist, code, day):
    """T+1 开盘买入，统计未来 20/60/120 日最高涨幅与最大回撤。窗口不完整返回 None。"""
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
    seg = snaps[i + 1: i + 1 + MAX_H]
    if len(seg) < MAX_H:          # 不完整窗口丢弃（否则尾部样本被系统性低估）
        return None

    def hi_of(n):
        return max(float(s.get("high") or 0) for s in seg[:n])

    lows = [float(s.get("low") or 0) for s in seg if float(s.get("low") or 0) > 0]
    return {"buy": buy,
            "g20": (hi_of(20) / buy - 1) * 100,
            "g60": (hi_of(60) / buy - 1) * 100,
            "g120": (hi_of(120) / buy - 1) * 100,
            "maxgain": (max(float(s.get("high") or 0) for s in seg) / buy - 1) * 100,
            "maxdd": (min(lows) / buy - 1) * 100 if lows else 0.0,
            "r10": (float(seg[9].get("close") or buy) / buy - 1) * 100}


def summarize(rows):
    """一层信号的起飞统计。"""
    n = len(rows)
    if not n:
        return {"n": 0}
    out = {"n": n}
    for win, th in FLY:
        key = "g%d" % win
        out["fly%d" % win] = round(sum(1 for r in rows if r[key] >= th) / n * 100, 2)
        out["fly%d_th" % win] = th
    out["avg_maxgain"] = round(statistics.mean(r["maxgain"] for r in rows), 2)
    out["med_maxgain"] = round(statistics.median(r["maxgain"] for r in rows), 2)
    out["p75_maxgain"] = round(sorted(r["maxgain"] for r in rows)[int(n * 0.75)], 2)
    out["avg_maxdd"] = round(statistics.mean(r["maxdd"] for r in rows), 2)
    out["win10"] = round(sum(1 for r in rows if r["r10"] > 0) / n * 100, 2)
    out["avg_r10"] = round(statistics.mean(r["r10"] for r in rows), 2)
    return out


# ───────────────────────── 主流程 ─────────────────────────

def run_one(usecase, days, hist, nf_stocks, nb, t5):
    step_txt = "industry" if usecase == "industry" else "top5(覆盖≥2)"
    buckets = {L: [] for L in LAYERS}
    n_sig = 0
    for day in days:
        st = EH.screen_picks_asof(day, hist=hist)
        uniq = {}
        for _fc, info in st.items():
            for pk in info["picks"]:
                c = pk["code"]
                if c in uniq:
                    continue
                if usecase == "top5" and c not in t5:
                    continue
                uniq[c] = pk
        for code in uniq:
            pr = probe(hist, code, day)
            if pr is None:
                continue
            n_sig += 1
            b = NF.flow_bundle(nf_stocks.get(code) or {}, day)
            nat, big, ss = b["natState"], b["bigState"], b["ssState"]
            nbv = nb_in_at(nb, code, day)
            natbig = nat == "流入" or big == "流入"
            hit = {"all"}
            if nat == "流入":
                hit.add("nat")
            if big == "流入":
                hit.add("big")
            if natbig:
                hit.add("natbig")
                if ss == "流入":
                    hit.add("natbig_ss")
            if ss == "流入":
                hit.add("ss")
            if nbv is True:
                hit.add("nb")
            if nat == "无" and big == "无" and ss == "无" and nbv is None:
                hit.add("none")
            for L in hit:
                buckets[L].append(pr)
    return {"usecase": step_txt, "signals": n_sig,
            "layers": {L: summarize(buckets[L]) for L in LAYERS}}


def report(res):
    base = (res["layers"].get("all") or {}).get("fly20")
    print("\n" + "═" * 96)
    print("═══ %s ｜ 信号 %d 条 ｜ %s" % (res["usecase"], res["signals"], res.get("window", "")))
    print("═" * 96)
    print("%-12s %7s %11s %11s %11s %10s %10s %8s" % (
        "层", "N", "20日≥20%", "60日≥30%", "120日≥50%",
        "平均最高", "中位最高", "10日胜率"))
    for L in LAYERS:
        s = res["layers"].get(L) or {}
        if not s.get("n"):
            continue
        lift = ""
        if L != "all" and base is not None:
            lift = "  (lift %+.1fpp)" % (s["fly20"] - base)
        print("%-12s %7d %10.1f%% %10.1f%% %10.1f%% %9.1f%% %9.1f%% %7.1f%%%s" % (
            L, s["n"], s["fly20"], s["fly60"], s["fly120"],
            s["avg_maxgain"], s["med_maxgain"], s["win10"], lift))
    print("  注：平均/中位最高 = 未来120日内最高价相对T+1开盘的最大涨幅；最大回撤均值 %.1f%%(all)"
          % ((res["layers"].get("all") or {}).get("avg_maxdd") or 0))


def main():
    ap = argparse.ArgumentParser(description="国家队/大基金持有进出 回溯拟合（起飞概率）")
    ap.add_argument("--start", default="2008-01-01")
    ap.add_argument("--end", default=dt.date.today().isoformat())
    ap.add_argument("--step", type=int, default=5, help="采样步长（交易日）")
    ap.add_argument("--usecase", default="both", choices=("industry", "top5", "both"))
    ap.add_argument("--out", default=OUT_FILE)
    a = ap.parse_args()

    days_all = trading_days(a.start, a.end)
    if not days_all:
        print("窗口内无交易日")
        return 2
    days = days_all[::max(1, a.step)]
    hist = EH.load_top5_hist().get("codes") or {}
    if len(hist) < 30:
        print("构建 ETF 前五历史K线 ...")
        hist = EH.build_top5_hist()
    nf = NF.load_national_hist()
    nf_stocks = nf.get("stocks") or {}
    if not nf_stocks:
        print("✗ 缺 data/_national_flow_hist.json：先跑 python _national_flow.py --build")
        return 1
    nb = load_nb()
    t5 = top5_covered()
    print("窗口 %s ~ %s（%d 交易日，采样步长 %d → %d 天）" % (
        days_all[0], days_all[-1], len(days_all), a.step, len(days)))
    print("top5 历史覆盖 %d 只 ｜ 国家队/大基金资产 %d 只（判定日 %s）｜ 覆盖≥2 池 %d 只" % (
        len(hist), len(nf_stocks), nf.get("judge_day"), len(t5)))
    print("起飞口径：20日≥+20% / 60日≥+30% / 120日≥+50%；T+1 开盘买入；窗口不完整一律丢弃")

    uses = ("industry", "top5") if a.usecase == "both" else (a.usecase,)
    results = []
    for u in uses:
        r = run_one(u, days, hist, nf_stocks, nb, t5)
        r["window"] = "%s ~ %s" % (days[0], days[-1])
        results.append(r)
        report(r)

    out = {"start": days_all[0], "end": days_all[-1], "step": a.step,
           "sampled": len(days), "note":
           "信号=ETF前五重仓低吸候选（industry=全行业；top5=覆盖≥2）；T+1开盘买入；"
           "机构状态只用 NOTICE_DATE≤信号日 的十大流通股东法定披露（无未来函数）；"
           "起飞=未来N日最高价≥买入价×(1+阈值)；窗口不完整丢弃",
           "layers_meaning": {
               "all": "全部低吸候选", "nat": "国家队(汇金/证金)流入", "big": "大基金流入",
               "natbig": "国家队或大基金流入", "natbig_ss": "国家队|大基金 且 社保流入",
               "ss": "社保流入", "nb": "北向季度增持≥0.35pp", "none": "三者均无披露"},
           "results": results}
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("\n✓ 已存 %s" % a.out)
    return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
