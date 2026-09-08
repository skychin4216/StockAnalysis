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
  low_buy_lines(hot_themes, etf_flow=None, dag_codes=None, max_rows=5) -> [str]
    # 低吸精选：热门+重点关注板块 ETF 前五 → SAR红(绿排除/绿转红✓) →
    #           企稳/量能/MACD/OBV共振打分 → 分组渲染 ≤max_rows 只
  low_buy_lines_offline(asof, hist=None, dag_codes=None, max_rows=5) -> [str]
    # 离线(收盘K线/回放)全行业同口径
  screen_picks_asof(asof, hist=None, industry_only=True, dag_codes=None)
    # 低吸候选明细(含 _screen_meta 的 SAR/企稳/MACD/OBV/均线字段)，供回溯拟合复用
"""
import argparse
import datetime as _dt
import json
import os
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(os.path.dirname(HERE), "data")
OUT_FILE = os.path.join(DATA_DIR, "_etf_holdings.json")
MARKET_DB_PATH = os.path.join(DATA_DIR, "market_data.db")
# 行业ETF前五重仓的全史日K（供逐日回放/回溯拟合离线评估；db 优先、腾讯补缺）
TOP5_HIST_FILE = os.path.join(DATA_DIR, "_etf_top5_hist.json")

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


# ── 用户重点关注板块（来自 APK「量化选股 → AI 分析」输入，经云同步落地）──
FOCUS_FILE = os.path.join(DATA_DIR, "user_focus_sectors.json")


def load_focus_sectors(path=None):
    """读取用户重点关注板块名列表（板块已归一化，可与 THEME_RULES/热门板块关键词匹配）。

    文件 schema（APK 端 CloudSync 上传）：{"asof": "...", "sectors": ["半导体", ...],
    "stocks": [{"code","name","sector"}, ...]}。个股未落到板块名时按 sector 去重兜底。
    文件缺失/为空 → []。
    """
    p = path or FOCUS_FILE
    if not os.path.exists(p):
        return []
    try:
        with open(p, encoding="utf-8") as f:
            d = json.load(f)
    except (OSError, ValueError):
        return []
    names = [str(x).strip() for x in (d.get("sectors") or []) if str(x).strip()]
    # 兜底：只有个股时按其所属板块名聚合（名称去重、保序）
    if not names:
        for s in d.get("stocks") or []:
            sec = str(s.get("sector") or "").strip()
            if sec and sec not in names:
                names.append(sec)
    return names


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


# ══════════════════════════════════════════
# 离线评估基元（回放 / 回溯拟合共用；口径与 _kline_cache.json 一致）
# ══════════════════════════════════════════

def _slice_by_date(snaps, asof):
    return [s for s in snaps if s["date"] <= asof]


def _pos60(snaps):
    """距60日收盘高点的回撤%（负=低于高点）；样本不足返回 None。与线上 pos60 同口径。"""
    if len(snaps) < 21:
        return None
    closes = [float(s.get("close") or 0) for s in snaps[-60:]]
    hi = max(closes)
    if hi <= 0:
        return None
    return round((closes[-1] / hi - 1) * 100, 1)


def _day_pct(snaps):
    """末根 vs 前一根 的涨跌幅%（收盘口径）。"""
    if len(snaps) < 2:
        return None
    c0 = float(snaps[-2].get("close") or 0)
    c1 = float(snaps[-1].get("close") or 0)
    if c0 <= 0:
        return None
    return round((c1 / c0 - 1) * 100, 1)


def _ema(vals, n):
    """EMA(n) 序列（首值=首个样本，与行情软件近似口径）。"""
    k = 2.0 / (n + 1)
    out, e = [], None
    for v in vals:
        e = v if e is None else v * k + e * (1 - k)
        out.append(e)
    return out


def _ind_tag(snaps):
    """snaps → 紧凑指标串（RSI / SAR / MACD柱趋势 / OBV），样本不足返回 ''。
    与 _technicals 同源；MACD 用连续两日柱体刻画『收窄/扩大』。"""
    try:
        import _technicals as T
        s = T.analyze(snaps)
        if not s:
            return ""
        p = []
        rsi = s.get("rsi")
        if rsi is not None:
            p.append("RSI%d" % int(rsi))
        sar = s.get("sar") or {}
        d, bars = sar.get("dir"), sar.get("bars") or 0
        fd, fa = sar.get("flip_dir"), sar.get("flip_ago")
        if d == "UP":
            p.append("SAR刚翻红" if (fd == "UP" and fa and fa <= 3) else "SAR红↑%d" % bars)
        elif d == "DOWN":
            p.append("SAR刚翻绿%d天" % (fa or 0)
                     if (fd == "DOWN" and fa and fa <= 3) else "SAR绿↓%d" % bars)
        mc = s.get("macd") or {}
        if mc.get("cross") == "gold":
            p.append("MACD金叉")
        elif mc.get("cross") == "dead":
            p.append("MACD死叉")
        else:
            hist = mc.get("hist")
            if hist is not None and len(p) < 4:
                try:
                    closes = [float(x.get("close") or 0) for x in snaps]
                    if len(closes) >= 2:
                        dif = _ema(closes, 12)
                        dea = _ema(dif, 9)
                        prev = dif[-2] - dea[-2]
                        color = "红" if hist > 0 else "绿"
                        trend = "收窄" if abs(hist) < abs(prev) else "扩大"
                        p.append("MACD%s柱%s" % (color, trend))
                except Exception:
                    p.append("MACD%s柱" % ("红" if hist > 0 else "绿"))
        obv = s.get("obv_up")
        if obv is True and len(p) < 5:
            p.append("OBV上行")
        elif obv is False and len(p) < 5:
            p.append("OBV下行")
        return " ".join(p[:5])
    except Exception:
        return ""


def _offline_confirm(snaps):
    """对已截断到 asof 的 snaps：末根是否放量企稳。样本不足返回 ''。"""
    return _confirm_note_from_snaps(snaps)


def _confirm_note_from_snaps(snaps):
    """snaps(收盘口径,≥260根启用企稳判定) → 『当日放量企稳✓ / 待企稳(预计N日) / 未企稳·勿接飞刀』。"""
    if len(snaps) < 260:
        return ""
    try:
        import _volume_confirm as vc
        i = len(snaps) - 1
        ind = vc.indicators(snaps)
        if vc.is_confirm(snaps, i, ind=ind):
            return "当日放量企稳✓"
        j = vc.first_confirm(snaps, i, look=5, ind=ind)
        if j >= 0:
            return "待企稳(预计%d日)" % (j - i)
        return "未企稳·勿接飞刀"
    except Exception:
        return ""


def _screen_meta(snaps):
    """snaps → 低吸精选判定明细（SAR方向/绿转红、企稳note、MACD/OBV、站上均线、三日不新低、
    量比等）。离线切片与实时日K共用同一口径。任意异常静默降级为缺字段。"""
    out = {}
    n = len(snaps)
    if n < 2:
        return out
    try:
        closes = [float(x.get("close") or 0) for x in snaps]
        c = closes[-1]
        def _ma(k):
            return (sum(closes[-k:]) / k) if n >= k else None
        ma5, ma10 = _ma(5), _ma(10)
        out["above5"] = bool(ma5 is not None and c > ma5)
        out["above10"] = bool(ma10 is not None and c > ma10)
        last = snaps[-1]
        o = float(last.get("open") or c)
        out["up_day"] = c > o
        if n >= 4:
            prev_low = float(snaps[-4].get("low") or 0)
            out["three_no_low"] = all(float(x.get("low") or 0) >= prev_low
                                      for x in snaps[-3:])
        vols = [float(x.get("volume") or 0) for x in snaps]
        v5 = sum(vols[-6:-1]) / max(len(vols[-6:-1]), 1)
        out["vol_ratio"] = round(vols[-1] / v5, 2) if v5 > 0 else 1.0
    except Exception:
        pass
    try:
        import _technicals as T
        s = T.analyze(snaps) or {}
        sar = s.get("sar") or {}
        out["sar_dir"] = sar.get("dir")
        out["sar_bars"] = sar.get("bars") or 0
        # 绿转红=当前红(dir UP)且前一段为绿(flip_dir DOWN)、翻转≤3日
        out["fresh_up"] = bool(sar.get("dir") == "UP"
                               and sar.get("flip_dir") == "DOWN"
                               and (sar.get("flip_ago") or 99) <= 3)
        mc = s.get("macd") or {}
        out["macd_golden"] = mc.get("cross") == "gold"
        out["macd_hist"] = mc.get("hist")
        out["obv_up"] = s.get("obv_up")
        out["rsi"] = s.get("rsi")
    except Exception:
        pass
    # 豆包×DeepSeek 低位因子（可回溯口径）：52周位置分位、MACD红柱/DIF上行、温和放量
    try:
        if n >= 250:
            w = snaps[-252:]
            wh = max(float(x.get("high") or 0) for x in w)
            wl = min(float(x.get("low") or 0) for x in w)
            if wh > wl > 0:
                out["pos52"] = round((c - wl) / (wh - wl), 3)
        dif = _ema(closes, 12)
        dea = _ema(dif, 9)
        if len(dif) >= 2 and len(dea) >= 2:
            out["macd_bull"] = bool(dif[-1] >= dea[-1])
            out["dif_up"] = bool(dif[-1] >= dif[-2])
        vr = out.get("vol_ratio")
        out["vr_ok"] = bool(vr is not None and 1.05 <= vr <= 2.5)
    except Exception:
        pass
    out["note"] = _confirm_note_from_snaps(snaps)
    out["tag"] = _ind_tag(snaps) if n >= 30 else ""
    return out


# 低吸精选门槛（2026-09-06 方案B：前五→SAR红→企稳/量能/MACD/OBV共振→最多5只）
_DECIDE_MIN_SCORE = 5.0


def _decide_pick(pk):
    """对单个候选做『符合低吸精选』判定。
    pk 需含 _screen_meta 各字段 + pos60/pct/dag_hit。
    返回 (ok, score)。SAR绿(下跌趋势)直接排除。"""
    if pk.get("sar_dir") != "UP":
        return False, 0.0
    note = pk.get("note") or ""
    stable = note.startswith("当日放量企稳")
    wait_stable = note.startswith("待企稳")
    fresh = bool(pk.get("fresh_up"))
    gold = bool(pk.get("macd_golden"))
    hist = pk.get("macd_hist")
    obv = pk.get("obv_up")
    rsi = pk.get("rsi")
    dag = bool(pk.get("dag_hit"))
    score = 0.0
    if stable:
        score += 3.0
    elif wait_stable:
        score += 0.5
    if fresh:
        score += 2.0
    if gold:
        score += 2.2
    elif isinstance(hist, (int, float)) and hist > 0:
        score += 1.0
    if obv is True:
        score += 1.5
    if isinstance(rsi, (int, float)) and 20 <= rsi <= 55:
        score += 0.8
    pos60 = pk.get("pos60")
    if isinstance(pos60, (int, float)):
        if pos60 <= -20:
            score += 2.0
        elif pos60 <= -12:
            score += 1.5
        elif pos60 <= -6:
            score += 1.0
        else:
            score += 0.3
    if pk.get("above5"):
        score += 0.8
    if pk.get("above10"):
        score += 0.4
    if pk.get("three_no_low"):
        score += 1.2
    if pk.get("up_day"):
        score += 0.6
    vr = pk.get("vol_ratio")
    if pk.get("up_day") and isinstance(vr, (int, float)) and 1.0 <= vr <= 3.5:
        score += 1.0
    if dag:
        score += 3.0
    # 核心：须有企稳/刚翻红/金叉/待企稳/主线DAG 之一的转折确认。
    # （仅"站上5日线+三日不新低"不作数——那是反弹可回落的形态，避免重演
    #  "未企稳·勿接飞刀"仍被列进精选的自相矛盾）
    core = stable or fresh or wait_stable or gold or dag
    return (score >= _DECIDE_MIN_SCORE and core), score


def _qualify_line(pk):
    """精选合格票的单行（🔴 候选推荐；含指标/确认/主线DAG标记）。
    精选行不再输出『未企稳·勿接飞刀』等负面标注，改为转折催化剂结论。"""
    line = "    🔴 %s(%s) 距60日高%+.1f%% 今%+.1f%%" % (
        pk["name"], pk["code"], pk.get("pos60") or 0, pk.get("pct") or 0)
    extras = []
    if pk.get("tag"):
        extras.append(pk["tag"])
    note = pk.get("note") or ""
    if note.startswith("当日放量企稳"):
        extras.append("当日放量企稳✓")
    elif note.startswith("待企稳"):
        extras.append(note)
    if pk.get("fresh_up"):
        extras.append("绿转红✓")
    joined = " ".join(extras)
    if pk.get("macd_golden") and "金叉" not in joined:
        extras.append("MACD金叉✓")
    if pk.get("dag_hit") and "主线DAG" not in " ".join(extras):
        extras.append("主线DAG✓")
    if extras:
        line += " | " + " | ".join(extras)
    return line


def _pick_line(pk):
    """低吸候选单行（🔴 名称(代码) 距60日高x% 今y% | 指标 | 确认）。"""
    base = "    🔴 %s(%s) 距60日高%+.1f%% 今%+.1f%%" % (
        pk["name"], pk["code"], pk["pos60"], pk["pct"])
    if pk.get("tag"):
        base += " | " + pk["tag"]
    if pk.get("note"):
        base += " | " + pk["note"]
    return base


# ── 行业ETF前五重仓 全史日K（db 优先 + 腾讯补缺；回放/拟合唯一数据源）──
def load_top5_hist():
    try:
        with open(TOP5_HIST_FILE, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def save_top5_hist(codes):
    payload = {"updated": _dt.datetime.now().strftime("%Y-%m-%d %H:%M"),
               "note": "行业ETF前五重仓全史日K(db优先/腾讯补缺)",
               "codes": codes}
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        tmp = TOP5_HIST_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
        os.replace(tmp, TOP5_HIST_FILE)
    except OSError as e:
        print("top5 hist 落盘失败:", e)


def industry_funds():
    """THEME_RULES 全部行业主题 ETF（去重保序）→ [(fcode, fname, theme)]"""
    out, seen = [], set()
    for kws, lst in THEME_RULES:
        theme = kws[0]
        for fcode, fname in lst:
            if fcode not in seen:
                seen.add(fcode)
                out.append((fcode, fname, theme))
    return out


def _db_snaps(code6):
    """market_data.db 全史日K → snaps dict 列表；无则 []。"""
    pref = _prefixed(code6)
    try:
        import sqlite3
        con = sqlite3.connect(MARKET_DB_PATH)
        try:
            rows = con.execute(
                "SELECT date,open,high,low,close,volume FROM kline "
                "WHERE secid=? ORDER BY date", (pref,)).fetchall()
        finally:
            con.close()
        return [{"date": d, "open": float(o or 0), "high": float(h or 0),
                 "low": float(l or 0), "close": float(c or 0),
                 "volume": float(v or 0)} for d, o, h, l, c, v in rows]
    except Exception:
        return []


def build_top5_hist(refresh_missing=True, print_log=None):
    """构建/补齐『行业ETF前五重仓』全史日K 映射 {code: {"name","snaps"}}。
    来源：market_data.db 全史优先；腾讯日K 520 根补缺。
    返回 codes dict（已自动落盘增量）。"""
    log = print_log or print
    hold = load_holdings()
    if not hold:
        log("[top5hist] 无 _etf_holdings.json，先运行 python _etf_holdings.py")
        return {}
    need = {}
    for f in hold.get("funds") or []:
        if f.get("theme") in ("宽基",):
            continue
        for s in (f.get("top") or [])[:5]:
            need.setdefault(s["code"], s.get("name") or "")
    old = (load_top5_hist().get("codes") or {})
    codes = {}
    for c, nm in need.items():
        ent = old.get(c) or {}
        if ent.get("snaps") and len(ent["snaps"]) >= 40:
            codes[c] = {"name": ent.get("name") or nm, "snaps": ent["snaps"]}
    if not refresh_missing:
        return codes
    todo = [c for c in need if c not in codes]
    if not todo:
        return codes
    # db 补齐
    db_ok, fetched = 0, 0
    for c in todo:
        snaps = _db_snaps(c)
        if snaps and len(snaps) >= 40:
            codes[c] = {"name": need[c], "snaps": snaps}
            db_ok += 1
    todo2 = [c for c in need if c not in codes]
    if todo2:
        try:
            import _overseas_fetch
            for i, c in enumerate(todo2):
                try:
                    rows = _overseas_fetch.fetch_kline(_prefixed(c), count=520)
                    if rows and len(rows) >= 40:
                        codes[c] = {"name": need[c], "snaps": rows}
                        fetched += 1
                except Exception:
                    pass
                if i % 20 == 19:
                    log("[top5hist] 腾讯补缺 %d/%d" % (i + 1, len(todo2)))
        except Exception as e:
            log("[top5hist] 腾讯补缺失败:", e)
    save_top5_hist(codes)
    log("[top5hist] 覆盖 %d/%d (db补齐%d 腾讯%d)" % (len(codes), len(need), db_ok, fetched))
    return codes


def screen_picks_asof(asof, hist=None, industry_only=True, dag_codes=None):
    """以截至 asof(含当日收盘K线) 的数据，对行业ETF前五重仓做低吸选股。
    返回 {fcode: {"name","theme","picks":[dict]}}；picks 按距60日高优先深伏排序。
    每个 pick 含 _screen_meta 明细字段(SAR方向/绿转红/企稳note/MACD/OBV/均线等)，
    dag_codes 传当日主线DAG命中代码集合时附加 dag_hit。
    hist: {code:{"name","snaps"}}（全史）；缺省用 data/_etf_top5_hist.json。"""
    hist = hist or (load_top5_hist().get("codes") or {})
    dag_codes = dag_codes or set()
    hold = load_holdings()
    if not hold:
        return {}
    by_fund = {f["code"]: f for f in hold.get("funds") or []}
    out = {}
    for fcode, fname, theme in industry_funds():
        if industry_only and theme in ("宽基",):
            continue
        top = ((by_fund.get(fcode) or {}).get("top") or [])[:5]
        if not top:
            continue
        picks = []
        for s in top:
            ent = hist.get(s["code"])
            if not ent:
                continue
            snaps = _slice_by_date(ent.get("snaps") or [], asof)
            if len(snaps) < 2:
                continue
            pct = _day_pct(snaps)
            if pct is None or not (-4.0 <= pct <= 4.0):
                continue
            pos60 = _pos60(snaps)
            if pos60 is None or pos60 > -1.0:
                continue
            meta = _screen_meta(snaps)
            pk = {
                "code": s["code"],
                "name": (ent.get("name") or s.get("name") or s["code"]),
                "pos60": pos60, "pct": pct,
                "dag_hit": s["code"] in dag_codes,
            }
            pk.update(meta)
            picks.append(pk)
        if not picks:
            continue
        picks.sort(key=lambda x: x["pos60"])
        out[fcode] = {"name": fname, "theme": theme, "picks": picks}
    return out


def _assemble_lowbuy(seq, groups, stats, max_rows, rejected=None):
    """seq=[(score, gidx, pk)…] 打分组渲染低吸精选文本行。
    同股跨ETF只保留一次；按分数降序取前 max_rows 只；
    SAR绿转红(fresh_up/刚翻红)不占前 N 限额——超出部分追加展示，允许输出 >5 只。
    rejected: SAR红但未过企稳/共振的 pk 列表——2026-09-08 起点名列出(🟡仅观察)，
    不再只数个数，让用户知道"是谁"。无任何符合时给出空因摘要。"""
    seq.sort(key=lambda r: -r[0])
    seen, keep, fresh_extra = set(), [], []
    for score, gidx, pk in seq:
        if pk["code"] in seen:
            continue
        seen.add(pk["code"])
        if len(keep) < max_rows:
            keep.append((gidx, pk))
        elif pk.get("fresh_up"):
            fresh_extra.append((gidx, pk))
    # SAR红但未过企稳/共振 → 点名（与精选/fresh 去重）
    pending = []
    for pk in rejected or []:
        if pk["code"] in seen:
            continue
        seen.add(pk["code"])
        pending.append(pk)
    lines, prev = [], None
    if not keep and not pending:
        sar_ok = max(stats.get("cand", 0) - stats.get("sar_dn", 0), 0)
        tail = ""
        if stats.get("fresh"):
            tail = "；其中绿转红%d只(可留意)" % stats["fresh"]
        return ["  (今日无符合低吸：观察%d只→SAR绿%d排除、SAR红%d只未过企稳/共振精选%s)"
                % (stats.get("cand", 0), stats.get("sar_dn", 0), sar_ok, tail)]

    def _flush(items):
        nonlocal prev
        for gidx, pk in items:
            if gidx != prev:
                g = groups[gidx]
                lines.append("  %s(%s)%s:" % (g["name"], g["code"],
                                              (" 资金入%+.1f亿" % g["fz"]) if g.get("fz") else ""))
                prev = gidx
            lines.append(_qualify_line(pk))

    if not keep:
        lines.append("  (今日无低吸精选通过；SAR红%d只未过企稳/共振，点名如下，仅观察)"
                     % len(pending))
    _flush(keep)
    if fresh_extra:
        lines.append("  ── 以下 SAR 刚翻红(绿转红) 不占前%d限额，放宽列出 ──" % max_rows)
        _flush(fresh_extra)
    if pending:
        if keep:
            lines.append("  ── SAR红但未过企稳/共振(仅观察，勿接飞刀) ──")
        for pk in pending[:max_rows * 2]:
            note = pk.get("note") or ""
            tail = (" | " + note) if note else ""
            lines.append("    🟡 %s(%s) 距60日高%+.1f%% 今%+.1f%%%s" % (
                pk["name"], pk["code"], pk.get("pos60") or 0, pk.get("pct") or 0, tail))
    lines.append("  ── 口径: 前五重仓→SAR红(绿排除)→企稳/量能/MACD/OBV共振打分，取前≤%d只%s ──"
                 % (max_rows, "，绿转红可超限" if fresh_extra else ""))
    return lines


def low_buy_lines_offline(asof, hist=None, dag_codes=None, max_rows=5):
    """离线『🎯 ETF持仓前五·低吸精选』文本行（全行业板块；历史回放页面2用）。
    只输出通过 _decide_pick 的合格票（SAR绿直接不展示）；无符合时明示空因。"""
    st = screen_picks_asof(asof, hist=hist, dag_codes=dag_codes)
    groups, seq, rejected = [], [], []
    stats = {"cand": 0, "sar_dn": 0, "fresh": 0}
    seen_code = set()
    for fcode, info in st.items():
        gidx = len(groups)
        groups.append({"code": fcode, "name": info["name"]})
        for pk in info["picks"]:
            c = pk["code"]
            if c in seen_code:
                continue
            seen_code.add(c)
            stats["cand"] += 1
            if pk.get("fresh_up"):
                stats["fresh"] += 1
            if pk.get("sar_dir") != "UP":
                stats["sar_dn"] += 1
                continue
            ok, score = _decide_pick(pk)
            if ok:
                seq.append((score, gidx, pk))
            else:
                rejected.append(pk)  # SAR红但未过企稳/共振 → 点名观察
    return _assemble_lowbuy(seq, groups, stats, max_rows, rejected)


# 当日已取数缓存（code → _screen_meta dict），避免 15 分钟轮询重复拉日K
_KLINE_TODAY = {}
_KLINE_DATE = ""


def _meta_live(code):
    """code → 最新日K的 _screen_meta 明细（SAR/企稳/共振/均线等）。单日按 code 缓存；
    拉取失败返回 {}。"""
    global _KLINE_DATE
    today = _dt.date.today().isoformat()
    if _KLINE_DATE != today:
        _KLINE_TODAY.clear()
        _KLINE_DATE = today
    if code in _KLINE_TODAY:
        return _KLINE_TODAY[code]
    meta = {}
    try:
        import _overseas_fetch
        rows = _overseas_fetch.fetch_kline(_prefixed(code), count=300)
        if rows:
            meta = _screen_meta(rows)
    except Exception:
        meta = {}
    _KLINE_TODAY[code] = meta
    return meta


def _confirm_and_tag(code):
    """兼容壳：返回 (note, tag)，规则同 _screen_meta（企稳/待企稳/未企稳 + 指标串）。"""
    m = _meta_live(code)
    return (m.get("note") or ""), (m.get("tag") or "")


def _confirm_note(code):
    """兼容壳：只返回确认标注。"""
    return _confirm_and_tag(code)[0]


def low_buy_lines(hot_themes, etf_flow=None, dag_codes=None, max_rows=5):
    """实时『热门+重点关注板块 → ETF 前5重仓 → 低吸精选』文本行（供每轮推送页面2）。

    hot_themes: 板块名列表（资金流入热门在前、用户重点关注殿后，已按优先级排好）
    etf_flow: ctx["etf_flow"]（东财ETF净流入榜 [{name,in_yi,chg_pct}]，用于标注板块资金）
    dag_codes: 当日主线DAG命中代码集合(6位)；命中票附『主线DAG✓』并加分
    只输出通过 _decide_pick 的合格票（SAR绿直接不展示）；无符合时明示空因。
    """
    hold = load_holdings()
    if not hold:
        return []
    theme_funds = match_funds_by_theme(hot_themes)
    if not theme_funds:
        return []
    top_by_fund = {f["code"]: f["top"] for f in hold.get("funds") or []}
    name_by_fund = {f["code"]: f["name"] for f in hold.get("funds") or []}
    theme_of_fund = {fcode: theme for fcode, _, theme in theme_funds}
    stocks = hold.get("stocks") or {}
    dag_codes = dag_codes or set()

    # 候选去重保序 + 归属首个板块ETF（同股跨ETF只显示一次，如兆易创新同时属半导体/芯片）
    own = {}
    for fcode, _, _ in theme_funds:
        for s in (top_by_fund.get(fcode) or [])[:5]:
            own.setdefault(s["code"], (fcode, s))
    quotes = tencent_quotes(list(own.keys()))
    if not quotes:
        return []

    # ETF 当日资金净流入（用于标注，名称含 ETF 主题词或简称）
    flow_in = {}
    for e in etf_flow or []:
        nm = str(e.get("name") or "")
        yi = float(e.get("in_yi") or 0)
        if yi > 0:
            flow_in[nm] = yi

    groups, fidx = [], {}
    seq, stats, rejected = [], {"cand": 0, "sar_dn": 0, "fresh": 0}, []
    for code, (fcode, s) in own.items():
        q = quotes.get(code)
        if not q:
            continue
        pct = q["pct"]
        # 低吸过滤：当日温吞（不追高、不接暴跌中段），且已低于 60 日高点
        if not (-4.0 <= pct <= 4.0):
            continue
        pos60 = (stocks.get(code) or {}).get("pos60")
        if pos60 is None or pos60 > -1.0:
            continue
        meta = _meta_live(code)
        if not meta.get("sar_dir"):
            continue
        stats["cand"] += 1
        pk = {"code": code, "name": s.get("name") or s["code"],
              "pos60": pos60, "pct": pct, "dag_hit": code in dag_codes}
        pk.update(meta)
        if pk.get("fresh_up"):
            stats["fresh"] += 1
        if pk.get("sar_dir") != "UP":
            stats["sar_dn"] += 1
            continue
        ok, score = _decide_pick(pk)
        if not ok:
            rejected.append(pk)  # SAR红但未过企稳/共振 → 点名观察
            continue
        if fcode not in fidx:
            fidx[fcode] = len(groups)
            fname = name_by_fund.get(fcode, fcode)
            theme = theme_of_fund.get(fcode) or ""
            fz = 0.0
            short = fname.replace("ETF", "")
            for fn, yi in flow_in.items():
                if (theme and theme in fn) or short in fn or fname in fn:
                    fz = yi
                    break
            groups.append({"code": fcode, "name": fname, "fz": fz})
        seq.append((score, fidx[fcode], pk))
    return _assemble_lowbuy(seq, groups, stats, max_rows, rejected)


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
