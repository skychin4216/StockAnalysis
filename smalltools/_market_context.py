# -*- coding: utf-8 -*-
"""盘中市场上下文聚合器：为四大周期选股提供「异动/资金/热度/持仓/exe」共振信号（只读、容错降级）。

背景：_publish_candidates.py 之前每 30 分钟全量选股，评分只依赖形态(analyze_snaps)
与静态轮动，缺少盘中「正在发生」的资金与热度信号，且单独跑 _market_scan 做异动推送
价值有限。本模块把分散的信号聚合成一份轻量上下文 dict，供选股评分融合：

  1. 板块实时资金流（东财行业板块主力净流入，push2delay）  → 资金共振
  2. 日热门榜单（data/_daily_hot.json：成交/换手/涨幅/近20日）→ 当日热度共振
  3. 周/月热门（data/_daily_hot_history.json 连续上榜天数 +
     _daily_hot.json 的 month_sectors / month_stock_top）      → 持续性共振
  4. Android 实仓（real_positions，来自 COS phone zip 本地镜像）→ 持仓评估输入
  5. exe(AutoQuant) 最近一次 screen_report 命中              → 双端对比输入

全部数据源均可缺失/失败：任何异常只记 warn，不影响选股主流程（降级为纯形态）。
实时接口带 TTL 缓存（板块资金流 120s），避免守护轮询时重复请求拖慢节奏。

用法：
  python _market_context.py                # 打印上下文摘要（调试）
  python _market_context.py --flow 半导体   # 只查某板块资金流
"""
import json
import os
import sys
import time

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _sector_fundflow  # noqa: E402  (fetch_board_flow / ALIAS)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

DAILY_HOT_FILE = os.path.join(ROOT, "data", "_daily_hot.json")          # 日热门榜单快照
HIST_FILE = os.path.join(ROOT, "data", "_daily_hot_history.json")        # 多日上榜累计(=周级焦点)
EXE_REPORT = os.path.join(ROOT, "AutoQuant", "data", "screen_report_latest.json")
CLOUD_DIR = os.path.join(HERE, "_records", "cloud")                     # COS phone zip 镜像

# TTL 秒
FLOW_TTL = 120.0
FILE_TTL = 300.0

_cache = {}


def _cache_get(key, ttl):
    hit = _cache.get(key)
    if hit and time.time() - hit[0] < ttl:
        return hit[1]
    return None


def _cache_put(key, val):
    _cache[key] = (time.time(), val)


def trading_now(now=None):
    """A 股交易时段(工作日 9:30-11:30 / 13:00-15:00)。"""
    import datetime
    now = now or datetime.datetime.now()
    if now.weekday() >= 5:
        return False
    hm = now.hour * 60 + now.minute
    return (9 * 60 + 30) <= hm <= (11 * 60 + 30) or (13 * 60) <= hm <= (15 * 60)


def secid_of(code):
    """股票代码 → secid(sh/sz 前缀)。支持 605376 / sh605376 两种输入。"""
    c = str(code or "").strip().lower()
    if c.startswith(("sh", "sz")):
        return c
    if c.startswith(("6", "9")):
        return "sh" + c
    return "sz" + c


def _norm_code(c):
    c = str(c or "").strip().lower()
    if c.startswith(("sh", "sz")):
        return c[2:]
    return c


# ── 1. 板块实时资金流（东财行业板块） ───────────────────────────────────
def fetch_board_flow_cached(ttl=FLOW_TTL):
    """东财全行业板块主力资金流 {行业名: {main_yi, main_pct, zdf_pct, code}}。"""
    got = _cache_get("flow", ttl)
    if got is not None:
        return got
    try:
        got = _sector_fundflow.fetch_board_flow()
    except Exception as e:
        print("  [warn] 板块资金流获取失败: %s" % e)
        got = {}
    _cache_put("flow", got)
    return got


# ── 1.2 ETF 资金走向（东财 ETF 分类，f62 净流入近似） ────────────────
ETFS_HOST = "https://push2delay.eastmoney.com/api/qt/clist/get"
ETFS_UA = dict(_sector_fundflow.UA)  # 与板块资金流同配置(Referer)，防断连
ETFS_PX = dict(_sector_fundflow.PX)


