# -*- coding: utf-8 -*-
"""国家队（中央汇金/证金）与大基金（国家集成电路产业投资基金等）进出检测。

规则出处：
  · 《national_flow_research.md》—— 国家队 ETF 行为与十大持有人规律（附核查：本实现只用个股层面法定披露）
  · 《institutional_breakout_guide.md》§8/§9 —— FundFlowAnalyzer 流入/流出扫描口径
  · 《institutional_breakout.py》—— NATIONAL_AUTHORITY 权威性加权 / 连续两期确认

数据源（与 _holder_signals.py 同一接口，可回溯 2008，季度粒度）：
  ① 东财 RPT_F10_EH_FREEHOLDERS 十大流通股东历史
  字段 HOLDER_NAME / HOLDER_STATE(新进/加仓/不变/减持) / FREE_HOLDNUM_RATIO(占自由流通%)
       / HOLD_NUM_CHANGE / END_DATE(报告期) / NOTICE_DATE(实际披露日)

★ 2026-09-13 扩展（用户提问：「国家队/社保大机构会不会**直接买入某只股票**，而不是只买 ETF？」）
  答：会，而且是常态。ETF 只是汇金 2015 后的主通道；社保（走委托组合）、大基金（定增/战投）、
  证金（2015 救市通道）基本都是**直接持股个股**。为此本模块在原「季报流通」通道之外，再开两条
  「直接持股」通道，三者互补、都不含 ETF（ETF 归 _etf_share_flow.py，严禁混算）：
  ① 锁定通道 blk = 十大股东（RPT_F10_EH_HOLDERS）中「已可见于十大股东、但不在十大流通股东里」
     的 nat/big/ss 持股 —— 即**限售/锁定**部分（定增锁定 18 个月、战投、发起人股）。
     ⚠️ 这是原口径的硬缺口：锁定股不计入「十大流通股东」排名，季报流通通道**完全看不到**，
        大基金定增入股后会被漏掉最长 18 个月。占比口径=**占总股本%**（HOLD_NUM_RATIO）。
     披露日来源：同一报告期的十大流通股东 NOTICE_DATE（同一份定期报告同日披露）；缺则丢弃。
  ② 公告通道 ann = 公告级一手证据（T+1，比季报快 1-3 个月），只保留 nat/big/ss 主体：
     a) 股东增减持 RPT_SHARE_HOLDER_INCREASE
        字段 DIRECTION(增持/减持) / HOLDER_NAME / CHANGE_NUM(万股) / CHANGE_RATE(变动比例%)
             / START_DATE~END_DATE(变动区间) / NOTICE_DATE(公告日) / TRADE_AVERAGE_PRICE
     b) 定增获配 RPT_SEO_DETAIL（ISSUE_OBJECT 发行对象文本命中主体关键词；弱证据，标记 src=定增）

★ 三条硬约束（research 文档 §7.4「实盘红线」，实现层强制）：
  1) 国家队/大基金身份**只能**来自「十大流通股东」法定披露；
     ETF 申赎、估算资金流、成交额一律不得写入国家队口径（避免伪归因）；
  2) 所有判定必须 NOTICE_DATE ≤ 信号日（无未来函数），本模块只输出「已披露」状态；
  3) 季报天然滞后 1-3 月，只做中长线定性，**不作实时信号**；看不见前十大之后的仓位（小市值假阴性）。

分类口径（单一事实源，双端/拟合/推送三处共用本文件）：
  nat 国家队 = 中央汇金投资 / 中央汇金资产管理 / 中国证券金融 / 证金
  big 大基金 = 国家集成电路产业投资基金(一/二/三期) / 国家制造业转型升级基金 /
               国家中小企业发展基金 / 国家绿色发展基金 / 中国国有企业结构调整基金 /
               中国国有资本风险投资基金 / 国家军民融合产业投资基金 / 国家产融合作
  ss  社保   = 全国社保基金 / 社保基金 / 基本养老
  nb  北向   = 香港中央结算有限公司

概要加权（对齐 institutional_breakout.py 的 NATIONAL_AUTHORITY）：
  nat 1.0（平准型：单季波动大，需连续 ≥3 期才确认）
  big 1.0（半导体产业链专属；非半导体票不加分）
  ss  0.9（配置型：连续 ≥2 期即确认）

产物：
  data/_national_flow_hist.json                     （PC 侧，拟合/推送复用）
  app/src/main/assets/data/_national_flow_hist.json （APK 内置，双端只读不算）
  结构：{schema, asof, codes, built, judge_day, snap:{code:{...}}, stocks:{code:{name, theme,
          nat:[{end,notice,name,ratio,state}], big:[...], ss:[...],
          blk:[{end,notice,name,ratio,state,kind,type}],
          ann:[{notice,end,start,name,kind,dir,src,chg,num,after,price}]}}}

用法：
  python _national_flow.py --build                 # 全史抓取（断点续跑）→ 落盘 + 同步 assets
  python _national_flow.py --build --codes 300308,600487
  python _national_flow.py --show                  # 最新季国家队/大基金进出榜
  python _national_flow.py --pool 300308           # 单只诊断（逐季序列 + 锁定 + 公告）
  python _national_flow.py --check                 # 自检（离线，用已存资产跑判定口径）
  python _national_flow.py --ann                   # 公告级（增减持/定增）直持榜
"""
import argparse
import datetime as dt
import json
import os
import re
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import _holder_signals as hs  # noqa: E402  复用 F10 抓取（同接口、同回溯窗口）

_PX = {"http": None, "https": None}
_HEAD = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
         "Referer": "https://data.eastmoney.com/"}

DATA_DIR = os.path.join(ROOT, "data")
ASSET_DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")
OUT_FILE = os.path.join(DATA_DIR, "_national_flow_hist.json")
ASSET_OUT = os.path.join(ASSET_DATA, "_national_flow_hist.json")
PROG_FILE = os.path.join(DATA_DIR, "_national_flow_progress.json")

# 资产 schema 版本：结构变更时 +1，使断点续跑自动失效
# （否则旧结构会被 done 跳过，新增的 blk/ann 字段永远补不上）
SCHEMA = 2

