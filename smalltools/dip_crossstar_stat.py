# -*- coding: utf-8 -*-
"""
十字星分歧信号 四形态回溯（2008-01-02 ~ 数据末端）
====================================================
数据：公共市场库 StockAnalysis/data/market_data.db（kline 表，2008-01-02 起全量；
     来源 _kline_cache.json + _extend_history_2008.py 的 2008-2015 hfq 拼接段）。

背景（用户思路，做短线视角）：
  大盘回调中出现十字星(分歧)后，星后 1..7 交易日最先走出的 K 线形态有四种：
    big_yin    大阴线(≤-1.5%)      → 恐慌释放，低吸候选
    small_yin  小阴线(-1.5~0)      → 弱势阴跌，低吸候选
    big_yang   大阳线(≥+1.5%)      → 强势转阳，追买候选
    small_yang 小阳线(0~+1.5%)     → 弱转阳，确认候选
  不赌"次日最后一跌"，赌的是买入后未来 2-3 日反弹回本、有浮盈卖点。

固化到数据库：
  star_form_events 表（market_data.db）：18 年每次「星→四形态」事件明细
  （星前连跌天数、形态日在星后第几天、形态日涨跌%、相对 MA20%、量比），
  每次运行按指数幂等重建（_market_db.replace_star_form_events）。

统计口径（选股层，逐股打分）：
  - 买入 = 形态日收盘（对阴线=低吸，对阳线=确认买入），全池逐股评分。
  - 热门 = 事件日前 60 日涨幅前 30% 分位。
  - 下跌多 = 个股形态日涨跌 ≤ -2.5% / -3.5%（仅阴线分组）。
  - 阳线分组：热门∩当日也收阳。
  - 收盘持有：信号日收盘 → 第 H 日收盘。
  - 盘中逃顶速查：买入后 H 日内 最高价≥成本 的占比 + 平均最高点% （核心短线口径）。
"""
import datetime
import json
import os
import random
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _market_db as mdb  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "AutoQuant", "backtest_logs", "_dip_crossstar_report.md")
IDX = [("sh000001", "上证指数"), ("sh000688", "科创50")]
START = "2008-01-01"
HORIZONS = [1, 2, 3, 5, 10]
FORMS = [
    ("big_yin", "大阴线(≤-1.5%)", "低吸"),
    ("small_yin", "小阴线(-1.5~0)", "低吸"),
    ("big_yang", "大阳线(≥+1.5%)", "确认追买"),
    ("small_yang", "小阳线(0~+1.5%)", "确认追买"),
]
DOJI_THR = 0.20
BIG_THR = 1.5          # 大/小 K 分界
WINDOW = 7             # 星后窗口
MAX_EV = 400           # 个股打分单形态最大事件日（超出随机抽样）


def load_index(conn, code):
    """从市场库读指数日线，转内部结构（changePct 缺失时按前收重算）。"""
    rows = mdb.query_kline(conn, code, start="2006-12-01")
    out = []
    prev_c = None
    for r in rows:
        s = {
            "date": r["date"], "open": r["open"], "high": r["high"],
            "low": r["low"], "close": r["close"], "volume": r["volume"] or 0.0,
            "turnover": r["turnover"] or 0.0,
            "changePct": r["change_pct"],
        }
        if s["changePct"] is None and prev_c:
            s["changePct"] = (s["close"] / prev_c - 1) * 100
        out.append(s)
        if s["close"]:
            prev_c = s["close"]
    return out


def load_stocks(conn):
    """读全池个股序列（2008 起足够长的），返回 {code: [snap,...]}。"""
    secs = [r["secid"] for r in conn.execute(
        "SELECT DISTINCT secid FROM kline WHERE secid NOT IN ('sh000001','sh000688','sz399001','sz399006')"
    ).fetchall()]
    out = {}
    for code in secs:
        rows = mdb.query_kline(conn, code, start="2006-12-01")
        if len(rows) < 220:
            continue
        seq = []
        prev_c = None
        for r in rows:
            s = {"date": r["date"], "open": r["open"], "high": r["high"],
                 "low": r["low"], "close": r["close"], "volume": r["volume"] or 0.0,
                 "changePct": r["change_pct"]}
            if s["changePct"] is None and prev_c:
                s["changePct"] = (s["close"] / prev_c - 1) * 100
            seq.append(s)
            if s["close"]:
                prev_c = s["close"]
        out[code] = seq
    return out


