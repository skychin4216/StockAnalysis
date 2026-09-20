# -*- coding: utf-8 -*-
"""分钟K数据源 + 「可成交最高价」计算（2026-09-19 用户需求）。

## 为什么需要它

复盘巡诊原先取当日日K的 `high` 当"最高价"算空间，但那是**瞬时波峰** ——
真实盘口上人手速来不及挂到那个价（尤其冲高回落的一笔）。用户要求换成
「**能卖掉**」的口径：一个价位至少能维持 10~30 秒，才承认它是可成交价。

## 口径（5 分钟K近似）

- 单根 5 分钟K 的时间窗 = 300 秒 ≫ 30 秒，故窗口内出现过的价格**存在可成交的窗口**；
- 但仍要剔除「无量尖刺」（开盘一笔/尾盘一笔拉高）：某根分钟K 的成交量低于
  **当日分钟K成交量中位数 × 0.5** 时，其 high 视为偶然撮合、不计入；
- **可成交最高价 `tx` = max over i of min(high[i], high[i+1])**（连续 2 根有量分钟K
  = 10 分钟窗口都能达到的最高价）。单根孤立尖峰（开盘/尾盘一笔拉高）天然被剔除，
  因为相邻那根达不到该价位 —— 这正是"人手速来不及卖"的典型场景。
  若分钟K不足 2 根，回退单根口径 `hi_vol`；
- 另返回 `hi`（当日最高，理论上限）与 `hi_vol`（有量单根最高）作对照；
- 诚实说明：5 分钟K的 high 严格说仍是窗口内瞬时价，但"连续 2 根都达到"已要求
  价格在该位维持 ≈10 分钟，远严于 10~30 秒的要求。

## 数据源

主源新浪 `scale=5`（300 根 ≈ 6 个交易日，实测可用）；
备选腾讯 proxy `mkline?param=...,m5`（实测可用；末根含盘中实时）。

## 用法

    python _intraday.py 300308          # 近6日「日K最高 vs 可成交最高」对照
    python _intraday.py --check         # 两源自检

模块内调用：`fetch_m5(code6, n=300)` / `tradable_highs(code6)`。
缓存：`data/_intraday_YYYYMMDD.json`（当日，跨日自动失效）。
"""
import datetime
import json
import os
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA_DIR = os.path.join(ROOT, "data")
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import _sources as _S  # noqa: E402

PROXIES = {"http": None, "https": None}
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
VOL_KEEP = 0.5      # 量能过滤阈值：≥ 当日分钟量中位数 × 该系数 才认作"有量"
_MEMO = {}


def _sid(code6):
    """裸代码 → 带市场前缀（sz/sh/bj）。"""
    c = str(code6).strip()
    if c[:2].lower() in ("sh", "sz", "bj"):
        return c.lower()
    if c.startswith(("6", "9")):
        return "sh" + c
    if c.startswith(("4", "8")):
        return "bj" + c
    return "sz" + c


def _parse_sina(txt):
    """新浪 JSONP → [{day,open,high,low,close,vol}]。"""
    i, j = txt.find("["), txt.rfind("]")
    if i < 0 or j <= i:
        return []
    try:
        arr = json.loads(txt[i:j + 1])
    except Exception:  # noqa: BLE001
        return []
    out = []
    for it in arr:
        try:
            out.append({"day": str(it.get("day") or ""),
                        "open": float(it.get("open") or 0),
                        "high": float(it.get("high") or 0),
                        "low": float(it.get("low") or 0),
                        "close": float(it.get("close") or 0),
                        "vol": float(it.get("volume") or 0)})
        except (TypeError, ValueError):
            continue
    return out


def _parse_tencent(obj, sid):
    """腾讯 proxy mkline → 同上（字段序：时间,开,收,高,低,量(手)）。"""
    node = (obj.get("data") or {}).get(sid) or {}
    out = []
    for r in (node.get("m5") or []):
        try:
            t = str(r[0])
            day = "%s-%s-%s %s:%s:00" % (t[:4], t[4:6], t[6:8], t[8:10], t[10:12])
            out.append({"day": day, "open": float(r[1]), "close": float(r[2]),
                        "high": float(r[3]), "low": float(r[4]), "vol": float(r[5])})
        except (IndexError, TypeError, ValueError):
            continue
    return out