# ── 直接持股通道的数据源（2026-09-13 新增，见模块 docstring）──
DC_F10 = "https://datacenter.eastmoney.com/securities/api/data/v1/get"
DC_WEB = "https://datacenter-web.eastmoney.com/api/data/v1/get"
RPT_ALL_HOLDERS = "RPT_F10_EH_HOLDERS"        # ① 十大股东（含限售/非流通）
RPT_HOLDER_CHG = "RPT_SHARE_HOLDER_INCREASE"  # ②a 股东增减持（公告级）
RPT_SEO_OBJECT = "RPT_SEO_DETAIL"             # ②b 定增发行对象（公告级，弱证据）
ANN_WIN = 365      # 公告级信号有效期（自然日）：超期视为「无」
ANN_MAX = 3        # 快照里保留的公告条数
KIND_SHORT = {"nat": "国", "big": "基", "ss": "社", "nb": "北"}
_NAT_MARK = {"流入": "▲", "流出": "▼", "退出": "退", "持稳": "="}

# ── 分类关键词（按优先级从上到下匹配；先 nat/big 后 ss，避免「社保」误吞）──
NAT_HINT = ("中央汇金", "中国证券金融", "证金")
BIG_HINT = ("国家集成电路产业投资基金", "国家制造业转型升级基金",
            "国家中小企业发展基金", "国家绿色发展基金",
            "中国国有企业结构调整基金", "中国国有资本风险投资基金",
            "国家军民融合产业投资基金", "国家产融合作")
SS_HINT = ("全国社保基金", "社保基金", "基本养老")

STATE_POS = ("新进", "加仓", "增持")
STATE_NEG = ("减持",)

# 通道：free=十大流通股东（季报）/ blk=十大股东锁定部分（季报）/ ann=公告级（T+1）
CH_FREE, CH_BLK, CH_ANN = "free", "blk", "ann"

# 各报告期 → 法定披露截止日 (年份偏移, 月, 日)；expected_end「是否已披露」判定用
_DEADLINES = {(3, 31): (0, 4, 30), (6, 30): (0, 8, 31),
              (9, 30): (0, 10, 31), (12, 31): (1, 4, 30)}

# 权威性加权（institutional_breakout_guide §2 的 NATIONAL_AUTHORITY）
AUTHORITY = {"nat": 1.0, "big": 1.0, "ss": 0.9}
# 确认期数门槛（guide §8.2：平准型要求 ≥3 期，配置型 ≥2 期；blk 为混合通道按配置型）
CONFIRM_STREAK = {"nat": 3, "big": 2, "ss": 2, "blk": 2}

# 半导体产业链关键词（大基金专属规则的适用域，guide §2「大基金专属规则」）
SEMI_HINT = ("半导体", "芯片", "集成电路", "电子", "封测", "晶圆", "EDA", "材料", "设备")


def classify(name):
    """股东名 → 类别（nat/big/ss/nb/None）。"""
    if not name:
        return None
    for h in NAT_HINT:
        if h in name:
            return "nat"
    for h in BIG_HINT:
        if h in name:
            return "big"
    for h in SS_HINT:
        if h in name:
            return "ss"
    if name == hs.NORTH_NAME:
        return "nb"
    return None


# ═══════════════════ 数据采集：直接持股两条通道（① 锁定 / ② 公告）═══════════════════

def _dc_get(url, tries=3):
    """东财 datacenter 取数（失败重试）。返回 result.data 列表。"""
    for i in range(tries):
        try:
            j = requests.get(url, timeout=25, headers=_HEAD, proxies=_PX).json()
            rows = ((j.get("result") or {}).get("data")) or []
            if rows or i >= 1:
                return rows
        except Exception:  # noqa: BLE001
            pass
        time.sleep(0.5 * (i + 1))
    return []


def _fnum(v, scale=1.0):
    try:
        return round(float(v) * scale, 4)
    except (TypeError, ValueError):
        return 0.0


def fetch_all_holders(code, min_q="2008-01-01"):
    """① 十大股东（含限售/非流通）。规整为 {end, name, ratio(占总股本%), type, state, rank}。

    与十大流通股东（RPT_F10_EH_FREEHOLDERS）的关键区别：本表**包含限售股**，
    因此能看见「定增锁定 18 个月中的大基金」——这是季报流通通道的硬缺口。
    该接口无 NOTICE_DATE，披露日由同报告期的流通股东记录补齐（见 _notice_by_end）。
    """
    recs, page = [], 1
    while page <= 6:
        rows = _dc_get(DC_F10 + "?reportName=%s&columns=ALL&source=HSF10&client=PC"
                       "&sortColumns=END_DATE&sortTypes=-1&pageNumber=%d&pageSize=500"
                       "&filter=(SECURITY_CODE%%3D%%22%s%%22)"
                       % (RPT_ALL_HOLDERS, page, code))
        if not rows:
            break
        for r in rows:
            end = (r.get("END_DATE") or "")[:10]
            if not end or end < min_q:
                continue
            recs.append({
                "end": end,
                "name": r.get("HOLDER_NAME") or "",
                "ratio": _fnum(r.get("HOLD_NUM_RATIO")),      # 占总股本%
                "type": r.get("SHARES_TYPE") or "",
                "state": hs.norm_state(r),
                "rank": r.get("HOLDER_RANK"),
            })
        if len(rows) < 500 or (rows[-1].get("END_DATE") or "")[:10] < min_q:
            break
        page += 1
    return recs


def _notice_by_end(free_recs):
    """报告期 → 该定期报告的实际披露日（十大股东表无此字段，用同报告期流通股东补齐）。"""
    m = {}
    for r in free_recs:
        e, n = r.get("end"), r.get("notice")
        if e and n and n > m.get(e, ""):
            m[e] = n
    return m