def is_doji(s, thr=DOJI_THR):
    hi, lo = s.get("high"), s.get("low")
    if not hi or not lo or hi <= lo:
        return False
    return abs(s.get("close", 0) - s.get("open", 0)) <= thr * (hi - lo)


def ma_vol(seq, i, n=5):
    if i < n:
        return None
    vals = [seq[j].get("volume") for j in range(i - n, i) if seq[j].get("volume")]
    return sum(vals) / len(vals) if len(vals) == n else None


def down_days(seq, i, cap=15):
    n = 0
    for j in range(i, max(i - cap, -1), -1):
        c = seq[j].get("changePct")
        if c is None or c >= 0:
            break
        n += 1
    return n


def classify(chg):
    """K 线涨跌幅 → 四形态之一（None=平盘/无）。"""
    if chg is None:
        return None
    if chg <= -BIG_THR:
        return "big_yin"
    if chg < 0:
        return "small_yin"
    if chg >= BIG_THR:
        return "big_yang"
    if chg > 0:
        return "small_yang"
    return None


def collect_events(seq):
    """提取指数序列全部「星→四形态」事件（2008-01-01 起）。

    对每个十字星（星前不限连跌，记录 streak），在星后 1..7 日内按四种形态
    分别取该形态**最先出现**的交易日；同一星可同时命中多个形态（不同 ev_date）。
    返回 (events, year_cnt)：events 为 dict(form->list)，year_cnt 为 {year: 十字星数}。
    """
    events = {f: [] for f, *_ in FORMS}
    year_doji = {}
    n = len(seq)
    for i in range(20, n - 11):
        if seq[i]["date"] < START:
            continue
        if not is_doji(seq[i]):
            continue
        sb = down_days(seq, i - 1)
        year_doji[seq[i]["date"][:4]] = year_doji.get(seq[i]["date"][:4], 0) + 1
        first = {}
        for k in range(i + 1, min(i + 1 + WINDOW, n - 10)):
            f = classify(seq[k].get("changePct"))
            if f and f not in first:
                first[f] = k
        for f, k in first.items():
            s = seq[k]
            vm = ma_vol(seq, k)
            m20 = sum(x["close"] for x in seq[k - 20:k]) / 20
            events[f].append({
                "star_date": seq[i]["date"], "star_streak": sb, "form": f,
                "ev_date": s["date"], "ev_gap": k - i, "ev_chg": s.get("changePct"),
                "dev_ma20": (s["close"] / m20 - 1) * 100,
                "vol_ratio": (s.get("volume") / vm) if vm else None,
            })
    return events, year_doji


def fmt(rets):
    if not rets:
        return "-"
    w = 100.0 * sum(1 for x in rets if x > 0) / len(rets)
    avg = sum(rets) / len(rets)
    gains = [x for x in rets if x > 0]
    loss = [x for x in rets if x <= 0]
    pl = "-"
    if gains and loss:
        pl = "%.2f" % (sum(gains) / len(gains) / (-sum(loss) / len(loss)))
    return "%d %.0f%% %+.2f%% %s" % (len(rets), w, avg, pl)


def esc_cell(arr):
    """盘中逃顶速查格：H日内最高价≥成本占比 / 平均最高点%"""
    if not arr:
        return "-"
    p = 100.0 * sum(1 for x in arr if x >= 0) / len(arr)
    return "%d %.0f%% %+.2f%%" % (len(arr), p, sum(arr) / len(arr))


def idx_detail_line(seq, k):
    """指数形态日后续收益明细：D1/D2/D3/D5/D10 收盘% + H2 盘中最高%(自形态日收盘)。"""
    b = seq[k]["close"]
    rets = []
    for h in (1, 2, 3, 5, 10):
        rets.append("%+.1f%%" % ((seq[k + h]["close"] / b - 1) * 100))
    hi2 = max(x["high"] for x in seq[k:k + 3])
    return " | ".join(rets), (hi2 / b - 1) * 100


