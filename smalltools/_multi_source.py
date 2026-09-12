# -*- coding: utf-8 -*-
"""多源行情抓取层（2026-09-10）

背景：网络抖动时东财/腾讯日K全挂（2026-09-10 凌晨实测：东财 push2his 与腾讯
web.ifzq 双双超时，仅腾讯批量实时 qt.gtimg.cn 稳）。此前日K路径只有
「东财→腾讯」单链 fallback，单域名全挂 = 全池陪葬。

规则（用户口径 2026-09-10 确认）：
  1. 历史缺口 / 修复回填只允许【前复权 qfq】源（东财多 host / 腾讯 ifzq·sqt），
     保证缓存序列复权口径一致（未复权源写入会撕裂 qfq 序列）。
  2. 未复权实时源（qtg 批量 / sina）只用于补「最新一根真实价」，且落盘前由调用方
     做 prev_close/XD/换手校验（_update_cache_inc 侧把关）。
  3. 股票分 ABC 组走不同源：按 secid 稳定哈希绑定到当前健康 qfq 源（静态分组），
     源连续失败自动熔断冷却，故障组的请求自动迁移到下一个健康源 —— 单域名全挂
     只影响它那一组的那几秒，不会拖死全池。
  4. 并发边界：每个 qfq 源独立 1 个 keep-alive Session；健康表跨线程加锁共享。

用法：
  from _multi_source import fetch_one_qfq, fetch_pool_qfq, realtime_fallback
  fetch_one_qfq("sz000338", "20260901", "20260910")   -> (secid, name, snaps, src)
  fetch_pool_qfq(codes, beg, end, workers=30)          -> {secid: (name, snaps, src)} 失败键缺失
"""
import os
import random
import re
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import requests  # noqa: E402
import backtest_guangmo as _bg  # noqa: E402  复用 HEADERS/PROXIES/腾讯解析/东财解析

_HEADERS = _bg.HEADERS
_PROXIES = _bg.PROXIES
_HDR_SINA = dict(_HEADERS)
_HDR_SINA["Referer"] = "https://finance.sina.com.cn"

# ── qfq 日K源注册 ────────────────────────────────────────────────────────────
# 每源一个 host + 一个 keep-alive Session（域名级故障互不传染）
EAST_HOSTS = [
    "https://push2his.eastmoney.com",
    "https://90.push2his.eastmoney.com",
    "https://17.push2his.eastmoney.com",
    "https://80.push2his.eastmoney.com",
]
# web.sqt.gtimg.cn 实测不提供 fqkline(非JSON)，只保留 ifzq（2026-09-10 验证）
TENCENT_HOSTS = [
    "https://web.ifzq.gtimg.cn",
]

SOURCES = []
for i, h in enumerate(EAST_HOSTS):
    SOURCES.append({"name": "east%d" % i, "kind": "qfq", "host": h,
                    "session": requests.Session()})
for i, h in enumerate(TENCENT_HOSTS):
    SOURCES.append({"name": "tencent%d" % i, "kind": "qfq", "host": h,
                    "session": requests.Session()})

_QFQ_NAMES = [s["name"] for s in SOURCES]


def _src(name):
    return next(s for s in SOURCES if s["name"] == name)


# ── 健康跟踪（跨线程共享，加锁）──────────────────────────────────────────────
class _Health:
    def __init__(self):
        self._lock = threading.Lock()
        self.streak_fail = {s["name"]: 0 for s in SOURCES}
        self.cooldown_until = {s["name"]: 0.0 for s in SOURCES}
        self.total = {s["name"]: 0 for s in SOURCES}
        self.ok = {s["name"]: 0 for s in SOURCES}

    def note(self, name, ok):
        with self._lock:
            self.total[name] += 1
            if ok:
                self.ok[name] += 1
                self.streak_fail[name] = 0
            else:
                self.streak_fail[name] += 1
                # 连续 2 次失败 → 熔断冷却 45s（避免域名级故障期间反复空转）
                if self.streak_fail[name] >= 2:
                    self.cooldown_until[name] = time.time() + 45

    def healthy(self):
        now = time.time()
        with self._lock:
            return [n for n in _QFQ_NAMES if self.cooldown_until[n] <= now]

    def stats(self):
        with self._lock:
            return {n: {"total": self.total[n], "ok": self.ok[n],
                        "fail_streak": self.streak_fail[n],
                        "cooldown_s": max(0.0, self.cooldown_until[n] - time.time())}
                    for n in _QFQ_NAMES}