def fetch_holder_changes(code, page_size=200):
    """②a 股东增减持公告（T+1 一手证据）。只返回可归因主体（nat/big/ss）的记录。"""
    rows = _dc_get(DC_WEB + "?reportName=%s&columns=ALL&source=WEB&client=WEB"
                   "&sortColumns=NOTICE_DATE&sortTypes=-1&pageNumber=1&pageSize=%d"
                   "&filter=(SECURITY_CODE%%3D%%22%s%%22)"
                   % (RPT_HOLDER_CHG, page_size, code))
    out = []
    for r in rows:
        nm = r.get("HOLDER_NAME") or ""
        k = classify(nm)
        if k not in ("nat", "big", "ss"):
            continue
        notice = (r.get("NOTICE_DATE") or "")[:10]
        if not notice:
            continue
        d = r.get("DIRECTION") or ""
        up = ("增持" in d) or (not d and _fnum(r.get("CHANGE_RATE")) > 0)
        out.append({
            "notice": notice, "end": (r.get("END_DATE") or "")[:10],
            "start": (r.get("START_DATE") or "")[:10],
            "name": nm, "kind": k, "dir": "增持" if up else "减持", "src": "增减持",
            "chg": abs(_fnum(r.get("CHANGE_RATE"))) * (1 if up else -1),
            "num": _fnum(r.get("CHANGE_NUM")),                  # 变动数量(万股)
            "after": _fnum(r.get("AFTER_CHANGE_RATE"), 100),     # 变动后占总股本%
            "price": r.get("TRADE_AVERAGE_PRICE"),
        })
    return out


def _split_objects(txt):
    """定增 ISSUE_OBJECT 自由文本 → 发行对象名列表（去「向/对」前缀）。"""
    out = []
    for p in re.split(r"[,，、;；]", txt or ""):
        p = re.sub(r"^(向|对)", "", (p or "").strip())
        p = re.sub(r"^(控股股东|实际控制人|关联方)", "", p).strip()
        if len(p) >= 6:            # 机构全称；4 字简称/自然人不参与归因
            out.append(p)
    return out


def fetch_seo_objects(code, page_size=100):
    """②b 定增获配（弱证据）：ISSUE_OBJECT 为自由文本，只做主体关键词命中。

    该表无 NOTICE_DATE，用 ISSUE_LISTING_DATE（新增股份上市日）作为可获得时点。
    """
    base = (DC_WEB + "?reportName=%s&columns=ALL&source=WEB&client=WEB"
            "&pageNumber=1&pageSize=%d&filter=(SECURITY_CODE%%3D%%22%s%%22)"
            % (RPT_SEO_OBJECT, page_size, code))
    rows = _dc_get(base + "&sortColumns=ISSUE_LISTING_DATE&sortTypes=-1")
    if not rows:
        rows = _dc_get(base)          # 排序字段不被支持时退回无序
    out = []
    for r in rows:
        notice = (r.get("ISSUE_LISTING_DATE") or r.get("EQUITY_RECORD_DATE") or "")[:10]
        if not notice:
            continue
        for nm in _split_objects(r.get("ISSUE_OBJECT")):
            k = classify(nm)
            if k in ("nat", "big", "ss"):
                out.append({"notice": notice, "end": notice, "start": "",
                            "name": nm, "kind": k, "dir": "增持", "src": "定增",
                            "chg": 0.0, "num": 0.0, "after": 0.0,
                            "price": r.get("ISSUE_PRICE")})
    return out


# ═══════════════════════ 判定口径（唯一实现，供节点/拟合/推送共用）═══════════════════════

def _ratio_sum(rows):
    return round(sum(float(r.get("ratio") or 0) for r in rows), 4)


def flow_series(recs, day=None):
    """按报告期聚合的序列（升序），仅含 NOTICE_DATE ≤ day 的记录（无未来函数）。

    返回 [{end, notice, ratio(合计), n(账户数), names[], pos(bool 有加码动作)}]
    """
    if day:
        recs = [r for r in recs if r.get("notice") and r["notice"] <= day]
    by_end = {}
    for r in recs:
        by_end.setdefault(r["end"], []).append(r)
    out = []
    for end in sorted(by_end):
        rows = by_end[end]
        out.append({
            "end": end,
            "notice": max((r.get("notice") or "") for r in rows),
            "ratio": _ratio_sum(rows),
            "n": len({r.get("name") for r in rows}),
            "names": sorted({r.get("name") for r in rows if r.get("name")}),
            "pos": any(r.get("state") in STATE_POS for r in rows),
            "neg": any(r.get("state") in STATE_NEG for r in rows),
        })
    return out


def _qidx(end):
    """报告期 → 连续季度序号（算缺席期数用）。"""
    try:
        y, m = int(str(end)[:4]), int(str(end)[5:7])
        return y * 4 + {3: 0, 6: 1, 9: 2, 12: 3}.get(m, 0)
    except (TypeError, ValueError):
        return -10 ** 6


def expected_end(day):
    """信号日 day 对应的最近一个「法定披露截止日已到」的季末报告期。

    按各期真实披露截止日（沪深交易所规则）判定，避免统一滞后天数造成的偏差：
      3-31 → 4-30 | 6-30 → 8-31 | 9-30 → 10-31 | 12-31 → 次年 4-30
    """
    try:
        d = dt.date.fromisoformat(str(day)[:10])
    except (TypeError, ValueError):
        return ""
    best = ""
    for y in (d.year, d.year - 1, d.year - 2):
        for (m, dd), dl in _DEADLINES.items():
            e = dt.date(y, m, dd)
            if d >= dt.date(y + dl[0], dl[1], dl[2]) and e.isoformat() > best:
                best = e.isoformat()
    return best


def _dir_of(cur, prv):
    """一期方向：+1 增持 / -1 减持 / 0 持平。以合计占比环比为主、动作标签为辅。"""
    d = (cur["ratio"] - prv["ratio"]) if prv else None
    if cur["pos"] and (d is None or d >= 0):
        return 1
    if cur["neg"] and (d is None or d <= 0):
        return -1
    if d is None:
        return 0
    return 1 if d > 0.3 else (-1 if d < -0.3 else 0)


