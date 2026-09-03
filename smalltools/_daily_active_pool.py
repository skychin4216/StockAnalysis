# -*- coding: utf-8 -*-
"""每日活跃池发现 —— 用「榜单 Top」替代「全市场 56 页快照」的开盘前轻量流程。

背景：_market_snapshot.py 抓全市场 4600+ 只需要建池/换池时跑（低频），
市值与行业属性几天内基本不变，每天都翻 56 页是浪费。而震荡期的小市值炒作
（用户经验：炒的永远是 成交/换手/涨幅 榜前排 + 机构偏好股）必然出现在
当日榜单 Top 内，因此每日只需要 4~6 个 1 页请求即可捕获市场焦点。

本脚本每日产出：
  1. StockAnalysis/data/_daily_hot.json        -- 当日榜单快照
       {date, amount_top[100], turnover_top[100], gain_top[100],
        month_sectors[行业20日榜], concept_sectors[概念20日榜]}
  2. StockAnalysis/data/_daily_hot_history.json -- 历史上榜天数累计（多日连续上榜=市场焦点）
  3. 与核心池(_kline_cache.json)对比 → 控制台输出「池外新晋活跃股」
  4. --fetch-candidates: 把池外多日上榜/成交前20的候选从 market_data.db 补 K 线（每日只几只，轻量）

用法：
  python _daily_active_pool.py                    # 只刷新当日榜单 + 对比提示
  python _daily_active_pool.py --fetch-candidates # 补候选 K 线进 market_data.db
  python _daily_active_pool.py --json             # 打印候选清单(供上游脚本对接)
"""
import argparse
import json
import os
import sys
import time
from collections import defaultdict

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _market_db  # noqa: E402

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "Referer": "https://quote.eastmoney.com/"}
PX = {"http": None, "https": None}
HOST = "https://push2delay.eastmoney.com/api/qt/clist/get"
FS_A = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23"   # 沪深A
FS_IND = "m:90+t:2+f:!50"                     # 东财行业板块
FS_CON = "m:90+t:3+f:!50"                     # 东财概念板块

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DAILY_HOT = os.path.join(ROOT, "data", "_daily_hot.json")
HIST_FILE = os.path.join(ROOT, "data", "_daily_hot_history.json")
CACHE_FILE = os.path.join(HERE, "_kline_cache.json")
DB = os.path.join(ROOT, "data", "market_data.db")

# 榜单抓取页大小（东财实际每页上限 100）
PAGE = 100
# 板块榜取多少名
SECTOR_TOPN = 30
# 历史保留多少天
HIST_KEEP_DAYS = 120
# 过滤新股/次新（N/C 前缀）与 ST、退
IGNORE_PREFIX = ("N", "C", "N!")
STOCK_FIELDS = "f2,f3,f6,f8,f12,f14,f20,f21,f100"
SECTOR_FIELDS = "f2,f3,f12,f14,f109,f128,f140"


def _clist(fs, fid, po, pz=PAGE, fields=None):
    params = {"pn": 1, "pz": pz, "po": po, "np": 1,
              "ut": "bd1d9ddb04089700cf9c27f6f7426281",
              "fltt": 2, "invt": 2, "fid": fid, "fs": fs,
              "fields": fields or STOCK_FIELDS}
    q = "&".join("%s=%s" % (k, v) for k, v in params.items())
    r = requests.get(HOST + "?" + q, timeout=15, headers=UA, proxies=PX)
    d = r.json().get("data") or {}
    return d.get("diff") or []


def _num(v):
    try:
        x = float(v or 0)
        return x if x == x else 0.0
    except (TypeError, ValueError):
        return 0.0


def board_of(code):
    if code.startswith(("688", "689")):
        return "科创"
    if code.startswith(("300", "301")):
        return "创业"
    return "主板"


def _pick_stock(it):
    code = str(it.get("f12") or "")
    name = str(it.get("f14") or "").replace(" ", "")
    if len(code) != 6:
        return None
    if name[:1] in IGNORE_PREFIX or "ST" in name.upper() or "退" in name:
        return None
    return {
        "secid": ("sh" if code[0] in "69" else "sz") + code,
        "name": name,
        "board": board_of(code),
        "price": _num(it.get("f2")),
        "chg_pct": _num(it.get("f3")),
        "amount_yi": _num(it.get("f6")) / 1e8,
        "turnover": _num(it.get("f8")),
        "mv_total_yi": _num(it.get("f20")) / 1e8,
        "mv_float_yi": _num(it.get("f21")) / 1e8,
        "industry": str(it.get("f100") or ""),
    }


def _pick_sector(it, src):
    return {
        "name": str(it.get("f14") or ""),
        "code": str(it.get("f12") or ""),
        "chg20_pct": _num(it.get("f109")),
        "chg_pct": _num(it.get("f3")),
        "leader": str(it.get("f128") or ""),
        "leader_code": str(it.get("f140") or ""),
        "src": src,
    }


