# -*- coding: utf-8 -*-
"""PC 候选清单发布：四周期选股(异动共振) → 双端命中对比 → 微信通知 → 上传 COS → 守护轮询。

v11 盘中共振版：交易时段每 10 分钟一轮(v3 守护)，选股不再只看形态——先收集盘中市场上下文
(_market_context.py：实时板块资金流 / 日榜 / 连续上榜焦点 / Android 实仓 / exe 命中)，
对每只候选做「技术买点 + 资金/轮动/热度共振」综合排序；推送同时并列 CodeBuddy 命中
与 exe(AutoQuant screen_report) 命中，并附实仓持仓的持/加/减/止损建议。

用法：
  python _publish_candidates.py --once            # 跑一次（选股+对比+通知+上传）
  python _publish_candidates.py --once --dry      # 只选股+本地输出，不上传不通知
  python _publish_candidates.py --daemon          # 每 600 秒(10分钟)轮询，有更新才推(完整推)
  python _publish_candidates.py --daemon --interval 600 --no-ctx
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
from _full_cycle_backtest import INDEXES, load_cache, limit_up_flag, market_state  # noqa: E402
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
    from _technicals import rich_tag as _tech_rich  # noqa: E402
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
# 每日节奏情报（_daily_intel.py 产出：08:00 宏观/美股、09:00 亚太 → 利好利空板块 + 候选）
DAILY_INTEL_FILE = os.path.join(HERE, "data", "_daily_intel.json")
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
    # 涨停标注：候选当日收盘涨停（无法买入）→ limit_up=True，供推送标注并置后展示
    try:
        for _grp in list(groups.values()) + [prepared]:
            for _x in _grp:
                _sid = _x.get("secid") or ""
                _ent = cache.get(_sid) or {}
                _x["limit_up"] = limit_up_flag(
                    _ent.get("snaps"), _sid, _ent.get("name") or "", asof)
    except Exception:
        pass
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
    data = _quality_gate(data, cache)
    return data


def _gate_weak(it, cache):
    """形态质量闸门判定：SAR 绿 + 现价跌破 MA5 + 5日动量为负 → 明确走弱（阴跌/均线粘合向下）。
    2026-09-08 用户反馈华能水电等『绿线+粘合向下』仍进中长线候选，加此闸门拦截。"""
    if not it or not it.get("secid"):
        return False
    try:
        snaps = _find_cache_snaps(cache, it["secid"])
        closes = [float(x["close"]) for x in snaps if x.get("close")]
        if len(closes) < 35:
            return False
        ma5 = sum(closes[-5:]) / 5.0
        chg5 = (closes[-1] / closes[-6] - 1) * 100.0 if len(closes) > 6 else 0.0
        if closes[-1] >= ma5 or chg5 >= 0:
            return False
        sar = ((_tech_analyze(snaps) if _TECHS_OK else {}).get("sar") or {})
        return sar.get("dir") == "DOWN"
    except Exception:  # noqa: BLE001
        return False


def _quality_gate(data, cache):
    """候选形态质量闸门：剔除明确走弱票（仅作用于展示/上传的 groups/prepared，
    不动评分模型与 XML DAG）。剔除的票名记入日志，便于核对。"""
    removed = []
    for p, items in list((data.get("groups") or {}).items()):
        keep = []
        for it in items:
            if _gate_weak(it, cache):
                removed.append("%s/%s" % (p, it.get("name") or it.get("secid")))
            else:
                keep.append(it)
        data["groups"][p] = keep
    pre_keep = []
    for it in (data.get("prepared") or []):
        if _gate_weak(it, cache):
            removed.append("预备队/%s" % (it.get("name") or it.get("secid")))
        else:
            pre_keep.append(it)
    data["prepared"] = pre_keep
    if removed:
        print("[质量闸门] 剔除 %d 只走弱候选: %s"
              % (len(removed), "、".join(removed[:12])))
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
            parts = _tech_rich(snaps)
            if not parts:
                return ""
            q = []
            if _QUOTE_OK:
                s0 = snaps[-1]
                tn = s0.get("turnover") if s0 and isinstance(s0, dict) else None
                for t in _tech_quote(turnover=tn, volume_ratio=_tech_vr(snaps)):
                    # 只留紧凑档位（"换手2.1%"），去冗长点评尾巴
                    q.append(t.split("·")[0])
            if q:
                parts = parts.split()
                if len(parts) > 5:
                    parts = parts[:5]
                return " ".join(parts + q)
            return parts
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

    2026-09-11：所有文本推送统一写入 market_data.db `push_record` 账本
    （slot 按当前盘段，kind=text），满足「推送的股票/资讯存储到数据库」。
    """
    err = ""
    ok = False
    try:
        ok = push_channel.push(title, content, cfg)
    except Exception as e:  # noqa: BLE001
        err = "%s: %s" % (type(e).__name__, e)
        raise
    finally:
        try:
            import _market_db as mdb
            conn = mdb.get_conn()
            try:
                mdb.save_push_record(conn, slot=_slot_of() or "", kind="text",
                                     title=title, ok=ok, err=err, content=content)
            finally:
                conn.close()
        except Exception:  # noqa: BLE001
            pass
    return ok


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
        # ── 2026-09-13 强化：归档降级管线 + 老化告警 ──
        # 事故复盘：09-12 12:05 的 dag_screen_latest.json（n_direction/n_base_guard
        # 未实现透传）被当 9.11 盘后推送；consumers 此前对归档是否"残缺/过期"无感。
        _miss = dag.get("bypass_missing") or []
        if dag.get("degraded") and _miss:
            lines.append("⚠️ 降级管线: 本次 DAG 缺 %d 个未实现节点(%s)，结果可能与设计不符，谨慎采纳" %
                         (len(_miss), "、".join(_miss[:6]) + ("…" if len(_miss) > 6 else "")))
        _built = dag.get("built_at")
        if _built:
            try:
                _age = (datetime.datetime.now() - datetime.datetime.strptime(
                    _built, "%Y-%m-%d %H:%M:%S")).total_seconds()
                if _age > 7200:
                    lines.append("⚠️ 归档老化: built_at=%s cache_last=%s（>2h 未重跑）" %
                                 (_built, dag.get("cache_last", "")))
            except Exception:
                pass
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


def _find_name(data, secid, extra_names=None):
    """secid → 「名称+代码」；名称缺失时依次回退 传入名表 / 全池缓存名。

    2026-09-11：收盘/午间总结的「多轮入选」是全天累计命中，命中 secid 到结算时
    往往已不在当轮 data(prepared/groups) 内；此前直接返回裸 secid → 用户看到
    「预备队只有代码没名称」。现由调用方传入 extra_names（rhythm.hit_name 累计名
    ＋全池缓存名）兜底，只在彻底查不到时才退化。
    """
    for items in (data.get("groups") or {}).values():
        for it in items:
            if it.get("secid") == secid and it.get("name"):
                return "%s%s" % (it.get("name"), secid[2:])
    for it in data.get("prepared") or []:
        if it.get("secid") == secid and it.get("name"):
            return "%s%s" % (it.get("name"), secid[2:])
    nm = (extra_names or {}).get(secid)
    if nm:
        return "%s%s" % (nm, secid[2:])
    return secid[2:] or secid


def _name_index(cache, extra=None):
    """全池缓存名 + 累计命中名 → {secid: 名称}（供 _find_name 兜底，一次构建复用）。"""
    idx = {}
    for sid, ent in (cache or {}).items():
        nm = (ent or {}).get("name")
        if nm:
            idx[sid] = nm
    for sid, nm in (extra or {}).items():
        if nm and not idx.get(sid):
            idx[sid] = nm
    return idx


# ── 5. 主流程 ──────────────────────────────────────────────────────────
def _find_item_by_secid(data, secid):
    for items in (data.get("groups") or {}).values():
        for it in items:
            if it.get("secid") == secid:
                return it
    return None


# ── 统一推送表格（2026-09-12 用户确认）：五段选股 + 实仓镜像共用同一套列 ──
# 此前各段表头各行其是（主线/smalltool 12 列、ETF 全行业扫描 6 列、ETF top5 9 列），
# 无法横向对比。用户决定：把原「ETF 全行业扫描」独有的 距60日高 / 今日 / 所属ETF / 备注
# 上提为公共列，「ETF top5」的 RSI/SAR/MACD/OBV/均线粘合/换手/量比 亦为公共技术列 ——
# 所有选股结果同一套列，在同一张长图 / 同一份 CSV 里逐列对齐可直接横比。
# 2026-09-12 二次改版：曾把「趋势图谱 / 星后形态 / 趋势图」三列合并为一列「趋势形态」，
#   导致 a1~a5 为 14 列、b 实仓镜像为 16 列（多「建议 / 盈亏%」两列）→ 两段列数差 2、
#   列位整体错开、长图列宽对不齐。
# 2026-09-12 三次定稿（用户确认）：统一为 16 列 —— 形态三列恢复分列（信息不丢），
#   b 段「建议 / 盈亏%」不再单开两列，并入「备注」格 → a1~a5 与 b 完全同表头。
# 2026-09-12 四次定稿（用户确认）：再增 1 列「机构股」→ **17 列**，与 APK/exe 的
#   「技术假设(PickTechTable)」同口径；盘中选股与收盘复盘全部用这一套 UI 模板。
#   机构股取值 = A·机构加仓 / B·机构参与 / C·散户票 / —（无季报数据），
#   来源 data/_inst_holdings.json（smalltools/_inst_holdings.py 每周 --update-pool 刷新，
#   usecase inst_holding）；口径见《机构持续加仓自动选股池.txt》与 _inst_holdings 模块。
# 2026-09-13 五次定稿（用户要求）：再增 1 列「国家队」→ **18 列**（与 APK/exe 的
#   national_flow 节点同一份资产 data/_national_flow_hist.json，推送侧只读不算）。
#   国家队取值 = ▲国 06-30（国家队流入+披露日）/ ▼基 03-31（大基金流出）/ 退社 12-31（社保退出）
#   / =国·基·社（有披露但持稳）/ —（无披露记录）；最多并列 2 条，均带季报披露日。
#   用户诉求原话：「要能看到是否有国家队/大基金/机构买入或者卖出，以及买入卖出的时间点」。
# 2026-09-13 同日 v2（用户追问「它们会不会**直接买某只股票**，而不是只买 ETF？」）：
#   该列扩为**三类直接持股证据合流**（详见 _nat_cell 上方区块）：
#   告▲基 04-24（公告级：增减持/定增获配，T+1，最新最及时）/ 锁基 06-30（锁定持股=十大股东
#   可见而流通榜不可见的限售部分）/ ▲国 06-30（季报十大流通股东，原口径）/ 史▼基 25-09
#   （超 365 天窗口的公告历史，仅给时间点、不计分）。对应资产侧新增 blk/ann 两条通道。
# 列口径 = [股票名称, 代码] + 16 格 cells
#          （距60日高/今日/所属ETF 3 + 趋势图谱/星后形态 2 + _tech_cells 7
#            + 趋势图 1 + 机构股 1 + 国家队 1 + 备注 1）。
_TABLE_HEAD = ["股票名称", "代码", "距60日高", "今日", "所属ETF",
               "趋势图谱", "星后形态", "RSI", "SAR", "MACD", "OBV",
               "均线粘合", "换手", "量比", "趋势图", "机构股", "国家队", "备注"]
_N_CELLS = len(_TABLE_HEAD) - 2          # 16：距60日高 … 备注
_I_INST = _TABLE_HEAD.index("机构股") - 2     # cells 下标 13（趋势图 12 之后）
_I_NAT = _TABLE_HEAD.index("国家队") - 2       # cells 下标 14（机构股 13 之后）

# ── v11b 趋势图谱列（2026-09-08）：识别「连跌x天→十字星→星后大/小阴阳」K线形态 ──
# 口径与 dip_crossstar_stat.py（十字星分歧四形态·18年市场库）完全一致：
#   十字星 = |收-开| ≤ 0.20×(高-低)；大/小K分界 = ±1.5%；星前连跌 = 日涨跌<0 的连续天数。
# 命中则「趋势图谱」填 连跌x天→十字星，「星后形态」填星后第y天走出的大/小阴阳（未走出=今日星待变盘）。
_STAR_DOJI_THR = 0.20
_STAR_BIG_THR = 1.5
_STAR_WINDOW = 7


def _snap_chg_pct(snaps, i):
    """第 i 根较前收涨跌%。缓存 snap 未必带 changePct → 自算兜底。"""
    if i <= 0:
        return 0.0
    prev = snaps[i - 1].get("close") or 0.0
    cur = snaps[i].get("close") or 0.0
    return (cur / prev - 1.0) * 100.0 if prev else 0.0


def _snap_chg(snaps, i):
    c = snaps[i].get("changePct")
    if c is None:
        c = _snap_chg_pct(snaps, i)
    try:
        return float(c)
    except (TypeError, ValueError):
        return _snap_chg_pct(snaps, i)


def _is_doji_snap(s):
    hi, lo = s.get("high"), s.get("low")
    if not hi or not lo:
        return False
    try:
        hi, lo = float(hi), float(lo)
    except (TypeError, ValueError):
        return False
    if hi <= lo:
        return False
    return abs(float(s.get("close") or 0.0) - float(s.get("open") or 0.0)) <= _STAR_DOJI_THR * (hi - lo)


def _down_days_before(snaps, i, cap=15):
    """从第 i 根往回（含 i）数连续阴跌天数；口径=dip_crossstar.down_days（changePct<0 计一天）。"""
    n = 0
    j = i
    while j >= 0 and n < cap:
        if _snap_chg(snaps, j) >= 0:
            break
        n += 1
        j -= 1
    return n


def _k_form_name(chg):
    """涨跌% → 大阴/小阴/大阳/小阳（±1.5% 分界，与 dip_crossstar.classify 同口径）。"""
    if chg <= -_STAR_BIG_THR:
        return "大阴"
    if chg < 0:
        return "小阴"
    if chg >= _STAR_BIG_THR:
        return "大阳"
    if chg > 0:
        return "小阳"
    return None


def _trend_cells(snaps):
    """趋势图谱 2 格。最近 7 根内从最新往回找「星前连跌≥1」的十字星（取最近命中）：
    [图谱="连跌x天→十字星", 星后形态="星后第y天 大阴/小阴/大阳/小阳"｜星=最新日="今日星·待变盘"]。
    未匹配返回 ["",""]（渲染为 —），只对匹配的候选填充。"""
    try:
        snaps = snaps or []
        n = len(snaps)
        if n < 25:
            return ["", ""]
        for i in range(n - 1, max(n - 1 - _STAR_WINDOW, 20) - 1, -1):
            if not _is_doji_snap(snaps[i]):
                continue
            x = _down_days_before(snaps, i - 1)  # 星前连跌天数（不含星日本身）
            if x < 1:
                continue  # 高位/横盘十字星（无前跌）不属于连跌后企稳场景，继续找更早的星
            graph = "连跌%d天→十字星" % x
            after = ""
            for k in range(i + 1, min(i + 1 + _STAR_WINDOW, n)):
                f = _k_form_name(_snap_chg(snaps, k))
                if f:
                    after = "星后第%d天%s" % (k - i, f)
                    break
            if not after:
                after = "今日星·待变盘" if i == n - 1 else "星后未走大K"
            return [graph, after]
        return ["", ""]
    except Exception:
        return ["", ""]


def _tw(s):
    """显示宽度：CJK 算 2 个半角位。"""
    return sum(2 if ord(c) > 127 else 1 for c in str(s or ""))