def flow_state(recs, day, kind="nat"):
    """截至 day（≤NOTICE_DATE）的最新已披露进出状态。**无未来函数**。

    ★ 关键修正（2026-09-13）：十大流通股东**每季披露**。若最新记录比「当期应披露报告期」
      落后 ≥2 期，说明该主体**已退出前十大**（不是因为"还持有只是没披露"）→ 判「退出」并按流出占分。
      否则会把 2015 年的一次新进一路带到 2026 年，制造大量伪信号。
      （实测：002371 国家队最新披露停在 2021Q1、300308 停在 2020Q2 —— 与 research 文档
       「汇金 2015 后个股前十大可见度下降、转向 ETF」一致，必须显式退出。）

    kind: nat/big/ss —— 决定「连续几期才算确认」（CONFIRM_STREAK）。

    返回 dict：
      state   流入 | 流出 | 退出 | 持稳 | 无
              （无 = 从未进前十大 ≠ 减持；退出 = 曾在前十大、现已连续 ≥2 期缺席）
      strong  连续增持期数 ≥ CONFIRM_STREAK（仅 state=流入 时可能为真）
      chg     最新期合计占比 − 上一期合计占比（pp；仅一期时为 None）
      streak  连续增持期数（负值=连续减持期数）
      ratio/n/names  最新期合计占比(%) / 账户数 / 账户名
      end/notice/gap 最新已披露报告期 / 实际披露日 / 距当期报告期的缺席期数
    """
    ser = flow_series(recs, day)
    if not ser:
        return {"state": "无", "strong": False, "chg": None, "streak": 0,
                "ratio": None, "n": 0, "names": [], "end": "", "notice": "",
                "gap": None, "series_n": 0}
    last = ser[-1]
    prev = ser[-2] if len(ser) >= 2 else None
    chg = round(last["ratio"] - prev["ratio"], 4) if prev else None
    exp = expected_end(day) if day else ""
    gap = (_qidx(exp) - _qidx(last["end"])) if exp else 0

    # 连续期数（增持为正 / 减持为负）
    dirs = [_dir_of(ser[i], ser[i - 1]) for i in range(1, len(ser))]
    last_dir = dirs[-1] if dirs else _dir_of(last, None)
    streak = last_dir
    if last_dir:
        for d in reversed(dirs[:-1]):
            if d != last_dir:
                break
            streak += last_dir

    if gap >= 2:
        state = "退出"                                   # 连续 ≥2 期缺席前十大
    elif chg is not None and chg < -0.3:
        state = "流出"
    elif chg is not None and chg > 0.3:
        state = "流入"
    elif last["pos"] and (chg is None or chg >= 0):
        state = "流入"
    elif last["neg"] and (chg is None or chg <= 0):
        state = "流出"
    else:
        state = "持稳"

    need = CONFIRM_STREAK.get(kind, 2)
    return {"state": state, "strong": bool(state == "流入" and streak >= need),
            "chg": chg, "streak": streak, "ratio": last["ratio"],
            "n": last["n"], "names": last["names"], "end": last["end"],
            "notice": last["notice"], "gap": gap, "series_n": len(ser)}


def _dom_kind(rows):
    """一组记录里「主导主体类别」（nat/big/ss，按占比/变动幅度合计最大）——单元格短标用。"""
    w = {}
    for r in rows or []:
        k = r.get("kind") or classify(r.get("name") or "")
        if k in ("nat", "big", "ss"):
            w[k] = w.get(k, 0.0) + max(abs(_fnum(r.get("chg"))), abs(_fnum(r.get("ratio"))), 0.1)
    return max(w, key=w.get) if w else ""


def ann_state(recs, day, win=ANN_WIN):
    """② 公告级（股东增减持 / 定增获配）最近状态。**无未来函数**：只用 NOTICE_DATE ≤ day。

    与 flow_state 的区别：公告是 T+1 的一手证据（比季报快 1-3 个月）且带明确方向，
    因此**不判「退出」、不做多期确认**，只看「最近 win 天内最后一次公告的方向」。

    返回 dict：
      state   流入 | 流出 | 无（无 = 无公告，或最近公告已超期 win 天 → 状态不残留）
      dir     增持 | 减持（最近一次）
      kind    主导主体类别 nat/big/ss（单元格短标 国/基/社）
      ratio   窗口内「增持比例 − 减持比例」净额（pp，正=净增持）
      notice/end  最近公告日 / 变动截止日
      src     增减持 | 定增
      names   窗口内主体名（最近 ANN_MAX 条，新→旧）
      detail  [{dir,kind,name,notice,src,chg,num}] 最近 ANN_MAX 条
      n       历史公告总条数（含已超期）
      fresh   True=窗口内（参与计分）；False=仅有历史公告（dir/notice 仍为**全史最近一条**，
              供推送展示「时间点」但不计分）
    """
    base = {"state": "无", "dir": "", "kind": "", "ratio": None, "notice": "",
            "end": "", "src": "", "names": [], "detail": [], "n": 0, "fresh": False}
    rs = [r for r in (recs or []) if r.get("notice") and r["notice"] <= day]
    if not rs:
        return base
    rs.sort(key=lambda r: (r["notice"], r.get("end") or ""))
    base["n"] = len(rs)
    latest = rs[-1]
    base.update({"dir": latest.get("dir") or "", "kind": latest.get("kind") or "",
                 "notice": latest["notice"], "end": latest.get("end") or "",
                 "src": latest.get("src") or "", "names": [latest.get("name") or ""]})
    lo = ""
    if day:
        try:
            lo = (dt.date.fromisoformat(str(day)[:10]) - dt.timedelta(days=win)).isoformat()
        except (TypeError, ValueError):
            lo = ""
    recent = [r for r in rs if not lo or r["notice"] >= lo]
    if not recent:
        return base                       # 最近公告已超期 → 视为「无」
    last = recent[-1]
    net = sum(abs(_fnum(r.get("chg"))) * (1 if r.get("dir") == "增持" else -1)
              for r in recent)
    tail = recent[-ANN_MAX:][::-1]
    base.update({
        "state": "流入" if last.get("dir") == "增持" else "流出",
        "dir": last.get("dir") or "", "notice": last["notice"],
        "end": last.get("end") or "", "src": last.get("src") or "",
        "kind": _dom_kind(recent), "ratio": round(net, 4), "fresh": True,
        "names": [r.get("name") or "" for r in tail],
        "detail": [{"dir": r.get("dir") or "", "kind": r.get("kind") or "",
                    "name": (r.get("name") or "")[-20:], "notice": r["notice"],
                    "src": r.get("src") or "", "chg": abs(_fnum(r.get("chg"))),
                    "num": _fnum(r.get("num"))} for r in tail],
    })
    return base