def fetch_etf_flow(ttl=180.0):
    """拉 ETF 分类成交额榜(资金流向近似)。返回 [{'name', 'in_yi', 'chg_pct'}]。

    东财 ETF 无严格主力概念，用 f62(净流入近似)按成交额榜 f66 取前 15，
    净流入>0 的主题视为「资金正在买的方向」。失败返回 []。
    """
    got = _cache_get("etf_flow", ttl)
    if got is not None:
        return got
    rows = []
    params = {
        "pn": 1, "pz": 15, "po": 1, "np": 1,
        "ut": "bd1d9ddb04089700cf9c27f6f7426281",
        "fltt": 2, "invt": 2, "fid": "f66", "fs": "b:MK0021+b:MK0022+b:MK0023",
        "fields": "f12,f14,f2,f3,f62,f66",
    }
    try:
        query = "&".join("%s=%s" % (k, v) for k, v in params.items())
        d = requests.get(ETFS_HOST + "?" + query, timeout=10,
                         headers=ETFS_UA, proxies=ETFS_PX).json()
        for it in ((d.get("data") or {}).get("diff") or []):
            name = str(it.get("f14") or "")
            if not name:
                continue
            rows.append({
                "name": name.replace("ETF", "").strip(),
                "in_yi": (float(it.get("f62") or 0)) / 1e8,
                "chg_pct": float(it.get("f3") or 0),
            })
    except Exception as e:
        print("  [warn] ETF 资金流获取失败: %s" % e)
    _cache_put("etf_flow", rows)
    return rows


def fetch_intraday_prices(secids, ttl=60.0):
    """批量拉实时行情(腾讯 qt.gtimg.cn)。返回 {secid: {name, price, chg_pct, ts}}。

    只补池外持仓/临时需要的小批代码；TTL 60s 避免守护轮询频繁请求。
    """
    need = [s for s in (secids or []) if s]
    if not need:
        return {}
    got = {}
    fresh = []
    for s in need:
        hit = _cache_get("rt_" + s, ttl)
        if hit is not None:
            got[s] = hit
        else:
            fresh.append(s)
    for i in range(0, len(fresh), 40):
        batch = fresh[i:i + 40]
        url = "https://qt.gtimg.cn/q=" + ",".join(batch)
        try:
            r = requests.get(url, timeout=6,
                             headers={"User-Agent": "Mozilla/5.0"}, proxies={"http": None, "https": None})
            r.encoding = "gbk"
            for line in r.text.strip().split(";"):
                line = line.strip()
                if "=" not in line:
                    continue
                code = line.split("=")[0].replace("v_", "").strip()
                if code not in batch:
                    continue
                import re
                m = re.search(r'="([^"]*)"', line)
                if not m:
                    continue
                f = m.group(1).split("~")
                try:
                    rec = {"name": f[1], "price": float(f[3]),
                           "chg_pct": float(f[32]) if len(f) > 32 and f[32] else 0.0}
                except (ValueError, IndexError):
                    continue
                sid = code if code[:2] in ("sh", "sz") else secid_of(code)
                got[sid] = rec
                _cache_put("rt_" + sid, rec)
        except Exception as e:
            print("  [warn] 实时行情 %s 失败: %s" % (",".join(batch[:5]), e))
    return got


_FUZZY_STOP = {"其他", "其它", "-", "", "未知"}  # 泛化名禁止模糊匹配(如"线缆部件及其他"含"其他")


def flow_match(name, flow=None):
    """中文名 → 资金流条目。依次：精确 / 东财名包含候选名 / 候选名包含东财名 / ALIAS 反查。

    保护：候选 industry 常有"其他"兜底，禁止其模糊匹配到大板块名；
    2 字以内的短名只走精确(避免"电子"匹配到"电子化学品"之类的串扰)。
    """
    flow = flow or fetch_board_flow_cached()
    if not flow:
        return None
    n = str(name or "").strip()
    if not n or n in _FUZZY_STOP:
        return None
    if n in flow:
        return flow[n]
    if len(n) >= 3:
        for k, v in flow.items():
            if n in k or k in n:
                return v
    # ALIAS 反查：东财行业名 → 轮动/候选常用名
    rev = {}
    for em, alias in _sector_fundflow.ALIAS.items():
        rev.setdefault(alias, em)
    em_name = rev.get(n)
    if em_name and em_name in flow:
        return flow[em_name]
    return None


def flow_rank_of(flow=None):
    """按主力净流入排序的行业列表 [{name, main_yi, main_pct, zdf_pct}]，供 TOP 展示。"""
    flow = flow if flow is not None else fetch_board_flow_cached()
    rows = [{"name": k, **v} for k, v in flow.items()]
    rows.sort(key=lambda r: r.get("main_yi", 0), reverse=True)
    return rows


