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
    """notify 推送配置（与 _market_scan/_publish_candidates 同源统一入口）。

    2026-09-14 密钥迁移：改存 AutoQuant/cloud_config.json（AES-GCM 加密入库），
    统一入口 = push_channel.load_notify_cfg()（支持从 .enc 内存解密）。
    """
    return push_channel.load_notify_cfg()


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


# ══════════════════════════════════════════════════════════════════════════
# 利率决议/加息事件深度分析卡（2026-09-17 用户需求）
#   用户反馈「对加息的分析有点简单，应该参考历史加息对股市的影响，同时增加
#   预期加息一次还是加息完之后还会再加息，进行多情景分析规划和总结」。
#   触发：快讯/事件标题命中 利率决议/FOMC/议息/加息/降息 关键词时，在
#   08:00 盘前情报 / offhour 宏观警报正文追加本卡：
#   事件定位 → 历史案例 → 路径预期（一次 vs 周期）→ 三情景规划 → 验证清单。
# ══════════════════════════════════════════════════════════════════════════

_RATE_TRIGGER_KW = ("加息", "降息", "利率决议", "FOMC", "议息", "点阵图",
                    "联邦基金利率", "货币政策声明", "鲍威尔", "拉加德", "上调利率", "下调利率")

_RATE_HIST_CASES = (
    ("2015-12~2018-12 美联储加息周期（9次共225bp）",
     "上证累计-27%、创业板更深；风格切向价值/红利/消费；每次落地后1-3个月震荡消化，"
     "周期末段（2018H2）市场提前交易衰退宽松"),
    ("2022-03~2023-07 美联储加息周期（11次共525bp，40年最快）",
     "上证2022年-15%；高估值成长（创业板/纳指）承压最深，煤炭/红利/油气逆势走强；"
     "最后1-2次加息时市场已提前反弹（交易宽松预期）"),
    ("2019-07~10 / 2024-09 美联储预防式降息",
     "成长科技弹性最大；A股/港股风险偏好修复、北向回流、人民币升值"),
    ("历史规律总结（4条）",
     "①首次加息冲击 > 后续每次加息；②对A股是「外资流动性+汇率」间接传导，"
     "人民币贬值期北向流出放大压力；③加息尾声=布局点、首次加息=减仓点；"
     "④滞胀环境（油价90+且加息）→ 能源/红利最强、成长最弱"),
)

# 2026-09-17 量化实证（kline_store 4 大指数实测，详见 docs/fed_hike_a_share_impact.md
# 与 event_library.json → learned_history.fed_rate_hike）：
# C1=2015-12-17→2018-12-19(+225bp)；C2=2022-03-17→2023-07-26(+525bp)。
_RATE_EMPIRICAL = (
    "T+5 首周：四指数 ±1%（利空提前定价，首日勿恐慌割肉）",
    "T+20~T+60 杀估值期：上证 -0.6%(C2)~-19%(C1)，创业板 -8%~-25%（估值越高跌越深）",
    "T+120 半年：C2 沪+1.5%/创-6.0%；C1 沪-19.8%/创-25.9%（关键=国内是否宽松对冲）",
    "T+250 一年：C2 沪+0.9%/创-12.6%；C1 沪-12.8%/创-30.4%",
    "整轮：C1 沪-28.8%/创-55.3%；C2 沪+0.3%/创-19.2%（独立性=国内货币方向）",
    "修复时间：沪 5周(C2)~2.4年(C1)；深 6周~1.8年；创业板 1.8~2.3年（等降息+国内政策共振）",
    "最低点早于末次加息 1~3 个月（市场抢跑末段）",
)

_RATE_PATH_RULES = (
    ("声明含 additional/ongoing/进一步/继续", "加息周期延续，后面还会再加", "情景B"),
    ("声明含 data-dependent/视数据而定/暂停/pause", "本次为单次加息后暂停观察", "情景A"),
    ("单次幅度 ≥50bp", "紧急应对通胀/汇率压力，通常不是最后一次", "情景B"),
    ("年内已连续 ≥2 次加息", "周期中段，市场已部分定价，冲击递减", "B（中性化）"),
    ("通胀(CPI)仍在走高 + 加息", "抗通胀优先，倾向继续加", "情景B"),
    ("加息伴随就业/PMI走弱（衰退信号）", "可能已是「最后一次」，末段特征", "情景A"),
)

