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
import re
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
# 技术指标(零依赖纯 Python)：SAR/MACD/KDJ/RSI/CCI/OBV/ATR/MA 单行摘要。
# exe(frozen) 未打包该模块时静默降级，不影响主流程。
try:  # noqa: E402
    from _technicals import analyze as _tech_analyze  # noqa: E402
    from _technicals import make_tag as _tech_tag  # noqa: E402
    from _technicals import sar_alert as _tech_sar_alert  # noqa: E402
    _TECHS_OK = True
except Exception:  # pragma: no cover - frozen exe 旧包缺失时降级
    _TECHS_OK = False

try:  # noqa: E402 - 换手/量比档位标注（旧包缺失时静默降级，不影响 tech 主摘要）
    from _technicals import annotate_quote as _tech_quote  # noqa: E402
    from _technicals import volume_ratio_of as _tech_vr  # noqa: E402
    _QUOTE_OK = True
except Exception:  # pragma: no cover
    _QUOTE_OK = False

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

# 2026-09-06 个股基本面画像（逻辑票分档柔性建议）：_holding_thesis.json
#   soft=true  → 有中期逻辑/预期：破 -8% 不机械喊清仓，按 risk_pct(逻辑止损) 分档：
#                 -8%~risk_pct 区间给「减仓应对/留底仓做T」，破 risk_pct 才「止损警戒」。
THESIS_FILE = os.path.join(HERE, "_holding_thesis.json")

