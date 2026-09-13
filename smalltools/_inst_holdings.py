# -*- coding: utf-8 -*-
"""机构持续加仓 —— 季报数据抓取 + 机构/散户判定（规则出处《机构持续加仓自动选股池.txt》）。

数据源（2026-09-12 实测确认，东方财富数据中心，免 token、免 akshare）：
  ① RPT_MAIN_ORGHOLD    机构持股一览表（全市场 / 单报告期，约 2 万行/期）
       SECURITY_CODE / SECURITY_NAME_ABBR / REPORT_DATE / ORG_TYPE / ORG_TYPE_NAME
       HOULD_NUM(机构家数) / TOTAL_SHARES / HOLD_VALUE
       FREESHARES_RATIO(占流通股%) / TOTALSHARES_RATIO(占总股本%)
       HOLDCHA(新进/增仓/减仓) / HOLDCHA_NUM(增减股数) / HOLDCHA_RATIO(增减比例%)
     ORG_TYPE：00=全部机构 01=基金 02=QFII 03=社保 04=券商 05=保险 06=信托 07=其他
  ② RPT_HOLDERNUMLATEST 股东户数（全市场一次拉取，约 5500 行）
       HOLDER_NUM / PRE_HOLDER_NUM / HOLDER_NUM_CHANGE / HOLDER_NUM_RATIO(环比%)
       END_DATE / AVG_MARKET_CAP / AVG_HOLD_NUM / TOTAL_MARKET_CAP
     ★ 户数下降=筹码集中(机构收集)，户数暴增=散户涌入(机构派发) —— 「散户票」判别关键。

产物：
  data/institutional_cache.csv   格式与参考方案「方式1」完全一致（人工/同花顺导出可覆盖）：
      code,name,period,hold_ratio,hold_ratio_change,n_funds
      一只股票多期多行（「连续加仓期数」需要多期）。
  data/_inst_holdings.json       全市场判定明细（各机构类型分档 + 股东户数 + 分级），
                                 供 usecase / 推送 17 列表 / 决策工作台 Excel 复用。

三档降级（与参考方案一致，算法与数据源完全解耦）：
  L1 真实接口（requests 直连，不依赖 akshare）→ 成功即落盘并回写 CSV
  L2 本地缓存 CSV（institutional_cache.csv；人工填 / 同花顺导出向导粘贴）→ 手填优先
  L3 内置 mock → 离线兜底，算法始终可跑（--mock 自检）

判定口径（同参考方案）：
  口径A（严格）连续 ≥2 期，持股比例环比增持 > 0.5pp
  口径B（宽松）全程净增持 > 1pp（数据稀疏时兜底）
  机构加仓分 ins(0~100) = 持仓水平分(≤30) + 本期环比分(≤25)
                          + 连续增持期数×12(≤30) + 基金家数环比(±5)
  集中度   conc(0~100) = 持仓占比分(≤60) + 股东户数下降分(≤40)
  综合分   score = 0.6×ins + 0.4×conc
  分级      A=精选（口径A 成立 且 score≥75）｜B=观察（(口径A或B) 且 score≥50）｜C=排除

★ 重要提醒（参考方案原文）：机构持仓季度披露、天然滞后 1-3 月，只能做中长线趋势判断，
  不可作实时信号；机构加仓 ≠ 必涨，必须与「趋势规律7条 + 止损」三系统共振才入场。

用法：
  python _inst_holdings.py                  # 抓最近 3 期（多抓 1 期做环比基准）→ 落盘 + 摘要
  python _inst_holdings.py --periods 4      # 抓更多期（连续加仓期数更准）
  python _inst_holdings.py --csv-only       # 只把 CSV 读成 JSON（L2，不联网）
  python _inst_holdings.py --mock           # 内置 mock 跑通算法（L3 离线自检）
  python _inst_holdings.py --update-pool    # 每周自动更新：抓取→判定→同步 APK assets 副本
  python _inst_holdings.py --pool "300308,600487"   # 指定清单打印机构判定
"""
import csv
import datetime as dt
import gzip
import json
import os
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA_DIR = os.path.join(ROOT, "data")
ASSET_DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")
CSV_FILE = os.path.join(DATA_DIR, "institutional_cache.csv")
OUT_FILE = os.path.join(DATA_DIR, "_inst_holdings.json")
RAW_FILE = os.path.join(DATA_DIR, "_inst_raw.json.gz")
ASSET_OUT = os.path.join(ASSET_DATA, "_inst_holdings.json")

