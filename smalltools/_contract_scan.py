# -*- coding: utf-8 -*-
"""个股重大合同/负债扫描（2026-09-15 用户需求：通过合同分析前景，
同板块横比、按市值归一——「合同额/总市值」比绝对金额更有意义）。

数据链路（2026-09-15 已实测打通）：
  1) 东财公告列表 np-anotice-stock（近 N 天标题 + art_code，每只 1 请求）
  2) 正文 np-cnotice-stock/api/content/ann?art_code= → notice_content 纯文本
     （正文按 art_code 永久缓存 data/_contract_cache.json，不重复拉）
  3) 正则提取金额（亿/万美元/港元/万元/元）→ 归一为亿元人民币

分类口径（标题+正文关键词）：
  订（订单/合同，正向）：合同|中标|订单|框架协议|预中标|联合体|签订|中标候选人
  借（借款/负债，负向）：借款|贷款|公司债|可转债|中期票据|短期融资券|融资租赁|发债

cell 口径（推送表格「合同/市值」列）：
  订+12%    近90天订单类合同额合计 ≈ 总市值 12%（越大越有催化）
  借3%      近90天借款/发债额 ≈ 市值 3%
  订12%借3% 双侧都有 ｜ — 无相关公告

用法：
  CLI：python _contract_scan.py 600176 600150 ...
  集成：import _contract_scan; _contract_scan.scan(["600176"]) -> {code: cell}
"""
import datetime
import json
import os
import re
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_FILE = os.path.join(HERE, "data", "_contract_cache.json")
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
      "Referer": "https://finance.eastmoney.com/"}
PX = {"http": None, "https": None}
DAYS = 90

ORDER_KW = re.compile(r"合同|中标|订单|框架协议|预中标|联合体|签订|中标候选人")
DEBT_KW = re.compile(r"借款|贷款|公司债|可转债|中期票据|短期融资券|融资租赁|发债|债券")

_AMT_PATS = [
    (re.compile(r"([\d,]+(?:\.\d+)?)\s*亿美元"), 7.2),
    (re.compile(r"([\d,]+(?:\.\d+)?)\s*亿美元等值"), 7.2),
    (re.compile(r"([\d,]+(?:\.\d+)?)\s*亿港元"), 0.92),
    (re.compile(r"([\d,]+(?:\.\d+)?)\s*亿欧元"), 7.8),
    (re.compile(r"([\d,]+(?:\.\d+)?)\s*亿元人民币"), 1.0),
    (re.compile(r"([\d,]+(?:\.\d+)?)\s*亿元"), 1.0),
    (re.compile(r"([\d,]+(?:\.\d+)?)\s*万港元"), 0.92e-4),
    (re.compile(r"([\d,]+(?:\.\d+)?)\s*万美元"), 7.2e-4),
    (re.compile(r"([\d,]+(?:\.\d+)?)\s*万元"), 1e-4),
    (re.compile(r"([\d,]+(?:\.\d+)?)\s*元"), 1e-8),
]
_SESS = requests.Session()


