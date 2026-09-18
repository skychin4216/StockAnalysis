# -*- coding: utf-8 -*-
"""临时回测（2026-09-17 用户质疑验证）：

「机构增持命中票被订单 node 拦截（趋势下跌/三乌鸦）是不是不对？机构注资
理论上存在触底反弹可能 → 回溯拟合看看放开和拦截对比，若放开后面涨概率
高，考虑修改。」

设计：
- 样本 = kline_store 688 只池的全历史十大流通股东「机构增持披露事件」
  （NOTICE_DATE 2022-01-01 ~ 2026-06-30，HOLD_NUM_CHANGE>0，机构身份命中，
  排除「香港中央结算(代理人)」——那是 H 股登记处不是北向，属生产代码误判）；
- 买点 = 披露日之后首个交易日收盘（模拟保送命中后即买）；
- 门控 = 复用生产同口径三重检查 _trend_match_3way / _candle_veto_bearish /
  _idiom_buy_veto（用买点前的 snaps 截断），被任一拦截 → 「拦截组」，否则「放行组」；
- 对比两组未来 5/10/20 日收益（胜率/均值/中位数）→ 拦截组收益即「若放开」的表现。
"""
import sys, os, json, bisect, urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "assets", "usecases"))

from _kline_store import load_store
import usecase_pipeline as U

CACHE_F = os.path.join(ROOT, "data", "_ibr_hist_cache.json")
STORE = load_store()
print("K线池:", len(STORE), "只")


def fetch_all(code6):
    secu = "%s.%s" % (code6, "SH" if code6[:1] in "569" else "SZ")
    url = ("https://datacenter-web.eastmoney.com/api/data/v1/get"
           "?reportName=RPT_F10_EH_FREEHOLDERS&columns=ALL"
           "&filter=(SECUCODE%%3D%%22%s%%22)"
           "&sortColumns=END_DATE,HOLDER_RANK&sortTypes=-1,1&pageSize=250&pageNumber=1" % secu)
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Referer": "https://emweb.securities.eastmoney.com/"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        d = json.loads(resp.read().decode("utf-8"))
    rows = (((d or {}).get("result") or {}).get("data")) or []
    out = []
    for r in rows:
        out.append({
            "HOLDER_NAME": r.get("HOLDER_NAME") or "",
            "HOLD_NUM_CHANGE": r.get("HOLD_NUM_CHANGE") or 0,
            "CHANGE_RATIO": r.get("CHANGE_RATIO") or 0,
            "END_DATE": str(r.get("END_DATE") or "")[:10],
            "NOTICE_DATE": str(r.get("NOTICE_DATE") or "")[:10],
        })
    return out


def safe_fetch(code6):
    try:
        return code6, fetch_all(code6)
    except Exception:
        return code6, []


# ── 抓取/缓存 ──
cache = {}
if os.path.exists(CACHE_F):
    cache = json.load(open(CACHE_F, encoding="utf-8"))
codes6 = sorted({c[2:] for c in STORE})
todo = [c for c in codes6 if c not in cache]
print("待抓:", len(todo), "已缓存:", len(cache))
if todo:
    n = 0
    with ThreadPoolExecutor(8) as ex:
        for code6, recs in ex.map(safe_fetch, todo):
            cache[code6] = recs
            n += 1
            if n % 100 == 0:
                json.dump(cache, open(CACHE_F, "w", encoding="utf-8"), ensure_ascii=False)
                print("  进度", n, "/", len(todo))
    json.dump(cache, open(CACHE_F, "w", encoding="utf-8"), ensure_ascii=False)
print("股东记录抓取完成:", len(cache))

# ── 事件构建 ──
LO, HI = "2022-01-01", "2026-06-30"
events = {}  # (code6, notice_date) -> best hit
n_rec = 0
for code6, recs in cache.items():
    if not recs:
        continue
    secid = ("sh" if code6[:1] in "569" else "sz") + code6
    snaps = (STORE.get(secid) or {}).get("snaps") or []
    if len(snaps) < 80:
        continue
    dates = [s["date"] for s in snaps]
    for r in recs:
        nd = r["NOTICE_DATE"] or r["END_DATE"]
        if not (LO <= nd <= HI):
            continue
        try:
            chg = float(r["HOLD_NUM_CHANGE"] or 0)
        except (TypeError, ValueError):
            continue
        if chg <= 0:
            continue
        holder = r["HOLDER_NAME"]
        if "代理人" in holder:  # H股登记处 ≠ 北向，剔除
            continue
        kind = U._ibr_holder_kind(holder)
        if not kind:
            continue
        n_rec += 1
        # 买点 = 披露日之后首个交易日
        idx = bisect.bisect_right(dates, nd)
        if idx < 60 or idx + 20 >= len(snaps):  # 需要历史够算门控 + 前向20日完整
            continue
        key = (code6, nd)
        sc = U._IBR_KIND_SCORE.get(kind, 70) + max(0.0, min(25.0, float(r["CHANGE_RATIO"] or 0)))
        if key in events and events[key]["score"] >= sc:
            continue
        events[key] = {"code": code6, "name": (STORE.get(secid) or {}).get("name") or secid,
                       "notice": nd, "idx": idx, "kind": kind, "score": sc,
                       "holder": holder, "ratio": float(r["CHANGE_RATIO"] or 0)}