def fetch_m5(code6, n=300, scale="5"):
    """分钟K（升序）。新浪为主、腾讯 proxy 为备；失败返回 []。

    scale="5"  → 5 分钟（新浪上限 ~1023 根 ≈ **21 个交易日**）
    scale="60" → 60 分钟（新浪上限 ~1023 根 ≈ **1 年**，用于"维持 1 小时"的长窗口口径：
                 1 根 = 1 小时 ⇒ 连续 2 根都达到该价 = 维持 ≥2 小时，比"1 小时"要求更严）

    ⚠️ 实测硬上限（2026-09-19，勿再试探）：
      · 新浪 `datalen` > 1023 → **直接返回 0 根**（不是截断）；scale=60 满额 1023 根
        覆盖 2025-09-01 ~ 2026-09-18（≈1 年）。
      · 腾讯 proxy `mkline` 恒 **320 根**（m5≈7 个交易日 / m60≈3.5 个月）。
      · 东财 klt=5/60 本机 5 个分片全 ConnectionError（被断）。
    ⇒ **2008 年至今的分钟K在免费源不可能拿到**（差 18~300 倍）。18 年回溯只能用
      日K口径；"维持"口径最长可用**近 1 年的 60 分钟K**（本参数）。
    """
    sid = _sid(code6)
    if sid in _MEMO:
        return _MEMO[sid]
    bars = []
    try:
        u = _S.url("sina_m5", codes=sid, scale=str(scale), n=str(int(n)))
        r = requests.get(u, timeout=12, headers={**UA, **_S.headers("sina_m5")},
                         proxies=PROXIES)
        bars = _parse_sina(r.text)
    except Exception:  # noqa: BLE001
        bars = []
    if not bars:
        try:
            u = _S.url("tencent_proxy_m5", codes=sid, n=str(int(n)))
            r = requests.get(u, timeout=12, headers={**UA, **_S.headers("tencent_proxy_m5")},
                             proxies=PROXIES)
            bars = _parse_tencent(r.json(), sid)
        except Exception:  # noqa: BLE001
            bars = []
    bars.sort(key=lambda b: b["day"])
    if bars:
        _MEMO[sid] = bars
    return bars


def _cache_path():
    return os.path.join(DATA_DIR, "_intraday_%s.json" % time.strftime("%Y%m%d"))


def _load_cache():
    try:
        with open(_cache_path(), encoding="utf-8") as f:
            return json.load(f)
    except Exception:  # noqa: BLE001
        return {}


def _save_cache(c):
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        with open(_cache_path(), "w", encoding="utf-8") as f:
            json.dump(c, f, ensure_ascii=False)
    except Exception:  # noqa: BLE001
        pass