def flow_bundle(stock, day):
    """一只股票在 day 时点的全部渠道状态（national_flow 节点 / 拟合 / 推送共用入口）。

    stock = {name, theme, nat:[], big:[], ss:[], blk:[], ann:[]}
    返回 {nat, big, ss, blk, ann, flowScore, directScore, label, semi, ...}

    ★ flowScore 口径**保持不变**（仅季报流通三通道），另开 directScore = 锁定 + 公告
      两条「直接持股」通道的独立分（0~100，50 中性）——老口径零回归，双端可各自选用。
    """
    nat = flow_state(stock.get("nat") or [], day, "nat")
    big = flow_state(stock.get("big") or [], day, "big")
    ss = flow_state(stock.get("ss") or [], day, "ss")
    blk = flow_state(stock.get("blk") or [], day, "blk")
    ann = ann_state(stock.get("ann") or [], day)
    lkind = _dom_kind(stock.get("blk") or [])
    blk["kind"] = lkind

    score = 0.0
    for kind, st, up_w, dn_w in (("nat", nat, 45, 40), ("big", big, 40, 35), ("ss", ss, 25, 25)):
        w = AUTHORITY[kind]
        if st["state"] == "流入":
            score += up_w * w * (1.35 if st["strong"] else 1.0)
        elif st["state"] == "流出":
            score -= dn_w * w
        elif st["state"] == "退出":
            score -= dn_w * w * 0.6          # 退出前十大：弱于显式减持（可能仅掉出十名外）
    score = max(0.0, min(100.0, 50.0 + score))

    # 直接持股分（锁定 + 公告）：权重低于季报通道，但时效性更强
    dscore = 0.0
    lw = AUTHORITY.get(lkind, 1.0)
    if blk["state"] == "流入":
        dscore += 18 * lw * (1.3 if blk["strong"] else 1.0)
    elif blk["state"] == "流出":
        dscore -= 16 * lw
    # ⚠️ 锁定通道的「退出」**刻意不计分**：锁定股退出十大股东最常见原因是
    #    **锁定期届满转为流通股**（同一主体随即出现在流通榜，属正常解禁而非减持）。
    #    若真减持，会由流通通道(▼)或公告通道(▼)体现，避免同一事件重复计分/误判。
    aw = AUTHORITY.get(ann["kind"] or "big", 1.0)
    if ann["state"] == "流入":
        dscore += 22 * aw
    elif ann["state"] == "流出":
        dscore -= 20 * aw
    dscore = max(0.0, min(100.0, 50.0 + dscore))

    tags = []
    for lab, st in (("国家队", nat), ("大基金", big), ("社保", ss)):
        if st["state"] == "流入":
            tags.append("%s流入%s" % (lab, "·强" if st["strong"] else ""))
        elif st["state"] == "流出":
            tags.append("%s流出" % lab)
        elif st["state"] == "退出":
            tags.append("%s退出" % lab)
    if blk["state"] == "流入":
        tags.append("锁定持股·%s%s" % (KIND_SHORT.get(lkind, ""), "强" if blk["strong"] else ""))
    elif blk["state"] == "流出":
        tags.append("锁定减持·%s" % KIND_SHORT.get(lkind, ""))
    if ann["state"] in ("流入", "流出"):
        tags.append("公告%s·%s(%s)" % (ann["dir"], KIND_SHORT.get(ann["kind"], ""), ann["notice"]))

    if not tags:
        tags.append("无披露" if all(x["state"] == "无"
                                    for x in (nat, big, ss, blk, ann)) else "持稳")

    semi = any(h in (stock.get("theme") or "") for h in SEMI_HINT) or \
        any(h in (stock.get("name") or "") for h in SEMI_HINT)
    return {"nat": nat, "big": big, "ss": ss, "blk": blk, "ann": ann,
            "flowScore": round(score, 1), "directScore": round(dscore, 1),
            "label": " + ".join(tags), "semi": semi,
            "natState": nat["state"], "bigState": big["state"], "ssState": ss["state"],
            "natStrong": nat["strong"], "bigStrong": big["strong"],
            "lockState": blk["state"], "lockStrong": blk["strong"], "lockKind": lkind,
            "annState": ann["state"], "annDir": ann["dir"], "annKind": ann["kind"],
            "annNotice": ann["notice"]}


def build_snapshot(stocks, day):
    """当前时点状态快照 —— 供 pipeline 节点 / APK / 推送表**只读**，避免 Python/Kotlin/推送三处重算口径。

    与 _inst_holdings.json 内嵌 grade 同一模式：算法只在 smalltools，资产内预计算。
    （回溯拟合不读快照，直接逐日调 flow_bundle —— 状态随时点变化，必须逐日算。）
    """
    snap = {}
    for c, s in (stocks or {}).items():
        b = flow_bundle(s, day)
        if all(b[k]["state"] == "无" for k in ("nat", "big", "ss", "blk", "ann")):
            continue
        ent = {"name": s.get("name") or "", "theme": s.get("theme") or "",
               "flowScore": b["flowScore"], "directScore": b["directScore"],
               "flowLabel": b["label"], "semi": b["semi"], "day": day}
        for k in ("nat", "big", "ss"):
            st = b[k]
            ent[k + "State"] = st["state"]
            ent[k + "Strong"] = st["strong"]
            ent[k + "Ratio"] = st["ratio"]
            ent[k + "Chg"] = st["chg"]
            ent[k + "End"] = st["end"]
            ent[k + "Notice"] = st["notice"]
            ent[k + "Gap"] = st["gap"]
            ent[k + "Names"] = (st["names"] or [])[:3]
        # ① 锁定通道（十大股东可见、十大流通股东不可见 = 限售/非流通，报告期口径）
        lk = b["blk"]
        ent.update({"lockState": lk["state"], "lockStrong": lk["strong"],
                    "lockKind": b["lockKind"], "lockRatio": lk["ratio"],
                    "lockChg": lk["chg"], "lockEnd": lk["end"],
                    "lockNotice": lk["notice"], "lockGap": lk["gap"],
                    "lockNames": (lk["names"] or [])[:3]})
        lb = [r for r in (s.get("blk") or []) if r.get("notice") and r["notice"] <= day]
        if lb:
            q = max(r["end"] for r in lb)
            ent["lockTypes"] = sorted({(r.get("type") or "") for r in lb if r["end"] == q})
        # ② 公告通道（股东增减持/定增获配，T+1；带公告日与变动比例）
        a = b["ann"]
        ent.update({"annState": a["state"], "annDir": a["dir"], "annKind": a["kind"],
                    "annRatio": a["ratio"], "annNotice": a["notice"], "annEnd": a["end"],
                    "annSrc": a["src"], "annNames": (a["names"] or [])[:2],
                    "annDetail": a["detail"] or [], "annFresh": a["fresh"],
                    "annN": a["n"]})
        snap[c] = ent
    return snap


