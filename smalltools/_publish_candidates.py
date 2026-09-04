# -*- coding: utf-8 -*-
"""PC 候选清单发布：四周期选股(异动共振) → 双端命中对比 → 微信通知 → 上传 COS → 守护轮询。

v11 盘中共振版：每 15 分钟(交易时段)一轮，选股不再只看形态——先收集盘中市场上下文
(_market_context.py：实时板块资金流 / 日榜 / 连续上榜焦点 / Android 实仓 / exe 命中)，
对每只候选做「技术买点 + 资金/轮动/热度共振」综合排序；推送同时并列 CodeBuddy 命中
与 exe(AutoQuant screen_report) 命中，并附实仓持仓的持/加/减/止损建议。

用法：
  python _publish_candidates.py --once            # 跑一次（选股+对比+通知+上传）
  python _publish_candidates.py --once --dry      # 只选股+本地输出，不上传不通知
  python _publish_candidates.py --daemon          # 每 900 秒(15分钟)轮询整轮推送
  python _publish_candidates.py --daemon --interval 900 --no-ctx
                                                  # 关闭市场上下文，纯形态快速模式

数据流：
  _kline_cache.json(池) + _industry_map(行业) + _market_context(盘中异动/资金/热度/实仓/exe)
  → 候选清单 JSON(schema2, groups 含共振字段 reso/total)
  → 每轮推送微信(pushplus/serverchan)：CodeBuddy + exe 命中并列 + 实仓建议 + 板块资金流
  → PUT 到 COS(candidates_key)，APK 工作台「PC 候选」Tab 下载展示
"""
import argparse
import datetime
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cos_utils  # noqa: E402
from _full_cycle_backtest import INDEXES, load_cache, market_state  # noqa: E402
from backtest_guangmo import PARAMS, analyze_snaps  # noqa: E402
from _industry_map import build_industry  # noqa: E402
from _rotation_engine import rotate as rotation_rotate, load_cache as rotation_load_cache  # noqa: E402
from _rotation_engine import macro_div_pref, HIGH_DIVIDEND_SECTORS, oil_high, OIL_HIGH_SECTORS  # noqa: E402
from _market_context import (  # noqa: E402
    build_context, resonance_for, secid_of, flow_match, fetch_intraday_prices)
import push_channel  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def _resolve_app_config():
    """notify 推送配置（app_config.json）：源码=StockAnalysis 工程内；frozen exe 回退探测工程绝对路径。"""
    p = os.path.join(ROOT, "app", "src", "main", "assets", "data", "app_config.json")
    if os.path.exists(p) or not getattr(sys, "frozen", False):
        return p
    # exe 位于 AutoQuant/dist 下，工程根在其上三级；再按本机绝对路径兜底
    for cand in (
        os.path.join(os.path.dirname(sys.executable), "..", "..", "..",
                     "app", "src", "main", "assets", "data", "app_config.json"),
        r"E:\Android\work\dev\StockAnalysis\app\src\main\assets\data\app_config.json",
    ):
        cand = os.path.abspath(cand)
        if os.path.exists(cand):
            return cand
    return p


APP_CONFIG = _resolve_app_config()
LAST_FILE = os.path.join(HERE, "_last_publish.json")
# 守护模式每日推送去重档案: 记录当日已推送的候选 secid 与板块动量, 相同信息不重复发送
DIGEST_FILE = os.path.join(HERE, "_push_digest.json")
OUT_DEFAULT = os.path.join(ROOT, "AutoQuant", "data", "candidates_quant.json")

# APK 扫描结果（外部板块信号源）+ APK 上传的全量 K 线库（动态补池数据源）
APK_SCAN_FILE = os.path.join(HERE, "_last_scan.json")
MARKET_DB = os.path.join(ROOT, "data", "market_data.db")
APK_MIN_SEC_MOM = 10.0  # APK 外部板块动量阈值，低于此不并入候选

DEFAULT_CANDIDATES_KEY = "stockanalysis/quant/candidates.json"
# 2026-09-04：用户要求"先把条件放开看推送效果"。通过比例从 0.55 降到 0.45，
# 让更多达到基础的候选进组观察；后续效果好再逐步收紧。
RATIO_THRESHOLD = {"超短": 0.45, "短线": 0.45, "中线": 0.45, "长线": 0.45}
GROUP_SIZE = 8
PREPARED_SIZE = 15


# ── 1. 大盘状态与评分 ───────────────────────────────────────────────────
def market_state_trend(cache):
    all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
    if not all_dates:
        return None, "NO_DATA", all_dates
    asof = all_dates[-1]
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    st = market_state(idx_snaps, asof, all_dates, date_to_idx)
    trend = st if st in ("BULLISH", "BEARISH") else ("BEARISH" if st == "CRASH" else "OSCILLATION")
    return asof, trend, all_dates


def board_of(code):
    if code.startswith("688"):
        return "科创板"
    if code.startswith("300") or code.startswith("301"):
        return "创业板"
    return "主板"


def score_pool(cache, asof, trend):
    """每只池内股四周期命中率，返回 {secid: {period, ratio, pass, total}}。"""
    # v10: 大盘量能——上证指数当日量/前5日均量（<1 大盘缩量，个股量能阈值动态下调）
    market_vol_ratio = None
    try:
        idx_snaps = (cache.get("sh000001", {}) or {}).get("snaps") or []
        if len(idx_snaps) >= 6:
            vol5 = sum(s["volume"] for s in idx_snaps[-6:-1]) / 5.0
            if vol5 > 0:
                market_vol_ratio = idx_snaps[-1]["volume"] / vol5
    except Exception:
        market_vol_ratio = None
    score = {}
    for code, ent in cache.items():
        if code.startswith("sh000") or code.startswith("sz399"):
            continue
        snaps = ent.get("snaps") or []
        if not snaps or snaps[-1]["date"] != asof:
            continue
        sub = [dict(s) for s in snaps]
        sub[-1]["name"] = ent.get("name") or code
        best = None
        ambush = False
        for period, p in PARAMS.items():
            try:
                r = analyze_snaps(sub, p, trend, market_vol_ratio=market_vol_ratio)
            except Exception:
                continue
            ratio = r["passCount"] / max(r["totalChecks"], 1)
            # v9: 低位埋伏通过直接记录（中长线：均线粘合+三日不新低）
            if r.get("passed") and p.get("allowLowAmbush", False):
                ambush = True
            if best is None or ratio > best[1]:
                best = (period, ratio, r["passCount"], r["totalChecks"])
        if best:
            score[code] = {"period": best[0], "ratio": round(best[1], 2),
                           "pass": best[2], "total": best[3],
                           "ambush": ambush}
    return score


