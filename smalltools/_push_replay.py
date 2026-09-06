# -*- coding: utf-8 -*-
"""盘外选股逐日回放 + ETF持仓前五低吸 回溯拟合（2026-09-06）。

回放（默认 dry，逐日重建"当日选股+推送"）：
  python _push_replay.py 2026-09-01 2026-09-02 2026-09-03 2026-09-04
  python _push_replay.py 2026-09-01 --days 4
  python _push_replay.py 2026-09-01 --days 4 --push      # 真实推送（企微：大盘图 + 三段消息）
  python _push_replay.py 2026-09-01 --days 4 --skip-chart

回溯拟合：
  python _push_replay.py --fit --start 2026-08-03 --end 2026-09-04
    # 逐日低吸信号 → 次日开盘买 / 网格持有 → 正确率/累计收益/盈亏比/回撤

口径说明：
  - 回放以「当日收盘定稿K线」把 _kline_cache.json 截断到 asof（等于当日 dag 文件口径），
    引擎、技术指标、ETF低吸均为该时点确定值，可复现。
  - 板块/ETF 资金流为盘中实时采集、无历史存档，页面2 以全行业ETF前五低吸(收盘口径)替代，
    并附说明行。
  - 数据依赖：data/market_data.db（补top5历史，首次自动拉）、data/_etf_top5_hist.json、
    AutoQuant/data/dag_screen_YYYYMMDD.json、data/_etf_holdings.json。
"""
import argparse
import datetime as dt
import json
import os
import sqlite3
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import _etf_holdings as EH          # noqa: E402
import _index_market_chart as IMC   # noqa: E402
import _publish_candidates as PC    # noqa: E402
import push_channel                 # noqa: E402

REC_DIR = os.path.join(HERE, "_records", "replay")
MARKET_DB = os.path.join(ROOT, "data", "market_data.db")
INDEX_NAMES = {"sh000001": "上证指数", "sz399001": "深证成指",
               "sh000688": "科创50", "sz399006": "创业板指"}
CHART_DAYS = 55


def trading_days(start=None, end=None):
    """从 market_data.db 上证指数取交易日（升序）。"""
    sql = "SELECT DISTINCT date FROM kline WHERE secid='sh000001'"
    conds, args = [], []
    if start:
        conds.append("date>=?")
        args.append(start)
    if end:
        conds.append("date<=?")
        args.append(end)
    if conds:
        sql += " AND " + " AND ".join(conds)
    sql += " ORDER BY date"
    con = sqlite3.connect(MARKET_DB)
    try:
        days = [r[0] for r in con.execute(sql, args).fetchall()]
    finally:
        con.close()
    return days


def truncate_cache(cache, asof):
    """把全量缓存逐标地截断到 asof(含当日K线)。"""
    out = {}
    for sid, ent in cache.items():
        snaps = ent.get("snaps") or []
        if not snaps or snaps[0]["date"] > asof:
            continue
        if snaps[-1]["date"] <= asof:
            out[sid] = {"name": ent.get("name", "") or sid, "snaps": snaps}
            continue
        out[sid] = {"name": ent.get("name", "") or sid,
                    "snaps": [s for s in snaps if s["date"] <= asof]}
    return out


def dag_for(day):
    """当日 dag 文件（asof 匹配兜底 latest）。"""
    path = os.path.join(ROOT, "AutoQuant", "data",
                        "dag_screen_%s.json" % day.replace("-", ""))
    if os.path.exists(path):
        try:
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return None
    path2 = os.path.join(ROOT, "AutoQuant", "data", "dag_screen_latest.json")
    try:
        with open(path2, encoding="utf-8") as f:
            d = json.load(f)
        return d if (d.get("asof") or "") == day else None
    except (OSError, ValueError):
        return None


