# -*- coding: utf-8 -*-
"""个股资金流数据源 + v2.1 双层评分（2026-09-18 用户需求）。

数据源（2026-09-18 实测本机网络）：
  · 主源 = **新浪历史资金流**（MoneyFlow.ssl_qsfx_zjlrqs，30~60 日逐日序列，稳定可用）：
      opendate 日期 | trade 收盘 | changeratio 涨跌幅(小数) | turnover 换手
      netamount 净流入(元) | ratioamount 净占比 | r0_net 超大单净额(元) | r0_ratio 超大单占比
    实测中际旭创 2026-09-18：netamount=+21.99亿、r0_net=+21.99亿、涨 +3.40%。
  · 增强（可选）= 东财 push2delay 当日 4 档明细（主力/超大单/大单/中单/小单，盘中实时）。
    ⚠️ 东财 push2his / push2 全域名在本机不可达（ConnectionError，与项目既有踩坑一致），
    仅 push2delay 可达；历史序列因此改用新浪。

评分口径 = docs 同源《资金流选股策略_优化设计方案_v2.1.md》：
  · 趋势分 0-6：20D(3分) > 10D(2分) > 5D(1分)，1D 仅作确认(+0.5，需 5D>0)；
    价格位置惩罚：距5日均线>8% 或 距10日均线>12% → 各 -1（抑制追高）；
  · 质量分 0-4：超大单占比≥0.6→2 / ≥0.4→1；5日流入加速 +1；无 NOISE/背离 +1；
  · 总分 0-10 → 8-10 STRONG_BUY / 6-7.5 BUY / 4-5.5 WATCH / 0-3.5 SKIP。
  说明：设计文档中的「尾盘30分钟占比 END_SPIKE」该日线接口不提供，未实现（注释保留）。

用法：
  python _stock_fundflow.py 300308 600188     # 单票评分详情
  python _stock_fundflow.py --top 20          # kline_store 全池资金流 Top20

模块内调用：fetch_fflow(code6) / score(code6, snaps=None) / batch(codes)
缓存：data/_stock_fflow_YYYYMMDD.json（当日），跨日自动失效。
"""
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA_DIR = os.path.join(ROOT, "data")
if HERE not in sys.path:
    sys.path.insert(0, HERE)

UA_SINA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
           "Referer": "https://finance.sina.com.cn/"}
UA_EAST = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
           "Referer": "https://quote.eastmoney.com/"}
PROXIES = {"http": None, "https": None}
# 2026-09-19 用户需求：URL 统一收敛到 data/datasources.json（走 _sources 调用）；
# 配置缺失时回退硬编码，保证任何情况下可用。
try:
    import _sources as _SRC
    SINA_URL = _SRC.url("sina_moneyflow")
    EAST_DELAY = _SRC.url("east_delay_fflow")
except Exception:  # noqa: BLE001
    SINA_URL = ("https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
                "MoneyFlow.ssl_qsfx_zjlrqs")
    EAST_DELAY = "https://push2delay.eastmoney.com/api/qt/stock/fflow/daykline/get"
_SESSION = requests.Session()
_MEMO = {}          # 进程内缓存 {code6: rows}


def _full_code(code6):
    """裸代码 → 带市场前缀（新浪口径）：6/9=sh，4/8=bj，其余=sz。"""
    c = str(code6).strip()
    if c[:2].lower() in ("sh", "sz", "bj"):
        return c.lower()
    if c.startswith(("6", "9")):
        return "sh" + c
    if c.startswith(("4", "8")):
        return "bj" + c
    return "sz" + c


def _secid(code6):
    """裸代码 → 东财 secid：6/9 开头=沪(1.)，其余=深/北(0.)。"""
    c = str(code6).strip()
    c = c[2:] if c[:2].lower() in ("sh", "sz", "bj") else c
    return ("1." if c.startswith(("6", "9")) else "0.") + c


