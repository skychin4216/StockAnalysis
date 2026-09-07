# -*- coding: utf-8 -*-
"""盘中情报扫描器：每 10 分钟收集 → 与上次快照对比 → 有变动才推送微信。

覆盖（用户需求）：
  1. A股热门板块及切换情况（板块动量轮动，龙头）
  2. 美股涨幅 TOP10（权重股池实时）
  3. 韩国股市 TOP3（KOSPI 指数 + 权重股）
  4. 中国股市各板块龙头（轮动引擎 leaders）
  5. 投研机构 TOP5 最新研报（东财研报中心，按机构聚合）
  6. 实时财经快讯（东财 7x24）

用法：
  python _market_scan.py --once            # 跑一次（采集+对比+通知）
  python _market_scan.py --daemon          # 交易时段每 900 秒轮询（默认）
  python _market_scan.py --daemon --interval 900
  python _market_scan.py --once --force    # 强制推送（忽略变动检测）
"""
import argparse
import datetime
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import requests  # noqa: E402
from _industry_map import build_industry  # noqa: E402
from _rotation_engine import load_cache as rotation_load_cache, rotate as rotation_rotate  # noqa: E402
import push_channel  # noqa: E402
import _news_watch  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
APP_CONFIG = os.path.join(ROOT, "app", "src", "main", "assets", "data", "app_config.json")
LAST_SNAP = os.path.join(HERE, "_last_scan.json")

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
PROXIES = {"http": None, "https": None}

# 美股权重股池（腾讯代码）
US_POOL = ("usAAPL,usMSFT,usNVDA,usGOOGL,usAMZN,usMETA,usTSLA,usAVGO,"
           "usAMD,usNFLX,usTSM,usCOST,usLLY,usJPM,usORCL,usCRM,usINTC,"
           "usQCOM,usCSCO,usMRK")
US_TOP = 10

# 韩国权重股池（腾讯韩股代码 + 中文名）
KR_POOL = [("kr005930", "三星电子"), ("kr000660", "SK海力士"),
           ("kr373220", "LG新能源"), ("kr005380", "现代汽车"),
           ("kr035420", "NAVER"), ("kr000270", "起亚")]
KR_TOP = 3
KR_INDEX = "100.KS11"  # 东财全球 KOSPI

# 全球指数（东财 secid → 中文名）
GLOBAL_INDEX = {
    "100.KS11": "韩国KOSPI",
    "100.NDX": "纳斯达克100",
    "100.DJIA": "道琼斯",
    "100.SPX": "标普500",
    "100.399001": "沪深300",  # 占位（东财A股用不同secid，忽略）
}

DEFAULT_INTERVAL = 900  # 15 分钟（与选股守护盘中轮同节奏）


# ── 1. 采集 ─────────────────────────────────────────────────────────────
def _tencent(codes):
    """腾讯批量实时行情，返回 {code: {name, price, pct, ts}}。"""
    try:
        r = requests.get("https://qt.gtimg.cn/q=" + codes, timeout=10,
                         headers=HEADERS, proxies=PROXIES)
        r.encoding = "gbk"
    except Exception as e:
        print("腾讯行情失败:", e)
        return {}
    out = {}
    for line in r.text.strip().split(";"):
        if "=" not in line:
            continue
        code = line.split("=")[0].replace("v_", "").strip()
        f = line.split('="', 1)[1].rstrip('"').split("~")
        if len(f) < 33:
            continue
        try:
            out[code] = {
                "name": f[1], "price": float(f[3]), "pct": float(f[32]),
                "ts": f[30],
            }
        except (ValueError, IndexError):
            continue
    return out


def collect_us():
    """美股 TOP10（权重池按涨幅排序）。"""
    d = _tencent(US_POOL)
    rows = sorted(d.values(), key=lambda x: x["pct"], reverse=True)[:US_TOP]
    return [{"name": r["name"], "pct": r["pct"], "price": r["price"]} for r in rows]


def collect_kr():
    """韩国 TOP3 + KOSPI 指数。"""
    d = _tencent(",".join(c[0] for c in KR_POOL))
    rows = []
    for code, cn in KR_POOL:
        it = d.get(code)
        if it:
            rows.append({"name": cn, "code": code, "pct": it["pct"],
                         "price": it["price"]})
    rows.sort(key=lambda x: x["pct"], reverse=True)
    top = rows[:KR_TOP]
    # KOSPI 指数（多 host 重试）
    kospi = None
    for host in ("https://push2.eastmoney.com", "https://90.push2.eastmoney.com",
                 "https://92.push2.eastmoney.com"):
        try:
            r = requests.get(
                host + "/api/qt/stock/get",
                params={"secid": KR_INDEX, "fields": "f43,f58,f170"},
                timeout=8, headers=HEADERS, proxies=PROXIES)
            data = (r.json().get("data") or {})
            if data.get("f43") is not None:
                kospi = {"name": data.get("f58") or "韩国KOSPI",
                         "last": data["f43"] / 100.0, "pct": data.get("f170", 0) / 100.0}
                break
        except Exception as e:
            print("KOSPI %s 获取失败: %s" % (host, e))
    return {"stocks": top, "index": kospi}