_RATE_SCENARIOS = (
    ("情景A：单次加息后暂停（本轮最后一步）", (
        "市场提前交易宽松预期，压制解除 → 超跌成长/科技反弹弹性最大",
        "高股息红利仍是底仓，但相对收益下降（避险溢价回落）",
        "操作：成长+红利哑铃；逢回调布局超跌成长（半导体/创新药/新能源）")),
    ("情景B：加息周期延续（后面还会再加）", (
        "美债利率维持高位 → 北向流出 + 人民币贬值压力延续",
        "高估值成长持续承压；煤炭/银行/红利/油气相对占优",
        "操作：防御为主、缩成长仓位；等「末段信号」（点阵图下修/首次讨论暂停）再转攻")),
    ("情景C：鹰派意外转鸽（声明偏鸽或直接降息）", (
        "风险偏好快速修复，A股港股共振反弹，弹性最大=恒生科技+创业板",
        "人民币升值 → 北向回流 → 白酒/医药/新能源等外资重仓股受益",
        "操作：转进攻，指数ETF + 超跌白马")),
)


def rate_cycle_card(news_titles, snap=None):
    """利率决议深度分析卡（历史案例+路径预期+三情景规划）。无触发返回 []。"""
    titles = [str(t or "") for t in (news_titles or [])]
    hit = [t for t in titles if any(k in t for k in _RATE_TRIGGER_KW)]
    if not hit:
        return []
    txt = " ".join(hit)
    lines = ["", "【利率决议·深度分析】（命中 %d 条相关快讯）" % len(hit)]
    lines.append("  触发: " + hit[0][:70])
    # ── ① 事件定位（多标题混合时按关键词出现次数定方向，避免单条噪音抢先）──
    n_hike = txt.count("加息") + txt.count("上调利率")
    n_cut = txt.count("降息") + txt.count("下调利率")
    if n_hike > n_cut:
        direction = "加息"
    elif n_cut > n_hike:
        direction = "降息"
    elif n_hike > 0:
        direction = "加息/降息信号混合（以点阵图与声明为准）"
    else:
        direction = "利率决议/议息"
    size = next((bp for bp in ("50基点", "50个基点", "25基点", "25个基点") if bp in txt), "")
    who = next((c for c in ("美联储", "欧央行", "欧洲央行", "日央行", "日本央行", "英国央行", "中国央行", "央行")
                if c in txt), "")
    lines.append("  ① 事件定位: %s%s%s" % (who or "主要央行", direction,
                                          ("·" + size) if size else ""))
    # ── ② 历史加息对股市的影响（案例库）──
    lines.append("  ② 历史案例（加息/降息周期对 A 股影响）")
    for nm, concl in _RATE_HIST_CASES:
        lines.append("     · %s" % nm)
        lines.append("       %s" % concl)
    # ── ②b 量化实证（kline_store 4 大指数实测，2026-09-17）──
    lines.append("  ②b 量化实证（4 大指数实测·短期/长期/修复）")
    for ln in _RATE_EMPIRICAL:
        lines.append("     · " + ln)
    # ── ③ 路径预期：一次还是连续 ──
    lines.append("  ③ 路径预期（判断「加一次就停」还是「还会再加」，按声明特征对号入座）")
    for rule, concl, scen in _RATE_PATH_RULES:
        lines.append("     · 若%s → %s（倾向 %s）" % (rule, concl, scen))
    if "暂停" not in txt and "additional" not in txt and "pause" not in txt:
        lines.append("     · 声明细节未在快讯中披露 → 按「周期中段」中性处理，等点阵图/发布会")
    # ── ④ 三情景规划 ──
    lines.append("  ④ 三情景规划与操作")
    for nm, pts in _RATE_SCENARIOS:
        lines.append("     ▸ %s" % nm)
        for p in pts:
            lines.append("       - %s" % p)
    # ── ⑤ 验证清单 ──
    lines.append("  ⑤ 验证清单（下一步盯什么）")
    lines.append("     - 声明措辞（additional=继续加 / data-dependent=暂停）+ 点阵图中值变动")
    lines.append("     - 2年期美债利率（继续上行=市场定价再加；快速回落=末段信号）")
    lines.append("     - 人民币汇率与北向流向（贬值+流出=情景B压力项）")
    us = (snap or {}).get("us_close") or []
    if us:
        lines.append("     - 美股即时反应: " + " | ".join(
            "%s %s" % (r.get("name"), _fmt_pct(r.get("pct"))) for r in us))
    return lines