def fetch_daily():
    """4~6 个 1 页请求，返回当日榜单快照。"""
    def dedup(rows):
        seen, out = set(), []
        for r in rows:
            if r and r["secid"] not in seen:
                seen.add(r["secid"])
                out.append(r)
        return out

    amount = [_pick_stock(i) for i in _clist(FS_A, "f6", 1)]            # 成交额降序
    turnover = [_pick_stock(i) for i in _clist(FS_A, "f8", 1)]          # 换手率降序
    gain = [_pick_stock(i) for i in _clist(FS_A, "f3", 1)]              # 当日涨幅
    try:
        month_stock = [_pick_stock(i) for i in _clist(FS_A, "f109", 1)]  # 近20日涨幅(若支持)
    except Exception:
        month_stock = []
    ind = [_pick_sector(i, "行业") for i in _clist(FS_IND, "f109", 1, fields=SECTOR_FIELDS)]
    con = [_pick_sector(i, "概念") for i in _clist(FS_CON, "f109", 1, fields=SECTOR_FIELDS)]
    return {
        "amount_top": dedup([r for r in amount if r])[:PAGE],
        "turnover_top": dedup([r for r in turnover if r])[:PAGE],
        "gain_top": dedup([r for r in gain if r])[:PAGE],
        "month_stock_top": [r for r in (month_stock or []) if r][:PAGE],
        "sector_top": ([r for r in ind if r and not r["name"].endswith(("Ⅱ", "Ⅲ"))] +
                       [r for r in con if r])[:SECTOR_TOPN * 2],
        "month_sectors": [r for r in (ind + con) if r and r["name"]][:SECTOR_TOPN * 2],
    }


def load_history():
    try:
        with open(HIST_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"days": [], "hits": {}}  # days: 日期列表(新在前)  hits: {secid: 上榜次数累计}


def update_history(hot):
    """把当日 成交额/换手/涨幅 三榜合并为 secid→上榜榜数(0-3)，计入历史。"""
    hist = load_history()
    hits = hist.get("hits", {})
    today = hot["date"]
    days = hist.get("days", [])
    if days and days[0] == today:
        return hist  # 当日已记录
    merged = {}
    for key in ("amount_top", "turnover_top", "gain_top"):
        for it in hot.get(key, []):
            sid = it["secid"]
            merged[sid] = merged.get(sid, 0) + 1
    for sid, n in merged.items():
        h = hits.setdefault(sid, {"days": 0, "last_date": "", "max_rank_bonus": 0.0})
        h["days"] += 1
        h["last_date"] = today
    days.insert(0, today)
    # 清理很久未上榜的
    if len(days) > HIST_KEEP_DAYS:
        cutoff = days[HIST_KEEP_DAYS - 1]
        hits = {sid: h for sid, h in hits.items()
                if h.get("last_date", "") >= cutoff}
        days = days[:HIST_KEEP_DAYS]
    hist["days"], hist["hits"] = days, hits
    with open(HIST_FILE, "w", encoding="utf-8") as f:
        json.dump(hist, f, ensure_ascii=False)
    return hist


def load_core_pool():
    try:
        with open(CACHE_FILE, encoding="utf-8") as f:
            return set(json.load(f).keys())
    except (OSError, ValueError):
        return set()


def compare_pool(hot, hist):
    """与核心池对比，返回池外活跃候选（多日上榜或成交额前20）。"""
    core = load_core_pool()
    hits = hist.get("hits", {})
    cand = []
    for it in (hot.get("amount_top") or [])[:20]:   # 成交额前20必看
        if it["secid"] not in core:
            cand.append((it, hits.get(it["secid"], {}).get("days", 1)))
    for it in (hot.get("turnover_top") or [])[:40]:  # 换手高=震荡炒作前排
        if it["secid"] not in core:
            days = hits.get(it["secid"], {}).get("days", 0)
            if days >= 2:
                cand.append((it, days))
    seen, out = set(), []
    for it, days in cand:
        if it["secid"] not in seen:
            seen.add(it["secid"])
            it["hit_days"] = days
            out.append(it)
    out.sort(key=lambda x: (-x["hit_days"], -(x.get("amount_yi") or 0)))
    return out


def fetch_candidates(cands):
    """对候选补 K 线：db 已有直接复用，没有才拉取。返回 (from_db, fetched)。"""
    from_db, fetched, con = 0, 0, None
    if os.path.exists(DB):
        con = _market_db.get_conn(DB)
    for it in cands:
        sid = it["secid"]
        have = 0
        if con:
            have = con.execute("SELECT COUNT(*) FROM kline WHERE secid=?", (sid,)).fetchone()[0]
        if have >= 20:
            from_db += 1
            continue
        # 拉四年全量
        from _add_leaders import fetch_full
        name, snaps, src = fetch_full(sid)
        if snaps:
            if con:
                _market_db.upsert_kline(con, sid, snaps, src=src, name=name or it["name"])
            else:
                print("  (无db) %s 拉取 %d 根" % (sid, len(snaps)))
            fetched += 1
            print("  补池 %s %s(%s) %d根 src=%s" % (it["name"], sid, it["board"], len(snaps), src))
        time.sleep(0.2)
    if con:
        con.close()
    return from_db, fetched


