# -*- coding: utf-8 -*-
"""社保 / 国家队 / 外资(北向) 信号采集（ETF 前五重仓口径）。

数据源（2026-09-06 探测确认，单一接口即可）：
  RPT_F10_EH_FREEHOLDERS  十大流通股东历史
  https://datacenter.eastmoney.com/securities/api/data/v1/get?reportName=RPT_F10_EH_FREEHOLDERS
  字段：HOLDER_NAME / HOLDER_TYPE(社保基金…) / HOLDER_STATE(新进/加仓/不变/减持或None) /
        FREE_HOLDNUM_RATIO(占自由流通比%) / HOLD_NUM_CHANGE / HOLD_RATIO_CHANGE /
        END_DATE(报告期) / NOTICE_DATE(实际披露日，即“具体时间”)
  覆盖：可回溯到 2008 年前后（茅台等 2008 起即可查）；粒度=季报。
  分类口径：
    · 社保/养老   → HOLDER_NAME 含“全国社保基金 / 基本养老”
    · 国家队       → 中央汇金 / 证金
    · 外资(北向)  → “香港中央结算有限公司”（2014 沪港通后逐步进入前十大，2017 后普遍；
                   2024-08 后港交所停发北向逐股日频，故用季度口径做历史一致性回溯）
  注意：仅能看到进入十大流通股东后的持仓；看不见前十大之后的仓。

用法：
  python _holder_signals.py            # 全量更新 data/_holder_signals.json + 摘要
  HOLDER_NORTH_ONLY=1 python _holder_signals.py   # 仅刷新摘要显示（复用已存数据）
"""
import json
import os
import sys
import time
from collections import Counter

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _etf_holdings as eh  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_FILE = os.path.join(os.path.dirname(HERE), "data", "_holder_signals.json")
PROG = os.path.join(HERE, "_holder_progress.json")
PX = {"http": None, "https": None}
HEAD = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Referer": "https://emweb.securities.eastmoney.com/"}
SS_HINT = ("全国社保基金", "社保基金", "基本养老")
NAT_HINT = ("中央汇金", "中国证券金融", "证金")
NORTH_NAME = "香港中央结算有限公司"
STATE_TAGS = ("新进", "加仓", "增持", "减持", "不变")


def dc_get(url, tries=4):
    for i in range(tries):
        try:
            j = requests.get(url, timeout=25, headers=HEAD, proxies=PX).json()
            rows = ((j.get("result") or {}).get("data")) or []
            if rows or i >= 2:
                return rows
        except Exception:  # noqa: BLE001
            pass
        time.sleep(0.6 * (i + 1))
    return []


def norm_state(rec):
    """优先文字标签；缺失时由数量变动推符号。"""
    st = (rec.get("HOLDER_STATE") or rec.get("HOLDER_STATEE") or
          rec.get("HOLD_CHANGE") or "")
    if isinstance(st, str) and any(t in st for t in STATE_TAGS):
        return st
    n = rec.get("HOLD_NUM_CHANGE")
    if isinstance(n, (int, float)):
        return "增持" if n > 0 else ("减持" if n < 0 else "不变")
    return ""


def fetch_f10_history(code, min_q="2008-01-01"):
    """全报告期（END_DATE 倒序）十大流通股东，规整为记录列表。"""
    recs = []
    page = 1
    while page <= 6:
        url = ("https://datacenter.eastmoney.com/securities/api/data/v1/get"
               "?reportName=RPT_F10_EH_FREEHOLDERS&columns=ALL&source=HSF10&client=PC"
               "&sortColumns=END_DATE&sortTypes=-1&pageNumber=%d&pageSize=500"
               "&filter=(SECURITY_CODE%%3D%%22%s%%22)" % (page, code))
        rows = dc_get(url)
        if not rows:
            break
        for r in rows:
            end = (r.get("END_DATE") or "")[:10]
            if not end or end < min_q:
                continue
            try:
                ratio = float(r.get("FREE_HOLDNUM_RATIO") or 0)
            except Exception:  # noqa: BLE001
                ratio = 0.0
            recs.append({
                "end": end,
                "notice": (r.get("NOTICE_DATE") or "")[:10],
                "name": r.get("HOLDER_NAME") or "",
                "type": r.get("HOLDER_TYPE") or "",
                "ratio": round(ratio, 4),
                "state": norm_state(r),
                "rank": r.get("HOLDER_RANK"),
            })
        if len(rows) < 500 or (rows[-1].get("END_DATE") or "")[:10] < min_q:
            break
        page += 1
    return recs