def collect_news(top=12, authoritative=True, drain_pool=True):
    """财经快讯。

    2026-09-17 用户口径：新闻解析量太大 → 收敛为权威源
    （美国 1财经+1政治+1机构 / 中国 1财经+1政治+1机构，见 _news_watch.collect_authoritative）。

    · authoritative=True（默认）：优先取六类权威条目（5 分钟复用缓存），
      并合并非交易时段 offhour 新闻池（drain_pool=True 时消费即清空）；
      权威源为空时退化为原东财 7x24 全量快讯。
    · drain_pool=False：只读不消费池（供 offhour 哨兵自查，避免把池吃空）。
    """
    if authoritative:
        out = []
        try:
            import _news_watch
            auth = _news_watch.collect_authoritative()
            hm = (auth.get("ts") or "")[11:16]
            for key in ("us_finance", "us_politics", "us_agency",
                        "cn_finance", "cn_politics", "cn_agency"):
                for it in (auth.get(key) or []):
                    # 条目 {title,url,time,src}（2026-09-17）；url 供整理溯源
                    if isinstance(it, dict):
                        out.append({"title": (it.get("title") or "")[:80],
                                    "time": it.get("time") or hm, "src": key,
                                    "url": it.get("url") or ""})
                    else:
                        out.append({"title": str(it)[:80], "time": hm,
                                    "src": key, "url": ""})
        except Exception as e:  # noqa: BLE001
            print("权威源采集失败: %s" % type(e).__name__)
        if drain_pool:
            try:
                import _offhour_watch
                for it in _offhour_watch.drain_news_pool(max_items=40):
                    out.append({"title": (it.get("title") or "")[:80],
                                "time": it.get("time") or "", "src": "offhour_pool",
                                "url": it.get("url") or ""})
            except Exception:  # noqa: BLE001
                pass
        if out:
            return out
    # 兜底：东财 7x24 全量
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
        code = str(it.get("code") or "").strip()
        out.append({"title": (it.get("title") or it.get("summary", ""))[:80],
                    "time": it.get("showTime") or it.get("digestTime") or "",
                    # 原文网页 URL（2026-09-17 用户需求：参考过的消息保留网页方便整理）
                    "url": it.get("uniqueUrl") or (
                        "https://finance.eastmoney.com/a/%s.html" % code if code else "")})
    return out