def tradable_highs(code6, days=6):
    """{日期: {hi, tx, n, kept, med}}。

    hi = 当日分钟K最高价（≈日K high，含瞬时尖峰）
    tx = **可成交最高价**（量能过滤后的最高价；全部被滤则回退 hi）
    n/kept = 当日分钟K根数 / 过滤后保留根数（用于判断可信度）
    """
    sid = _sid(code6)
    cache = _load_cache()
    c_key = sid
    if isinstance(cache.get(c_key), dict) and cache[c_key]:
        return {d: v for d, v in cache[c_key].items()}
    bars = fetch_m5(sid, n=max(120, int(days) * 48 + 20))
    by_day = {}
    for b in bars:
        d = b["day"][:10]
        if d:
            by_day.setdefault(d, []).append(b)
    out = {}
    for d, bs in by_day.items():
        vols = sorted(b["vol"] for b in bs)
        med = vols[len(vols) // 2] if vols else 0.0
        thr = med * VOL_KEEP
        kept = [b for b in bs if b["vol"] >= thr] or bs
        # ① 单根口径（参考）：有量分钟K的最高价 —— 相对日K high 已剔除无量尖刺
        hi_vol = max(b["high"] for b in kept)
        # ② 「维持」口径（主口径，对应用户「至少维持 10~30s」）：
        #    连续 2 根有量分钟K（=10 分钟窗口）**都能达到**的最高价
        #    `min(high[i], high[i+1])` 天然剔除单根孤立尖峰（开盘/尾盘一笔拉高），
        #    价格能连续 10 分钟站上该位 → 挂单有充足成交窗口。
        pair = 0.0
        for i in range(len(kept) - 1):
            pair = max(pair, min(kept[i]["high"], kept[i + 1]["high"]))
        out[d] = {
            "hi": round(max(b["high"] for b in bs), 3),
            "tx": round(pair or hi_vol, 3),
            "hi_vol": round(hi_vol, 3),
            "n": len(bs), "kept": len(kept), "med": round(med, 1),
        }
    if out:
        cache[c_key] = out
        _save_cache(cache)
    return out


# ── 选股成败判定（用户 2026-09-19 口径）──────────────────────────────────
#   成功 = 5 日内价格 > 入选价×1.05 **且维持 1 小时以上**
#   尚可 = 涨幅 1%~5% 且价格**站稳 30 分钟以上**
#   失败 = 5 日内最高（可成交）价 ≤ 入选价×1.01（连 1% 都没摸到）
#   weak = 摸到 >1% 但没站稳 30 分钟（介于尚可与失败之间，如实单列）
# 用 5 分钟K计数：1 小时 = 12 根，30 分钟 = 6 根；**按日切分**（不跨隔夜，
# 跨日连算会把隔夜跳空算成"维持"，不严谨）。
RUN_1H = 12
RUN_30M = 6


def _max_run_lows(bars, thr):
    """单日内最长连续满足 low ≥ thr 的根数。"""
    best = cur = 0
    for b in bars:
        if b["low"] >= thr:
            cur += 1
            if cur > best:
                best = cur
        else:
            cur = 0
    return best


def eval_trade(code6, base, since=None, days=5):
    """按用户口径评估单只票的选股成败。

    返回 {level, best_pct, run_1h, run_30m, n_days, thr_1h, thr_30m}
    level ∈ success / ok / weak / fail / nodata
    """
    bars = fetch_m5(code6, n=400)
    if not bars or not base:
        return {"level": "nodata", "best_pct": None, "run_1h": 0,
                "run_30m": 0, "n_days": 0}
    all_days = sorted({b["day"][:10] for b in bars})
    if since:
        keep = [d for d in all_days if d > since][:days]
    else:
        keep = all_days[-days:]
    if not keep:
        return {"level": "nodata", "best_pct": None, "run_1h": 0,
                "run_30m": 0, "n_days": 0}
    keep_set = set(keep)
    seq = [b for b in bars if b["day"][:10] in keep_set]
    thr_5, thr_1 = base * 1.05, base * 1.01
    run_1h = run_30m = 0
    for d in keep:                      # 按日切分，不跨隔夜
        day_bars = [b for b in seq if b["day"][:10] == d]
        run_1h = max(run_1h, _max_run_lows(day_bars, thr_5))
        run_30m = max(run_30m, _max_run_lows(day_bars, thr_1))
    best = max((b["high"] for b in seq), default=0.0)
    best_pct = (best / base - 1) * 100 if best else 0.0
    if run_1h >= RUN_1H:
        lv = "success"
    elif run_30m >= RUN_30M:
        lv = "ok"
    elif best <= base * 1.01:
        lv = "fail"
    else:
        lv = "weak"
    return {"level": lv, "best_pct": round(best_pct, 2), "run_1h": run_1h,
            "run_30m": run_30m, "n_days": len(keep),
            "thr_1h": thr_5, "thr_30m": thr_1}


def check():
    """两源自检：各拉一只票，打印根数与末根。"""
    for name, fn in (("sina_m5", "sina"), ("tencent_proxy_m5", "tencent")):
        try:
            if fn == "sina":
                u = _S.url("sina_m5", codes="sz300308", scale="5", n="60")
                r = requests.get(u, timeout=12, headers={**UA, **_S.headers(name)},
                                 proxies=PROXIES)
                bars = _parse_sina(r.text)
            else:
                u = _S.url("tencent_proxy_m5", codes="sz300308", n="60")
                r = requests.get(u, timeout=12, headers={**UA, **_S.headers(name)},
                                 proxies=PROXIES)
                bars = _parse_tencent(r.json(), "sz300308")
            print("  %-20s 根数=%-4d 末根=%s" % (
                name, len(bars), bars[-1] if bars else "—"))
        except Exception as e:  # noqa: BLE001
            print("  %-20s ERR %s: %s" % (name, type(e).__name__, str(e)[:60]))


def main(argv):
    if not argv:
        print(__doc__)
        return
    if argv[0] == "--check":
        check()
        return
    for code in argv:
        th = tradable_highs(code)
        print("=== %s 可成交高价（量能阈值 ≥ 中位数×%.1f）===" % (code, VOL_KEEP))
        print("  日期          日K最高  有量单根  可成交(维持)  保留/总根数  中位量")
        for d in sorted(th)[-6:]:
            v = th[d]
            print("  %s  %8.2f  %8.2f  %10.2f    %3d/%-3d    %.0f" % (
                d, v["hi"], v["hi_vol"], v["tx"], v["kept"], v["n"], v["med"]))


if __name__ == "__main__":
    main(sys.argv[1:])
