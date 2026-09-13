# -*- coding: utf-8 -*-
"""8 月选股综合报告（临时脚本，用完即删）。

输出：
  A. 本月实际信号（剔除 ST/指数，含板块/市值/行业排名）
  B. 精选清单：信号 + 预备队（板块前10 + 主板5/科创创业5 配额）
  C. 黄金/医药专项核查：不在池内的龙头用腾讯实时K线算是否够格触发
"""
import json
import os
import re
import sys
import time

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _full_cycle_backtest import load_cache  # noqa: E402
from backtest_guangmo import PARAMS, analyze_snaps, fetch_tencent  # noqa: E402
import _walk_forward as wf  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
HEADERS = {"User-Agent": "Mozilla/5.0"}
PROXIES = {"http": None, "https": None}

# ── 1. 股票池 + 行业映射 ──────────────────────────────────────────────
cache = load_cache()
POOL = {k: v for k, v in cache.items()
        if not (k.startswith("sh000") or k.startswith("sz399"))}

# CSV 文件名带板块（49 只）
INDUSTRY = {}
data_dir = os.path.join(HERE, "..", "AutoQuant", "data")
if os.path.isdir(data_dir):
    for fn in os.listdir(data_dir):
        if not fn.endswith(".csv"):
            continue
        m = re.match(r"(\d{6})\.(SH|SZ)_(.+)\.csv", fn)
        if not m:
            continue
        code, _ex, rest = m.groups()
        parts = [p for p in rest.split("-") if p]
        if len(parts) >= 2:
            INDUSTRY[code] = parts[1]  # 格式：名称-板块[-子板块]，取名称后第一段为板块

# 静态补齐（池内其余股票，按东财行业板块命名）
STATIC_INDUSTRY = {
    "600011": "电力", "600025": "电力", "600028": "石油石化", "600089": "电力设备",
    "600101": "电力", "600105": "通信设备", "600111": "小金属", "600117": "钢铁",
    "600131": "电网设备", "600160": "化学制品", "600176": "玻璃玻纤", "600183": "元件",
    "600219": "工业金属", "600312": "电网设备", "600338": "工业金属", "600362": "工业金属",
    "600378": "化学制品", "600392": "小金属", "600406": "电网设备", "600416": "电机",
    "600438": "光伏设备", "600456": "小金属", "600487": "通信设备", "600497": "工业金属",
    "600498": "通信设备", "600517": "电网设备", "600522": "通信设备", "600536": "软件开发",
    "600549": "小金属", "600584": "半导体", "600703": "半导体", "600745": "半导体",
    "600746": "化学制品", "600801": "水泥建材", "600875": "电力设备", "600900": "电力",
    "600973": "电网设备", "601012": "光伏设备", "601138": "消费电子", "601600": "工业金属",
    "601615": "风电设备", "601700": "电网设备", "601869": "通信设备", "601899": "贵金属",
    "603019": "计算机设备", "603160": "半导体", "603228": "元件", "603290": "半导体",
    "603399": "能源金属", "603501": "半导体", "603618": "电网设备", "603650": "化学制品",
    "603690": "半导体", "603799": "能源金属", "603920": "元件", "603989": "元件",
    "603993": "工业金属", "688008": "半导体", "688012": "半导体", "688036": "消费电子",
    "688041": "半导体", "688111": "软件开发", "688256": "半导体", "688313": "通信设备",
    "688396": "半导体", "688521": "半导体", "688981": "半导体", "000063": "通信设备",
    "000545": "化学制品", "000629": "钢铁", "000630": "工业金属", "000657": "小金属",
    "000758": "工业金属", "000807": "工业金属", "000960": "工业金属", "002036": "光学光电子",
    "002049": "半导体", "002055": "消费电子", "002149": "小金属", "002156": "半导体",
    "002168": "电网设备", "002202": "风电设备", "002230": "软件开发", "002281": "通信设备",
    "002339": "电网设备", "002340": "电池", "002371": "半导体", "002372": "装修建材",
    "002384": "元件", "002430": "专用设备", "002459": "光伏设备", "002460": "能源金属",
    "002463": "元件", "002466": "能源金属", "002475": "消费电子", "002579": "元件",
    "002815": "元件", "002902": "元件", "002916": "元件", "300118": "光伏设备",
    "300236": "半导体", "300274": "光伏设备", "300308": "通信设备", "300394": "通信设备",
    "300476": "元件", "300502": "通信设备", "300576": "电子化学品", "300661": "半导体",
    "300750": "电池", "300763": "光伏设备", "300782": "半导体",
    # 2026-08-21 扩池新增：黄金 + 医药
    "600547": "贵金属", "600489": "贵金属", "600988": "贵金属", "002155": "贵金属",
    "000975": "贵金属", "600276": "化学制药", "603259": "医疗服务", "600436": "中药",
    "000538": "中药", "600196": "化学制药", "000661": "生物制品", "300760": "医疗器械",
    "300122": "生物制品", "300142": "生物制品", "688235": "生物制品",
}
# 静态映射统一用东财行业板块名（覆盖 CSV 中的描述式名称，保持口径一致）
INDUSTRY.update({k: v for k, v in STATIC_INDUSTRY.items()})

