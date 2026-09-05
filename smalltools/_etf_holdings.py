# -*- coding: utf-8 -*-
"""核心指数 ETF 重仓股跟踪 → 「低位埋伏」候选池（原型）。

背景（2026-09-06 决策）：
  - 行业 ETF 前十大重仓 ≈ 板块权重锚/机构共识核心，与「行业→龙头图谱」交叉验证
  - 数据源：东财基金移动端 F10（FundMNInverstPosition）实测可用（fundf10 HTML 版 404）
  - 行情源：腾讯 qt.gtimg 批量行情（东财 push2 直连被断）+ _overseas_fetch.fetch_kline 日K
  - 披露口径：ETF 前十大重仓为「最新披露(季报级)」，低频更新；盘中行情/位置实时算

产出：data/_etf_holdings.json
  {
    "updated": "2026-09-06 15:20",
    "report_note": "前十大重仓为最新定期披露(季报级)口径",
    "funds": [{"code": "512480", "name": "半导体ETF", "theme": "半导体",
               "top": [{"code":"603986","name":"兆易创新","ratio":7.82,
                        "chg_type":"增持","chg_pct":2.74}]}],
    "stocks": { "603986": {"name":"兆易创新", "n":2, "funds":["512480","159995"],
                            "sum_ratio": 12.3, "pos60": -14.2, "last_close": 123.4}}
  }

用法：
  python _etf_holdings.py                  # 全量更新（拉重仓 + 逐股算60日位置，约1分钟）
  python _etf_holdings.py --no-kline       # 仅刷新重仓（快速）
  python _etf_holdings.py --low-buy 半导体,券商   # 读缓存 + 实时行情 → 低吸观察清单

模块内调用（供 _publish_candidates 每轮推送使用）：
  low_buy_lines(hot_themes, etf_flow) -> [str]
"""
import argparse
import json
import os
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(os.path.dirname(HERE), "data")
OUT_FILE = os.path.join(DATA_DIR, "_etf_holdings.json")

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
MOBILE_UA = {"User-Agent": "Mozilla/5.0 (Linux; Android 12; Pixel) AppleWebKit/537.36"}
PROXIES = {"http": None, "https": None}

FUNDMOB_API = "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNInverstPosition"
QUOTE_API = "https://qt.gtimg.cn/q="

# ── 宽基（进覆盖矩阵，但行业低吸推送不参与）──
BASE_FUNDS = [
    ("510050", "上证50ETF"), ("510300", "沪深300ETF"),
    ("159915", "创业板ETF"), ("588000", "科创50ETF"),
]

# ── 行业主题规则：(关键词元组) -> [(基金代码, 简称), ...] ──
# 关键词对齐东财行业板块榜名（fetch_board_flow 的 key），板块名包含任一关键词即命中
THEME_RULES = [
    (("半导体", "芯片", "集成电路"), [("512480", "半导体ETF"), ("159995", "芯片ETF")]),
    (("证券", "券商"), [("512880", "证券ETF")]),
    (("银行",), [("512800", "银行ETF")]),
    (("白酒", "饮料乳品", "食品饮料"), [("512690", "酒ETF")]),
    (("化学制药", "生物制品"), [("159992", "创新药ETF")]),
    (("医疗服务", "医疗器械", "中药", "医药商业", "化学制药", "生物制品"), [("512170", "医疗ETF"), ("512010", "医药ETF")]),
    (("光伏设备", "光伏"), [("515790", "光伏ETF")]),
    (("电池", "能源金属"), [("159755", "电池ETF")]),
    (("航天航空", "船舶制造", "地面兵装", "航空装备"), [("512660", "军工ETF")]),
    (("工业金属", "小金属", "能源金属", "有色金属", "贵金属"), [("512400", "有色金属ETF")]),
    (("煤炭行业", "煤炭"), [("515220", "煤炭ETF")]),
    (("房地产开发", "房地产服务", "房地产"), [("512200", "房地产ETF")]),
    (("通信设备", "通信服务"), [("515880", "通信ETF")]),
    (("软件开发", "计算机设备", "IT服务", "计算机"), [("512720", "计算机ETF")]),
    (("游戏",), [("516010", "游戏ETF")]),
    (("文化传媒", "广告营销", "传媒"), [("512980", "传媒ETF")]),
    (("白色家电", "家用电器"), [("159996", "家电ETF")]),
    (("汽车整车", "汽车零部件", "汽车服务"), [("516110", "汽车ETF")]),
    (("人工智能", "AI", "机器人"), [("515070", "人工智能ETF"), ("562500", "机器人ETF")]),
    (("互联网服务", "软件开发", "人工智能"), [("513050", "中概互联网ETF")]),
]