def render_chart(cacheD, asof, chart_days=CHART_DAYS):
    """四大指数归一化叠加图（离线切片）。返回 (ok, png)。"""
    idx = {}
    for code, nm in INDEX_NAMES.items():
        ent = cacheD.get(code)
        if ent and ent.get("snaps"):
            idx[code] = {"name": nm, "snaps": ent["snaps"]}
    if len(idx) < 2:
        return False, ""
    png = os.path.join(ROOT, "data", "_index_market_replay_%s.png"
                       % asof.replace("-", ""))
    return IMC.render_from_snaps(idx, out=png, days=chart_days), png


def replay_one(day, cache, hist, args):
    dag = dag_for(day)
    if not dag:
        print("  ✗ 跳过 %s：无当日 DAG 文件" % day)
        return None
    asof = dag.get("asof") or day
    t0 = time.time()
    print("── %s 回放开始 (asof=%s) ──" % (day, asof))
    cacheD = truncate_cache(cache, asof)
    industry = PC.build_industry()
    data = PC.build_candidates(cacheD, industry, ctx=None)
    if not data:
        print("  ✗ %s 引擎无有效数据" % day)
        return None
    # 页2 离线低吸（全行业ETF前五，收盘口径；SAR红+企稳/共振精选≤5只，
    # 当日主线DAG命中票附『主线DAG✓』）
    dag_codes = set()
    if dag.get("result"):
        for period in ("超短", "短线", "中线", "长线"):
            for it in (dag.get("result") or {}).get(period) or []:
                raw = (it.get("code") or "").strip()
                c6 = raw[2:] if raw[:2].lower() in ("sh", "sz", "bj") else raw
                if c6:
                    dag_codes.add(c6)
    low_lines = (EH.low_buy_lines_offline(asof, hist=hist, dag_codes=dag_codes)
                 if hist else [])
    note = ("💡 回放说明：板块/ETF资金流为盘中实时采集、无历史存档；"
            "本页以 %s 收盘K线口径展示全行业ETF前五低吸。" % asof)
    pages = PC.round_pages(data, None, None, scene="盘外选股", dag=dag,
                           cache=cacheD, lowbuy_offline=low_lines,
                           note_offline=note)
    # 大盘K图（开盘前推送）
    ok_img, png = (False, "")
    if not args.skip_chart:
        ok_img, png = render_chart(cacheD, asof, args.chart_days)
    print("  大盘图 %s (%s) | 引擎 %s | 池 %d | %.0fs" % (
        "ok" if ok_img else "跳过", os.path.basename(png) if png else "-",
        data.get("market_state"), data.get("pool_total", 0), time.time() - t0))
    rec = {"date": day, "asof": asof, "market_state": data.get("market_state"),
           "pool_total": data.get("pool_total", 0),
           "dag": dag.get("result") or {},
           "engine_groups": {k: len(v) for k, v in (data.get("groups") or {}).items()},
           "lowbuy_funds": len(EH.screen_picks_asof(asof, hist=hist)),
           "png": png,
           "pages": [{"title": t, "content": c} for t, c in pages]}
    # 落盘记录 + 控制台预览
    os.makedirs(REC_DIR, exist_ok=True)
    rec_path = os.path.join(REC_DIR, "%s.json" % asof.replace("-", ""))
    with open(rec_path, "w", encoding="utf-8") as f:
        json.dump(rec, f, ensure_ascii=False, indent=1)
    for title, content in pages:
        print("\n" + "=" * 30 + "\n%s\n%s" % (title, content))
    if ok_img:
        print("\n[图] %s" % png)
    # 推送
    if args.push:
        cfg = PC.load_notify_cfg()
        if ok_img:
            push_channel.send_image(png, cfg)
        PC.send_wechat_round(data, None, cfg, scene="盘外选股", dag=dag,
                             cache=cacheD, lowbuy_offline=low_lines,
                             note_offline=note)
        print("  ✓ %s 已推送" % day)
    return rec


def replay_days(days, args):
    print("加载行情缓存(_kline_cache.json) ...")
    cache = PC.load_cache()
    print("  标的 %d 只" % len(cache))
    hist = EH.load_top5_hist().get("codes") or {}
    if len(hist) < 30:
        print("构建ETF前五历史K线(首次较慢, db+腾讯补缺)...")
        hist = EH.build_top5_hist()
    recs = []
    for day in days:
        r = replay_one(day, cache, hist, args)
        if r:
            recs.append(r)
    print("\n完成: %d 天回放 → %s" % (len(recs), REC_DIR))
    return recs