def _cache_path(day=None):
    import datetime as _dt
    d = day or _dt.date.today().strftime("%Y%m%d")
    return os.path.join(DATA_DIR, "_stock_fflow_%s.json" % d)


def _load_cache():
    try:
        with open(_cache_path(), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _save_cache(cache):
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        tmp = _cache_path() + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cache, f, ensure_ascii=False)
        os.replace(tmp, _cache_path())
    except OSError:
        pass


def _parse_east(klines):
    """东财 klines(逗号串) → [{date,main,small,mid,large,jumbo,...}]，金额单位亿。"""
    rows = []
    for k in klines or []:
        p = str(k).split(",")
        if len(p) < 13:
            continue
        def _f(i):
            try:
                return float(p[i] or 0)
            except ValueError:
                return 0.0
        main = _f(1) / 1e8
        jumbo = _f(5) / 1e8
        rows.append({
            "date": p[0],
            "main": round(main, 4),       # 主力净流入(亿)
            "small": round(_f(2) / 1e8, 4),
            "mid": round(_f(3) / 1e8, 4),
            "large": round(_f(4) / 1e8, 4),
            "jumbo": round(jumbo, 4),     # 超大单
            "main_pct": _f(6),            # 主力净占比%
            "jumbo_ratio": round(min(1.0, (jumbo / main) if main > 0 else 0.0), 4),
            "close": _f(11),
            "chg": _f(12),                # 当日涨跌幅%
        })
    rows.sort(key=lambda r: r["date"])
    return rows


def _parse_sina(items):
    """新浪 JSON 数组（opendate 降序）→ 升序 rows，金额单位亿。"""
    rows = []
    for it in items or []:
        if not isinstance(it, dict):
            continue
        try:
            main = float(it.get("netamount") or 0) / 1e8
            jumbo = float(it.get("r0_net") or 0) / 1e8
            close = float(it.get("trade") or 0)
            chg = float(it.get("changeratio") or 0) * 100
        except (TypeError, ValueError):
            continue
        rows.append({
            "date": str(it.get("opendate") or ""),
            "main": round(main, 4),
            "jumbo": round(jumbo, 4),
            # 新浪仅给 r0（超大单）明细 → 大单净额 ≈ 主力 - 超大单，中/小单缺（置 0）
            "large": round(main - jumbo, 4),
            "mid": 0.0,
            "small": 0.0,
            "main_pct": round(float(it.get("ratioamount") or 0) * 100, 4),
            "jumbo_ratio": round(min(1.0, (jumbo / main) if main > 0 else 0.0), 4),
            "close": close,
            "chg": round(chg, 4),
            "turnover": float(it.get("turnover") or 0),
        })
    rows.sort(key=lambda r: r["date"])
    return rows


def _fetch_sina(code6, days=60):
    """新浪历史资金流序列（默认近 60 交易日），失败返回 []。"""
    try:
        r = _SESSION.get(SINA_URL, params={
            "page": "1", "num": str(max(5, int(days))), "sort": "opendate",
            "asc": "0", "daima": _full_code(code6)},
            timeout=12, headers=UA_SINA, proxies=PROXIES)
        return _parse_sina(r.json())
    except Exception:
        return []


def _east_today_row(code6):
    """东财 push2delay 当日 4 档明细（盘中实时；失败返回 None）。"""
    try:
        r = _SESSION.get(EAST_DELAY, params={
            "lmt": "1", "klt": "101", "secid": _secid(code6),
            "fields1": "f1,f2,f3,f7",
            "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65"},
            timeout=8, headers=UA_EAST, proxies=PROXIES)
        rows = _parse_east((r.json().get("data") or {}).get("klines"))
        return rows[-1] if rows else None
    except Exception:
        return None