ALL_FUNDS = [(c, n) for _, lst in THEME_RULES for (c, n) in lst] + BASE_FUNDS


# ══════════════════════════════════════════
# 数据抓取
# ══════════════════════════════════════════

def _norm6(code):
    code = str(code).strip()
    if code[:2].lower() in ("sh", "sz", "bj"):
        return code[2:]
    return code


def _prefixed(code):
    code = _norm6(code)
    if code.startswith(("6", "9")):
        return "sh" + code
    if code.startswith(("0", "2", "3")):
        return "sz" + code
    return "bj" + code


def fetch_holdings(fcode, retries=3):
    """东财基金移动端 F10：前十大重仓。返回 [{code,name,ratio,chg_type,chg_pct}]"""
    params = {"FCODE": fcode, "deviceid": "Wap", "plat": "Wap",
              "product": "EFund", "version": "6.2.8",
              "pageIndex": "1", "pageSize": "15"}
    d = None
    for attempt in range(retries):
        try:
            r = requests.get(FUNDMOB_API, params=params, timeout=20,
                             headers=MOBILE_UA, proxies=PROXIES)
            d = r.json()
            break
        except Exception as e:
            if attempt == retries - 1:
                raise
            time.sleep(1.0 + attempt)
    data = (d or {}).get("Datas") or {}
    fs = data.get("fundStocks") or []
    out = []
    for s in fs:
        code = _norm6(s.get("GPDM") or "")
        name = s.get("GPJC") or ""
        if not code or not name:
            continue
        out.append({
            "code": code,
            "name": name,
            "ratio": float(s.get("JZBL") or 0),           # 占净值比%
            "chg_type": s.get("PCTNVCHGTYPE") or "",       # 增减持
            "chg_pct": float(s.get("PCTNVCHG") or 0),      # 季度变动pp
        })
    return out


def fetch_kline_pos(code, count=65):
    """腾讯日K → 距60日高点回撤%(负值=低于高点)。复用 _overseas_fetch.fetch_kline"""
    try:
        import _overseas_fetch
        rows = _overseas_fetch.fetch_kline(_prefixed(code), count=count)
        if len(rows) < 5:
            return None
        closes = [x["close"] for x in rows]
        high = max(closes)
        last = closes[-1]
        if high <= 0:
            return None
        return round((last / high - 1) * 100, 1)
    except Exception:
        return None


def tencent_quotes(codes):
    """腾讯批量行情 → {code: {price,pct,high52,low52,name}}（code 为6位数字）"""
    items = [(c, _prefixed(c)) for c in dict.fromkeys(_norm6(x) for x in codes)]
    if not items:
        return {}
    url = QUOTE_API + ",".join(p for _, p in items)
    try:
        r = requests.get(url, timeout=8, headers=HEADERS, proxies=PROXIES)
        r.encoding = "gbk"
    except Exception:
        return {}
    out = {}
    for seg in r.text.split(";"):
        seg = seg.strip()
        if not seg or "=" not in seg:
            continue
        pref, _, body = seg.partition("=")
        body = body.strip().strip('"')
        f = body.split("~")
        if len(f) < 49 or not body:
            continue
        try:
            # 腾讯行形如 v_sh600519="..." → 去掉 v_ 前缀取市场+代码
            key = pref[2:] if pref.startswith("v_") else pref
            code6 = key[2:] if key[:2] in ("sh", "sz", "bj") else _norm6(key)
        except Exception:
            continue
        try:
            out[code6] = {
                "name": f[1],
                "price": float(f[3]),
                "pct": float(f[32]),
                "high52": float(f[47]),
                "low52": float(f[48]),
            }
        except (ValueError, IndexError):
            continue
    return out