def _load_cache():
    try:
        with open(CACHE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _save_cache(c):
    try:
        tmp = CACHE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(c, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, CACHE_FILE)
    except OSError:
        pass


def _get(url, params, timeout=10):
    for _ in range(2):
        try:
            r = _SESS.get(url, params=params, timeout=timeout,
                          headers=UA, proxies=PX)
            if r.status_code == 200:
                return r.json()
        except Exception:
            time.sleep(0.4)
    return None


def list_anns(code, days=DAYS):
    """近 days 天公告列表 → [(date, title, art_code)]。"""
    j = _get("https://np-anotice-stock.eastmoney.com/api/security/ann",
             {"sr": "-1", "page_size": "50", "page_index": "1",
              "ann_type": "A", "client_source": "web", "stock_list": code})
    out = []
    try:
        for it in (j.get("data") or {}).get("list") or []:
            d = (it.get("notice_date") or "")[:10]
            try:
                if (datetime.date.today()
                        - datetime.date.fromisoformat(d)).days > days:
                    continue
            except ValueError:
                continue
            out.append((d, it.get("title") or "", it.get("art_code") or ""))
    except AttributeError:
        pass
    return out


def content(art_code):
    j = _get("https://np-cnotice-stock.eastmoney.com/api/content/ann",
             {"art_code": art_code, "client_source": "web", "page_index": 1})
    try:
        return (j.get("data") or {}).get("notice_content") or ""
    except AttributeError:
        return ""


def extract_amount_yi(text):
    """正文中最大金额（亿元）。框架协议无金额 → None。"""
    best = 0.0
    for pat, k in _AMT_PATS:
        for m in pat.finditer(text):
            try:
                v = float(m.group(1).replace(",", "")) * k
            except ValueError:
                continue
            if v > best:
                best = v
    # 单位为「元」的误匹配（如证券代码 600176 元）通常是噪声：亿元以下忽略
    return best if best >= 0.05 else None


def scan_one(code, cache, mcap_yi):
    """单只 → (cell, detail)。cache 会就地追加新 art_code 结果。"""
    order_yi = debt_yi = 0.0
    n_hit = 0
    for date, title, art in list_anns(code):
        if not art or not (ORDER_KW.search(title) or DEBT_KW.search(title)):
            continue
        kind = "debt" if DEBT_KW.search(title) and not ORDER_KW.search(title) \
            else "order"
        ent = cache.get(art)
        if ent is None:
            amt = extract_amount_yi(content(art))
            ent = {"amt_yi": amt, "kind": kind, "title": title[:40],
                   "date": date, "code": code}
            cache[art] = ent
            time.sleep(0.12)
        n_hit += 1
        if ent.get("amt_yi"):
            if ent.get("kind") == "debt":
                debt_yi += ent["amt_yi"]
            else:
                order_yi += ent["amt_yi"]
    cell = "—"
    if order_yi <= 0 and debt_yi <= 0:
        return cell, {"code": code, "hits": n_hit}
    o = ("订%g%%" % round(order_yi / mcap_yi * 100, 1)) if (order_yi > 0 and mcap_yi) else ""
    d = ("借%g%%" % round(debt_yi / mcap_yi * 100, 1)) if (debt_yi > 0 and mcap_yi) else ""
    if not o and not d:                      # 无市值兜底：给绝对额
        o = "订%.0f亿" % order_yi if order_yi > 0 else ""
        d = "借%.0f亿" % debt_yi if debt_yi > 0 else ""
    cell = (o + d) if (o and d) else (o or d)
    return cell, {"code": code, "hits": n_hit, "order_yi": round(order_yi, 2),
                  "debt_yi": round(debt_yi, 2), "mcap": mcap_yi}


def _mcaps(codes):
    """腾讯实时批量取总市值（亿）。"""
    out = {}
    import re as _re
    for i in range(0, len(codes), 30):
        batch = []
        for c in codes:
            p = "sh" if c.startswith("6") else ("bj" if c.startswith(("4", "8", "9")) else "sz")
            batch.append(p + c)
        try:
            r = _SESS.get("https://qt.gtimg.cn/q=" + ",".join(batch),
                          timeout=10, headers=UA, proxies=PX)
            r.encoding = "gbk"
        except Exception:
            continue
        for seg in r.text.strip().split(";"):
            m = _re.search(r'v_(\w+)="([^"]*)"', seg)
            if not m:
                continue
            f = m.group(2).split("~")
            if len(f) > 45:
                try:
                    out[m.group(1)[2:]] = float(f[45])
                except (TypeError, ValueError):
                    pass
        time.sleep(0.1)
    return out


def scan(codes, mcaps=None, days=DAYS):
    """codes（6 位代码列表）→ {code: cell}；正文缓存增量落盘。"""
    codes = [c for c in dict.fromkeys(codes) if c]
    if not codes:
        return {}
    global DAYS
    DAYS = days
    cache = _load_cache()
    mc = mcaps or _mcaps(codes)
    out = {}
    for c in codes:
        try:
            out[c], _ = scan_one(c, cache, mc.get(c) or 0)
        except Exception as e:  # noqa: BLE001
            print("  合同扫描失败 %s: %s" % (c, e))
            out[c] = "—"
    _save_cache(cache)
    return out


if __name__ == "__main__":
    import sys
    cs = sys.argv[1:]
    if not cs:
        cs = ["600150", "600176"]
    print(scan(cs))
