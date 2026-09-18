# -*- coding: utf-8 -*-
"""持仓 + 板块资金流联合监控守护（2026-09-17 用户需求）。

设计口径：
  - 持仓清单 = data/_holdings.json（用户维护，每只票带阈值表）
  - 板块资金流 = 复用 _sector_fundflow.fetch_board_flow()
  - 节奏 = 1 小时一次（与 _market_scan 对齐）
  - 触发即推 1 条；非触发静默
  - 推送去重：
      · 持仓阈值：同一 (code, dim, op, val) 24h 内只推 1 次
      · 板块阈值：同一 (board, 方向)  4h  内只推 1 次（板块切换节奏快，回推即噪音）

监控维度：
  A. 持仓阈值（PB / 价格 / ROE / 营收% / 净利% / 分红率 / MACD / 行业景气）
  B. 板块资金流（单板块净流入/流出阈值 + 持仓票板块联动）

用法：
  python _holdings_flow_daemon.py --once             # 跑一次
  python _holdings_flow_daemon.py --daemon           # 守护模式（默认 3600s）
  python _holdings_flow_daemon.py --daemon --interval 1800  # 自定义间隔

依赖：
  · _sector_fundflow.py（板块资金流）    smalltools 内已有
  · push_channel.py（微信推送统一入口）   smalltools 内已有
  · _kline_store.py（K 线 MACD 计算）    smalltools 内已有
"""
import argparse
import datetime
import json
import os
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
ROOT = os.path.dirname(HERE)

import _sector_fundflow as sff  # noqa: E402
import push_channel  # noqa: E402

# 数据资产
HOLDINGS_JSON = os.path.join(ROOT, "data", "_holdings.json")
STATE_FILE = os.path.join(HERE, "_records", "_holdings_flow_state.json")
LAST_REPORT = os.path.join(HERE, "_records", "_holdings_flow_last.json")
EVENT_LIBRARY = os.path.normpath(os.path.join(
    ROOT, "app", "src", "main", "assets", "macro_events", "event_library.json"))

# 默认阈值
DEFAULT_INTERVAL = 3600              # 守护间隔（1h，与 _market_scan 对齐）
SECTOR_INFLOW_YI = 10.0              # 单板块主力净流入 ≥10 亿 → 推（避免噪音）
SECTOR_OUTFLOW_YI = -10.0            # 单板块主力净流出 ≤-10 亿 → 推
SECTOR_DELTA_YI = 5.0                # 环比变化 ≥5 亿 → 推
SECTOR_HOLD_LINK_YI = 1.0            # 持仓所在板块异动阈值（更敏感，因只对持仓板块触发，无噪音）
SECTOR_TOP_N = 5                     # 推送 TOP N（按净流入/流出排序）


def in_trading_time(now=None):
    """是否在 A 股交易时段（09:30-11:30 / 13:00-15:00）。"""
    import datetime
    now = now or datetime.datetime.now()
    if now.weekday() >= 5:  # 周六/日
        return False
    t = now.time()
    if datetime.time(9, 30) <= t <= datetime.time(11, 30):
        return True
    if datetime.time(13, 0) <= t <= datetime.time(15, 0):
        return True
    return False
HOLDING_DEDUP_HOURS = 24             # 持仓阈值去重窗口（24h）
SECTOR_DEDUP_HOURS = 4               # 板块阈值去重窗口（4h）

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
      "Referer": "https://quote.eastmoney.com/"}
PX = {"http": None, "https": None}