# ══════════════════════════════════════════
# 更新落盘
# ══════════════════════════════════════════

def update(with_kline=True):
    import datetime
    os.makedirs(DATA_DIR, exist_ok=True)
    funds = []
    stock_map = {}
    for fcode, fname in ALL_FUNDS:
        try:
            top = fetch_holdings(fcode)
        except Exception as e:
            print("  [warn] %s %s 拉取失败: %s" % (fcode, fname, e))
            continue
        if not top:
            print("  [warn] %s %s 无重仓数据" % (fcode, fname))
            continue
        # 主题名（首条命中规则）
        theme = ""
        for kws, lst in THEME_RULES:
            if any(fcode == c for c, _ in lst):
                theme = kws[0]
                break
        if not theme and any(fcode == c for c, _ in BASE_FUNDS):
            theme = "宽基"
        funds.append({"code": fcode, "name": fname, "theme": theme, "top": top})
        for s in top:
            sc = s["code"]
            ent = stock_map.setdefault(sc, {"name": s["name"], "funds": [], "sum_ratio": 0.0})
            if fcode not in ent["funds"]:
                ent["funds"].append(fcode)
                ent["sum_ratio"] = round(ent["sum_ratio"] + s["ratio"], 1)
        print("  %s %s 重仓 %d 只 (累计 %d 只股票)" % (fcode, fname, len(top), len(stock_map)))
        time.sleep(0.15)

    # 60日位置（低频，可跳过）
    if with_kline:
        codes = list(stock_map.keys())
        for i, sc in enumerate(codes):
            pos = fetch_kline_pos(sc)
            if pos is not None:
                stock_map[sc]["pos60"] = pos
            if i % 20 == 19:
                print("  位置进度 %d/%d" % (i + 1, len(codes)))
    for ent in stock_map.values():
        ent["n"] = len(ent["funds"])

    out = {
        "updated": datetime.datetime.now().strftime("%Y-%m-%d %H:%M"),
        "report_note": "前十大重仓为最新定期披露(季报级)口径，fundmob F10",
        "funds": funds,
        "stocks": stock_map,
    }
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("✓ 已落盘 %s (基金%d只 股票%d只)" % (OUT_FILE, len(funds), len(stock_map)))
    return out


# ══════════════════════════════════════════
# 低吸观察（供推送 / --low-buy 使用）
# ══════════════════════════════════════════

