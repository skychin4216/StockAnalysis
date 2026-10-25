# -*- coding: utf-8 -*-
"""ER分位 × BBI12 上行拐点 · 双条件筛选（2026-10-18 用户需求）

一句话：在每日荐股核心池里，找「ER 分位高 **且** BBI12 处于谷底拐点之后仍在上升」的票，
排除下跌/回落中的票，输出 Top N。

口径（与既有体系完全同源）：
- 池 = app/src/main/assets/core_stock_pool.json（每日荐股核心池，~171 只）。
- ER = 日线 Kaufman 效率比 W=60，池内**中位秩**横截面分位（与 PC 推送 er_pct_map 同公式）。
- BBI12 = SMA(BBI,12)，BBI=(MA3+MA6+MA12+MA24)/4 —— 直接复用
  `smalltools/_er_router_backtest.py` 的 `bbi12_line` / `bbi12_pivots`（与 Kotlin 引擎同口径，
  396/400 一致）。拐点须次根确认（信号根=确认根）。
- 「上升拐点之后的趋势」= 末个**已确认**拐点是谷底，且末根 BBI12 仍在上升
  （line[-1] >= line[-2]）；其余（末拐点=峰顶 / BBI12 走低）= 下跌或回落 → 排除。

数据源：优先 data/kline_store.json（守护环境）；本机没有时 **--fetch 直接抓新浪日线**
（scale=240，仅抓池内 ~171 只，缓存 smalltools/_records/_er_screen_cache.json，重跑免抓）。
用法：python smalltools/_er_bbi12_screen.py [--top 10] [--fetch] [--refresh]
"""
import bisect
import json
import math
import os
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from smalltools._er_router_backtest import bbi12_line, bbi12_pivots, er_series  # noqa: E402

POOL_FILE = os.path.join(ROOT, "app", "src", "main", "assets", "core_stock_pool.json")
CACHE = os.path.join(ROOT, "smalltools", "_records", "_er_screen_cache.json")
ER_W = 60
MIN_BARS = 130          # ER(W=60) + BBI12 预热(~35) + 余量
DAILY_LEN = 160         # 抓取日线根数


def fetch_daily(code, datalen=DAILY_LEN):
    """新浪日线（scale=240）：[{day, open, high, low, close, volume}...]；失败返回 []。"""
    sym = ("sh" if code[0] in "569" else "sz") + code
    url = ("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
           "CN_MarketData.getKLineData?symbol=%s&scale=240&ma=no&datalen=%d" % (sym, datalen))
    for att in range(3):
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": "Mozilla/5.0", "Referer": "https://finance.sina.com.cn/"})
            body = urllib.request.urlopen(req, timeout=15).read().decode("utf-8", "ignore")
            if body.strip().startswith("["):
                return [{"date": o["day"][:10], "close": float(o["close"])}
                        for o in json.loads(body) if o.get("close") is not None]
        except Exception:
            pass
        time.sleep(0.4 * (att + 1))
    return []


def load_universe(pool, refresh=False):
    """返回 {c6: {"snaps": [{"date","close"}...]}}：kline_store 优先 → 缓存 → 网络抓取。"""
    import smalltools._kline_store as ks
    try:
        store = ks.load_store()
        out = {}
        for secid, ent in (store or {}).items():
            if not isinstance(ent, dict):
                continue
            c6 = secid[2:] if secid[:2] in ("sh", "sz", "bj") else secid
            if c6 in pool:
                out[c6] = {"snaps": [{"date": str(s.get("date") or ""),
                                      "close": float(s.get("close") or 0)}
                                     for s in (ent.get("snaps") or [])]}
        if out:
            print("数据源：data/kline_store.json（%d 只池内命中）" % len(out))
            return out
    except FileNotFoundError:
        pass
    if not refresh and os.path.exists(CACHE):
        d = json.load(open(CACHE, encoding="utf-8"))
        if len(d) >= len(pool) * 0.8:
            print("行情缓存：%s（%d 只）" % (CACHE, len(d)))
            return d
    print("本机无 kline_store → 网络抓新浪日线（%d 只 × %d 根）…" % (len(pool), DAILY_LEN))
    codes = sorted(pool)

    def one(c):
        return c, fetch_daily(c)

    got = {}
    with ThreadPoolExecutor(max_workers=8) as ex:
        for i, (c, b) in enumerate(ex.map(one, codes), 1):
            if len(b) >= MIN_BARS:
                got[c] = {"snaps": b}
            if i % 40 == 0 or i == len(codes):
                print("  [%d/%d] 已取 %d 只" % (i, len(codes), len(got)))
    if got:
        os.makedirs(os.path.dirname(CACHE), exist_ok=True)
        json.dump(got, open(CACHE, "w", encoding="utf-8"), ensure_ascii=False)
        print("已缓存 → %s" % CACHE)
    return got


def core_pool_codes():
    """每日荐股核心池 6 位代码集合；缺文件返回空集合（此时提示用户）。"""
    try:
        with open(POOL_FILE, encoding="utf-8") as f:
            arr = json.load(f).get("stocks") or []
    except Exception as e:
        print("⚠️ 核心池不可读：", type(e).__name__, e)
        return set()
    out = set()
    for s in arr:
        s = str(s or "").strip()
        if len(s) > 6 and s[:2] in ("sh", "sz", "bj"):
            s = s[2:]
        if s:
            out.add(s)
    return out