# ── 持仓清单加载 ─────────────────────────────────────────────────────
def load_holdings():
    """读 data/_holdings.json。失败返回 []。"""
    if not os.path.isfile(HOLDINGS_JSON):
        return []
    try:
        with open(HOLDINGS_JSON, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except (OSError, ValueError) as e:
        print("读取持仓清单失败:", type(e).__name__, e)
        return []


# ── 状态文件（推送去重） ─────────────────────────────────────────────
def _read_state():
    if not os.path.isfile(STATE_FILE):
        return {"holdings": {}, "sectors": {}}
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            d = json.load(f)
        d.setdefault("holdings", {})
        d.setdefault("sectors", {})
        return d
    except (OSError, ValueError):
        return {"holdings": {}, "sectors": {}}


def _write_state(d):
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
    os.replace(tmp, STATE_FILE)


def _dedup_ok(state_dict, key, window_hours):
    """state_dict[key] 距今 < window_hours 小时 → 已推送过，跳过；否则返回 True（可推）。"""
    if key in state_dict:
        try:
            last = datetime.datetime.strptime(state_dict[key], "%Y-%m-%d %H:%M:%S")
        except (ValueError, TypeError):
            return True
        age = datetime.datetime.now() - last
        if age.total_seconds() < window_hours * 3600:
            return False
    return True


def _mark_pushed(state_dict, key):
    state_dict[key] = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


# ── 持仓维度：实时行情（PB/PE） ──────────────────────────────────────
def _tencent_one(code6):
    """拉 1 只票的腾讯实时字段。返回 {price, pct, pe_rate, pb_rate} 或 {}。

    腾讯 q= 需带 sh/sz 前缀（沪市 sh+6位、深市 sz+6位），自动按 code 首位判定。
    字段位置（split("~") 后）：f3=现价, f32=涨跌%, f39=PE, f46=PB。
    """
    if not code6 or not code6.isdigit() or len(code6) != 6:
        return {}
    prefix = "sh" if code6.startswith("6") else "sz"
    full = prefix + code6
    try:
        r = requests.get("https://qt.gtimg.cn/q=" + full,
                         timeout=8, headers=UA, proxies=PX)
        r.encoding = "gbk"
    except Exception:
        return {}
    txt = r.text.strip()
    if "=" not in txt or '""' in txt:
        return {}
    try:
        f = txt.split('="', 1)[1].rstrip('"').split("~")
        if len(f) < 47:
            return {}
        return {
            "name": f[1],
            "price": float(f[3] or 0),
            "pct": float(f[32] or 0),
            "pe_rate": float(f[39] or 0) or None,
            "pb_rate": float(f[46] or 0) or None,
        }
    except (ValueError, IndexError):
        return {}


# ── 持仓维度：业绩（ROE/营收%/净利%）────────────────────────────────
def _em_perf_map(codes):
    """{code6: {ystz, sjltz, roe}}。复用 _board_peg_report.em_perf_map。"""
    try:
        import _board_peg_report as bpr
        perf = bpr.em_perf_map(codes, log=lambda m: print("  [perf]", m)) or {}
    except Exception as e:
        print("em_perf_map 拉取失败:", type(e).__name__, e)
        return {}
    return perf


# ── 持仓维度：MACD（基于 _kline_store） ───────────────────────────────
def _macd_of(secid):
    """拉日 K → 算 MACD 柱值（hist = DIF - DEA × 2）。"""
    try:
        import _kline_store as ks
        snap = ks.load_store()
        bars = snap.get(secid) or []
        if len(bars) < 26:
            return None
        closes = [float(b.get("close") or 0) for b in bars if b.get("close")]
        if len(closes) < 26:
            return None
        # EMA12 / EMA26 / DEA = EMA(DIF, 9)
        ema12 = closes[0]
        ema26 = closes[0]
        difs = []
        for c in closes:
            ema12 = ema12 * 11 / 13 + c * 2 / 13
            ema26 = ema26 * 25 / 27 + c * 2 / 27
            difs.append(ema12 - ema26)
        dea = difs[0]
        for d in difs:
            dea = dea * 8 / 10 + d * 2 / 10
        hist = (difs[-1] - dea) * 2
        return {"dif": difs[-1], "dea": dea, "hist": hist,
                "bull": hist > 0, "dead": hist < 0 and difs[-2] >= dea}
    except Exception as e:
        print("MACD 计算失败 %s: %s" % (secid, type(e).__name__, e))
        return None


# ── 加息周期阶段判定（2026-09-17 用户需求 ③）──────────────────────
def _fed_hike_phase(today=None):
    """读事件库，判定当前是否处于美联储加息周期及所处阶段。

    实证依据（learned_history.fed_rate_hike / docs/fed_hike_a_share_impact.md）：
      T+5 内   落地期（利空提前定价，勿恐慌）
      T+10~60  杀估值期（高 PE / 亏损票跌幅最深 → 高估值持仓要提示）
      T+60+    周期中段（冲击递减）
    首加息日取 instance.first_hike（无则回退 start）。
    返回 None（无活跃加息实例）或 {first_hike, days, phase}。
    """
    today = today or datetime.date.today()
    try:
        with open(EVENT_LIBRARY, encoding="utf-8") as f:
            lib = json.load(f)
    except (OSError, ValueError):
        return None
    for inst in lib.get("instances") or []:
        if inst.get("event") != "fed_rate_hike":
            continue
        try:
            s = datetime.date.fromisoformat(inst.get("start") or "")
            e = datetime.date.fromisoformat(inst.get("end") or "9999-12-31")
        except ValueError:
            continue
        if not (s <= today <= e):
            continue
        fh = inst.get("first_hike") or inst.get("start")
        try:
            fh_d = datetime.date.fromisoformat(fh)
        except (ValueError, TypeError):
            fh_d = s
        days = (today - fh_d).days
        if days < 0:
            phase = "加息预期期"
        elif days <= 10:
            phase = "落地期"
        elif days <= 60:
            phase = "杀估值期"
        else:
            phase = "周期中段"
        return {"first_hike": fh, "days": days, "phase": phase,
                "note": inst.get("note") or ""}
    return None


# ── 板块资金流（复用现成接口） ──────────────────────────────────────
def _board_flow():
    """全行业板块资金流。失败返回 {}。"""
    try:
        return sff.fetch_board_flow() or {}
    except Exception as e:
        print("板块资金流拉取失败:", type(e).__name__, e)
        return {}


# ── 持仓阈值触发检查 ────────────────────────────────────────────────
def _check_holding(h, quote, perf, macd, state, force_push=False):
    """单个持仓的阈值检查。返回 (messages: list[str], state_marks: list[tuple])。"""
    code = h.get("code") or ""
    name = h.get("name") or code
    msgs = []
    marks = []   # (state_key) 触发的去重 key

    # 实时行情维度：PB / 价格（PE 暂未列入阈值，但保留扩展位）
    if quote:
        for t in (h.get("thresholds") or []):
            dim = t.get("dim")
            op = t.get("op")
            val = t.get("val")
            action = t.get("action") or ""
            reason = t.get("reason") or ""
            # PB / 价格（来自腾讯实时）
            if dim == "PB":
                cur = quote.get("pb_rate")
                if cur is None or cur <= 0:
                    continue
            elif dim == "价格":
                cur = quote.get("price")
                if not cur or cur <= 0:
                    continue
            else:
                continue
            ok = (op == "<" and cur < val) or (op == ">" and cur > val)
            if ok:
                key = "holding:%s:%s:%s:%s" % (code, dim, op, val)
                if force_push or _dedup_ok(state["holdings"], key, HOLDING_DEDUP_HOURS):
                    emoji = {"减仓": "🟠", "兑现": "🔴", "加仓候选": "🟢",
                             "持有": "🟡", "止损": "🔴", "减半仓": "🟠"}.get(action, "⚠️")
                    msgs.append("%s %s(%s) %s %.2f %s阈值 %.2f → %s（%s）" % (
                        emoji, name, code, dim, cur,
                        "跌破" if op == "<" else "涨破", val, action, reason))
                    marks.append(key)
    # 业绩维度：ROE / 营收% / 净利%
    if perf:
        for t in (h.get("thresholds") or []):
            dim = t.get("dim")
            op = t.get("op")
            val = t.get("val")
            action = t.get("action") or ""
            reason = t.get("reason") or ""
            cur = None
            unit = "%"
            if dim == "ROE":
                cur = perf.get("roe")
            elif dim == "营收%":
                cur = perf.get("ystz")
            elif dim == "净利%":
                cur = perf.get("sjltz")
            if cur is None:
                continue
            try:
                cur = float(cur)
            except (ValueError, TypeError):
                continue
            ok = (op == "<" and cur < val) or (op == ">" and cur > val)
            if ok:
                key = "holding:%s:%s:%s:%s" % (code, dim, op, val)
                if force_push or _dedup_ok(state["holdings"], key, HOLDING_DEDUP_HOURS):
                    emoji = {"减仓": "🟠", "加仓候选": "🟢", "持有": "🟡",
                             "止损": "🔴"}.get(action, "⚠️")
                    msgs.append("%s %s(%s) %s %.2f%s %s %.2f%s → %s（%s）" % (
                        emoji, name, code, dim, cur, unit,
                        "跌破" if op == "<" else "涨破", val, unit,
                        action, reason))
                    marks.append(key)
    # MACD 维度
    if macd and macd.get("hist") is not None:
        for t in (h.get("thresholds") or []):
            if t.get("dim") != "MACD":
                continue
            op = t.get("op")
            val = float(t.get("val") or 0)
            cur = macd["hist"]
            ok = (op == "<" and cur < val) or (op == ">" and cur > val)
            if ok:
                key = "holding:%s:MACD:%s:%s" % (code, op, val)
                if _dedup_ok(state["holdings"], key, HOLDING_DEDUP_HOURS):
                    cross = ""
                    if macd.get("dead"):
                        cross = " · 死叉"
                    msgs.append("🔴 %s(%s) MACD 柱 %.3f %s %.2f%s → 止损（技术破位）" % (
                        name, code, cur,
                        "跌破" if op == "<" else "涨破", val, cross))
                    marks.append(key)
    return msgs, marks


# ── 板块阈值触发检查 ────────────────────────────────────────────────
def _check_sectors(flow, prev_flow, holdings_by_sector, state, force_push=False):
    """板块资金流阈值检查。

    flow: 当前全板块资金流 {name: {main_yi, ...}}
    prev_flow: 上一周期（用于算环比变化）
    holdings_by_sector: {sector_name: [holding_name, ...]} 用于持仓联动
    返回 (msgs, marks)。
    """
    msgs = []
    marks = []
    if not flow:
        return msgs, marks

    # 1) 单板块阈值触发（按净流入/流出排序，只推 TOP N）
    inflow = sorted([(n, d) for n, d in flow.items() if (d.get("main_yi") or 0) >= SECTOR_INFLOW_YI],
                    key=lambda x: -(x[1].get("main_yi") or 0))[:SECTOR_TOP_N]
    outflow = sorted([(n, d) for n, d in flow.items() if (d.get("main_yi") or 0) <= SECTOR_OUTFLOW_YI],
                     key=lambda x: (x[1].get("main_yi") or 0))[:SECTOR_TOP_N]

    # 流入 TOP N
    for name, d in inflow:
        main_yi = d.get("main_yi") or 0
        zdf = d.get("zdf_pct") or 0
        key = "sector:%s:in:abs" % name
        if force_push or _dedup_ok(state["sectors"], key, SECTOR_DEDUP_HOURS):
            msgs.append("💸 %s 主力净流入 %.1f亿（板块涨%+.2f%%）" % (
                name, main_yi, zdf))
            marks.append(key)

    # 流出 TOP N
    for name, d in outflow:
        main_yi = d.get("main_yi") or 0
        zdf = d.get("zdf_pct") or 0
        key = "sector:%s:out:abs" % name
        if force_push or _dedup_ok(state["sectors"], key, SECTOR_DEDUP_HOURS):
            msgs.append("💸 %s 主力净流出 %.1f亿（板块涨%+.2f%%）" % (
                name, main_yi, zdf))
            marks.append(key)

    # 2) 环比变化触发（≥ SECTOR_DELTA_YI 即推，1 个板块 1 条 delta）
    if prev_flow:
        deltas = []
        for name, d in flow.items():
            if name in prev_flow:
                prev_yi = prev_flow[name].get("main_yi") or 0
                cur_yi = d.get("main_yi") or 0
                delta = cur_yi - prev_yi
                if abs(delta) >= SECTOR_DELTA_YI:
                    deltas.append((name, d, delta))
        deltas.sort(key=lambda x: -abs(x[2]))
        for name, d, delta in deltas[:SECTOR_TOP_N]:
            direction = "in" if delta > 0 else "out"
            key = "sector:%s:%s:delta" % (name, direction)
            if force_push or _dedup_ok(state["sectors"], key, SECTOR_DEDUP_HOURS):
                zdf = d.get("zdf_pct") or 0
                msgs.append("📊 %s 主力%+.1f亿（环比%+.1f亿）板块涨%+.2f%%" % (
                    name, d.get("main_yi") or 0, delta, zdf))
                marks.append(key)

    # 3) 持仓票板块联动：持仓所在板块资金异动（更敏感阈值，模糊匹配）
    for sector, names in holdings_by_sector.items():
        if not sector:
            continue
        d = flow.get(sector)
        matched_name = sector
        if not d:
            # 模糊匹配：sector 含板块名 或 板块名含 sector
            for k, v in flow.items():
                if sector in k or k in sector:
                    d = v
                    matched_name = k
                    break
        if not d:
            continue
        main_yi = d.get("main_yi") or 0
        if abs(main_yi) >= SECTOR_HOLD_LINK_YI:
            key = "sector:%s:hold_link" % sector
            if force_push or _dedup_ok(state["sectors"], key, SECTOR_DEDUP_HOURS):
                arrow = "流入" if main_yi > 0 else "流出"
                holding_str = "、".join(names[:3])
                msgs.append("🎯 持仓联动：%s 主力净%s %.1f亿（%s）" % (
                    matched_name, arrow, main_yi, holding_str))
                marks.append(key)
    return msgs, marks


# ── 主流程：跑一次 ──────────────────────────────────────────────────
def run_once(force_push=False, dry=False):
    """跑一次检查 + 推送。force_push=True 忽略去重（手动测试用）。
    dry=True 只采集不推送（测试用）。
    返回推送条数（int）。

    分段推送：持仓阈值（PB/ROE/MACD/分红率）任何时间推；
    板块资金流仅交易时段推（盘中东财数据才有意义）。"""
    holdings = load_holdings()
    if not holdings:
        print("无持仓清单（%s 空或缺失）" % HOLDINGS_JSON)
        return 0
    print("[holdings] %d 只" % len(holdings))

    # 上一周期板块资金流（环比对比用）
    prev_flow = {}
    if os.path.isfile(LAST_REPORT):
        try:
            with open(LAST_REPORT, encoding="utf-8") as f:
                last = json.load(f)
            prev_flow = (last.get("flow") or {})
        except (OSError, ValueError):
            pass

    state = _read_state()
    if force_push:
        # 强制模式：临时清空去重，让所有阈值都触发一次（手动测试用）
        print("[force] 清空去重 state，全部阈值重新触发")
    h_msgs = []           # 持仓阈值消息（任何时间推）
    h_marks = []          # 持仓阈值触发的去重 key
    s_msgs = []           # 板块资金流消息（仅交易时段推）
    s_marks = []          # 板块资金流触发的去重 key

    # ── A. 持仓维度检查 ────────────────────────────────────────────
    if holdings:
        codes = [h.get("code") for h in holdings if h.get("code")]
        print("[A.持仓] 拉实时行情 ...")
        quotes = {}
        for h in holdings:
            c = h.get("code")
            if not c:
                continue
            q = _tencent_one(c)
            if q:
                quotes[c] = q
        print("  行情 %d/%d 成功" % (len(quotes), len(holdings)))

        print("[A.持仓] 拉业绩（ROE/营收%/净利%） ...")
        perf_map = _em_perf_map(codes)
        print("  业绩 %d/%d 票" % (len(perf_map), len(codes)))

        for h in holdings:
            code = h.get("code") or ""
            quote = quotes.get(code) or {}
            perf = perf_map.get(code) or {}
            macd = _macd_of(h.get("secid") or ("1." + code))
            msgs, marks = _check_holding(h, quote, perf, macd, state, force_push=force_push)
            if msgs:
                print("  [持仓触发] %s: %d 条" % (h.get("name"), len(msgs)))
            h_msgs.extend(msgs)
            h_marks.extend(marks)

    # ── A2. 加息周期杀估值闸（2026-09-17 用户需求 ③）─────────────────
    # 处于 fed_rate_hike 首加息后 10~60 日（实证杀估值期）时，
    # 对 PE>50 高估值或 PE<=0 亏损的持仓推风险提示（24h 去重，每票 1 条）。
    if holdings and quotes:
        ph = _fed_hike_phase()
        if ph:
            print("  [A2.加息闸] 活跃加息周期 → %s（首加 %s 后第 %d 天）" % (
                ph.get("phase"), ph.get("first_hike"), ph.get("days")))
            if ph.get("phase") == "杀估值期":
                for h in holdings:
                    code = h.get("code") or ""
                    q = quotes.get(code) or {}
                    pe = q.get("pe_rate")
                    if pe is None:
                        continue
                    if 0 < pe <= 50:
                        continue  # 低/合理估值不提示
                    risk = "亏损(PE=%.0f)" % pe if pe <= 0 else "高估值(PE=%.0f)" % pe
                    key = "fedhike:%s:valuation" % code
                    if force_push or _dedup_ok(state["holdings"], key, HOLDING_DEDUP_HOURS):
                        h_msgs.append("⚠️ 加息杀估值期(%s 后第%d天)：%s(%s) %s "
                                      "→ 历史实证 T+10~T+60 高估值/亏损票跌幅最深"
                                      "（C1 创-25%/C2 创-8%），注意仓位与止损" % (
                                          ph.get("first_hike"), ph.get("days"),
                                          h.get("name") or code, code, risk))
                        h_marks.append(key)

    # ── B. 板块资金流维度检查 ────────────────────────────────────────
    print("[B.板块] 拉全行业资金流 ...")
    flow = _board_flow()
    print("  板块 %d 个" % len(flow))

    # 持仓票按行业聚合（用于联动）
    by_sector = {}
    for h in holdings:
        s = h.get("sector") or ""
        if s:
            by_sector.setdefault(s, []).append(h.get("name") or h.get("code") or "")
    sm, sm_marks = _check_sectors(flow, prev_flow, by_sector, state, force_push=force_push)
    if sm:
        print("  [板块触发] %d 条" % len(sm))
    s_msgs.extend(sm)
    s_marks.extend(sm_marks)

    # ── 推送 ──────────────────────────────────────────────────────
    trading = in_trading_time()
    # 持仓消息：任何时间推（基本面信号与日内无关）
    # 板块消息：仅交易时段推（非交易时段东财数据无意义）
    push_h = h_msgs                  # 持仓 always push
    push_s = s_msgs if trading else []  # 板块 only push if trading
    push_msgs = push_h + push_s

    if push_msgs:
        now = datetime.datetime.now().strftime("%m-%d %H:%M")
        title = "持仓/板块监控 %s（%d 条）" % (now, len(push_msgs))
        body = "\n".join(push_msgs)
        print("\n[推送预览] %s\n%s" % (title, body))
        if dry:
            print("  [dry] 跳过推送")
        else:
            cfg = push_channel.load_notify_cfg()
            sent = push_channel.push(title, body, cfg, kind="notice")
            print("  推送结果:", "成功" if sent else "失败")
    else:
        # 静默也要写明非交易时段板块没推，避免误判守护异常
        if s_msgs and not trading:
            print("[非交易] 持仓无触发 · 板块 %d 条已采集（盘后东财数据无意义，未推）" % len(s_msgs))
        else:
            print("[静默] 无触发（持仓 %d 只 · 板块 %d 个）" % (
                len(holdings), len(flow)))

    # ── 写状态（去重）+ 写 last_report（环比基线） ─────────────────
    for k in h_marks:
        _mark_pushed(state["holdings"], k)
    for k in s_marks:
        _mark_pushed(state["sectors"], k)
    _write_state(state)
    try:
        with open(LAST_REPORT, "w", encoding="utf-8") as f:
            json.dump({
                "asof": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "flow": flow,
                "holdings_count": len(holdings),
                "h_msgs": len(h_msgs),
                "s_msgs": len(s_msgs),
                "pushed": len(push_msgs),
            }, f, ensure_ascii=False, indent=2)
    except OSError as e:
        print("写 last_report 失败:", e)
    return len(push_msgs)


# ── 守护主循环 ──────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser(description="持仓+板块资金流联合监控守护")
    ap.add_argument("--once", action="store_true", help="跑一次退出")
    ap.add_argument("--daemon", action="store_true", help="守护模式")
    ap.add_argument("--interval", type=int, default=DEFAULT_INTERVAL,
                    help="守护间隔秒数（默认 %d）" % DEFAULT_INTERVAL)
    ap.add_argument("--dry", action="store_true", help="只采集不推送（测试用）")
    ap.add_argument("--force", action="store_true", help="忽略去重强制推送")
    args = ap.parse_args()

    if args.daemon:
        print("持仓/板块监控守护启动：每 %d 秒一轮（默认）" % args.interval)
        while True:
            try:
                run_once(force_push=args.force, dry=args.dry)
            except Exception as e:
                print("轮询异常:", type(e).__name__, e)
            time.sleep(args.interval)
    else:
        return run_once(force_push=args.force, dry=args.dry)


if __name__ == "__main__":
    sys.exit(0 if main() is not None else 0)