def load_holdings():
    if not os.path.exists(OUT_FILE):
        return None
    try:
        with open(OUT_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def match_funds_by_theme(hot_themes):
    """热门板块名(东财行业名) → [(fcode,fname,theme)]（主题关键词命中）"""
    hit = []
    for theme_name in hot_themes or []:
        theme_name = str(theme_name or "").strip()
        if not theme_name:
            continue
        for kws, lst in THEME_RULES:
            if any(kw in theme_name for kw in kws):
                for item in lst:
                    hit.append((item[0], item[1], kws[0]))
    # 去重保序
    seen, out = set(), []
    for item in hit:
        if item[0] not in seen:
            seen.add(item[0])
            out.append(item)
    return out


def low_buy_lines(hot_themes, etf_flow=None):
    """生成「热门板块 → ETF 前5重仓 → 低吸观察」文本行（供每轮推送 ⑥ 段）。

    hot_themes: 当日资金流入榜前N的东财行业名列表
    etf_flow: ctx["etf_flow"]（东财ETF净流入榜 [{name,in_yi,chg_pct}]，用于标注资金）
    返回 markdown 行列表；任何数据缺失时安静返回 []。
    """
    hold = load_holdings()
    if not hold:
        return []
    theme_funds = match_funds_by_theme(hot_themes)
    if not theme_funds:
        return []
    top_by_fund = {f["code"]: f["top"] for f in hold.get("funds") or []}
    name_by_fund = {f["code"]: f["name"] for f in hold.get("funds") or []}
    stocks = hold.get("stocks") or {}

    # 候选股票：命中主题 ETF 的前5重仓
    cand_codes = []
    for fcode, _, _ in theme_funds:
        top = (top_by_fund.get(fcode) or [])[:5]
        for s in top:
            if s["code"] not in cand_codes:
                cand_codes.append(s["code"])
    quotes = tencent_quotes(cand_codes)
    if not quotes:
        return []

    # ETF 当日资金净流入（用于标注，名称含 ETF 主题词或简称）
    flow_in = {}
    for e in etf_flow or []:
        nm = str(e.get("name") or "")
        yi = float(e.get("in_yi") or 0)
        if yi > 0:
            flow_in[nm] = yi

    lines = []
    done_funds = set()
    for fcode, fname, theme in theme_funds:
        if fcode in done_funds:
            continue
        done_funds.add(fcode)
        top = (top_by_fund.get(fcode) or [])[:5]
        picks = []
        for s in top:
            q = quotes.get(s["code"])
            ent = stocks.get(s["code"])
            if not q:
                continue
            pct = q["pct"]
            # 低吸过滤：当日温吞（不追高、不接暴跌中段），且已低于 60 日高点
            if not (-4.0 <= pct <= 4.0):
                continue
            pos60 = (ent or {}).get("pos60")
            if pos60 is None or pos60 > -1.0:
                continue
            picks.append("%s(距60日高%+.1f%% %+.1f%%)" % (s["name"], pos60, pct))
        if not picks:
            continue
        # 资金标注：ETF 当日净流入（东财 ETF 榜简称含主题词/基金名）
        fz = ""
        short = fname.replace("ETF", "")
        for fn, yi in flow_in.items():
            if (theme and theme in fn) or short in fn or fname in fn:
                fz = " 资金入%+.1f亿" % yi
                break
        lines.append("  %s(%s)%s:" % (fname, fcode, fz))
        lines.append("    " + " | ".join(picks[:3]))
    return lines


def summary_json(limit=15):
    """轻量摘要（注入 candidates json 供 App 端展示）：按「被基金覆盖数 n 降序 + 回撤深优先」取 top"""
    hold = load_holdings()
    if not hold:
        return None
    stocks = hold.get("stocks") or {}
    rows = []
    for code, ent in stocks.items():
        rows.append({
            "code": code, "name": ent.get("name"), "n": ent.get("n", 1),
            "funds": ent.get("funds"), "sum_ratio": ent.get("sum_ratio"),
            "pos60": ent.get("pos60"),
        })
    rows.sort(key=lambda x: (-x["n"], x["pos60"] if x["pos60"] is not None else 0))
    return {
        "updated": hold.get("updated"), "note": hold.get("report_note"),
        "fund_count": len(hold.get("funds") or []), "stock_count": len(stocks),
        "top": rows[:limit],
    }


# ══════════════════════════════════════════
# CLI
# ══════════════════════════════════════════

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-kline", action="store_true", help="仅刷新重仓(跳过60日位置)")
    ap.add_argument("--low-buy", type=str, default="", help="读缓存+实时行情输出低吸观察, 如 '半导体,券商'")
    args = ap.parse_args()
    if args.low_buy:
        themes = [x.strip() for x in args.low_buy.split(",") if x.strip()]
        lines = low_buy_lines(themes, etf_flow=None)
        print("🎯 低吸观察（热门: %s）" % "、".join(themes))
        print("\n".join(lines) if lines else "  (无命中或无缓存数据，先跑 python _etf_holdings.py 更新)")
        return 0
    print("更新核心 ETF 重仓池...")
    update(with_kline=not args.no_kline)
    return 0


if __name__ == "__main__":
    sys.exit(main())