def fetch_fflow(code6, days=60, east_merge=True):
    """个股逐日资金流（默认近 60 交易日，升序）。

    主源新浪历史序列；east_merge=True 时用东财当日 4 档明细覆盖/补齐当日行
    （盘中实时更有优势）。失败返回 []（调用方自然降级）。
    """
    c = str(code6).strip()
    c = c[2:] if c[:2].lower() in ("sh", "sz", "bj") else c
    if not c.isdigit():
        return []
    if c in _MEMO:
        return _MEMO[c]
    cache = _load_cache()
    if c in cache and cache[c]:
        _MEMO[c] = cache[c]
        return _MEMO[c]
    rows = _fetch_sina(c, days)[-days:] if days else _fetch_sina(c, 60)
    if rows and east_merge:
        et = _east_today_row(c)
        if et and et.get("date"):
            if rows[-1]["date"] == et["date"]:
                rows[-1] = et
            elif rows[-1]["date"] < et["date"]:
                rows.append(et)
    if rows:
        _MEMO[c] = rows
        cache[c] = rows
        _save_cache(cache)
    return rows


# ────────────────────────── v2.1 评分 ──────────────────────────

def validate_fund(row, prev_rows=None):
    """质量校验（v2.1 §2.2）：返回 issues 标记列表。

    注：设计文档的 END_SPIKE（尾盘30分钟占比）该日线接口不提供，未实现。
    """
    issues = []
    chg = row.get("chg") or 0.0
    main = row.get("main") or 0.0
    if chg > 1.0 and main < 0:
        issues.append("DIVERG_A")     # 价涨资金流出 → 诱多
    if chg < -1.0 and main > 0:
        issues.append("ACCUM_B")      # 价跌资金流入 → 吸筹（可接受）
    if main > 0 and row.get("jumbo") is not None:
        jr = row.get("jumbo_ratio")
        if jr is None:
            jr = (row.get("jumbo") or 0.0) / main if main else 0.0
        if jr < 0.4:
            issues.append("NOISE")    # 主力流入但靠中小单 → 不可靠
    return issues


def _ma(vals, n, i):
    if i + 1 < n:
        return None
    seg = vals[i + 1 - n:i + 1]
    return sum(seg) / n if seg else None


def trend_score(rows, snaps=None):
    """趋势分 0-6（v2.1 §3.1，权重倒挂 20D>10D>5D>1D）。"""
    if not rows:
        return 0.0
    def _sum(n):
        return sum(r.get("main") or 0.0 for r in rows[-n:])
    s5, s10, s20 = _sum(5), _sum(10), _sum(20)
    d1 = rows[-1].get("main") or 0.0
    score = 0.0
    if s20 > 0:
        score += 3
    if s10 > 0:
        score += 2
    if s5 > 0:
        score += 1
    if d1 > 0 and s5 > 0:
        score += 0.5
    # 价格位置惩罚（远离均线 = 追高风险）
    if snaps:
        closes = [float(x.get("close") or 0) for x in snaps]
        i = len(closes) - 1
        last = closes[-1] if closes else 0
        if last > 0:
            ma5, ma10 = _ma(closes, 5, i), _ma(closes, 10, i)
            if ma5 and (last / ma5 - 1) * 100 > 8:
                score -= 1
            if ma10 and (last / ma10 - 1) * 100 > 12:
                score -= 1
    return max(0.0, min(6.0, score))


def quality_score(rows, issues):
    """质量分 0-4（v2.1 §3.2）。"""
    if not rows:
        return 0.0
    row = rows[-1]
    main = row.get("main") or 0.0
    jr = row.get("jumbo_ratio")
    if jr is None:
        jr = ((row.get("jumbo") or 0.0) / main) if main > 0 else 0.0
    score = 0.0
    if jr >= 0.6:
        score += 2
    elif jr >= 0.4:
        score += 1
    accel = acceleration([r.get("main") or 0.0 for r in rows], 5)
    if accel > 0:
        score += 1
    if "NOISE" not in issues and "DIVERG_A" not in issues:
        score += 1
    return max(0.0, min(4.0, score))