def pick(recs, hint=None, exact=None):
    if exact is not None:
        return [x for x in recs if x["name"] == exact]
    return [x for x in recs if x["name"] and any(h in x["name"] for h in hint)]


def ss_section(recs):
    rows = pick(recs, SS_HINT)
    if not rows:
        return None
    rows_sorted = sorted(rows, key=lambda x: x["end"])
    first = rows_sorted[0]
    latest_q = max(x["end"] for x in rows)
    return {
        "first_entry": first["end"],
        "first_holder": first["name"],
        "first_state": first["state"],
        "latest_quarter": latest_q,
        "latest_combos": len({(x["name"], x["end"]) for x in rows if x["end"] == latest_q}),
        "actions": sorted(rows, key=lambda x: x["end"], reverse=True)[:8],
    }


def north_trend(recs, name=NORTH_NAME, win=3):
    """中央结算季度序列 → 趋势判断（最新三期）。"""
    rows = sorted([x for x in recs if x["name"] == name], key=lambda x: x["end"])
    if not rows:
        return None
    last3 = rows[-win:]
    seq = [x["ratio"] for x in last3]
    latest = last3[-1]
    trend = "平稳"
    if len(seq) >= 3 and seq[-1] > seq[-2] + 0.2 and seq[-2] > seq[-3] + 0.2:
        trend = "连续两季增持"
    elif len(seq) >= 3 and seq[-1] > seq[-2] + 0.35:
        trend = "本季明显增持"
    elif len(seq) >= 2 and seq[-1] > seq[-2] + 0.35:
        trend = "近季明显增持"
    elif len(seq) >= 2 and seq[-1] < seq[-2] - 0.35:
        trend = "减持"
    elif len(seq) >= 3 and seq[-1] < seq[-2] - 0.2 and seq[-2] < seq[-3] - 0.2:
        trend = "连续两季减持"
    return {"trend": trend, "latest": latest, "series": last3}


def _theme_map(hold):
    m = {}
    for f in (hold.get("funds") or []):
        th = f.get("theme") or ""
        if th == "宽基":
            th = ""
        for s in (f.get("top") or [])[:10]:
            m.setdefault(s["code"], th)
    return m