def collect_rotation():
    """A股热门板块及切换情况（轮动引擎）+ 龙头。"""
    cache = rotation_load_cache()
    industry = build_industry()
    all_dates = sorted({d for e in cache.values()
                        for s in e.get("snaps", []) for d in [s["date"]]})
    if not all_dates:
        return []
    asof = all_dates[-1]
    rot = rotation_rotate(cache, asof, industry, top=10)
    out = []
    for r in rot[:8]:
        leaders = [{"name": l["name"], "secid": l["secid"]}
                   for l in (r.get("leaders") or [])[:3]]
        out.append({
            "industry": r["industry"], "sec_mom": r["sec_mom"],
            "catalyst": r.get("catalyst_reason", ""), "leaders": leaders,
            "asof": asof,
        })
    return out


def collect_reports(days=2, per_org=5, top_org=5):
    """东财研报中心：最近 N 天研报，按机构聚合 → TOP 机构各前 N 条。"""
    end = datetime.date.today()
    begin = end - datetime.timedelta(days=days)
    items = []
    for page in (1, 2):
        try:
            r = requests.get(
                "https://reportapi.eastmoney.com/report/list",
                params={"industryCode": "*", "pageSize": 50, "pageNo": page,
                        "qType": 0, "code": "*",
                        "beginTime": begin.strftime("%Y-%m-%d"),
                        "endTime": end.strftime("%Y-%m-%d")},
                timeout=10, headers=HEADERS, proxies=PROXIES)
            lst = (r.json().get("data") or []) or []
            items.extend(lst)
            if len(lst) < 50:
                break
        except Exception as e:
            print("研报拉取失败:", e)
            break
    # 按机构聚合
    by_org = {}
    for it in items:
        org = it.get("orgSName") or it.get("orgName") or "未知机构"
        by_org.setdefault(org, []).append({
            "title": it.get("title", ""),
            "stock": it.get("stockName", ""),
            "date": it.get("publishDate", "")[:10] or it.get("infoPublishDate", "")[:10],
            "author": it.get("author", ""),
        })
    ranked = sorted(by_org.items(), key=lambda kv: len(kv[1]), reverse=True)[:top_org]
    out = []
    for org, lst in ranked:
        out.append({"org": org, "count": len(lst),
                    "items": sorted(lst, key=lambda x: x["date"], reverse=True)[:per_org]})
    return out


def collect_news(top=10):
    """东财 7x24 财经快讯。"""
    try:
        r = requests.get(
            "https://np-listapi.eastmoney.com/comm/web/getFastNewsList",
            params={"client": "web", "biz": "web_724", "fastColumn": "102",
                    "sortEnd": "", "pageSize": top, "req_trace": "1"},
            timeout=10, headers=HEADERS, proxies=PROXIES)
        d = r.json()
        lst = (d.get("data") or {}).get("fastNewsList") or []
    except Exception as e:
        print("快讯拉取失败:", e)
        return []
    out = []
    for it in lst[:top]:
        out.append({
            "title": it.get("title") or it.get("summary", "")[:60],
            "time": it.get("showTime") or it.get("digestTime") or "",
        })
    return out


def collect_all():
    out = {
        "ts": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "us": collect_us(),
        "kr": collect_kr(),
        "rotation": collect_rotation(),
        "reports": collect_reports(),
        "news": collect_news(),
        "session": collect_session_factor(),
    }
    # 每晚/每次采集顺带刷新外围历史缓存（纳指/韩股代理日K，供隔夜外围因子）
    try:
        import _overseas_fetch
        _overseas_fetch.fetch_all()
    except Exception as e:
        print("外围缓存刷新失败: %s" % type(e).__name__)
    return out


def collect_session_factor():
    """大盘时段因子（早盘下杀企稳 / 尾盘勿追），用上证指数当日分时判定。"""
    try:
        import _session_factor
        f = _session_factor.session_factor_for("sh000001")
        out = {
            "morning_verdict": f.morning_verdict,
            "reason": f.reason,
            "late_zone_active": f.late_zone_active,
        }
        return out
    except Exception as e:
        print("时段因子采集失败: %s" % type(e).__name__)
        return {}