# ── 2. 日 / 周 / 月热门榜单 ────────────────────────────────────────────
def load_daily_hot():
    """读 data/_daily_hot.json。返回 dict 或 None。带 TTL 缓存。"""
    got = _cache_get("daily_hot", FILE_TTL)
    if got is not None:
        return got
    got = None
    try:
        with open(DAILY_HOT_FILE, encoding="utf-8") as f:
            got = json.load(f)
    except (OSError, ValueError) as e:
        print("  [warn] 日热门榜单读取失败: %s" % e)
    _cache_put("daily_hot", got)
    return got


def load_hot_history():
    """读 data/_daily_hot_history.json → {secid: {days, last_date, max_rank_bonus}}。"""
    got = _cache_get("hot_hist", FILE_TTL)
    if got is not None:
        return got
    got = {}
    try:
        with open(HIST_FILE, encoding="utf-8") as f:
            d = json.load(f)
        got = d.get("hits") or {}
    except (OSError, ValueError) as e:
        print("  [warn] 热门历史读取失败: %s" % e)
    _cache_put("hot_hist", got)
    return got


def _secids_from(items):
    out = set()
    for it in items or []:
        sid = it.get("secid")
        if sid:
            out.add(sid)
    return out


def hot_secids(daily_hot):
    """从榜单提取 secid 集合 → {维度: set}。日热门=当日榜单；月热门=近20日涨幅榜。"""
    if not daily_hot:
        return {}
    out = {
        "day_gain": _secids_from(daily_hot.get("gain_top")),          # 当日涨幅
        "day_amount": _secids_from(daily_hot.get("amount_top")),       # 当日成交额
        "day_turnover": _secids_from(daily_hot.get("turnover_top")),   # 当日换手
        "small_focus": _secids_from(daily_hot.get("small_focus")),     # 小市值焦点
        "month_stock": _secids_from(daily_hot.get("month_stock_top")),  # 近20日涨幅(=月热门股)
    }
    return out


def hot_sectors_rank(daily_hot):
    """月热门板块（近20日涨幅榜）。返回 [{name, chg20_pct, chg_pct}]。"""
    if not daily_hot:
        return []
    rows = []
    for it in daily_hot.get("month_sectors") or []:
        if isinstance(it, dict) and it.get("name"):
            rows.append({"name": it.get("name"), "chg20_pct": it.get("chg20_pct"),
                         "chg_pct": it.get("chg_pct")})
    return rows


# ── 3. Android 实仓 / exe 命中（双端对比输入） ─────────────────────────
def load_real_positions():
    """读本地最新的 COS phone zip 镜像里的 real_positions。

    返回 (positions, asof)：positions 为实体列表，字段与 Android 打包一致
    (stock_code, stock_name, period_type, quantity, avg_buy_price, buy_date,
     current_price, sector)。asof 为镜像目录名(phone_YYYYMMDD_HHMMSS)或 None。
    """
    try:
        dirs = [d for d in os.listdir(CLOUD_DIR)
                if os.path.isdir(os.path.join(CLOUD_DIR, d))] if os.path.isdir(CLOUD_DIR) else []
    except OSError:
        return [], None
    if not dirs:
        return [], None
    dirs.sort(key=lambda d: d.lower(), reverse=True)
    for d in dirs[:5]:
        f = os.path.join(CLOUD_DIR, d, "data.json")
        if not os.path.exists(f):
            continue
        try:
            with open(f, encoding="utf-8") as fp:
                data = json.load(fp)
            pos = data.get("real_positions") or []
            if not isinstance(pos, list):
                continue
            return pos, d
        except (OSError, ValueError):
            continue
    return [], None


def load_exe_report():
    """读 AutoQuant/data/screen_report_latest.json（exe 端最近一次选股命中）。

    返回 {"asof":..., "result": {周期: [{code,name,...}]}} 或 None。
    """
    got = _cache_get("exe_report", FILE_TTL)
    if got is not None:
        return got
    got = None
    try:
        with open(EXE_REPORT, encoding="utf-8") as f:
            got = json.load(f)
    except (OSError, ValueError):
        got = None
    _cache_put("exe_report", got)
    return got


