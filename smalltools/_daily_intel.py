# -*- coding: utf-8 -*-
"""每日节奏情报引擎（PC 侧权威实现；APK 侧 DailyRhythmScheduler 同口径）。

用户 2026-09-11 确认的节奏：
    pre8   08:00  盘前：宏观快照 + 美股收盘 → 利好利空板块判定 → 候选标的 → 推送 + 存库
    pre9   09:00  盘前：宏观快照 + 亚太实时（日经/KOSPI/恒生/台湾/新加坡/澳洲 + 韩国权重股）
                        → 同上（用于开盘前最后一次校正）
    review 15:20  收盘复盘：a 板块判定对错 / b 当日主线·smalltools·ETF 选股
                        / c 近5日信号票巡诊 / d 实仓镜像 —— 全部表格化（PNG）

口径要点（用户明确）：
  · 宏观「预期」本身就是催化（如美联储加息概率、日央行口风），不因「未落地」而淡化；
    判定板块时按「预期方向 × 已发生事实」双轨给分。

数据源：
  · 腾讯 qt.gtimg.cn            A股/美股/港股/韩股实时
  · 新浪 hq.sinajs.cn           商品（WTI/黄金）、在岸人民币、日经/恒生、美股收盘
  · 东财 push2delay.eastmoney.com  全球指数（push2 主域被断时的可用链路，2026-09-11 实测）
  · FRED（经 _macro_sentinel）  10Y/30Y 美债

存库：market_data.db → intel_report（情报原文+板块判定+标的）、push_record（推送账本）

CLI：
  python _daily_intel.py --slot pre8      # 08:00 盘前情报
  python _daily_intel.py --slot pre9      # 09:00 亚太情报
  python _daily_intel.py --slot review    # 15:20 表格化复盘
  python _daily_intel.py --slot auto      # 按当前时间自动选段
  python _daily_intel.py --slot pre8 --dry
"""
import argparse
import datetime
import json
import os
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import _market_db as mdb            # noqa: E402
import push_channel                 # noqa: E402
from _sector_quote import kline_stats, quotes   # noqa: E402

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
SINA_H = {"Referer": "https://finance.sina.com.cn", **UA}
EAST_DELAY = "https://push2delay.eastmoney.com"
EAST_UT = "fa5fd1943c7b386f172d6893dbfba10b"

# 新浪指数兜底：secid → (新浪代码, 名称, 区域, 点位字段, 涨跌幅字段)
# 字段布局 2026-09-11 实测：int_* f1=点位 f3=涨跌幅；gb_$* f1=点位 f2=涨跌幅（KOSPI 新浪无源）
SINA_IDX_FALLBACK = {
    "100.N225": ("int_nikkei", "日经225", "亚太", 1, 3),
    "100.HSI": ("int_hangseng", "恒生指数", "亚太", 1, 3),
    "100.DJIA": ("gb_$dji", "道琼斯", "美股", 1, 2),
    "100.NDX": ("gb_$ixic", "纳斯达克", "美股", 1, 2),
    "100.SPX": ("gb_$inx", "标普500", "美股", 1, 2),
}

APP_CONFIG = os.path.join(ROOT, "app", "src", "main", "assets", "data", "app_config.json")
DATA_DIR = os.path.join(HERE, "data")
INTEL_JSON = os.path.join(DATA_DIR, "_daily_intel.json")      # 供盘中轮前导展示
TABLE_DIR = os.path.join(DATA_DIR, "review_tables")
LEDGER = os.path.join(HERE, "_records", "_pick_ledger.jsonl")
REVIEWS = os.path.join(HERE, "_records", "_pick_reviews.json")
DAG_SCREEN = os.path.normpath(os.path.join(ROOT, "AutoQuant", "data", "dag_screen_latest.json"))
DAG_LUNCH = os.path.normpath(os.path.join(ROOT, "AutoQuant", "data", "dag_screen_lunch.json"))
CAND_JSON = os.path.normpath(os.path.join(ROOT, "AutoQuant", "data", "candidates_quant.json"))
ETF_LIVE = os.path.join(DATA_DIR, "_etf_live_picks.json")

# 全球指数（东财 secid → (中文名, 区域)）
GLOBAL_IDX = {
    "100.DJIA": ("道琼斯", "美股"),
    "100.NDX": ("纳斯达克", "美股"),
    "100.SPX": ("标普500", "美股"),
    "100.N225": ("日经225", "亚太"),
    "100.KS11": ("韩国KOSPI", "亚太"),
    "100.HSI": ("恒生指数", "亚太"),
    "100.TWII": ("台湾加权", "亚太"),
    "100.STI": ("新加坡海峡", "亚太"),
    "100.AS51": ("澳洲标普200", "亚太"),
    "100.FTSE": ("英国富时100", "欧洲"),
    "100.GDAXI": ("德国DAX30", "欧洲"),
    "100.FCHI": ("法国CAC40", "欧洲"),
    "100.SX5E": ("欧洲斯托克50", "欧洲"),
    "100.UDI": ("美元指数", "汇率"),
}

# 新浪商品/汇率（代码 → 中文名）
SINA_CMDT = {
    "hf_CL": "WTI原油",
    "hf_GC": "COMEX黄金",
    "fx_susdcny": "在岸人民币",
}

# 韩国权重股（腾讯韩股代码 + 中文名），09:00 亚太时段用
KR_POOL = [("kr005930", "三星电子"), ("kr000660", "SK海力士"),
           ("kr373220", "LG新能源"), ("kr005380", "现代汽车"),
           ("kr035420", "NAVER"), ("kr000270", "起亚")]