# ═══════════════════════ 数据采集 ═══════════════════════

def load_national_hist():
    """读 data/_national_flow_hist.json（PC 侧）。"""
    try:
        with open(OUT_FILE, encoding="utf-8") as f:
            return json.load(f) or {}
    except (OSError, ValueError):
        return {}


def _theme_map():
    """code → 行业主题（复用 _etf_holdings 的行业ETF前五归属）。"""
    try:
        import _etf_holdings as eh
        return hs._theme_map(eh.load_holdings() or {})
    except Exception:  # noqa: BLE001
        return {}


def target_codes(explicit=None):
    """默认标的池 = _holder_hist.json 的 codes（行业ETF前五重仓，与拟合口径一致）。"""
    if explicit:
        return [c.strip().zfill(6) for c in explicit if c.strip()]
    try:
        with open(os.path.join(DATA_DIR, "_holder_hist.json"), encoding="utf-8") as f:
            d = json.load(f) or {}
        m = {}
        for k in ("ss", "nb"):
            for c in (d.get(k) or {}):
                m[c] = 1
        codes = sorted(m)
        if codes:
            return codes
    except (OSError, ValueError):
        pass
    try:
        with open(os.path.join(DATA_DIR, "_etf_top5_hist.json"), encoding="utf-8") as f:
            return sorted(((json.load(f) or {}).get("codes") or {}).keys())
    except (OSError, ValueError):
        return []


def _rows_free(recs, kind):
    """季报流通通道：从十大流通股东记录里筛出某一主体类别的行。"""
    rows = []
    for r in recs:
        if classify(r.get("name") or "") != kind:
            continue
        if not r.get("notice"):
            continue           # 无披露日 → 无法做无未来函数判定，丢弃
        rows.append({kk: r[kk] for kk in ("end", "notice", "name", "ratio", "state")})
    return rows


def _rows_blk(all_recs, free_recs, kind):
    """① 锁定通道：十大股东榜里有、十大流通股东榜里没有的 nat/big/ss 持股。

    这类持股 = 限售/非流通（定增锁定、战投、发起人股），**原季报流通通道完全看不见**。
    披露日取同报告期的流通股东 NOTICE_DATE（同一份定期报告同日披露）；缺则丢弃。
    """
    nb = _notice_by_end(free_recs)
    seen = {(r["end"], r["name"]) for r in free_recs}
    out = []
    for r in all_recs:
        if classify(r.get("name") or "") != kind:
            continue
        if (r["end"], r["name"]) in seen:
            continue           # 流通股东榜已可见 → 不是增量，避免双计
        nd = nb.get(r["end"])
        if not nd:
            continue           # 无披露日 → 丢弃（无未来函数）
        out.append({"end": r["end"], "notice": nd, "name": r["name"],
                    "ratio": r["ratio"], "state": r["state"],
                    "kind": kind, "type": r.get("type") or ""})
    return out


def build(codes=None, resume=True, sources=("free", "blk", "ann"), day=None):
    """逐股抓 ① 十大流通股东 ② 十大股东锁定 ③ 公告（增减持/定增）→ 归集 → 落盘 + 同步 assets。

    sources 可裁剪（如 ("free",) 只刷老口径）。SCHEMA 变更时断点自动失效。
    day 指定「判定日」（默认取 _holder_hist 的 asof）；回放历史某日时必须显式指定，
    否则快照会把之后才披露的记录算进来（未来函数）。
    """
    codes = target_codes(codes)
    if not codes:
        print("✗ 无标的池（先跑 _holder_signals.py --build-hist 或 _etf_holdings.py）")
        return {}
    theme_of = _theme_map()
    res, stocks = {}, {}
    if resume:
        for f in (OUT_FILE, PROG_FILE):        # 主文件 + 中断进度，合并续跑
            try:
                with open(f, encoding="utf-8") as fh:
                    r = json.load(fh) or {}
            except (OSError, ValueError):
                continue
            if int(r.get("schema") or 0) != SCHEMA:
                print("↻ %s schema=%s ≠ %d → 忽略断点，全量重抓"
                      % (os.path.basename(f), r.get("schema"), SCHEMA))
                continue
            res = r or res
            stocks.update(r.get("stocks") or {})
    done = set(stocks.keys()) if resume else set()
    print("标的 %d 只（已完成 %d）｜通道 %s" % (len(codes), len(done), "+".join(sources)),
          flush=True)
    hit = {"nat": 0, "big": 0, "ss": 0, "blk": 0, "ann": 0}
    for i, c in enumerate(codes):
        if c in done:
            for k in hit:
                hit[k] += 1 if stocks[c].get(k) else 0
            continue
        try:
            free_recs = hs.fetch_f10_history(c)
        except Exception as e:  # noqa: BLE001
            print("  ! %s 抓取失败: %s" % (c, e))
            continue
        ent = {"name": "", "theme": theme_of.get(c) or ""}
        for k in ("nat", "big", "ss"):
            rows = _rows_free(free_recs, k)
            if rows:
                ent[k] = rows
                hit[k] += 1
        if "blk" in sources:
            try:
                all_recs = fetch_all_holders(c)
            except Exception as e:  # noqa: BLE001
                print("  ! %s 十大股东失败: %s" % (c, e))
                all_recs = []
            blk = []
            for k in ("nat", "big", "ss"):
                blk += _rows_blk(all_recs, free_recs, k)
            if blk:
                ent["blk"] = sorted(blk, key=lambda r: (r["end"], r["name"]))
                hit["blk"] += 1
        if "ann" in sources:
            ann = []
            for fn in (fetch_holder_changes, fetch_seo_objects):
                try:
                    ann += fn(c)
                except Exception as e:  # noqa: BLE001
                    print("  ! %s %s 失败: %s" % (c, fn.__name__, e))
            if ann:
                ent["ann"] = sorted(ann, key=lambda r: (r["notice"], r["name"]))
                hit["ann"] += 1
        if any(ent.get(k) for k in ("nat", "big", "ss", "blk", "ann")):
            stocks[c] = ent
        if (i + 1) % 10 == 0:
            print("  %d/%d 国家队%d 大基金%d 社保%d 锁定%d 公告%d"
                  % (i + 1, len(codes), hit["nat"], hit["big"], hit["ss"],
                     hit["blk"], hit["ann"]), flush=True)
            _save({"schema": SCHEMA, "asof": res.get("asof") or "", "codes": len(codes),
                   "built": dt.datetime.now().strftime("%Y-%m-%d %H:%M"),
                   "stocks": stocks}, partial=True)
    asof = ""
    try:
        with open(os.path.join(DATA_DIR, "_holder_hist.json"), encoding="utf-8") as f:
            asof = (json.load(f) or {}).get("asof") or ""
    except (OSError, ValueError):
        pass
    day = day or asof or dt.date.today().isoformat()
    out = {"schema": SCHEMA, "asof": asof, "codes": len(codes),
           "built": dt.datetime.now().strftime("%Y-%m-%d %H:%M"),
           "judge_day": day,
           "rule": "国家队=中央汇金/证金；大基金=国家集成电路产业投资基金等；"
                   "社保=全国社保/基本养老。通道：①季报十大流通股东 "
                   "②锁定=十大股东可见而流通榜不可见(限售/非流通) "
                   "③公告=股东增减持+定增获配；全部要求 披露/公告日≤信号日（无未来函数）",
           "states": "流入|流出|退出|持稳|无（退出=曾进前十大、现连续≥2期缺席；"
                     "公告超期180天→无）",
           "snap": build_snapshot(stocks, day),
           "stocks": stocks}
    _save(out)
    print("✓ 国家队%d只 / 大基金%d只 / 社保%d只 ｜ 锁定%d只 ｜ 公告%d只 ｜ 快照 %d 只"
          "（判定日 %s）→ %s"
          % (hit["nat"], hit["big"], hit["ss"], hit["blk"], hit["ann"],
             len(out["snap"]), day, OUT_FILE))
    return out


