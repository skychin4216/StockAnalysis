# -*- coding: utf-8 -*-
"""股票池/信号硬过滤 —— 与 Kotlin common pipeline 过滤节点对齐 + 归因改进。

设计目的：从机制上减少「假信号 / 弱势板块 / 风险股」漏入选股链路，而非拟合。

周期差异化策略（长线豁免，只增强低胜率的 超短/短线/中线）：
- ST_FILTER      = True   ST/*ST 无条件排除（对齐 StockPoolFilterNode，全周期）
- MAIN_BOARD     = True   主板开关：排除科创/创业/北交所（对齐 onlyMainBoard，全周期）
- SECTOR_FILTER  = True   板块代理过滤：仅 超短/短线/中线（长线豁免，避免误杀大赢家）
- STICKY_HARD    = True   粘合持续硬性：仅 中线（消灭「瞬间收敛」假粘合）
- SHORT_HARD     = True   短线关键项硬性：仅 超短/短线（多头排列 + 三日不新低必须通过）
- PASS_COUNT     = {}     按周期提高 minPassCount 门槛（如 {"短线":7,"中线":7}），None=不覆盖

用法：
  from _pool_filters import extra_filter
  ...analyze_snaps 通过后...
  if not extra_filter(code, name, r, period, cache, all_dates, date_to_idx, asof): continue

对比旧口径：
  import _pool_filters as pf
  pf.ST_FILTER = pf.MAIN_BOARD = pf.SECTOR_FILTER = pf.STICKY_HARD = pf.SHORT_HARD = False
"""
import os

ST_FILTER = True
MAIN_BOARD = True
SECTOR_FILTER = True
SECTOR_THRESHOLD = float(os.environ.get("SECTOR_THRESHOLD", "-3.0"))  # 行业近20日平均涨幅低于此值→弱板块
SECTOR_LOOKBACK = 20
STICKY_HARD = True
SHORT_HARD = True
# 按周期提高 minPassCount（analyze_snaps 通过后仍低于此值 → 拒绝）。默认不覆盖。
PASS_COUNT = {}

# 周期命名归一：超短/超短线 → 超短线
P2 = {"超短": "超短线", "超短线": "超短线", "短线": "短线", "中线": "中线", "长线": "长线"}

# 行业关键词 → 板块（近似分类，仅用于板块代理过滤）
SECTOR_RULES = [
    ("稀土有色", ["稀土", "钨", "铜", "铝", "钴", "镍", "锂", "钛", "锡", "锌", "铅", "钼", "有色", "资源", "黄金", "矿业"]),
    ("半导体电子", ["半导体", "芯片", "中芯", "澜起", "卓胜微", "长电", "新阳", "至纯", "联创", "容大", "彤程", "电子", "京瓷", "积电"]),
    ("光通信", ["光", "仕佳", "烽火", "铭普", "中际", "旭创", "新易盛", "天孚", "光迅"]),
    ("光伏新能源", ["光伏", "隆基", "东方日升", "阳光电源", "锦浪", "正泰", "晶澳", "通威", "德业"]),
    ("电网设备", ["电网", "南瑞", "风范", "宝胜", "积成", "平高", "特变", "许继", "思源", "国电南自"]),
    ("电力公用", ["电力", "华能", "长电", "明星", "水电", "华电", "大唐", "三峡"]),
    ("医药生物", ["医药", "沃森", "迈瑞", "白药", "生物", "长春", "百济", "恒瑞", "智飞", "康泰", "药明"]),
    ("化工材料", ["化工", "索普", "新材", "昊华", "万华", "华鲁", "恒力", "荣盛", "三友", "中泰"]),
    ("钢铁", ["钢", "西宁", "宝钢", "鞍钢", "太钢", "华菱"]),
    ("消费", ["茅台", "五粮液", "海天", "伊利", "泸州", "美的", "格力", "海尔"]),
]
DEFAULT_SECTOR = "其他"


def set_filters(st=None, main=None, sector=None, sticky=None, short=None, threshold=None, pass_count=None):
    """集中开关（用于对比实验）。None 表示保持当前值。
    pass_count: dict，按周期覆盖 minPassCount，如 {"短线":7,"中线":7}；传 {} 清空。"""
    global ST_FILTER, MAIN_BOARD, SECTOR_FILTER, STICKY_HARD, SHORT_HARD, SECTOR_THRESHOLD, PASS_COUNT
    if st is not None: ST_FILTER = bool(st)
    if main is not None: MAIN_BOARD = bool(main)
    if sector is not None: SECTOR_FILTER = bool(sector)
    if sticky is not None: STICKY_HARD = bool(sticky)
    if short is not None: SHORT_HARD = bool(short)
    if threshold is not None: SECTOR_THRESHOLD = float(threshold)
    if pass_count is not None: PASS_COUNT = dict(pass_count)


def sector_of(name):
    for sec, kws in SECTOR_RULES:
        if any(k in name for k in kws):
            return sec
    return DEFAULT_SECTOR