# 板块候选池（板块名 → [(secid, 名称), ...]）
BOARD_POOL = {
    "油气开采/油服": [
        ("sh601857", "中国石油"), ("sh600938", "中国海油"), ("sh600583", "海油工程"),
        ("sh601808", "中海油服"), ("sh603619", "中曼石油"), ("sh600968", "海油发展"),
    ],
    "油运/航运": [
        ("sh601872", "招商轮船"), ("sh600026", "中远海能"),
        ("sh601975", "招商南油"), ("sh600798", "宁波海运"),
    ],
    "天然气/LNG": [
        ("sh600256", "广汇能源"), ("sh600803", "新奥股份"), ("sh603393", "新天然气"),
        ("sz002267", "陕天然气"), ("sh600617", "国新能源"), ("sh600956", "新天绿能"),
    ],
    "煤炭/煤化工": [
        ("sh601088", "中国神华"), ("sh601225", "陕西煤业"), ("sh601898", "中煤能源"),
        ("sh600188", "兖矿能源"), ("sz000983", "山西焦煤"), ("sh600985", "淮北矿业"),
        ("sh600989", "宝丰能源"), ("sz000933", "神火股份"), ("sh601666", "平煤股份"),
        ("sh600971", "恒源煤电"),
    ],
    "有色/资源": [
        ("sh601600", "中国铝业"), ("sh600362", "江西铜业"), ("sh603993", "洛阳钼业"),
        ("sh600111", "北方稀土"), ("sh600547", "山东黄金"), ("sz002155", "湖南黄金"),
    ],
    "高股息红利": [
        ("sh601398", "工商银行"), ("sh601288", "农业银行"), ("sh600028", "中国石化"),
        ("sh600900", "长江电力"), ("sh601088", "中国神华"),
    ],
    "电力/公用": [
        ("sh600900", "长江电力"), ("sh600011", "华能国际"),
        ("sh601985", "中国核电"), ("sh600025", "华能水电"),
    ],
    "农业/食品(CPI)": [
        ("sz000998", "隆平高科"), ("sz002041", "登海种业"), ("sh600598", "北大荒"),
        ("sh601952", "苏垦农发"), ("sz000876", "新希望"), ("sh600887", "伊利股份"),
    ],
    "造纸(人民币升值)": [
        ("sz002078", "太阳纸业"), ("sh600966", "博汇纸业"), ("sh600567", "山鹰国际"),
    ],
    "建材/基建": [
        ("sh600801", "华新建材"), ("sh600176", "中国巨石"), ("sh600019", "宝钢股份"),
    ],
    "航空(利空关注)": [
        ("sh601111", "中国国航"), ("sh600029", "南方航空"),
        ("sh601021", "春秋航空"), ("sh600115", "中国东航"),
    ],
    "成长科技(利空关注)": [
        ("sh688981", "中芯国际"), ("sz300502", "新易盛"),
        ("sz002371", "北方华创"), ("sh688008", "澜起科技"),
    ],
    "化工下游(利空关注)": [
        ("sz002001", "新和成"), ("sh600309", "万华化学"),
        ("sh600426", "华鲁恒升"), ("sz000301", "东方盛虹"),
    ],
}


# ────────────────────────────── 基础工具 ──────────────────────────────
def _f(v, default=None):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def _slug(s):
    return "".join(ch for ch in str(s) if ch not in "()（）/· ")


def load_notify_cfg():
    """读 app_config.json 的 notify 段（与 _market_scan/_publish_candidates 同源）。"""
    try:
        with open(APP_CONFIG, encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, ValueError):
        return {}
    return cfg.get("notify") or cfg


# ────────────────────────────── 采集 ──────────────────────────────
def east_global(secids=None):
    """东财全球指数批量行情 → {secid: {name, price, pct}}。

    东财 `push2delay` 偶发 ReadTimeout（2026-09-11 实测），故每批重试 3 次；
    仍缺的指数用新浪兜底（日经/恒生 `int_*`、美股 `gb_$*`，字段布局已实测：
    int_ 系列 f1=点位 f3=涨跌幅；gb_$ 系列 f1=点位 f2=涨跌幅）。KOSPI 新浪无源，仅东财。
    """
    secids = secids or list(GLOBAL_IDX)
    out = {}
    for i in range(0, len(secids), 12):
        batch = secids[i:i + 12]
        diff = None
        for attempt in range(3):
            try:
                r = requests.get(EAST_DELAY + "/api/qt/ulist.np/get",
                                 params={"secids": ",".join(batch),
                                         "fields": "f2,f3,f4,f12,f14",
                                         "fltt": 2, "ut": EAST_UT, "invt": 2},
                                 timeout=15, headers=UA)
                diff = ((r.json().get("data") or {}).get("diff")) or []
                break
            except Exception as e:  # noqa: BLE001
                if attempt == 2:
                    print("东财全球指数失败(重试3次): %s" % type(e).__name__)
                else:
                    time.sleep(1.0 + attempt)
        if not diff:
            continue
        for d in diff:
            sid = "100." + str(d.get("f12") or "")
            if sid in GLOBAL_IDX:
                out[sid] = {"name": GLOBAL_IDX[sid][0], "region": GLOBAL_IDX[sid][1],
                            "price": _f(d.get("f2")), "pct": _f(d.get("f3"))}
        time.sleep(0.15)

    missing = [s for s in GLOBAL_IDX if s not in out and s in SINA_IDX_FALLBACK]
    if missing:
        got = _sina_index_fallback(missing)
        if got:
            print("  （东财缺 %d 个，新浪兜底补全 %d 个）" % (len(missing), len(got)))
            out.update(got)
    return out


def _sina_index_fallback(secids):
    """新浪指数兜底：secid → {name, region, price, pct}。"""
    if not secids:
        return {}
    syms = [SINA_IDX_FALLBACK[s][0] for s in secids]
    lines = sina_lines(syms)
    out = {}
    for sid in secids:
        sym, name, region, p_field, pct_field = SINA_IDX_FALLBACK[sid]
        f = lines.get(sym) or []
        if len(f) <= max(p_field, pct_field):
            continue
        out[sid] = {"name": name, "region": region,
                    "price": _f(f[p_field]), "pct": _f(f[pct_field])}
    return out


def east_kr_stocks():
    """韩国权重股（东财 100.KS11 域不覆盖个股 → 走腾讯韩股代码）。"""
    return {}


def sina_lines(syms):
    """新浪批量行情 → {代码: [字段...]}。"""
    try:
        r = requests.get("https://hq.sinajs.cn/list=" + ",".join(syms),
                         timeout=10, headers=SINA_H)
        r.encoding = "gbk"
    except Exception as e:  # noqa: BLE001
        print("新浪行情失败: %s" % type(e).__name__)
        return {}
    out = {}
    for line in r.text.strip().split("\n"):
        line = line.strip()
        if "hq_str_" not in line or '="' not in line:
            continue
        code = line.split("hq_str_", 1)[1].split("=", 1)[0]
        body = line.split('="', 1)[1].rstrip('";')
        if body:
            out[code] = body.split(",")
    return out


