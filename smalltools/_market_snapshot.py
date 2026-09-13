# -*- coding: utf-8 -*-
"""全市场市值快照 + 板块分层清单生成器 —— 【低频任务：每周/换池时运行一次】。

数据源：东方财富 push2delay 行情列表（主 push2 域名常被断连，push2delay 更稳）。
- 全市场沪深 A（含创业板/科创板，排除北交所与 ST 标记需自行过滤）
- 每页 100 条，按总市值降序翻页（约 56 页 / 5500+ 只）

⚠️ 频率定位：市值与行业归属几天内几乎不变，每天翻 56 页全市场是浪费。
   每日轻量流程请用 _daily_active_pool.py（榜单 Top 发现，6 个 1 页请求）。
   本脚本只在以下时机运行：
     - 每周校准：板块分层清单 refresh
     - 需要重建小票池 / 轮动统计报告时（_small_pool.json 导出）
   典型用法：python _market_snapshot.py --skip-snapshot --export-small <out>

产出：
  1. StockAnalysis/data/_market_snapshot.json    -- 全市场当日快照
  2. StockAnalysis/data/_pool_layers.json        -- 板块分层清单
  3. StockAnalysis/data/_small_pool.json         -- 低价小市值分析池(--export-small)

用途：为轮动规律统计 / 牛熊统计提供"分析层股票池"候选；
选股 usecase pipeline 仍由 extra_filter(MAIN_BOARD) 独立过滤，不受本清单影响。
"""
import json
import os
import re
import sys
import time

import requests

UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Referer": "https://quote.eastmoney.com/",
}
PROXIES = {"http": None, "https": None}
HOST = "https://push2delay.eastmoney.com/api/qt/clist/get"
# 沪深 A：深主板 t:6 + 深创业 t:80 + 沪主板 m:1 t:2 + 沪科创 m:1 t:23
FS = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23"
FIELDS = "f2,f3,f8,f12,f14,f20,f21,f100"
PAGE_SIZE = 100
SNAPSHOT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "_market_snapshot.json")
LAYERS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "_pool_layers.json")

SECTOR_CODE_MAP = {
    "有色金属": "BK0478", "钢铁": "BK0470", "煤炭": "BK0421", "银行": "BK0475",
    "白酒": "BK1078", "半导体": "BK0447", "医药生物": "BK0465", "新能源汽车": "BK0811",
    "军工": "BK0460", "房地产": "BK0451", "化工": "BK0419", "电力": "BK0427",
    "证券": "BK0473", "保险": "BK0474", "汽车": "BK0481", "家电": "BK0436",
    "电子": "BK0448", "通信": "BK0480", "计算机": "BK0446", "传媒": "BK0420",
    "互联网": "BK0803", "农业": "BK0403", "建筑": "BK0414", "交通运输": "BK0437",
    "商业零售": "BK0467", "纺织服装": "BK0431", "旅游": "BK0463", "造纸": "BK0485",
    "机械": "BK0441", "光伏": "BK1026", "储能": "BK0816", "人工智能": "BK1064",
    "稀土": "BK1045", "氢能": "BK1041",
}


def http_get_json(url):
    r = requests.get(url, timeout=15, headers=UA, proxies=PROXIES)
    r.raise_for_status()
    return r.json()


def fetch_all_snapshot(progress=True):
    """翻页拉全市场快照。返回 dict[secid] = {name, price, chg_pct, turnover, mv_total, mv_float, industry}"""
    out = {}
    pn = 1
    total = None
    while True:
        params = {
            "pn": pn, "pz": PAGE_SIZE, "po": 1, "np": 1,
            "ut": "bd1d9ddb04089700cf9c27f6f7426281",
            "fltt": 2, "invt": 2, "fid": "f20", "fs": FS, "fields": FIELDS,
        }
        query = "&".join("%s=%s" % (k, v) for k, v in params.items())
        try:
            d = http_get_json(HOST + "?" + query)
        except Exception as e:
            print("  [warn] 第 %d 页失败: %s" % (pn, e))
            time.sleep(1.2)
            try:
                d = http_get_json(HOST + "?" + query)
            except Exception as e2:
                print("  [warn] 重试仍失败: %s" % e2)
                break
        data = d.get("data") or {}
        if total is None:
            total = data.get("total") or 0
        diff = data.get("diff") or []
        if not diff:
            break
        for it in diff:
            raw = str(it.get("f12") or "")
            if len(raw) != 6:
                continue

            def fnum(key, div=1.0):
                try:
                    v = it.get(key)
                    if v is None or str(v).strip() in ("", "-"):
                        return 0.0
                    return float(v) / div
                except (TypeError, ValueError):
                    return 0.0

            mv = fnum("f20", 1e8)  # 元→亿
            if mv < 1:
                continue  # 过滤停牌/异常
            secid = ("sh" if raw[0] in "69" else "sz") + raw
            ind = str(it.get("f100") or "")
            out[secid] = {
                "name": str(it.get("f14") or "").replace(" ", ""),
                "price": fnum("f2"),
                "chg_pct": fnum("f3"),
                "turnover": fnum("f8"),
                "mv_total_yi": mv,
                "mv_float_yi": fnum("f21", 1e8),
                "industry": ind,
            }
        if progress:
            print("  第 %d 页 / 共约 %d 只, 已累计 %d" % (pn, total or "?", len(out)))
        pn += 1
        if pn * PAGE_SIZE > (total or 0) + PAGE_SIZE:
            break
        time.sleep(0.25)
    return out