def stock_panel(events, stocks, out, fname, act):
    """对某形态全部事件日做选股层统计：收盘持有(对照) + 盘中逃顶速查(核心)。"""
    dates = sorted({e["ev_date"] for e in events})
    if not dates:
        return
    seed_dates = dates if len(dates) <= MAX_EV else random.sample(dates, MAX_EV)
    yin = act == "低吸"
    if yin:
        groups = ("所有个股", "热门前30%", "热门∩当日跌≤-2.5%", "热门∩当日跌≤-3.5%")
    else:
        groups = ("所有个股", "热门前30%", "热门∩当日也收阳")
    # date -> pos 索引
    di = {c: {s["date"]: j for j, s in enumerate(seq)} for c, seq in stocks.items()}
    ret_h = {g: {h: [] for h in HORIZONS} for g in groups}
    hi_h = {g: {h: [] for h in HORIZONS} for g in groups}
    sim_h = {g: {h: [] for h in (2, 3)} for g in groups}  # 止盈纪律模拟
    n_day = 0
    for d in seed_dates:
        info = []
        for c, sd in stocks.items():
            pos = di[c].get(d)
            if pos is None or pos < 65 or pos + 10 >= len(sd):
                continue
            info.append((c, sd, pos))
        if len(info) < 5:
            continue
        r60_all = sorted((sd[pos]["close"] / sd[pos - 60]["close"] - 1) * 100
                         for _, sd, pos in info)
        hot_th = r60_all[max(0, int(len(r60_all) * 0.7) - 1)]
        n_day += 1
        for c, sd, pos in info:
            day_chg = sd[pos].get("changePct") or 0.0
            r60 = (sd[pos]["close"] / sd[pos - 60]["close"] - 1) * 100
            base = sd[pos]["close"]
            grp = {"所有个股"}
            if r60 >= hot_th:
                grp.add("热门前30%")
                if yin:
                    if day_chg <= -2.5:
                        grp.add("热门∩当日跌≤-2.5%")
                    if day_chg <= -3.5:
                        grp.add("热门∩当日跌≤-3.5%")
                elif day_chg > 0:
                    grp.add("热门∩当日也收阳")
            mh = -1e18
            hi_by_h = {}
            for h in HORIZONS:
                if pos + h < len(sd):
                    mh = max(mh, (sd[pos + h]["high"] / base - 1) * 100)
                    hi_by_h[h] = mh
            for g in grp:
                for h in HORIZONS:
                    if pos + h < len(sd):
                        ret_h[g][h].append((sd[pos + h]["close"] / base - 1) * 100)
                        hi_h[g][h].append(hi_by_h[h])
                for h in (2, 3):
                    if pos + h < len(sd):
                        # 冲高≥+2% 即卖；否则第 H 日收盘离场
                        sim = 2.0 if hi_by_h.get(h, -1e18) >= 2.0 \
                            else (sd[pos + h]["close"] / base - 1) * 100
                        sim_h[g][h].append(sim)
    out("#### ① 选股统计：%s（形态日收盘%s 全池逐股，事件日=%d，H=第H日收盘/盘中）"
        % (fname, act, n_day))
    out("**收盘持有（对照口径）**：样本 胜率% 均值% 盈亏比")
    out("| 分组 | H=1 | H=2 | H=3 | H=5 | H=10 |")
    out("|---|--:|--:|--:|--:|--:|")
    for g in groups:
        out("| " + g + " | " + " | ".join(fmt(ret_h[g][h]) for h in HORIZONS) + " |")
    out("")
    out("**盘中逃顶速查（短线核心口径）**：H日内最高价≥成本 占比，样本 均最高点%")
    out("| 分组 | H=1 | H=2 | H=3 | H=5 | H=10 |")
    out("|---|--:|--:|--:|--:|--:|")
    for g in groups:
        out("| " + g + " | " + " | ".join(esc_cell(hi_h[g][h]) for h in HORIZONS) + " |")
    out("")
    out("**止盈纪律模拟**：买入后 H 日内盘中冲高≥+2% 即卖（记+2%），否则第 H 日收盘离场；"
        "格=成功率(收益>0) 均收益%")
    out("| 分组 | H=2 | H=3 |")
    out("|---|--:|--:|")
    for g in groups:
        def _s(arr):
            if not arr:
                return "-"
            return "%d %.0f%% %+.2f%%" % (len(arr),
                                          100.0 * sum(1 for x in arr if x > 0) / len(arr),
                                          sum(arr) / len(arr))
        out("| " + g + " | " + " | ".join(_s(sim_h[g][h]) for h in (2, 3)) + " |")
    out("")
    return n_day