CSV_HEAD = ["code", "name", "period", "hold_ratio", "hold_ratio_change", "n_funds"]

BASE = "https://datacenter-web.eastmoney.com/api/data/v1/get"
HEAD = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Referer": "https://data.eastmoney.com/"}
PAGESIZE = 500
ORG_NAMES = {"00": "全部机构", "01": "基金", "02": "QFII", "03": "社保",
             "04": "券商", "05": "保险", "06": "信托", "07": "其他"}


# ═══════════════════════ 基础网络 ═══════════════════════

def get_json(url, params=None, tries=4):
    for i in range(tries):
        try:
            j = requests.get(url, params=params, timeout=30, headers=HEAD).json()
            if j.get("success") or j.get("result"):
                return j
        except Exception:  # noqa: BLE001
            pass
        time.sleep(0.5 * (i + 1))
    return {}


def _f(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def _i(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


# ═══════════════════════ 报告期 ═══════════════════════

def quarter_ends_back(n=4, today=None):
    """由近及远生成 n 个「已披露」季报日（YYYY-MM-DD，含披露滞后 45 天）。"""
    d = today or dt.date.today()
    qs, y = [], d.year
    for _ in range(n + 3):
        for m, day in ((12, 31), (9, 30), (6, 30), (3, 31)):
            q = "%04d-%02d-%02d" % (y, m, day)
            end = dt.date(y, m, day)
            if end <= d and (d - end).days >= 45 and q not in qs:
                qs.append(q)
        y -= 1
    return sorted(qs, reverse=True)[:n]


# ═══════════════════════ 抓取 ═══════════════════════

def fetch_org_hold(period, log=print):
    """RPT_MAIN_ORGHOLD 单报告期全市场 → {code: {name, types{org_type: {...}}}}。"""
    out, page, pages = {}, 1, None
    while True:
        j = get_json(BASE, {
            "reportName": "RPT_MAIN_ORGHOLD", "columns": "ALL",
            "source": "WEB", "client": "WEB", "pageNumber": page, "pageSize": PAGESIZE,
            "sortColumns": "SECURITY_CODE", "sortTypes": "1",
            "filter": "(REPORT_DATE='%s')" % period})
        res = j.get("result") or {}
        rows = res.get("data") or []
        if not rows:
            break
        if pages is None:
            pages = res.get("pages") or 1
            log("  [ORGHOLD %s] %s 行 / %d 页" % (period, res.get("count") or 0, pages))
        if page % 10 == 0:
            log("    · %s 第 %d/%d 页" % (period, page, pages or 1), flush=True)
        for r in rows:
            code = str(r.get("SECURITY_CODE") or "")
            if not code:
                continue
            rec = out.setdefault(code, {"code": code,
                                        "name": r.get("SECURITY_NAME_ABBR") or "",
                                        "types": {}})
            ot = str(r.get("ORG_TYPE") or "")
            rec["types"][ot] = {
                "name": ORG_NAMES.get(ot, r.get("ORG_TYPE_NAME") or ot),
                "n": _i(r.get("HOULD_NUM")),
                "ratio": round(_f(r.get("FREESHARES_RATIO")), 4),         # 占流通股%
                "ratio_total": round(_f(r.get("TOTALSHARES_RATIO")), 4),   # 占总股本%
                "cha": r.get("HOLDCHA") or "",
                "cha_num": _f(r.get("HOLDCHA_NUM")),
                "cha_ratio": round(_f(r.get("HOLDCHA_RATIO")), 2),
                "value": _f(r.get("HOLD_VALUE")),
            }
        if page >= (pages or 1):
            break
        page += 1
    return out


def fetch_holder_num(log=print):
    """RPT_HOLDERNUMLATEST 股东户数（全市场）→ {code: {...}}。"""
    out, page, pages = {}, 1, None
    while True:
        j = get_json(BASE, {
            "reportName": "RPT_HOLDERNUMLATEST", "columns": "ALL",
            "source": "WEB", "client": "WEB", "pageNumber": page, "pageSize": PAGESIZE,
            "sortColumns": "SECURITY_CODE", "sortTypes": "1"})
        res = j.get("result") or {}
        rows = res.get("data") or []
        if not rows:
            break
        if pages is None:
            pages = res.get("pages") or 1
            log("  [HOLDERNUM] %s 行 / %d 页" % (res.get("count") or 0, pages))
        if page % 4 == 0:
            log("    · HOLDERNUM 第 %d/%d 页" % (page, pages or 1), flush=True)
        for r in rows:
            code = str(r.get("SECURITY_CODE") or "")
            if not code:
                continue
            out[code] = {
                "num": _i(r.get("HOLDER_NUM")),
                "prev_num": _i(r.get("PRE_HOLDER_NUM")),
                "chg": _i(r.get("HOLDER_NUM_CHANGE")),
                "chg_pct": round(_f(r.get("HOLDER_NUM_RATIO")), 2),
                "end": (r.get("END_DATE") or "")[:10],
                "prev_end": (r.get("PRE_END_DATE") or "")[:10],
                "avg_cap": round(_f(r.get("AVG_MARKET_CAP")), 1),
                "avg_hold": round(_f(r.get("AVG_HOLD_NUM")), 1),
            }
        if page >= (pages or 1):
            break
        page += 1
    return out


def _agg_types(t):
    """机构合计：优先 ORG_TYPE=00；缺失时各类型求和兜底。"""
    if t.get("00"):
        return t["00"]
    acc = {"name": "全部机构", "n": 0, "ratio": 0.0, "ratio_total": 0.0,
           "cha": "", "cha_num": 0.0, "cha_ratio": 0.0, "value": 0.0}
    for k, v in t.items():
        if k == "00":
            continue
        acc["n"] += v.get("n", 0)
        acc["ratio"] += v.get("ratio", 0.0)
        acc["ratio_total"] += v.get("ratio_total", 0.0)
        acc["value"] += v.get("value", 0.0)
    acc["ratio"] = round(acc["ratio"], 4)
    acc["ratio_total"] = round(acc["ratio_total"], 4)
    return acc if acc["n"] else None


# ═══════════════════════ 判定算法（纯函数，离线可复用） ═══════════════════════

def _clamp(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)


def judge_series(series, holder=None):
    """多期持股比例序列 → 机构判定。

    series: [{"period","hold_ratio","hold_ratio_change","n_funds"}...]（period 升序）
    holder: 股东户数记录（可选，RPT_HOLDERNUMLATEST）
    返回 {ok_a, ok_b, ins, conc, score, grade, streak, net_chg, latest_ratio,
          latest_chg, verdict, hold_trend, series}
    """
    s = [x for x in (series or []) if x.get("hold_ratio") is not None]
    s.sort(key=lambda x: str(x.get("period") or ""))
    if not s:
        return {"ok_a": False, "ok_b": False, "ins": 0.0, "conc": 0.0, "score": 0.0,
                "grade": "C", "streak": 0, "n_up": 0, "net_chg": 0.0, "latest_ratio": 0.0,
                "latest_chg": 0.0, "verdict": "无季报数据", "hold_trend": "", "series": []}

    ratios = [_f(x.get("hold_ratio")) for x in s]
    funds = [_i(x.get("n_funds")) for x in s]
    chgs = []
    for i, x in enumerate(s):
        c = x.get("hold_ratio_change")
        chgs.append(_f(c) if c is not None else (0.0 if i == 0 else ratios[i] - ratios[i - 1]))

    latest_ratio, latest_chg = ratios[-1], chgs[-1]
    net_chg = ratios[-1] - ratios[0]
    n_up = sum(1 for c in chgs if c > 0.5)
    streak = 0
    for c in reversed(chgs):
        if c > 0.5:
            streak += 1
        else:
            break

    ok_a = streak >= 2          # 连续 ≥2 期环比增持 > 0.5pp
    ok_b = net_chg > 1.0        # 全程净增持 > 1pp

    ins = _clamp(latest_ratio / 30.0, 0.0, 1.0) * 30.0           # 持仓水平
    ins += _clamp((latest_chg + 2.0) / 4.0, 0.0, 1.0) * 25.0     # 本期环比
    ins += min(streak * 12.0, 30.0)                              # 持续性（最高权重）
    if len(funds) >= 2:
        ins += 5.0 if funds[-1] > funds[-2] else (-5.0 if funds[-1] < funds[-2] else 0.0)
    ins = round(_clamp(ins, 0.0, 100.0), 1)

    conc = _clamp(latest_ratio / 40.0, 0.0, 1.0) * 60.0          # 持仓占比
    if holder and holder.get("chg_pct") is not None:
        conc += _clamp(-_f(holder.get("chg_pct")) / 10.0, 0.0, 1.0) * 40.0   # 户数下降
    else:
        conc += 20.0                                             # 无户数数据给中性分
    conc = round(_clamp(conc, 0.0, 100.0), 1)

    score = round(0.6 * ins + 0.4 * conc, 1)

    if not ok_b and latest_chg < -0.5:
        grade, verdict = "C", "机构连续减仓·排除"
    elif ok_a and score >= 75:
        grade, verdict = "A", "机构真加仓·强趋势"
    elif (ok_a or ok_b) and score >= 50:
        grade, verdict = "B", "机构温和参与·观察"
    else:
        grade, verdict = "C", "机构撤退/散户票"

    return {"ok_a": ok_a, "ok_b": ok_b, "ins": ins, "conc": conc, "score": score,
            "grade": grade, "streak": streak, "n_up": n_up,
            "net_chg": round(net_chg, 4), "latest_ratio": round(latest_ratio, 4),
            "latest_chg": round(latest_chg, 4), "verdict": verdict,
            "hold_trend": "→".join("%.2f" % r for r in ratios),
            "series": [{"period": x.get("period"),
                        "hold_ratio": round(_f(x.get("hold_ratio")), 4),
                        "n_funds": _i(x.get("n_funds"))} for x in s]}


def grade_label(rec):
    """判定 → 推送 17 列表「机构股」列的短标签。"""
    if not rec:
        return "—"
    g = rec.get("grade") or "C"
    mark = {"A": "A·机构加仓", "B": "B·机构参与", "C": "C·散户票"}.get(g, "—")
    if g == "C" and (rec.get("latest_chg") or 0) < -0.5:
        mark = "C·机构撤退"
    return mark


# ═══════════════════════ L1：抓取 → 快照 ═══════════════════════

def build_snapshot(periods=3, log=print, reuse=False):
    """抓 periods+1 个报告期（多抓 1 期作环比基准，环比全部用真实前后差）→ 快照 dict。

    reuse=True 时优先读 data/_inst_raw.json.gz（上次抓取的原始分页结果，免重复联网）。
    """
    got, holder = {}, {}
    if reuse and os.path.exists(RAW_FILE):
        try:
            with gzip.open(RAW_FILE, "rt", encoding="utf-8") as f:
                raw = json.load(f)
            got, holder = raw.get("org") or {}, raw.get("holder") or {}
            log("  [reuse] 复用 %s（%d 期）" % (os.path.basename(RAW_FILE), len(got)))
        except Exception:  # noqa: BLE001
            got, holder = {}, {}

    used = []
    if got:
        used = sorted(got.keys(), reverse=True)[:periods + 1]
        got = {k: got[k] for k in used}
    else:
        for p in quarter_ends_back(periods + 3):
            if len(used) >= periods + 1:
                break
            d = fetch_org_hold(p, log=log)
            if d:
                got[p] = d
                used.append(p)
            else:
                log("  [ORGHOLD %s] 无数据，跳过" % p)
    if len(used) < 2:
        log("  ✗ 可用报告期不足 2 期，改用 L2/L3")
        return None
    if not holder:
        log("  [HOLDERNUM] 拉取股东户数…")
        holder = fetch_holder_num(log=log)
    if not reuse:
        try:
            with gzip.open(RAW_FILE, "wt", encoding="utf-8") as f:
                json.dump({"asof": dt.date.today().isoformat(), "org": got,
                           "holder": holder}, f, ensure_ascii=False,
                          separators=(",", ":"))
            log("  [raw] 原始快照 → %s" % os.path.basename(RAW_FILE))
        except Exception:  # noqa: BLE001
            pass

    series_periods = sorted(used, reverse=True)[:periods]        # 暴露期（近→远）
    ref = sorted(used, reverse=True)[:periods + 1]               # 多一层做环比基准
    base = got[series_periods[0]]
    stocks = {}
    for code, cur in base.items():
        row = []
        for i, p in enumerate(series_periods):
            pprev = ref[i + 1] if i + 1 < len(ref) else None
            t = ((got.get(p) or {}).get(code) or {}).get("types") or {}
            agg = _agg_types(t)
            if not agg:
                continue
            chg = None
            if pprev:
                pt = ((got.get(pprev) or {}).get(code) or {}).get("types") or {}
                pagg = _agg_types(pt)
                if pagg:
                    chg = round(_f(agg.get("ratio")) - _f(pagg.get("ratio")), 4)
            row.append({"period": p, "hold_ratio": agg.get("ratio"),
                        "hold_ratio_change": chg,
                        "n_funds": (t.get("01") or {}).get("n", 0)})
        if not row:
            continue
        row.sort(key=lambda x: x["period"])
        stocks[code] = {"code": code, "name": cur.get("name") or "",
                        "holder": holder.get(code) or {},
                        "types": cur.get("types") or {},
                        "series": row}
    return {"asof": dt.date.today().isoformat(),
            "source": "eastmoney:RPT_MAIN_ORGHOLD+RPT_HOLDERNUMLATEST",
            "period": series_periods[0],
            "prev_period": series_periods[1] if len(series_periods) > 1 else "",
            "periods": series_periods, "stocks": stocks}


def finalize(snap, log=print):
    """快照 → 逐股判定 → 输出 JSON（全市场精简 + A/B 精选池带明细）。

    全市场只留判定摘要（供推送 17 列表按码取「机构股」列，体积压到 ~0.7MB 可进 APK assets）；
    A/B 级在 pool[] 里带 series / hold_trend / 分项得分等完整明细（供决策工作台 Excel）。
    """
    stocks, pool = {}, []
    for code, st in snap["stocks"].items():
        j = judge_series(st["series"], st.get("holder"))
        agg = _agg_types(st.get("types") or {}) or {}
        row = {"code": code, "name": st["name"], "grade": j["grade"],
               "score": j["score"], "hold_ratio": j["latest_ratio"],
               "latest_chg": j["latest_chg"], "streak": j["streak"],
               "net_chg": j["net_chg"], "n_funds": (st["series"][-1].get("n_funds")
                                                    if st["series"] else 0),
               "holder_chg_pct": (st.get("holder") or {}).get("chg_pct")}
        stocks[code] = row
        if j["grade"] in ("A", "B"):
            pool.append(dict(row, verdict=j["verdict"], ins=j["ins"], conc=j["conc"],
                             hold_trend=j["hold_trend"], org_n=agg.get("n", 0),
                             holdcha=agg.get("cha", ""),
                             holder_end=(st.get("holder") or {}).get("end", ""),
                             series=j["series"]))
    pool.sort(key=lambda x: (-x["score"], -x["hold_ratio"]))
    return {"asof": snap["asof"], "source": snap["source"], "period": snap["period"],
            "prev_period": snap["prev_period"], "periods": snap["periods"],
            "rule": "口径A 连续≥2期环比增持>0.5pp；口径B 全程净增持>1pp；"
                    "score=0.6×机构加仓分+0.4×集中度；A=口径A且≥75分；B=(A或B)且≥50分",
            "n_stocks": len(stocks), "n_pool": len(pool),
            "a_count": sum(1 for v in stocks.values() if v["grade"] == "A"),
            "b_count": sum(1 for v in stocks.values() if v["grade"] == "B"),
            "c_count": sum(1 for v in stocks.values() if v["grade"] == "C"),
            "pool": pool, "stocks": stocks}


# ═══════════════════════ CSV 读写（L2 手填优先） ═══════════════════════

def write_csv(snap):
    os.makedirs(DATA_DIR, exist_ok=True)
    n = 0
    with open(CSV_FILE, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(CSV_HEAD)
        for code, st in sorted(snap["stocks"].items()):
            for s in st["series"]:
                chg = s.get("hold_ratio_change")
                w.writerow([code, st["name"], s["period"],
                            "%.4f" % _f(s.get("hold_ratio")),
                            "" if chg is None else "%.4f" % _f(chg),
                            _i(s.get("n_funds"))])
                n += 1
    return n


def norm_period(p):
    """2026-06-30 / 2026Q2 / 20262 / 20260630 → YYYY-MM-DD（不可解析则原样）。"""
    s = str(p or "").strip()
    if not s:
        return ""
    if len(s) >= 10 and s[4] == "-" and s[7] == "-":
        return s[:10]
    d = "".join(ch for ch in s if ch.isdigit())
    if len(d) == 8:
        return "%s-%s-%s" % (d[:4], d[4:6], d[6:8])
    if len(d) == 5 and d[-1] in "1234":                      # 20262 = 2026 Q2
        y, q = d[:4], int(d[-1])
        m, day = ((12, 31), (9, 30), (6, 30), (3, 31))[4 - q]
        return "%04d-%02d-%02d" % (int(y), m, day)
    if "Q" in s.upper() or "q" in s:
        y = d[:4]
        q = int(s.upper().split("Q")[-1][0] or 0)
        if 1 <= q <= 4:
            m, day = ((12, 31), (9, 30), (6, 30), (3, 31))[4 - q]
            return "%04d-%02d-%02d" % (int(y), m, day)
    return s


def read_csv(path=None):
    """L2：institutional_cache.csv → {code: {name, series[]}}。"""
    fp = path or CSV_FILE
    if not os.path.exists(fp):
        return {}
    out = {}
    with open(fp, encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            code = str(row.get("code") or "").strip().lstrip("'").zfill(6)
            if not code or code == "000000":
                continue
            rec = out.setdefault(code, {"code": code, "name": row.get("name") or "",
                                        "series": [], "holder": {}, "types": {}})
            if row.get("name"):
                rec["name"] = row["name"]
            chg = str(row.get("hold_ratio_change") or "").strip()
            rec["series"].append({"period": norm_period(row.get("period")),
                                  "hold_ratio": _f(row.get("hold_ratio")),
                                  "hold_ratio_change": _f(chg) if chg else None,
                                  "n_funds": _i(row.get("n_funds"))})
    for rec in out.values():
        rec["series"].sort(key=lambda x: str(x["period"]))
    return out


# ═══════════════════════ L3：内置 mock ═══════════════════════

def mock_snapshot():
    """离线兜底样本（参考方案里验证过的两只票的真实走势）。"""
    raw = {
        "300308": ("中际旭创", [(11, 420), (14, 520), (17, 640), (20, 760), (23, 880), (26, 1163)]),
        "600487": ("亨通光电", [(20, 300), (16.5, 280), (13, 240), (9.5, 190), (6, 120), (3, 70)]),
    }
    stocks = {}
    for code, (name, pts) in raw.items():
        series = []
        for i, (r, nf) in enumerate(pts):
            series.append({"period": "2025-%02d-30" % (3 * (i + 1)),
                           "hold_ratio": float(r), "n_funds": nf,
                           "hold_ratio_change": None if i == 0 else float(r) - pts[i - 1][0]})
        stocks[code] = {"code": code, "name": name, "holder": {}, "types": {},
                        "series": series}
    return {"asof": dt.date.today().isoformat(), "source": "mock(L3)",
            "period": "2025-12-30", "prev_period": "2025-09-30",
            "periods": [s["period"] for s in stocks["300308"]["series"]][::-1],
            "stocks": stocks}


# ═══════════════════════ 对外 API ═══════════════════════

def load_cache():
    """读 data/_inst_holdings.json（缺失回退 CSV → mock）。返回完整快照 dict。"""
    if os.path.exists(OUT_FILE):
        try:
            with open(OUT_FILE, encoding="utf-8") as f:
                d = json.load(f)
            if d.get("stocks"):
                return d
        except Exception:  # noqa: BLE001
            pass
    csvd = read_csv()
    if csvd:
        stocks = {}
        for code, rec in csvd.items():
            j = judge_series(rec["series"])
            stocks[code] = {"code": code, "name": rec["name"], "grade": j["grade"],
                            "score": j["score"], "verdict": j["verdict"],
                            "hold_ratio": j["latest_ratio"], "latest_chg": j["latest_chg"],
                            "streak": j["streak"], "net_chg": j["net_chg"], "ins": j["ins"],
                            "conc": j["conc"], "hold_trend": j["hold_trend"],
                            "n_funds": (rec["series"][-1]["n_funds"] if rec["series"] else 0),
                            "org_n": 0, "holdcha": "", "n_types": 0,
                            "holder_chg_pct": None, "holder_end": "", "series": j["series"]}
        return finalize({"asof": dt.date.today().isoformat(), "source": "L2:institutional_cache.csv",
                         "period": "", "prev_period": "", "periods": [], "stocks": {
                             c: {"code": c, "name": r["name"], "holder": {}, "types": {},
                                 "series": r["series"]} for c, r in csvd.items()}})
    return finalize(mock_snapshot())


def lookup(d, codes=None):
    """{code: 判定摘要} —— 供推送 17 列表/工作台按码取「机构股」列。

    codes=None 时返回全部。返回 {code: {grade, score, verdict, label, hold_ratio, ...}}
    """
    if not d:
        return {}
    src = d.get("stocks") or {}
    if codes is None:
        keys = list(src.keys())
    else:
        keys = ["%06d" % int(str(c).lstrip("abcdefghijklmnopqrstuvwxyz").zfill(6)[-6:])
                if str(c).strip().isdigit() or str(c).strip()[-6:].isdigit() else str(c)
                for c in codes]
    out = {}
    for c in keys:
        v = src.get(c)
        if v:
            out[c] = dict(v, label=grade_label(v))
    return out


# ═══════════════════════ CLI ═══════════════════════

def dump(d, log=print):
    log("\n═══ 机构持续加仓选股池（%s 源=%s）═══" % (d.get("asof", ""), d.get("source", "")))
    log("全市场 %d 只 ｜ A级 %d ｜ B级 %d ｜ C级 %d ｜ 报告期 %s"
        % (d.get("n_stocks", 0), d.get("a_count", 0), d.get("b_count", 0),
           d.get("c_count", 0), d.get("period", "")))
    for v in (d.get("pool") or [])[:25]:
        log("  %-6s %-8s %s 分=%-5s 连续增持%s期 净%+.2fpp 最新%.2f%% 基金%s只 户数环比%s%% | %s"
            % (v["code"], v["name"], v["grade"], v["score"], v["streak"], v["net_chg"],
               v["hold_ratio"], v["n_funds"],
               "—" if v.get("holder_chg_pct") is None else ("%+.1f" % v["holder_chg_pct"]),
               v["hold_trend"]))
    log("→ %s" % OUT_FILE)


def main(argv):
    log = print
    if "--mock" in argv:
        d = finalize(mock_snapshot(), log=log)
        dump(d, log=log)
        log("（L3 mock 自检：300308 应为 A / 600487 应为 C）")
        return 0

    if "--csv-only" in argv:
        csvd = read_csv()
        if not csvd:
            log("✗ %s 不存在" % CSV_FILE)
            return 1
        d = finalize({"asof": dt.date.today().isoformat(), "source": "L2:" + CSV_FILE,
                      "period": "", "prev_period": "", "periods": [],
                      "stocks": {c: {"code": c, "name": r["name"], "holder": {},
                                     "types": {}, "series": r["series"]}
                                 for c, r in csvd.items()}})
        _save(d, log=log, mirror=("--update-pool" in argv))
        dump(d, log=log)
        return 0

    periods = 3
    for i, a in enumerate(argv):
        if a == "--periods" and i + 1 < len(argv):
            periods = max(1, _i(argv[i + 1]))
        elif a.startswith("--periods="):
            periods = max(1, _i(a.split("=", 1)[1]))

    snap = None
    if "--pool" in argv or any(a.startswith("--pool=") for a in argv):
        pass
    if not (len(argv) > 1 and "--local" in argv):
        log("L1 抓取东方财富机构持股一览表（%d 期 + 1 期环比基准）…" % periods)
        snap = build_snapshot(periods=periods, log=log, reuse=("--reuse" in argv))
    if not snap:
        log("⚠ L1 失败 → 降级 L2（读 institutional_cache.csv）")
        d = load_cache()
        _save(d, log=log, mirror=("--update-pool" in argv))
        dump(d, log=log)
        return 0

    cnt = write_csv(snap)
    log("  CSV 回写 %d 行 → %s" % (cnt, CSV_FILE))
    d = finalize(snap, log=log)
    _save(d, log=log, mirror=("--update-pool" in argv))
    dump(d, log=log)

    codes = None
    for i, a in enumerate(argv):
        if a == "--pool" and i + 1 < len(argv):
            codes = argv[i + 1].split(",")
        elif a.startswith("--pool="):
            codes = a.split("=", 1)[1].split(",")
    if codes:
        log("\n═══ 指定清单机构判定 ═══")
        for c, v in sorted(lookup(d, codes).items(), key=lambda kv: -kv[1]["score"]):
            log("  %s %-8s %s 分=%s | %s" % (c, v["name"], v["label"], v["score"], v["hold_trend"]))
    return 0


def _save(d, log=print, mirror=False):
    os.makedirs(DATA_DIR, exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, separators=(",", ":"))
    if mirror:
        os.makedirs(ASSET_DATA, exist_ok=True)
        with open(ASSET_OUT, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, separators=(",", ":"))
        log("  APK assets 副本 → %s" % ASSET_OUT)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