def snapshot(slot, prefetched=None):
    """一次完整采集（08:00 / 09:00 共用，09:00 额外带亚太实时与韩国权重股）。

    prefetched（2026-09-17 用户口径·节流）：offhour 哨兵已在顶部抓过 news/us_close/commodity_fx，
    调 run_slot("offhour") 时把数据传进来直接复用，避免 4 次重复网络抓取（30s+ 延迟）。
    结构：{"news": [...], "us_close": [...], "commodity": {...}}；缺位字段兜底为单次抓取。
    """
    pf = prefetched or {}
    news = pf.get("news") if pf.get("news") is not None else collect_news()
    us = pf.get("us_close") if pf.get("us_close") is not None else us_close()
    cmd = pf.get("commodity") if pf.get("commodity") is not None else commodity_fx()
    snap = {
        "ts": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "slot": slot,
        "global": east_global(),
        "us_close": us,
        "commodity": cmd,
        "news": news,
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

    # 2026-09-17 用户需求：利率决议（加息/降息/FOMC）命中 → 深度分析卡
    # （历史案例 + 一次还是连续的路径预期 + 三情景规划 + 验证清单）
    try:
        rc = rate_cycle_card([n.get("title") for n in (snap.get("news") or [])], snap)
        if rc:
            lines.extend(rc)
    except Exception as e:  # noqa: BLE001
        print("利率分析卡追加失败:", type(e).__name__, e)

    return "\n".join(lines)


def render_pre9_text(snap, verd, picks, slot):
    """09:00 亚太情报正文（保持原有格式）。offhour 复用此格式，仅换头行。"""
    ts = snap.get("ts", "")
    _head = "盘后宏观警报" if slot == "offhour" else "09:00亚太"
    lines = ["【每日节奏·%s】%s" % (_head, ts), ""]

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

    # 2026-09-17 用户需求：利率决议（加息/降息/FOMC）命中 → 深度分析卡
    try:
        rc = rate_cycle_card([n.get("title") for n in (snap.get("news") or [])], snap)
        if rc:
            lines.extend(rc)
    except Exception as e:  # noqa: BLE001
        print("利率分析卡追加失败:", type(e).__name__, e)

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


def run_slot(slot, dry=False, log=print, prefetched=None):
    """执行一个情报时段：采集 → 判定 → 选股 → 推送 → 存库。

    prefetched（2026-09-17）：复用上游已抓数据，透传给 snapshot()。仅 offhour 哨兵用。
    """
    t0 = time.time()
    log("[%s] %s 情报采集…" % (datetime.datetime.now().strftime("%H:%M:%S"), slot))
    snap = snapshot(slot, prefetched=prefetched)
    verd = verdicts(snap)
    picks = screen(verd, log=log)
    content = render_text(snap, verd, picks, slot)
    # 2026-09-16 用户需求：08:00/09:00 推送末尾加"活跃宏观事件"行（加息/战争/石油/美国资本担忧等）
    try:
        from _publish_candidates import _macro_events_lines
        me = _macro_events_lines(asof=datetime.date.today().strftime("%Y-%m-%d"))
        if me:
            content = content + "\n\n" + "\n".join(me)
    except Exception as e:
        log("[%s] 宏观事件行追加失败: %s" % (slot, e))
    title = "📡 %s情报 %s" % ("盘前08:00" if slot == "pre8" else "盘前09:00亚太",
                              snap.get("ts", "")[5:16])
    if slot == "pre8":
        title = "📡 板块情报 %s" % snap.get("ts", "")[5:16]
    elif slot == "offhour":
        # 2026-09-17 用户需求：非交易时间确定级宏观事件 → 宏观分析+选股+推送
        # （_offhour_watch 触发，与盘前情报同一链路）
        title = "🚨 非交易时间宏观警报 %s" % snap.get("ts", "")[5:16]

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
            # 引擎指纹（2026-09-13）：a 段表头已由 _build_candidate_sections 标 ⚠，但
            # 表头在长图缩略图/CSV 首列里极易被忽略，故再出一条独立提示段（置 a 段之前）。
            _eng = pc._dag_engine_stale(dag)
            if _eng:
                sections.append({"title": "a0. ⚠ 引擎已更新（归档非当前引擎）",
                                 "header": ["归档指纹", "现引擎指纹", "处置"],
                                 "body": [[_eng[0], _eng[1],
                                           "归档由旧版本引擎/XML 产出，结果与设计不符；"
                                           "建议重跑 DAG 后再采纳"]]})
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

    # ── d. 近5个交易日信号票巡诊（2026-09-17 改版：逐日 D+1~D+5）──
    # 旧版只有「至今涨跌」一列，且 ledger 写入端 close 大多为 null（只有部分来源带价）
    # → 入选价/涨跌大面积「—」。现在用腾讯日K按入选日对齐：
    #   · 入选价 base = ledger close（缺则回填入选日K线收盘）；
    #   · D+1~D+5 = 入选日后第 k 个交易日收盘相对入选价的涨跌（未到 → ·）；
    #   · 入选超过 5 个交易日的信号剔除（过期不再巡诊）。
    rows_d = []
    ledgers = _recent_ledgers(6)
    sig = {}
    for lg in ledgers:
        for p in (lg.get("picks") or []):
            sid = p.get("secid")
            if not sid:
                continue
            prev = sig.get(sid)
            if prev is None or (lg.get("asof") or "") > (prev.get("asof") or ""):
                sig[sid] = {"name": p.get("name"), "period": p.get("period"),
                            "asof": lg.get("asof"), "close": p.get("close"),
                            "src": p.get("src")}
    if sig:
        try:
            from backtest_guangmo import fetch_tencent
        except Exception as e:  # noqa: BLE001
            fetch_tencent = None
            log("巡诊：日K模块不可用 %s" % e)
        if fetch_tencent is not None:
            beg = (datetime.date.today() - datetime.timedelta(days=25)).strftime("%Y%m%d")
            end = datetime.date.today().strftime("%Y%m%d")
            for sid, v in sig.items():
                try:
                    _, snaps = fetch_tencent(sid, beg, end)
                except Exception:
                    snaps = []
                kmap = {str(s.get("date"))[:10]: float(s["close"])
                        for s in (snaps or []) if s.get("close")}
                if not kmap:
                    continue                      # 日K拉不到 → 整行跳过（无锚点无法逐日）
                days = sorted(kmap)
                asof = v.get("asof") or ""
                if asof not in kmap:
                    continue                      # 入选日无K线（停牌/超窗）→ 无法对齐
                base = v.get("close") or kmap[asof]
                after = days[days.index(asof) + 1:]
                if len(after) > 5:
                    continue                      # 超过5个交易日 → 剔除
                dcells = []
                for k in range(1, 6):
                    dcells.append("%.1f%%" % ((kmap[after[k - 1]] / base - 1) * 100)
                                  if len(after) >= k else "·")
                rows_d.append([v.get("name") or sid, sid[2:], v.get("period") or "—",
                               asof, _fmt_num(base)] + dcells
                              + [(v.get("src") or "—")[:20]])
            # 排序：按已实现的最远日涨跌降序（同旧版按涨跌排，读者先看最好/最差）
            def _last_pct(r):
                for c in reversed(r[5:10]):
                    if c not in ("·", ""):
                        try:
                            return float(c.replace("%", "").replace("+", ""))
                        except ValueError:
                            return -99.0
                return -99.0
            rows_d.sort(key=lambda r: -_last_pct(r))
    if rows_d:
        sections.append({"title": "d. 近5日信号票巡诊（入选后逐日涨跌，超5日剔除）",
                         "header": ["名称", "代码", "周期", "入选日", "入选价",
                                    "D+1", "D+2", "D+3", "D+4", "D+5", "来源"],
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


# ── 收盘复盘尾句池（2026-09-17 用户需求：不要千篇一律，每天换一句）──
# 风格混排：财经谚语 / 寓言 / 段子 / 无关冷知识；_pick_review_tail 随机且
# 避免最近 10 天内重复（留痕 _records/_review_tails.json，只存最近 50 条）。
_REVIEW_TAILS = [
    "💬 复盘是为了下一次更准，不是为了后悔。按纪律走，把仓位留给确定性 👊",
    "💬 市场永远不缺机会，缺的是机会来临时你还有子弹。",
    "💬 芒格说：如果我知道我会死在哪，我就永远不去那儿。空仓也是一种仓位。",
    "💬 趋势是朋友，但朋友也会翻脸——止损就是给翻脸买的保险。",
    "💬 巴菲特的雪球靠的是很湿的雪和很长的坡，不是每天滚得最快的那个人。",
    "💬 别人恐惧我贪婪，前提是先数清楚自己兜里有几个钢镚儿。",
    "💬 老渔民说：出海看天，收网看汛。行情没到那份上，网补好就行。",
    "💬 猎人大部分时间在等，扣扳机只要一秒。交易难的是那一秒之外的忍耐。",
    "💬 亏钱的单子教会你的，往往比赚钱的多——但学费交一次就该毕业。",
    "💬 利弗莫尔：赚大钱靠的是坐得住，不是频繁折腾。",
    "💬 高手和菜鸟的区别：高手亏小钱，菜鸟亏大钱，速度还更快。",
    "💬 农夫不会因为昨天歉收就把种子全炒了吃。播种和收获不在同一个季节。",
    "💬 行情好的时候猪都能飞，退潮了才知道谁在裸泳——今天检查泳裤了吗。",
    "💬 华尔街最贵的一句话：这次不一样。",
    "💬 短线是别人的游戏，纪律才是自己的护城河。",
    "💬 一位交易员安慰自己：我没亏，只是给市场交了会员费。",
    "💬 散户三大错觉：我能抄底、我能逃顶、这次听我的。复盘就是打醒自己。",
    "💬 龟兔赛跑在股市的版本：乌龟满仓乌龟壳，兔子加杠杆跑得快。",
    "💬 温水煮青蛙的股市版：每天跌一点，你每天都说「再等等」。",
    "💬 索罗斯：重要的不是对错，而是对的时候赚多少、错的时候亏多少。",
    "💬 昨天的价格已经过去，今天的仓位才是唯一能决定的事。",
    "💬 厨师看火候，裁缝看尺寸，交易员看仓位——各凭手艺吃饭。",
    "💬 达利欧的原则：痛苦 + 复盘 = 进步。今天痛不痛不知道，复盘是复盘了。",
    "💬 冷知识：章鱼有三颗心脏。炒股只需要一颗大心脏，可惜大多数人没有。",
    "💬 冷知识：蜂蜜永不变质。好的持仓也一样，不折腾它就不会坏。",
    "💬 冷知识：树懒一天睡 20 小时。它从不追涨杀跌，所以心态比我们都好。",
    "💬 冷知识：光从太阳到地球要 8 分钟。你看到的好消息，市场可能早消化完了。",
    "💬 冷知识：鲨鱼比树还古老。活得久，比长得快重要——仓位的生存哲学。",
    "💬 彼得·林奇：决定投资成败的，不是头脑，而是屁股——坐得住的屁股。",
    "💬 每天收盘后的十分钟复盘，胜过盘中盯四个小时的分时图。",
]
_REVIEW_TAIL_LOG = os.path.join(HERE, "_records", "_review_tails.json")


def _pick_review_tail():
    """随机挑一句复盘尾句，避开最近用过的（留痕去重，失败静默退化随机）。"""
    import random
    used = []
    try:
        with open(_REVIEW_TAIL_LOG, encoding="utf-8") as f:
            used = json.load(f) or []
    except (OSError, ValueError):
        used = []
    pool = [t for t in _REVIEW_TAILS if t not in used[-10:]] or _REVIEW_TAILS
    pick = random.choice(pool)
    try:
        used.append(pick)
        with open(_REVIEW_TAIL_LOG, "w", encoding="utf-8") as f:
            json.dump(used[-50:], f, ensure_ascii=False, indent=1)
    except OSError:
        pass
    return pick


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
        # 合同/市值 列回填（2026-09-15 用户需求）：a/b 段与盘中推送同一套口径，
        # 失败静默 → 整列「—」，不阻塞复盘。
        try:
            pc._annotate_contracts(sections)
        except Exception as e:  # noqa: BLE001
            log("复盘：合同/市值列回填失败（显示—）：%s" % e)
        # 基本面 6 列回填（2026-09-17 修复）：此前 push_review 只回填了合同/市值，
        # 漏调 _annotate_fundamentals → 复盘长图/XLSX 里 PE静/PB/营收%/净利%/ROE%/PEG
        # 整块为「—」（盘中推送有值、复盘没有，口径不一致）。现补上，与盘中同源。
        try:
            pc._annotate_fundamentals(sections)
        except Exception as e:  # noqa: BLE001
            log("复盘：基本面列回填失败（显示—）：%s" % e)
        csv_out, png = tcsv.export(
            csv_path, png_path, title, sections, pc._TABLE_HEAD,
            note="统一口径：a 当日选股（与盘中同 5 段 / 17 列，含合同/市值）· "
                 "b 实仓镜像（同 17 列，建议/盈亏在备注格）· c 板块判定对错 · "
                 "d 近5日信号票巡诊 ｜ 各表标题与表头各自保留"
                 " · 趋势图谱/星后形态/趋势图三列分列（区别于曾经的合并口径）")
    except Exception as e:  # noqa: BLE001
        log("复盘表格 CSV/长图失败（回退纯文本）：%s" % e)
    try:
        import _table_xlsx as txlsx
        import _publish_candidates as pc
        xlsx_out = txlsx.write_xlsx(xlsx_path, sections, pc._TABLE_HEAD)
    except Exception as e:  # noqa: BLE001
        log("复盘 XLSX 生成失败：%s" % e)

    brief = ["（表格见下方合并长图 / XLSX，两者内容一致）", ""]
    for s in sections:
        brief.append("· %s：%d 行" % (s.get("title") or "",
                                     len(s.get("rows") or s.get("body") or [])))
    brief.append("")
    brief.append("口径：a 当日选股与盘中同一构建器（主线DAG / smalltool / ETF全行业扫描 / ETF "
                 "top5 / ETF当日）；b 实仓镜像逐笔建议 + 组合纪律；c 用板块内权重股当日均涨"
                 "核对情报判定；d 近5日信号票按入选价逐日算 D+1~D+5 收盘涨跌（未到为 ·，超5日剔除）。")
    brief.append(_pick_review_tail())
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
            # 2026-09-15 用户要求：XLSX 与 CSV 内容一致，只发 XLSX（CSV 仍落盘，供程序消费）
            if xlsx_out and os.path.isfile(xlsx_out):
                pushed = bool(push_channel.send_file(xlsx_out, cfg)) and pushed
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