def fetch_names(secids):
    """东财批量票名（尽力而为；失败返回空表，不影响主流程）。"""
    try:
        import urllib.request
        q = ",".join(("1." if s.startswith("sh") else "0.") + s[2:] for s in secids)
        url = ("https://push2.eastmoney.com/api/qt/ulist.np/get?secids=" + q +
               "&fields=f12,f14&fltt=2&invt=2")
        with urllib.request.urlopen(url, timeout=6) as r:
            data = json.loads(r.read().decode("utf-8"))
        diff = (data.get("data") or {}).get("diff") or []
        if isinstance(diff, dict):
            diff = list(diff.values())
        return {str(d.get("f12")): str(d.get("f14") or "") for d in diff}
    except Exception:
        return {}


def main():
    top_n = 10
    refresh = False
    args = sys.argv[1:]
    if "--top" in args:
        try:
            top_n = max(1, int(args[args.index("--top") + 1]))
        except Exception:
            pass
    refresh = "--refresh" in args

    pool = core_pool_codes()
    if not pool:
        print("⚠️ 核心池为空，无法筛选")
        return
    data = load_universe(pool, refresh=refresh)

    ers, states = {}, {}          # c6 → ER 原值；c6 → 状态元组
    scanned = 0
    for c6, ent in data.items():
        if c6 not in pool:
            continue
        scanned += 1
        snaps = [s for s in (ent.get("snaps") or []) if (s.get("close") or 0) > 0]
        if len(snaps) < MIN_BARS:
            continue
        closes = [float(s["close"]) for s in snaps]
        e = er_series(closes, ER_W)[-1]
        if e != e:                 # 末根 ER 无效（数据不足）
            continue
        line = bbi12_line(closes)
        piv = bbi12_pivots(line)
        if not piv or line[-1] != line[-1] or line[-2] != line[-2]:
            continue
        idx, is_bottom = piv[-1]   # 末个已确认拐点（信号根=确认根）
        up = bool(is_bottom) and line[-1] >= line[-2]
        if idx >= len(snaps):
            idx = len(snaps) - 1
        ers[c6] = e
        states[c6] = {
            "up": up, "trough_i": idx, "is_bottom": is_bottom,
            "bars_after": len(closes) - 1 - idx,
            "last_date": str(snaps[-1].get("date") or ""),
            "trough_date": str(snaps[idx].get("date") or ""),
            "close": closes[-1],
            "gain": (line[-1] / line[idx] - 1.0) * 100.0 if line[idx] > 0 else float("nan"),
            "slope_up": line[-1] >= line[-2],
        }

    if len(ers) < 2:
        print(f"⚠️ 有效样本不足（池 {len(pool)} 只，实际可算 {len(ers)} 只）")
        return
    vals = sorted(ers.values())
    n = len(vals)
    pct = {c: 100.0 * (bisect.bisect_left(vals, e)
                       + 0.5 * (bisect.bisect_right(vals, e) - bisect.bisect_left(vals, e))) / n
           for c, e in ers.items()}

    ups = [c for c in ers if states[c]["up"]]
    downs = [c for c in ers if not states[c]["up"]]
    rows = sorted(ups, key=lambda c: -pct[c])[:top_n]

    names = fetch_names([("sh" if c[0] in "569" else "sz") + c for c in rows]) or {}

    print(f"\n=== ER分位 × BBI12 上行拐点 · Top {len(rows)} ===")
    print(f"池 {len(pool)} 只 · 可算 ER {n} 只 · 上行中 {len(ups)} 只（排除下行/回落 {len(downs)} 只）"
          f" · 数据截至 {next(iter(states.values()))['last_date']}\n")
    print(f"{'#':>2}  {'代码':<8}{'名称':<8}{'ER分位':>7}  {'BBI12 状态':<16}{'谷底日':<11}"
          f"{'收盘':>9}  {'BBI12距谷底':>10}")
    for i, c in enumerate(rows, 1):
        st = states[c]
        arrow = "↑" if pct[c] >= 70 else " "
        print(f"{i:>2}  {c:<8}{names.get(c, '—'):<8}{pct[c]:>6.0f}{arrow} "
              f"{'谷底后第%d根·上行' % st['bars_after']:<14}{st['trough_date']:<11}"
              f"{st['close']:>9.2f}  {st['gain']:>+9.2f}%")

    print("\n参考 · ER 分位最高但被趋势条件排除（下行/峰顶后）的前 5 只：")
    for i, c in enumerate(sorted(downs, key=lambda c: -pct[c])[:5], 1):
        st = states[c]
        why = "峰顶之后" if not st["is_bottom"] else "BBI12 走低"
        print(f"  {i}. {c} {names.get(c, '')}  ER {pct[c]:.0f}  ({why})")
    print("\n口径：ER=日线 W=60 池内中位秩分位（同 PC er_pct_map）；BBI12=SMA(BBI,12)（同回测/Kotlin 引擎）；"
          "拐点须次根收盘确认。仅排序观察，不构成投资建议。")


if __name__ == "__main__":
    main()