print("机构增持记录:", n_rec, "→ 去重事件:", len(events))

# ── 门控分组 + 前向收益 ──
rows = []
for (code6, nd), ev in events.items():
    secid = ("sh" if code6[:1] in "569" else "sz") + code6
    snaps = (STORE.get(secid) or {}).get("snaps") or []
    if len(snaps) < ev["idx"] + 21:
        continue
    upto = snaps[:ev["idx"] + 1]  # 买点当日（含）为止，与实盘口径一致
    tm = U._trend_match_3way(upto)
    blocked, why = False, ""
    if tm["label"] == "下跌":
        blocked, why = True, "趋势下跌"
    if not blocked:
        pat = U._candle_veto_bearish(upto)
        if pat:
            blocked, why = True, "看空形态:" + pat
    if not blocked:
        veto = U._idiom_buy_veto(upto)
        if veto:
            blocked, why = True, "口诀:" + veto[:12]
    buy = snaps[ev["idx"]]["close"]
    fwd = {}
    for n in (5, 10, 20):
        j = ev["idx"] + n
        if j < len(snaps):
            fwd[n] = (snaps[j]["close"] / buy - 1) * 100
    # 20日内最大冲高
    seg = snaps[ev["idx"] + 1: ev["idx"] + 21]
    mx = max((s["high"] / buy - 1) * 100 for s in seg) if seg else None
    rows.append({"blocked": blocked, "why": why, "kind": ev["kind"],
                 "trend": tm["label"], "ratio": ev["ratio"], **{("fwd%d" % k): v for k, v in fwd.items()},
                 "max20": mx, "code": code6, "notice": nd, "name": ev["name"]})
print("有效回测事件:", len(rows))


def stat(rs, tag):
    if not rs:
        print("%-24s n=0" % tag)
        return
    out = ["%-24s n=%-5d" % (tag, len(rs))]
    for n in (5, 10, 20):
        vs = [r["fwd%d" % n] for r in rs if r.get("fwd%d" % n) is not None]
        if vs:
            vs.sort()
            out.append("fwd%d: 胜率%.0f%% 均%+.1f%% 中位%+.1f%%" % (
                n, 100 * sum(1 for v in vs if v > 0) / len(vs), sum(vs) / len(vs),
                vs[len(vs) // 2]))
    mvs = [r["max20"] for r in rs if r.get("max20") is not None]
    if mvs:
        out.append("20日内最大冲高均%+.1f%%" % (sum(mvs) / len(mvs)))
    print(" | ".join(out))


print()
print("════════ 总体：放行 vs 拦截（拦截组=若放开后的真实表现） ════════")
ok = [r for r in rows if not r["blocked"]]
bl = [r for r in rows if r["blocked"]]
stat(ok, "放行组(现状会买)")
stat(bl, "拦截组(若放开)")

print()
print("════════ 拦截组细分 ════════")
for why in sorted({r["why"].split(":")[0] for r in bl}):
    stat([r for r in bl if r["why"].split(":")[0] == why], "拦截:" + why)

print()
print("════════ 拦截组按机构类型 ════════")
for kind in ("国家队", "社保养老", "大基金", "公募基金", "险资", "QFII外资", "北向"):
    rs = [r for r in bl if r["kind"] == kind]
    if rs:
        stat(rs, "拦截·" + kind)

print()
print("════════ 拦截组按增持幅度 ════════")
stat([r for r in bl if r["ratio"] >= 20], "拦截·大幅增持≥20%")
stat([r for r in bl if 5 <= r["ratio"] < 20], "拦截·中幅5~20%")
stat([r for r in bl if r["ratio"] < 5], "拦截·小幅<5%")

print()
print("════════ 高权重机构(国家队/社保/大基金) 拦截 vs 放行 ════════")
hi_k = ("国家队", "社保养老", "大基金")
stat([r for r in bl if r["kind"] in hi_k], "高权重·拦截(若放开)")
stat([r for r in ok if r["kind"] in hi_k], "高权重·放行")

# 拦截组里 fwd10 最差/最好样本（看尾部风险）
print()
print("════════ 拦截组 fwd10 尾部样本（各10个） ════════")
b10 = sorted([r for r in bl if r.get("fwd10") is not None], key=lambda r: r["fwd10"])
for r in b10[:10]:
    print("  最差 %s %s %s %s fwd10=%+.1f%% fwd20=%s" % (r["name"], r["code"], r["notice"], r["why"][:14], r["fwd10"], ("%+.1f" % r["fwd20"]) if r.get("fwd20") is not None else "-"))
for r in b10[-10:]:
    print("  最好 %s %s %s %s fwd10=%+.1f%% fwd20=%s" % (r["name"], r["code"], r["notice"], r["why"][:14], r["fwd10"], ("%+.1f" % r["fwd20"]) if r.get("fwd20") is not None else "-"))