def _save(d, partial=False):
    os.makedirs(DATA_DIR, exist_ok=True)
    with open(OUT_FILE if not partial else PROG_FILE, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, separators=(",", ":"))
    if not partial:
        os.makedirs(ASSET_DATA, exist_ok=True)
        with open(ASSET_OUT, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, separators=(",", ":"))
        if os.path.exists(PROG_FILE):
            try:
                os.remove(PROG_FILE)
            except OSError:
                pass


# ═══════════════════════ 展示 / 自检 ═══════════════════════

def show(today=None):
    d = load_national_hist()
    stocks = d.get("stocks") or {}
    if not stocks:
        print("✗ 缺 %s，先跑 --build" % OUT_FILE)
        return 1
    day = today or dt.date.today().isoformat()
    print("═══ 国家队/大基金/社保 进出（截至 %s，标的 %d 只，schema %s）═══"
          % (day, len(stocks), d.get("schema")))
    buckets = {"流入": [], "流出": []}
    lock_rows, ann_rows = [], []
    for c, s in stocks.items():
        b = flow_bundle(s, day)
        for k, lab in (("nat", "国家队"), ("big", "大基金"), ("ss", "社保")):
            st = b[k]
            if st["state"] in ("流入", "流出"):
                buckets[st["state"]].append(
                    (b["flowScore"], c, s.get("name") or "", lab, st, s.get("theme") or ""))
        if b["lockState"] in ("流入", "流出"):
            lock_rows.append((b["lockState"], c, s.get("name") or "",
                              KIND_SHORT.get(b["lockKind"], ""), b["blk"]))
        if b["annState"] != "无":
            ann_rows.append((b["ann"]["notice"], b["annState"], c, s.get("name") or "",
                             b["ann"]["dir"], KIND_SHORT.get(b["ann"]["kind"], ""),
                             b["ann"]["src"]))
    for lab in ("流入", "流出"):
        rows = sorted(buckets[lab], key=lambda x: (-x[0] if lab == "流入" else x[0]))
        print("\n▼ %s %d 条" % (lab, len(rows)))
        for score, c, nm, kind, st, theme in rows[:25]:
            print("   [%5.1f] %s %s %s | %s%s | 占比%s%% 环比%s streak=%d 披露%s"
                  % (score, c, nm, theme or "-", kind,
                     "·强" if st["strong"] else "",
                     st["ratio"], st["chg"], st["streak"], st["notice"]))
    print("\n▼ ①锁定通道（十大股东可见/流通榜不可见，限售=定增锁定等）%d 条" % len(lock_rows))
    for state, c, nm, ks, st in sorted(lock_rows, key=lambda x: x[4]["notice"], reverse=True)[:20]:
        print("   %s%s %s %-6s | 占总股本%s%% 报告期%s 披露%s | %s"
              % (_NAT_MARK.get(state, ""), ks, c, nm[:6], st["ratio"], st["end"],
                 st["notice"], (st["names"] or [""])[0][-18:]))
    print("\n▼ ②公告通道（股东增减持/定增，T+1）%d 条" % len(ann_rows))
    for notice, state, c, nm, dr, ks, src in sorted(ann_rows, reverse=True)[:20]:
        print("   %s %s%s(%s) %s %-6s | 公告%s"
              % (_NAT_MARK.get(state, ""), dr, ks, src, c, nm[:6], notice))
    return 0