def acceleration(main_series, n=5):
    """资金加速度（v2.1 §3.3）：今日流入 - 近 n 日均值；>0 = 加速。"""
    if not main_series:
        return 0.0
    seg = main_series[-n:]
    if not seg:
        return 0.0
    return main_series[-1] - (sum(seg) / len(seg))


def signal_of(total):
    """总分 → 信号（v2.1 §3.5）。"""
    if total >= 8:
        return "STRONG_BUY"
    if total >= 6:
        return "BUY"
    if total >= 4:
        return "WATCH"
    return "SKIP"


def score(code6, snaps=None, rows=None):
    """单票资金流评分。返回 dict（无数据返回 {}）。"""
    rows = rows if rows is not None else fetch_fflow(code6)
    if not rows:
        return {}
    def _sum(n):
        return sum(r.get("main") or 0.0 for r in rows[-n:])
    row = rows[-1]
    issues = validate_fund(row)
    t = trend_score(rows, snaps)
    q = quality_score(rows, issues)
    total = round(t + q, 2)
    main1 = row.get("main") or 0.0
    jr = (row.get("jumbo") or 0.0) / main1 if main1 > 0 else 0.0
    return {
        "code6": str(code6),
        "date": row.get("date"),
        "score": total,
        "trend": t,
        "quality": q,
        "signal": signal_of(total),
        "main_1d": round(main1, 3),
        "main_5d": round(_sum(5), 3),
        "main_10d": round(_sum(10), 3),
        "main_20d": round(_sum(20), 3),
        "jumbo_ratio": round(jr, 3),
        "accel_5d": round(acceleration([r.get("main") or 0.0 for r in rows], 5), 3),
        "main_pct": row.get("main_pct"),
        "chg": row.get("chg"),
        "issues": issues,
    }


def batch(codes, snaps_map=None, workers=8, limit=None):
    """并发批量评分：codes → [score_dict...]（按分数降序，无数据剔除）。"""
    seq = list(dict.fromkeys(str(c) for c in codes if c))
    if limit:
        seq = seq[:limit]
    out = []
    with ThreadPoolExecutor(max_workers=max(1, min(workers, len(seq) or 1))) as ex:
        futs = {ex.submit(score, c, (snaps_map or {}).get(c)): c for c in seq}
        for fu in futs:
            try:
                r = fu.result()
            except Exception:
                continue
            if r:
                out.append(r)
    out.sort(key=lambda x: -x["score"])
    return out


def _main():
    import argparse
    ap = argparse.ArgumentParser(description="个股资金流 v2.1 评分")
    ap.add_argument("codes", nargs="*", help="裸代码，如 300308 600188")
    ap.add_argument("--top", type=int, default=0, help="对 kline_store 全池评分取 TopN")
    args = ap.parse_args()

    targets = []
    if args.top:
        try:
            from _kline_store import load_store
            targets = [k for k in (load_store() or {}).keys()]
        except Exception as e:
            print("读取 kline_store 失败:", e)
            return
    targets += args.codes
    if not targets:
        ap.print_help()
        return
    res = batch(targets, snaps_map=None, limit=(len(targets) if not args.top else 0))
    show = res[:args.top] if args.top else res
    print("评分 %d 只（%s）:" % (len(res), "全池Top%d" % args.top if args.top else "指定"))
    for r in show:
        print("  %-8s %5.2f %-11s 1D%+7.2f亿 5D%+8.2f亿 10D%+8.2f亿 20D%+8.2f亿 "
              "超大单占比%.2f %s" % (r["code6"], r["score"], r["signal"], r["main_1d"],
                                  r["main_5d"], r["main_10d"], r["main_20d"],
                                  r["jumbo_ratio"], ",".join(r["issues"]) or "-"))


if __name__ == "__main__":
    _main()