def _tech_cells(tech="", snaps=None):
    """指标摘要串(rich_tag: RSI.. SAR.. MACD.. OBV.. 均线粘合..) + 可选 snaps
    → 7 个表格单元格 [RSI, SAR, MACD, OBV, 均线粘合, 换手, 量比]。
    摘要串缺失时用 snaps 重算；仍缺的置 ''（渲染时显示 —）。"""
    t = tech or ""
    if not t and snaps:
        try:
            t = _tech_rich(snaps)
        except Exception:
            t = ""
    d = {"rsi": "", "sar": "", "macd": "", "obv": "", "sq": "", "to": "", "vr": ""}
    for tok in str(t).split():
        if tok.startswith("RSI"):
            d["rsi"] = tok[3:].lstrip(": ")
        elif tok.startswith("SAR"):
            d["sar"] = tok[3:]
        elif tok.startswith("MACD"):
            d["macd"] = tok[4:]
        elif tok.startswith("OBV"):
            d["obv"] = tok[3:]
        elif "粘合" in tok:
            d["sq"] = "粘合"
        elif tok.startswith("MA多头"):
            d["sq"] = "多头"
        elif tok.startswith("MA偏空"):
            d["sq"] = "偏空"
        elif tok.startswith("换手"):
            m = re.search(r"([\d.]+)%", tok)
            if m:
                d["to"] = m.group(1) + "%"
        elif tok.startswith("量比"):
            m = re.search(r"([\d.]+)", tok)
            if m:
                d["vr"] = m.group(1)
    # 换手/量比在 tech 串缺时用 snaps 补齐
    if (not d["to"] or not d["vr"]) and snaps:
        try:
            last = snaps[-1] or {}
            if not d["to"] and last.get("turnover"):
                d["to"] = "%.1f%%" % float(last["turnover"])
            if not d["vr"]:
                vr = _tech_vr(snaps) if _QUOTE_OK else None
                if vr:
                    d["vr"] = "%.1f" % vr
        except Exception:
            pass
    return [d["rsi"], d["sar"], d["macd"], d["obv"], d["sq"], d["to"], d["vr"]]


# ── 趋势图列（2026-09-10）：经典K线形态匹配 → 方向(上涨/中性/下跌) + 形态名 ──
# 口径＝XML DAG 主线引擎 usecase_pipeline._trend_match_3way（与 APK TrendClassGate 同口径）；
# 形态库＝assets/trend_charts/index.html（早晨之星/看涨吞没/红三兵/黄昏之星/三乌鸦…）。
_UP_MOD = None


def _up_module():
    """惰性导入 XML DAG 主线引擎 usecase_pipeline（与 XML 同居 assets/usecases）。"""
    global _UP_MOD
    if _UP_MOD is None:
        p = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main",
                                          "assets", "usecases"))
        if p not in sys.path:
            sys.path.insert(0, p)
        import usecase_pipeline as up  # noqa: E402
        _UP_MOD = up
    return _UP_MOD


def _trend_chart_cell(snaps):
    """趋势图 1 格：『↑上涨·早晨之星 / →中性 / ↓下跌·三乌鸦』。

    口径＝`_trend_match_3way`（经典K线形态库：早晨之星/看涨吞没/红三兵/黄昏之星/三乌鸦…）
    + 方向标签；与「趋势图谱 / 星后形态」两列（十字星口径）相互独立、各自成列（2026-09-12
    三次定稿恢复三列分列）。K线不足/异常 → —。
    """
    try:
        win = snaps or []
        if len(win) < 30:
            return "—"
        m = _up_module()._trend_match_3way(win)
        lab = m.get("label") or "中性"
        nm = m.get("bull") or m.get("bear") or ""
        arrow = {"上涨": "↑", "下跌": "↓"}.get(lab, "→")
        return "%s%s%s" % (arrow, lab, ("·" + nm) if nm else "")
    except Exception:  # noqa: BLE001
        return "—"


def _pos60_cell(snaps):
    """距60日高（负=低于60日高点；口径同 _etf_holdings._pos60，全表统一）。"""
    try:
        import _etf_holdings as _eh
        v = _eh._pos60(snaps or [])
    except Exception:  # noqa: BLE001
        v = None
    return ("%+.1f%%" % v) if isinstance(v, (int, float)) else ""


def _day_pct_cell(snaps):
    """今日涨跌%（最新K收盘口径；口径同 _etf_holdings._day_pct，全表统一）。"""
    try:
        import _etf_holdings as _eh
        v = _eh._day_pct(snaps or [])
    except Exception:  # noqa: BLE001
        v = None
    return ("%+.1f%%" % v) if isinstance(v, (int, float)) else ""


_IND_MAP = None


def _industry_map():
    """{6位代码: 东财行业名}（进程内缓存；供「所属ETF」按板块关键词回填）。"""
    global _IND_MAP
    if _IND_MAP is None:
        try:
            import _industry_map as _im
            _IND_MAP = _im.build_industry() or {}
        except Exception:  # noqa: BLE001
            _IND_MAP = {}
    return _IND_MAP


def _etf_tags(codes, industries=None, strict=False):
    """6位代码集合 → {code: 所属ETF}。

    两级口径（2026-09-12 用户确认「按板块关键词回填」）：
      ① 命中核心 ETF 前十大重仓矩阵（data/_etf_holdings.json）→ 直接用它；
      ② 未覆盖 → 用该票的东财行业名去 THEME_RULES 关键词匹配，回填对应行业 ETF 简称；
      ③ 仍空 → ""（渲染为 —）。
    `industries` = {code: 行业名}；`strict=True` 时只认 ①（实仓镜像核对用）。
    """
    codes = list(codes or [])
    try:
        import _etf_holdings as _eh
        m = _eh.etf_tag_map() or {}
    except Exception:  # noqa: BLE001
        _eh, m = None, {}
    out = {}
    ind_map = _industry_map() if industries is None else industries
    for c in codes:
        tag = m.get(c, "")
        if not tag and not strict and _eh is not None:
            ind = ind_map.get(c) or ""
            if ind and ind != "未知":
                tag = _eh.industry_to_etf(ind)
        out[c] = tag
    return out


_INST_MAP = {"key": None, "data": {}}


def _inst_map():
    """机构判定映射 {code: (grade, label, score, verdict)}（按 data/_inst_holdings.json mtime 缓存）。

    数据资产由 smalltools/_inst_holdings.py 每周 --update-pool 刷新（东财机构持股一览表 +
    股东户数）；缺失或解析失败时返回 {}，调用方降级为「—」，不阻塞推送。
    """
    try:
        import _inst_holdings as _I
    except Exception:  # noqa: BLE001
        return {}
    try:
        key = os.path.getmtime(_I.OUT_FILE)
    except OSError:
        return {}
    if _INST_MAP["key"] == key:
        return _INST_MAP["data"]
    try:
        d = _I.load_cache()
        m = {c: (v.get("grade") or "", _I.grade_label(v), v.get("score"),
                 v.get("verdict") or "") for c, v in (d.get("stocks") or {}).items()}
        _INST_MAP.update({"key": key, "data": m})
        return m
    except Exception:  # noqa: BLE001
        return {}


def _code6(code):
    c = str(code or "").strip().lower()
    if c[:2] in ("sh", "sz", "bj"):
        c = c[2:]
    return c[-6:] if len(c) >= 6 else c


def _inst_cell(code):
    """「机构股」单元格：A·机构加仓 / B·机构参与 / C·散户票 / —（无季报数据）。"""
    v = _inst_map().get(_code6(code))
    txt = v[1] if v else "—"
    if txt[:2] == "C·" and _nat_map().get(_code6(code)):
        return txt + "｜国持"
    return txt


# ── 「国家队」列（2026-09-13 新增；同日 v2 扩为「直接持股证据链」）────────────────
# 用户要求：推送里要能直接看到「国家队 / 大基金 / 社保」是买还是卖、以及**时间点**
#          —— 并且要回答「它们会不会**直接买某只股票**（而不是只买 ETF）」。
# 答：会。ETF 只是汇金 2015 后的主通道；社保（委托组合）、大基金（定增/战投）、
#     证金（2015 救市资管计划）基本都是**直接持股个股**。因此本列合流三类证据，
#     数据源 = data/_national_flow_hist.json 的 snap 字段（_national_flow.build_snapshot
#     预计算，与 APK/exe 的 national_flow 节点同一份资产、同一口径，推送侧只读不算）。
#
#   ① 告▲基 04-24  公告级：股东增减持 / 定增获配公告（**T+1**，比季报快 1-3 个月）
#   ② 锁基 06-30    锁定持股：十大股东榜可见、十大流通榜**不可见**＝限售/非流通
#                   （大基金定增锁定 18 个月、战投、发起人股）——原口径的硬缺口
#   ③ ▲国 06-30     季报十大流通股东进出（原口径，②③为季报披露日）
#   ④ 史▼基 25-09   公告历史（超 365 天窗口，只给**时间点**、不计分）
#   「—」= 三类通道均无记录。
#   符号：▲流入 ▼流出 退=退出 =持稳；前缀 告=公告级 锁=锁定持股 史=历史公告。
_NAT_MAP = {"key": None, "snap": {}}
_NAT_SYM = {"流入": "▲", "流出": "▼", "退出": "退", "持稳": "="}
_ANN_SYM = {"增持": "▲", "减持": "▼"}
_NAT_KIND = {"nat": "国", "big": "基", "ss": "社", "nb": "北", "": ""}
_NAT_LAB = (("nat", "国"), ("big", "基"), ("ss", "社"))
_NAT_ASSET = os.path.join(ROOT, "data", "_national_flow_hist.json")


def _nat_map():
    """国家队快照 {code: {...}}（按资产 mtime 缓存）；缺失时返回 {}，不阻塞推送。

    资产由 smalltools/_national_flow.py 维护（与 APK/exe 的 national_flow 节点同一份）。
    """
    try:
        key = os.path.getmtime(_NAT_ASSET)
    except OSError:
        return {}
    if _NAT_MAP["key"] == key:
        return _NAT_MAP["snap"]
    snap = {}
    try:
        with open(_NAT_ASSET, encoding="utf-8") as f:
            snap = (json.load(f) or {}).get("snap") or {}
    except Exception as e:  # noqa: BLE001
        ops_note("nat_map_fail", "%s: %s" % (type(e).__name__, e))
        snap = {}
    _NAT_MAP.update({"key": key, "snap": snap})
    return snap


def _nat_day(d):
    """披露/公告日 → 单元格里的短日期（MM-DD）。"""
    d = str(d or "")[:10]
    return (" " + d[5:]) if len(d) >= 7 else ""


def _nat_cell(code):
    """「国家队」单元格 = 直接持股证据链（时效性优先，最多 4 段，每段都带时间点）。

    ① 告▲基 04-24（公告级，T+1，参与计分）  ② 锁基 06-30（锁定/限售持股）
    ③ ▲国 06-30（季报十大流通股东）          ④ 史▼基 25-09（超窗口公告，仅时间点）
    详见上方 _NAT_* 区块注释；「—」= 三类通道均无记录。
    """
    v = _nat_map().get(_code6(code))
    if not v:
        return "—"
    fresh, quarter = [], []
    # ① 公告级（最新、最及时；只有窗口内才计分，由 annFresh 标记）
    ast = v.get("annState")
    if ast in ("流入", "流出"):
        fresh.append("告%s%s%s" % (_NAT_SYM.get(ast, ""),
                                 _NAT_KIND.get(v.get("annKind") or "", ""),
                                 _nat_day(v.get("annNotice"))))
    # ② 锁定持股（十大股东可见 / 十大流通榜不可见 = 限售、定增锁定中）
    lst = v.get("lockState")
    if lst in ("流入", "流出"):
        fresh.append("锁%s%s" % (_NAT_KIND.get(v.get("lockKind") or "", ""),
                               _nat_day(v.get("lockNotice"))))
    # ③ 季报十大流通股东（原口径）
    for k, short in _NAT_LAB:
        st = v.get(k + "State")
        if st not in ("流入", "流出", "退出"):
            continue
        quarter.append("%s%s%s" % (_NAT_SYM.get(st, ""), short,
                                 _nat_day(v.get(k + "Notice") or v.get(k + "End"))))
    parts = fresh[:2] + quarter[:2]
    # ④ 公告历史（超出 365 天窗口）：给出「最后一次直接买卖」的时间点，明确标注为「史」
    if not fresh and v.get("annNotice"):
        parts.append("史%s%s%s" % (_ANN_SYM.get(v.get("annDir") or "", "·"),
                                 _NAT_KIND.get(v.get("annKind") or "", ""),
                                 _nat_day(v.get("annNotice"))))
    return " ".join(parts[:4]) if parts else "—"


def _national_etf_lines(max_lines=4):
    """🏛 国家队（汇金）ETF 份额摘要 —— 直接读 _etf_share_signal.json 的 judge 段。

    2026-09-13 新增（用户要求：ETF 页要能看到国家队在买还是在卖 + 时间点 + 判断）。
    资产由 smalltools/_etf_share_flow.py 维护：
      份额(gmbd 季度) + 日频快照(push2 f84) + 持有人结构(cyrjg) + 年报 §9.2 前十名持有人(点名汇金)。
    缺失时返回 []，不影响推送。
    """
    try:
        with open(os.path.join(ROOT, "data", "_etf_share_signal.json"), encoding="utf-8") as f:
            sig = json.load(f) or {}
    except Exception:  # noqa: BLE001
        return []
    j = sig.get("judge") or {}
    if not j:
        return []
    mark = {"买入/承接": "🟢买入/承接", "卖出/派发": "🔴卖出/派发"}.get(
        j.get("verdict") or "", "⚪" + (j.get("verdict") or "?"))
    out = ["🏛 国家队ETF份额（汇金动向）",
           "  判断：%s（置信 %s）｜口径：份额↓+价格↑=高位派发（在卖）；份额↑+价格↓=低位承接（在买）"
           % (mark, j.get("level") or "?")]
    for ln in (j.get("lines") or [])[:max_lines]:
        out.append("  · " + ln)
    return out


_INST_POOL_OUT = os.path.join(ROOT, "data", "_inst_pool.json")
_INST_POOL_ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "data", "_inst_pool.json")


def _write_inst_pool(secs, asof=""):
    """把本轮全部表（五段选股 + 实仓镜像）的股票汇总落盘 data/_inst_pool.json。

    与 data/_inst_holdings.json 同目录，供 usecase `inst_holding` 的 `inst_pool_build` 节点读取
    （APK / exe 双端），实现「推送选股池 → 机构判定 → 多理论止损」离线闭环。同时镜像到
    assets/data 供 APK 内置读取。失败静默（不阻塞推送）。
    """
    try:
        rows, idx = [], {}
        for s in (secs or []):
            title = str(s.get("title") or "")
            src = "选股池"
            for kw, tag in (("实仓", "实仓"), ("top5", "ETFtop5"), ("top 5", "ETFtop5"),
                            ("全行业扫描", "ETF全行业扫描"), ("ETF 当日", "ETF当日"),
                            ("smalltool", "三周期选股"), ("DAG", "三周期选股")):
                if kw in title:
                    src = tag
                    break
            for r in (s.get("rows") or s.get("body") or []):
                if not isinstance(r, dict):
                    continue
                code = _code6(r.get("code"))
                if len(code) != 6 or not code.isdigit():
                    continue
                name = str(r.get("name") or "").lstrip("·🔒🆕🟡√").strip()
                row = idx.get(code)
                if row is None:
                    idx[code] = {"code": code, "name": name, "from": src, "entry": 0}
                    rows.append(idx[code])
                    continue
                if src and src not in row["from"]:
                    row["from"] = (row["from"] + "+" + src) if row["from"] else src
                if name and not row["name"]:
                    row["name"] = name
        out = {"asof": asof,
               "updated": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
               "n": len(rows), "rows": rows,
               "source": "五段选股(三周期+ETF全行业扫描+ETFtop5)+实仓"}
        for p in (_INST_POOL_OUT, _INST_POOL_ASSET):
            try:
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as f:
                    json.dump(out, f, ensure_ascii=False, indent=1)
            except OSError:
                continue
        return out
    except Exception as e:  # noqa: BLE001
        ops_note("inst_pool_write_fail", "%s: %s" % (type(e).__name__, e))
        return None