def main():
    hold = eh.load_holdings() or {}
    hist = eh.load_top5_hist().get("codes") or {}
    theme_of = _theme_map(hold)
    only_show = os.environ.get("HOLDER_NORTH_ONLY") == "1"
    prev = {}
    if os.path.exists(OUT_FILE):
        try:
            prev = json.load(open(OUT_FILE, encoding="utf-8"))
        except Exception:  # noqa: BLE001
            prev = {}
    codes = sorted(hist.keys())
    print("对象 %d 只（行业ETF前五重仓）" % len(codes), flush=True)
    asof = max((s["date"] for e in hist.values() for s in e.get("snaps", [])),
               default="")

    res = {"asof": asof, "codes": len(codes), "social_security": {},
           "national": {}, "north_quarter": {}}
    if only_show and prev.get("social_security"):
        res = prev
        codes_done = set(prev.get("social_security", {}).keys())
    else:
        codes_done = set()
    for i, c in enumerate(codes):
        if c in codes_done:
            continue
        recs = fetch_f10_history(c)
        nm = (hist.get(c) or {}).get("name") or ""
        th = theme_of.get(c) or ""
        ss = ss_section(recs)
        if ss:
            ss["name"] = nm
            ss["theme"] = th
            res["social_security"][c] = ss
        nat = pick(recs, NAT_HINT)
        if nat:
            res["national"][c] = {"name": nm, "theme": th, "rows": nat[:4]}
        nb = north_trend(recs)
        if nb:
            nb["name"] = nm
            nb["theme"] = th
            res["north_quarter"][c] = nb
        if (i + 1) % 10 == 0:
            print("  %d/%d 社保%d 北向%d" % (i + 1, len(codes),
                  len(res["social_security"]), len(res["north_quarter"])), flush=True)
            with open(PROG, "w", encoding="utf-8") as f:
                json.dump(res, f, ensure_ascii=False)
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    if os.path.exists(PROG):
        try:
            os.remove(PROG)
        except Exception:  # noqa: BLE001
            pass
    dump(res)
    print("\n报告 → %s" % OUT_FILE)
    return 0


def dump(res):
    ss, nb = res["social_security"], res["north_quarter"]
    asof = res.get("asof", "")
    print("\n═══ 社保/养老（ETF前五·n=%d）═══" % len(ss))
    latest = {}
    for c, s in ss.items():
        end = s["latest_quarter"]
        latest.setdefault(end, []).append((c, s))
    for end in sorted(latest, reverse=True)[:2]:
        lst = latest[end]
        act = [(c, s) for c, s in lst
               if any(a["state"] in ("新进", "加仓", "增持") for a in s["actions"] if a["end"] == end)]
        if not act:
            continue
        print("▼ %s 新进/加仓 %d/%d 只" % (end, len(act), len(lst)))
        for c, s in sorted(act, key=lambda x: -max(
                (a["ratio"] for a in s["actions"] if a["end"] == end), default=0)):
            acts = [a for a in s["actions"] if a["end"] == end]
            lab = "；".join("%s %s(%s%%)" % (a["name"][-8:], a["state"],
                                             a["ratio"]) for a in acts[:3])
            print("   %s %s %s | 首见%s | %s" % (c, s["name"], s["theme"] or "-",
                                                 s["first_entry"], lab))
    print("\n═══ 外资/北向=中央结算（ETF前五·n=%d，季度口径）═══" % len(nb))
    ups = [(c, s) for c, s in nb.items()
           if s["trend"] in ("连续两季增持", "本季明显增持", "近季明显增持")]
    down = [(c, s) for c, s in nb.items() if s["trend"].startswith("减持") or s["trend"].startswith("连续")]
    print("最新季度分布：增持方向 %d 只 / 减持方向 %d 只" % (len(ups), len(down)))
    for c, s in sorted(ups, key=lambda x: -(x[1]["latest"]["ratio"]))[:20]:
        last = s["series"][-1]
        his = " → ".join("%s(%.2f)" % (x["end"][:7], x["ratio"]) for x in s["series"][-3:])
        print("   %s %s %s | %s | 最新%.2f%%" % (c, s["name"], s["theme"] or "-",
                                                 s["trend"], last["ratio"]))
    if ups:
        ind = Counter(s["theme"] for _, s in ups)
        print("  增持行业分布:", dict(ind.most_common()))
    if down:
        ind = Counter(s["theme"] for _, s in down)
        print("  减持行业分布:", dict(ind.most_common()))
    print("\n═══ 最早进入十大流通(近似建仓时点) 前15 ═══")
    ss_all = sorted(ss.items(), key=lambda kv: kv[1]["first_entry"])[:15]
    for c, s in ss_all:
        print("   %s %s %s | %s %s" % (c, s["name"], s["first_entry"],
                                       s["first_holder"][-10:], s["first_state"]))


if __name__ == "__main__":
    sys.exit(main())