def is_tradeable_code(code, name):
    """基础准入：ST 硬排除 + 主板开关（与 Kotlin StockPoolFilterNode 一致）。"""
    if ST_FILTER and "ST" in (name or "").upper():
        return False
    if MAIN_BOARD and code.startswith(("sh688", "sh689", "sz300", "sz301", "bj8", "sh51", "sh56", "sz15", "sz16")):
        return False
    return True


# ── sector_ret20 加速：预计算「板块 × 日期 → 20日平均涨幅」查找表 ──
_SECTOR_TABLE = None  # {"cache_id": {sec: {date: pct}}}，build_sector_ret_table 构建后 O(1) 查


def build_sector_ret_table(cache, lb=SECTOR_LOOKBACK):
    """预计算 {板块: {日期: 近 lb 日平均涨幅%}}。全缓存只遍历一次。
    返回该表并写入模块级 _SECTOR_TABLE（后续 sector_ret20 直接查）。"""
    global _SECTOR_TABLE
    sums = {}  # sec -> {date: [累计涨幅, 计数]}
    for code, ent in cache.items():
        if code.startswith(("sh000", "sz399")):
            continue
        sec = sector_of(ent.get("name") or code)
        snaps = ent.get("snaps") or []
        closes = [s["close"] for s in snaps]
        dates = [s["date"] for s in snaps]
        dd = sums.setdefault(sec, {})
        for i in range(lb, len(closes)):
            if closes[i - lb] > 0:
                pct = (closes[i] / closes[i - lb] - 1) * 100
                rec = dd.get(dates[i])
                if rec is None:
                    dd[dates[i]] = [pct, 1]
                else:
                    rec[0] += pct
                    rec[1] += 1
    table = {sec: {dt: s[0] / s[1] for dt, s in dd.items()} for sec, dd in sums.items()}
    _SECTOR_TABLE = {"cache": cache, "lb": lb, "data": table}
    return table


def sector_ret20(cache, all_dates, date_to_idx, code, name, asof, lookback=None):
    """信号日所在行业（关键词近似）近 lookback 日的平均涨跌幅（%，不含指数）。
    数据不足返回 None。优先查预计算表（O(1)），无表时退化为全量扫描（慢，仅兜底）。"""
    global _SECTOR_TABLE
    lb = lookback or SECTOR_LOOKBACK
    idx = date_to_idx.get(asof)
    if idx is None or idx < lb:
        return None
    sec = sector_of(name)
    if _SECTOR_TABLE is not None and _SECTOR_TABLE["cache"] is cache and _SECTOR_TABLE["lb"] == lb:
        return _SECTOR_TABLE["data"].get(sec, {}).get(asof)
    # 兜底：无预计算表时按板块实时聚合（慢）
    vals = []
    for other, ent in cache.items():
        if other.startswith(("sh000", "sz399")):
            continue
        if sector_of(ent.get("name") or other) != sec:
            continue
        closes = {s["date"]: s["close"] for s in (ent.get("snaps") or [])}
        base = all_dates[idx - lb] if idx >= lb else None
        if base and base in closes and asof in closes and closes[base] > 0:
            vals.append((closes[asof] / closes[base] - 1) * 100)
    return sum(vals) / len(vals) if vals else None


def extra_filter(code, name, r, period, cache, all_dates, date_to_idx, asof):
    """在 analyze_snaps 通过后执行额外硬过滤。返回 True=保留信号。

    周期差异化：长线只做 ST/主板两级基础过滤（不参与增强过滤），
    增强过滤只作用于低胜率的 超短/短线/中线。
    """
    period = P2.get(period, period)

    # 1) ST 无条件排除（全周期）
    if ST_FILTER and "ST" in (name or "").upper():
        return False
    # 2) 主板开关：排除科创/创业/北交所/ETF（全周期）
    if MAIN_BOARD and code.startswith(("sh688", "sh689", "sz300", "sz301", "bj8", "sh51", "sh56", "sz15", "sz16")):
        return False

    # 3) 长线豁免：以下增强过滤一律不作用于长线（长线保持原口径）
    if period == "长线":
        return True

    # 4) minPassCount 覆盖：该周期总通过项数必须 ≥ 设定门槛
    need = PASS_COUNT.get(period)
    if need and r.get("passCount", 0) < need:
        return False
    # 5) 粘合持续硬性（仅中线）：消灭「瞬间收敛」假粘合
    if STICKY_HARD and period == "中线":
        ck = (r.get("checks") or {}).get("③粘合持续")
        if ck is not None and not ck[0]:
            return False
    # 6) 短线关键项硬性（超短/短线）：追涨信号必须有多头排列 + 三日不新低确认
    if SHORT_HARD and period in ("超短线", "短线"):
        checks = r.get("checks") or {}
        for k in ("②多头排列", "⑬三日不新低"):
            ck = checks.get(k)
            if ck is not None and not ck[0]:
                return False
    # 7) 板块代理过滤（超短/短线/中线）：弱板块（行业近 20 日平均涨幅 < 阈值）拒绝
    if SECTOR_FILTER:
        r20 = sector_ret20(cache, all_dates, date_to_idx, code, name, asof)
        if r20 is not None and r20 < SECTOR_THRESHOLD:
            return False
    return True