def code_of(secid):
    return secid[2:]

def board_type(secid):
    c = secid[2:]
    if c.startswith("688"):
        return "科创板"
    if c.startswith("300"):
        return "创业板"
    return "主板"

# ── 2. 腾讯总市值 ──────────────────────────────────────────────────────
def fetch_mktcap(secids):
    out = {}
    codes = [code_of(s) for s in secids]
    for i in range(0, len(codes), 60):
        chunk = codes[i:i + 60]
        for attempt in range(3):
            try:
                r = requests.get("https://qt.gtimg.cn/q=" + ",".join(
                    ("sh" if c.startswith("6") else "sz") + c for c in chunk),
                    timeout=12, headers=HEADERS, proxies=PROXIES)
                r.encoding = "gbk"
                for line in r.text.strip().split(";"):
                    f = line.split("~")
                    if len(f) > 45 and f[2]:
                        out[f[2]] = float(f[45])
                break
            except Exception as e:
                print("  mktcap chunk %d fail: %s" % (i, type(e).__name__), flush=True)
                time.sleep(1)
    return out

all_secids = list(POOL.keys())
mktcap = fetch_mktcap(all_secids)
print("总市值获取 %d/%d 只" % (len(mktcap), len(all_secids)), flush=True)

# ── 3. 本月信号 ────────────────────────────────────────────────────────
sigs = json.load(open(os.path.join(HERE, "_august_sigs.json"), encoding="utf-8"))
all_dates = sorted({d for e in cache.values() for s in e.get("snaps", []) for d in [s["date"]]})
date_to_idx = {d: i for i, d in enumerate(all_dates)}

# ── 4. 当前预备队评分（最新交易日各周期 pass_count） ────────────────────
asof = all_dates[-1]
idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in ["sh000001", "sz399001", "sz399006"]}
st = wf.market_state(idx_snaps, asof, all_dates, date_to_idx)
sel_trend = st if st in ("BULLISH", "BEARISH") else ("BEARISH" if st == "CRASH" else "OSCILLATION")
print("最新交易日 %s | 大盘状态 %s -> sel_trend %s" % (asof, st, sel_trend), flush=True)

score = {}
for code, ent in POOL.items():
    snaps = ent.get("snaps") or []
    if not snaps or snaps[-1]["date"] != asof:
        continue
    sub = [dict(s) for s in snaps]
    sub[-1]["name"] = ent.get("name") or code
    best = None
    for period, p in PARAMS.items():
        try:
            r = analyze_snaps(sub, p, sel_trend)
        except Exception:
            continue
        ratio = r["passCount"] / max(r["totalChecks"], 1)
        if best is None or ratio > best[1]:
            best = (period, ratio, r["passCount"], r["totalChecks"],
                    r.get("drawdownPct"), p.get("minDrawdownPct", 0))
    if best:
        score[code] = best

# ── 5. 报告 ────────────────────────────────────────────────────────────
def show(code, tag, extra=""):
    c = code_of(code)
    nm = POOL[code]["name"]
    ind = INDUSTRY.get(c, "?")
    mc = mktcap.get(c)
    mc_s = ("%.0f亿" % mc) if mc else "?"
    print("  %-4s %-6s %-10s %-8s 市值%s  %s %s" % (
        tag, c, nm, ind, mc_s, extra, ""))
    return {"code": c, "name": nm, "industry": ind, "mktcap": mc,
            "board": board_type(code)}