def _load_thesis():
    """读取个股基本面画像（失败返回 {}，守护不因此中断）。"""
    try:
        with open(THESIS_FILE, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def _thesis_of(code):
    """按 6 位代码取画像；无则 None。"""
    code = str(code or "").strip()[-6:]
    return (_load_thesis() or {}).get(code)


DEFAULT_CANDIDATES_KEY = "stockanalysis/quant/candidates.json"
# 2026-09-04：用户要求"先把条件放开看推送效果"。通过比例从 0.55 降到 0.45，
# 让更多达到基础的候选进组观察；后续效果好再逐步收紧。
RATIO_THRESHOLD = {"超短": 0.45, "短线": 0.45, "中线": 0.45, "长线": 0.45}
GROUP_SIZE = 8
# 2026-09-05 机构化收敛：面向用户只露 短线/中线/长线 三档。超短引擎保留
# (walk-forward wr51.3% 薄利稳定)，命中折入"短线⚡"极速档。引擎/DB 键不变。
DISPLAY_PERIODS = ("短线", "中线", "长线")
_FLASH_PERIODS = {"超短", "超短线", "ultra_short", "UltraShortQuant"}


def fold_period(period):
    """内部周期键 → 展示档: 超短类 → (短线, True)，其余原样。"""
    return ("短线", True) if period in _FLASH_PERIODS else (period, False)
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
        # 事件库注入：活跃宏观事件的强受益词 → analyze 放宽通道（macroSectorKeywords）
        try:
            from _event_kb import boost_keywords  # noqa: PLC0415
            _ekws = boost_keywords(asof)
        except Exception:
            _ekws = None
        best = None
        ambush = False
        for period, p in PARAMS.items():
            try:
                if _ekws:
                    p["macroSectorKeywords"] = _ekws or []
                else:
                    p.pop("macroSectorKeywords", None)
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
      - 非逻辑票：盈亏 ≤ -8% 或跌破 MA20 且现价低于成本 → 止损/减仓警戒
      - 逻辑票(个股画像 _holding_thesis.json soft=true)：-8%~逻辑止损(risk_pct)
        区间 → 「减仓应对」柔性建议(留底仓/做T/企稳加回)，破 risk_pct 才真止损
      - 本轮候选命中(超短/短/中/长/轮动/prepared) → 加仓候选/持有
      - 行业资金净流入>0 且盈亏>0 → 持有增强
      - 其他 → 持有观察
    返回 [{secid,name,code,qty,avg_buy_price,current_price,pnl_pct,period_type,
           sector,in_candidates,hit_periods,ma20,ma60,soft,verdict,note}]
    """
    if not ctx:
        return []   # use_ctx=False（--no-ctx/预演）时不做实仓评估
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
        soft = _thesis_of(code6)
        if cur <= 0 or cost <= 0:
            verdict, note = "数据不足", "无有效成本/现价"
        else:
            sector = p.get("sector") or ""
            f = flow_match(sector, ctx.get("flow") or {}) if sector else None
            fund_in = bool(f and float(f.get("main_yi") or 0) > 0)
            if soft:
                # 逻辑票（个股画像 soft）：-8% 不机械清仓，破逻辑止损(risk_pct)才警戒
                hard_stop = -abs(float(soft.get("risk_pct") or -20.0))
                if pnl is not None and pnl <= hard_stop:
                    verdict, note = "止损警戒", "亏损%.1f%% 跌破逻辑止损位(%.0f%%)" % (
                        pnl, -hard_stop)
                elif pnl is not None and pnl <= -8.0:
                    verdict = "减仓应对"
                    note = "逻辑票回调%.1f%% %s" % (pnl, soft.get("tone") or "")
                elif ma20 is not None and cur < ma20:
                    verdict = "减仓应对"
                    note = "跌破MA20(%.2f) %s" % (ma20, soft.get("tone") or "")
                elif hit_periods:
                    verdict = "加仓候选"
                    note = "本轮命中:%s" % "/".join(hit_periods[:3])
                elif pnl is not None and pnl > 0:
                    verdict = "持有"
                    note = "行业资金流入 逻辑票浮盈%.1f%%" % pnl
                else:
                    verdict = "持有观察"
                    note = "逻辑票浮亏%.1f%% 等企稳" % (pnl if pnl is not None else 0)
            else:
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
            "soft": bool(soft), "verdict": verdict, "note": note,
        })
    return out


def assess_portfolio(pos_advice, max_single=0.30, top2_max=0.55, stop_pct=-8.0):
    """组合级风控纪律（机构化一期）：单票仓位上限 / 集中度上限 / 整体止损。

    pos_advice: assess_positions() 输出（含 qty/avg_buy_price/current_price）。
    分母 = 持仓总市值(现价)。至少 1 只有效持仓才计算。
    返回 {n, total_mv, pnl_pct, alerts:[...]}，无效返回 {}。
    """
    valid = [p for p in (pos_advice or [])
             if (p.get("qty") or 0) > 0 and (p.get("avg_buy_price") or 0) > 0
             and (p.get("current_price") or 0) > 0]
    if not valid:
        return {}
    mv = [(float(p["qty"]) * float(p["current_price"]),
           float(p["qty"]) * float(p["avg_buy_price"]), p) for p in valid]
    total_mv = sum(m[0] for m in mv)
    cost_mv = sum(m[1] for m in mv)
    if total_mv <= 0 or cost_mv <= 0:
        return {}
    pnl_pct = round((total_mv / cost_mv - 1) * 100, 1)
    ranked = sorted(mv, key=lambda m: -m[0])
    alerts = []
    over = [r for r in ranked if r[0] / total_mv > max_single]
    for cur_mv, _, p in over[:3]:
        ratio = cur_mv / total_mv
        alerts.append("🔴 %s(%s) 占持仓%.0f%% 超单票上限%d%% → 建议减至≤%d%%" % (
            p["name"], p["code"], ratio * 100, max_single * 100, max_single * 100))
    if len(over) > 3:
        alerts.append("…另%d只超限" % (len(over) - 3))
    top2 = (ranked[0][0] + ranked[1][0]) if len(ranked) >= 2 else total_mv
    if len(valid) >= 3 and top2 / total_mv > top2_max:
        alerts.append("🟠 前2大持仓占%.0f%% 超集中度上限%d%% → 建议分散至≥3只" % (
            top2 / total_mv * 100, top2_max * 100))
    if pnl_pct <= stop_pct:
        alerts.append("🔻 组合整体浮亏%.1f%% 达整体止损%d%% → 建议降仓防守" % (
            pnl_pct, -stop_pct))
    return {"n": len(valid), "total_mv": round(total_mv, 0),
            "pnl_pct": pnl_pct, "alerts": alerts}


# ── 2. 候选清单生成 ────────────────────────────────────────────────────
def build_candidates(cache, industry, ctx=None):
    asof, trend, _ = market_state_trend(cache)
    if not asof:
        return None
    # ① 外部板块信号：动态补池（池外龙头 K 线并入）
    apk_rotation = load_apk_rotation()
    cache = enrich_cache_from_db(cache, apk_rotation)
    score = score_pool(cache, asof, trend)
    groups = {p: [] for p in DISPLAY_PERIODS}
    for code, s in score.items():
        # v9: 低位埋伏（中长线：均线粘合+三日不新低）直接放行，不再依赖 ratio 阈值
        if s["ratio"] >= RATIO_THRESHOLD.get(s["period"], 0.5) or s.get("ambush"):
            ent = cache.get(code, {})
            disp, flash = fold_period(s["period"])
            groups[disp].append({
                "secid": code,
                "name": ent.get("name") or code,
                "industry": industry.get(code[2:], "其他"),
                "board": board_of(code[2:]),
                "pass": s["pass"], "total": s["total"], "ratio": s["ratio"],
                "ambush": s.get("ambush", False),
                "flash": flash, "signal_period": s["period"],
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
    data["portfolio"] = assess_portfolio(data["positions"])
    data = _attach_tech(data, cache)
    return data


def _find_cache_snaps(cache, key):
    """候选 secid / 实仓 6 位 code → 日线 snaps；key 带/不带市场前缀均可。"""
    if not cache or not key:
        return []
    if key in cache:
        return (cache.get(key) or {}).get("snaps") or []
    for pref in ("sh", "sz", "bj"):
        if (pref + key) in cache:
            return (cache.get(pref + key) or {}).get("snaps") or []
    return []


def _attach_tech(data, cache):
    """候选(groups/prepared)附技术指标单行摘要；实仓附 SAR 刚转绿预警。
    数据不足或指标模块缺失时静默跳过，不影响主流程。"""
    if not _TECHS_OK:
        return data

    def tech_of(secid):
        snaps = _find_cache_snaps(cache, secid)
        if len(snaps) < 30:
            return ""
        try:
            tag = " ".join(_tech_tag(_tech_analyze(snaps)).split()[:3])
            if not _QUOTE_OK:
                return tag
            s0 = snaps[-1]
            tn = s0.get("turnover") if s0 and isinstance(s0, dict) else None
            q = _tech_quote(turnover=tn, volume_ratio=_tech_vr(snaps))
            return (tag + " ｜" + "｜".join(q[:2])) if q else tag
        except Exception:
            return ""

    for items in (data.get("groups") or {}).values():
        for it in items:
            if it.get("secid") and not it.get("tech"):
                it["tech"] = tech_of(it["secid"])
    for it in (data.get("prepared") or []):
        if it.get("secid") and not it.get("tech"):
            it["tech"] = tech_of(it["secid"])
    for p in (data.get("positions") or []):
        if p.get("secid"):
            snaps = _find_cache_snaps(cache, p["secid"])
        elif p.get("code"):
            snaps = _find_cache_snaps(cache, p["code"])
        else:
            continue
        if len(snaps) < 30:
            continue
        al = _tech_sar_alert(snaps)
        if al:
            p["sar_warn"] = al["days"]
        if not p.get("tech"):
            try:
                tag = " ".join(_tech_tag(_tech_analyze(snaps)).split()[:4])
                if tag:
                    p["tech"] = tag
            except Exception:
                pass
    # XML DAG 主线命中票也补技术标签（DAG json code 带 sh/sz 前缀）
    dag_tech = {}
    try:
        with open(DAG_SCREEN_FILE, encoding="utf-8") as f:
            _dag = json.load(f)
        for items in (_dag.get("result") or {}).values():
            for it in items:
                raw = (it.get("code") or "").strip()
                if raw and raw not in dag_tech:
                    dag_tech[raw] = tech_of(raw)
    except (OSError, ValueError):
        pass
    data["dag_tech"] = dag_tech
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
    for period in DISPLAY_PERIODS + ("板块轮动",):
        items = (data.get("groups") or {}).get(period, [])
        if not items:
            continue
        head = "🟢 %s (%d只)" % (period, len(items))
        body = ["  %s%s %s(%s) %s" % ("⚡" if it.get("flash") else "", it["name"],
                                      it.get("board", ""), it["secid"][2:],
                                      it.get("industry", ""))
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


def _round_legacy(data, ctx, cfg, old_secids=None):
    """[legacy 2026-09-05] 旧版整轮概览（CodeBuddy 首段 + DAG/exe 尾段），已由新版 send_wechat_round 取代，保留回滚。"""
    old_secids = old_secids or set()
    lines = []
    # 2026-09-05 主次对齐：XML DAG(下方 🤖 段)为选股主线；CodeBuddy 引擎候选仅作对照参考。
    # 参考引擎明细截断，避免再出现"一大串非主线候选"刷屏。
    # ① CodeBuddy 引擎候选（对照参考，非主线）
    groups = data.get("groups") or {}
    # 资金流出候选单独归组（2026-09-04 用户决策：不硬排除，但不再混在正常候选里）
    flow_out = {}
    for period in DISPLAY_PERIODS + ("板块轮动",):
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
        show = items[:3] if period != "板块轮动" else items[:3]
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
                body.append("  %s %s%s" % (
                    mark, "⚡" if it.get("flash") else "", _fmt_cand(it)))
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
            disp, flash = fold_period(period)
            dag_lines.append("  %s%s(%d): %s" % ("⚡" if flash else "", disp,
                                                 len(names), ", ".join(names)))
            for it in items:
                code = (it.get("code") or "").strip()
                if code:
                    dag_all.add(secid_of(code))
        both = sorted(dag_all & candidate_secids(data))
        dag_asof = dag.get("asof") or ""
        stale = " (asof %s)" % dag_asof if dag_asof != data.get("asof", "") else ""
        lines.append("⭐ 主线·XML DAG 当日选股%s:" % (" " + stale if stale else "")
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
            disp, flash = fold_period(period)
            exe_lines.append("  %s%s: %s" % (
                "⚡" if flash else "", disp, ", ".join(names)))
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
    # ③ 组合纪律（机构化一期：单票仓位上限/集中度上限/整体止损；仅触发时提示）
    pf = data.get("portfolio") or {}
    if pf and pf.get("alerts"):
        lines.append("⚠️ 组合纪律(持仓%d只 整体%+.1f%%):" % (
            pf["n"], pf["pnl_pct"]) + "\n" + "\n".join("  " + a for a in pf["alerts"]))
    # ③b 实仓建议
    pos_advice = data.get("positions") or []
    verdicts = {"止损警戒": "🔴", "减仓警戒": "🟠", "减仓应对": "🟠", "加仓候选": "🟢",
                "持有": "🟡", "持有观察": "⚪", "数据不足": "⚪"}
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
def _find_item_by_secid(data, secid):
    for items in (data.get("groups") or {}).values():
        for it in items:
            if it.get("secid") == secid:
                return it
    return None


def _norm_pos_txt(s):
    """归一化实仓文本：数字/价格/百分比→占位符、压缩空白，用于「变化指纹」。"""
    if not s:
        return ""
    s = re.sub(r"[-+]?\d+(?:\.\d+)?%?", "#", str(s))
    s = re.sub(r"\d{4}-\d{2}-\d{2}", "DATE", s)
    return re.sub(r"\s+", " ", s).strip()


def _pos_advice_sig(data):
    """实仓建议变化指纹：每笔(code, verdict, note模板) + 组合纪律条目。

    数字/价格波动不视为「变化」，只有 verdict/建议内容/纪律类型变化才触发展开。"""
    pos = data.get("positions") or []
    pf = data.get("portfolio") or {}
    parts = []
    for p in pos[:8]:
        parts.append("|".join((p.get("code", ""), p.get("verdict", ""),
                               _norm_pos_txt(p.get("note", "")))))
    for a in (pf.get("alerts") or []):
        parts.append("A|" + _norm_pos_txt(a))
    return "\n".join(parts)


def _pos_advice_lines(data):
    """构造实仓建议完整段落（组合纪律 + 逐笔，含个股画像柔性结论）。无实仓返回 []。"""
    pos = data.get("positions") or []
    if not pos:
        return []
    pf = data.get("portfolio") or {}
    lines = []
    if pf and pf.get("alerts"):
        lines.append("⚠️ 组合纪律(持仓%d只 整体%+.1f%%):" % (pf["n"], pf["pnl_pct"]))
        lines.extend("  " + a for a in pf["alerts"])
    verdicts = {"止损警戒": "🔴", "减仓警戒": "🟠", "减仓应对": "🟠", "加仓候选": "🟢",
                "持有": "🟡", "持有观察": "⚪", "数据不足": "⚪"}
    body = []
    for p in pos[:8]:
        pnl = ("%+.1f%%" % p["pnl_pct"]) if p["pnl_pct"] is not None else "-"
        warn = " [SAR刚翻绿%d天]" % p["sar_warn"] if p.get("sar_warn") else ""
        note = p.get("note") or ""
        tech = p.get("tech") or ""
        row = "  %s %s%s %s %s 盈亏%s" % (
            verdicts.get(p["verdict"], "⚪"), p["verdict"], warn,
            p.get("name", ""), p.get("code", ""), pnl)
        if tech:
            row += "  | " + tech
        if note:
            row += " " + note
        body.append(row)
    lines.extend(body)
    return lines


def _append_pos_block(lines, data):
    """实仓段内嵌选股消息：与上轮指纹相同 → 仅一行概要；不同 → 展开完整建议。

    2026-09-06 用户决策：盘中轮不再单独推实仓独立消息，改为随每轮选股消息
    「有变化才推」——评估每轮都在跑，结论无变化不刷屏，有变化才展开详情。"""
    pos = data.get("positions") or []
    if not pos:
        return
    pf = data.get("portfolio") or {}
    pnl_all = pf.get("pnl_pct") if pf else None
    sig = _pos_advice_sig(data)
    rhythm = _load_rhythm()
    changed = sig and sig != (rhythm.get("pos_sig") or "")
    lines.append("")
    if changed:
        lines.append("💼 实仓·建议变化 ↓")
        lines.extend(_pos_advice_lines(data))
        rhythm["pos_sig"] = sig
        _save_rhythm(rhythm)
    else:
        lines.append("💼 实仓 %d只 整体%s · 建议与上轮一致，无新操作" % (
            len(pos), ("%+.1f%%" % pnl_all) if pnl_all is not None else "-"))


def round_pages(data, ctx, cfg, old_secids=None, pos_advice=True,
                scene=None, dag=None, cache=None, lowbuy_offline=None,
                note_offline=None):
    """整轮三段消息的纯拼装（不改文件、不推送），实时推送与历史回放共用同一排版。

    - 页1 选股摘要：⭐ 主线·XML DAG 当日选股（超短+短线合并去重，每档最多3只，
      每只附 RSI/SAR/MACD/OBV 等技术指标）+ 📋 对照参考(非主线·引擎精选前3)
      + ⭐ 双端共同命中
    - 页2 资金与低吸：💸 板块资金流入/流出 + 📈 ETF资金流向 + 🎯 ETF持仓前五低吸
    - 页3 实仓做T（无实仓不生成）

    scene=None 时按当前时间推导(盘中/盘外)；dag 缺省读 DAG_SCREEN_FILE；
    cache={secid:{"snaps":[]}} 供 DAG 独有票补算技术指标；lowbuy_offline 传
    离线低吸行(历史回放；此时页2 不展示无存档的实时资金流)。
    返回 [(title, content), ...]。
    """
    old_secids = old_secids or set()
    if scene is None:
        now = datetime.datetime.now()
        scene = ("盘中选股 " if in_trading_time(now) else "盘外选股 ") + now.strftime("%H:%M")
    asof = data.get("asof", "")
    state = data.get("market_state", "")
    state_cn = {"BULLISH": "上涨", "BEARISH": "下跌", "OSCILLATION": "震荡",
                "NO_DATA": "数据不足"}.get(state, state)
    state_mark = {"BULLISH": "🔴", "BEARISH": "🟢", "OSCILLATION": "🟡",
                  "NO_DATA": "⚪"}.get(state, "⚪")
    p1 = ["%s | %s" % (scene, asof),
          "%s 大盘: %s %s | 池 %d" % (state_mark, state, state_cn,
                                     data.get("pool_total", 0))]
    # ① 主线：XML DAG 当日选股（与 exe 同源；超短并入短线、按代码去重、每档≤3只）
    if dag is None:
        try:
            with open(DAG_SCREEN_FILE, encoding="utf-8") as f:
                dag = json.load(f)
        except (OSError, ValueError):
            dag = None
    dag_all = set()
    if dag and (dag.get("result") or {}):
        res = dag.get("result") or {}
        bucket = {"短线": [], "中线": [], "长线": []}
        for period in ("超短", "短线", "中线", "长线"):
            disp, _ = fold_period(period)  # 超短 → 短线
            for it in res.get(period) or []:
                raw = (it.get("code") or "").strip()
                code = raw[2:] if raw[:2].lower() in ("sh", "sz", "bj") else raw
                nm = it.get("name") or code
                extra = ""
                hit = None
                if raw:
                    hit = (_find_item_by_secid(data, raw)
                           or _find_item_by_secid(data, code))
                if hit:
                    extra = hit.get("tech") or ""
                if not extra and raw:
                    extra = (data.get("dag_tech") or {}).get(raw, "")
                if not extra and raw and cache is not None and raw in cache:
                    try:
                        extra = _tech_tag(_tech_analyze(cache[raw].get("snaps") or []))
                    except Exception:
                        extra = ""
                bucket[disp].append((raw, code, nm, extra))
        rows = []
        for disp in ("短线", "中线", "长线"):
            seen = set()
            seq = []
            for raw, code, nm, extra in bucket[disp]:
                key = code or raw
                if not key or key in seen:
                    continue
                seen.add(key)
                seq.append((raw, code, nm, extra))
                if len(seq) >= 3:
                    break
            if not seq:
                continue
            for raw, _, _, _ in seq:
                if raw:
                    dag_all.add(raw)
            rows.append("  %s(%d):" % (disp, len(seq)))
            for _, code, nm, extra in seq:
                rows.append("    🔴 %s(%s)%s" % (nm, code,
                                                 (" | " + extra) if extra else ""))
        stale = ""
        if dag.get("asof") and dag.get("asof") != asof:
            stale = " (asof %s)" % dag.get("asof")
        p1.append("")
        p1.append("⭐ 主线·XML DAG 当日选股(%d只)%s" % (len(dag_all), stale))
        p1.append("\n".join(rows) if rows else "  (暂无 DAG 命中)")
    # ② 对照参考：CodeBuddy 引擎候选（非主线，剔除主线 DAG 已展示代码）→ 精选最多3只
    ref_rows = []
    for period in DISPLAY_PERIODS:
        for it in (data.get("groups") or {}).get(period) or []:
            sid = it.get("secid") or ""
            if sid in dag_all or sid[2:] in dag_all:
                continue
            tags = (it.get("reso") or {}).get("tags") or []
            flowout = any(str(t).startswith("资金流出") for t in tags)
            ref_rows.append((period, it, flowout))
    if ref_rows:
        ref_rows.sort(key=lambda r: -(r[1].get("ratio") or 0))
        keep = ref_rows[:3]
        p1.append("")
        p1.append("📋 对照参考·非主线(%d只)" % len(keep))
        for period, it, flowout in keep:
            mark = "🟡" if flowout else "🔴"
            r = it.get("ratio")
            ratio_s = ("%.0f%%" % (r * 100)) if r is not None else ""
            side = " 资金流出" if flowout else ""
            newdot = "🆕" if it["secid"] not in old_secids else ""
            tech = it.get("tech") or ""
            base = "%s%s [%s] %s%s(%s) %s%s" % (
                newdot, mark, period, it.get("name", ""), it.get("board", ""),
                it["secid"][2:], ratio_s, side)
            if tech:
                base += "  | " + tech
            p1.append("    " + base)
    # ③ 双端共同命中
    both = sorted(dag_all & candidate_secids(data))
    if both:
        names = " ".join(_find_name(data, sid) for sid in both[:8])
        p1.append("")
        p1.append("⭐ 双端共同命中(%d): %s" % (len(both), names))
    # ── 页面2：资金 / ETF / 低吸（独立消息）──
    p2 = ["%s | %s" % (scene, asof)]
    p2_extra = False
    if ctx:
        flow_top = ctx.get("flow_rank") or []
        pos_f = [r for r in flow_top if r.get("main_yi", 0) > 0][:4]
        neg_f = [r for r in reversed(flow_top) if r.get("main_yi", 0) < 0][:4]
        if pos_f:
            p2.append("")
            p2.append("💸 板块资金流入: " + " ".join(
                "%s%+.1f亿" % (r["name"], r["main_yi"]) for r in pos_f))
            p2_extra = True
        if neg_f:
            p2.append("")
            p2.append("💸 板块资金流出: " + " ".join(
                "%s%.1f亿" % (r["name"], r["main_yi"]) for r in neg_f))
            p2_extra = True
        etf_top = sorted(ctx.get("etf_flow") or [],
                         key=lambda e: float(e.get("in_yi") or 0), reverse=True)
        etf_in = [e for e in etf_top if float(e.get("in_yi") or 0) > 0][:4]
        etf_out = [e for e in reversed(etf_top) if float(e.get("in_yi") or 0) < 0][:2]
        eparts = []
        if etf_in:
            eparts.append("入 " + " ".join(
                "%s%+.1f亿" % (e.get("name"), e.get("in_yi")) for e in etf_in))
        if etf_out:
            eparts.append("出 " + " ".join(
                "%s%.1f亿" % (e.get("name"), e.get("in_yi")) for e in etf_out))
        if eparts:
            p2.append("")
            p2.append("📈 ETF资金流向: " + " | ".join(eparts))
            p2_extra = True
        # ⑥ 热门板块 ETF 前5重仓 · 低吸（行业ETF重仓跟踪→低位埋伏，每票附
        #    紧凑指标串 + 放量企稳确认标注）
        try:
            import _etf_holdings as _eh
            hot_low = _eh.low_buy_lines(
                [r["name"] for r in pos_f], ctx.get("etf_flow") or [])
            if hot_low:
                p2.append("")
                p2.append("🎯 ETF持仓前五低吸(热门板块)")
                p2.extend(hot_low)
                p2_extra = True
            try:
                focus = _eh.load_focus_sectors()
            except Exception:
                focus = []
            if focus:
                f_low = _eh.low_buy_lines(focus, ctx.get("etf_flow") or [])
                if f_low:
                    p2.append("")
                    p2.append("📌 用户重点关注板块·ETF低吸")
                    p2.extend(f_low)
                    p2_extra = True
        except Exception:
            pass
    else:
        # 离线/历史回放：板块/ETF资金流为盘中实时采集、无存档 → 说明 + 全板块前五低吸
        if note_offline:
            p2.append("")
            p2.append(note_offline)
            p2_extra = True
        if lowbuy_offline:
            p2.append("")
            p2.append("🎯 ETF持仓前五低吸")
            p2.extend(lowbuy_offline)
            p2_extra = True
    # ── 页面3：实仓与做T（独立消息；指纹无变化仅一行概要，有变化展开逐笔+指标标注）──
    pages = [("%s | %s" % (scene, asof), "\n".join(p1))]
    if p2_extra:
        pages.append(("📊 资金流向与低吸 | %s" % asof, "\n".join(p2)))
    if pos_advice and (data.get("positions") or []):
        p3 = ["%s | %s" % (scene, asof)]
        _append_pos_block(p3, data)
        pages.append(("💼 实仓建议与做T | %s" % asof, "\n".join(p3)))
    return pages


def send_wechat_round(data, ctx, cfg, old_secids=None, pos_advice=True, **kw):
    """整轮三段独立消息推送（排版见 round_pages，2026-09-05 新版消息结构）。

    - 主线 = ⭐ XML DAG 当日选股（与 exe 共用同一套 XML，等同 exe 选股，不再单列 exe 段）
    - 📋 对照参考（非主线）：CodeBuddy 引擎候选按命中率排序，精选前 3
    - 每票标色：🔴 = 引擎过筛/推荐候选；🟡 = 盘面在池但资金流出
    - 候选行附主流指标摘要(SAR/MACD/RSI/OBV 等)
    - 实仓段内嵌本轮消息（2026-09-06）：与上轮建议指纹相同仅一行概要「无新操作」，
      有变化(verdict/建议内容变化)才展开逐笔详情——每轮评估但不刷屏。
    """
    pages = round_pages(data, ctx, cfg, old_secids=old_secids, pos_advice=pos_advice,
                        **kw)
    ok = False
    for title, content in pages:
        ok = _push_wechat(title, content, cfg) or ok
    return ok


def run_once(dry=False, candidates_key=None, timed_push=False, use_ctx=True, pos_advice=True):
    cache = load_cache()
    industry = build_industry()
    ctx = build_context() if use_ctx else None
    data = build_candidates(cache, industry, ctx=ctx)
    if not data:
        print("无有效数据（缓存为空？）")
        return 1
    # ETF 重仓低吸摘要（供 App 端渲染；无缓存数据时为 None 不影响主流程）
    try:
        import _etf_holdings as _eh
        data["etf_holdings"] = _eh.summary_json()
    except Exception:
        data["etf_holdings"] = None
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
        # 守护(15分钟)轮：整轮概览推送；实仓段内嵌消息（有变化才展开，见 _append_pos_block）
        send_wechat_round(data, ctx, cfg, old_secids=old_secids, pos_advice=pos_advice)
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
# 每日选股自检闭环（记忆账本 + 到期结算 + 基线漂移反馈），见 _self_review.py
SELF_REVIEW = os.path.join(HERE, "_self_review.py")
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
    d.setdefault("rounds", {})    # 当日各盘段已选股轮数 {"am": n, "pm": n}
    d.setdefault("pre", False)    # 09:00 预热(下载+情报暖缓存)是否完成
    d.setdefault("tail", False)   # 15:00 尾盘K线拉取是否完成
    d.setdefault("learn", False)  # 15:00 尾盘后的选股自检(记忆+结算)是否完成
    d.setdefault("sum", False)    # 15:10 收盘总结是否已推送
    d.setdefault("hit", {})       # 当日逐轮命中累计 period -> {secid: 次数}
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


# ── v2 时刻表辅助（2026-09-06 方案A，用户确认）───────────────────────────
# 交易日时刻表：
#   09:00        预热：K线增量下载 + 情报扫描(dry)暖缓存（美股隔夜/韩股开盘/快讯研报）
#   09:30~11:30  上午 9 轮（09:30 首次，之后每 15 分钟：09:45…11:30）
#   13:00~14:30  下午 7 轮（每 15 分钟）；14:30 后不再盘中选股，避免尾盘诱导
#   15:00        尾盘最后一次 K 线拉取（仅下载，不再选股）
#   15:10        收盘总结：当日选股汇总 + 持仓回顾 + 纪律 + 鼓励（无做T/买卖点指令）
# 守护/选股过程错误写入 _daemon_ops.jsonl，供后续矫正（用户需求）。
OPS_LOG = os.path.join(HERE, "_daemon_ops.jsonl")


def ops_note(kind, msg):
    """守护/选股过程错误留痕（追加 JSONL，供后续矫正）。"""
    try:
        with open(OPS_LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps({
                "ts": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "kind": kind, "msg": msg}, ensure_ascii=False) + "\n")
    except OSError as e:
        print("ops_note 写入失败:", e)


def rhythm_need_flag(key):
    return not bool(_load_rhythm().get(key))


def rhythm_mark_flag(key):
    d = _load_rhythm()
    d[key] = True
    _save_rhythm(d)


def _slot_of(now=None):
    """守护档位：pre(09:00-09:29) / am(09:30-11:30) / pm(13:00-14:30) / None。
    14:31 之后由 daemon 空档分支处理「尾盘拉取 + 收盘总结」。"""
    now = now or datetime.datetime.now()
    if now.weekday() >= 5:
        return None
    hm = now.hour * 60 + now.minute
    if hm < 9 * 60:
        return None
    if hm < 9 * 60 + 30:
        return "pre"
    if hm <= 11 * 60 + 30:
        return "am"
    if hm < 13 * 60:
        return None
    if hm <= 14 * 60 + 30:
        return "pm"
    return None


def _is_quarter(now=None):
    now = now or datetime.datetime.now()
    return now.minute % 15 == 0


def _today_at(hour, minute=0):
    now = datetime.datetime.now()
    return now.replace(hour=hour, minute=minute, second=0, microsecond=0)


def next_weekday_0900(now=None):
    now = now or datetime.datetime.now()
    d = now.date()
    while True:
        d += datetime.timedelta(days=1)
        if d.weekday() < 5:
            return datetime.datetime.combine(d, datetime.time(9, 0))


def _next_slot_at(now=None):
    """空档期下一个动作点。"""
    now = now or datetime.datetime.now()
    hm = now.hour * 60 + now.minute
    if hm < 9 * 60:
        return _today_at(9, 0)
    if hm < 9 * 60 + 30:
        return _today_at(9, 30)
    if hm < 13 * 60:
        return _today_at(13, 0)
    if hm < 15 * 60:
        return _today_at(15, 0)   # 尾盘K线
    if hm < 15 * 60 + 10:
        return _today_at(15, 10)  # 收盘总结
    return next_weekday_0900(now)


def _sleep_until(dt, stop_check=None):
    now = datetime.datetime.now()
    delta = max((dt - now).total_seconds(), 1)
    _interruptible_sleep(delta, stop_check)


def _run_script(script, log=print, stop_check=None, extra=None):
    """子进程执行脚本并回显输出；返回是否成功。"""
    cmd = [sys.executable, script] + (extra or [])
    try:
        proc = subprocess.Popen(
            cmd, cwd=os.path.dirname(script),
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
            env={**os.environ, "PYTHONIOENCODING": "utf-8"})
        for line in proc.stdout:
            line = line.rstrip()
            if line:
                log("    | " + line)
        proc.wait()
        if proc.returncode != 0:
            log("  ✗ %s 失败 (exit=%d)" % (os.path.basename(script), proc.returncode))
            return False
        log("  ✓ %s 完成" % os.path.basename(script))
        return True
    except Exception as e:  # noqa: BLE001
        log("  ✗ %s 异常：%s" % (os.path.basename(script), e))
        ops_note("script_error", "%s: %s" % (script, e))
        return False


def _push_market_image(log=print, days=55):
    """生成并推送「大盘4指数归一化叠加图」（企微 image；失败仅留痕，不影响文本推送）。"""
    try:
        import _index_market_chart as imc
        ok, png = imc.render_png(days=days)
        if not ok:
            log("大盘图生成失败，跳过图片推送")
            return False
        sent = push_channel.send_image(png, load_notify_cfg())
        if sent:
            log("✓ 大盘4指数图已推送: %s" % os.path.basename(png))
        else:
            log("大盘图未推送（渠道不支持 / 未配置企微机器人）")
        return sent
    except Exception as e:  # noqa: BLE001
        log("大盘图推送异常：%s" % e)
        ops_note("index_img_error", repr(e))
        return False


def _push_pre_market(dry, log=print):
    """09:00 开盘前：大盘4指数归一化叠加图 + 强弱结论（每日一次，幂等由守护标记控制）。

    正文规则与 APK「大盘K线」一致：上证×科创50 优先级 + 深成/创业板佐证。
    """
    try:
        import _index_market_chart as imc
        text = imc.verdict_text()
        title = "🌅 开盘前大盘速览 %s" % datetime.date.today().strftime("%m-%d")
        if dry:
            log("[dry] 开盘前大盘速览（不推送）：\n" + text)
            return True
        sent_txt = _push_wechat(title, text, load_notify_cfg())
        sent_img = _push_market_image(log=log)
        return sent_txt or sent_img
    except Exception as e:  # noqa: BLE001
        log("开盘前大盘速览失败：%s" % e)
        ops_note("pre_msg_error", repr(e))
        return False


def run_pre_preheat(log=print, stop_check=None):
    """09:00 预热：K线增量下载 → 情报扫描(dry 暖缓存：美股隔夜/韩股开盘/快讯)。"""
    log("[09:00 预热] 下载K线增量 + 情报扫描暖缓存（美股/韩股开盘前）…")
    steps = ((PREP_DOWNLOAD, None),
             (os.path.join(HERE, "_market_scan.py"), ["--once", "--dry"]))
    for script, extra in steps:
        if stop_check is not None and stop_check():
            return False
        log("  ▶ %s" % os.path.basename(script))
        if not _run_script(script, log=log, stop_check=stop_check, extra=extra):
            return False
    rhythm_mark_flag("pre")
    log("[09:00 预热] 完成，等待 09:30 首轮选股")
    return True


def run_tail_kline(log=print, stop_check=None):
    """15:00 后最后一次K线拉取（仅下载收盘数据，不再选股）。"""
    log("[15:00 尾盘] 最后拉取一次当日K线（收盘定格）…")
    if _run_script(PREP_DOWNLOAD, log=log, stop_check=stop_check):
        rhythm_mark_flag("tail")
        log("[15:00 尾盘] K线拉取完成")
        return True
    ops_note("tail_fail", "尾盘K线拉取失败")
    return False


def run_self_review(log=print, stop_check=None):
    """15:00 尾盘K线定格后：记录当日选股入记忆账本 + 结算到期信号 + 刷新复盘摘要。

    失败不阻塞收盘总结（总结里若已有自检报告则展示，否则跳过该段）。
    """
    log("[15:00 自检] 记录当日选股 → 结算到期信号 → 刷新复盘摘要…")
    try:
        proc = subprocess.Popen(
            [sys.executable, SELF_REVIEW], cwd=HERE,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
            env={**os.environ, "PYTHONIOENCODING": "utf-8"})
        for line in proc.stdout:
            line = line.rstrip()
            if line:
                log("    | " + line)
        proc.wait()
        if proc.returncode == 0:
            rhythm_mark_flag("learn")
            log("[15:00 自检] 完成（记忆+结算已落盘）")
            return True
        ops_note("learn_fail", "选股自检退出码=%d" % proc.returncode)
    except Exception as e:  # noqa: BLE001
        log("选股自检异常：%s" % e)
        ops_note("learn_error", repr(e))
    log("[15:00 自检] 失败，30 秒后重试")
    return False


def _acc_round_picks(log=print):
    """把本轮候选并入当日累计命中（供收盘总结展示高频入选）。"""
    try:
        with open(OUT_DEFAULT, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        ops_note("acc_picks_fail", "读取本轮候选失败: %s" % e)
        return
    d = _load_rhythm()
    hit = d.setdefault("hit", {})
    for period, items in (data.get("groups") or {}).items():
        for it in items:
            sid = it.get("secid")
            if sid:
                m = hit.setdefault(period, {})
                m[sid] = m.get(sid, 0) + 1
    for it in data.get("prepared") or []:
        sid = it.get("secid")
        if sid:
            m = hit.setdefault("预备队", {})
            m[sid] = m.get(sid, 0) + 1
    _save_rhythm(d)


def _mark_round_count(slot):
    d = _load_rhythm()
    d.setdefault("rounds", {})
    d["rounds"][slot] = d["rounds"].get(slot, 0) + 1
    _save_rhythm(d)


def _do_round(slot, dry, use_ctx, log):
    """守护整轮：选股+推送。实仓段内嵌消息，有变化才展开（pos_advice=True）。"""
    now = datetime.datetime.now()
    label = "上午" if slot == "am" else "下午"
    n_next = _load_rhythm()["rounds"].get(slot, 0) + 1
    log("[%s] 整轮选股（%s 第%d轮）…" % (now.strftime("%H:%M:%S"), label, n_next))
    t0 = time.time()
    try:
        rc = run_once(dry=dry, candidates_key=None,
                      timed_push=not dry, use_ctx=use_ctx, pos_advice=True)
        log("[%s] 整轮完成 rc=%d（耗时 %.0fs）" % (
            datetime.datetime.now().strftime("%H:%M:%S"), rc, time.time() - t0))
        if rc == 0:
            rhythm_mark_round(slot)
            _mark_round_count(slot)
            _acc_round_picks(log)
        else:
            ops_note("round_fail", "%s rc=%d" % (slot, rc))
    except Exception as e:  # noqa: BLE001
        log("选股轮异常：%s" % e)
        ops_note("round_error", "%s: %s" % (slot, e))


def _push_eod_summary(dry, use_ctx, log):
    """15:10 收盘总结：当日选股汇总 + 持仓回顾 + 纪律 + 鼓励（无做T/买卖点指令）。"""
    try:
        cache = load_cache()
        industry = build_industry()
        ctx = build_context() if use_ctx else None
        data = build_candidates(cache, industry, ctx=ctx)
    except Exception as e:  # noqa: BLE001
        log("收盘总结 构建异常：%s" % e)
        ops_note("eod_build_error", repr(e))
        return False
    if not data:
        log("收盘总结：无有效数据（缓存为空？）")
        return False
    r = _load_rhythm()
    rounds = r.get("rounds", {})
    n_am, n_pm = rounds.get("am", 0), rounds.get("pm", 0)
    hits = r.get("hit", {})
    asof = data.get("asof", "")
    lines = ["交易日 asof=%s" % asof,
             "今日共 %d 轮选股（上午 %d / 下午 %d）" % (n_am + n_pm, n_am, n_pm)]
    # ① 当日主线候选（XML DAG，exe/APK 同源）
    try:
        with open(DAG_SCREEN_FILE, encoding="utf-8") as f:
            dag = json.load(f)
    except (OSError, ValueError):
        dag = None
    if dag and (dag.get("result") or {}):
        rows = []
        for period in ("超短", "短线", "中线", "长线"):
            its = (dag.get("result") or {}).get(period) or []
            if not its:
                continue
            names = ", ".join(
                (it.get("name") or (it.get("code") or "?")).strip()
                for it in its[:8])
            rows.append("%s(%d): %s" % (period, len(its), names))
        if rows:
            lines.append("⭐ 当日主线·XML DAG 选股\n  " + " | ".join(rows))
    # ② 当日多轮入选
    if hits:
        segs = []
        for period, sidmap in sorted(hits.items(), key=lambda kv: -sum(kv[1].values())):
            top = sorted(sidmap.items(), key=lambda kv: -kv[1])[:5]
            items_txt = ", ".join("%s×%d" % (_find_name(data, sid), c)
                                  for sid, c in top if c > 1)
            if items_txt:
                segs.append("%s: %s" % (period, items_txt))
        if segs:
            lines.append("📊 当日多轮入选（共振参考）")
            lines.extend("  · " + s for s in segs)
    # ②.5 自检复盘摘要（尾盘已入账本/结算，asof 一致才追加；失败不影响总结）
    try:
        with open(os.path.join(HERE, "_records", "_selfreview_report.json"),
                  encoding="utf-8") as f:
            sr = json.load(f)
        if sr and sr.get("asof") == asof and sr.get("text"):
            lines.append(sr["text"].strip())
    except (OSError, ValueError):
        pass
    # ③ 持仓回顾 + 纪律 + 鼓励
    pos = data.get("positions") or []
    pf = data.get("portfolio") or {}
    pnl_all = pf.get("pnl_pct") if pf else None
    if pos:
        state_cn = {"止损警戒": "破位风险", "减仓警戒": "仓位偏高", "减仓应对": "回调应对",
                    "加仓候选": "强度尚可", "持有": "持有观察", "持有观察": "观察",
                    "数据不足": "数据不足"}
        lines.append("💼 持仓回顾（%d 笔，整体%s）：" % (
            len(pos), ("%+.1f%%" % pnl_all) if pnl_all is not None else "数据不足"))
        for p in pos[:8]:
            pnl = ("%+.1f%%" % p["pnl_pct"]) if p.get("pnl_pct") is not None else "-"
            note = p.get("note") or ""
            note0 = note.splitlines()[0][:30] if note else ""
            lines.append("  %s %s(%s) 盈亏%s %s" % (
                state_cn.get(p["verdict"], p["verdict"]), p.get("name", ""),
                p.get("code", ""), pnl, note0))
        if pf and pf.get("alerts"):
            lines.append("⚠️ 组合纪律：")
            lines.extend("  " + a for a in pf["alerts"])
    else:
        lines.append("💼 今日无实仓持仓记录（未做持仓评估）")
    if pnl_all is not None:
        if pnl_all >= 0:
            cheer = "今天账户飘红，节奏很棒，继续守住纪律 👏"
        elif pnl_all > -2:
            cheer = "今天小幅波动，别着急——坚持既定纪律，修复往往就在两三个交易日内 💪"
        else:
            cheer = "今天回撤了些，辛苦了。按纪律控住仓位、稳住心态，市场会还你公道 🧘"
    else:
        cheer = "今天也完整跑完了选股流程，辛苦了，好好休息 🌙"
    lines.append("")
    lines.append("💬 " + cheer)
    content = "\n".join(lines)
    if dry:
        log("[dry] 收盘总结预览（不推送）:\n" + content)
        return True
    try:
        cfg = load_notify_cfg()
        ok_txt = _push_wechat("📅 收盘总结 %s" % asof, content, cfg)
        if ok_txt:
            # 收盘后附带「大盘4指数归一化叠加图」（图片单独一条，企微机器人支持；
            # 失败仅留痕，不影响总结本体）
            _push_market_image(log=log)
        return ok_txt
    except Exception as e:  # noqa: BLE001
        log("收盘总结 推送失败：%s" % e)
        ops_note("eod_push_error", repr(e))
        return False


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
    """盘段守护 v2（2026-09-06 方案A，用户确认）：

    09:00 预热（K线下载+情报扫描暖缓存，无需等到 09:30 才拉数据）
        → 09:30-11:30 每 15 分钟 9 轮（09:30 首次）
        → 午休不选股
        → 13:00-14:30 每 15 分钟 7 轮（之后不再盘中选股）
        → 15:00 尾盘最后一次 K 线拉取（仅下载）
        → 15:10 收盘总结：当日选股+持仓回顾+纪律+鼓励（无做T/买卖点指令）
    盘中轮推送不再含「实仓买卖/做T」建议；守护/选股错误写入 _daemon_ops.jsonl。
    interval 参数仅兼容旧调用，v2 固定 15 分钟整点轮。
    """
    log("盘段守护 v2：09:00 预热 → 09:30-11:30 每15分 → 13:00-14:30 每15分 "
        "→ 15:00 尾盘K线 → 15:10 收盘总结")
    while stop_check is None or not stop_check():
        now = datetime.datetime.now()
        # ── 周末：睡到下一交易日 09:00 ──
        if now.weekday() >= 5:
            nxt = next_weekday_0900(now)
            log("[%s] 周末 → 下一交易日 %s 09:00 预热" % (
                now.strftime("%m-%d %H:%M"), nxt.strftime("%m-%d")))
            _sleep_until(nxt, stop_check)
            continue
        slot = _slot_of(now)
        # ── 09:00-09:29 预热 ──
        if slot == "pre":
            if prep and rhythm_need_flag("pre"):
                if not run_pre_preheat(log=log, stop_check=stop_check):
                    log("[09:00 预热] 失败，2 分钟后重试")
                    _interruptible_sleep(120, stop_check)
                    continue
            # 开盘前大盘速览：4指数归一化叠加图 + 强弱结论（每日一次）
            if rhythm_need_flag("pre_msg"):
                _push_pre_market(dry, log)
                rhythm_mark_flag("pre_msg")
            _sleep_until(_today_at(9, 30), stop_check)
            continue
        # ── 交易盘段：首刷 → 固定 15 分钟轮 ──
        if slot in ("am", "pm"):
            if prep and rhythm_need_prep(slot):
                run_prep(slot, interval=interval, log=log, stop_check=stop_check)
                continue
            r = _load_rhythm()
            n_r = r.get("rounds", {}).get(slot, 0)
            last_at = r.get("round_at", {}).get(slot)
            do_round = (n_r == 0)   # 本段首轮：首刷完成后立即选股
            if not do_round and _is_quarter(now):
                do_round = True
                if last_at:
                    try:
                        lt = datetime.datetime.combine(
                            now.date(),
                            datetime.datetime.strptime(last_at, "%H:%M:%S").time())
                        if (now - lt).total_seconds() < 60:
                            do_round = False   # 同一分钟已轮过，防重复
                    except ValueError:
                        pass
            if do_round:
                _do_round(slot, dry, use_ctx, log)
                continue
            _interruptible_sleep(5, stop_check)
            continue
        # ── 空档（开盘前/午休/14:30 后）──
        hm = now.hour * 60 + now.minute
        if hm >= 15 * 60:
            if rhythm_need_flag("tail"):
                run_tail_kline(log=log, stop_check=stop_check)
                continue
            # 尾盘定格后 15:00-15:09 窗口：当日选股入账本 + 到期信号结算。
            # 自检失败仅降级（总结里无该段），绝不让 15:10 收盘总结被拖住。
            if hm < 15 * 60 + 10 and rhythm_need_flag("learn"):
                if not run_self_review(log=log, stop_check=stop_check):
                    _interruptible_sleep(30, stop_check)
                continue
            if rhythm_need_flag("sum"):
                if hm < 15 * 60 + 10:
                    _sleep_until(_today_at(15, 10), stop_check)
                    continue
                if _push_eod_summary(dry, use_ctx, log):
                    rhythm_mark_flag("sum")
                    log("✓ 收盘总结完成")
                else:
                    log("收盘总结失败，1 分钟后重试")
                    _interruptible_sleep(60, stop_check)
                continue
            # 当日流程完毕 → 次日 09:00
            _sleep_until(next_weekday_0900(now), stop_check)
            continue
        nxt = _next_slot_at(now)
        if nxt:
            log("[%s] 空档 → 下一动作 %s" % (
                now.strftime("%H:%M:%S"), nxt.strftime("%m-%d %H:%M")))
            _sleep_until(nxt, stop_check)
        else:
            _interruptible_sleep(60, stop_check)
    log("🛑 守护已停止")


def main():
    ap = argparse.ArgumentParser(description="PC 候选清单发布（选股→通知→COS）")
    ap.add_argument("--once", action="store_true", help="执行一次")
    ap.add_argument("--daemon", action="store_true", help="盘段守护 v2（固定时刻表，见 daemon_serve 注释）")
    ap.add_argument("--interval", type=int, default=900, help="兼容参数（v2 固定 15 分钟整点轮，忽略此值）")
    ap.add_argument("--dry", action="store_true", help="不通知不上传（调试）")
    ap.add_argument("--key", default=None, help="COS candidates_key，默认 stockanalysis/quant/candidates.json")
    ap.add_argument("--timed", action="store_true", help="定时推送模式（单次执行也推送整轮概览）")
    ap.add_argument("--no-pos", action="store_true",
                    help="推送不含实仓段（默认随整轮消息内嵌，有变化才展开详情）")
    ap.add_argument("--no-ctx", action="store_true", help="跳过市场上下文/共振/实仓（纯形态快速选股）")
    ap.add_argument("--no-prep", action="store_true",
                    help="关闭盘段首刷/预热/尾盘(下载+XML DAG 选股)，只做固定轮次选股")
    args = ap.parse_args()
    if args.daemon:
        print("盘段守护 v2：09:00 预热 → 09:30-11:30 每15分 → 13:00-14:30 每15分 "
              "→ 15:00 尾盘K线 → 15:10 收盘总结")
        daemon_serve(prep=not args.no_prep, interval=args.interval,
                     dry=args.dry, use_ctx=not args.no_ctx)
        return 0
    return run_once(dry=args.dry, candidates_key=args.key, timed_push=args.timed,
                    use_ctx=not args.no_ctx, pos_advice=not args.no_pos)


if __name__ == "__main__":
    sys.exit(main())