# ── 2. 变动检测 ─────────────────────────────────────────────────────────
def fingerprint_news(items):
    return {it["title"] for it in items}


def diff_snap(prev, cur):
    """返回 [(标题, 内容行列表)]，无变动返回 []。"""
    changes = []
    # 美股 TOP10：新进前10 或 涨幅变化>1%
    prev_us = {r["name"]: r["pct"] for r in (prev or {}).get("us", [])}
    cur_us = {r["name"]: r["pct"] for r in cur["us"]}
    us_new = [n for n in cur_us if n not in prev_us]
    us_big = [n for n in cur_us if n in prev_us and abs(cur_us[n] - prev_us[n]) > 1.0]
    if us_new or us_big:
        lines = ["%s %+.2f%%" % (n, cur_us[n]) for n in (us_new + us_big)[:5]]
        changes.append(("🇺🇸 美股异动", lines))
    # 韩国：新进前3 或 KOSPI 波动>0.5%
    prev_kr = {(r["name"]): r["pct"] for r in (prev or {}).get("kr", {}).get("stocks", [])}
    cur_kr = {r["name"]: r["pct"] for r in cur["kr"]["stocks"]}
    kr_new = [n for n in cur_kr if n not in prev_kr]
    prev_kpi = (prev or {}).get("kr", {}).get("index") or {}
    cur_kpi = cur["kr"]["index"] or {}
    kpi_delta = abs(cur_kpi.get("pct", 0) - prev_kpi.get("pct", 0))
    if kr_new or kpi_delta > 0.5:
        lines = []
        if kr_new:
            lines += ["%s %+.2f%%" % (n, cur_kr[n]) for n in kr_new]
        if cur_kpi and kpi_delta > 0.5:
            lines.append("KOSPI %+.2f%%" % cur_kpi.get("pct", 0))
        changes.append(("🇰🇷 韩国异动", lines))
    # A股板块轮动：新进板块
    prev_rot = {r["industry"] for r in (prev or {}).get("rotation", [])}
    cur_rot = {r["industry"]: r for r in cur["rotation"]}
    rot_new = [n for n in cur_rot if n not in prev_rot]
    if rot_new:
        lines = []
        for n in rot_new[:5]:
            r = cur_rot[n]
            lead = " ".join(l["name"] for l in r["leaders"][:3])
            lines.append("%s %+.1f%% 龙头:%s" % (n, r["sec_mom"], lead))
        changes.append(("🎡 A股板块轮动切换", lines))
    # 研报：新研报标题
    prev_rep = set()
    for o in (prev or {}).get("reports", []):
        for it in o["items"]:
            prev_rep.add((o["org"], it["title"]))
    cur_rep = {}
    for o in cur["reports"]:
        for it in o["items"]:
            cur_rep[(o["org"], it["title"])] = (o["org"], it["stock"], it["date"])
    rep_new = [k for k in cur_rep if k not in prev_rep]
    if rep_new:
        lines = []
        for org, title in rep_new[:6]:
            stock, dt = cur_rep[(org, title)][1], cur_rep[(org, title)][2]
            lines.append("%s|%s %s" % (org, stock, title[:30]))
        changes.append(("📄 机构新研报", lines))
    # 财经快讯：新标题
    prev_n = fingerprint_news((prev or {}).get("news", []))
    cur_n = fingerprint_news(cur["news"])
    n_new = [t for t in cur_n if t not in prev_n]
    if n_new:
        lines = [t[:50] for t in n_new[:5]]
        changes.append(("📰 财经快讯", lines))
    # 外媒·宏观 / 名人·大行 / 券商宏观策略（_news_watch 附加组）
    pe, ce = (prev or {}).get("ext") or {}, cur.get("ext") or {}
    for head, key in (("🌐 外媒·宏观", "world"), ("🗣️ 名人·大行动态", "names"),
                      ("📑 券商宏观·策略", "macro_reports")):
        fresh = [t for t in ce.get(key, []) if t not in set(pe.get(key, []))]
        if fresh:
            changes.append((head, [t[:55] for t in fresh[:3]]))
    return changes


# ── 3. 微信推送 ─────────────────────────────────────────────────────────
def load_notify_cfg():
    try:
        with open(APP_CONFIG, encoding="utf-8") as f:
            return (json.load(f).get("notify") or {})
    except (OSError, ValueError):
        return {}


def _push_wechat(title, content, cfg):
    """推送微信。渠道顺序：企业微信机器人(wecom_key) > pushplus > serverchan。

    统一实现见 push_channel.py：企微机器人为本机 POST 直推（零审核，
    不依赖第三方公众号）；pushplus/serverchan 仅作未配 wecom 时的兜底。
    """
    return push_channel.push(title, content, cfg)