print("\n===== A. 本月（2026-08）实际信号 =====", flush=True)
rows = []
for period in ["超短", "短线", "中线", "长线"]:
    for s in sigs.get(period, []):
        code = s["code"]
        key = code if code.startswith(("sh", "sz")) else (
            "sh" if code.startswith("6") else "sz") + code
        if key not in POOL:
            continue
        rows.append((period, key, s))
seen = set()
print("\n-- 剔除 ST/杂毛后 --")
for period, key, s in sorted(rows, key=lambda x: x[2]["date"]):
    if "ST" in POOL[key]["name"]:
        print("   [剔除杂毛] %s %s(%s)" % (period, POOL[key]["name"], s["code"]))
        continue
    if key in seen:
        continue
    seen.add(key)
    show(key, period, "信号日%s" % s["date"])

print("\n===== B. 预备队（当前最接近买点，板块前10候选） =====", flush=True)
# 按 pass 占比排序，剔除 ST，按板块内市值排名门控（池内=核心龙头，前10内）
cand = [k for k in score if "ST" not in POOL[k]["name"]]
cand.sort(key=lambda k: (-score[k][1], -(mktcap.get(code_of(k)) or 0)))
print("大盘 %s，阈值参考：超短7/13 短6/13 中6/13 长6/13" % sel_trend)
for k in cand[:20]:
    p, ratio, pc, tc, dd, thr = score[k]
    dd_s = ("回撤%.1f%%(需%.0f%%)" % (dd, thr)) if dd is not None else ""
    print("  %s %s(%s) %s 命中%d/%d  %s" % (
        k, POOL[k]["name"], code_of(k), p, pc, tc, dd_s))

print("\n===== C. 精选清单（板块前10 + 主板5 / 科创创业5，剔除杂毛） =====", flush=True)
# 板块内按市值排名（池内口径）
def industry_rank(code):
    ind = INDUSTRY.get(code_of(code), "?")
    peers = [c for c in POOL if INDUSTRY.get(code_of(c), "?") == ind]
    peers.sort(key=lambda c: -(mktcap.get(code_of(c)) or 0))
    return peers.index(code) + 1, len(peers)

main_picks, gem_picks = [], []
for period in ["超短", "短线", "中线", "长线"]:
    for s in sorted(sigs.get(period, []), key=lambda x: x["date"]):
        code = s["code"]
        key = code if code.startswith(("sh", "sz")) else (
            "sh" if code.startswith("6") else "sz") + code
        if key not in POOL or "ST" in POOL[key]["name"] or key in [x[0] for x in main_picks + gem_picks]:
            continue
        (main_picks if board_type(key) == "主板" else gem_picks).append((key, period, s["date"]))
# 预备队补齐
for k in cand:
    if len(main_picks) >= 5 and len(gem_picks) >= 5:
        break
    if k in [x[0] for x in main_picks + gem_picks]:
        continue
    if board_type(k) == "主板" and len(main_picks) < 5:
        main_picks.append((k, score[k][0], "预备"))
    elif board_type(k) != "主板" and len(gem_picks) < 5:
        gem_picks.append((k, score[k][0], "预备"))

print("-- 主板 5 只 --")
for key, period, note in main_picks:
    rk, tot = industry_rank(key)
    show(key, period, "池内%s内排名%d/%d %s" % (INDUSTRY.get(code_of(key), "?"), rk, tot, note))
print("-- 科创板+创业板 5 只 --")
for key, period, note in gem_picks:
    rk, tot = industry_rank(key)
    show(key, period, "池内%s内排名%d/%d %s" % (INDUSTRY.get(code_of(key), "?"), rk, tot, note))

# ── 5.5 强势板块补选（做拟合通道） ─────────────────────────────────────
print("\n===== E. 强势板块补选（板块强度前10龙头，不要求回调） =====", flush=True)
from collections import defaultdict