def main():
    ap = argparse.ArgumentParser(description="每日活跃池(榜单发现，替代全市场快照)")
    ap.add_argument("--fetch-candidates", action="store_true", help="补池外候选 K 线进 market_data.db")
    ap.add_argument("--json", action="store_true", help="只输出候选清单 json 供上游对接")
    args = ap.parse_args()

    hot = fetch_daily()
    hot["date"] = time.strftime("%Y-%m-%d")
    # 小市值炒作焦点持久化到榜单 JSON（供轮动统计/报告脚本引用）
    hist0 = load_history()
    h0 = hist0.get("hits", {})
    smf = [it for it in (hot.get("turnover_top") or []) + (hot.get("gain_top") or [])
           if (it.get("mv_total_yi") or 0) <= 150
           and ((it.get("turnover") or 0) >= 8 or h0.get(it["secid"], {}).get("days", 0) >= 2)]
    hot["small_focus"] = smf[:60]
    os.makedirs(os.path.dirname(DAILY_HOT), exist_ok=True)
    with open(DAILY_HOT, "w", encoding="utf-8") as f:
        json.dump(hot, f, ensure_ascii=False)
    hist = update_history(hot)
    hits = hist.get("hits", {})
    cands = compare_pool(hot, hist)

    print("当日榜单落盘: %s" % DAILY_HOT)
    print("  成交额榜 Top: %s" % " ".join(i["name"] for i in (hot["amount_top"] or [])[:8]))
    print("  换手榜 Top:   %s" % " ".join(i["name"] for i in (hot["turnover_top"] or [])[:6]))
    print("  涨幅榜 Top:   %s" % " ".join(i["name"] for i in (hot["gain_top"] or [])[:6]))
    sec = [s for s in hot.get("month_sectors") or [] if s.get("src") == "行业"][:8]
    print("  近20日行业热门: %s" % " ".join("%s%+.1f%%" % (s["name"], s["chg20_pct"]) for s in sec))
    print("  历史焦点股(多日上榜)累计 %d 只" % len(hist.get("hits", {})))

    # 震荡期小市值炒作焦点：市值<=150亿 且 换手高或多日上榜（用户经验：炒的永远是榜前排）
    small_focus = [it for it in (hot.get("turnover_top") or []) + (hot.get("gain_top") or [])
                   if (it.get("mv_total_yi") or 0) <= 150
                   and ((it.get("turnover") or 0) >= 8 or hits.get(it["secid"], {}).get("days", 0) >= 2)]
    seen_sf = set()
    small_focus_u = []
    for it in small_focus:
        if it["secid"] not in seen_sf:
            seen_sf.add(it["secid"])
            small_focus_u.append(it)
    small_focus_u.sort(key=lambda x: (-(x.get("turnover") or 0), -hits.get(x["secid"], {}).get("days", 0)))

    print("\n小市值炒作焦点 %d 只 (≤150亿 且 高换手/多日上榜):" % len(small_focus_u))
    for it in small_focus_u[:12]:
        d = hits.get(it["secid"], {}).get("days", 0)
        print("  %-6s %s(%s) 换手%.1f%% 市值%.0f亿 涨%+.1f%% 上榜%d天 %s" % (
            it["name"], it["secid"], it["board"], it.get("turnover") or 0,
            it.get("mv_total_yi") or 0, it.get("chg_pct") or 0, d, it.get("industry") or ""))

    print("\n池外活跃候选 %d 只:" % len(cands))
    for it in cands[:15]:
        print("  %-6s %s(%s) 连续上榜%d天 成交%.1f亿 换手%.1f%% 市值%.0f亿 %s" % (
            it["name"], it["secid"], it["board"], it["hit_days"],
            it.get("amount_yi") or 0, it.get("turnover") or 0,
            it.get("mv_total_yi") or 0, it.get("industry") or ""))
    if args.json:
        print(json.dumps([{"secid": i["secid"], "name": i["name"], "board": i["board"],
                           "hit_days": i["hit_days"], "mv_total_yi": i.get("mv_total_yi"),
                           "industry": i.get("industry")} for i in cands], ensure_ascii=False))
    if args.fetch_candidates and cands:
        fb, fe = fetch_candidates(cands)
        print("补池完成: db已有 %d 只, 新拉取 %d 只" % (fb, fe))


if __name__ == "__main__":
    main()