# ══════════════════════════════════════════
# 回溯拟合：逐日ETF前五低吸信号 → T+1开盘买，网格持有/止盈止损
# ══════════════════════════════════════════

def _entry_exit(hist, code, day, hold, tp_pct, sl_pct):
    """给定信号日 day(收盘已知)：下一交易日开盘买入；持有 hold 日，
    逐日收盘触碰 +tp% 止盈 / -sl% 止损即出，到 hold 日收尾。
    返回 ret% 或 None(后续无足够K线)。"""
    ent = hist.get(code)
    if not ent:
        return None
    snaps = ent.get("snaps") or []
    i = next((k for k, s in enumerate(snaps) if s["date"] == day), -1)
    if i < 0 or i + 1 >= len(snaps):
        return None
    buy = float(snaps[i + 1].get("open") or 0)
    if buy <= 0:
        return None
    seg = snaps[i + 1: i + 2 + hold]
    if not seg:
        return None
    for j, s in enumerate(seg):
        c = float(s.get("close") or 0)
        if c <= 0:
            continue
        ret = (c / buy - 1) * 100
        if sl_pct and ret <= -abs(sl_pct):
            return ret
        if tp_pct and ret >= tp_pct:
            return ret
        if j == len(seg) - 1:
            return ret
    return None


def _stats(trades):
    if not trades:
        return {"n": 0}
    rets = [t["ret"] for t in trades]
    wins = [r for r in rets if r > 0]
    losses = [r for r in rets if r < 0]
    gross_win = sum(wins)
    gross_loss = -sum(losses)
    eq, peak, dd = 0.0, 0.0, 0.0
    for r in sorted(trades, key=lambda t: t["exit"]):
        eq += r["ret"]
        peak = max(peak, eq)
        dd = min(dd, eq - peak)
    return {
        "n": len(rets), "win_rate": len(wins) / len(rets) * 100,
        "avg": sum(rets) / len(rets),
        "sum": sum(rets), "pf": (gross_win / gross_loss if gross_loss > 0 else None),
        "max_dd": dd, "best": max(rets), "worst": min(rets),
    }


THRESH_T = (-1, -3, -5, -8, -12)   # 距60日高 ≤ 阈值档位
FIT_H = (3, 5, 8, 10)              # 持有日
FIT_STOPS = ((0, 0), (5, -3), (8, -5))


def _cfg_name(t, h, tp, sl):
    return "阈值≤%d%% H%d%s%s" % (
        t, h,
        (" TP+%d%%" % tp) if tp else "",
        (" SL-%d%%" % abs(sl)) if sl else "")


FUND_POS_TAGS = ("新进", "加仓", "增持")
HOLDER_HIST_FILE = os.path.normpath(
    os.path.join(HERE, "..", "data", "_holder_hist.json"))
_fund_holder = {"hh": None}


def _load_holder_hist():
    """股东披露日历（_holder_signals.build_history 产出）：社保/北向每季记录带 NOTICE_DATE。"""
    if _fund_holder["hh"] is None:
        try:
            with open(HOLDER_HIST_FILE, encoding="utf-8") as f:
                _fund_holder["hh"] = json.load(f) or {}
        except Exception:  # noqa: BLE001
            _fund_holder["hh"] = {}
    return _fund_holder["hh"]