def main():
    conn = mdb.get_conn()
    out = []
    O = lambda *a: out.append(" ".join(str(x) for x in a))  # noqa: E731
    O("# 十字星分歧信号 四形态短线回溯（2008-01 ~ %s）"
      % datetime.datetime.now().strftime("%Y-%m-%d"))
    O("")
    O("> 大盘回调中出现十字星(分歧)后，星后 1..7 日内最先走出的形态分四类："
      "大阴线≤-1.5% / 小阴线(-1.5~0) / 大阳线≥+1.5% / 小阳线(0~+1.5%)。")
    O("> 做短线不赌\"次日最后一跌\"：买点=形态日收盘(阴线=低吸、阳线=确认追买)，"
      "核心看未来 2-3 日是否反弹回本/有浮盈卖点（盘中逃顶速查）；收盘持有表仅作对照。")
    O("> 数据：market_data.db kline（2008-01-02 起，hfq/qfq 拼接）；"
      "事件已固化 star_form_events 表（每次运行按指数幂等重建）。")
    O("")

    random.seed(7)
    stocks = load_stocks(conn)
    di_pool = {c: {s["date"]: j for j, s in enumerate(seq)} for c, seq in stocks.items()}
    for code, cname in IDX:
        seq = load_index(conn, code)
        seq = [s for s in seq if s["date"] >= START]
        n = len(seq)
        if n < 300:
            continue
        events, year_doji = collect_events(seq)
        rows = [e for lst in events.values() for e in lst]
        mdb.replace_star_form_events(conn, code, rows)
        span = "%s→%s" % (seq[0]["date"], seq[-1]["date"])
        O("## %s %s（%d 交易日 %s）" % (cname, code, n, span))
        O("")
        # ── 事件库总览：按年分布
        O("### 事件库固化 star_form_events（%d 条：每星段可命中多形态）" % len(rows))
        O("| 十字星年份 | 星数 | 大阴线 | 小阴线 | 大阳线 | 小阳线 |")
        O("|---|--:|--:|--:|--:|--:|")
        years = sorted(year_doji)
        for y in years:
            cnt = {f: 0 for f, *_ in FORMS}
            for e in rows:
                if e["ev_date"][:4] == y:
                    cnt[e["form"]] += 1
            O("| %s | %d | %d | %d | %d | %d |"
              % (y, year_doji[y], cnt["big_yin"], cnt["small_yin"],
                 cnt["big_yang"], cnt["small_yang"]))
        O("")
        # ── 四形态逐事件明细 + 选股统计
        for f, fname, act in FORMS:
            evs = sorted(events[f], key=lambda e: e["ev_date"])
            if not evs:
                continue
            O("### ① 逐事件明细：十字星→%s（星后≤7日最先出现；共 %d 个事件）" % (fname, len(evs)))
            O("| 形态日 | 星前连跌 | 星后第几天 | 涨跌% | vsMA20% | 量比 | 指数H2盘中最高% | D1 | D2 | D3 | D5 | D10 |")
            O("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|")
            for e in evs:
                k = next(j for j, s in enumerate(seq) if s["date"] == e["ev_date"])
                det, hi2 = idx_detail_line(seq, k)
                vr = ("%.2f" % e["vol_ratio"]) if e["vol_ratio"] else "-"
                O("| %s | %d | %d | %+.1f | %+.1f | %s | %+.1f | %s |"
                  % (e["ev_date"], e["star_streak"], e["ev_gap"], e["ev_chg"],
                     e["dev_ma20"], vr, hi2, det))
            O("")
            O("### ② 短线成功率：星→%s（%s）" % (fname, act))
            stk_evs = [{**e} for e in evs]
            stock_panel(stk_evs, stocks, O, fname, act)
        O("---")
        O("")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    # 控制台只打印摘要
    print("已写出: %s (%d 行)" % (OUT, len(out)))
    print("库: %s" % mdb.kline_stats(conn))
    for code, cname in IDX:
        for f, fname, _ in FORMS:
            cnt = len(mdb.query_star_form_events(conn, idx_code=code, form=f))
            print("  %s %s: %d 事件" % (cname, fname, cnt))
    conn.close()


if __name__ == "__main__":
    main()