def us_close():
    """美股收盘（新浪 gb_$dji / gb_$ixic / gb_$inx）：名称/收盘/涨跌%/报价时间。"""
    m = sina_lines(["gb_$dji", "gb_$ixic", "gb_$inx"])
    names = {"gb_$dji": "道琼斯", "gb_$ixic": "纳斯达克", "gb_$inx": "标普500"}
    out = []
    for k, cn in names.items():
        f = m.get(k)
        if not f or len(f) < 5:
            continue
        out.append({"name": cn, "close": _f(f[1]), "pct": _f(f[2]),
                    "ts": f[3], "chg": _f(f[4])})
    return out


def commodity_fx():
    """商品/汇率（新浪）：WTI 原油、COMEX 黄金、在岸人民币。"""
    m = sina_lines(list(SINA_CMDT))
    out = {}
    for k, cn in SINA_CMDT.items():
        f = m.get(k)
        if not f:
            continue
        if k.startswith("hf_"):
            # hf_ 字段：0最新 1? 2买 3卖 4最高 5最低 6时间 7昨收 8今开
            cur, prev = _f(f[0]), _f(f[7])
            pct = ((cur / prev - 1) * 100) if (cur and prev) else None
            out[cn] = {"price": cur, "prev": prev, "pct": pct,
                       "time": f[6] if len(f) > 6 else ""}
        else:
            # fx_susdcny：0时间 1买价 2卖价 3中间价 ...
            out[cn] = {"price": _f(f[3]) or _f(f[1]),
                       "time": f[0] if f else ""}
    return out


def sentinels():
    """四根宏观哨兵（复用 _macro_sentinel）。返回 (lines, state_dict)。"""
    try:
        import _macro_sentinel as msent
        st = msent.refresh()
        return msent.render_sentinel_lines(record_daily=False), (st or {})
    except Exception as e:  # noqa: BLE001
        return [], {"error": type(e).__name__}


def collect_news(top=12):
    """东财 7x24 财经快讯。"""
    try:
        r = requests.get(
            "https://np-listapi.eastmoney.com/comm/web/getFastNewsList",
            params={"client": "web", "biz": "web_724", "fastColumn": "102",
                    "sortEnd": "", "pageSize": top, "req_trace": "1"},
            timeout=10, headers=UA)
        lst = ((r.json().get("data") or {}).get("fastNewsList")) or []
    except Exception as e:  # noqa: BLE001
        print("快讯拉取失败: %s" % type(e).__name__)
        return []
    out = []
    for it in lst[:top]:
        out.append({"title": (it.get("title") or it.get("summary", ""))[:80],
                    "time": it.get("showTime") or it.get("digestTime") or ""})
    return out


def snapshot(slot):
    """一次完整采集（08:00 / 09:00 共用，09:00 额外带亚太实时与韩国权重股）。"""
    snap = {
        "ts": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "slot": slot,
        "global": east_global(),
        "us_close": us_close(),
        "commodity": commodity_fx(),
        "news": collect_news(),
    }
    if slot == "pre9":
        try:
            from _market_scan import _tencent
            d = _tencent(",".join(c[0] for c in KR_POOL))
            snap["kr_stocks"] = [
                {"name": cn, "pct": d[code]["pct"], "price": d[code]["price"]}
                for code, cn in KR_POOL if code in d]
            snap["kr_stocks"].sort(key=lambda x: -(x["pct"] or 0))
        except Exception as e:  # noqa: BLE001
            print("韩国权重股失败: %s" % type(e).__name__)
            snap["kr_stocks"] = []
    lines, st = sentinels()
    snap["sentinels_text"] = lines
    snap["sentinels"] = st
    # 大盘定档（用于正文「二、大盘定档」）
    try:
        import _index_market_chart as imc
        snap["market_state_text"] = imc.verdict_text(table=False)
    except Exception:  # noqa: BLE001
        snap["market_state_text"] = ""
    return snap


# ────────────────────────── 利好利空板块判定 ──────────────────────────
def _signals(snap):
    """把全球快照压成规则可用的一组信号。"""
    g = snap.get("global") or {}
    cmd = snap.get("commodity") or {}
    us = {r["name"]: r for r in (snap.get("us_close") or [])}
    st = snap.get("sentinels") or {}

    oil = cmd.get("WTI原油") or {}
    oil_price = oil.get("price")
    oil_pct = oil.get("pct")
    gold = (cmd.get("COMEX黄金") or {})
    cny = (cmd.get("在岸人民币") or {})
    udi = (g.get("100.UDI") or {})

    a = (g.get("100.N225") or {})
    k = (g.get("100.KS11") or {})
    hsi = (g.get("100.HSI") or {})
    nas = us.get("纳斯达克") or (g.get("100.NDX") or {})

    y30 = (st.get("y30") or st.get("us30y") or {})
    y10 = (st.get("y10") or st.get("us10y") or {})
    y30_v = y30.get("value") if isinstance(y30, dict) else None
    y10_v = y10.get("value") if isinstance(y10, dict) else None

    return {
        "oil_price": oil_price,
        "oil_pct": oil_pct,
        "oil_high": bool(oil_price and oil_price >= 100),
        "oil_strong": bool(oil_price and oil_price >= 95),
        "gold_pct": gold.get("pct"),
        "cny": cny.get("price"),
        "udi": udi.get("price"),
        "udi_pct": udi.get("pct"),
        "nikkei_pct": a.get("pct"),
        "kospi_pct": k.get("pct"),
        "hsi_pct": hsi.get("pct"),
        "nas_pct": nas.get("pct"),
        "y30": _f(y30_v),
        "y10": _f(y10_v),
        "rate_hike": True,   # 欧央行已加息 + 日央行/美联储预期升温（用户：预期即催化）
    }