# ── 4. 上下文聚合 ──────────────────────────────────────────────────────
def build_context(rotation=None):
    """聚合一份选股上下文 dict。rotation: 已算好的板块轮动列表(可选，避免重复计算)。

    返回 {
      ts, trading, asof?,
      flow: {行业: {...}}, flow_rank: [...],
      daily_hot: dict|None, hot_stale: bool(榜单是否非当日),
      day_hot_secids: {维度:set}, month_hot_secids:set,
      week_focus: {secid: days},        # 多日连续上榜
      month_sector_rank: [...],
      rot_sectors: {板块名: sec_mom},    # 轮动动量索引(候选 industry 用 name_hit 匹配)
      rot_catalyst: {板块名: 理由},
      positions: [...], positions_asof: str|None,
      exe: dict|None,
    }
    """
    import datetime
    now = datetime.datetime.now()
    flow = fetch_board_flow_cached()
    etf_flow = fetch_etf_flow()
    daily_hot = load_daily_hot()
    hist = load_hot_history()
    week_focus = {sid: int(v.get("days", 0) or 0) for sid, v in hist.items()
                  if isinstance(v, dict) and v.get("days")}
    ctx = {
        "ts": now.strftime("%Y-%m-%d %H:%M:%S"),
        "trading": trading_now(now),
        "flow": flow,
        "flow_rank": flow_rank_of(flow) if flow else [],
        "etf_flow": etf_flow,
        "daily_hot": daily_hot,
        "hot_stale": bool(daily_hot) and daily_hot.get("date") != now.strftime("%Y-%m-%d"),
        "day_hot_secids": hot_secids(daily_hot),
        "month_hot_secids": (_secids_from((daily_hot or {}).get("month_stock_top"))),
        "week_focus": week_focus,
        "month_sector_rank": hot_sectors_rank(daily_hot),
        "rot_sectors": {},
        "rot_catalyst": {},
        "positions": [],
        "positions_asof": None,
        "exe": None,
    }
    for r in rotation or []:
        ind = r.get("industry")
        if ind:
            ctx["rot_sectors"][ind] = float(r.get("sec_mom") or 0)
            if r.get("catalyst_reason"):
                ctx["rot_catalyst"][ind] = r["catalyst_reason"]
    positions, pos_asof = load_real_positions()
    ctx["positions"] = positions
    ctx["positions_asof"] = pos_asof
    ctx["exe"] = load_exe_report()
    return ctx


def name_hit(name, target, min_len=2):
    """宽松名称命中：全等 / 互相包含（"贵金属"∈"黄金贵金属"、"半导体"=="半导体"）。

    name 为兜底/泛化名(其他/其它/未知)或过短(默认 <2 字)时不做包含匹配，
    防"其他"误配"其他种植业"、防 1 字行业串扰。
    """
    if not name or not target:
        return False
    name = str(name).strip()
    target = str(target).strip()
    if name in _FUZZY_STOP or name == "":
        return False
    if name == target:
        return True
    if len(name) < min_len:
        return False
    return name in target or target in name