def fund_state_at(hh, code, day):
    """截至信号收盘日 day 的最新已披露股东状态（披露日≤day，无未来函数）。
    ss: 社保最新披露季存在 新进/加仓/增持 动作 → True；有记录但无加码 → False；从未进前十大 → None。
    nb: 香港中央结算最新披露季占比较上披露季升 ≥0.35pp → True；无升/仅一季 → False；从未进前十大 → None。"""
    ss = (hh.get("ss") or {}).get(code) or []
    ssr = [r for r in ss if r.get("notice") and r["notice"] <= day]
    ssv = None
    if ssr:
        q = max(r["end"] for r in ssr)
        rs = [r for r in ssr if r["end"] == q]
        ssv = any(r.get("state") in FUND_POS_TAGS for r in rs)
    nb = (hh.get("nb") or {}).get(code) or []
    nbr = sorted([r for r in nb if r.get("notice") and r["notice"] <= day],
                 key=lambda r: r["end"])
    nbv = None
    if nbr:
        if len(nbr) >= 2 and nbr[-1]["end"] > nbr[-2]["end"]:
            nbv = nbr[-1]["ratio"] - nbr[-2]["ratio"] >= 0.35
        else:
            nbv = False
    return {"ss": ssv, "nb": nbv}


def _variant_ok(pk, variant, fs=None):
    """豆包/DeepSeek/股东共振 可回溯因子的叠加过滤（在 base 低吸候选之上）。
    ds     = DeepSeek: 52周位置分位≤0.35 且 RSI14≤40（低位+不接飞刀）
    ds_ss  = ds ∩ 社保最新披露季加码（新进/加仓/增持）
    ds_nb  = ds ∩ 北向(中央结算)季度增持 ≥0.35pp
    ds_fund= ds ∩ (社保加码 或 北向增持)；无数据=未确认，不构成信号
    db     = 豆包共振: MACD红柱/金叉 + OBV升 + 收阳温和放量(1.05~2.5) + RSI≤62 + 52周≤0.5
    all    = ds∩db（共振精选：只留两者都满足的极少数）
    注：pos52 需要≥250根历史，2008 早期新上市样本不足会自动排除。"""
    if variant == "base":
        return True
    p52 = pk.get("pos52")
    rsi = pk.get("rsi")
    ok_ds = bool(p52 is not None and p52 <= 0.35 and isinstance(rsi, (int, float))
                 and rsi <= 40)
    if variant == "ds":
        return ok_ds
    if variant in ("ds_ss", "ds_nb", "ds_fund"):
        if not ok_ds or not isinstance(fs, dict):
            return False
        if variant == "ds_ss":
            return fs.get("ss") is True
        if variant == "ds_nb":
            return fs.get("nb") is True
        return fs.get("ss") is True or fs.get("nb") is True
    if variant == "db":
        macd = bool(pk.get("macd_bull") or pk.get("macd_golden"))
        return bool(macd and pk.get("obv_up") is True and pk.get("vr_ok")
                    and pk.get("up_day")
                    and isinstance(rsi, (int, float)) and rsi <= 62
                    and p52 is not None and p52 <= 0.5)
    # all
    macd = bool(pk.get("macd_bull") or pk.get("macd_golden"))
    return bool(ok_ds and macd and pk.get("obv_up") is True and pk.get("vr_ok")
                and pk.get("up_day"))