def pool(codes, day=None):
    d = load_national_hist()
    stocks = d.get("stocks") or {}
    day = day or dt.date.today().isoformat()
    for c in codes:
        s = stocks.get(c)
        print("\n═══ %s ═══" % c)
        if not s:
            print("  无国家队/大基金/社保披露记录")
            continue
        b = flow_bundle(s, day)
        print("  %s | flowScore %.1f | directScore %.1f | %s"
              % (s.get("name") or "", b["flowScore"], b["directScore"], b["label"]))
        for k, lab in (("nat", "国家队"), ("big", "大基金"), ("ss", "社保")):
            rows = (s.get(k) or [])
            if not rows:
                continue
            print("  · %s %d 条：%s" % (lab, len(rows), flow_state(rows, day, k)))
            for r in sorted(rows, key=lambda x: x["end"])[-6:]:
                print("      %s(披露%s) %s %s%% %s"
                      % (r["end"], r["notice"], r["name"][-14:], r["ratio"], r["state"]))
        lb = (s.get("blk") or [])
        if lb:
            print("  · ①锁定通道 %d 条（占总股本%%，限售/非流通）：%s"
                  % (len(lb), b["blk"]))
            for r in sorted(lb, key=lambda x: x["end"])[-6:]:
                print("      %s(披露%s) [%s] %s %s%% %s"
                      % (r["end"], r["notice"], r.get("type") or "-",
                         r["name"][-14:], r["ratio"], r["state"]))
        for r in (b["ann"]["detail"] or []):
            print("  · ②公告通道 %s %s %s %s 变动%s%% 公告%s"
                  % (r["src"], r["dir"], KIND_SHORT.get(r["kind"], ""),
                     r["name"], r["chg"], r["notice"]))
    return 0


def ann_board(today=None):
    """② 公告级（股东增减持 / 定增获配）直持榜 —— T+1，比季报快 1-3 个月。"""
    d = load_national_hist()
    stocks = d.get("stocks") or {}
    if not stocks:
        print("✗ 缺 %s，先跑 --build" % OUT_FILE)
        return 1
    day = today or dt.date.today().isoformat()
    rows, hist = [], []
    for c, s in stocks.items():
        a = ann_state(s.get("ann") or [], day)
        nm = a["names"][0][-18:] if a["names"] else ""
        if a["state"] != "无":
            rows.append((a["notice"], a["state"], c, s.get("name") or "",
                         a["dir"], KIND_SHORT.get(a["kind"], ""), a["src"],
                         a["ratio"], nm, a["n"]))
        elif a["notice"]:
            hist.append((a["notice"], c, s.get("name") or "", a["dir"],
                         KIND_SHORT.get(a["kind"], ""), a["src"], nm, a["n"]))
    print("═══ ② 公告级直持（截至 %s，%d 只标的）═══" % (day, len(stocks)))
    print("   口径：NOTICE_DATE≤%s；state 取「最近一次公告方向」；窗口 %d 天"
          % (day, ANN_WIN))
    print("\n▼ 窗口内（参与 directScore 计分）%d 只" % len(rows))
    for r in sorted(rows, key=lambda x: x[0], reverse=True)[:40]:
        print("   %s %s %s %-6s %s%s(%s) 净变动%+.2f%% 历史%d条 | %s"
              % (r[0], _NAT_MARK.get(r[1], r[1]), r[2], r[3][:6],
                 r[4], r[5], r[6], r[7] or 0, r[9], r[8]))
    print("\n▼ 超窗口（仅展示时间点，不计分）%d 只" % len(hist))
    for r in sorted(hist, key=lambda x: x[0], reverse=True)[:40]:
        print("   %s %s %s %-6s %s%s 历史%d条 | %s"
              % (r[0], r[3], r[1], r[2][:6], r[4], r[5], r[7], r[6]))
    return 0


def check():
    """离线自检：用已存资产跑判定口径，确认无未来函数（同 day 复现）。"""
    d = load_national_hist()
    stocks = d.get("stocks") or {}
    print("资产: %d 只，schema %s，built %s" % (len(stocks), d.get("schema"), d.get("built")))
    day = d.get("judge_day") or d.get("asof") or dt.date.today().isoformat()
    n_ok = 0
    n_open = 0
    for c, s in stocks.items():
        b = flow_bundle(s, day)
        for k in ("nat", "big", "ss", "blk"):
            for r in (s.get(k) or []):
                if r["notice"] > day:
                    print("✗ 未来函数: %s %s 披露%s > %s" % (c, k, r["notice"], day))
                    return 1
        for r in (s.get("ann") or []):
            if r["notice"] > day:
                print("✗ 未来函数: %s ann 公告%s > %s" % (c, r["notice"], day))
                return 1
        if b["natState"] != "无" or b["bigState"] != "无":
            n_ok += 1
        if b["lockState"] != "无":
            n_open += 1
    # 同一 day 连算两次必须完全一致（判定纯函数）
    snap1 = build_snapshot(stocks, day)
    snap2 = build_snapshot(stocks, day)
    if json.dumps(snap1, sort_keys=True) != json.dumps(snap2, sort_keys=True):
        print("✗ 判定不确定：同 day 两次快照不一致")
        return 1
    print("✓ 无未来函数（含 blk/ann）且判定可复现；day=%s 国家队/大基金 %d 只，锁定通道 %d 只"
          % (day, n_ok, n_open))
    return 0


def main():
    ap = argparse.ArgumentParser(description="国家队/大基金持有进出检测（数据层）")
    ap.add_argument("--build", action="store_true", help="抓全史 → 落盘 + 同步 APK assets")
    ap.add_argument("--codes", default="", help="指定标的（逗号分隔，缺省=行业ETF前五重仓）")
    ap.add_argument("--no-resume", action="store_true", help="忽略已完成，全量重抓")
    ap.add_argument("--show", action="store_true", help="最新季进出榜")
    ap.add_argument("--pool", default="", help="单只/多只诊断")
    ap.add_argument("--check", action="store_true", help="离线自检判定口径")
    ap.add_argument("--ann", action="store_true", help="②公告级（增减持/定增）直持榜")
    ap.add_argument("--sources", default="free,blk,ann",
                    help="抓取通道：free=季报十大流通股东 / blk=十大股东锁定 / ann=公告（逗号分隔）")
    ap.add_argument("--asof", default="", help="指定判定日（默认今天）")
    a = ap.parse_args()
    codes = [x for x in a.codes.split(",") if x.strip()]
    if a.build:
        src = tuple(x.strip() for x in a.sources.split(",") if x.strip())
        return 0 if build(codes or None, resume=not a.no_resume, sources=src,
                          day=a.asof or None) else 1
    if a.pool:
        return pool([x.strip().zfill(6) for x in a.pool.split(",")], a.asof or None)
    if a.check:
        return check()
    if a.ann:
        return ann_board(a.asof or None)
    return show(a.asof or None)


if __name__ == "__main__":
    sys.exit(main() or 0)
