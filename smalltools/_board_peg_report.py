# -*- coding: utf-8 -*-
"""板块 PEG / 营收增速 早报（2026-09-16 用户需求，参考 选股思路/PEG_营收增速_超跌反转.txt）。

每天 08:00 盘前情报之后自动跑一次（daemon 接入见 _publish_candidates v2 时刻表），
也可手动：
  python _board_peg_report.py               # 全板块（行业+子板块+概念）前10大市值 → xlsx + 微信
  python _board_peg_report.py --dry         # 只落盘/打印，不推送
  python _board_peg_report.py --kinds industry,concept --top 5

数据链：
  1. 板块 = data/board_index.json（industry 102 / concept 18 / subindustry 20，
     覆盖核心池 505 只；「直接通过板块选」口径，不用 ETF 折射——ETF 只有 27 只
     白名单覆盖面远小于板块索引）；
  2. 每板块成分按总市值取前 N（默认 10，龙头口径同 _industry_leader_map）；
  3. 腾讯 qt.gtimg 批量（复用 _sector_quote.quotes）：现价/今日%/总市值/静态PE/PB；
  4. 东财 datacenter 业绩报表 RPT_LICO_FN_CPD：营收同比 YSTZ / 净利同比 SJLTZ /
     加权 ROE WEIGHTAVG_ROE（全市场分页拉，当日缓存 data/_em_perf_YYYYMMDD.json，
     缺失票自动回落上一报告期补一轮）；
  5. 距60日高：kline_store 日K现算（超跌反转参考条件）。

口径（与用户 txt 一致）：
  PEG = 静态PE ÷ 营收同比增速%（百分数数值）。PE≤0 或 增速≤0 → 「—」。
  提示：PEG<1 且 营收增速>30% → 「低估+高增」；距60日高≤-25% → 叠加「超跌」。

输出：data/board_peg/板块PEG早报_YYYYMMDD.xlsx（一张表全部板块，板块名在首列可筛选）
      + 微信推送文字摘要 + xlsx 文件。
"""
import argparse
import datetime
import json
import os
import sys
import time

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import push_channel                     # noqa: E402
from _sector_quote import quotes        # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(ROOT, "data")
OUT_DIR = os.path.join(DATA_DIR, "board_peg")
BOARD_INDEX = os.path.join(DATA_DIR, "board_index.json")

EM_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get"
EM_HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
DEFAULT_KINDS = ("industry", "subindustry", "concept")

# 列：板块 在首列（xlsx 里可按板块筛选），name/code 由 write_xlsx 的行格式承担
PEG_HEAD = ["板块", "现价", "今日%", "距60日高%", "总市值(亿)", "PE静", "PB",
            "营收同比%", "净利同比%", "ROE%", "PEG", "提示"]


def _to_f(v):
    try:
        f = float(v)
        return f if f == f else None
    except (TypeError, ValueError):
        return None


def _report_periods(today=None):
    """候选报告期（近两期，降序）。披露法定截止：一季报04-30 / 中报08-31 /
    三季报10-31 / 年报次年04-30。取「已过截止日」的最近两期，保证数据完整。"""
    today = today or datetime.date.today()
    cands = []
    y = today.year
    for yy in (y, y - 1):
        cands += [datetime.date(yy, 9, 30), datetime.date(yy, 6, 30),
                  datetime.date(yy, 3, 31), datetime.date(yy - 1, 12, 31)]
    deadline = {  # 报告期 → 披露截止日
        (9, 30): lambda d: datetime.date(d.year, 10, 31),
        (6, 30): lambda d: datetime.date(d.year, 8, 31),
        (3, 31): lambda d: datetime.date(d.year, 4, 30),
        (12, 31): lambda d: datetime.date(d.year + 1, 4, 30),
    }
    ok = [d for d in cands if d <= today and deadline[(d.month, d.day)](d) <= today]
    ok.sort(reverse=True)
    return ok[:2]