# ── 5. 共振评分（候选股 × 上下文 → 加权分 + 标签） ─────────────────────
def resonance_for(secid, industry, ctx):
    """给一只候选股算市场共振分。返回 {score, level, tags}。

    思路（综合网上主流的资金流确认 / 题材热度 / 轮动动量多因子共振）：
      - 板块主力净流入为正  → 资金确认（当天真金白银）
      - 所在板块在轮动前列  → 趋势主线
      - 个股连续多日上榜     → 市场焦点（周级持续性）
      - 个股出现在当日榜单/月度涨幅榜 → 热度确认
    分数只作排序/标签，不替代形态 gate；score>=5 视为「强共振」优先展示。
    """
    tags = []
    score = 0.0
    # ① 资金流：候选所属东财行业的主力净流入
    f = flow_match(industry, ctx.get("flow") or {}) if industry else None
    if f:
        my = float(f.get("main_yi") or 0)
        mp = float(f.get("main_pct") or 0)
        zdf = float(f.get("zdf_pct") or 0)
        if my > 0:
            score += 2.0
            tags.append("资金流入%+.1f亿(%s,板块%+.1f%%)" % (my, industry, zdf))
            if mp >= 3.0:
                score += 1.0
                tags.append("主力占比%.1f%%" % mp)
        elif my < -1.0:
            tags.append("资金流出%.1f亿(%s)" % (my, industry))
    # ①b ETF 资金走向：净流入主题命中候选行业 → 资金关注
    if industry:
        for etf in (ctx.get("etf_flow") or []):
            if float(etf.get("in_yi") or 0) > 0 and name_hit(industry, etf.get("name")):
                score += 1.0
                tags.append("ETF资金入%s%+.1f亿" % (etf.get("name"), etf.get("in_yi")))
                break
    # ② 轮动动量：板块是否在轮动 TOP 且动量强
    rot_mom = 0.0
    for ind, mom in (ctx.get("rot_sectors") or {}).items():
        if name_hit(industry or "", ind):
            rot_mom = max(rot_mom, float(mom))
    if rot_mom >= 15.0:
        score += 2.0
        tags.append("轮动强板块%+.1f%%" % rot_mom)
    elif rot_mom >= 8.0:
        score += 1.0
        tags.append("轮动板块%+.1f%%" % rot_mom)
    cat = next((v for k, v in (ctx.get("rot_catalyst") or {}).items()
                if name_hit(industry or "", k)), None)
    if cat:
        score += 1.0
        tags.append("催化:" + cat[:16])
    # ③ 周级焦点：连续多日上榜
    days = int((ctx.get("week_focus") or {}).get(secid, 0) or 0)
    if days >= 3:
        score += 2.0
        tags.append("连续%d日上榜" % days)
    elif days >= 2:
        score += 1.0
        tags.append("多日上榜(%d)" % days)
    # ④ 日/月热度确认
    day_sets = ctx.get("day_hot_secids") or {}
    if secid in day_sets.get("day_gain", set()):
        score += 0.5
        tags.append("当日涨幅榜")
    if secid in day_sets.get("day_amount", set()):
        score += 0.5
        tags.append("当日成交额榜")
    if secid in ctx.get("month_hot_secids", set()):
        score += 1.0
        tags.append("月度涨幅榜")
    # 行业层：月热门板块
    if industry:
        for s in (ctx.get("month_sector_rank") or [])[:8]:
            if name_hit(industry, s.get("name")):
                score += 1.0
                tags.append("板块月热%+.1f%%" % (s.get("chg20_pct") or 0))
                break
    if score >= 5.0:
        level = "强"
    elif score >= 2.0:
        level = "中"
    elif score > 0:
        level = "弱"
    else:
        level = ""
    return {"score": round(score, 1), "level": level, "tags": tags[:4]}


# ── 6. CLI 调试 ────────────────────────────────────────────────────────
def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--flow", help="只查指定板块资金流(可逗号分隔)")
    args = ap.parse_args()
    if args.flow:
        for n in args.flow.split(","):
            f = flow_match(n.strip())
            if f:
                print("%-10s 主力%+.2f亿 占比%+.2f%% 涨%+.2f%%" % (
                    n, f.get("main_yi", 0), f.get("main_pct", 0), f.get("zdf_pct", 0)))
            else:
                print("%-10s 未匹配到板块" % n)
        return
    ctx = build_context()
    print("== 市场上下文 %s 交易中:%s ==" % (ctx["ts"], ctx["trading"]))
    print("板块资金流 TOP8:")
    for r in ctx["flow_rank"][:8]:
        print("  %-10s 主力%+.2f亿 占比%+.2f%% 涨%+.2f%%" % (
            r["name"], r["main_yi"], r["main_pct"], r["zdf_pct"]))
    if ctx["daily_hot"]:
        stale = " (非当日,陈旧)" if ctx["hot_stale"] else ""
        print("日热门榜单 date=%s%s" % (ctx["daily_hot"].get("date"), stale))
        print("  日榜个股:%d/%d/%d 月涨榜:%d 月板块:%d" % (
            len(ctx["day_hot_secids"].get("day_gain", set())),
            len(ctx["day_hot_secids"].get("day_amount", set())),
            len(ctx["day_hot_secids"].get("day_turnover", set())),
            len(ctx["month_hot_secids"]), len(ctx["month_sector_rank"])))
    else:
        print("日热门榜单: 无")
    wf = ctx["week_focus"]
    print("周级焦点(连续上榜>=2): %d 只" % sum(1 for v in wf.values() if v >= 2))
    print("轮动板块: %s" % ", ".join("%s%+.1f%%" % (k, v)
                                    for k, v in sorted(ctx["rot_sectors"].items(),
                                                       key=lambda x: -x[1])[:8]) or "无")
    print("实仓: %d 条 (%s)" % (len(ctx["positions"]), ctx["positions_asof"]))
    exe = ctx["exe"]
    if exe:
        hit = sum(len(v) for v in (exe.get("result") or {}).values())
        print("exe 最近选股 asof=%s 命中%d" % (exe.get("asof"), hit))
    else:
        print("exe 报告: 无")


if __name__ == "__main__":
    main()