def _unified_cells(snaps, tech="", etf_tag="", note="", tech_cells=None,
                   p60=None, day=None, note_cap=26, inst=None, nat=None):
    """公共 16 单元格（五段选股 / 实仓镜像共用同一口径）。

    距60日高 / 今日 / 所属ETF / 趋势图谱 / 星后形态 / RSI / SAR / MACD / OBV /
    均线粘合 / 换手 / 量比 / 趋势图 / 机构股 / 国家队 / 备注。

    - tech_cells：外部已算好的 7 格技术列（如 _etf_holdings._pk_tech_cells），优先；
    - p60 / day：外部已持有的原始数值（ETF 段来自持仓覆盖 + 实时行情），优先于 snaps 重算；
    - 趋势图谱 / 星后形态：十字星口径（_trend_cells），未匹配 → 空（渲染为 —）；
    - 趋势图：经典K线形态 + 方向（_trend_chart_cell，_trend_match_3way 口径）；
    - inst：「机构股」格（None 时不查表，直接给「—」；传 "" 同—）；
    - nat：「国家队」格（2026-09-13 新增，见 _nat_cell）——**直接持股证据链**：
      公告级(告▲基 04-24)/锁定持股(锁基 06-30)/季报十大流通股东(▲国 06-30)/公告历史(史▼基 25-09)；
    - note 里的换行会被压平（CSV 单元格内不能带换行、长图也不画多行），并截断到 note_cap 字；
      实仓段把「建议 / 盈亏%」并入备注格，故传更大的 note_cap 以保信息完整。
    """
    if p60 is None:
        p60_s = _pos60_cell(snaps)
    else:
        p60_s = ("%+.1f%%" % p60) if isinstance(p60, (int, float)) else ""
    if day is None:
        day_s = _day_pct_cell(snaps)
    else:
        day_s = ("%+.1f%%" % day) if isinstance(day, (int, float)) else ""
    mid = list(tech_cells) if tech_cells else _tech_cells(tech, snaps)
    note_s = str(note or "").replace("\r", " ").replace("\n", " ").strip()[:note_cap]
    graph, after = _trend_cells(snaps)
    return ([p60_s, day_s, str(etf_tag or ""), graph, after] + mid
            + [_trend_chart_cell(snaps), inst if inst else "—",
               nat if nat else "—", note_s])


def _table_lines(rows):
    """rows: [{name, code, cells(16)}] → 对齐文本表行(含表头/分隔线)。空列以 — 占位。
    16 = 距60日高/今日/所属ETF 3 + 趋势图谱/星后形态 2
        + 技术 7(RSI/SAR/MACD/OBV/粘合/换手/量比) + 趋势图 1 + 机构股 1 + 国家队 1 + 备注 1。"""
    if not rows:
        return []
    body = []
    for r in rows:
        body.append([str(r["name"] or ""), str(r["code"] or "")] + [c or "—" for c in (r["cells"] or [])])
    n = len(_TABLE_HEAD)
    widths = [_tw(_TABLE_HEAD[i]) for i in range(n)]
    for row in body:
        for i in range(n):
            widths[i] = max(widths[i], _tw(row[i]))

    def fmt(row):
        line = ""
        for i in range(n):
            cell = str(row[i])
            line += cell + " " * (widths[i] - _tw(cell))
            if i < n - 1:
                line += "  "
        return line.rstrip()

    out = [fmt(_TABLE_HEAD), fmt(["-" * w for w in widths])]
    out += [fmt(r) for r in body]
    return out


def _grid_lines(header, rows):
    """自定义表头段 → 显示宽度对齐的文本表行（含表头/分隔线）。"""
    head = [str(h) for h in (header or [])]
    if not head:
        return []
    body = [[str(c) if c is not None else "" for c in r] for r in (rows or [])]
    widths = [_tw(h) for h in head]
    for row in body:
        for i in range(len(head)):
            widths[i] = max(widths[i], _tw(row[i] if i < len(row) else ""))

    def fmt(row):
        line = ""
        for i in range(len(head)):
            cell = str(row[i] if i < len(row) else "")
            line += cell + " " * (widths[i] - _tw(cell))
            if i < len(head) - 1:
                line += "  "
        return line.rstrip()

    out = [fmt(head), fmt(["-" * w for w in widths])]
    out += [fmt(r) for r in body]
    return out


def _sec_fallback(sec):
    """分段 → 文本回退行（图片不可用时用；rows 形态走 _table_lines，body 形态走 _grid_lines）。"""
    title = sec.get("title") or ""
    if sec.get("body"):
        return [title] + _grid_lines(sec.get("header"), sec.get("body"))
    return [title] + _table_lines(sec.get("rows") or [])


def _tag_section(sec, target):
    """给分段打上页面归属 + 文本回退，供正文占位/降级共用。"""
    sec["target"] = target
    if not sec.get("fallback"):
        sec["fallback"] = _sec_fallback(sec)
    return sec


def _readable_sec_lines(sec):
    """分段 → 微信正文可读行（逐只一行，不依赖等宽对齐）。

    2026-09-12 用户要求：正文必须带出完整表格信息（股票名/形态/技术指标/备注），
    而不是「见长图」占位。微信是比例字体，17 列对齐文本表会错乱（这也正是当初表格
    改发图片的原因），故这里改成「序号) 名称(代码) ｜ 列名 值 ｜ …」的逐只展开：
    信息与长图 / CSV / XLSX 逐列一致，空列与占位符（—/-）自动省略。
    兼容两种分段形态：`rows`（cells 对齐 _TABLE_HEAD[2:]）与 `body`（对齐 header）。
    """
    title = sec.get("title") or ""
    out = [title] if title else []

    def _kv(cells, head, start):
        parts = []
        for j, c in enumerate(cells):
            v = str(c if c is not None else "").strip()
            if not v or v in ("—", "-", "nan"):
                continue
            h = str(head[start + j]) if 0 <= start + j < len(head) else ""
            parts.append(("%s %s" % (h, v)).strip() if h else v)
        return parts

    def _head_line(i, name, code):
        name = (name or "").strip() or "?"
        code = (code or "").strip()
        return "%d) %s(%s)" % (i, name, code) if code and code != "—" else "%d) %s" % (i, name)

    rows = sec.get("rows")
    if rows:
        for i, r in enumerate(rows, 1):
            cells = _kv(r.get("cells") or [], _TABLE_HEAD, 2)
            line = _head_line(i, r.get("name"), r.get("code"))
            out.append(line + ("  ｜ " + " ｜ ".join(cells) if cells else ""))
        return out

    body = sec.get("body")
    if body:
        head = list(sec.get("header") or [])
        same = head == _TABLE_HEAD
        for i, r in enumerate(body, 1):
            cells = [str(c) if c is not None else "" for c in r]
            if same and len(cells) >= 2:
                parts = _kv(cells[2:], head, 2)
                headline = _head_line(i, cells[0], cells[1])
            else:
                # 非统一 17 列表（如「组合纪律」两列）→ 直接「列名 值 ｜ …」，不加序号
                parts = _kv(cells, head, 0)
                headline = ""
            if headline and parts:
                out.append(headline + "  ｜ " + " ｜ ".join(parts))
            elif headline:
                out.append(headline)
            elif parts:
                out.append(" ｜ ".join(parts))
        return out

    return out or [title]


# ── 候选表格 → 图片（2026-09-08：微信 text 非等宽字体导致对齐表错乱 → 表格改发图片）──
TABLE_IMG_DIR = os.path.join(HERE, "data", "round_tables")
_IMG_MARKS = (("🔒", "封板·"), ("🆕", "新·"), ("🟡", "流出·"))


def _name_cell_img(name):
    """名称前缀 emoji 标记 → 图片可渲染的文字标注（matplotlib 无 emoji 字形，会出豆腐块）。"""
    s = str(name or "")
    for k, r in _IMG_MARKS:
        s = s.replace(k, r)
    return s


def _rows_to_png(out_dir, title, rows, idx, note=None):
    """把统一 rows（{name,code,cells}）渲染成候选表格 PNG。

    成功返回图片路径；失败留痕并返回 None（调用方回退为文本表，不丢内容）。
    """
    try:
        import _table_img as ti
        os.makedirs(out_dir, exist_ok=True)
        body = []
        for r in rows:
            cells = [c or "—" for c in (r.get("cells") or [])]
            body.append([_name_cell_img(r.get("name") or ""),
                         str(r.get("code") or "")] + cells)
        path = os.path.join(out_dir, "_tbl_%s_%d.png" % (
            datetime.datetime.now().strftime("%Y%m%d_%H%M%S"), idx))
        ti.render_table(title, _TABLE_HEAD, body, path, note=note)
        return path
    except Exception as e:  # noqa: BLE001
        ops_note("table_png_fail", "%s: %s" % (type(e).__name__, e))
        print("候选表格渲染失败(回退文本表):", type(e).__name__, e)
        return None


def _rows_body(rows):
    """统一 rows({name,code,cells}) → 图片二维行数据（名称 emoji 标记转文字）。"""
    body = []
    for r in rows:
        cells = [c or "—" for c in (r.get("cells") or [])]
        body.append([_name_cell_img(r.get("name") or ""),
                     str(r.get("code") or "")] + cells)
    return body


def _sections_to_png(out_dir, title, sections, idx, note=None):
    """多段表格 → 单张 PNG（2026-09-10：DAG 段 + SmallTool 段合并，各自标题保留）。

    sections: [{"title": 分段标题, "rows": 统一rows}]，共用 _TABLE_HEAD；
    2026-09-11：页2 的低吸表/绿转红表列口径不同 → 支持 {"title","header","body"}
    直接给二维行数据（body 优先于 rows），各段可带自己的表头（图内各表仍独立）。
    成功返回图片路径；失败留痕并返回 None（调用方回退为文本表，不丢内容）。
    """
    try:
        import _table_img as ti
        os.makedirs(out_dir, exist_ok=True)
        pack = []
        for s in sections or []:
            if s.get("body"):
                pack.append({"title": s.get("title") or "",
                             "header": s.get("header") or _TABLE_HEAD,
                             "rows": [[("" if c is None else str(c)) for c in r]
                                      for r in s["body"]]})
            elif s.get("rows"):
                pack.append({"title": s.get("title") or "", "header": _TABLE_HEAD,
                             "rows": _rows_body(s.get("rows") or [])})
        if not pack:
            return None
        path = os.path.join(out_dir, "_tbl_%s_%d.png" % (
            datetime.datetime.now().strftime("%Y%m%d_%H%M%S"), idx))
        if len(pack) == 1:
            ti.render_table(pack[0]["title"], pack[0]["header"], pack[0]["rows"],
                            path, note=note)
        else:
            ti.render_multi_table(title, pack, path, note=note)
        return path
    except Exception as e:  # noqa: BLE001
        ops_note("table_png_fail", "%s: %s" % (type(e).__name__, e))
        print("候选合并表格渲染失败(回退文本表):", type(e).__name__, e)
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


def _pos_img_sections(data, cache):
    """实仓镜像 → 合并图分段（逐笔建议表 + 组合纪律表）。2026-09-11 Item4 用户确认。

    2026-09-12 三次定稿（用户确认）：逐笔表与五段候选**完全同一套 16 列表头**
    （名称/代码 / 距60日高 / 今日 / 所属ETF / 趋势图谱 / 星后形态 / 技术7列 / 趋势图 / 备注），
    2026-09-12 四次定稿：随全局升为 17 列（新增「机构股」列），仍与五段候选逐列对齐；
    使页3 实仓与页1/页2 五段候选在同一张长图 / 同一份 CSV 里逐列直接横向对比；
    原前置的「建议 / 盈亏%」两列并入「备注」格（信息不丢、列数与候选一致）。
    组合纪律/整体盈亏另起一段（项目/说明）。无实仓返回 []。
    """
    pos = data.get("positions") or []
    if not pos:
        return []
    pf = data.get("portfolio") or {}
    tagmap = _etf_tags([(p.get("code") or "")[-6:] for p in pos[:12]])
    rows = []
    for p in pos[:12]:
        sid = p.get("secid") or p.get("code") or ""
        snaps = _find_cache_snaps(cache, sid) if cache else []
        tag = p.get("verdict") or "-"
        if p.get("sar_warn"):
            tag += "·SAR绿%d日" % p["sar_warn"]
        pnl = ("%+.1f%%" % p["pnl_pct"]) if p.get("pnl_pct") is not None else "-"
        # 「建议 / 盈亏%」并入备注格（2026-09-12 三次定稿：不再单开两列，保列口径统一）
        note = "建议:%s 盈亏:%s" % (tag, pnl)
        if (p.get("note") or "").strip():
            note += " " + str(p["note"]).strip()
        cells = _unified_cells(snaps, tech=p.get("tech") or "", note=note,
                               etf_tag=tagmap.get((p.get("code") or "")[-6:], ""),
                               note_cap=48, inst=_inst_cell(p.get("code")),
                               nat=_nat_cell(p.get("code")))
        rows.append([p.get("name") or "", p.get("code") or ""] + cells)
    secs = [{"title": "💼 实仓镜像·逐笔建议（与候选同一套 17 列，建议/盈亏在备注格）",
             "header": _TABLE_HEAD, "body": rows, "target": "p3",
             "fallback": ["💼 实仓·建议变化 ↓"] + _pos_advice_lines(data)}]
    disc = []
    if pf.get("pnl_pct") is not None:
        disc.append(["组合整体", "持仓%d只 整体%+.1f%%" % (
            pf.get("n", len(pos)), pf["pnl_pct"])])
    for a in (pf.get("alerts") or [])[:5]:
        disc.append(["组合纪律", str(a)])
    if disc:
        secs.append({"title": "◆ 组合纪律与整体（实仓镜像）",
                     "header": ["项目", "说明"], "body": disc, "target": "p3",
                     "fallback": ["◆ 组合纪律"] + ["  " + str(a)
                                                  for a in (pf.get("alerts") or [])]})
    return secs


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