def verdicts(snap):
    """规则化「利好 / 利空」板块判定。

    每条 = {board, side, strength, logic, trigger, falsify}；同板块正反冲突时保留强者并提示。
    """
    s = _signals(snap)
    out = []

    def add(board, side, strength, logic, trigger, falsify):
        out.append({"board": board, "side": side, "strength": strength,
                    "logic": logic, "trigger": trigger, "falsify": falsify})

    # ① 油价破百（已发生事实）
    if s["oil_high"]:
        add("油气开采/油服", "利好", "强",
            "WTI %.1f 美元站稳100关口，EIA 上调均价，资本开支扩张预期" % (s["oil_price"] or 0),
            "布油站稳100以上 / OPEC 继续减产", "布油跌破95 且霍尔木兹流量恢复")
        add("油运/航运", "利好", "中",
            "中东供给扰动 → 运距拉长 + 运价弹性", "霍尔木兹流量未恢复 / VLCC 运价上行",
            "航道恢复常态、运价回落")
        add("煤炭/煤化工", "利好", "中",
            "能源比价替代 + 国内 PPI 煤炭开采环比走强 + 冬储补库",
            "动力煤站上700元/吨 / 10月冬储启动", "动力煤跌破650元/吨")
        add("航空(利空关注)", "利空", "强",
            "航油成本占比高，油价上行直接压缩毛利", "布油维持100以上", "油价快速回落至85以下")
        add("化工下游(利空关注)", "利空", "中",
            "原料成本上行而 CPI 仅0.8% 难以向下游传导", "油价维持高位", "油价回落或化工品提价落地")
    elif s["oil_strong"]:
        add("油气开采/油服", "利好", "中", "油价90+ 区间震荡，油服订单景气延续",
            "油价站稳95", "油价跌破85")

    # ② 全球加息潮（欧央行已加息 + 日央行/美联储预期升温 → 预期即催化）
    if s["rate_hike"]:
        add("高股息红利", "利好", "中",
            "全球利率中枢上移 + 滞胀预期 → 低估值高股息避险资金抱团",
            "10Y美债维持4.5%以上 / 红利成交占比提升", "美债收益率快速下行且成长股放量领涨")
        add("成长科技(利空关注)", "利空", "中",
            "无风险利率上行压制高估值成长，创业板/科创50 已连续走弱",
            "纳指跌破前低 / 创业板失守20日线", "纳指企稳回升、成长放量反包")

    # ③ 美元与汇率
    if s["udi_pct"] is not None and s["udi_pct"] > 0.3:
        add("有色/资源", "利空", "弱", "美元走强压制以美元计价的商品价格",
            "美元指数上破100", "美元指数回落至98下方")
    if s["cny"] and s["cny"] < 6.90:
        add("造纸(人民币升值)", "利好", "中",
            "人民币走强降低进口木浆成本、增厚汇兑收益", "汇率维持6.9以内", "人民币重回7.0上方")

    # ④ 亚太/美股风险偏好
    for label, pct in (("日经225", s["nikkei_pct"]), ("韩国KOSPI", s["kospi_pct"]),
                       ("恒生指数", s["hsi_pct"]), ("纳斯达克", s["nas_pct"])):
        if pct is not None and pct <= -1.5:
            add("高股息红利", "利好", "弱",
                "%s 跌 %.2f%%，外围避险情绪外溢，A股防御占优" % (label, pct),
                "外围继续走弱", "外围快速修复")
            break

    # ⑤ 黄金
    if s["gold_pct"] is not None and s["gold_pct"] > 1.0:
        add("有色/资源", "利好", "弱",
            "黄金单日涨超1%%，避险与滞胀交易共振", "金价续创新高", "金价回落2%以上")

    # ⑥ 国内 CPI/PPI（通胀温和 + 上游涨价）
    add("农业/食品(CPI)", "利好", "弱",
        "CPI 同比0.8% 回升但食品项仍弱，属预期修复而非需求驱动",
        "食品项同比转正 / 猪价上行", "CPI 回落至0.5%以下")
    add("电力/公用", "利好", "弱",
        "PPI 电力+1.4%、用电用煤季节性增加，成本可传导",
        "煤价上行且电价联动", "煤价快速回落")

    # 同板块正反冲突 → 保留强者并标注
    merged = {}
    for v in out:
        key = v["board"]
        rank = {"强": 3, "中": 2, "弱": 1}
        cur = merged.get(key)
        if cur is None or rank.get(v["strength"], 0) > rank.get(cur["strength"], 0):
            merged[key] = v
        elif cur["side"] != v["side"]:
            cur["logic"] += "（另有反向：%s）" % v["logic"]
    order = {"强": 0, "中": 1, "弱": 2}
    return sorted(merged.values(), key=lambda v: (order.get(v["strength"], 9),
                                                  v["side"] != "利好"))


# ────────────────────────── 选股（板块 → 标的） ──────────────────────────
def _pass_filter(q, ks):
    """六维过滤（sector-picks 口径）：估值/市值/涨幅/形态/回撤。"""
    if not q or not ks:
        return False, "数据不足"
    mcap = q.get("mcap_yi")
    pb = q.get("pb")
    pe = q.get("pe_static")
    chg20 = ks.get("chg20")
    dd60 = ks.get("dd60")
    shape = ks.get("shape")
    if mcap is not None and (mcap < 40 or mcap > 30000):
        return False, "市值%.0f亿超区间" % mcap
    if pb is not None and pb > 3.2:
        return False, "PB%.2f偏高" % pb
    if pe is not None and pe > 45:
        return False, "PE%.1f偏高" % pe
    if chg20 is not None and chg20 > 50:
        return False, "20日+%.0f%%已鱼尾" % chg20
    # 追高闸门：20日涨超25%且已贴近60日高 → 鱼身中后段，只做回调不追高
    if chg20 is not None and chg20 > 25 and (dd60 if dd60 is not None else -99) > -6:
        return False, "20日+%.0f%%且贴近60高，鱼身中后段" % chg20
    if shape == "空头":
        return False, "均线空头"
    if dd60 is not None and dd60 < -20:
        return False, "距60高%.0f%%深跌" % dd60
    return True, ""


def screen(verd, per_board=2, limit=6, log=print):
    """按利好强度排序，逐板块取候选并做六维过滤 → 标的清单。"""
    picks = []
    used = set()
    bull = [v for v in verd if v["side"] == "利好"]
    for v in bull:
        board = v["board"]
        pool = BOARD_POOL.get(board) or []
        if not pool:
            continue
        qs = quotes([(c, n, board) for c, n in pool])
        rows = []
        for c, n in pool:
            if c in used:
                continue
            q = qs.get(c)
            ks = kline_stats(c)
            ok, why = _pass_filter(q, ks)
            if not ok:
                continue
            mcap = (q or {}).get("mcap_yi") or 0
            score = ({1: 0.6, 2: 0.8}.get(len(rows) + 1, 1.0) *
                     (1.4 if (mcap and mcap <= 800) else 1.0) *
                     (1.2 if (ks or {}).get("shape") == "多头" else 1.0))
            rows.append({"secid": c, "name": n, "board": board,
                         "logic": v["logic"], "trigger": v["trigger"],
                         "falsify": v["falsify"], "strength": v["strength"],
                         "price": (q or {}).get("price"),
                         "pct": (q or {}).get("pct"),
                         "pe": (q or {}).get("pe_static"),
                         "pb": (q or {}).get("pb"),
                         "mcap": mcap, "turn": (q or {}).get("turn"),
                         "chg5": (ks or {}).get("chg5"),
                         "chg20": (ks or {}).get("chg20"),
                         "dd60": (ks or {}).get("dd60"),
                         "shape": (ks or {}).get("shape"),
                         "score": score})
        rows.sort(key=lambda r: -r["score"])
        take = rows[:per_board]
        for r in take:
            used.add(r["secid"])
        picks.extend(take)
        log("  · %s → %s" % (board, "、".join(r["name"] for r in take) or "无合格标的"))
        if len(picks) >= limit:
            break
    return picks[:limit]