def format_content(cur, changes):
    lines = []
    for head, rows in changes:
        lines.append(head)
        lines += ["  " + r for r in rows]
    # 附当前轮动板块（轻量）
    rot = cur.get("rotation") or []
    if rot:
        lines.append("🎡 当前轮动: " + ", ".join(
            "%s%+.1f%%" % (r["industry"], r["sec_mom"]) for r in rot[:5]))
    return "\n".join(lines)


# ── 4. 交易时段 ─────────────────────────────────────────────────────────
def in_trading_time(now=None):
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


def run_once(force=False, dry=False):
    t0 = time.time()
    cur = collect_all()
    cur["ext"] = _news_watch.collect_ext()  # 外媒/宏观/名人/券商宏观策略
    print("[%s] 采集完成 %.1fs" % (cur["ts"], time.time() - t0))
    print("  🇺🇸 美股TOP: %s" % ", ".join(
        "%s%+.1f%%" % (r["name"], r["pct"]) for r in cur["us"][:5]))
    print("  🇰🇷 韩股TOP: %s | KOSPI %s" % (
        ", ".join("%s%+.1f%%" % (r["name"], r["pct"]) for r in cur["kr"]["stocks"]),
        cur["kr"]["index"] and "%.1f%%" % cur["kr"]["index"]["pct"] or "-"))
    print("  🎡 轮动: %s" % ", ".join(
        "%s%+.1f%%" % (r["industry"], r["sec_mom"]) for r in cur["rotation"][:5]))
    print("  📄 研报机构: %s" % ", ".join(
        "%s(%d)" % (o["org"], o["count"]) for o in cur["reports"]))
    print("  📰 快讯: %s" % " | ".join(
        n["title"][:24] for n in cur["news"][:3]))

    prev = {}
    if os.path.exists(LAST_SNAP):
        try:
            with open(LAST_SNAP, encoding="utf-8") as f:
                prev = json.load(f)
        except (OSError, ValueError):
            prev = {}
    changes = diff_snap(prev, cur) if prev else []
    # 首轮或强制：推送完整概览
    if force or not prev:
        changes = [("📡 首次采集", [])] + changes if changes else [("📡 首次采集", [])]

    # 写快照
    with open(LAST_SNAP, "w", encoding="utf-8") as f:
        json.dump(cur, f, ensure_ascii=False, indent=1)

    if dry:
        print("[dry] 不推送")
        return 0
    if not changes:
        print("✅ 无变动，不推送")
        return 0
    title = "📡 盘中情报 %s" % datetime.datetime.now().strftime("%m-%d %H:%M")
    content = format_content(cur, changes)
    # 追加大盘时段因子提示（早盘下杀企稳 / 尾盘勿追）
    sess = cur.get("session") or {}
    if sess.get("reason"):
        tag = "🕐 时段因子"
        if sess.get("late_zone_active"):
            tag = "⚠️ 尾盘警示"
        content = f"{tag} 上证指数: {sess['reason']}\n" + content
    _push_wechat(title, content, load_notify_cfg())
    return 0


def main():
    ap = argparse.ArgumentParser(description="盘中情报扫描器（15分钟收集+变动通知）")
    ap.add_argument("--once", action="store_true", help="执行一次")
    ap.add_argument("--daemon", action="store_true", help="守护轮询（交易时段每 900 秒）")
    ap.add_argument("--interval", type=int, default=DEFAULT_INTERVAL)
    ap.add_argument("--force", action="store_true", help="强制推送")
    ap.add_argument("--dry", action="store_true", help="采集但不推送")
    args = ap.parse_args()
    if args.daemon:
        print("情报守护启动：交易时段每 %d 秒收集，有变动才推送微信" % args.interval)
        while True:
            if in_trading_time():
                try:
                    run_once(force=False, dry=args.dry)
                except KeyboardInterrupt:
                    break
                except Exception as e:
                    print("轮询异常:", type(e).__name__, e)
                time.sleep(args.interval)
            else:
                nxt = next_trading_start()
                wait = max((nxt - datetime.datetime.now()).total_seconds(), 1)
                print("[%s] 非交易时段，等待 %.1f 分钟 → %s" % (
                    datetime.datetime.now().strftime("%H:%M"), wait / 60,
                    nxt.strftime("%m-%d %H:%M")))
                time.sleep(min(wait, 600))
        return 0
    return run_once(force=args.force, dry=args.dry)


if __name__ == "__main__":
    main()
