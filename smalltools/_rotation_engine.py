# -*- coding: utf-8 -*-
"""板块动量轮动引擎 —— 捕捉「连续放量上涨」型轮动行情。

背景（8月拟合结论）：
  现有策略（均线粘合+突破）只适配「横盘→突破」形态（光通信/PCB/液冷），
  对轮动板块（农业种植/创新药/煤炭）8月零信号——因为它们是「板块动量驱动的
  连续放量上涨」，个股不存在粘合形态。
  而「板块20日动量转正」信号能提前捕捉：
    - 创新药  8/4  板块20日+7.15% → 药明+19.5% 智飞+13.8%（后续+27.5%/+16.3%）
    - 煤炭    8/10 板块20日+3.97% → 平煤+24.5% 淮北+22.2%
    - 农业种植 8/14 板块20日+6.94% → 敦煌+7.5% 万向德农+6.9%（后续+21%/+45.9%）

本引擎：
  1. 按东财行业聚合板块，计算板块近 N 日平均动量（个股涨幅均值）
  2. 板块动量 > 阈值 → 轮动活跃板块，进入候选
  3. 板块内按 个股动量 + 量比 打分，取龙头 TopK
  4. 支持催化剂权重（消息面/政策/资金/基本面标签），人工注入轮动预期

用法：
  python _rotation_engine.py                  # 扫描当前(最新)轮动板块
  python _rotation_engine.py --asof 2026-08-14
  python _rotation_engine.py --min-mom 2 --top 5 --json
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _industry_map import build_industry  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "_kline_cache.json")
EXTRA = os.path.join(HERE, "_aug_extra.json")

DEFAULT_WINDOW = 20
DEFAULT_MIN_MOM = 2.0     # 板块近20日平均涨幅(%)，低于此值不算轮动活跃
DEFAULT_TOP = 5           # 轮动活跃板块数
LEADER_TOP = 3            # 每板块龙头数
MIN_STOCKS = 3            # 板块至少 N 只样本才算有效

# 催化剂标签：板块名 → (权重加成, 理由)。人工/消息面注入轮动预期。
CATALYSTS = {
    "煤炭行业": (4.0, "9月用电需求+高股息防御，机构回补"),
    "种植业与林业": (4.0, "种业政策催化+粮食安全，8月下旬启动"),
    "农业种植": (4.0, "种业政策催化+粮食安全，8月下旬启动"),
    "化学制药": (3.0, "创新药出海授权+医保谈判预期"),
    "生物制品": (3.0, "创新药出海授权+医保谈判预期"),
    "医疗服务": (2.0, "医药复苏+政策支持"),
    "半导体": (1.0, "国产替代+AI算力需求（全球加息杀估值，降权）"),
    "通信设备": (1.0, "AI光通信产业趋势（全球加息杀估值，降权）"),
    "元件": (1.0, "PCB/AI硬件需求"),
    "油运": (5.0, "红海/霍尔木兹断航→运距拉长、运价飙升"),
    "航运港口": (4.0, "地缘断航→运价与港口周转受益"),
    "石油行业": (4.0, "油价破百→上游资源重估"),
    "燃气": (2.0, "能源替代+冬季需求"),
    "航天航空": (2.0, "地缘紧张→军工订单与避险配置"),
    "贵金属": (2.0, "避险+全球加息后的滞胀预期"),
    "银行": (3.0, "全球加息→息差改善、类债高股息"),
}

# 宏观环境因子（可被 --macro-* 参数覆盖，后续引用保持统一口径）：
#   美债利率高 + 美元信用下调 + 中国国债收益率低 → 全球资金避险、人民币资产受青睐，
#   高股息类债资产（银行/煤炭/化工/电力/保险）性价比凸显 → 中长线埋伏高股息。
#   此配置同时写入了 APK app_config.json 的 macro_environment 块，保持两端一致。
MACRO_ENV = {
    "us_10y_yield_pct": 4.6,     # 美国 10Y 国债收益率（%）
    "us_10y_yield_high": 4.0,    # 超过该值视为"美债利率高"
    "usd_credit_weaken": True,   # 美元信用是否下调
    "cn_10y_yield_pct": 1.7,     # 中国 10Y 国债收益率（%）
    "cn_10y_yield_low": 2.2,     # 低于该值视为"国内利率低 → 高股息类债资产占优"
    "high_dividend_prefer": True,
    "oil_price": 102,            # 当前油价（美元/桶）
    "oil_price_high": 85,        # 高于该值视为"油价高"
    "oil_trend_rising": True,    # 油价是否处于上涨趋势
    "global_hiking": True,       # 全球加息周期（美/欧/日同步收紧）
    "geopolitical_blockade": True,  # 红海/霍尔木兹断航等地缘供给冲击
}

# 油价>85 且上涨趋势 → 化工产业链板块（选股核心中化工股低位埋伏优先）
OIL_HIGH_SECTORS = ["化学制品", "化工", "石油", "化纤", "化肥"]


def oil_high():
    """油价是否处于高位（>85美元/桶 且上涨趋势，化工景气强信号）"""
    env = MACRO_ENV
    return env["oil_price"] > env["oil_price_high"] and env.get("oil_trend_rising", True)

# 高股息类债板块（美债利率高+美元信用弱+国内利率低时中长线埋伏）
HIGH_DIVIDEND_SECTORS = ["银行", "煤炭行业", "化学制品", "化工", "电力", "保险",
                         "石油", "高速公路", "贵金属", "公用事业"]


def macro_div_pref():
    """宏观环境是否支持高股息偏好（两端配置一致）"""
    env = MACRO_ENV
    return (env["high_dividend_prefer"]
            and env["usd_credit_weaken"]
            and env["us_10y_yield_pct"] >= env["us_10y_yield_high"]
            and env["cn_10y_yield_pct"] <= env["cn_10y_yield_low"])

# ══════════════ 重大宏观 / 地缘事件 → 板块加减分（2026-09-11 新增）══════════════
# 设计：事件「配方」内置在引擎里（保证离线可用、口径可复现），
# 每日新闻自动化（automation）把当天搜到的重大新闻写成 data/_daily_macro_news.json，
# 引擎读取后按事件名匹配配方、叠加自定义 sectors，实现「新闻 → 板块加分」闭环。
NEWS_FILE = os.path.join(HERE, "data", "_daily_macro_news.json")

MACRO_EVENT_RECIPES = {
    "霍尔木兹封锁": {
        "sectors": {"航运港口": 6.0, "石油行业": 4.0, "航天航空": 3.0,
                    "贵金属": 2.0, "化学制品": 1.0, "航空": -3.0},
        "note": "海峡/红海断航→运距拉长运价飙升、油价避险溢价、军工避险",
    },
    "红海断航": {
        "sectors": {"航运港口": 5.0, "石油行业": 2.0, "贵金属": 1.0},
        "note": "红海航线绕行好望角→集装箱/油运运价与运距双升",
    },
    "油价破百": {
        "sectors": {"石油行业": 5.0, "航运港口": 2.0, "煤炭行业": 3.0,
                    "化学制品": 3.0, "燃气": 2.0},
        "note": "油价高位→上游资源重估、煤化工/气替代受益、下游成本承压",
    },
    "全球加息周期": {
        "sectors": {"银行": 4.0, "保险": 3.0, "煤炭行业": 2.0, "电力": 1.0,
                    "贵金属": -1.0, "半导体": -2.0, "生物制品": -2.0,
                    "元件": -2.0, "通信设备": -2.0},
        "note": "美/欧/日同步加息→贴现率上行杀高估值成长，利好银行息差与类债高股息",
    },
}

# 当前生效的内置事件（自动化/人工维护；新闻文件可追加同名事件并按板块叠加）
ACTIVE_MACRO_EVENTS = ["霍尔木兹封锁", "油价破百", "全球加息周期"]


def load_news_events():
    """读取每日新闻自动化产出 → {事件名: {"sectors": {板块: 分}, "note": 说明}}。"""
    if not os.path.exists(NEWS_FILE):
        return {}
    try:
        j = json.load(open(NEWS_FILE, encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}
    out = {}
    for ev in (j.get("events") or []):
        name = (ev.get("name") or "").strip()
        if name:
            out[name] = {"sectors": ev.get("sectors") or {}, "note": ev.get("note") or ""}
    return out


def active_events():
    """合并「内置生效事件」与「当日新闻事件」（同名则板块加分叠加）。"""
    ev = {k: {"sectors": dict(v["sectors"]), "note": v["note"]}
          for k, v in MACRO_EVENT_RECIPES.items() if k in ACTIVE_MACRO_EVENTS}
    for name, v in load_news_events().items():
        base = ev.setdefault(name, {"sectors": {}, "note": ""})
        for sector, sc in (v.get("sectors") or {}).items():
            base["sectors"][sector] = base["sectors"].get(sector, 0.0) + float(sc)
        if v.get("note") and not base.get("note"):
            base["note"] = v["note"]
    return ev


def macro_event_bonus(ind_group, ind_name, events):
    """板块 → (事件加分, 命中事件说明)。

    同时匹配「显式板块键名」(如 油运) 与「东财行业名」(如 航运港口)，
    这样新闻自动化写 `航运港口` 或 `油运` 都能命中。
    """
    bonus, reasons = 0.0, []
    for name, v in events.items():
        sc = 0.0
        for key in (ind_group, ind_name):
            val = v["sectors"].get(key)
            if val:
                sc = float(val)
                break
        if sc:
            bonus += sc
            reasons.append("%s%+.0f" % (name, sc))
    return bonus, "；".join(reasons)


# 显式轮动板块成分表：板块名 → 成分股 secid。
# 行业映射(CSV/静态表)覆盖不到的轮动板块在此固化，引擎直接按成分股聚合动量。
ROTATION_SECTORS = {
    "农业种植": ["sh601952", "sh600598", "sz000998", "sz002041",
                 "sh600354", "sz002385", "sh600371", "sh600359"],
    "煤炭行业": ["sh601001", "sh600985", "sh601898", "sh601666"],
    "创新药": ["sh603259", "sh600276", "sz300122", "sz300142", "sz000661", "sh688235"],
    "光通信": ["sz300308", "sz300502", "sh600487", "sh600105", "sz002281", "sh601869"],
    "黄金贵金属": ["sh600547", "sh600489", "sh600988", "sz002155", "sz000975"],
    # ↓ 2026-09-11 新增：地缘断航 / 油价破百 → 油运航运、石油、军工的受益板块
    "油运": ["sh601919", "sh600026", "sh601872", "sh601975", "sh601866"],
    "石油行业": ["sh601857", "sh600028", "sh600938", "sh601808", "sh600583"],
    "天然气": ["sh600256", "sh600803", "sz002267", "sh601139", "sz000593"],
    "军工": ["sh600760", "sh600893", "sh600038", "sz002179", "sh600150"],
    "银行": ["sh601398", "sh601288", "sh600036", "sh601166", "sh600016"],
}
# 显式板块 → 东财行业名（用于与行业聚合结果对齐）
ROTATION_SECTOR_IND = {
    "农业种植": "种植业与林业", "煤炭行业": "煤炭行业", "创新药": "生物制品",
    "光通信": "通信设备", "黄金贵金属": "贵金属",
    "油运": "航运港口", "石油行业": "石油行业", "天然气": "燃气",
    "军工": "航天航空", "银行": "银行",
}


def load_cache():
    cache = json.load(open(CACHE, encoding="utf-8"))
    if os.path.exists(EXTRA):
        for code, ent in json.load(open(EXTRA, encoding="utf-8")).items():
            cur = cache.get(code, {}).get("snaps") or []
            new = ent.get("snaps") or []
            # 仅当 extra 数据末端更新于主池时才覆盖（extra 可能是历史回测快照，避免旧数据盖掉新 K 线）
            if not cur or (new and new[-1].get("date", "") > cur[-1].get("date", "")):
                cache[code] = ent
    return cache


def board_of(code):
    if code.startswith("688"):
        return "科创板"
    if code.startswith(("300", "301")):
        return "创业板"
    return "主板"


def momentum(snaps, window=DEFAULT_WINDOW):
    """个股近 window 日动量(%)，数据不足返回 None"""
    if len(snaps) < window + 1:
        return None
    base = snaps[-window - 1]["close"]
    if base <= 0:
        return None
    return (snaps[-1]["close"] / base - 1) * 100


def vol_ratio(snaps):
    """当日量比(当日量/前5日均量)，不足返回 None"""
    if len(snaps) < 6:
        return None
    vols = [s.get("volume") or 0 for s in snaps]
    avg = sum(vols[-6:-1]) / 5
    return vols[-1] / avg if avg > 0 else None


def rotate(cache, asof, industry, window=DEFAULT_WINDOW, min_mom=DEFAULT_MIN_MOM, top=DEFAULT_TOP):
    """返回轮动板块清单 [{industry, sec_mom, catalyst, leaders:[...]}]"""
    events = active_events()
    # 1) 板块聚合动量（截取到 asof 的数据，兼容历史回放）
    agg = {}
    for code, ent in cache.items():
        if code.startswith(("sh000", "sz399")):
            continue
        snaps = ent.get("snaps") or []
        sub = [s for s in snaps if s["date"] <= asof]
        if not sub or sub[-1]["date"] != asof:
            continue
        mom = momentum(sub, window)
        if mom is None:
            continue
        ind = industry.get(code[2:], "其他")
        a = agg.setdefault(ind, {"sum": 0.0, "stocks": []})
        a["sum"] += mom
        a["stocks"].append((mom, code, ent.get("name") or code, sub))
    # 2) 显式板块成分聚合（轮动板块表优先，避免被行业映射缺失吞掉）
    handled = {}
    groups = list(ROTATION_SECTORS.items()) + [(ind, [c for c in a["stocks"]])
                                               for ind, a in agg.items()]
    rows = []
    for ind, stocks in groups:
        explicit = ind in ROTATION_SECTORS
        if explicit:
            stocks = [(code, ent) for code, ent in
                      [(c, cache.get(c, {})) for c in ROTATION_SECTORS[ind]]
                      if ent.get("snaps")]
            # 重新截取到 asof
            prep = []
            for code, ent in stocks:
                sub = [s for s in ent["snaps"] if s["date"] <= asof]
                if not sub or sub[-1]["date"] != asof:
                    continue
                mom = momentum(sub, window)
                if mom is None:
                    continue
                prep.append((mom, code, ent.get("name") or code, sub))
            stocks = prep
        else:
            stocks = [s for s in stocks]  # 已是从 agg 提取的 (mom, code, name, snaps)
        if len(stocks) < MIN_STOCKS:
            continue
        sec_mom = sum(s[0] for s in stocks) / len(stocks)
        cat_bonus, cat_reason = CATALYSTS.get(ind, (0.0, ""))
        ind_name = ROTATION_SECTOR_IND.get(ind, ind)

        # ── 动量加速度：近5日动量 vs 20日均速（>0 加速启动，<0 钝化退潮）──
        m5s = [m for m in (momentum(s[3], 5) for s in stocks) if m is not None]
        sec_mom5 = sum(m5s) / len(m5s) if m5s else 0.0
        accel = round(sec_mom5 - sec_mom / 4.0, 1)
        accel_bonus = round(max(min(accel, 8.0), -8.0) * 0.5, 1)

        # ── 过热惩罚：短线涨幅过大 → 鱼尾风险，降权防接力 ──
        overheat = -6.0 if sec_mom > 25 else (-3.0 if sec_mom > 18 else 0.0)

        # ── 宏观因子：美债高+美元弱+国内利率低 → 高股息板块加分（中长线埋伏）──
        macro_bonus = 0.0
        macro_reason = ""
        if macro_div_pref() and ind in HIGH_DIVIDEND_SECTORS:
            macro_bonus = 4.0
            macro_reason = "宏观:美债高+美元弱+国债低→高股息类债占优"
        if MACRO_ENV.get("global_hiking") and ind in HIGH_DIVIDEND_SECTORS:
            macro_bonus += 2.0
            macro_reason = (macro_reason + "；全球加息→类债资产占优").strip("；")

        # ── 事件因子：地缘断航 / 油价破百 / 全球加息 → 板块加减分 ──
        event_bonus, event_reason = macro_event_bonus(ind, ind_name, events)

        score = sec_mom + cat_bonus + macro_bonus + accel_bonus + overheat + event_bonus
        # 板块内龙头：动量 + 量比
        leaders = []
        for mom, code, nm, snaps in stocks:
            vr = vol_ratio(snaps)
            ld_score = mom + (vr or 0) * 2.0
            leaders.append((ld_score, code, nm, mom, vr))
        leaders.sort(reverse=True)
        rows.append({
            "industry": ind_name,
            "group": ind,
            "explicit": explicit,
            "sec_mom": round(sec_mom, 1),
            "sec_mom5": round(sec_mom5, 1),
            "accel": accel,
            "accel_bonus": accel_bonus,
            "overheat": overheat,
            "event_bonus": round(event_bonus, 1),
            "event_reason": event_reason,
            "catalyst_bonus": round(cat_bonus, 1),
            "catalyst_reason": cat_reason,
            "macro_bonus": round(macro_bonus, 1),
            "macro_reason": macro_reason,
            "score": round(score, 1),
            "count": len(stocks),
            "leaders": [{"secid": c, "name": n, "board": board_of(c[2:]),
                         "mom20": round(m, 1), "vol_ratio": round(v, 2) if v else None,
                         "score": round(s, 1)}
                        for s, c, n, m, v in leaders[:LEADER_TOP]],
        })
    # 去重：显式板块优先，行业聚合重复项剔除
    seen = set()
    dedup = []
    for r in rows:
        key = r["industry"]
        if key in seen:
            continue
        seen.add(key)
        dedup.append(r)
    dedup.sort(key=lambda r: r["score"], reverse=True)
    return dedup[:top]


def main():
    ap = argparse.ArgumentParser(description="板块动量轮动引擎")
    ap.add_argument("--asof", default=None, help="扫描日期，默认最新")
    ap.add_argument("--window", type=int, default=DEFAULT_WINDOW)
    ap.add_argument("--min-mom", type=float, default=DEFAULT_MIN_MOM)
    ap.add_argument("--top", type=int, default=DEFAULT_TOP)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    cache = load_cache()
    industry = build_industry()
    all_dates = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    asof = args.asof or all_dates[-1]
    if asof not in all_dates:
        print("日期无数据:", asof)
        return 1

    rows = rotate(cache, asof, industry, args.window, args.min_mom, args.top)
    if args.json:
        print(json.dumps({"asof": asof, "rotation": rows}, ensure_ascii=False, indent=1))
        return 0
    print("轮动扫描 asof=%s  板块20日动量阈值≥%.1f%%" % (asof, args.min_mom))
    print("宏观环境: 油价%.0f(高) 美债%.1f%%(高) 全球加息=%s 地缘断航=%s 中国10Y国债%.1f%%(低)"
          % (MACRO_ENV["oil_price"], MACRO_ENV["us_10y_yield_pct"],
             MACRO_ENV["global_hiking"], MACRO_ENV["geopolitical_blockade"],
             MACRO_ENV["cn_10y_yield_pct"]))
    evs = active_events()
    if evs:
        print("生效事件: " + " | ".join("%s(%s)" % (k, v["note"] or "") for k, v in evs.items())[:200])
    print("=" * 100)
    for r in rows:
        cat = "  [催化剂 %s]" % r["catalyst_reason"] if r["catalyst_reason"] else ""
        mac = "  [宏观 %s]" % r["macro_reason"] if r["macro_reason"] else ""
        evt = "  [事件 %s]" % r["event_reason"] if r["event_reason"] else ""
        print("\n%s  板块动量%+.1f%%(5日%+.1f 加速%+.1f)  "
              "加分[事件%+.1f/催化%+.1f/宏观%+.1f/加速%+.1f/过热%+.1f] → 总分%.1f  (样本%d)"
              % (r["industry"], r["sec_mom"], r["sec_mom5"], r["accel"],
                 r["event_bonus"], r["catalyst_bonus"], r["macro_bonus"],
                 r["accel_bonus"], r["overheat"], r["score"], r["count"]))
        print("   %s%s%s" % (cat, mac, evt))
        for l in r["leaders"]:
            print("    %-6s %-8s %s  20日%+.1f%%  量比%s" % (
                l["secid"][2:], l["name"], l["board"], l["mom20"], l["vol_ratio"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