def sector_strength():
    agg = defaultdict(list)
    for code, ent in POOL.items():
        snaps = [s for s in (ent.get("snaps") or []) if s["date"] <= asof]
        if len(snaps) < 25:
            continue
        closes = [s["close"] for s in snaps]
        agg[INDUSTRY.get(code_of(code), "?")].append({
            "code": code, "name": ent["name"],
            "r20": (closes[-1] / closes[-21] - 1) * 100,
            "r5": (closes[-1] / closes[-6] - 1) * 100,
            "mc": mktcap.get(code_of(code)) or 0,
        })
    return agg

sectors = sector_strength()
hot = []
for ind, members in sectors.items():
    if len(members) < 2:
        continue
    avg20 = sum(m["r20"] for m in members) / len(members)
    avg5 = sum(m["r5"] for m in members) / len(members)
    hot.append((avg20, avg5, ind, members))
hot.sort(reverse=True)
print("-- 板块近20日平均涨幅 TOP8 --")
for avg20, avg5, ind, members in hot[:8]:
    top = sorted(members, key=lambda m: -m["mc"])[:5]
    print("  %-6s 近20日%.1f%% 近5日%.1f%% | 龙头: %s" % (
        ind, avg20, avg5, " ".join("%s(%.0f亿)" % (m["name"], m["mc"]) for m in top)))
print("-- 黄金/医药板块补选清单（板块前10内市值龙头） --")
for avg20, avg5, ind, members in hot:
    if ind not in ("贵金属", "化学制药", "中药", "医疗服务", "生物制品", "医疗器械"):
        continue
    top = sorted(members, key=lambda m: -m["mc"])[:10]
    for m in top:
        print("  %s %s(%s) 市值%.0f亿 近20日%.1f%% 近5日%.1f%%" % (
            ind, m["name"], code_of(m["code"]), m["mc"], m["r20"], m["r5"]))

# ── 6. 黄金/医药专项核查 ───────────────────────────────────────────────
GOLD_PHARMA = [
    ("sh600547", "山东黄金", "贵金属"), ("sh600489", "中金黄金", "贵金属"),
    ("sh600988", "赤峰黄金", "贵金属"), ("sz002155", "湖南黄金", "贵金属"),
    ("sz000975", "银泰黄金", "贵金属"), ("sh601899", "紫金矿业", "贵金属"),
    ("sh600276", "恒瑞医药", "化学制药"), ("sh603259", "药明康德", "医疗服务"),
    ("sh600436", "片仔癀", "中药"), ("sz000538", "云南白药", "中药"),
    ("sh600196", "复星医药", "化学制药"), ("sz000661", "长春高新", "生物制品"),
    ("sz300760", "迈瑞医疗", "医疗器械"), ("sz300122", "智飞生物", "生物制品"),
    ("sz300142", "沃森生物", "生物制品"), ("sh688235", "百济神州", "生物制品"),
]
print("\n===== D. 黄金/医药专项核查（当前是否够格触发） =====", flush=True)
for secid, nm, ind in GOLD_PHARMA:
    in_pool = secid in POOL
    name, snaps = None, None
    if in_pool:
        snaps = POOL[secid]["snaps"]
        name = POOL[secid]["name"]
    else:
        name, snaps = fetch_tencent(secid, "20240801", asof.replace("-", ""))
    if not snaps:
        print("  %s %s：K线获取失败" % (secid, nm), flush=True)
        continue
    sub = [dict(s) for s in snaps if s["date"] <= asof]
    if not sub:
        continue
    sub[-1]["name"] = name
    res = []
    for period, p in PARAMS.items():
        try:
            r = analyze_snaps(sub, p, sel_trend)
        except Exception:
            continue
        res.append((period, r["passCount"], r["totalChecks"],
                    r.get("drawdownPct"), p.get("minDrawdownPct", 0)))
    line = "  %s %s(%s) %s 总市值%.0f亿" % (secid, nm, code_of(secid), ind,
                                          mktcap.get(code_of(secid)) or 0)
    line += " | 池内" if in_pool else " | 池外(需入池)"
    for period, pc, tc, dd, thr in res:
        dd_s = "回撤%.1f%%" % dd if dd is not None else "?"
        line += "  %s:%d/%d(%s≥%.0f%%)" % (period, pc, tc, dd_s, thr)
    print(line, flush=True)
