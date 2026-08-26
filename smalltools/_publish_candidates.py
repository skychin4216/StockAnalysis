# -*- coding: utf-8 -*-
"""PC 候选清单发布：选股 → 对比上次 → 微信通知 → 上传 COS → 可选守护轮询。

用法：
  python _publish_candidates.py --once            # 跑一次（选股+对比+通知+上传）
  python _publish_candidates.py --once --dry      # 只选股+本地输出，不上传不通知
  python _publish_candidates.py --daemon          # 每 300 秒轮询（盘中盯新买点）
  python _publish_candidates.py --daemon --interval 300

数据流：
  _kline_cache.json(池) + _industry_map(行业) → 候选清单 JSON
  → 与 _last_publish.json 对比出新信号 → 微信(pushplus/serverchan)通知
  → PUT 到 COS(candidates_key)，APK 工作台「PC 候选」Tab 下载展示
"""
import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cos_utils  # noqa: E402
from _full_cycle_backtest import INDEXES, load_cache, market_state  # noqa: E402
from backtest_guangmo import PARAMS, analyze_snaps  # noqa: E402
from _industry_map import build_industry  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
APP_CONFIG = os.path.join(ROOT, "app", "src", "main", "assets", "data", "app_config.json")
LAST_FILE = os.path.join(HERE, "_last_publish.json")
OUT_DEFAULT = os.path.join(ROOT, "AutoQuant", "data", "candidates_quant.json")

DEFAULT_CANDIDATES_KEY = "stockanalysis/quant/candidates.json"
RATIO_THRESHOLD = {"超短": 0.55, "短线": 0.55, "中线": 0.55, "长线": 0.55}
GROUP_SIZE = 8
PREPARED_SIZE = 15
PUSH_HEADERS = {"User-Agent": "Mozilla/5.0", "Content-Type": "application/json"}


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
        for period, p in PARAMS.items():
            try:
                r = analyze_snaps(sub, p, trend)
            except Exception:
                continue
            ratio = r["passCount"] / max(r["totalChecks"], 1)
            if best is None or ratio > best[1]:
                best = (period, ratio, r["passCount"], r["totalChecks"])
        if best:
            score[code] = {"period": best[0], "ratio": round(best[1], 2),
                           "pass": best[2], "total": best[3]}
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