# ────────────────────────── 渲染（文本 / 表格） ──────────────────────────
def _fmt_pct(v):
    return "%+.2f%%" % v if v is not None else "—"


def _fmt_num(v, n=2):
    return ("%%.%df" % n) % v if v is not None else "—"


def render_text(snap, verd, picks, slot):
    """情报正文（企微 text）。pre8 用 1-7 段板块情报；pre9 用亚太情报格式。"""
    if slot == "pre8":
        return render_board_report(snap, verd, picks)
    return render_pre9_text(snap, verd, picks, slot)


def render_board_report(snap, verd, picks):
    """08:00 板块情报正文：文字(1/2/7) + 表格(3/4/5/6)占位。"""
    ts = snap.get("ts", "")
    lines = ["【板块情报·%s】%s" % ("08:00盘前", ts), ""]

    # 一、宏观综合
    lines.append("一、宏观综合")
    g = snap.get("global") or {}
    for region in ("美股", "亚太", "欧洲"):
        toks = []
        for sid, meta in GLOBAL_IDX.items():
            if meta[1] != region:
                continue
            it = g.get(sid)
            if it and it.get("pct") is not None:
                toks.append("%s %s" % (meta[0], _fmt_pct(it["pct"])))
        if toks:
            lines.append("  " + " | ".join(toks))
    us = snap.get("us_close") or []
    if us:
        lines.append("  美股收盘: " + " | ".join(
            "%s %s(%s)" % (r["name"], _fmt_pct(r["pct"]), _fmt_num(r["close"]))
            for r in us))
    cmd = snap.get("commodity") or {}
    ctoks = []
    for cn in ("WTI原油", "COMEX黄金", "在岸人民币"):
        it = cmd.get(cn)
        if it and it.get("price"):
            s = "%s %s" % (cn, _fmt_num(it["price"]))
            if it.get("pct") is not None:
                s += "(%s)" % _fmt_pct(it["pct"])
            ctoks.append(s)
    if ctoks:
        lines.append("  商品汇率: " + " | ".join(ctoks))

    # 二、大盘定档
    lines.append("")
    lines.append("二、大盘定档")
    mst = snap.get("market_state_text") or ""
    if mst:
        lines.extend("  " + ln for ln in mst.strip().split("\n"))
    else:
        lines.append("  （待 09:20 开盘速览补充）")

    # 三~六：表格占位
    lines.append("")
    lines.append("三、利好板块 / 四、利空板块 / 五、证伪线：详见下方图片①")
    lines.append("六、个股卡：详见下方图片②")

    # 七、宏观哨兵
    if snap.get("sentinels_text"):
        lines.append("")
        lines.append("七、宏观哨兵扫描结果")
        lines.extend(snap["sentinels_text"])

    # 附：快讯
    news = (snap.get("news") or [])[:6]
    if news:
        lines.append("")
        lines.append("附：盘前快讯")
        lines.extend("  · %s" % n["title"] for n in news)

    return "\n".join(lines)


def render_pre9_text(snap, verd, picks, slot):
    """09:00 亚太情报正文（保持原有格式）。"""
    ts = snap.get("ts", "")
    lines = ["【每日节奏·%s】%s" % ("09:00亚太", ts), ""]

    # 一、全球市场快照
    lines.append("一、全球市场快照")
    g = snap.get("global") or {}
    for region in ("美股", "亚太", "欧洲"):
        toks = []
        for sid, meta in GLOBAL_IDX.items():
            if meta[1] != region:
                continue
            it = g.get(sid)
            if it and it.get("pct") is not None:
                toks.append("%s %s" % (meta[0], _fmt_pct(it["pct"])))
        if toks:
            lines.append("  " + " | ".join(toks))
    us = snap.get("us_close") or []
    if us:
        lines.append("  美股收盘: " + " | ".join(
            "%s %s(%s)" % (r["name"], _fmt_pct(r["pct"]), _fmt_num(r["close"]))
            for r in us))
    cmd = snap.get("commodity") or {}
    ctoks = []
    for cn in ("WTI原油", "COMEX黄金", "在岸人民币"):
        it = cmd.get(cn)
        if it and it.get("price"):
            s = "%s %s" % (cn, _fmt_num(it["price"]))
            if it.get("pct") is not None:
                s += "(%s)" % _fmt_pct(it["pct"])
            ctoks.append(s)
    if ctoks:
        lines.append("  商品汇率: " + " | ".join(ctoks))
    kr = snap.get("kr_stocks") or []
    if kr:
        lines.append("  韩国权重股: " + " | ".join(
            "%s %s" % (r["name"], _fmt_pct(r["pct"])) for r in kr[:5]))

    # 二、利好利空板块
    lines.append("")
    lines.append("二、利好 / 利空板块判定")
    for v in verd:
        mark = "🔴" if v["side"] == "利空" else "🟢"
        lines.append("  %s[%s]%s %s" % (mark, v["strength"], v["board"], v["logic"]))
        lines.append("      触发: %s ｜ 证伪: %s" % (v["trigger"], v["falsify"]))

    # 三、候选标的
    lines.append("")
    lines.append("三、候选标的（%d 只，六维过滤后）" % len(picks))
    for i, p in enumerate(picks, 1):
        lines.append("  %d) %s(%s) %s｜PE%s PB%s 市值%.0f亿 20日%s 距60高%s %s" % (
            i, p["name"], p["secid"][2:], p["board"], _fmt_num(p["pe"], 1),
            _fmt_num(p["pb"], 2), p["mcap"], _fmt_pct(p["chg20"]),
            _fmt_pct(p["dd60"]), p["shape"]))

    # 四、宏观哨兵
    if snap.get("sentinels_text"):
        lines.append("")
        lines.extend(snap["sentinels_text"])

    # 五、快讯
    news = (snap.get("news") or [])[:6]
    if news:
        lines.append("")
        lines.append("四、盘前快讯")
        lines.extend("  · %s" % n["title"] for n in news)

    return "\n".join(lines)