def _intel_lines(max_age_min=150):
    """盘中轮正文·最新情报前导：读 _last_scan.json（情报扫描每 10 分钟刷新）。

    快照缺失/过旧(>max_age_min)/时钟超前时返回空（盘外或未启动不误报）。
    只做「展示参考」，不改动选股结果。返回页面行列表(含空行分隔)。
    """
    try:
        with open(APK_SCAN_FILE, encoding="utf-8") as f:
            snap = json.load(f)
    except (OSError, ValueError):
        return []
    ts = (snap.get("ts") or "").strip()
    try:
        t = datetime.datetime.strptime(ts, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return []
    age = (datetime.datetime.now() - t).total_seconds()
    if not (0 <= age <= max_age_min * 60):
        return []
    parts = []
    rot = snap.get("rotation") or []
    if rot:
        toks = []
        for r in rot[:3]:
            s = "%s%+.1f%%" % (r.get("industry", ""), float(r.get("sec_mom") or 0))
            if r.get("catalyst"):
                s += "[催化]"
            toks.append(s)
        if toks:
            parts.append("轮动:" + " ".join(toks))
    news = snap.get("news") or []
    for n in news[:1]:
        ttl = (n.get("title") or "").strip()
        if ttl:
            parts.append("快讯:" + (ttl[:44] + ("…" if len(ttl) > 44 else "")))
    reps = snap.get("reports") or []
    if reps:
        toks = []
        for o in reps[:3]:
            nm = o.get("org") or "?"
            cnt = o.get("count")
            toks.append(nm + ("%d篇" % cnt if cnt else ""))
        if toks:
            parts.append("研报:" + "、".join(toks))
    extra = _daily_intel_lines()
    if not parts and not extra:
        return []
    out = [""]
    if parts:
        out += ["📡 最新情报 %s" % ts[11:16], "  " + " | ".join(parts)]
    out += extra
    return out


def _daily_intel_lines(max_age_min=480):
    """08:00/09:00 每日节奏情报前导：宏观 → 利好利空板块 → 候选（同日有效）。

    数据源 data/_daily_intel.json（_daily_intel.py 产出）。只做展示参考，不改选股结果。
    """
    try:
        with open(DAILY_INTEL_FILE, encoding="utf-8") as f:
            it = json.load(f)
    except (OSError, ValueError):
        return []
    ts = (it.get("ts") or "").strip()
    try:
        t = datetime.datetime.strptime(ts, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return []
    age = (datetime.datetime.now() - t).total_seconds()
    if not (0 <= age <= max_age_min * 60):
        return []
    boards = it.get("boards") or []
    out = ["", "🌍 每日节奏情报 %s（%s）" % (ts[11:16], it.get("slot") or "")]
    bulls = [b for b in boards if b.get("side") == "利好"][:4]
    bears = [b for b in boards if b.get("side") == "利空"][:3]
    if bulls:
        out.append("  🟢利好: " + " ".join("%s(%s)" % (b.get("board"), b.get("strength"))
                                         for b in bulls))
    if bears:
        out.append("  🔴利空: " + " ".join("%s(%s)" % (b.get("board"), b.get("strength"))
                                         for b in bears))
    picks = it.get("picks") or []
    if picks:
        out.append("  ⭐候选: " + " ".join(
            "%s%s" % (p.get("name"), ("%+.1f%%" % p["pct"]) if p.get("pct") is not None else "")
            for p in picks[:6]))
    return out


_BIG_IDX = (("sh000001", "上证"), ("sh000688", "科创50"), ("sh000300", "沪深300"))


def _etf_index_cache():
    """ETF/指数缓存（沪深300 在 _etf_cache.json，PC 全量池 cache 无）懒加载。"""
    if not hasattr(_etf_index_cache, "_d"):
        try:
            with open(os.path.join(HERE, "_etf_cache.json"), encoding="utf-8") as f:
                _etf_index_cache._d = json.load(f)
        except (OSError, ValueError):
            _etf_index_cache._d = {}
    return _etf_index_cache._d


# ETF 低位 usecase 当日发布物（_etf_publish.py 产出，含 gate + signal_today + approach）
ETF_LIVE_FILE = os.path.join(HERE, "data", "_etf_live_picks.json")


def _etf_table_rows():
    """ETF 低位低吸当日命中 → 并入推送候选表（2026-09-10）。

    数据源 data/_etf_live_picks.json = _etf_publish.py 跑 etf_dip usecase 产出，
    与 APK ETF 页同源；signal_today 为空 → ([], "")，调用方不建段。
    统一 17 列（2026-09-12 四次定稿）单元格映射（ETF 无「均线粘合/所属ETF」口径，置空）：
      距60日高/今日/趋势图谱/星后形态/趋势图←由日K现算（与股票段同口径）；趋势图谱若未命中
      十字星口径，则改用引擎发布的跌后K形态 kline，趋势图为 — 时用 trend 方向回填 ·
      RSI←rsi6 · SAR/MACD/OBV←引擎发布值 · 换手/量比←复用 _tech_cells 同口径 ·
      备注←回撤60（原放段标题，现按统一列放行内）。
    """
    try:
        with open(ETF_LIVE_FILE, encoding="utf-8") as f:
            live = json.load(f)
    except (OSError, ValueError):
        return [], ""
    picks = live.get("signal_today") or []
    if not picks:
        return [], ""
    gate = live.get("gate") or {}
    cache = _etf_index_cache()
    rows = []
    for it in picks:
        code = it.get("code") or ""
        snaps = _find_cache_snaps(cache, code)
        tc = _tech_cells("", snaps)          # [RSI, SAR, MACD, OBV, 粘合, 换手, 量比]
        rsi = it.get("rsi6")
        if isinstance(rsi, (int, float)):
            tc[0] = "%.0f" % rsi
        tc[1] = it.get("sar") or tc[1]
        tc[2] = it.get("macd") or tc[2]
        tc[3] = it.get("obv") or tc[3]
        dd = it.get("dd60")
        note = "回撤60 " + (("%.1f%%" % dd) if isinstance(dd, (int, float)) else "—")
        cells = _unified_cells(snaps, tech_cells=tc, note=note, inst=_inst_cell(code),
                               nat=_nat_cell(code))
        # 趋势图谱：日K现算未命中（趋势图谱/星后形态均空）时，改用引擎发布的跌后K形态；
        # 趋势图若因日K不足落到 — 而引擎给了方向，则用「方向+标签」回填（信息不丢）。
        if not cells[3] and (it.get("kline") or ""):
            cells[3] = str(it["kline"])
        tr = str(it.get("trend") or "")
        if tr and cells[12] in ("", "—"):
            cells[12] = {"上涨": "↑", "下跌": "↓"}.get(tr, "→") + tr
        rows.append({
            "name": "·" + (it.get("name") or code),
            "code": code[2:] if code[:2] in ("sh", "sz", "bj") else code,
            "cells": cells,
        })
    if not rows:
        return [], ""
    # 字号兼容：Microsoft YaHei 无 U+2713/U+2717 字形（渲染会缺字告警）→ 用 √ / ×
    title = "🎯 ETF 低位低吸·当日命中(%d只) ｜ 沪深300门控:%s" % (
        len(rows), "多头√" if gate.get("ok") else "空头×")
    dds = [it.get("dd60") for it in picks if isinstance(it.get("dd60"), (int, float))]
    if dds:
        title += " ｜ 回撤60 " + ",".join("%.1f%%" % d for d in dds)
    return rows, title


def _big_board_lines(cache, asof):
    """三指数（上证/科创50/沪深300）大盘速览 + 大方向建议。

    守护早盘/盘中推送页1 使用：给选股提供"大盘大方向"指导——
    - 指数日涨跌、5/20 日强弱、连续涨跌天数
    - 依据 _dip_rebound_stat 统计给出操作方向提示（超跌抄底/顺势/震荡防守）
    """
    etf = _etf_index_cache()
    snaps_map = {}
    for code, _ in _BIG_IDX:
        e = ((cache or {}).get(code) or {}).get("snaps") or []
        if not e:
            e = (etf.get(code) or {}).get("snaps") or []
        snaps_map[code] = e
    meta = []
    row_parts = []
    for code, disp in _BIG_IDX:
        snaps = snaps_map.get(code) or []
        if not snaps:
            continue
        dts = [s["date"] for s in snaps]
        stale = False
        if asof not in dts:
            past = [d for d in dts if d <= asof]  # 数据滞后时取最近可用交易日
            if not past:
                continue
            i = dts.index(past[-1])
            stale = True
        else:
            i = dts.index(asof)
        if i < 1:
            continue
        s = snaps[i]
        chg = s.get("changePct")

        def pct_n(n):
            return (s["close"] / snaps[i - n]["close"] - 1) * 100 if i >= n and snaps[i - n].get("close") else None

        p5, p20 = pct_n(5), pct_n(20)
        streak = 0
        if (chg or 0) >= 0:
            for j in range(i, max(i - 10, -1), -1):
                if (snaps[j].get("changePct") or 0) >= 0:
                    streak += 1
                else:
                    break
        else:
            for j in range(i, max(i - 10, -1), -1):
                if (snaps[j].get("changePct") or 0) < 0:
                    streak -= 1
                else:
                    break
        meta.append({"disp": disp, "chg": chg, "p5": p5, "p20": p20, "streak": streak})
        disp_tail = "~%s" % s["date"][5:].replace("-", "/") if stale else ""
        row_parts.append("%s%.0f(%+.1f%%)%s" % (disp, s["close"], chg or 0, disp_tail))
        if streak >= 3 or streak <= -3:
            row_parts[-1] += ("连涨%d" % streak) if streak > 0 else ("连跌%d" % -streak)
    if not row_parts:
        return []
    lines = ["📊 大盘(%s): %s" % (asof, " ".join(row_parts))]
    strong = " ".join("%s 5日%+.1f/20日%+.1f" % (m["disp"], m["p5"] or 0, m["p20"] or 0) for m in meta)
    lines.append("   强弱(5日/20日) %s" % strong)
    # 大方向建议（依据回溯统计）
    deep = [m for m in meta if (m["p5"] or 0) <= -6]
    down3 = [m for m in meta if (m["streak"] or 0) <= -3]
    up4 = [m for m in meta if (m["streak"] or 0) >= 4]
    bull20 = [m for m in meta if (m["p20"] or 0) >= 6]
    bear20 = [m for m in meta if (m["p20"] or 0) <= -6]
    if deep:
        tip = "⚠️ 大盘5日跌幅≥6% 处超跌区：跌透热门龙头(前60日强+自身连跌)左侧抄底窗口，持2-3日胜率约6成(2015-26统计)"
    elif down3:
        tip = "🟡 指数连跌%d日：跌得越深反弹概率越高(连跌≥4后5日胜率约7成)，可低吸前期热门+已连跌的龙头，暂避追高" % (-down3[0]["streak"])
    elif up4:
        tip = "🔴 指数连涨%d日 顺势强势：主攻热门主线(量价配合)，勿追已大涨高位股" % up4[0]["streak"]
    elif bull20:
        tip = "🔴 中期多头(20日≥6%)：顺势选强势主线，回调缩量低吸为主"
    elif bear20:
        tip = "🟢 中期空头(20日≤-6%)：轻仓防守，仅做超跌反弹(热门跌透龙头快进快出)"
    else:
        tip = "🟡 震荡：精选个股——非牛市只惩罚不追强；优先均线粘合+缩量企稳/超跌热门龙头"
    lines.append("   大方向: %s" % tip)
    return lines


def _load_intraday_dag():
    """当日主线 DAG：取「最新有效」的今日结果（盘中快照 / 收盘定格），避开 T-1 定格。

    2026-09-11 修「当日选股整天不变」：盘中各轮此前只读 dag_screen_latest.json
    （15:00 收盘定格才有当日结果），于是 09:30-14:30 一整天显示的都是一份 T-1 结果。
    现改为同时读取 dag_screen_lunch.json（盘中实时快照）与 dag_screen_latest.json
    （收盘定格），只保留 asof=今日 且有 result 的文件，并按文件修改时间取最新的。
    这样 11:31 午餐快照后优先用午餐快照；15:00 收盘定格后定格文件更新，自动切到
    收盘结果；盘中每 30 分钟刷新的快照也会自动覆盖旧快照。文件缺一或损坏静默跳过。
    """
    today = datetime.date.today().isoformat()
    candidates = []
    for f in (DAG_LUNCH_FILE, DAG_SCREEN_FILE):
        try:
            with open(f, encoding="utf-8") as fh:
                dag = json.load(fh)
        except (OSError, ValueError):
            continue
        if not isinstance(dag, dict) or not (dag.get("result") or {}):
            continue
        # 两个文件都必须是今日结果；latest 在 15:00 前可能是 T-1 定格，不能误用
        if dag.get("asof") != today:
            continue
        try:
            mtime = os.path.getmtime(f)
        except OSError:
            mtime = 0
        candidates.append((mtime, dag))
    if not candidates:
        return None
    candidates.sort(key=lambda x: x[0], reverse=True)
    return candidates[0][1]


def _build_candidate_sections(data, ctx, cache, dag, old_secids, asof):
    """构建盘中选股 5 个模式段（2026-09-12 用户确认结构）。

    返回 (sections, dag_all)。section 可为 {"title","rows"}（统一 _TABLE_HEAD）
    或 {"title","header","body"}（自定义表头）。
    """
    sections = []
    dag_all = set()
    if dag is None:
        dag = _load_intraday_dag()
    dag = dag or {}   # 文件缺失时 _load_intraday_dag() 返回 None → 统一成空字典

    # ① 主线 DAG 当日选股
    dag_rows = []
    if dag and (dag.get("result") or {}):
        res = dag.get("result") or {}
        bucket = {"短线": [], "中线": [], "长线": []}
        for period in ("超短", "短线", "中线", "长线"):
            disp, _ = fold_period(period)
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
                        extra = _tech_rich(cache[raw].get("snaps") or [])
                    except Exception:
                        extra = ""
                lu = bool(it.get("limit_up"))
                if not lu and hit:
                    lu = bool(hit.get("limit_up"))
                bucket[disp].append((raw, code, nm, extra, lu))
        seen_code = set()
        seq_all = []
        for disp in ("短线", "中线", "长线"):
            for raw, code, nm, extra, lu in bucket[disp]:
                key = code or raw
                if not key or key in seen_code:
                    continue
                seen_code.add(key)
                seq_all.append((raw, code, nm, extra, lu, disp))
                if raw:
                    dag_all.add(raw)
        normal = [x for x in seq_all if not x[4]]
        ups = [x for x in seq_all if x[4]]
        keeps = (normal[:3] + ups[:3])[:3]
        dag_tags = _etf_tags([(k[1] or k[0] or "")[-6:] for k in keeps])
        for raw, code, nm, extra, lu, disp in keeps:
            snaps = _find_cache_snaps(cache, raw or code)
            # 备注：来源周期（超短已并入短线）+ 涨停提示，供盘后复盘核对
            note = disp + ("·涨停不可追" if lu else "")
            mark = "🔒" if lu else ("🆕" if (raw and raw not in old_secids) else "·")
            dag_rows.append({
                "name": mark + nm, "code": code or raw,
                "cells": _unified_cells(snaps, tech=extra, note=note,
                                        etf_tag=dag_tags.get((code or raw)[-6:], ""),
                                        inst=_inst_cell(code or raw),
                                        nat=_nat_cell(code or raw))})
    dag_real = len(dag_rows)
    if not dag_rows:
        dag_rows = [{"name": "·今日无命中", "code": "—",
                     "cells": [""] * _N_CELLS}]
    dag_asof = dag.get("asof") or ""
    stale = ""
    if dag_asof and asof and dag_asof < asof:
        stale = " (asof %s)" % dag_asof
    elif dag.get("mode") == "snapshot_intraday" or (dag_asof and asof and dag_asof > asof):
        stale = "【盘中实时】"
    sections.append(_tag_section({
        "title": "主线 DAG 当日选股（%d只）%s（形态匹配仅标注，看涨才买入）" % (dag_real, stale),
        "rows": dag_rows}, "p1"))

    # ② smalltool 当日选股
    ref_rows = []
    for period in DISPLAY_PERIODS:
        for it in (data.get("groups") or {}).get(period) or []:
            sid = it.get("secid") or ""
            if sid in dag_all or sid[2:] in dag_all:
                continue
            tags = (it.get("reso") or {}).get("tags") or []
            flowout = any(str(t).startswith("资金流出") for t in tags)
            ref_rows.append((period, it, flowout))
    st_rows = []
    if ref_rows:
        ref_rows.sort(key=lambda r: -(r[1].get("ratio") or 0))
        # 同一只票可能同时挂在多个周期（超短/短线/中线）→ 按代码去重，保留 ratio 最高者
        seen_code = set()
        uniq = []
        for period, it, flowout in ref_rows:
            key = it.get("secid") or it.get("code") or it.get("name") or ""
            if not key or key in seen_code:
                continue
            seen_code.add(key)
            uniq.append((period, it, flowout))
        keep = ([x for x in uniq if not x[1].get("limit_up")][:3]
                + [x for x in uniq if x[1].get("limit_up")][:3])[:3]
        st_tags = _etf_tags([(x[1].get("secid") or "")[-6:] for x in keep])
        for period, it, flowout in keep:
            lu = bool(it.get("limit_up"))
            newdot = "🆕" if it["secid"] not in old_secids else ""
            # 已有 🆕 前缀时不再叠「·」占位符（否则 CSV 长图会渲染成「新··中国巨石」；
            # 与 ① DAG 段的标记写法保持一致）
            mark = "🔒" if lu else ("🟡" if flowout else ("" if newdot else "·"))
            sid = it.get("secid") or ""
            snaps = _find_cache_snaps(cache, sid)
            # 备注：命中周期 + 资金流向（原文 reso.tags 里的 资金流出）
            note = period + ("·资金流出" if flowout else "")
            st_rows.append({
                "name": "%s%s%s" % (newdot, mark, it.get("name", "")),
                "code": sid[2:] or "",
                "cells": _unified_cells(snaps, tech=it.get("tech") or "", note=note,
                                        etf_tag=st_tags.get(sid[-6:], ""),
                                        inst=_inst_cell(sid), nat=_nat_cell(sid))})
    st_real = len(st_rows)
    if not st_rows:
        st_rows = [{"name": "·今日无命中", "code": "—",
                    "cells": [""] * _N_CELLS}]
    sections.append(_tag_section({
        "title": "smalltool 当日选股（%d只）（盘中实时）（形态匹配仅标注，看涨才买入）" % st_real,
        "rows": st_rows}, "p1"))

    # ③④⑤ ETF 相关三段
    dag_codes = set()
    if dag and (dag.get("result") or {}):
        for period in ("超短", "短线", "中线", "长线"):
            for it in (dag.get("result") or {}).get(period) or []:
                raw = (it.get("code") or "").strip()
                c6 = raw[2:] if raw[:2].lower() in ("sh", "sz", "bj") else raw
                if c6:
                    dag_codes.add(c6)
    if ctx:
        try:
            import _etf_holdings as _eh
            etf_flow = ctx.get("etf_flow") or []

            # ③ ETF 全行业扫描（SAR 绿转红）—— 统一 17 列（2026-09-12）
            fu_rows = []
            for gname, pk in _eh.fresh_up_picks(
                    etf_flow=etf_flow, dag_codes=dag_codes, max_rows=10):
                code6 = str(pk.get("code") or "")
                snaps = _eh._snaps_live(code6)      # 与 _meta_live 同一次拉取（当日缓存）
                # 备注只留 ETF 扫描自身的「绿转红√」判定：原先还回显 pk.tag
                # （"RSI58 SAR红↑1 MACD红柱扩大"），而 RSI/SAR/MACD 三列由 _pk_tech_cells(pk)
                # 按**当日实时 snaps** 另算 → 同一张表里同一指标出现两个值（59 vs 58 等），
                # 故去掉 tag 回显，避免自相矛盾。
                note = "绿转红√"
                fu_rows.append({
                    "name": str(pk.get("name") or code6) + ("√DAG" if pk.get("dag_hit") else ""),
                    "code": code6,
                    "cells": _unified_cells(
                        snaps, etf_tag=gname, note=note,
                        tech_cells=_eh._pk_tech_cells(pk),
                        p60=pk.get("pos60"), day=pk.get("pct"),
                        inst=_inst_cell(code6), nat=_nat_cell(code6))})
            fu_real = len(fu_rows)
            if not fu_rows:
                fu_rows = [{"name": "·今日无命中", "code": "—", "cells": [""] * _N_CELLS}]
            sections.append(_tag_section({
                "title": "ETF 全行业扫描 当日选股（%d只）（盘中实时）（形态匹配仅标注，看涨才买入）" % fu_real,
                "rows": fu_rows}, "p2"))

            # ④ ETF top 5（热门板块前五重仓 · 低吸精选）—— 统一 17 列（2026-09-12）
            flow_top = ctx.get("flow_rank") or []
            pos_f = [r for r in flow_top if r.get("main_yi", 0) > 0][:4]
            themes = [r["name"] for r in pos_f]
            try:
                focus = _eh.load_focus_sectors()
            except Exception:
                focus = []
            for x in (focus or []):
                if x not in themes:
                    themes.append(x)
            lb_rows = []
            if themes:
                for gname, pk in _eh.low_buy_picks(
                        themes, etf_flow, dag_codes=dag_codes, max_rows=10):
                    code6 = str(pk.get("code") or "")
                    snaps = _eh._snaps_live(code6)
                    lb_rows.append({
                        "name": str(pk.get("name") or code6) + ("√DAG" if pk.get("dag_hit") else ""),
                        "code": code6,
                        "cells": _unified_cells(
                            snaps, etf_tag=gname, note=str(pk.get("note") or ""),
                            tech_cells=_eh._pk_tech_cells(pk),
                            p60=pk.get("pos60"), day=pk.get("pct"),
                            inst=_inst_cell(code6), nat=_nat_cell(code6))})
            lb_real = len(lb_rows)
            if not lb_rows:
                lb_rows = [{"name": "·今日无命中", "code": "—", "cells": [""] * _N_CELLS}]
            sections.append(_tag_section({
                "title": "ETF top 5 当日选股（%d只）（盘中实时）（形态匹配仅标注，看涨才买入）" % lb_real,
                "rows": lb_rows}, "p2"))
        except Exception as e:  # noqa: BLE001
            ops_note("candidate_etf_err", "%s: %s" % (type(e).__name__, e))

    # ⑤ ETF 当日选股
    etf_rows, _ = _etf_table_rows()
    etf_real = len(etf_rows)
    if not etf_rows:
        etf_rows = [{"name": "·今日无命中", "code": "—",
                     "cells": [""] * _N_CELLS}]
    sections.append(_tag_section({
        "title": "ETF 当日选股（%d只 ETF）（盘中实时）（形态匹配仅标注，看涨才买入）" % etf_real,
        "rows": etf_rows}, "p1"))

    return sections, dag_all


def _file_tag(scene):
    """场景 → 产出文件组标签（2026-09-13 用户要求：CSV/长图/XLSX 用「具体名称_时间」命名）。

    盘中有「盘中选股」/ 盘外有「盘外选股」/ 复盘有「收盘复盘」——便于在目录里按类型 + 时间
    直接辨认。旧名 `_round_<ts>` 无语义，且盘中轮与盘外补推都落 `_round_` 前缀无法区分。
    """
    s = str(scene or "")
    if "盘中" in s:
        return "盘中选股"
    if "盘外" in s:
        return "盘外选股"
    if "复盘" in s:
        return "收盘复盘"
    return "盘中选股" if in_trading_time(datetime.datetime.now()) else "盘外选股"


def round_pages(data, ctx, cfg, old_secids=None, pos_advice=True,
                scene=None, dag=None, cache=None, lowbuy_offline=None,
                note_offline=None, table_img_dir=None, merge_pages=True,
                file_tag=None):
    """整轮消息的纯拼装（不改文件、不推送），实时推送与历史回放共用同一排版。

    - 页1 选股摘要：⭐ 主线·XML DAG 当日选股 + 📋 smalltool 当日选股（超短+短线合并
      去重，每档最多3只，每只附 RSI/SAR/MACD/OBV 等技术指标）+ 🎯 ETF 当日选股
      + ⭐ 双端共同命中
    - 页2 资金与低吸：💸 板块资金流入/流出 + 📈 ETF资金流向 + 🌐 ETF 全行业扫描
      （SAR绿转红） + 🎯 ETF top 5（前五重仓低吸精选）
    - 页3 实仓做T（无实仓不生成）

    scene=None 时按当前时间推导(盘中/盘外)；dag 缺省读 DAG_SCREEN_FILE；
    cache={secid:{"snaps":[]}} 供 DAG 独有票补算技术指标；lowbuy_offline 传
    离线低吸行(历史回放；此时页2 不展示无存档的实时资金流)。

    table_img_dir: 传目录时，页1/页2/页3 所有表先「填充到一份 CSV」，再由该 CSV 统一
    渲染成一张长图，并额外产出一份全左对齐 XLSX；正文同时带出完整信息（2026-09-12
    用户要求，不再只放「见长图」占位），以「序号) 名称(代码) ｜ 列名 值 …」可读行展开
    （微信比例字体，17 列对齐表会错乱），图片/XLSX/CSV 路径经返回值 imgs 带回由调用方
    推送；None = 不出图（正文同口径可读行）。

    返回 (pages, imgs)；pages=[(title, content), ...]，
    imgs=[长图png, 全左对齐xlsx, 原始csv, ...]。
    """
    old_secids = old_secids or set()
    imgs = []
    if scene is None:
        now = datetime.datetime.now()
        scene = ("盘中选股 " if in_trading_time(now) else "盘外选股 ") + now.strftime("%H:%M")
    tag = file_tag or _file_tag(scene)      # 产出文件组前缀（2026-09-13 用户要求）
    asof = data.get("asof", "")
    state = data.get("market_state", "")
    state_cn = {"BULLISH": "上涨", "BEARISH": "下跌", "OSCILLATION": "震荡",
                "NO_DATA": "数据不足"}.get(state, state)
    state_mark = {"BULLISH": "🔴", "BEARISH": "🟢", "OSCILLATION": "🟡",
                  "NO_DATA": "⚪"}.get(state, "⚪")
    # 内容不再重复场景头行（该行与推送标题相同，2026-09-06 修复：标题=「%s | %s」）
    p1 = ["%s 大盘: %s %s | 池 %d" % (state_mark, state, state_cn,
                                     data.get("pool_total", 0))]
    p1 += _big_board_lines(cache, asof)  # 📊 上证/科创50/沪深300 大方向
    p1 += _intel_lines()  # 📡 最新情报（scan 10 分钟快照，过旧自动省略）
    # ① ② ③④⑤ 五段候选表（2026-09-12 用户确认结构）：统一由 _build_candidate_sections
    #   构建，正文 / 合并长图 / 原始 CSV 共用同一数据源（避免两处实现口径漂移）。
    #   段→页面归属：①②⑤ 归页1；③④(ETF 全行业扫描 / ETF top5) 归页2。
    cand_secs, dag_all = _build_candidate_sections(data, ctx, cache, dag, old_secids, asof)
    img_secs = list(cand_secs)
    cand_p2_secs = [s for s in cand_secs if s.get("target") == "p2"]
    if not table_img_dir:  # 不出图：正文输出各段可读文本（页1 段在此，页2 段见 p2）
        for _s in cand_secs:
            if _s.get("target") == "p1":
                p1.append("")
                p1.extend(_readable_sec_lines(_s))
    # ── 各表合并进同一张长图 + 同一份 CSV（2026-09-12 用户方案）──
    #  页1 段(⭐DAG/📋SmallTool/🎯ETF当日) + 页2 段(🌐全行业扫描/🎯ETF top5) + 页3 段(实仓)
    #  一律先「填充到一份 CSV」，再由该 CSV 渲染成一张长图（图 = CSV 内容，逐行一致）；
    #  整轮只发「正文 + 长图 + 原始 CSV」。此处仅收集，待 p2/p3 段收集完再统一渲染。
    img_note = ("统一口径：五段选股 + 实仓镜像 + APK/exe 技术假设同为 18 列（距60日高/今日/"
                "所属ETF/趋势图谱/星后形态/RSI/SAR/MACD/OBV/均线粘合/换手/量比/趋势图/机构股/国家队/备注）"
                " · 趋势图谱=连跌x天"
                "→十字星 · 星后形态=星后第y天 大/小阴阳 · 趋势图=经典K线形态+方向"
                "（如 ↑上涨·早晨之星）· 机构股=A机构加仓/B机构参与/C散户票（季报滞后，非实时信号）"
                " · 国家队（直接持股证据链，三类合流）：告▲基 04-24=股东增减持/定增获配公告"
                "(T+1，最快) · 锁基 06-30=锁定持股(十大股东可见而流通榜不可见=限售，"
                "如大基金定增锁定18个月) · ▲国 06-30=季报十大流通股东流入(退=退出，披露日)"
                " · 史▼基 25-09=超365天窗口的公告历史(仅给时间点，不计分)"
                " ｜ 标记：封板·=当日涨停不可追 · 新·=本轮新晋 · 流出·=资金流出"
                " · 实仓段「建议/盈亏%」在备注格 · 距60日高负值=低于60日高点")
    # ③ 双端共同命中
    both = sorted(dag_all & candidate_secids(data))
    if both:
        names = " ".join(_find_name(data, sid) for sid in both[:8])
        p1.append("")
        p1.append("⭐ 双端共同命中(%d): %s" % (len(both), names))
    # ── 页面2：资金 / ETF / 低吸（独立消息）──
    p2 = []
    p2_extra = False
    # ③④ 段（ETF 全行业扫描 / ETF top5）：图片化时并入合并长图；文本模式在此输出
    for _s in cand_p2_secs:
        p2_extra = True
        if not table_img_dir:
            p2.append("")
            p2.extend(_readable_sec_lines(_s))
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
        # ⑥ ETF 低吸精选 / 全行业绿转红补充（2026-09-06 方案B）已升级为 ③④ 段，
        #    由 _build_candidate_sections 统一构建（2026-09-12），此处不再重复实现。
    else:
        # 离线/历史回放：板块/ETF资金流为盘中实时采集、无存档 → 说明 + 全板块前五低吸
        if note_offline:
            p2.append("")
            p2.append(note_offline)
            p2_extra = True
        if lowbuy_offline:
            p2.append("")
            p2.append("🎯 ETF持仓前五·低吸精选")
            p2.extend(lowbuy_offline)
            p2_extra = True
    # ⑦ 🏛 国家队（汇金）ETF 份额动向（2026-09-13 新增；离线回放与实时都带）
    nat_etf = _national_etf_lines()
    if nat_etf:
        p2.append("")
        p2.extend(nat_etf)
        p2_extra = True
    # ── 页面3：实仓与做T（2026-09-11 Item4 用户确认：并入合并图，与候选同口径）──
    #  有实仓且本轮出图时：逐笔建议 + 组合纪律作为 p3 段进同一张 PNG，正文只留标题占位；
    #  不出图（text-only / 无实仓）时沿用指纹「有变化才展开」的纯文本逻辑。
    p3 = []
    pos_in_img = False
    if pos_advice and (data.get("positions") or []):
        if table_img_dir:
            pos_secs = _pos_img_sections(data, cache)
            if pos_secs:
                img_secs.extend(pos_secs)
                pos_in_img = True
        if not pos_in_img:
            _append_pos_block(p3, data)

    # ── 机构判定池落盘（data/_inst_pool.json + assets 镜像）──
    #  供 usecase inst_holding 的 inst_pool_build 读取（APK/exe 双端离线跑机构季报判定），
    #  与 data/_inst_holdings.json 同目录；失败静默，不影响本轮推送。
    if img_secs:
        _write_inst_pool(img_secs, asof)

    # ── 统一渲染：各段先填充到「一份 CSV」，再由该 CSV 出「一张长图」（2026-09-12 用户方案）──
    #  页1/页2/页3 的所有表都进同一份 CSV + 同一张长图（各表标题+表头各自保留）；正文只留
    #  标题占位，渲染失败则逐段回退文本表（内容不丢）。imgs = [长图, 原始 CSV]。
    csv_path = png_path = xlsx_path = None
    if table_img_dir and img_secs:
        ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        # 2026-09-13 用户要求：产出用「场景_时间」命名（如 盘中选股_20260913_152033.csv），
        # 三类场景同扩展名同基名（csv/png/xlsx 一组），目录里可直接辨认与配对。
        stem = "%s_%s" % (tag, ts)
        csv_path = os.path.join(table_img_dir, stem + ".csv")
        png_path = os.path.join(table_img_dir, stem + ".png")
        xlsx_path = os.path.join(table_img_dir, stem + ".xlsx")
        try:
            import _table_csv as tcsv
            csv_path, png_path = tcsv.export(
                csv_path, png_path,
                "%s·合并表（候选5段 / 实仓镜像 ｜ 统一长图 + 原始 CSV）" % tag,
                img_secs, _TABLE_HEAD,
                note=img_note + " ｜ 各表标题与表头各自保留，明细以图 / CSV 为准")
        except Exception as e:  # noqa: BLE001
            ops_note("round_csv_fail", "%s: %s" % (type(e).__name__, e))
            print("合并表格 CSV/长图失败(回退文本表):", type(e).__name__, e)
            csv_path = png_path = None
        try:
            # XLSX（2026-09-12 用户反馈 CSV 在表格软件里数字列右对齐）→ 全左对齐 + 文本写入
            import _table_xlsx as txlsx
            txlsx.write_xlsx(xlsx_path, img_secs, _TABLE_HEAD)
        except Exception as e:  # noqa: BLE001
            ops_note("round_xlsx_fail", "%s: %s" % (type(e).__name__, e))
            xlsx_path = None
        tgt = {"p1": p1, "p2": p2, "p3": p3}
        for s in img_secs:
            tg = tgt.get(s.get("target"), p1)
            # 2026-09-12 用户要求：正文必须带出完整表格信息（股票名/形态/技术指标/备注），
            # 不能只放「见长图」占位——图片 / CSV / XLSX 仍随消息附带，文本与图表同源互为备份。
            tg.append("")
            tg.extend(_readable_sec_lines(s))
            if s.get("target") == "p2":
                p2_extra = True
        if png_path:
            imgs.append(png_path)
            if xlsx_path and os.path.isfile(xlsx_path):
                imgs.append(xlsx_path)          # 全左对齐、可直接看（Excel/WPS 打开）
            if csv_path and os.path.isfile(csv_path):
                imgs.append(csv_path)           # 原始数据（程序/脚本消费）
            hit = set(s.get("target") for s in img_secs)
            for t in ("p1", "p2", "p3"):
                if t in hit:
                    tgt[t].append(
                        "  （以上文本与随附合并长图 / XLSX / 原始 CSV 同源，字段逐列一致）")

    # ── 整轮消息：默认合并为「1 条正文 + 1 张合并图」（2026-09-11，Item4）──
    body_p2 = "\n".join(p2).strip("\n") if p2_extra else ""
    body_p3 = "\n".join(p3).strip("\n")
    if merge_pages:
        body = "\n".join(p1).strip("\n")
        for _t, _b in (("📊 资金流向与低吸", body_p2), ("💼 实仓建议与做T", body_p3)):
            if _b:
                body += ("\n\n" if body else "") + "━━ %s ━━\n%s" % (_t, _b)
        pages = [("%s | %s" % (scene, asof), body)]
    else:
        pages = [("%s | %s" % (scene, asof), "\n".join(p1).strip("\n"))]
        if body_p2:
            pages.append(("📊 资金流向与低吸 | %s" % asof, body_p2))
        if body_p3:
            pages.append(("💼 实仓建议与做T | %s" % asof, body_p3))
    return pages, imgs


def send_wechat_round(data, ctx, cfg, old_secids=None, pos_advice=True,
                      table_img_dir=None, **kw):
    """整轮三段独立消息推送（排版见 round_pages，2026-09-05 新版消息结构）。

    - 主线 = ⭐ XML DAG 当日选股（与 exe 共用同一套 XML，等同 exe 选股，不再单列 exe 段）
    - 📋 对照参考（非主线）：CodeBuddy 引擎候选按命中率排序，精选前 3
    - 每票标色：🔴 = 引擎过筛/推荐候选；🟡 = 盘面在池但资金流出
    - 候选行附主流指标摘要(SAR/MACD/RSI/OBV 等)
    - 实仓段内嵌本轮消息（2026-09-06）：与上轮建议指纹相同仅一行概要「无新操作」，
      有变化(verdict/建议内容变化)才展开逐笔详情——每轮评估但不刷屏。
    """
    # 2026-09-08：候选表默认渲染成图片推送（微信文本表非等宽 → 错乱），文本给占位提示。
    if table_img_dir is None:
        table_img_dir = TABLE_IMG_DIR
    pages, imgs = round_pages(data, ctx, cfg, old_secids=old_secids,
                              pos_advice=pos_advice, table_img_dir=table_img_dir,
                              **kw)
    ok = False
    for title, content in pages:
        ok = _push_wechat(title, content, cfg) or ok
    for path in imgs:
        # 2026-09-12：imgs = [长图png, 表格xlsx, 原始csv] → 按后缀分派
        if str(path).lower().endswith((".csv", ".xlsx")):
            ok = push_channel.send_file(path, cfg) or ok
        else:
            ok = push_channel.send_image(path, cfg) or ok
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
        # 守护(10分钟)轮：候选构成/实仓建议有变化才完整推送（完整推含与上轮重复入选标的）。
        # 无变化则跳过微信消息，但仍上传 COS、写 LAST_FILE。首轮/换盘段首轮必推。
        sig = _cand_sig(data)
        rhythm = _load_rhythm()
        seg = "am" if current_session() == "am" else "pm"
        if rhythm.get("cand_sig") != sig or rhythm.get("push_seg") != seg:
            send_wechat_round(data, ctx, cfg, old_secids=old_secids, pos_advice=pos_advice,
                              cache=cache)
            rhythm["cand_sig"] = sig
            rhythm["push_seg"] = seg
            _save_rhythm(rhythm)
        else:
            print("[守护] 候选/实仓建议与上轮一致，跳过本轮微信推送（仍上传 COS）")
    else:
        # 盘中新信号：仅在新买点出现时推送
        send_wechat(data, old_secids, cfg)
    upload_candidates(data, candidates_key)
    with open(LAST_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    return 0


def _cand_sig(data):
    """候选变化指纹（推送闸门用，2026-09-08）：
    各档候选 secid 顺序 + 预备队 secid + 实仓(code:verdict) 摘要。
    归一化处理：价格/涨跌幅等数字变化不触发推送；只有候选构成或实仓建议状态变化才推。"""
    parts = []
    for p, items in (data.get("groups") or {}).items():
        parts.append("%s[%s]" % (p, ",".join(it.get("secid", "") for it in items)))
    parts.append("预[%s]" % ",".join(it.get("secid", "") for it in (data.get("prepared") or [])))
    pos = data.get("positions") or []
    parts.append("仓[%s]" % "|".join(
        "%s:%s" % (p.get("code", ""), p.get("verdict", "")) for p in pos))
    return "\n".join(parts)


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
# 手机 COS 镜像同步（2026-09-11）：拉 APK 上传的实仓 real_positions + 用户关注板块
# 到本地镜像，供 PC/exe 持仓评估与推送「📌 关注板块」消费。此前守护从不自动拉取。
PREP_CLOUD = os.path.join(HERE, "cloud_download.py")
PREP_DAG = os.path.normpath(os.path.join(ROOT, "AutoQuant", "usecase_screen.py"))
DAG_SCREEN_FILE = os.path.normpath(os.path.join(ROOT, "AutoQuant", "data", "dag_screen_latest.json"))
# 盘中实时快照层(2026-09-10)：11:31 午间用 qtg 批量实时 asof=今日 做当日盘中选股，
# 输出到独立文件（不覆盖收盘定格 latest）；15:00 尾盘固化日K后追加收盘定格重选(latest)。
DAG_LUNCH_FILE = os.path.normpath(os.path.join(ROOT, "AutoQuant", "data", "dag_screen_lunch.json"))
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
    d.setdefault("etf", False)    # 15:12 ETF低位useCase当日发布 + 推送行情到手机 是否完成
    d.setdefault("hit", {})       # 当日逐轮命中累计 period -> {secid: 次数}
    # 2026-09-11：命中 secid → 名称 快照。累计命中横跨全天，到收盘时该 secid 往往
    # 已不在当轮 data(prepared/groups) 里，只按当轮 data 反查会退化成裸 secid
    # （收盘总结「预备队」只有代码没名称）。累计时把名称一并落盘，结算时再兜底。
    d.setdefault("hit_name", {})
    d.setdefault("lunch", False)  # 11:31 午间盘中总结是否已推（2026-09-08）
    d.setdefault("lunch_snap", False)  # 11:31 午间实时快照选股是否已跑(每日一次, 失败不再重试)
    d.setdefault("snap_at", {})   # 各盘段最近一次盘中实时快照时刻 {"am":"HH:MM:SS"}(节流用)
    d.setdefault("cfz", False)    # 15:00 尾盘后的收盘定格全池重选是否完成(2026-09-10)
    d.setdefault("cand_sig", "")  # 候选构成/实仓建议指纹（10分钟轮推送闸门）
    d.setdefault("push_seg", "")  # 上次微信推送所在盘段（换盘段首轮必推）
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


# ── v3 时刻表辅助（2026-09-08 用户确认）───────────────────────────────────
# 交易日时刻表：
#   09:00        预热：K线增量下载 + 情报扫描(dry)暖缓存（美股隔夜/韩股开盘/快讯研报）
#   09:20        开盘前大盘速览：4指数同轴叠加图 + 强弱结论 + 🧭期货与库存速览
#   09:30~11:20  上午 12 轮（09:30 首次，之后每 10 分钟）
#   11:31-12:00  午间盘中总结（每日一次）
#   13:00~14:30  下午 10 轮（每 10 分钟）；14:30 后不再盘中选股，避免尾盘诱导
#   15:00        尾盘最后一次 K 线拉取（仅下载，不再选股）
#   15:10        收盘总结：当日选股汇总 + 持仓回顾 + 纪律 + 鼓励（无做T/买卖点指令）
#   15:12        ETF低位 usecase 当日发布（XML 单一源）+ 推送行情到手机 + 宽基ETF份额快照 + 拐点预警（每日一次）
# 盘中轮推送策略：候选构成/实仓建议有变化才推且完整推（含与上轮重复入选的标的），
# 无变化不推送（仍上传 COS）。守护/选股过程错误写入 _daemon_ops.jsonl。
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
    """守护档位：pre8(08:00-08:59) / pre(09:00-09:29) / am(09:30-11:30) / pm(13:00-14:30) / None。
    14:31 之后由 daemon 空档分支处理「尾盘拉取 + 收盘总结 + 15:20 复盘」。"""
    now = now or datetime.datetime.now()
    if now.weekday() >= 5:
        return None
    hm = now.hour * 60 + now.minute
    if hm < 8 * 60:
        return None
    if hm < 9 * 60:
        return "pre8"
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


def next_weekday_at(now, hour, minute=0):
    """下一个交易日的 hour:minute（跳过周末）。"""
    d = now.date() + datetime.timedelta(days=1)
    while d.weekday() >= 5:
        d += datetime.timedelta(days=1)
    return datetime.datetime.combine(d, datetime.time(hour, minute))


def _next_slot_at(now=None):
    """空档期下一个动作点。"""
    now = now or datetime.datetime.now()
    hm = now.hour * 60 + now.minute
    if hm < 8 * 60:
        return _today_at(8, 0)   # 08:00 盘前情报
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
    """睡到墙钟时刻 dt。每步用当前时间重算剩余，避免系统睡眠/休眠冻结
    time.sleep 递减计数导致"睡过头"（跨周末/整夜长睡必须按墙钟，勿改回递减式）。"""
    while True:
        if stop_check is not None and stop_check():
            return
        now = datetime.datetime.now()
        if now >= dt:
            return
        time.sleep(min(0.5, (dt - now).total_seconds()))


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


def _push_market_image(log=print, days=55, data=None, pct=True):
    """生成并推送「大盘4指数图」（企微 image；失败仅留痕，不影响文本推送）。

    pct=True（2026-09-09 用户选定）推起点归一化涨跌幅折线图——0% 对齐看四指数
    相对强弱/背离，比真实点位蜡烛更清晰；data 传现成刷新结果时图与正文用同一快照。
    """
    try:
        import _index_market_chart as imc
        if pct:
            ok, png = imc.render_pct_png(days=days, data=data)
        else:
            ok, png = imc.render_png(days=days, data=data)
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


def _pre_market_pick_table(log=print):
    try:
        import _daily_intel as di
        if not os.path.exists(di.INTEL_JSON):
            return None
        with open(di.INTEL_JSON, encoding="utf-8") as f:
            picks = json.load(f).get("picks") or []
        if not picks:
            return None
        out = os.path.join(di.TABLE_DIR, "_pre_picks_%s.png" % datetime.date.today().strftime("%Y%m%d"))
        return di.render_pick_table(picks, out, "开盘前候选标的")
    except Exception as e:  # noqa: BLE001
        log("候选标的表生成失败: %s" % e)
        return None


def _push_pre_market(dry, log=print):
    """09:20 开盘前：四指数两张图（归一化折线 + 同轴真实点位蜡烛）+ 强弱结论 + 🧭期货速览。

    2026-09-08/09 用户决策：正文不再发文本表格（微信里乱）。
    2026-09-09 起配图改为两张都发：
     ① 起点归一化涨跌幅折线图——四指数以窗口首日为 0% 对齐，看相对强弱/背离；
     ② 同轴真实点位日K蜡烛图（带 MA5/MA20）——看上证/科创50 等是否均线纠缠、形态。
    结论仍随文本给一句。四指数数据只在线拉一次（文本/两张图共用同一快照）。
    """
    try:
        import _index_market_chart as imc
        data = imc.refresh()
        text = ("📊 四指数图 ×2 见下方：\n"
                "① 起点归一化涨跌幅折线——0% 对齐看相对强弱/背离\n"
                "② 同轴真实点位日K蜡烛——看均线纠缠与形态\n\n"
                + imc.verdict_text(table=False, data=data))
        try:
            import _macro_futures as mf
            extra = mf.render_futures()
            if extra:
                text += "\n\n" + "\n".join(extra)
        except Exception as e:  # noqa: BLE001
            log("期货库存速览获取失败: %s" % e)
            ops_note("macro_fut_fail", repr(e))
        # 四根宏观哨兵（美债/日元/油价/费半）：越阈给定向风控动作，未越阈给状态行
        try:
            import _macro_sentinel as msent
            s_lines = msent.render_sentinel_lines()
            if s_lines:
                text += "\n\n" + "\n".join(s_lines)
        except Exception as e:  # noqa: BLE001
            log("宏观哨兵获取失败: %s" % e)
            ops_note("macro_sentinel_fail", repr(e))
        title = "🌅 开盘前大盘速览 %s" % datetime.date.today().strftime("%m-%d")
        # 候选标的表（图片）——取当日 08:00/09:00 情报选出的候选，正文仍为文字
        png_pick = _pre_market_pick_table(log)
        if dry:
            log("[dry] 开盘前大盘速览（不推送）：\n" + text)
            return True
        sent_txt = _push_wechat(title, text, load_notify_cfg())
        # 2026-09-09 用户选定：开盘前两张图都发——归一化折线(相对强弱) + 同轴蜡烛(均线纠缠/形态)
        sent_img1 = _push_market_image(log=log, data=data, pct=True)
        sent_img2 = _push_market_image(log=log, data=data, pct=False)
        sent_pick = False
        if png_pick:
            try:
                sent_pick = bool(push_channel.send_image(png_pick, load_notify_cfg()))
            except Exception as e:  # noqa: BLE001
                log("候选表推送失败：%s" % e)
        return sent_txt or sent_img1 or sent_img2 or sent_pick
    except Exception as e:  # noqa: BLE001
        log("开盘前大盘速览失败：%s" % e)
        ops_note("pre_msg_error", repr(e))
        return False


def _prefetch_indexes(log=print):
    """09:00 预热先行刷新四大指数缓存（data/_index_market.json 在线拉取）。

    保证 09:20 开盘速览（结论文本 + XY 轴蜡烛图）用的必然是最新收盘数据；
    在线失败自动回退本地缓存，且失败不阻塞预热主流程（09:20 速览还会再在线重试）。
    """
    try:
        import _index_market_chart as imc
        out = imc.refresh()
        parts = []
        for code, name in imc.INDEXES:
            snaps = (out.get(code) or {}).get("snaps") or []
            if snaps:
                last = snaps[-1]
                parts.append("%s %.0f(%+.1f%%)" % (name, last.get("close") or 0,
                                                   last.get("changePct") or 0))
        log("[09:00 预热] 四指数已刷新: %s" % (" | ".join(parts) if parts else "（无数据）"))
    except Exception as e:  # noqa: BLE001
        log("[09:00 预热] 四指数刷新失败（忽略，09:20 速览仍会在线重试）: %s" % e)


def run_pre_preheat(log=print, stop_check=None):
    """09:00 预热：K线增量下载 → 四指数先行拉取 → 情报扫描(dry 暖缓存)。"""
    log("[09:00 预热] 下载K线增量 + 四指数刷新 + 情报扫描暖缓存（美股/韩股开盘前）…")
    steps = ((PREP_DOWNLOAD, None),
             (os.path.join(HERE, "_market_scan.py"), ["--once", "--dry"]))
    for script, extra in steps:
        if stop_check is not None and stop_check():
            return False
        log("  ▶ %s" % os.path.basename(script))
        if not _run_script(script, log=log, stop_check=stop_check, extra=extra):
            return False
    _prefetch_indexes(log)
    rhythm_mark_flag("pre")
    log("[09:00 预热] 完成，等待 09:20 开盘速览 / 09:30 首轮选股")
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


def _run_python_dag(extra, log=print, stop_check=None, out=None):
    """跑 AutoQuant/usecase_screen.py 子进程并流式打日志。返回 bool。"""
    args = [sys.executable, PREP_DAG] + (["--out", out] if out else []) + extra
    try:
        proc = subprocess.Popen(args, cwd=os.path.dirname(PREP_DAG),
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace",
                                bufsize=1,
                                env={**os.environ, "PYTHONIOENCODING": "utf-8"})
        for line in proc.stdout:
            line = line.rstrip()
            if line:
                log("    | " + line)
        proc.wait()
        return proc.returncode == 0
    except Exception as e:  # noqa: BLE001
        log("usecase_screen 子进程异常：%s" % e)
        ops_note("dag_sub_error", repr(e))
        return False


SNAPSHOT_GAP_MIN = 0  # 盘中实时快照刷新间隔（分钟）。0 = 每轮必刷。
# 2026-09-12 用户要求「每一次选股都要更新」：守护轮本身已按 15 分钟一次（interval=900），
# 若再按 30 分钟节流，则相邻两轮共用同一份快照、候选列表整段不变。故取消节流，
# 每轮开始都重跑一次盘中实时快照（qtg 批量约 20s），使「当日选股」逐轮随盘刷新。


def _snap_should_refresh(session, now=None):
    """该盘段距上次实时快照是否已满 SNAPSHOT_GAP_MIN 分钟（无记录/间隔=0=该刷）。"""
    now = now or datetime.datetime.now()
    d = _load_rhythm()
    last = (d.get("snap_at") or {}).get(session) or ""
    if not last:
        return True
    try:
        hh, mm = int(last[:2]), int(last[3:5])
        last_dt = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
    except (ValueError, IndexError):
        return True
    return (now - last_dt).total_seconds() >= SNAPSHOT_GAP_MIN * 60


def _mark_snapshot(session, now=None):
    """记录本盘段最近一次实时快照时刻（供节流）。session=None(午间)时不动节流。"""
    if not session:
        return
    now = now or datetime.datetime.now()
    d = _load_rhythm()
    d.setdefault("snap_at", {})[session] = now.strftime("%H:%M:%S")
    _save_rhythm(d)


def run_intraday_snapshot(session=None, log=print, stop_check=None):
    """盘中实时快照选股（asof=今日，qtg 批量实时价，绝不写历史缓存）→ DAG_LUNCH_FILE。

    2026-09-11：原只在 11:31 跑一次（仅午间总结用）。而盘中各轮推送的「当日选股」
    主线读 dag_screen_latest.json —— 那是 15:00 收盘定格才有当日结果，于是 09:30-14:30
    一整天主线都是一份 T-1 结果（用户反馈「当日选股候选一天不变」）。现盘中每
    SNAPSHOT_GAP_MIN 分钟刷新一份今日实时快照，各轮/午间总结共用；失败保留上一份、
    不阻塞轮次（写 ops 留痕）。收盘定格仍由 run_close_freeze 写 latest。
    """
    if stop_check is not None and stop_check():
        return False
    today = datetime.date.today().strftime("%Y%m%d")
    log("[盘中快照] 实时选股 asof=%s：qtg 批量全池当日价 → XML DAG …" % today)
    if not _run_python_dag(["--snapshot", today], log=log, stop_check=stop_check,
                           out=DAG_LUNCH_FILE):
        ops_note("intraday_snap_fail", "盘中实时快照选股失败(本轮退回 T-1 主线)")
        return False
    _mark_snapshot(session)
    log("[盘中快照] 完成 → %s" % os.path.basename(DAG_LUNCH_FILE))
    return True


def run_lunch_snapshot(log=print, stop_check=None):
    """11:31 午间实时快照（每日一次；失败不重试、不阻塞午间总结，退回 T-1 主线）。"""
    log("[11:31 快照] 午间实时快照：全池当日价 → XML DAG asof=今日 …")
    if not run_intraday_snapshot(session=None, log=log, stop_check=stop_check):
        ops_note("lunch_snap_fail", "午间实时快照选股失败(退回T-1主线)")
        return False
    log("[11:31 快照] 午间实时快照完成 → %s" % DAG_LUNCH_FILE)
    return True


def run_close_freeze(log=print, stop_check=None):
    """15:00 尾盘固化日K后，追加一次收盘定格全池精筛（asof=今日）覆盖 dag_screen_latest。
    这是当日唯一权威收盘结果（此前盘中轮均基于 T-1 缓存/asof 昨日）。"""
    today = datetime.date.today().isoformat()
    log("[15:00 定格] 收盘定格重选：XML DAG 全池精筛 asof=%s …" % today)
    if stop_check is not None and stop_check():
        return False
    if not _run_python_dag(["--asof", today], log=log, stop_check=stop_check):
        ops_note("cfz_fail", "收盘定格重选失败(稍后轮次自动重试)")
        return False
    log("[15:00 定格] 收盘定格重选完成 → %s" % DAG_SCREEN_FILE)
    return True


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


def run_etf_publish(log=print):
    """15:12 ETF 低位 usecase 当日发布（XML 单一源选股）+ 推送行情到手机（每日一次）。

    与三周期同源：smalltools/_etf_publish.py 读 assets/usecases/etf_dip_usecase.xml
    （APK 与 Python 引擎共用），刷新 ETF 行情(腾讯 qfq) → 发布 data/_etf_live_picks.json
    → 尝试 adb 推送 etf_cache.json 到手机（无设备在线则跳过，不阻塞）。
    失败仅留痕，不影响守护流程。
    2026-09-13：顺带跑一次宽基 ETF 份额快照（见 _etf_share_snap），用于日频跟踪国家队。
    """
    log("[15:12 ETF] 刷新行情(--all) + etf_dip usecase 发布 + 推送手机…")
    try:
        # 2026-09-10 修：原为 --push（只发布不刷行情）→ 缓存长期停在旧交易日，
        # ETF 选股实际跑在过期 K 线上。改 --all = 刷新行情(腾讯 qfq) + 发布 + 推送。
        proc = subprocess.Popen(
            [sys.executable, os.path.join(HERE, "_etf_publish.py"), "--all"],
            cwd=HERE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1)
        lines = []
        while True:
            ln = proc.stdout.readline()
            if not ln:
                break
            lines.append(ln.rstrip())
            log("  " + ln.rstrip())
        proc.wait()
        if proc.returncode == 0:
            log("…ETF usecase 发布完成（工作台 ETF 页可看当日名单）")
            _etf_share_snap(log)
            return True
        ops_note("etf_publish_fail", "退出码=%d %s" % (proc.returncode, " | ".join(lines[-3:])))
    except Exception as e:  # noqa: BLE001
        log("ETF usecase 发布异常：%s" % e)
        ops_note("etf_publish_error", repr(e))
    return False


def _etf_share_snap(log=print):
    """15:12 顺带追加一次宽基 ETF 份额快照 —— 自建「日频」国家队（汇金/证金）观测序列。

    ★ 免费源（天天基金 gmbd）只给**季度**份额，没有日频历史份额，
      所以日频序列只能从今天起靠每个交易日追加累积（data/_etf_share_daily.json）。
      时点选 15:12：收盘后当日份额已定。非阻塞：失败只留痕，不影响守护流程。
    2026-09-13 追加：份额「由升转降 / 由降转升」拐点（或机构主导赎回）出现时，
      再单独推一条微信（见 _etf_share_alert）—— 这是唯一能**当日**看到国家队进出的通道
      （个股股东名册要等季报，滞后 1~3 个月）。
    """
    log("[15:12 ETF] 宽基 ETF 份额快照（国家队日频观测）…")
    proc = None
    try:
        proc = subprocess.Popen(
            [sys.executable, os.path.join(HERE, "_etf_share_flow.py"), "--snap", "--sync-apk"],
            cwd=HERE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace")
        out, _ = proc.communicate(timeout=240)
        for ln in (out or "").strip().splitlines()[-4:]:
            log("  " + ln.rstrip())
        if proc.returncode == 0:
            log("…份额快照已追加（data/_etf_share_daily.json）")
            _etf_share_alert(log)
            return True
        ops_note("etf_share_snap_fail", "退出码=%d" % proc.returncode)
    except Exception as e:  # noqa: BLE001
        log("ETF 份额快照异常：%s" % e)
        ops_note("etf_share_snap_error", repr(e))
        if proc is not None:
            try:
                proc.kill()
            except Exception:  # noqa: BLE001
                pass
    return False


_ETFSIG_SENT = os.path.join(ROOT, "data", "_etf_share_alert.json")


def _etf_share_alert(log=print):
    """读 _etf_share_signal.json：出现份额拐点 / 机构主导赎回 → 单独推一条微信。

    ★ 幂等：以 data/_etf_share_alert.json 记账「已推送的日期」，同一交易日只推一次
      （守护可能重启，故不能只用内存标记）。
    """
    try:
        with open(os.path.join(ROOT, "data", "_etf_share_signal.json"), encoding="utf-8") as f:
            sig = json.load(f) or {}
    except Exception as e:  # noqa: BLE001
        log("  份额拐点信号读取失败：%s" % e)
        return False
    if not sig.get("push"):
        log("  份额拐点：无（%s）" % ("；".join(sig.get("why") or []) or "未见拐点"))
        return False
    day = str(sig.get("as_of") or "")[:10]
    sent_day = ""
    try:
        with open(_ETFSIG_SENT, encoding="utf-8") as f:
            sent_day = (json.load(f) or {}).get("day") or ""
    except (OSError, ValueError):
        sent_day = ""
    if sent_day == day:
        log("  份额拐点：%s 当日已推送过，跳过" % day)
        return False
    body = "\n".join(_national_etf_lines(max_lines=4))
    why = sig.get("why") or []
    if why:
        body += "\n\n触发：" + "；".join(why)
    try:
        ok = _push_wechat("🏛 国家队ETF份额拐点 %s" % day, body, load_notify_cfg())
        if ok:
            with open(_ETFSIG_SENT, "w", encoding="utf-8") as f:
                json.dump({"day": day, "why": why}, f, ensure_ascii=False, indent=1)
        log("  份额拐点推送%s：%s" % ("成功" if ok else "失败", "；".join(why) or "-"))
        return ok
    except Exception as e:  # noqa: BLE001
        log("  份额拐点推送异常：%s" % e)
        ops_note("etf_share_alert_error", repr(e))
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
    hit_name = d.setdefault("hit_name", {})

    def _acc(period, it):
        sid = it.get("secid")
        if not sid:
            return
        m = hit.setdefault(period, {})
        m[sid] = m.get(sid, 0) + 1
        nm = (it.get("name") or "").strip()
        if nm and not hit_name.get(sid):
            hit_name[sid] = nm

    for period, items in (data.get("groups") or {}).items():
        for it in items:
            _acc(period, it)
    for it in data.get("prepared") or []:
        _acc("预备队", it)
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
    # 2026-09-11：按间隔先刷新今日盘中实时快照，使本轮「当日选股」主线随盘变化
    # （此前整天读 T-1 定格）。dry 预演不触发子进程；快照失败仅留痕、本轮照跑。
    if not dry and _snap_should_refresh(slot):
        run_intraday_snapshot(session=slot, log=log)
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
    # ② 当日多轮入选（名称兜底：累计命中名 + 全池缓存名，避免只剩代码）
    if hits:
        names = _name_index(cache, r.get("hit_name"))
        segs = []
        for period, sidmap in sorted(hits.items(), key=lambda kv: -sum(kv[1].values())):
            top = sorted(sidmap.items(), key=lambda kv: -kv[1])[:5]
            items_txt = ", ".join("%s×%d" % (_find_name(data, sid, names), c)
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
    # ⑤ 四根宏观哨兵（收盘定格：WTI 当日值入连续天数账，越阈给次日定向动作）
    try:
        import _macro_sentinel as msent
        s_lines = msent.render_sentinel_lines()
        if s_lines:
            lines.append("")
            lines.extend(s_lines)
    except Exception as e:  # noqa: BLE001
        log("收盘宏观哨兵失败: %s" % e)
        ops_note("macro_sentinel_fail", repr(e))
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
            # 收盘后附带「大盘4指数 起点归一化涨跌幅折线图」（图片单独一条，
            # 企微机器人支持；失败仅留痕，不影响总结本体）
            _push_market_image(log=log)
        return ok_txt
    except Exception as e:  # noqa: BLE001
        log("收盘总结 推送失败：%s" % e)
        ops_note("eod_push_error", repr(e))
        return False


def _push_lunch_summary(dry, use_ctx, log):
    """11:31 午间盘中总结（2026-09-08 新增，每日一次）：上午轮次 + XML DAG 主线
    + 上午多轮共振 + 持仓快照 + 午后节奏提示。无做T/买卖点指令。"""
    try:
        cache = load_cache()
        industry = build_industry()
        ctx = build_context() if use_ctx else None
        data = build_candidates(cache, industry, ctx=ctx)
    except Exception as e:  # noqa: BLE001
        log("午间总结 构建异常：%s" % e)
        ops_note("lunch_build_error", repr(e))
        return False
    if not data:
        log("午间总结：无有效数据（缓存为空？）")
        return False
    r = _load_rhythm()
    n_am = r.get("rounds", {}).get("am", 0)
    hits = r.get("hit", {})
    asof = data.get("asof", "")
    lines = ["📈 午间盘中总结 asof=%s" % asof,
             "上午完成 %d 轮选股（09:30 起每10分钟）；13:00 恢复每10分钟选股" % n_am]
    # ① 主线 XML DAG（午间优先展示 11:31 实时快照 asof=今日，退回 T-1 latest）
    dag = _load_intraday_dag()
    if dag and (dag.get("result") or {}):
        rows = []
        is_snap = dag.get("mode") == "snapshot_intraday"
        for period in ("超短", "短线", "中线", "长线"):
            its = (dag.get("result") or {}).get(period) or []
            if not its:
                continue
            names = ", ".join(
                (it.get("name") or (it.get("code") or "?")).strip() for it in its[:6])
            rows.append("%s(%d): %s" % (period, len(its), names))
        if rows:
            title = "⭐ 主线·午间实时快照(今日盘中)" if is_snap else "⭐ 主线 XML DAG"
            lines.append(title + "\n  " + " | ".join(rows))
    # ② 上午多轮共振（名称兜底同上）
    if hits:
        names = _name_index(cache, r.get("hit_name"))
        segs = []
        for period, sidmap in sorted(hits.items(), key=lambda kv: -sum(kv[1].values())):
            top = sorted(sidmap.items(), key=lambda kv: -kv[1])[:4]
            txt = ", ".join("%s×%d" % (_find_name(data, sid, names), c)
                            for sid, c in top if c > 1)
            if txt:
                segs.append("%s: %s" % (period, txt))
        if segs:
            lines.append("📊 上午多轮入选（>1次共振参考）")
            lines.extend("  · " + s for s in segs)
    # ③ 持仓快照
    pos = data.get("positions") or []
    pf = data.get("portfolio") or {}
    pnl_all = pf.get("pnl_pct") if pf else None
    if pos:
        state_cn = {"止损警戒": "破位风险", "减仓警戒": "仓位偏高", "减仓应对": "回调应对",
                    "加仓候选": "强度尚可", "持有": "持有观察", "持有观察": "观察",
                    "数据不足": "数据不足"}
        lines.append("💼 持仓（%d 笔，整体%s）：" % (
            len(pos), ("%+.1f%%" % pnl_all) if pnl_all is not None else "数据不足"))
        for p in pos[:6]:
            pnl = ("%+.1f%%" % p["pnl_pct"]) if p.get("pnl_pct") is not None else "-"
            note = p.get("note") or ""
            note0 = note.splitlines()[0][:30] if note else ""
            lines.append("  %s %s(%s) 盈亏%s %s" % (
                state_cn.get(p["verdict"], p["verdict"]), p.get("name", ""),
                p.get("code", ""), pnl, note0))
    else:
        lines.append("💼 当前无实仓持仓记录")
    # ④ 四根宏观哨兵（午间刷新实时：日元/费半/油价，越阈给定向动作）
    try:
        import _macro_sentinel as msent
        s_lines = msent.render_sentinel_lines()
        if s_lines:
            lines.append("")
            lines.extend(s_lines)
    except Exception as e:  # noqa: BLE001
        log("午间宏观哨兵失败: %s" % e)
        ops_note("macro_sentinel_fail", repr(e))
    lines.append("")
    lines.append("💡 午后 13:00 恢复每10分钟选股：候选构成有更新才推送（含与上午重复入选标的"
                 "），无新信号不重复打扰。")
    content = "\n".join(lines)
    if dry:
        log("[dry] 午间盘中总结预览（不推送）：\n" + content)
        return True
    try:
        return _push_wechat("☀️ 盘中总结 %s" % asof, content, load_notify_cfg())
    except Exception as e:  # noqa: BLE001
        log("午间总结推送失败：%s" % e)
        ops_note("lunch_push_error", repr(e))
        return False


def run_prep(session, interval=600, log=print, stop_check=None):
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
    # 2026-09-11：同步手机 COS 镜像（实仓 real_positions + 用户关注板块）。PC 侧 exe 与
    # 推送的页3 实仓、📌关注板块都读本地镜像 `_records/cloud`/`user_focus_sectors.json`，
    # 而此前守护从不自动拉取 → 镜像长期陈旧（实测停在 09-05，实仓评估失真）。
    # 纯拉取，失败只留痕、不阻塞首刷（离线/无配置时沿用上一份镜像）。
    if not (stop_check is not None and stop_check()):
        if not _run_script(PREP_CLOUD, log=log, stop_check=stop_check,
                           extra=["--max", "1", "--focus"]):
            log("  ! 手机镜像同步失败（沿用旧镜像：实仓/关注板块可能滞后）")
            ops_note("prep_cloud_fail", "手机COS镜像同步失败(实仓/关注板块沿用旧镜像)")
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


EXPECT_ROUNDS = {"am": 9, "pm": 7}  # 09:30-11:30 9轮 / 13:00-14:30 7轮，每15分


def _check_rounds(log=print):
    """15:00 尾盘核对当日轮次：少于时刻表期望则留痕告警（多为交易时段休眠/掉轮）。"""
    r = _load_rhythm()
    got = r.get("rounds", {})
    bad = {}
    for k, want in EXPECT_ROUNDS.items():
        if got.get(k, 0) < want:
            bad[k] = got.get(k, 0)
    if bad:
        msg = "  [warn] 轮次核对：%s（可能电脑在交易时段休眠/进程冻结掉轮）" % "、".join(
            "%s %d/%d 轮" % ("上午" if k == "am" else "下午", v, EXPECT_ROUNDS[k])
            for k, v in sorted(bad.items()))
        log(msg)
        ops_note("miss_rounds", msg)
    else:
        log("  ✓ 轮次核对：上午%d/9 下午%d/7 达标" % (got.get("am", 0), got.get("pm", 0)))


def daemon_serve(prep=True, interval=900, dry=False, use_ctx=True,
                 log=print, stop_check=None):
    """盘段守护 v4（2026-09-11 用户确认节奏）：

    08:00 盘前情报（宏观 + 美股收盘 → 利好利空板块判定 → 候选标的 → 推送+存库）
        → 09:00 预热（K线下载+情报扫描暖缓存）+ 亚太情报（日经/KOSPI/恒生实时→同上）
        → 09:20 开盘前大盘速览（4指数同轴叠加图+强弱结论+🧭期货与库存速览）
        → 09:30-11:30 每 15 分钟选股（09:30 首次，开盘立刻选股）
            * 候选构成/实仓建议有变化才推送；无变化跳过（仍上传 COS 供 APK 查询）
            * 若推送则完整推送（含与上轮重复入选的标的）
        → 11:31-12:00 午间盘中总结（每日一次）
        → 13:00-14:30 每 15 分钟（之后不再盘中选股）
        → 15:00 尾盘最后一次 K 线拉取（仅下载）
        → 15:10 收盘总结：当日选股+持仓回顾+纪律+鼓励（无做T/买卖点指令）
        → 15:12 ETF低位 usecase 当日发布（XML 单一源）+ 推送行情到手机
        → 15:20 收盘复盘（表格化：板块判定对错/当日选股/近5日巡诊/实仓镜像）
    盘中情报：独立 _market_scan.py 守护同频扫描，有变动才推送。
    盘中轮推送不再含「实仓买卖/做T」建议；守护/选股错误写入 _daemon_ops.jsonl。
    """
    log("盘段守护 v4：08:00 盘前情报 → 09:00 亚太情报 → 09:20 盘前速览 → 09:30 起每15分选股 "
        "→ 11:31 午间总结 → 13:00 起每15分 → 15:00 尾盘 → 15:10 收盘总结 → 15:20 表格化复盘")
    while stop_check is None or not stop_check():
        now = datetime.datetime.now()
        # ── 周末：睡到下一交易日 08:00 ──
        if now.weekday() >= 5:
            nxt = next_weekday_at(now, 8, 0)
            log("[%s] 周末 → 下一交易日 %s 08:00 盘前情报" % (
                now.strftime("%m-%d %H:%M"), nxt.strftime("%m-%d")))
            _sleep_until(nxt, stop_check)
            continue
        slot = _slot_of(now)
        # ── 08:00-08:59 盘前情报（宏观 + 美股收盘 → 利好利空板块 → 候选标的 → 存库）──
        if slot == "pre8":
            if rhythm_need_flag("pre8"):
                if now.hour * 60 + now.minute < 8 * 60:
                    _sleep_until(_today_at(8, 0), stop_check)
                    continue
                try:
                    import _daily_intel
                    _daily_intel.run_slot("pre8", dry=dry, log=log)
                    rhythm_mark_flag("pre8")
                    log("✓ 08:00 盘前情报完成")
                except Exception as e:  # noqa: BLE001
                    log("08:00 盘前情报失败：%s（2 分钟后重试）" % e)
                    _interruptible_sleep(120, stop_check)
                continue
            _sleep_until(_today_at(9, 0), stop_check)
            continue
        # ── 09:00-09:29 预热 ──
        if slot == "pre":
            if prep and rhythm_need_flag("pre"):
                if not run_pre_preheat(log=log, stop_check=stop_check):
                    log("[09:00 预热] 失败，2 分钟后重试")
                    _interruptible_sleep(120, stop_check)
                    continue
            # 09:00 亚太情报：宏观 + 日经/KOSPI/恒生/台湾/新加坡/澳洲 + 韩国权重股
            if rhythm_need_flag("pre9"):
                try:
                    import _daily_intel
                    _daily_intel.run_slot("pre9", dry=dry, log=log)
                    rhythm_mark_flag("pre9")
                    log("✓ 09:00 亚太情报完成")
                except Exception as e:  # noqa: BLE001
                    log("09:00 亚太情报失败：%s（不阻塞开盘）" % e)
            # 09:20 开盘前大盘速览（每日一次）：4指数同轴叠加图 + 强弱结论 + 期货库存
            if rhythm_need_flag("pre_msg"):
                if now.hour * 60 + now.minute < 9 * 60 + 20:
                    _sleep_until(_today_at(9, 20), stop_check)
                    continue
                _push_pre_market(dry, log)
                rhythm_mark_flag("pre_msg")
            _sleep_until(_today_at(9, 30), stop_check)
            continue
        # ── 交易盘段：首刷 → 每 interval 秒(默认10分钟)轮 ──
        if slot in ("am", "pm"):
            if prep and rhythm_need_prep(slot):
                run_prep(slot, interval=interval, log=log, stop_check=stop_check)
                continue
            r = _load_rhythm()
            n_r = r.get("rounds", {}).get(slot, 0)
            last_at = r.get("round_at", {}).get(slot)
            do_round = (n_r == 0)   # 本段首轮：首刷完成后立即选股
            if not do_round and last_at:
                # 距上轮 ≥ interval 即轮（不限于整刻）：守护短轮询若被系统休眠
                # 冻结，唤醒后只要仍在本段内即可补跑错过的轮，避免整段缺轮。
                try:
                    lt = datetime.datetime.combine(
                        now.date(),
                        datetime.datetime.strptime(last_at, "%H:%M:%S").time())
                    if (now - lt).total_seconds() >= interval - 30:
                        do_round = True
                except ValueError:
                    do_round = True
            if do_round:
                _do_round(slot, dry, use_ctx, log)
                continue
            _interruptible_sleep(5, stop_check)
            continue
        # ── 空档（开盘前/午休/14:30 后）──
        hm = now.hour * 60 + now.minute
        # 11:31-12:00 午间盘中总结（每日一次）
        if 11 * 60 + 31 <= hm <= 12 * 60 and rhythm_need_flag("lunch"):
            if rhythm_need_flag("lunch_snap"):
                run_lunch_snapshot(log=log, stop_check=stop_check)
                rhythm_mark_flag("lunch_snap")  # 只尝试一次，失败不阻塞总结
            if _push_lunch_summary(dry, use_ctx, log):
                rhythm_mark_flag("lunch")
                log("✓ 午间盘中总结完成")
            else:
                log("午间盘中总结失败，2 分钟后重试")
                _interruptible_sleep(120, stop_check)
            continue
        if hm >= 15 * 60:
            if rhythm_need_flag("tail"):
                if run_tail_kline(log=log, stop_check=stop_check):
                    if rhythm_need_flag("cfz"):
                        if run_close_freeze(log=log, stop_check=stop_check):
                            rhythm_mark_flag("cfz")
                _check_rounds(log)
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
            # 15:12：ETF 低位 usecase 当日发布 + 推送行情到手机（每日一次）
            if rhythm_need_flag("etf"):
                if hm < 15 * 60 + 12:
                    _sleep_until(_today_at(15, 12), stop_check)
                    continue
                if run_etf_publish(log=log):
                    rhythm_mark_flag("etf")
                    log("✓ ETF usecase 当日发布 + 手机行情推送完成")
                else:
                    log("ETF usecase 发布失败，2 分钟后重试")
                    _interruptible_sleep(120, stop_check)
                continue
            # 15:20：收盘复盘（表格化）a 板块判定对错 / b 当日选股 / c 近5日巡诊 / d 实仓镜像
            if rhythm_need_flag("rev"):
                if hm < 15 * 60 + 20:
                    _sleep_until(_today_at(15, 20), stop_check)
                    continue
                try:
                    import _daily_intel
                    _daily_intel.push_review(dry=dry, log=log)
                    rhythm_mark_flag("rev")
                    log("✓ 15:20 表格化复盘完成")
                except Exception as e:  # noqa: BLE001
                    log("15:20 复盘失败：%s（2 分钟后重试）" % e)
                    _interruptible_sleep(120, stop_check)
                continue
            # 当日流程完毕 → 次日 08:00 盘前情报
            _sleep_until(next_weekday_at(now, 8, 0), stop_check)
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
    ap.add_argument("--daemon", action="store_true", help="盘段守护 v4（固定时刻表，见 daemon_serve 注释）")
    ap.add_argument("--interval", type=int, default=900,
                    help="盘中轮间隔秒（v4 默认 900=15 分钟，首轮/尾轮仍随盘段整点）")
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
        print("盘段守护 v4：08:00 盘前情报 → 09:00 亚太情报 → 09:20 盘前速览 → "
              "09:30 起每15分选股 → 11:31 午间总结 → 13:00 起每15分 → 15:00 尾盘 → "
              "15:10 收盘总结 → 15:20 表格化复盘")
        daemon_serve(prep=not args.no_prep, interval=args.interval,
                     dry=args.dry, use_ctx=not args.no_ctx)
        return 0
    return run_once(dry=args.dry, candidates_key=args.key, timed_push=args.timed,
                    use_ctx=not args.no_ctx, pos_advice=not args.no_pos)


if __name__ == "__main__":
    sys.exit(main())