_HEALTH = _Health()


# ── 单 host 抓取实现（与 backtest_guangmo.fetch_east/fetch_tencent 同构）─────
def _fetch_east_host(src, secid, beg, end, session):
    secid = _bg.to_east_secid(secid)
    params = {
        "secid": secid, "klt": "101", "fqt": "1",
        "beg": beg, "end": end,
        "fields1": "f1,f2,f3,f4,f5,f6",
        "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
    }
    r = session.get(src["host"] + "/api/qt/stock/kline/get", params=params,
                    timeout=8, headers=_HEADERS, proxies=_PROXIES)
    data = r.json()
    if data.get("data") and data["data"].get("klines"):
        return data["data"]["name"], _bg.parse_east_klines(data["data"]["klines"])
    return None, []


def _fetch_tencent_host(src, secid, beg, end, session):
    """qfq 日K（web.ifzq / web.sqt 同一 API 路径），名称/换手率口径与 fetch_tencent 一致。"""
    host = src["host"]
    url = host + "/appstock/app/fqkline/get"
    params = {"param": "%s,day,%s,%s,640,qfq"
              % (secid, _bg.fmt_tencent_date(beg), _bg.fmt_tencent_date(end))}
    r = session.get(url, params=params, timeout=8, headers=_HEADERS, proxies=_PROXIES)
    data = r.json()
    node = (data.get("data") or {}).get(secid) or {}
    rows = node.get("qfqday") or node.get("day") or []
    if not rows:
        return None, []
    snaps = []
    for p in rows:
        snaps.append({
            "date": p[0],
            "open": float(p[1]), "close": float(p[2]),
            "high": float(p[3]), "low": float(p[4]),
            "volume": float(p[5]),
            "changePct": 0.0,
            "turnover": 0.0,
        })
    for i in range(1, len(snaps)):
        prev = snaps[i - 1]["close"]
        if prev > 0:
            snaps[i]["changePct"] = (snaps[i]["close"] / prev - 1) * 100
    fs = _bg.fetch_tencent_float_shares(secid)
    if fs and fs > 0:
        for s in snaps:
            s["turnover"] = s["volume"] * 100 / fs * 100
    name = None
    qt = node.get("qt")
    if isinstance(qt, dict):
        arr = qt.get(secid)
        if isinstance(arr, list) and len(arr) > 1:
            name = arr[1]
    if not name:
        name = node.get("name") or secid
    return name, snaps


# ── 多源调度 ─────────────────────────────────────────────────────────────────
def _rot_order(secid):
    """按 secid 稳定哈希把股票静态绑到某源（组成 A/B/C… 分组）——尽量错峰访问。"""
    active = _HEALTH.healthy()
    if not active:
        active = list(_QFQ_NAMES)
    start = _fnv(secid) % len(active)
    return active[start:] + active[:start]


def _fnv(s):
    h = 2166136261
    for ch in s:
        h = (h ^ ord(ch)) * 16777619 & 0xFFFFFFFF
    return h


def _attempt_once(src_name, secid, beg, end):
    """单源单次请求；成功 True + 结果。返回 (ok, name, snaps)。"""
    s = _src(src_name)
    try:
        if src_name.startswith("east"):
            name, snaps = _fetch_east_host(s, secid, beg, end, s["session"])
        else:
            name, snaps = _fetch_tencent_host(s, secid, beg, end, s["session"])
        ok = bool(snaps)
        _HEALTH.note(src_name, ok)
        return ok, name, snaps
    except Exception:  # noqa: BLE001
        _HEALTH.note(src_name, False)
        return False, None, []


def fetch_one_qfq(secid, beg, end, max_attempts=None):
    """多源 qfq 日K：静态分组首源 → 失败按健康序迁移。返回 (secid, name, snaps, src)。

    max_attempts=None（默认）→ **遍历全部健康源**。
    旧默认 4 < 源总数 5（4 个东财 host + 1 个腾讯 ifzq）：静态分组把票排在末位时，
    「前 4 次全给东财、腾讯永远轮不到」→ 该票直接漏拉。
    2026-09-12 实测：东财 push2his 全挂期间 sh600011 / sh600105 / sh600111 因此
    停在 9/10（腾讯源本身完全正常，单独调 fetch_tencent 能拿到 9/11）。
    """
    order = _rot_order(secid)
    n = len(order) if not max_attempts else min(int(max_attempts), len(order))
    for idx in range(n):
        src_name = order[idx]
        ok, name, snaps = _attempt_once(src_name, secid, beg, end)
        if ok:
            return secid, name, snaps, src_name
        time.sleep(0.15)
    return secid, None, [], "err"