# ── 2. 候选清单生成 ────────────────────────────────────────────────────
def build_candidates(cache, industry):
    asof, trend, _ = market_state_trend(cache)
    if not asof:
        return None
    score = score_pool(cache, asof, trend)
    groups = {p: [] for p in PARAMS}
    for code, s in score.items():
        if s["ratio"] >= RATIO_THRESHOLD.get(s["period"], 0.5):
            ent = cache.get(code, {})
            groups[s["period"]].append({
                "secid": code,
                "name": ent.get("name") or code,
                "industry": industry.get(code[2:], "其他"),
                "board": board_of(code[2:]),
                "pass": s["pass"], "total": s["total"], "ratio": s["ratio"],
            })
    for p in groups:
        groups[p].sort(key=lambda r: (r["ratio"], r["pass"]), reverse=True)
        groups[p] = groups[p][:GROUP_SIZE]
    prepared = []
    for code, s in score.items():
        ent = cache.get(code, {})
        prepared.append({
            "secid": code, "name": ent.get("name") or code,
            "industry": industry.get(code[2:], "其他"),
            "board": board_of(code[2:]), "period": s["period"],
            "pass": s["pass"], "total": s["total"], "ratio": s["ratio"]})
    prepared.sort(key=lambda r: (r["ratio"], r["pass"]), reverse=True)
    prepared = prepared[:PREPARED_SIZE]
    advice = {
        "BULLISH": "大盘多头排列，可积极操作，优先强势板块龙头回踩买点",
        "BEARISH": "大盘弱势，防守为主，只做高确定性买点，控制仓位",
        "OSCILLATION": "震荡市，结构性行情，聚焦板块轮动，快进快出",
        "NO_DATA": "数据不足，等待行情库更新",
    }[trend]
    return {
        "schema": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "asof": asof,
        "market_state": trend,
        "advice": advice,
        "groups": groups,
        "prepared": prepared,
        "hot_sectors": hot_sectors(cache, industry, asof),
        "pool_total": len(cache),
    }


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
    sent = False
    token = cfg.get("pushplus_token", "").strip()
    if token:
        try:
            req = urllib.request.Request(
                cfg.get("pushplus_url", "https://www.pushplus.plus/send"),
                data=json.dumps({"token": token, "title": title, "content": content,
                                 "template": "txt"}).encode("utf-8"),
                headers=PUSH_HEADERS, method="POST")
            with urllib.request.urlopen(req, timeout=10) as r:
                ok = json.loads(r.read().decode("utf-8")).get("code") in (200, "200")
            print("pushplus 通知 %s" % ("成功" if ok else "返回失败"))
            sent = sent or ok
        except Exception as e:
            print("pushplus 通知失败:", type(e).__name__, e)
    key = cfg.get("serverchan_key", "").strip()
    if key and not sent:
        try:
            url = ("%s/%s.send?title=%s&desp=%s" % (
                cfg.get("serverchan_url", "https://sctapi.ftqq.com"), key,
                urllib.parse.quote(title), urllib.parse.quote(content)))
            with urllib.request.urlopen(url, timeout=10) as r:
                ok = json.loads(r.read().decode("utf-8")).get("code") == 0
            print("serverchan 通知 %s" % ("成功" if ok else "返回失败"))
            sent = sent or ok
        except Exception as e:
            print("serverchan 通知失败:", type(e).__name__, e)
    if not sent:
        print("未配置推送 token(notify.pushplus_token / notify.serverchan_key)，以下消息未发送：\n%s\n%s" % (title, content))
    return sent


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


# ── 5. 主流程 ──────────────────────────────────────────────────────────
def run_once(dry=False, candidates_key=None):
    cache = load_cache()
    industry = build_industry()
    data = build_candidates(cache, industry)
    if not data:
        print("无有效数据（缓存为空？）")
        return 1
    os.makedirs(os.path.dirname(OUT_DEFAULT), exist_ok=True)
    with open(OUT_DEFAULT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print("候选清单 %d 只 → %s" % (sum(len(v) for v in data["groups"].values()), OUT_DEFAULT))
    print("大盘 %s | asof %s | 池 %d" % (data["market_state"], data["asof"], data["pool_total"]))
    for p, items in data["groups"].items():
        print("  [%s] %s" % (p, ", ".join("%s%s" % (it["name"], it["secid"][2:]) for it in items)))
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
    send_wechat(data, candidate_secids(old) if old else set(), load_notify_cfg())
    upload_candidates(data, candidates_key)
    with open(LAST_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    return 0


def main():
    ap = argparse.ArgumentParser(description="PC 候选清单发布（选股→通知→COS）")
    ap.add_argument("--once", action="store_true", help="执行一次")
    ap.add_argument("--daemon", action="store_true", help="守护轮询（默认 300 秒）")
    ap.add_argument("--interval", type=int, default=300, help="轮询间隔秒")
    ap.add_argument("--dry", action="store_true", help="不通知不上传（调试）")
    ap.add_argument("--key", default=None, help="COS candidates_key，默认 stockanalysis/quant/candidates.json")
    args = ap.parse_args()
    if args.daemon:
        print("守护轮询启动，每 %d 秒一次（Ctrl+C 退出）" % args.interval)
        while True:
            try:
                run_once(dry=args.dry, candidates_key=args.key)
            except KeyboardInterrupt:
                break
            except Exception as e:
                print("轮询异常:", type(e).__name__, e)
            time.sleep(args.interval)
        return 0
    return run_once(dry=args.dry, candidates_key=args.key)


if __name__ == "__main__":
    sys.exit(main())