def _em_fetch_period(rd, log=print):
    """拉东财某报告期全市场业绩（分页），返回 {code6: {ystz, sjltz, roe}}。"""
    out = {}
    page = 1
    while True:
        try:
            r = requests.get(EM_URL, timeout=15, headers=EM_HEADERS, params={
                "reportName": "RPT_LICO_FN_CPD",
                "columns": "SECURITY_CODE,REPORTDATE,YSTZ,SJLTZ,WEIGHTAVG_ROE",
                "filter": "(REPORTDATE='%s')" % rd.isoformat(),
                "pageSize": 500, "pageNumber": page,
                "sortColumns": "SECURITY_CODE", "sortTypes": "1",
            })
            d = (r.json().get("result") or {})
        except Exception as e:  # noqa: BLE001
            log("  东财业绩拉取失败 p%d：%s" % (page, e))
            break
        rows = d.get("data") or []
        for row in rows:
            out[str(row.get("SECURITY_CODE") or "")] = {
                "ystz": _to_f(row.get("YSTZ")),
                "sjltz": _to_f(row.get("SJLTZ")),
                "roe": _to_f(row.get("WEIGHTAVG_ROE")),
            }
        pages = int(d.get("pages") or 0)
        if not rows or (pages and page >= pages):
            break
        page += 1
        time.sleep(0.25)
    return out