def fetch_pool_qfq(codes, beg, end, workers=30):
    """并发多源拉池。返回 {secid: (name, snaps, src)}，失败票不出现。
    源健康表全进程共享，_update_cache_inc 慢路径 / --force / XD兜底共用。"""
    from concurrent.futures import ThreadPoolExecutor, as_completed
    out = {}
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futs = {pool.submit(fetch_one_qfq, c, beg, end): c for c in codes}
        for fut in as_completed(futs):
            secid = futs[fut]
            try:
                sid, name, snaps, src = fut.result()
            except Exception:  # noqa: BLE001
                continue
            if snaps:
                out[secid] = (name, snaps, src)
    return out


# ── 未复权实时兜底（仅「最新一根真实价」，落盘校验由调用方负责）────────────
def _parse_sina_realtime(text):
    """hq.sinajs.cn 返回 v_xxx="..."; 字段：0名称 1今开 2昨收 3现价 4高 5低
    ... 8成交量(股) 9成交额 30日期 31时间。"""
    got = {}
    for m in re.finditer(r'var hq_str_(\w+)="([^"]*)"', text):
        sid, raw = m.group(1), m.group(2)
        if not raw:
            continue
        f = raw.split(",")
        if len(f) < 32 or not f[30]:
            continue
        try:
            prev_close = float(f[2])
            price = float(f[3])
            got[sid] = {
                "date": f[30].replace("/", "-"),
                "open": float(f[1]),
                "close": price,
                "high": float(f[4]),
                "low": float(f[5]),
                "volume": float(f[8]) / 100.0,       # 股 -> 手
                "changePct": (price / prev_close - 1) * 100 if prev_close > 0 else 0.0,
                "turnover": 0.0,
                "prev_close": prev_close,
            }
        except (ValueError, IndexError):
            continue
    return got


def fetch_sina_realtime(secids, timeout=8):
    """sina 逐批实时（未复权，仅收盘后/实时快照用）。返回 {secid: snap}。"""
    out = {}
    ses = requests.Session()
    for i in range(0, len(secids), 50):
        part = secids[i:i + 50]
        try:
            r = ses.get("https://hq.sinajs.cn/list=" + ",".join(part),
                        timeout=timeout, headers=_HDR_SINA, proxies=_PROXIES)
            r.encoding = "gbk"
            out.update(_parse_sina_realtime(r.text))
        except Exception:  # noqa: BLE001
            continue
    return out


def realtime_fallback(secids, end_ymd, closed_only=True):
    """qtg/sina 未复权实时补「最新一根」。closed_only=True 时（调用方保证已收盘）
    要求返回日 == end_ymd，避免盘中动态价被误当收盘根。返回 {secid: snap}。
    """
    import datetime as _dt
    if closed_only and _dt.datetime.now().time() < _dt.time(15, 0):
        return {}
    from _update_cache_inc import batch_quote_day  # 延迟 import 防环
    end_iso = "%s-%s-%s" % (end_ymd[:4], end_ymd[4:6], end_ymd[6:])
    out = batch_quote_day(secids, end_ymd)
    miss = [c for c in secids if c not in out]
    if miss:
        for sid, s in fetch_sina_realtime(miss).items():
            if s.get("date", "")[:10] == end_iso:
                s = dict(s)
                s["src"] = "sina"
                out.setdefault(sid, s)
    return out


def stats():
    return _HEALTH.stats()


if __name__ == "__main__":
    import json
    probe = ["sh000001", "sz000338", "sz002491", "sh600801", "sz002396", "sh600519"]
    t0 = time.time()
    for c in probe:
        sid, name, snaps, src = fetch_one_qfq(c, "20260901", "20260910")
        print("%-10s %-6s %s 根=%d 末=%s src=%s"
              % (sid, src, name or "-", len(snaps or []),
                 snaps[-1]["date"] if snaps else "-", src))
    print("总耗时 %.1fs" % (time.time() - t0))
    print("源健康:", json.dumps(stats(), ensure_ascii=False))