def board_of(secid):
    num = secid[2:]
    if num.startswith(("688", "689")):
        return "科创"
    if num.startswith(("300", "301")):
        return "创业"
    if num.startswith(("4", "8")):
        return "北交"
    return "主板"


def drop_st(snapshot):
    return {k: v for k, v in snapshot.items()
            if "*ST" not in v["name"] and "ST" not in v["name"] and "退" not in v["name"]}


def build_layers(snapshot, min_mv=20.0, max_count_per_sector=120):
    """按行业板块分组并分层。

    层级规则（分析层专用，不参与选股）：
      - 龙头层：行业市值前 3（且行业归属名匹配）
      - 大票层：按市值降序
      - 小票层：按市值升序（最后展示，用于识别低价小票轮动标的）
    返回 {date, sectors: {板块: {n, boards, leaders, large, small}}}
    """
    groups = {}
    for secid, v in snapshot.items():
        ind = v.get("industry") or "未分类"
        ind = re.sub(r"[ⅡⅢIV]+$", "", ind)  # 归一化 "银行Ⅱ"→银行
        groups.setdefault(ind, []).append((secid, v))
    sectors = {}
    for ind, items in groups.items():
        items.sort(key=lambda x: -x[1]["mv_total_yi"])
        large = [{"secid": s, **v} for s, v in items[:max_count_per_sector]]
        small = [{"secid": s, **v} for s, v in items[-30:]]
        boards = {}
        for s, v in items[:max_count_per_sector]:
            b = board_of(s)
            boards[b] = boards.get(b, 0) + 1
        sectors[ind] = {
            "n_total": len(items),
            "n_large": len(large),
            "boards": boards,
            "leaders": large[:3],
            "large": large,
            "small": small,
        }
    return sectors


def export_small_pool(snapshot, mv_min=20.0, mv_max=120.0, price_max=40.0, need_cache=True):
    """导出「低价小市值分析池」：20~120亿流通市值且现价<=40元。
    need_cache=True 时过滤掉已在核心池(_kline_cache.json)的股票，得到待补清单。
    供轮动规律统计 / 小票轮动实验使用（仅分析层，选股 pipeline 不自动放开）。
    """
    cache = {}
    if need_cache:
        cache_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
        if os.path.exists(cache_path):
            cache = json.load(open(cache_path, encoding="utf-8"))
    rows = []
    for secid, v in snapshot.items():
        mv = v["mv_total_yi"]
        if mv_min <= mv <= mv_max and v["price"] <= price_max and v["price"] > 0:
            if need_cache and secid in cache:
                continue
            rows.append({"secid": secid, "name": v["name"], "price": v["price"],
                         "mv_total_yi": round(mv, 1), "turnover": v["turnover"],
                         "industry": v["industry"], "board": board_of(secid)})
    rows.sort(key=lambda x: x["mv_total_yi"])
    return rows


def main():
    import argparse
    ap = argparse.ArgumentParser(description="全市场市值快照 + 板块分层清单")
    ap.add_argument("--pages", type=int, default=60, help="最多翻页数(默认60→6000只)")
    ap.add_argument("--skip-snapshot", action="store_true", help="复用已存在快照只重新分层")
    ap.add_argument("--export-small", metavar="JSON", help="导出低价小市值分析池到指定 json")
    args = ap.parse_args()

    os.makedirs(os.path.dirname(SNAPSHOT_FILE), exist_ok=True)
    snap = None
    if args.skip_snapshot and os.path.exists(SNAPSHOT_FILE):
        snap = json.load(open(SNAPSHOT_FILE, encoding="utf-8"))
        print("复用已存在快照: %d 只" % len(snap))
    if snap is None:
        print("开始抓取全市场快照...")
        snap = fetch_all_snapshot()
        json.dump(snap, open(SNAPSHOT_FILE, "w", encoding="utf-8"), ensure_ascii=False)
        print("快照落盘: %s (%d 只)" % (SNAPSHOT_FILE, len(snap)))

    snap = drop_st(snap)
    if args.export_small:
        rows = export_small_pool(snap)
        with open(args.export_small, "w", encoding="utf-8") as f:
            json.dump({"date": time.strftime("%Y-%m-%d"), "pool": rows}, f, ensure_ascii=False, indent=1)
        print("低价小市值分析池: %d 只 -> %s" % (len(rows), args.export_small))
        from collections import Counter
        print("  板块分布(创业板/科创板): %s" % dict(Counter(r["board"] for r in rows)))
        inds = Counter(r["industry"] for r in rows).most_common(10)
        print("  行业Top10:", ", ".join("%s%d" % (i, n) for i, n in inds))

    layers = build_layers(snap)
    today = time.strftime("%Y-%m-%d")
    out = {"date": today, "source": "push2delay.eastmoney.com clist", "sectors": layers}
    json.dump(out, open(LAYERS_FILE, "w", encoding="utf-8"), ensure_ascii=False)

    print("\n板块分层清单落盘: %s" % LAYERS_FILE)
    print("板块数: %d, 去 ST 后股票数: %d" % (len(layers), len(snap)))
    for ind, v in sorted(layers.items(), key=lambda x: -x[1]["n_total"])[:12]:
        b = v["boards"]
        print("  %-8s 总数%-4d 主板%d/创业%d/科创%d 龙头:%s"
              % (ind, v["n_total"], b.get("主板", 0), b.get("创业", 0), b.get("科创", 0),
                 ",".join(i["name"] for i in v["leaders"][:2])))


if __name__ == "__main__":
    main()