def render_pick_table(picks, out_path, title, sec_title="候选标的"):
    """候选标的 → PNG 表格。失败返回 None。"""
    try:
        import _table_img as ti
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        header = ["名称", "板块", "现价", "PE", "PB", "市值(亿)", "5日%", "20日%", "距60高%", "形态"]
        rows = []
        for p in picks:
            rows.append([p.get("name", "—"), p.get("board", "—"), _fmt_num(p.get("price")),
                         _fmt_num(p.get("pe"), 1), _fmt_num(p.get("pb"), 2),
                         _fmt_num(p.get("mcap"), 0), _fmt_pct(p.get("chg5")),
                         _fmt_pct(p.get("chg20")), _fmt_pct(p.get("dd60")),
                         p.get("shape") or "—"])
        if not rows:
            return None
        ti.render_multi_table(title, [{"title": sec_title, "header": header, "rows": rows}],
                              out_path, note="六维过滤：市值40-6000亿 / PB≤3.2 / PE≤45 / 20日涨幅<50% / 非空头 / 距60高>-20%")
        return out_path
    except Exception as e:  # noqa: BLE001
        print("候选表格渲染失败: %s" % type(e).__name__)
        return None


def render_sector_tables(verd, picks, out_dir, tag, title):
    """08:00 板块情报两张表：图① 利好/利空/证伪线，图② 个股卡。返回 (png1, png2)。"""
    import _table_img as ti
    os.makedirs(out_dir, exist_ok=True)
    secs = []
    bh = ["强度", "板块", "核心逻辑", "触发信号"]
    bulls = [v for v in verd if v.get("side") == "利好"]
    bears = [v for v in verd if v.get("side") == "利空"]
    fals = [v for v in verd if v.get("falsify")]
    if bulls:
        secs.append({"title": "三、利好板块", "header": bh, "rows": [
            [v.get("strength", "—"), v.get("board", "—"),
             v.get("logic", "—"), v.get("trigger", "—")] for v in bulls]})
    if bears:
        secs.append({"title": "四、利空板块", "header": bh, "rows": [
            [v.get("strength", "—"), v.get("board", "—"),
             v.get("logic", "—"), v.get("trigger", "—")] for v in bears]})
    if fals:
        secs.append({"title": "五、证伪线", "header": ["方向", "强度", "板块", "证伪线"], "rows": [
            [v.get("side", "—"), v.get("strength", "—"),
             v.get("board", "—"), v.get("falsify", "—")] for v in fals]})
    png1 = None
    if secs:
        png1 = os.path.join(out_dir, "sector_%s.png" % tag)
        ti.render_multi_table(title, secs, png1,
                              note="利好/利空由隔夜外盘+商品+汇率规则推导；证伪线用于次日复盘核验")
    png2 = None
    if picks:
        png2 = render_pick_table(picks, os.path.join(out_dir, "cards_%s.png" % tag),
                                 title, sec_title="六、个股卡")
    return png1, png2


def render_board_table(verd, out_path, title):
    """利好利空板块 → PNG 表格。"""
    try:
        import _table_img as ti
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        header = ["方向", "强度", "板块", "核心逻辑", "触发信号", "证伪线"]
        rows = [[v["side"], v["strength"], v["board"], v["logic"], v["trigger"], v["falsify"]]
                for v in verd]
        if not rows:
            return None
        ti.render_multi_table(title, [{"title": "板块判定", "header": header, "rows": rows}],
                              out_path)
        return out_path
    except Exception as e:  # noqa: BLE001
        print("板块表格渲染失败: %s" % type(e).__name__)
        return None


# ────────────────────────── 落库 + 推送 ──────────────────────────
def _save_json_intel(snap, verd, picks, content, slot):
    """写 _daily_intel.json（供盘中轮正文前导 _intel_lines 展示）。"""
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        with open(INTEL_JSON, "w", encoding="utf-8") as f:
            json.dump({"ts": snap.get("ts"), "slot": slot,
                       "boards": [{k: v[k] for k in ("board", "side", "strength", "logic",
                                                     "trigger", "falsify")} for v in verd],
                       "picks": [{k: p.get(k) for k in
                                  ("secid", "name", "board", "price", "pct")} for p in picks],
                       "content": content}, f, ensure_ascii=False, indent=1)
    except OSError as e:
        print("_daily_intel.json 写入失败: %s" % e)


def run_slot(slot, dry=False, log=print):
    """执行一个情报时段：采集 → 判定 → 选股 → 推送 → 存库。"""
    t0 = time.time()
    log("[%s] %s 情报采集…" % (datetime.datetime.now().strftime("%H:%M:%S"), slot))
    snap = snapshot(slot)
    verd = verdicts(snap)
    picks = screen(verd, log=log)
    content = render_text(snap, verd, picks, slot)
    title = "📡 %s情报 %s" % ("盘前08:00" if slot == "pre8" else "盘前09:00亚太",
                              snap.get("ts", "")[5:16])
    if slot == "pre8":
        title = "📡 板块情报 %s" % snap.get("ts", "")[5:16]

    # 表格 PNG（板块 + 候选），失败自动降级为纯文本
    tag = "%s_%s" % (datetime.date.today().strftime("%Y%m%d"),
                     datetime.datetime.now().strftime("%H%M"))
    if slot == "pre8":
        png_board, png_pick = render_sector_tables(verd, picks, TABLE_DIR, tag, title)
    else:
        png_board = render_board_table(verd, os.path.join(TABLE_DIR, "_board_%s.png" % tag), title)
        png_pick = render_pick_table(picks, os.path.join(TABLE_DIR, "_picks_%s.png" % tag), title)

    pushed = False
    err = ""
    if dry:
        log("[dry] %s 预览：\n%s" % (slot, content))
    else:
        cfg = load_notify_cfg()
        try:
            ok = bool(push_channel.push(title, content, cfg))
            if png_board:
                ok = bool(push_channel.send_image(png_board, cfg)) and ok
            if png_pick:
                ok = bool(push_channel.send_image(png_pick, cfg)) and ok
            pushed = ok
        except Exception as e:  # noqa: BLE001
            err = repr(e)
            log("推送失败：%s" % e)

    # 存库（dry 只渲染不写账本，避免污染复盘「板块判定对错」核对）
    if not dry:
        try:
            conn = mdb.get_conn()
            mdb.save_intel_report(conn, slot=slot, title=title,
                                  digest="利好%d/利空%d，候选%d只" % (
                                      sum(1 for v in verd if v["side"] == "利好"),
                                      sum(1 for v in verd if v["side"] == "利空"), len(picks)),
                                  macro=snap, sectors=verd, picks=picks,
                                  news=snap.get("news"), content=content, pushed=pushed)
            mdb.save_push_record(conn, slot=slot, kind="intel", title=title,
                                 codes=[p["secid"] for p in picks], ok=pushed, err=err,
                                 content=content)
            conn.close()
        except Exception as e:  # noqa: BLE001
            log("存库失败：%s" % e)

    _save_json_intel(snap, verd, picks, content, slot)
    log("[%s] %s 完成，候选%d只，推送%s（耗时%.0fs）" % (
        datetime.datetime.now().strftime("%H:%M:%S"), slot, len(picks),
        "成功" if pushed else ("跳过" if dry else "失败"), time.time() - t0))
    return picks