def run_fit(args):
    variant = getattr(args, "variant", "base") or "base"
    days = trading_days(args.start, args.end)
    if not days:
        print("窗口内无交易日")
        return 2
    step = max(1, int(getattr(args, "step", 1) or 1))
    sel = days[::step]
    vname = {"base": "基线", "ds": "DeepSeek低位(52周≤35%+RSI14≤40)",
             "ds_ss": "ds∩社保新披露季加码",
             "ds_nb": "ds∩北向季度增持",
             "ds_fund": "ds∩(社保加码或北向增持)",
             "db": "豆包共振(MACD红+OBV升+温和放量+RSI≤62)",
             "all": "ds∩db 共振精选"}.get(variant, variant)
    print("拟合窗口 %s ~ %s，共 %d 个交易日（采样步长%d → %d 天）| 变体: %s"
          % (days[0], days[-1], len(days), step, len(sel), vname))
    hist = EH.load_top5_hist().get("codes") or {}
    if len(hist) < 30:
        hist = EH.build_top5_hist()
    print("top5 历史覆盖 %d 只股票" % len(hist))
    fund_variant = variant in ("ds_ss", "ds_nb", "ds_fund")
    hh = _load_holder_hist() if fund_variant else {}
    if fund_variant:
        print("股东披露日历: 社保%d只/北向%d只（按 NOTICE_DATE≤信号日 判定，无未来函数）"
              % (len(hh.get("ss") or {}), len(hh.get("nb") or {})))
    # 大盘动量分组（上证 20 日涨跌）＋窗口期指数基准（指数 secid 需直读 db）
    con = sqlite3.connect(MARKET_DB)
    try:
        idx_rows = con.execute(
            "SELECT date, close FROM kline WHERE secid='sh000001' "
            "ORDER BY date").fetchall()
    finally:
        con.close()
    closes_at = {d: float(c) for d, c in idx_rows}
    idx_days = sorted(closes_at)

    def idx_n(day, n=20):
        p = next((k for k, d in enumerate(idx_days) if d == day), -1)
        if p < n:
            return None
        c0 = closes_at[idx_days[p - n]]
        c1 = closes_at[idx_days[p]]
        return (c1 / c0 - 1) * 100 if c0 else None

    idx_ret = None
    if sel and sel[-1] in closes_at and sel[0] in closes_at:
        idx_ret = (closes_at[sel[-1]] / closes_at[sel[0]] - 1) * 100

    regime = {}
    for day in sel:
        m = idx_n(day)
        regime[day] = "多" if (m or 0) > 1.5 else ("空" if (m or 0) < -1.5 else "震荡")
    # 逐日唯一信号：同一股票多只ETF重复 → 每日每票只记一次（防重复膨胀）
    keys = [(t, h, tp, sl) for t in THRESH_T for h in FIT_H for tp, sl in FIT_STOPS]
    trades = {k: [] for k in keys}
    cf_sigs = {}
    n_sig = 0
    fstat = ({"ds_total": 0, "confirmed": 0, "ss_ok": 0, "nb_ok": 0, "no_data": 0}
             if fund_variant else None)
    for day in sel:
        st = EH.screen_picks_asof(day, hist=hist)
        seen = set()
        uniq = []
        fday = {}
        for info in st.values():
            for pk in info["picks"]:
                if pk["code"] in seen:
                    continue
                seen.add(pk["code"])
                if fund_variant:
                    fs = fday.get(pk["code"])
                    if fs is None:
                        fs = fund_state_at(hh, pk["code"], day)
                        fday[pk["code"]] = fs
                    p52 = pk.get("pos52")
                    rsi = pk.get("rsi")
                    if (p52 is not None and p52 <= 0.35
                            and isinstance(rsi, (int, float)) and rsi <= 40):
                        fstat["ds_total"] += 1
                        if fs.get("ss") is None and fs.get("nb") is None:
                            fstat["no_data"] += 1
                        if fs.get("ss") is True:
                            fstat["ss_ok"] += 1
                        if fs.get("nb") is True:
                            fstat["nb_ok"] += 1
                    if not _variant_ok(pk, variant, fs):
                        continue
                    fstat["confirmed"] += 1
                elif not _variant_ok(pk, variant):
                    continue
                uniq.append(pk)
        if not uniq:
            continue
        n_sig += len(uniq)
        cf_sigs[day] = [pk for pk in uniq if pk.get("note") == "当日放量企稳✓"]
        for pk in uniq:
            for t, h, tp, sl in keys:
                if pk["pos60"] > -abs(t):
                    continue
                r = _entry_exit(hist, pk["code"], day, h, tp, sl)
                if r is None:
                    continue
                trades[(t, h, tp, sl)].append(
                    {"date": day, "code": pk["code"], "name": pk["name"],
                     "ret": r, "exit": day})
    # 确认信号(当日放量企稳✓)次日介入
    cf_trades = {h: [] for h in FIT_H}
    for day, pks in cf_sigs.items():
        for pk in pks:
            for h in FIT_H:
                r = _entry_exit(hist, pk["code"], day, h, 0, 0)
                if r is None:
                    continue
                cf_trades[h].append({"date": day, "code": pk["code"],
                                     "name": pk["name"], "ret": r, "exit": day})
    print("累计信号(去重) %d 条（采样日均 %.1f）" % (n_sig, n_sig / len(sel)))
    if fstat:
        print("共振覆盖: ds候选 %d → 社保加码%d / 北向增持%d / 双通道无数据%d / 最终信号%d"
              % (fstat["ds_total"], fstat["ss_ok"], fstat["nb_ok"],
                 fstat["no_data"], fstat["confirmed"]))
    if idx_ret is not None:
        print("同期上证指数窗口涨幅: %+.1f%%（等权逐票累加收益会放大幅度，注意相对指数看方向）\n"
              % idx_ret)
    report = {"start": days[0], "end": days[-1], "index_ret": idx_ret,
              "note": _FIT_NOTE, "signals": n_sig, "buckets": {}}
    stats_all = {k: _stats(trades[k]) for k in keys}
    for k, s in stats_all.items():
        report["buckets"]["%d-%d-%d-%d" % k] = s
    # 主表：无TP/SL，阈值 × 持有日
    print("【纯持有】胜率%/累计% 表：阈值(距60日高≤) × 持有N日")
    head = "%-10s" % ""
    for h in FIT_H:
        head += "%10s" % ("H%d" % h)
    print(head)
    for t in THRESH_T:
        line = "%-10s" % ("≤%d%%" % t)
        for h in FIT_H:
            s = stats_all[(t, h, 0, 0)]
            line += "%9.0f%%/%+7.1f" % (s.get("win_rate", 0), s.get("sum", 0)) \
                if s.get("n") else "%9s" % "-"
        print(line)
    # 对照：TP/SL 是否改善
    print("\n【止盈止损对照】(N/胜率/平均/累计/PF)：")
    head2 = "%-10s" % "≤-5% H5"
    for tp, sl in FIT_STOPS:
        head2 += "%-30s" % ("TP%d SL-%d" % (tp, abs(sl)) if sl else
                            ("TP%d%%" % tp if tp else "无"))
    print(head2)
    line = "%-10s" % ""
    for tp, sl in FIT_STOPS:
        s = stats_all[(-5, 5, tp, sl)]
        if s.get("n"):
            line += "  %4d %4.0f%% %+5.2f %+6.1f %4.2f   " % (
                s["n"], s["win_rate"], s["avg"], s["sum"],
                s["pf"] if s["pf"] is not None else 0)
        else:
            line += "  %28s" % "-"
    print(line)
    # 确认信号对比：仅在"当日放量企稳✓"的候选里次日介入
    print("\n【企稳确认后介入】(当日放量企稳✓ 才买)：")
    cf_line = "%-10s" % ""
    for h in FIT_H:
        s = _stats(cf_trades[h])
        if s.get("n"):
            cf_line += "%10s" % ("H%d N%d" % (h, s["n"]))
        else:
            cf_line += "%10s" % ("H%d -" % h)
    print(cf_line)
    cf_line = "%-10s" % "胜率/平均"
    for h in FIT_H:
        s = _stats(cf_trades[h])
        if s.get("n"):
            cf_line += "  %4.0f%% %+5.2f" % (s["win_rate"], s["avg"])
        else:
            cf_line += "  %10s" % "-"
    print(cf_line)
    cf_line = "%-10s" % "累计/PF"
    for h in FIT_H:
        s = _stats(cf_trades[h])
        if s.get("n"):
            cf_line += "  %+6.1f %4.2f" % (s["sum"], s["pf"] if s["pf"] is not None else 0)
        else:
            cf_line += "  %10s" % "-"
    print(cf_line)
    for h in FIT_H:
        report["buckets"]["cf-H%d" % h] = _stats(cf_trades[h])
    # 推荐与状态分解：n>=min_n 中 PF 最高（严格变体样本少，门槛放宽）
    min_n = 30 if variant == "base" else 15
    cand = [(k, s) for k, s in stats_all.items() if s.get("n", 0) >= min_n]
    if cand:
        bt_key, best = max(cand, key=lambda ks: (
            ks[1]["pf"] if ks[1]["pf"] is not None else 0, ks[1]["sum"]))
        bt, bh, btp, bsl = bt_key
        print("\n推荐: 阈值≤%d%% 持有%d日%s%s  →  正确率%.1f%% 平均%+.2f%% "
              "累计%+.2f%% PF %.2f 回撤%.1f" % (
                  bt, bh, " TP%d%%" % btp if btp else "",
                  " SL-%d%%" % bsl if bsl else "",
                  best["win_rate"], best["avg"], best["sum"],
                  best["pf"] if best["pf"] is not None else 0, best["max_dd"]))
        by_reg = {}
        for tr in trades[bt_key]:
            by_reg.setdefault(regime.get(tr["date"], "?"), []).append(tr)
        print("大盘状态分解:")
        for k in ("多", "震荡", "空"):
            s = _stats(by_reg.get(k, []))
            if s.get("n"):
                print("  %s: N=%d 正确率%.1f%% 平均%+.2f%% 累计%+.2f%%" % (
                    k, s["n"], s["win_rate"], s["avg"], s["sum"]))
        report["recommended"] = best
        report["recommended_cfg"] = "%d-%d-%d-%d" % bt_key
    os.makedirs(os.path.dirname(REC_DIR), exist_ok=True)
    fname = ("_etf_top5_fit_report.json" if variant == "base"
             else "_etf_top5_fit_report_%s.json" % variant)
    out = os.path.normpath(os.path.join(HERE, "..", "data", fname))
    with open(out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    print("\n报告 → %s" % out)
    return 0


_FIT_NOTE = ("口径：信号=前五重仓 距60日高≤-1% 且当日涨跌∈[-4%,4%](收盘K线); "
             "次日开盘买入; 到持仓末日收盘结算; 逐日收盘触 TP/SL 即出。"
             "样本即最新季报披露前五(2026Q2口径), 严格有效期为披露日(约2026-08-03)之后。"
             "会员持仓未按披露时点回溯滚动, 早期样本存在近似偏差。")


def main():
    ap = argparse.ArgumentParser(description="盘外选股逐日回放 + ETF前五低吸回溯拟合")
    ap.add_argument("dates", nargs="*", help="回放日期 2026-09-01 ...")
    ap.add_argument("--days", type=int, default=4,
                    help="从首个日期往前推N个交易日回放")
    ap.add_argument("--push", action="store_true", help="真实推送(企微图+三段消息)")
    ap.add_argument("--skip-chart", action="store_true")
    ap.add_argument("--chart-days", type=int, default=CHART_DAYS)
    ap.add_argument("--fit", action="store_true")
    ap.add_argument("--start", default="2026-08-03")
    ap.add_argument("--end", default="2026-09-04")
    ap.add_argument("--step", type=int, default=1,
                    help="拟合采样步长：每N个交易日取1天（长窗口用，如 --step 10）")
    ap.add_argument("--variant", default="base",
                    choices=["base", "ds", "ds_ss", "ds_nb", "ds_fund", "db", "all"],
                    help="变体: base基线 / ds=52周分位≤35%+RSI14≤40 / "
                         "ds_ss=ds∩社保披露季加码 / ds_nb=ds∩北向季度增持 / "
                         "ds_fund=ds∩(社保或北向) / db=豆包共振 / all=ds∩db")
    args = ap.parse_args()
    if args.fit:
        return run_fit(args)
    if not args.dates:
        print("缺少回放日期，如: python _push_replay.py 2026-09-01 --days 4")
        return 2
    start = args.dates[0]
    days = args.dates
    if len(days) == 1:
        all_days = trading_days(None, None)
        i = next((k for k, d in enumerate(all_days) if d == start), -1)
        if i < 0:
            print("日期 %s 不是交易日" % start)
            return 2
        days = all_days[max(0, i - args.days + 1):i + 1]
    print("回放交易日: %s" % " ".join(days))
    replay_days(days, args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