def em_perf_map(need_codes=(), log=print):
    """{code6: 业绩dict}，当日缓存。主力报告期缺的票用上一期补一轮。"""
    today = datetime.date.today()
    cache = os.path.join(DATA_DIR, "_em_perf_%s.json" % today.strftime("%Y%m%d"))
    periods = _report_periods(today)
    if os.path.isfile(cache):
        try:
            with open(cache, encoding="utf-8") as f:
                d = json.load(f)
            if d.get("period") == periods[0].isoformat():
                log("  东财业绩：读当日缓存（%d 只）" % len(d.get("perf") or {}))
                return d.get("perf") or {}
        except Exception:  # noqa: BLE001
            pass
    perf = _em_fetch_period(periods[0], log=log)
    log("  东财业绩 %s：%d 只" % (periods[0], len(perf)))
    if periods[1:]:
        missing = [c for c in need_codes if c not in perf]
        if missing and len(missing) > 20:   # 缺太多才补（个别停牌/新股不值得整轮拉）
            prev = _em_fetch_period(periods[1], log=log)
            for c in missing:
                if c in prev:
                    perf[c] = prev[c]
            log("  上一期 %s 补齐 %d/%d 只" % (
                periods[1], sum(1 for c in missing if c in prev), len(missing)))
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        with open(cache, "w", encoding="utf-8") as f:
            json.dump({"period": periods[0].isoformat(), "perf": perf},
                      f, ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        log("  业绩缓存写盘失败：%s" % e)
    return perf


def _dd60_from_store(store, secid):
    """距60日高%（负=低于60日高点，口径同推送表 _pos60）。"""
    try:
        snaps = (store.get(secid) or {}).get("snaps") or []
        if len(snaps) < 20 or not snaps[-1].get("close"):
            return None
        closes = [s["close"] for s in snaps if s.get("close")]
        if not closes:
            return None
        hi = max(closes[-min(len(closes), 60):])
        return (closes[-1] / hi - 1.0) * 100 if hi else None
    except Exception:  # noqa: BLE001
        return None


def _fmt(v, suffix="", digits=1, plus=False):
    if v is None:
        return "—"
    return ("%+." + str(digits) + "f%s") % (v, suffix) if plus else \
           ("%." + str(digits) + "f%s") % (v, suffix)


def build_rows(kinds=DEFAULT_KINDS, top=10, log=print):
    """(sections, stat)。sections 供 write_xlsx / _table_csv 复用。"""
    with open(BOARD_INDEX, encoding="utf-8") as f:
        idx = json.load(f)
    kinds = [k for k in kinds if k in idx]
    # union 去重，一次拉行情
    union = {}
    for k in kinds:
        for bname, codes in (idx.get(k) or {}).items():
            for s in codes:
                union.setdefault(s, None)
    cands = [(s, s[2:], "") for s in union]
    log("  腾讯行情：%d 只（%s）…" % (len(cands), ",".join(kinds)))
    q = quotes(cands)
    log("  行情到位 %d 只" % len(q))
    perf = em_perf_map([c for c, _, _ in cands], log=log)

    store = None
    try:
        import _kline_store
        store = _kline_store.load_store()
    except Exception as e:  # noqa: BLE001
        log("  kline_store 加载失败（距60日高列显示—）：%s" % e)

    sections, n_rows, n_peg1, n_g50 = [], 0, 0, 0
    all_rows = []
    kind_label = {"industry": "行业板块", "subindustry": "子板块", "concept": "概念板块"}
    for k in kinds:
        rows = []
        for bname, codes in sorted((idx.get(k) or {}).items()):
            mem = [(s, q.get(s) or {}) for s in codes]
            mem = [(s, qq) for s, qq in mem if qq.get("mcap_yi")]
            mem.sort(key=lambda t: -(t[1].get("mcap_yi") or 0))
            for s, qq in mem[:top]:
                code6 = s[2:]
                pf = perf.get(code6) or {}
                ystz = pf.get("ystz")
                sjltz = pf.get("sjltz")
                roe = pf.get("roe")
                pe = qq.get("pe_static")
                pe_t = qq.get("pe_ttm")
                pb = qq.get("pb")
                dd60 = _dd60_from_store(store, s) if store else None
                # PEG = 静态PE / 营收同比%（txt 口径；PE≤0 或增速≤0 → —）
                # 微利股静态PE 可达数千（如 8264/14.2% → PEG 580 无意义），>50 视为无效
                peg = None
                if pe and pe > 0 and ystz and ystz > 0:
                    peg = pe / ystz
                    if peg > 50:
                        peg = None
                tags = []
                if peg is not None and peg < 1 and (ystz or 0) > 30:
                    tags.append("低估+高增")
                elif peg is not None and peg < 1:
                    tags.append("PEG<1")
                if (ystz or 0) >= 50:
                    tags.append("高增")
                if dd60 is not None and dd60 <= -25:
                    tags.append("超跌")
                cells = [
                    bname,
                    _fmt(qq.get("price"), digits=2),
                    _fmt(qq.get("pct"), "%", 2, plus=True),
                    _fmt(dd60, "%", 1, plus=True),
                    _fmt(qq.get("mcap_yi"), digits=0),
                    _fmt(pe, digits=1), _fmt(pb, digits=2),
                    _fmt(ystz, "%", 1, plus=True),
                    _fmt(sjltz, "%", 1, plus=True),
                    _fmt(roe, "%", 1),
                    _fmt(peg, digits=2),
                    "·".join(tags) if tags else "",
                ]
                rows.append({"name": qq.get("name") or code6, "code": code6,
                             "cells": cells, "_peg": peg, "_ystz": ystz,
                             "_pe_ttm": pe_t, "_dd60": dd60, "_board": bname,
                             "_kind": kind_label.get(k, k)})
                n_rows += 1
                if peg is not None and peg < 1:
                    n_peg1 += 1
                if (ystz or 0) >= 50:
                    n_g50 += 1
            if not rows:
                continue
        sections.append({"title": "%s（%d 板块 × 前%d 大市值）" % (
            kind_label.get(k, k), len(idx.get(k) or {}), top), "rows": rows})
        all_rows += rows
    return sections, {"boards": sum(len(idx.get(k) or {}) for k in kinds),
                      "rows": n_rows, "peg1": n_peg1, "g50": n_g50,
                      "period": _report_periods()[0].isoformat()}, all_rows


def _brief(all_rows, stat, top=10):
    """微信文字摘要：总览 + 全池 PEG 最低 top + 低估+高增清单。
    同一股票跨多板块（如中际旭创 ∈ 通信设备/光模块/AI算力…）只保留首个板块
    （sections 顺序 industry → subindustry → concept，行业归属优先）。"""
    lines = ["【板块 PEG/营收增速早报】报告期 %s" % stat["period"], ""]
    lines.append("板块 %d 个 · 龙头 %d 只：PEG<1 共 %d 只，营收增速≥50%% 共 %d 只" % (
        stat["boards"], stat["rows"], stat["peg1"], stat["g50"]))
    seen = set()
    uniq_rows = []
    for r in all_rows:
        if r["code"] in seen:
            continue
        seen.add(r["code"])
        uniq_rows.append(r)
    peg_rows = [r for r in uniq_rows if r["_peg"] is not None]
    peg_rows.sort(key=lambda r: r["_peg"])
    if peg_rows:
        lines.append("")
        lines.append("◆ 全池 PEG 最低 TOP%d：" % top)
        for r in peg_rows[:top]:
            lines.append("  %s(%s)·%s  PEG %.2f  营收%+.0f%%  PE(TTM)%s  %s" % (
                r["name"], r["code"], r["_board"], r["_peg"], r["_ystz"] or 0,
                _fmt(r["_pe_ttm"], digits=1), r["cells"][3]))
    stars = [r for r in uniq_rows if "低估+高增" in (r["cells"][-1] or "")]
    stars.sort(key=lambda r: (r["_ystz"] or 0), reverse=True)
    if stars:
        lines.append("")
        lines.append("◆ 低估+高增（PEG<1 且营收增速>30%%）%d 只，增速前%d：" % (
            len(stars), min(10, len(stars))))
        for r in stars[:10]:
            extra = " ←超跌" if "超跌" in (r["cells"][-1] or "") else ""
            lines.append("  %s(%s)·%s  营收%+.0f%% 净利%s ROE%s%s" % (
                r["name"], r["code"], r["_board"], r["_ystz"] or 0,
                r["cells"][8], r["cells"][9], extra))
    lines.append("")
    lines.append("口径：PEG=静态PE/营收增速（%%)；板块=board_index（核心池505只）前%d大市值；"
                 "完整 %d 行明细见 xlsx。" % (10, stat["rows"]))
    return "\n".join(lines)


def run(dry=False, log=print, kinds=DEFAULT_KINDS, top=10):
    t0 = time.time()
    log("[%s] 板块 PEG 早报构建…" % datetime.datetime.now().strftime("%H:%M:%S"))
    sections, stat, all_rows = build_rows(kinds=kinds, top=top, log=log)
    if not all_rows:
        log("板块 PEG 早报：无数据，跳过")
        return False
    os.makedirs(OUT_DIR, exist_ok=True)
    today = datetime.date.today().strftime("%Y%m%d")
    xlsx_path = os.path.join(OUT_DIR, "板块PEG早报_%s.xlsx" % today)
    xlsx_out = None
    try:
        import _table_xlsx as txlsx
        xlsx_out = txlsx.write_xlsx(xlsx_path, sections, PEG_HEAD)
        log("  xlsx：%s" % xlsx_out)
    except Exception as e:  # noqa: BLE001
        log("  xlsx 生成失败：%s" % e)
    content = _brief(all_rows, stat)
    if dry:
        log("[dry] 摘要预览：\n%s" % content)
        return bool(xlsx_out)
    pushed = False
    try:
        cfg = push_channel.load_notify_cfg()
        pushed = bool(push_channel.push("📊 板块PEG早报 %s" % today, content, cfg))
        if xlsx_out and os.path.isfile(xlsx_out):
            pushed = bool(push_channel.send_file(xlsx_out, cfg)) and pushed
    except Exception as e:  # noqa: BLE001
        log("  推送失败：%s" % e)
    log("[%s] 板块 PEG 早报完成：%d 行，推送%s（耗时%.0fs）" % (
        datetime.datetime.now().strftime("%H:%M:%S"), stat["rows"],
        "成功" if pushed else "失败", time.time() - t0))
    return pushed


def main():
    ap = argparse.ArgumentParser(description="板块 PEG / 营收增速 早报")
    ap.add_argument("--dry", action="store_true", help="只落盘/打印，不推送")
    ap.add_argument("--kinds", default=",".join(DEFAULT_KINDS),
                    help="板块类型：industry,subindustry,concept（逗号分隔）")
    ap.add_argument("--top", type=int, default=10, help="每板块按市值取前 N（默认10）")
    args = ap.parse_args()
    kinds = tuple(k.strip() for k in args.kinds.split(",") if k.strip())
    ok = run(dry=args.dry, kinds=kinds, top=args.top)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