# ────────────────────────── 15:20 表格化复盘 ──────────────────────────
def _read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def _recent_ledgers(n=5):
    """读最近 n 个交易日的选股账本。"""
    rows = []
    try:
        with open(LEDGER, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        rows.append(json.loads(line))
                    except ValueError:
                        continue
    except OSError:
        return []
    return rows[-n:]


def review_sections(log=print, trade_date=None):
    """构建复盘四组表（2026-09-12 用户确认顺序）：

    a. 当日选股（复用盘中同一构建器 → 5 段 / 统一 _TABLE_HEAD 16 列，盘中盘后同口径）
    b. 实仓镜像（与候选完全同一套 16 列；「建议/盈亏%」并入备注格）
    c. 板块判定对错核对（原格式）
    d. 近5日信号票巡诊（原格式）

    返回 section 列表（{"title", "rows"} 或 {"title", "header"+"body"}）；由
    `_table_csv` 统一「填充到一份 CSV → 出一张长图」并原样推送 CSV。
    """
    today = trade_date or datetime.date.today().isoformat()
    sections = []

    # 复用盘中的候选数据/上下文（a、b 两段共用，避免重复抓取）
    pc = data = cache = ctx = None
    try:
        import _publish_candidates as _pc
        pc = _pc
        cache = pc.load_cache()
        ctx = pc.build_context()
        data = pc.build_candidates(cache, pc.build_industry(), ctx=ctx)
    except Exception as e:  # noqa: BLE001
        log("复盘：候选数据构建失败 %s" % e)

    # ── a. 当日选股（与盘中同一构建器 → 口径完全一致，不再另起一套汇总表）──
    if pc is not None:
        try:
            dag = _read_json(DAG_SCREEN)
            cand_secs, _ = pc._build_candidate_sections(
                data or {}, ctx, cache, dag, set(), (data or {}).get("asof", ""))
            for i, s in enumerate(cand_secs, 1):
                sections.append({"title": "a%d. %s" % (i, s.get("title") or ""),
                                 "header": s.get("header"), "rows": s.get("rows"),
                                 "body": s.get("body")})
        except Exception as e:  # noqa: BLE001
            log("复盘：当日选股段构建失败 %s" % e)
            sections.append({"title": "a. 当日选股复盘", "header": ["提示"],
                             "body": [["构建失败：%s" % e]]})

    # ── b. 实仓镜像（与候选同口径技术列）──
    pos_secs = []
    if pc is not None and data:
        try:
            pos_secs = pc._pos_img_sections(data, cache)
        except Exception as e:  # noqa: BLE001
            log("复盘：实仓镜像构建失败 %s" % e)
    if pos_secs:
        for i, s in enumerate(pos_secs, 1):
            sections.append({"title": "b%d. %s" % (i, s.get("title") or "实仓镜像"),
                             "header": s.get("header"), "body": s.get("body")})
    else:
        sections.append({"title": "b. 实仓镜像复盘与应对", "header": ["名称", "代码", "状态", "盈亏", "应对/提示"],
                         "body": [["—", "—", "—", "—", "今日无实仓记录"]]})

    # ── c. 板块判定对错：情报判定 vs 当日实际板块表现（原格式）──
    rows_c1 = []
    try:
        conn = mdb.get_conn()
        recs = mdb.query_intel_report(conn, trade_date=today, limit=10)
        conn.close()
    except Exception as e:  # noqa: BLE001
        recs = []
        log("复盘：情报读取失败 %s" % e)
    # 板块指数代理：用板块内权重股当日涨跌均值近似
    checked = {}
    for rec in recs:
        for v in (rec.get("sectors") or []):
            key = (v.get("board"), v.get("side"))
            if key in checked or len(checked) >= 12:
                continue
            checked[key] = True
            pool = BOARD_POOL.get(v.get("board")) or []
            if not pool:
                continue
            qs = quotes([(c, n, v.get("board")) for c, n in pool])
            pcts = [q.get("pct") for q in qs.values() if q and q.get("pct") is not None]
            if not pcts:
                continue
            avg = sum(pcts) / len(pcts)
            hit = (v.get("side") == "利好" and avg > 0) or (v.get("side") == "利空" and avg < 0)
            rows_c1.append([v.get("side"), v.get("strength"), v.get("board"),
                            "%.2f%%" % avg, "√ 命中" if hit else "× 未兑现",
                            (rec.get("created_at") or "")[11:16]])
    if rows_c1:
        sections.append({"title": "c. 板块判定对错核对",
                         "header": ["方向", "强度", "板块", "板块均涨", "结论", "判定时间"],
                         "body": rows_c1})

    # ── d. 近5个交易日信号票巡诊（原格式）──
    rows_d = []
    ledgers = _recent_ledgers(5)
    latest = {}
    for lg in ledgers:
        for p in (lg.get("picks") or []):
            sid = p.get("secid")
            if not sid:
                continue
            prev = latest.get(sid)
            if prev is None or (lg.get("asof") or "") > (prev.get("asof") or ""):
                latest[sid] = {"name": p.get("name"), "period": p.get("period"),
                               "asof": lg.get("asof"), "close": p.get("close"),
                               "src": p.get("src")}
    if latest:
        qs = quotes([(sid, v["name"] or sid, "巡诊") for sid, v in latest.items()])
        for sid, v in latest.items():
            q = qs.get(sid) or {}
            cur = q.get("price")
            base = v.get("close")
            ret = ((cur / base - 1) * 100) if (cur and base) else None
            rows_d.append([v.get("name") or sid, sid[2:], v.get("period") or "—",
                           v.get("asof") or "—", _fmt_num(base), _fmt_num(cur),
                           _fmt_pct(ret), (v.get("src") or "—")[:12]])
        rows_d.sort(key=lambda r: -(_f(r[6].replace("%", "").replace("+", ""), -99) or -99))
    if rows_d:
        sections.append({"title": "d. 近5日信号票巡诊（持有中标的今日表现）",
                         "header": ["名称", "代码", "周期", "入选日", "入选价", "现价", "至今涨跌", "来源"],
                         "body": rows_d[:20]})
    return sections


def _last_trade_date():
    """取最近交易日（从候选数据 asof 推断），失败回退今天。"""
    try:
        import _publish_candidates as pc
        data = pc.build_candidates(pc.load_cache(), pc.build_industry(),
                                   ctx=pc.build_context())
        asof = data.get("asof")
        if asof:
            return asof
    except Exception:  # noqa: BLE001
        pass
    return datetime.date.today().isoformat()


def push_review(dry=False, log=print):
    """15:20 表格化复盘：各段先「填充到一份 CSV」，再由该 CSV 统一渲染成一张长图，
    推送「正文 + 长图 + 原始 CSV」，并存库（2026-09-12 用户方案）。"""
    trade_date = _last_trade_date()
    sections = review_sections(log=log, trade_date=trade_date)
    title = "📋 收盘复盘 %s" % trade_date
    os.makedirs(TABLE_DIR, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    # 2026-09-13 用户要求：复盘产出用「收盘复盘_时间」命名（旧名 _review_<ts> 无语义）
    csv_path = os.path.join(TABLE_DIR, "收盘复盘_%s.csv" % ts)
    png_path = os.path.join(TABLE_DIR, "收盘复盘_%s.png" % ts)
    xlsx_path = os.path.join(TABLE_DIR, "收盘复盘_%s.xlsx" % ts)
    csv_out, png, xlsx_out = None, None, None
    try:
        import _table_csv as tcsv
        import _publish_candidates as pc
        csv_out, png = tcsv.export(
            csv_path, png_path, title, sections, pc._TABLE_HEAD,
            note="统一口径：a 当日选股（与盘中同 5 段 / 16 列）· b 实仓镜像（同 16 列，"
                 "建议/盈亏在备注格）· c 板块判定对错 · d 近5日信号票巡诊 ｜ 各表标题与表头各自保留"
                 " · 趋势图谱/星后形态/趋势图三列分列（区别于曾经的合并口径）")
    except Exception as e:  # noqa: BLE001
        log("复盘表格 CSV/长图失败（回退纯文本）：%s" % e)
    try:
        import _table_xlsx as txlsx
        import _publish_candidates as pc
        xlsx_out = txlsx.write_xlsx(xlsx_path, sections, pc._TABLE_HEAD)
    except Exception as e:  # noqa: BLE001
        log("复盘 XLSX 生成失败：%s" % e)

    brief = ["（表格见下方合并长图 / 原始 CSV，两者内容一致）", ""]
    for s in sections:
        brief.append("· %s：%d 行" % (s.get("title") or "",
                                     len(s.get("rows") or s.get("body") or [])))
    brief.append("")
    brief.append("口径：a 当日选股与盘中同一构建器（主线DAG / smalltool / ETF全行业扫描 / "
                 "ETF top5 / ETF当日）；b 实仓镜像逐笔建议 + 组合纪律；c 用板块内权重股当日均涨"
                 "核对情报判定；d 近5日信号票按入选价对现价算至今涨跌。")
    brief.append("💬 复盘是为了下一次更准，不是为了后悔。按纪律走，把仓位留给确定性 👊")
    content = "\n".join(brief)

    pushed, err = False, ""
    if dry:
        log("[dry] 复盘预览：\n%s" % content)
    else:
        cfg = load_notify_cfg()
        try:
            pushed = bool(push_channel.push(title, content, cfg))
            if png:
                pushed = bool(push_channel.send_image(png, cfg)) and pushed
            if xlsx_out and os.path.isfile(xlsx_out):
                pushed = bool(push_channel.send_file(xlsx_out, cfg)) and pushed
            if csv_out:
                pushed = bool(push_channel.send_file(csv_out, cfg)) and pushed
        except Exception as e:  # noqa: BLE001
            err = repr(e)
            log("复盘推送失败：%s" % e)

    # 存库（dry 只渲染不写账本）
    if not dry:
        try:
            conn = mdb.get_conn()
            mdb.save_intel_report(conn, slot="review", title=title,
                                  digest="%d 段表格" % len(sections),
                                  sectors=[], picks=[], news=[],
                                  content=content, pushed=pushed)
            mdb.save_push_record(conn, slot="eod", kind="review", title=title,
                                 ok=pushed, err=err, content=content)
            conn.close()
        except Exception as e:  # noqa: BLE001
            log("复盘存库失败：%s" % e)
    log("✓ 复盘完成（%d 段），推送%s" % (len(sections), "成功" if pushed else
                                    ("跳过" if dry else "失败")))
    return png


# ────────────────────────────── 入口 ──────────────────────────────
def slot_now(now=None):
    """按当前时间自动选段。"""
    now = now or datetime.datetime.now()
    if now.weekday() >= 5:
        return None
    hm = now.hour * 60 + now.minute
    if 7 * 60 + 45 <= hm < 8 * 60 + 30:
        return "pre8"
    if 8 * 60 + 45 <= hm < 9 * 60 + 30:
        return "pre9"
    if hm >= 15 * 60 + 15:
        return "review"
    return None


def main():
    ap = argparse.ArgumentParser(description="每日节奏情报（08:00 / 09:00 / 15:20 复盘）")
    ap.add_argument("--slot", default="auto",
                    choices=["auto", "pre8", "pre9", "review"])
    ap.add_argument("--dry", action="store_true", help="不推送（调试）")
    args = ap.parse_args()
    slot = args.slot if args.slot != "auto" else slot_now()
    if not slot:
        print("当前非情报时段（工作日 07:45-08:30 / 08:45-09:30 / 15:15 之后）")
        return 0
    if slot == "review":
        push_review(dry=args.dry)
    else:
        run_slot(slot, dry=args.dry)
    return 0


if __name__ == "__main__":
    sys.exit(main())