def hot_sectors(cache, industry, asof, top=8, window=20):
    """行业近 window 日平均涨幅（东财行业口径），供清单展示 + 板块代理过滤。"""
    agg = {}
    for code, ent in cache.items():
        if code.startswith("sh000") or code.startswith("sz399"):
            continue
        snaps = ent.get("snaps") or []
        if len(snaps) < window or snaps[-1]["date"] != asof:
            continue
        closes = [s["close"] for s in snaps[-window:]]
        if not closes or closes[0] <= 0:
            continue
        pct = (closes[-1] / closes[0] - 1) * 100
        ind = industry.get(code[2:], "其他")
        a = agg.setdefault(ind, {"sum": 0.0, "count": 0, "names": []})
        a["sum"] += pct
        a["count"] += 1
        if len(a["names"]) < 5:
            a["names"].append(ent.get("name") or code)
    rows = [{"industry": k, "avg_pct_20d": round(v["sum"] / v["count"], 1),
             "count": v["count"], "names": v["names"]}
            for k, v in agg.items() if v["count"] >= 2]
    rows.sort(key=lambda r: r["avg_pct_20d"], reverse=True)
    return rows[:top]


# ── 1.5 APK 外部板块信号：动态补池 + 轮动合并 ───────────────────────────
def load_apk_rotation():
    """读取 APK 扫描结果(_last_scan.json)的 rotation，作为外部板块信号源。

    APK 扫描覆盖全市场板块（PC 池只有 218 只核心股，可能漏掉池外强势板块，
    如农业种植）。返回 [{industry, sec_mom, catalyst, leaders:[{name,secid}], asof}]。
    """
    try:
        with open(APK_SCAN_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return [r for r in (data.get("rotation") or [])
                if isinstance(r, dict) and r.get("industry")]
    except (OSError, ValueError):
        return []


def enrich_cache_from_db(cache, apk_rotation):
    """动态补池：APK 外部板块龙头若不在 PC 池，从 market_data.db 读 K 线补入。

    这样股票池无需扩大，强势板块龙头也能参与评分与轮动聚合。
    """
    if not apk_rotation:
        return cache
    need = {}
    for r in apk_rotation:
        for l in (r.get("leaders") or []):
            sid = l.get("secid")
            if sid and sid not in cache:
                need[sid] = l.get("name") or sid
    if not need or not os.path.exists(MARKET_DB):
        return cache
    import sqlite3
    added = 0
    try:
        con = sqlite3.connect(MARKET_DB)
        cur = con.cursor()
        for sid, nm in need.items():
            rows = cur.execute(
                "SELECT date,open,high,low,close,volume,change_pct,turnover "
                "FROM kline WHERE secid=? ORDER BY date", (sid,)).fetchall()
            if len(rows) < 20:
                continue
            snaps = [{"date": d, "open": o, "high": h, "low": lo, "close": c,
                      "volume": v, "changePct": ch, "turnover": t}
                     for d, o, h, lo, c, v, ch, t in rows]
            cache[sid] = {"name": nm, "snaps": snaps}
            added += 1
        con.close()
    except Exception as e:
        print("APK 动态补池失败:", e)
    if added:
        print("APK 外部信号动态补池 +%d 只: %s" % (added, ", ".join(need)))
    return cache


def merge_apk_rotation(pc_rotation, apk_rotation, cache, min_mom=APK_MIN_SEC_MOM):
    """PC 轮动 + APK 外部板块信号合并（板块去重，PC 优先；池外板块补龙头数据）。"""
    out = list(pc_rotation or [])
    seen = {r.get("industry") for r in out if r.get("industry")}
    for r in apk_rotation or []:
        ind = r.get("industry")
        if not ind or ind in seen:
            continue
        sec_mom = r.get("sec_mom") or 0
        if sec_mom < min_mom:
            continue
        leaders = []
        for l in (r.get("leaders") or [])[:3]:
            sid = l.get("secid")
            nm = l.get("name") or sid
            board = board_of(sid[2:]) if sid else "主板"
            # 补池内个股动量（供排序/展示）
            mom20 = None
            ent = cache.get(sid, {}) if sid else {}
            snaps = ent.get("snaps") or []
            if len(snaps) >= 21 and snaps[-21]["close"] > 0:
                mom20 = round((snaps[-1]["close"] / snaps[-21]["close"] - 1) * 100, 1)
            leaders.append({"secid": sid, "name": nm, "board": board, "mom20": mom20})
        out.append({"industry": ind, "sec_mom": round(sec_mom, 1),
                    "catalyst": r.get("catalyst") or r.get("catalyst_reason") or "",
                    "catalyst_reason": r.get("catalyst") or r.get("catalyst_reason") or "",
                    "leaders": leaders, "asof": r.get("asof"),
                    "source": "apk_scan"})
        seen.add(ind)
    return out


# ── 1.8 共振评分：市场上下文融合到候选 ─────────────────────────────────
RESO_WEIGHT = 0.06  # 共振分最多折算成 ratio 加成 0.06（技术买点仍为主排序）


def decorate_with_ctx(data, ctx):
    """给候选清单叠加市场上下文共振分，并按综合分重排。

    参考网上主流「技术买点 + 资金/题材/轮动共振」思路：不推翻形态准入门槛
    (ratio/ambush)，只做小幅度提权排序，让同档次买点里共振更强的排前面。
    """
    if not ctx:
        return data
    groups = data.get("groups") or {}
    for pname, items in groups.items():
        if pname == "板块轮动":
            continue  # 板块轮动组保持板块动量排序，不重排
        for it in items:
            reso = resonance_for(it["secid"], it.get("industry") or "", ctx)
            it["reso"] = reso
            it["total"] = round(it["ratio"] + min(reso["score"] / 8.0, 1.0) * RESO_WEIGHT, 3)
        items.sort(key=lambda r: (r["total"], r["ratio"], r["pass"]), reverse=True)
        items[:] = items[:GROUP_SIZE]
    prep = data.get("prepared") or []
    for it in prep:
        reso = resonance_for(it["secid"], it.get("industry") or "", ctx)
        it["reso"] = reso
        it["total"] = round(it["ratio"] + min(reso["score"] / 8.0, 1.0) * RESO_WEIGHT, 3)
    prep.sort(key=lambda r: (r["total"], r["ratio"], r["pass"]), reverse=True)
    data["prepared"] = prep[:PREPARED_SIZE]
    return data


def summarize_context(ctx):
    """把 ctx 压成候选 JSON 的 market_context 展示字段。"""
    if not ctx:
        return {}
    flow_rank = ctx.get("flow_rank") or []
    hot_stale = bool(ctx.get("hot_stale"))
    hot_date = ((ctx.get("daily_hot") or {}).get("date")) or ""
    return {
        "ts": ctx.get("ts"),
        "trading": ctx.get("trading"),
        "hot_date": hot_date,
        "hot_stale": hot_stale,
        "flow_top": [{k: r.get(k) for k in ("name", "main_yi", "main_pct", "zdf_pct")}
                     for r in flow_rank[:5]],
        "flow_bottom": [{k: r.get(k) for k in ("name", "main_yi", "main_pct", "zdf_pct")}
                        for r in flow_rank[-5:]] if len(flow_rank) >= 10 else [],
        "week_focus_count": sum(1 for v in (ctx.get("week_focus") or {}).values() if v >= 2),
        "positions": len(ctx.get("positions") or []),
        "positions_asof": ctx.get("positions_asof"),
        "etf_top": [{"name": r.get("name"), "in_yi": r.get("in_yi"), "chg_pct": r.get("chg_pct")}
                    for r in (ctx.get("etf_flow") or [])[:5]],
    }


def _ma(snaps, n, idx):
    """snaps 前 idx(含) 近 n 日均线。"""
    if idx + 1 < n:
        return None
    seg = snaps[idx + 1 - n:idx + 1]
    vals = [s["close"] for s in seg if s.get("close")]
    return sum(vals) / len(vals) if vals else None


def assess_positions(ctx, cache, data):
    """评估 Android 实仓：每只持仓给持有/加仓/减仓/止损建议。

    规则（实仓风控为主，不依赖形态回测）：
      - 盈亏 ≤ -8% 或跌破 MA20 且现价低于成本 → 止损/减仓警戒
      - 本轮候选命中(超短/短/中/长/轮动/prepared) → 加仓候选/持有
      - 行业资金净流入>0 且盈亏>0 → 持有增强
      - 其他 → 持有观察
    返回 [{secid,name,code,qty,avg_buy_price,current_price,pnl_pct,period_type,
           sector,in_candidates,hit_periods,ma20,ma60,verdict,note}]
    """
    pos = ctx.get("positions") or []
    if not pos:
        return []
    asof = data.get("asof", "")
    hit = {}  # secid -> [period,...]
    for pname, items in (data.get("groups") or {}).items():
        for it in items:
            hit.setdefault(it["secid"], []).append(pname)
    for it in data.get("prepared") or []:
        hit.setdefault(it["secid"], []).append("预备队")
    out = []
    for p in pos:
        raw_code = str(p.get("stock_code") or "").strip()
        if not raw_code:
            continue
        code6 = secid_of(raw_code)[2:]
        sid = secid_of(raw_code)
        name = p.get("stock_name") or code6
        qty = float(p.get("quantity") or 0)
        cost = float(p.get("avg_buy_price") or 0)
        cur = float(p.get("current_price") or 0)
        pnl = round((cur / cost - 1) * 100, 1) if cost > 0 and cur > 0 else None
        # 尽量用池内最新收盘（比镜像 current_price 更新鲜）
        snaps = (cache.get(sid) or {}).get("snaps") or []
        if snaps and snaps[-1].get("date") == asof:
            cur = float(snaps[-1]["close"] or cur)
            pnl = round((cur / cost - 1) * 100, 1) if cost > 0 else None
        idx = len(snaps) - 1
        ma20 = _ma(snaps, 20, idx) if snaps else None
        ma60 = _ma(snaps, 60, idx) if snaps else None
        hit_periods = hit.get(sid) or []
        # 池外/镜像无价的持仓：盘中用实时行情补一次（每轮一次，TTL 缓存）
        if cur <= 0:
            try:
                rt = fetch_intraday_prices([sid]).get(sid) or {}
                if rt.get("price"):
                    cur = float(rt["price"])
                    pnl = round((cur / cost - 1) * 100, 1) if cost > 0 else None
                    if not snaps:
                        snaps = [{"date": asof, "close": cur}]
                        idx = 0
            except Exception:
                pass
        verdict, note = "持有观察", ""
        if cur <= 0 or cost <= 0:
            verdict, note = "数据不足", "无有效成本/现价"
        else:
            sector = p.get("sector") or ""
            f = flow_match(sector, ctx.get("flow") or {}) if sector else None
            fund_in = bool(f and float(f.get("main_yi") or 0) > 0)
            if pnl <= -8.0:
                verdict = "止损警戒"
                note = "亏损%.1f%% 已达止损位" % pnl
            elif ma20 is not None and cur < ma20 and pnl is not None and pnl < 0:
                verdict = "减仓警戒"
                note = "跌破MA20(%.2f) 浮亏%.1f%%" % (ma20, pnl)
            elif hit_periods:
                verdict = "加仓候选"
                note = "本轮命中:%s" % "/".join(hit_periods[:3])
            elif fund_in and pnl is not None and pnl > 0:
                verdict = "持有"
                note = "行业资金流入 浮盈%.1f%%" % pnl
        out.append({
            "secid": sid, "name": name, "code": code6, "qty": qty,
            "avg_buy_price": round(cost, 3), "current_price": round(cur, 3),
            "pnl_pct": pnl, "period_type": p.get("period_type") or "",
            "sector": p.get("sector") or "",
            "in_candidates": bool(hit_periods), "hit_periods": hit_periods,
            "ma20": round(ma20, 3) if ma20 else None,
            "ma60": round(ma60, 3) if ma60 else None,
            "verdict": verdict, "note": note,
        })
    return out


# ── 2. 候选清单生成 ────────────────────────────────────────────────────
def build_candidates(cache, industry, ctx=None):
    asof, trend, _ = market_state_trend(cache)
    if not asof:
        return None
    # ① 外部板块信号：动态补池（池外龙头 K 线并入）
    apk_rotation = load_apk_rotation()
    cache = enrich_cache_from_db(cache, apk_rotation)
    score = score_pool(cache, asof, trend)
    groups = {p: [] for p in PARAMS}
    for code, s in score.items():
        # v9: 低位埋伏（中长线：均线粘合+三日不新低）直接放行，不再依赖 ratio 阈值
        if s["ratio"] >= RATIO_THRESHOLD.get(s["period"], 0.5) or s.get("ambush"):
            ent = cache.get(code, {})
            groups[s["period"]].append({
                "secid": code,
                "name": ent.get("name") or code,
                "industry": industry.get(code[2:], "其他"),
                "board": board_of(code[2:]),
                "pass": s["pass"], "total": s["total"], "ratio": s["ratio"],
                "ambush": s.get("ambush", False),
            })
    for p in groups:
        groups[p].sort(key=lambda r: (r["ratio"], r["pass"]), reverse=True)
        groups[p] = groups[p][:GROUP_SIZE]

    # 宏观因子补位（中长线）：震荡期四周期常选不出票，以板块动量 + 宏观属性补位中线/长线
    # ① 美债利率高+美元信用下调+中国国债收益率低 → 高股息（银行/煤炭/化工/电力/保险）
    # ② 油价≥80美元 → 化工产业链景气，低位埋伏优先
    macro_pool = []
    if macro_div_pref() or oil_high():
        for code, s in score.items():
            ent = cache.get(code, {})
            ind = industry.get(code[2:], "其他")
            if macro_div_pref() and ind in HIGH_DIVIDEND_SECTORS:
                macro_pool.append((s["ratio"], s["pass"], s["total"], code, ent.get("name") or code, ind, "宏观:高股息类债占优"))
            elif oil_high() and ind in OIL_HIGH_SECTORS:
                macro_pool.append((s["ratio"], s["pass"], s["total"], code, ent.get("name") or code, ind, "宏观:油价≥80化工景气"))
        macro_pool.sort(key=lambda r: (r[0], r[1]), reverse=True)
        for p in ("中线", "长线"):
            need = max(0, GROUP_SIZE - len(groups[p]))
            if need <= 0:
                continue
            for ratio, pass_n, total, code, nm, ind, mreason in macro_pool[:need]:
                if ratio <= 0:
                    continue
                # 同一股票已出现在其他周期则跳过（避免重复推荐）
                if any(g["secid"] == code for g in groups[p]):
                    continue
                groups[p].append({
                    "secid": code, "name": nm, "industry": ind,
                    "board": board_of(code[2:]),
                    "pass": pass_n, "total": total, "ratio": ratio,
                    "macro_div": True, "macro_reason": mreason,
                })
            groups[p].sort(key=lambda r: (r["ratio"], r["pass"]), reverse=True)
            groups[p] = groups[p][:GROUP_SIZE]
    prepared = []
    for code, s in score.items():
        ent = cache.get(code, {})
        prepared.append({
            "secid": code, "name": ent.get("name") or code,
            "industry": industry.get(code[2:], "其他"),
            "board": board_of(code[2:]), "period": s["period"],
            "pass": s["pass"], "total": s["total"], "ratio": s["ratio"],
            "ambush": s.get("ambush", False)})
    prepared.sort(key=lambda r: (r["ratio"], r["pass"]), reverse=True)
    prepared = prepared[:PREPARED_SIZE]
    advice = {
        "BULLISH": "大盘多头排列，可积极操作，优先强势板块龙头回踩买点",
        "BEARISH": "大盘弱势，防守为主，只做高确定性买点，控制仓位",
        "OSCILLATION": "震荡市，结构性行情，聚焦板块轮动，快进快出",
        "NO_DATA": "数据不足，等待行情库更新",
    }[trend]
    rotation = rotation_rotate(rotation_load_cache(), asof, industry, top=10)
    # ② 外部板块信号合并：APK 扫描的池外强势板块（如农业种植）并入轮动
    rotation = merge_apk_rotation(rotation, apk_rotation, cache)
    # 轮动龙头并入「板块轮动」组：强势板块的龙头（策略可能选不出，但板块动量已确认）
    rot_group = []
    for r in rotation:
        for l in r.get("leaders", []):
            rot_group.append({
                "secid": l["secid"], "name": l["name"],
                "industry": r["industry"],
                "board": l.get("board") or board_of((l.get("secid") or "")[2:]),
                "sector_mom": r["sec_mom"], "mom20": l.get("mom20"),
                "catalyst": r.get("catalyst_reason") or r.get("catalyst") or "",
            })
    # 去重 + 排序(板块动量×个股动量)
    seen = set()
    rot_unique = []
    for it in rot_group:
        if it["secid"] in seen:
            continue
        seen.add(it["secid"])
        rot_unique.append(it)
    rot_unique.sort(key=lambda r: (r["sector_mom"], r["mom20"] if r["mom20"] is not None else -999), reverse=True)
    groups["板块轮动"] = rot_unique[:GROUP_SIZE * 2]
    data = {
        "schema": 2,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "asof": asof,
        "market_state": trend,
        "advice": advice,
        "groups": groups,
        "prepared": prepared,
        "hot_sectors": hot_sectors(cache, industry, asof),
        "rotation": rotation,
        "pool_total": len(cache),
    }
    # ③ 市场上下文融合：轮动索引注入 ctx → 候选叠加共振分 → 重排
    if ctx is not None:
        ctx["rot_sectors"] = {r.get("industry"): float(r.get("sec_mom") or 0)
                              for r in rotation if r.get("industry")}
        ctx["rot_catalyst"] = {r.get("industry"): (r.get("catalyst_reason") or r.get("catalyst") or "")
                               for r in rotation if r.get("industry")}
        data = decorate_with_ctx(data, ctx)
    data["market_context"] = summarize_context(ctx)
    data["positions"] = assess_positions(ctx, cache, data)
    return data


def candidate_secids(data):
    out = set()
    for items in data.get("groups", {}).values():
        for it in items:
            out.add(it["secid"])
    for it in data.get("prepared", []):
        out.add(it["secid"])
    return out


# ── 3. 微信通知 ────────────────────────────────────────────────────────
def load_notify_cfg():
    try:
        with open(APP_CONFIG, encoding="utf-8") as f:
            return (json.load(f).get("notify") or {})
    except (OSError, ValueError):
        return {}


def send_wechat(new_data, old_data, cfg):
    """推送新买入点。支持 pushplus(优先) / serverchan。返回是否已发送。"""
    added = {}
    for period, items in new_data["groups"].items():
        for it in items:
            if it["secid"] not in old_data:
                added.setdefault(period, []).append(it)
    for it in new_data["prepared"]:
        if it["secid"] not in old_data and all(
                it["secid"] not in v for v in new_data["groups"].values()):
            added.setdefault("预备队", []).append(it)
    if not added:
        return False
    lines = []
    for period, items in added.items():
        head = "🟢 %s" % period
        body = []
        for it in items[:6]:
            body.append("%s %s(%s) %s" % (it["name"], it["board"], it["secid"][2:], it["industry"]))
        lines.append(head + "\n" + "\n".join("  " + b for b in body))
    title = "📈 新买入点 %s 共%d只" % (new_data["asof"], sum(len(v) for v in added.values()))
    content = ("大盘:%s | 池:%d只\n" % (new_data["market_state"], new_data["pool_total"])
               + "\n".join(lines))
    rotation = new_data.get("rotation") or []
    if rotation:
        rot_lines = ["\n🎡 板块轮动 TOP%d:" % min(len(rotation), 5)]
        for r in rotation[:5]:
            tag = "[催化剂]" if r.get("catalyst_reason") else ""
            names = " ".join(l["name"] for l in r["leaders"][:3])
            rot_lines.append("  %s %s %+.1f%% %s\n    %s" % (
                r["industry"], tag, r["sec_mom"], r["catalyst_reason"] or "", names))
        content += "\n".join(rot_lines)
    return _push_wechat(title, content, cfg)


def _push_wechat(title, content, cfg):
    """推送微信。渠道顺序：企业微信机器人(wecom_key) > pushplus > serverchan。

    统一实现见 push_channel.py：企微机器人为本机 POST 直推（零审核，
    不依赖第三方公众号）；pushplus/serverchan 仅作未配 wecom 时的兜底。
    """
    return push_channel.push(title, content, cfg)


# ── 4. COS 上传 ────────────────────────────────────────────────────────
def upload_candidates(data, candidates_key=None):
    cfg = cos_utils.load_cloud_config()
    if not cos_utils.configured(cfg):
        print("跳过上传：cloud_sync 未配置（bucket/secret_id/secret_key）")
        return False
    key = candidates_key or os.environ.get("COS_CANDIDATES_KEY") or DEFAULT_CANDIDATES_KEY
    body = json.dumps(data, ensure_ascii=False).encode("utf-8")
    uri = "/" + key.lstrip("/")
    status, headers, resp = cos_utils.request(
        cfg["secret_id"], cfg["secret_key"], cfg["bucket"], cfg["region"],
        "put", uri, http_headers={"content-type": "application/json",
                                  "content-length": str(len(body))},
        body=body, timeout=30)
    ok = 200 <= status < 300
    print("COS 上传 %s -> %s (%d, %dB)" % ("成功" if ok else "失败", uri, status, len(body)))
    if not ok:
        print("  resp:", resp.decode("utf-8", "replace")[:300])
    return ok


# ── 4.5 交易时间与定时推送 ─────────────────────────────────────────────
def in_trading_time(now=None):
    """是否处于 A 股交易时段(工作日 9:30-11:30, 13:00-15:00)。"""
    now = now or datetime.datetime.now()
    if now.weekday() >= 5:
        return False
    hm = now.hour * 60 + now.minute
    return (9 * 60 + 30) <= hm <= (11 * 60 + 30) or (13 * 60) <= hm <= (15 * 60)


def next_trading_start(now=None):
    """下一个交易时段开始时间：当天 09:30（开盘前）/ 13:00（午休）或次一工作日 9:30。"""
    now = now or datetime.datetime.now()
    if now.weekday() < 5:
        hm = now.hour * 60 + now.minute
        if hm < 9 * 60 + 30:
            return now.replace(hour=9, minute=30, second=0, microsecond=0)
        if hm < 13 * 60:
            return now.replace(hour=13, minute=0, second=0, microsecond=0)
    d = now.date()
    while True:
        d += datetime.timedelta(days=1)
        if d.weekday() < 5:
            return datetime.datetime.combine(d, datetime.time(9, 30))


def send_wechat_timed(data, cfg):
    """定时(每30分钟)概览推送：大盘 + 各周期候选 + 板块轮动，无论有无新信号都发。"""
    lines = []
    for period in ("超短", "短线", "中线", "长线", "板块轮动"):
        items = (data.get("groups") or {}).get(period, [])
        if not items:
            continue
        head = "🟢 %s (%d只)" % (period, len(items))
        body = ["  %s %s(%s) %s" % (it["name"], it.get("board", ""),
                                    it["secid"][2:], it.get("industry", ""))
                for it in items[:5]]
        lines.append(head + "\n" + "\n".join(body))
    rotation = data.get("rotation") or []
    if rotation:
        rot_lines = ["\n🎡 板块轮动 TOP%d:" % min(len(rotation), 5)]
        for r in rotation[:5]:
            tag = "[催化剂]" if r.get("catalyst_reason") else ""
            names = " ".join(l["name"] for l in r["leaders"][:3])
            rot_lines.append("  %s %s %+.1f%%\n    %s" % (
                r["industry"], tag, r["sec_mom"], names))
        lines.append("\n".join(rot_lines))
    title = "⏰ 定时选股 %s %s" % (
        data.get("asof", ""),
        datetime.datetime.now().strftime("%H:%M"))
    content = ("大盘:%s | 池:%d只\n" % (data["market_state"], data["pool_total"])
               + "\n".join(lines))
    return _push_wechat(title, content, cfg)


def _load_digest():
    try:
        with open(DIGEST_FILE, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) and d.get("date") else {"date": "", "secids": [], "sectors": {}}
    except (OSError, ValueError):
        return {"date": "", "secids": [], "sectors": {}}


def _save_digest(d):
    try:
        with open(DIGEST_FILE, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=1)
    except OSError:
        pass


def send_wechat_daily(data, cfg, old_secids=None):
    """每日守护推送：同一交易日相同信息只推送一次，候选 + 板块异动合并一条发送。

    增量规则：
      - 候选：仅上次(last_publish)没有、且当日尚未推送过的 secid
      - 板块异动：板块动量较当日上次推送变化显著(+2pct)或新进轮动 TOP 的板块
    若当日无新信息则不重复推送（返回 False）。
    """
    asof = data.get("asof", "")
    digest = _load_digest()
    if digest.get("date") != asof:   # 新交易日重置
        digest = {"date": asof, "secids": [], "sectors": {}, "sent": False}
    old_secids = old_secids or set()
    sent_secids = set(digest.get("secids") or [])
    # ① 新买入点（候选组 + 预备队，排除已推送）
    new_cands = {}
    for period, items in (data.get("groups") or {}).items():
        for it in items:
            sid = it["secid"]
            if sid in old_secids or sid in sent_secids:
                continue
            new_cands.setdefault(period, []).append(it)
    for it in (data.get("prepared") or []):
        sid = it["secid"]
        if sid in old_secids or sid in sent_secids:
            continue
        if all(sid not in v for v in (data.get("groups") or {}).values()):
            new_cands.setdefault("预备队", []).append(it)
    # ② 板块异动：新进轮动 TOP 或动量较上次 +2pct
    new_sec = []
    prev_sec = digest.get("sectors") or {}
    for r in (data.get("rotation") or [])[:8]:
        ind = r.get("industry")
        if not ind:
            continue
        mom = r.get("sec_mom") or 0
        if ind not in prev_sec or mom - (prev_sec.get(ind) or 0) >= 2.0:
            new_sec.append(r)
    # 大盘状态骤变(如 BULLISH→BEARISH)也算一次异动
    state_now = data.get("market_state", "")
    state_chg = digest.get("state", "") and digest["state"] != state_now
    if not new_cands and not new_sec and not state_chg:
        return False
    lines = []
    for period, items in new_cands.items():
        head = "🟢 %s" % period
        body = []
        for it in items[:8]:
            body.append("%s %s(%s) %s" % (it["name"], it.get("board", ""),
                                          it["secid"][2:], it.get("industry", "")))
        lines.append(head + "\n" + "\n".join("  " + b for b in body))
    if state_chg:
        lines.append("⚠️ 大盘状态: %s → %s" % (digest.get("state", ""), state_now))
    if new_sec:
        rot_lines = ["\n🎡 板块异动:"]
        for r in new_sec[:5]:
            tag = "[催化剂]" if r.get("catalyst_reason") else ""
            names = " ".join(l["name"] for l in (r.get("leaders") or [])[:2])
            rot_lines.append("  %s %s %+.1f%% %s\n    %s" % (
                r["industry"], tag, r["sec_mom"], r.get("catalyst_reason") or "", names))
        lines.append("\n".join(rot_lines))
    title = "📈 板块异动+新买点 %s" % asof
    content = ("大盘:%s | 池:%d只\n" % (state_now, data.get("pool_total", 0))
               + "\n".join(lines))
    ok = _push_wechat(title, content, cfg)
    if ok:
        digest["state"] = state_now
        digest["secids"] = sorted(set(digest.get("secids") or []) | {
            it["secid"] for v in new_cands.values() for it in v})
        for r in new_sec:
            digest["sectors"][r["industry"]] = r.get("sec_mom") or 0
        _save_digest(digest)
    return ok


def _reso_tag(it):
    """候选条目的共振一行摘要（资金/轮动/热度），无则空串。"""
    reso = it.get("reso") or {}
    tags = reso.get("tags") or []
    return " · ".join(tags[:2]) if tags else ""


def _fmt_cand(it):
    ratio = it.get("ratio")
    r = ("%.0f%%" % (ratio * 100)) if ratio is not None else ""
    tag = _reso_tag(it)
    body = "%s %s(%s) %s" % (it.get("name", ""), it.get("board", ""),
                             it["secid"][2:], r)
    return body + ("  [%s]" % tag if tag else "")


def send_wechat_round(data, ctx, cfg, old_secids=None):
    """盘中(15分钟)整轮概览推送：CodeBuddy 命中 + exe 命中对比 + 实仓建议 + 板块资金流。

    每轮必发（不再要求有新信息才推），便于用户跟随节奏看到双端选了什么。
    """
    old_secids = old_secids or set()
    lines = []
    # ① CodeBuddy 本轮四大周期 + 预备队 + 轮动
    groups = data.get("groups") or {}
    # 资金流出候选单独归组（2026-09-04 用户决策：不硬排除，但不再混在正常候选里）
    flow_out = {}
    for period in ("超短", "短线", "中线", "长线", "板块轮动"):
        items = groups.get(period) or []
        if not items:
            continue
        if period != "板块轮动":
            keep, drop = [], []
            for it in items:
                tags = (it.get("reso") or {}).get("tags") or []
                if any(str(t).startswith("资金流出") for t in tags):
                    drop.append(it)
                else:
                    keep.append(it)
            if drop:
                flow_out.setdefault(period, drop)
            items = keep
            if not items:
                continue
        show = items[:6] if period != "板块轮动" else items[:5]
        head = "🟢 %s" % period
        if period == "板块轮动":
            body = []
            for it in show:
                extra = " [催化]" if it.get("catalyst") else ""
                body.append("  %s %s(%s) %s动%+.1f%%%s" % (
                    it.get("name", ""), it.get("board", ""), it["secid"][2:],
                    it.get("industry", ""), it.get("sector_mom") or 0, extra))
            head = "🎡 %s" % period
        else:
            body = []
            for it in show:
                mark = "🆕" if it["secid"] not in old_secids else ""
                body.append("  %s %s" % (mark, _fmt_cand(it)))
            if len(items) > len(show):
                body.append("  …另 %d 只" % (len(items) - len(show)))
        lines.append(head + ("(%d)" % len(items)) + "\n" + "\n".join(body))
    if flow_out:
        fo_total = sum(len(v) for v in flow_out.values())
        fo_lines = []
        for period, items in flow_out.items():
            for it in items:
                # _fmt_cand 已带前 2 个共振标签(通常含"资金流出")；若被其他标签挤出则补注
                shown = _reso_tag(it)
                tag_note = next(
                    (str(t) for t in (it.get("reso") or {}).get("tags") or []
                     if str(t).startswith("资金流出")), "")
                extra = "" if ("资金流出" in shown) else (
                    "  [%s]" % tag_note if tag_note else "")
                fo_lines.append("  %s %s%s" % (period, _fmt_cand(it), extra))
        lines.append("💸 板块资金流出(%d只): 技术/基本面在池但当日资金净流出，非推荐、谨慎不追\n%s" % (
            fo_total, "\n".join(fo_lines)))
    # ①b XML DAG 当日选股（AutoQuant dag_screen_latest.json = exe/APK 共用 XML 引擎）
    dag = None
    try:
        with open(DAG_SCREEN_FILE, encoding="utf-8") as f:
            dag = json.load(f)
    except (OSError, ValueError):
        dag = None
    if dag and (dag.get("result") or {}):
        dag_lines = []
        dag_all = set()
        for period, items in (dag.get("result") or {}).items():
            names = [it.get("name", "") for it in items if it.get("name")]
            if not names:
                continue
            dag_lines.append("  %s(%d): %s" % (period, len(names), ", ".join(names)))
            for it in items:
                code = (it.get("code") or "").strip()
                if code:
                    dag_all.add(secid_of(code))
        both = sorted(dag_all & candidate_secids(data))
        dag_asof = dag.get("asof") or ""
        stale = " (asof %s)" % dag_asof if dag_asof != data.get("asof", "") else ""
        lines.append("🤖 XML DAG 当日选股%s:" % (" " + stale if stale else "")
                     + "\n" + "\n".join(dag_lines or ["  (空)"]))
        if both:
            names = ", ".join(_find_name(data, sid) for sid in both[:5])
            lines.append("⭐ DAG×CodeBuddy 共同命中: %s" % names)
    # ② exe 命中（screen_report_latest.json）与双端对比
    exe = ctx.get("exe") if ctx else None
    if exe and (exe.get("result") or {}):
        exe_lines = []
        exe_all = set()
        for period, items in (exe.get("result") or {}).items():
            names = [it.get("name", "") for it in items if it.get("name")]
            if not names:
                continue
            exe_lines.append("  %s: %s" % (period, ", ".join(names)))
            for it in items:
                code = (it.get("code") or "").strip()
                if code:
                    exe_all.add(secid_of(code))
        both = sorted(exe_all & candidate_secids(data))
        exe_asof = exe.get("asof") or ""
        data_asof = data.get("asof", "")
        if exe_asof == data_asof:
            stale = ""
        elif exe_asof > data_asof:
            stale = " (exe 比本池新 asof %s)" % exe_asof
        else:
            stale = " (exe 未同步当前 asof=%s，请在 exe 端运行选股)" % exe_asof
        lines.append("🔷 exe 命中%s:" % stale + "\n" + "\n".join(exe_lines or ["  (空)"]))
        if both:
            names = ", ".join(_find_name(data, sid) for sid in both[:5])
            lines.append("⭐ 双端共同命中: %s" % names)
        elif exe_all:
            lines.append("ℹ️ exe 本轮无与 CodeBuddy 重合的命中")
    # ③ 实仓建议
    pos_advice = data.get("positions") or []
    verdicts = {"止损警戒": "🔴", "减仓警戒": "🟠", "加仓候选": "🟢", "持有": "🟡", "持有观察": "⚪", "数据不足": "⚪"}
    if pos_advice:
        pl = []
        for p in pos_advice[:6]:
            pnl = ("%+.1f%%" % p["pnl_pct"]) if p["pnl_pct"] is not None else "-"
            pl.append("  %s %s %s(%s) 盈亏%s" % (
                verdicts.get(p["verdict"], "⚪"), p["verdict"], p["name"],
                p["code"], pnl))
        lines.append("💼 实仓建议(%d笔):" % len(pos_advice) + "\n" + "\n".join(pl))
    # ④ 板块资金流 + ETF 资金走向异动（流入/流出 TOP）
    flow_top = (ctx or {}).get("flow_rank") or []
    pos_flow = [r for r in flow_top if r.get("main_yi", 0) > 0][:3]
    neg_flow = [r for r in reversed(flow_top) if r.get("main_yi", 0) < 0][:3]
    if pos_flow or neg_flow:
        parts = []
        if pos_flow:
            parts.append("流入 " + " ".join("%s%+.1f亿" % (r["name"], r["main_yi"])
                                            for r in pos_flow))
        if neg_flow:
            parts.append("流出 " + " ".join("%s%.1f亿" % (r["name"], r["main_yi"])
                                            for r in neg_flow))
        lines.append("💸 板块资金流: " + " | ".join(parts))
    etf_top = sorted((ctx or {}).get("etf_flow") or [],
                     key=lambda e: float(e.get("in_yi") or 0), reverse=True)
    etf_total = sum(float(e.get("in_yi") or 0) for e in etf_top)
    etf_in = [e for e in etf_top if float(e.get("in_yi") or 0) > 0][:4]
    etf_out = [e for e in reversed(etf_top) if float(e.get("in_yi") or 0) < 0][:2]
    etf_parts = []
    if etf_in:
        etf_parts.append("入 " + " ".join(
            "%s%+.1f亿" % (e.get("name"), e.get("in_yi")) for e in etf_in))
    if etf_out:
        etf_parts.append("出 " + " ".join(
            "%s%.1f亿" % (e.get("name"), e.get("in_yi")) for e in etf_out))
    if etf_parts and etf_total < -1.0:
        lines.append("⚠️ ETF资金净流出%.1f亿: " % etf_total + " | ".join(etf_parts))
    elif etf_parts:
        lines.append("📈 ETF资金流向: " + " | ".join(etf_parts))
    # 大盘 + 滚动热度提示
    hot_note = ""
    mc = data.get("market_context") or {}
    if mc.get("hot_stale") and mc.get("trading"):
        hot_note = " | 日榜date=%s(盘中陈旧)" % (mc.get("hot_date") or "?")
    title = "⏰ 盘中选股 %s %s" % (
        datetime.datetime.now().strftime("%H:%M"), data.get("asof", ""))
    content = ("大盘:%s | 池:%d只%s\n" % (
        data.get("market_state", ""), data.get("pool_total", 0), hot_note)
        + "\n".join(lines))
    return _push_wechat(title, content, cfg)


def _find_name(data, secid):
    for items in (data.get("groups") or {}).values():
        for it in items:
            if it["secid"] == secid:
                return "%s%s" % (it.get("name", ""), secid[2:])
    for it in data.get("prepared") or []:
        if it["secid"] == secid:
            return "%s%s" % (it.get("name", ""), secid[2:])
    return secid


# ── 5. 主流程 ──────────────────────────────────────────────────────────
def run_once(dry=False, candidates_key=None, timed_push=False, use_ctx=True):
    cache = load_cache()
    industry = build_industry()
    ctx = build_context() if use_ctx else None
    data = build_candidates(cache, industry, ctx=ctx)
    if not data:
        print("无有效数据（缓存为空？）")
        return 1
    os.makedirs(os.path.dirname(OUT_DEFAULT), exist_ok=True)
    with open(OUT_DEFAULT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    n_cand = sum(len(v) for v in data["groups"].values())
    print("候选清单 %d 只 → %s" % (n_cand, OUT_DEFAULT))
    print("大盘 %s | asof %s | 池 %d" % (data["market_state"], data["asof"], data["pool_total"]))
    for p, items in data["groups"].items():
        for it in items:
            reso = it.get("reso") or {}
            lv = "[%s共振]" % reso["level"] if reso.get("level") else ""
            print("  [%s] %s%s(%s) ratio%.2f%s" % (
                p, it["name"], it["secid"][2:], it.get("industry", ""),
                it.get("ratio", 0), lv))
    pos_advice = data.get("positions") or []
    if pos_advice:
        print("实仓建议 %d 笔:" % len(pos_advice))
        for p in pos_advice[:8]:
            pnl = ("%+.1f%%" % p["pnl_pct"]) if p["pnl_pct"] is not None else "-"
            print("  [%s] %s %s(%s) 盈亏%s  %s" % (
                p["verdict"], p["name"], p["code"], p["sector"] or "-", pnl, p["note"]))
    mc = data.get("market_context") or {}
    if mc:
        print("市场上下文: 资金流板块%d | 周级焦点%d | 实仓%d(%s) | exe%s" % (
            len(mc.get("flow_top", [])) + len(mc.get("flow_bottom", [])),
            mc.get("week_focus_count", 0), mc.get("positions", 0),
            mc.get("positions_asof") or "-",
            (ctx.get("exe") or {}).get("asof", "-") if ctx else "-"))
    old = {}
    if os.path.exists(LAST_FILE):
        try:
            with open(LAST_FILE, encoding="utf-8") as f:
                old = json.load(f)
        except (OSError, ValueError):
            old = {}
    if dry:
        print("[dry] 跳过通知与上传")
        return 0
    cfg = load_notify_cfg()
    old_secids = candidate_secids(old) if old else set()
    if timed_push:
        # 守护(15分钟)模式：整轮概览推送(CodeBuddy+exe+实仓+资金流)，每轮都发
        send_wechat_round(data, ctx, cfg, old_secids=old_secids)
    else:
        # 盘中新信号：仅在新买点出现时推送
        send_wechat(data, old_secids, cfg)
    upload_candidates(data, candidates_key)
    with open(LAST_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    return 0


# ── 6. 盘段节奏守护：开盘后先「下载 + XML DAG 当日选股」，之后每 interval 秒推送 ──
# 交易时段分两个盘段：
#   上午段 09:30-11:30：段首跑一次 prep（下载 _kline_cache 增量 → XML DAG 当日选股）
#   下午段 13:00-15:00：段首再跑一次 prep（数据更新到当日最新后再跑 XML DAG）
# prep 完成后本段立即选股一次，之后每 interval 秒一轮；非交易时段挂起。
# 状态（当天哪段已 prep / 各段上次选股时间）持久化 _daemon_rhythm.json，
# 守护重启或换日期不会重复 prep。
#
# 2026-09-04 用户决策：codebuddy 不再调用 smalltools 的回溯+拟合
# （_full_cycle_backtest.py 仅保留代码不执行），选股改由 exe/APK 共用的
# XML DAG 引擎 AutoQuant/usecase_screen.py 提供（同一份 assets/usecases XML）。
# 实测耗时（2026-09-04 本机）：
#   下载 _update_cache_inc：有缺口约 6.2 分钟(218 只并发10)，无缺口秒回
#   XML DAG 当日选股 usecase_screen：约 1 分钟内
#   单轮选股+推送 run_once：约 1 分钟
RHYTHM_FILE = os.path.join(HERE, "_daemon_rhythm.json")
PREP_DOWNLOAD = os.path.join(HERE, "_update_cache_inc.py")
PREP_DAG = os.path.normpath(os.path.join(ROOT, "AutoQuant", "usecase_screen.py"))
DAG_SCREEN_FILE = os.path.normpath(os.path.join(ROOT, "AutoQuant", "data", "dag_screen_latest.json"))
_SESSIONS = (("am", 9 * 60 + 30, 11 * 60 + 30), ("pm", 13 * 60, 15 * 60))


def current_session(now=None):
    """当前所处盘段：'am' / 'pm' / None（非交易时段或周末）。"""
    now = now or datetime.datetime.now()
    if now.weekday() >= 5:
        return None
    hm = now.hour * 60 + now.minute
    for sid, start, end in _SESSIONS:
        if start <= hm <= end:
            return sid
    return None


def _load_rhythm():
    try:
        with open(RHYTHM_FILE, encoding="utf-8") as f:
            d = json.load(f)
    except (OSError, ValueError):
        d = {}
    today = datetime.date.today().isoformat()
    if d.get("date") != today:  # 跨日自动重置
        d = {"date": today}
    d.setdefault("prep", {})      # {"am": bool, "pm": bool}
    d.setdefault("round_at", {})  # {"am": "HH:MM:SS", "pm": ...}
    return d


def _save_rhythm(d):
    try:
        with open(RHYTHM_FILE, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=1)
    except OSError as e:  # noqa: BLE001
        print("节奏状态写入失败:", e)


def rhythm_need_prep(session):
    """本盘段今天是否尚未做过首刷（下载+回溯+拟合）。"""
    return not bool(_load_rhythm()["prep"].get(session))


def rhythm_mark_prep(session):
    """标记本盘段首刷（下载+回溯+拟合）已完成，防止重启/跨日重复跑。"""
    d = _load_rhythm()
    d["prep"][session] = True
    _save_rhythm(d)


def rhythm_round_due(session, interval, now=None):
    """本盘段 prep 已完成且（尚无本轮 或 距上轮≥interval）时应跑一轮选股。"""
    now = now or datetime.datetime.now()
    if current_session(now) != session or rhythm_need_prep(session):
        return False
    last = _load_rhythm()["round_at"].get(session)
    if not last:
        return True  # 段内首轮：prep 完成后立即选股
    try:
        t = datetime.datetime.strptime(last, "%H:%M:%S").time()
        last_dt = datetime.datetime.combine(now.date(), t)
        return (now - last_dt).total_seconds() >= interval
    except ValueError:
        return True


def rhythm_mark_round(session, now=None):
    now = now or datetime.datetime.now()
    d = _load_rhythm()
    d["round_at"][session] = now.strftime("%H:%M:%S")
    _save_rhythm(d)


def run_prep(session, interval=900, log=print, stop_check=None):
    """盘段首刷：下载当日K线缓存 → XML DAG 当日选股（不再跑 smalltools 回溯+拟合）。失败不标记，下个检查点重试。"""
    label = "上午" if session == "am" else "下午"
    log("[盘段%s] 首刷开始：下载K线 → XML DAG 当日选股（期间不选股，约 1~7 分钟）" % label)
    ok = True
    for title, script in (("下载K线缓存", PREP_DOWNLOAD), ("XML DAG 当日选股", PREP_DAG)):
        if stop_check is not None and stop_check():
            return False
        log("  ▶ %s：%s" % (title, os.path.basename(script)))
        try:
            proc = subprocess.Popen(
                [sys.executable, script], cwd=os.path.dirname(script),
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace", bufsize=1,
                env={**os.environ, "PYTHONIOENCODING": "utf-8"})
            for line in proc.stdout:
                line = line.rstrip()
                if line:
                    log("    | " + line)
            proc.wait()
            if proc.returncode != 0:
                ok = False
                log("  ✗ %s 失败 (exit=%d)" % (title, proc.returncode))
            else:
                log("  ✓ %s 完成" % title)
        except Exception as e:  # noqa: BLE001
            ok = False
            log("  ✗ %s 异常：%s" % (title, e))
        if not ok:
            break
    if ok:
        rhythm_mark_prep(session)
        log("[盘段%s] 首刷完成，开始每 %d 秒选股推送（每段首次立即选股）" % (label, interval))
    else:
        log("[盘段%s] 首刷失败，稍后自动重试" % label)
    return ok


def _interruptible_sleep(seconds, stop_check=None):
    """分段 sleep，便于守护线程快速响应停止。"""
    step = 0.5
    while seconds > 0:
        if stop_check is not None and stop_check():
            return
        time.sleep(min(step, seconds))
        seconds -= step


def daemon_serve(prep=True, interval=900, dry=False, use_ctx=True,
                 log=print, stop_check=None):
    """统一守护主循环（CLI --daemon 与 exe 选股推送面板共用）：

    每盘段（上午 9:30 / 下午 13:00）开始先做 prep（下载+回溯+拟合），
    完成后立即选股一次，此后每 interval 秒一轮；非交易时段挂起。
    """
    log("盘段守护启动：每盘段首刷（下载+XML DAG 选股）→ 每 %d 秒选股推送" % interval)
    last_session = None
    while stop_check is None or not stop_check():
        now = datetime.datetime.now()
        sess = current_session(now)
        if sess is None:
            nxt = next_trading_start(now)
            wait = max((nxt - now).total_seconds(), 1)
            log("[%s] 非交易时段挂起 → 下一盘段 %s（约 %.0f 分钟）" % (
                now.strftime("%H:%M"), nxt.strftime("%m-%d %H:%M"), wait / 60))
            _interruptible_sleep(min(wait, 600), stop_check)
            last_session = None
            continue
        if sess != last_session:
            log("[盘段%s] %s 开盘段开始" % (
                "上午" if sess == "am" else "下午", now.strftime("%H:%M:%S")))
            last_session = sess
        if prep and rhythm_need_prep(sess):
            run_prep(sess, interval=interval, log=log, stop_check=stop_check)
        if rhythm_round_due(sess, interval, now):
            t0 = time.time()
            log("[%s] 开始整轮选股…" % now.strftime("%H:%M:%S"))
            try:
                rc = run_once(dry=dry, candidates_key=None,
                              timed_push=not dry, use_ctx=use_ctx)
                log("[%s] 整轮选股+推送完成 rc=%d（耗时 %.0fs）" % (
                    datetime.datetime.now().strftime("%H:%M:%S"), rc, time.time() - t0))
                rhythm_mark_round(sess)
            except Exception as e:  # noqa: BLE001
                log("选股轮异常：%s" % e)
        _interruptible_sleep(15, stop_check)
    log("🛑 守护已停止")


def main():
    ap = argparse.ArgumentParser(description="PC 候选清单发布（选股→通知→COS）")
    ap.add_argument("--once", action="store_true", help="执行一次")
    ap.add_argument("--daemon", action="store_true", help="守护轮询（默认交易时段每 15 分钟）")
    ap.add_argument("--interval", type=int, default=900, help="轮询间隔秒（默认 900=15 分钟）")
    ap.add_argument("--dry", action="store_true", help="不通知不上传（调试）")
    ap.add_argument("--key", default=None, help="COS candidates_key，默认 stockanalysis/quant/candidates.json")
    ap.add_argument("--timed", action="store_true", help="定时推送模式（单次执行也推送整轮概览）")
    ap.add_argument("--no-ctx", action="store_true", help="跳过市场上下文/共振/实仓（纯形态快速选股）")
    ap.add_argument("--no-prep", action="store_true",
                    help="关闭盘段首刷(下载+XML DAG 选股)，纯每 interval 秒选股轮询（旧节奏）")
    args = ap.parse_args()
    if args.daemon:
        print("盘段守护启动：每盘段(上午9:30 / 下午13:00)首刷一次 "
              "「下载K线+XML DAG 当日选股」，完成后每 %d 秒整轮选股+推送双端命中" % args.interval)
        daemon_serve(prep=not args.no_prep, interval=args.interval,
                     dry=args.dry, use_ctx=not args.no_ctx)
        return 0
    return run_once(dry=args.dry, candidates_key=args.key, timed_push=args.timed,
                    use_ctx=not args.no_ctx)


if __name__ == "__main__":
    sys.exit(main())
