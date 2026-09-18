# -*- coding: utf-8 -*-
"""复盘工具台 CLI（2026-09-18 转正；AutoQuant GUI「📝 复盘」页与命令行同一入口）。

来源：2026-09-17 深夜临时脚本转正；2026-09-18 收盘复盘三需求升级：
  · 信号票巡诊 signal（近N日逐票 1~5日/至今涨跌 + 统一表基本面8列 + 主力成本，涨红跌绿）
  · 实仓/埋伏/巡诊统一口径：名称 代码 主力成本 PE静 PB 营收% 净利% ROE% PEG 距60日高
  · 埋伏扫描补 KDJ/BOLL/均线粘合/换手/量比/VOL 技术列

    python _review_tools.py pick  [--days 6] [--push]   # 选股复盘：T+1/T+3胜率/超额 + 高PE占比
    python _review_tools.py signal [--days 5] [--push]  # 信号票巡诊：近N日逐票明细
    python _review_tools.py holdings [--push]           # 实仓风险扫描（A2闸口径）
    python _review_tools.py fedhike [--push]            # 美联储加息影响表
    python _review_tools.py ambush [--universe etf]     # 埋伏扫描：ETF全景(默认)/热门板块

数据：picks = _records/_pick_ledger.jsonl；行情 = data/kline_store.json +
新浪日K兜底合并（_Bars 新鲜度检查）；PE静/PB/换手/量比 = _sector_quote（腾讯）；
营收/净利/ROE = 东财 em_perf_map（当日缓存）；主力成本 = _chip_dist 移动筹码。
表格 PNG 落 data/review_tables/（涨红 #C62828 / 跌绿 #1B7A3D）。
"""
import argparse
import json
import os
import sys
import time
from datetime import datetime

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA = os.path.join(ROOT, "data")
TABLE_DIR = os.path.join(DATA, "review_tables")
LEDGER = os.path.join(HERE, "_records", "_pick_ledger.jsonl")
HOLDINGS = os.path.join(DATA, "_holdings.json")

sys.path.insert(0, HERE)
from _table_img import render_table  # noqa: E402

_UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
UP_RED, DN_GREEN = "#C62828", "#1B7A3D"


# ─────────────────────────── 行情/基本面取数 ───────────────────────────

def _tx_quotes(codes):
    """腾讯实时批量行情：{code: {name, price, pe, pb, turn, vr, amount_yi}}。"""
    out = {}
    bare2full = {c[2:]: c for c in codes if len(c) > 2}
    for i in range(0, len(codes), 50):
        batch = codes[i:i + 50]
        url = "https://qt.gtimg.cn/q=" + ",".join(batch)
        try:
            r = requests.get(url, headers=_UA, timeout=8)
            for line in r.content.decode("gbk", "ignore").split(";"):
                line = line.strip()
                if "=" not in line:
                    continue
                v = line.split("=", 1)[1].strip('"').split("~")
                if len(v) < 50 or not v[2]:
                    continue
                key = bare2full.get(v[2]) or v[2]   # 键 = 完整代码(sh600519)
                out[key] = {"name": v[1], "price": _f(v[3]), "pe": _f(v[39]),
                            "pb": _f(v[46]), "turn": _f(v[38]),
                            "vr": _f(v[49]), "amount_yi": _f(v[37]) and _f(v[37]) / 1e4}
        except Exception as e:  # noqa: BLE001
            print("腾讯行情失败: %r" % e, file=sys.stderr)
    return out


def _f(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def _tx_code(x):
    """任意代码格式 → 腾讯格式 sh600519。支持 em(1.600141)/裸码(600141)/前缀(sh600519)。"""
    c = str(x or "")
    if "." in c:
        return ("sh" if c.startswith("1.") else "sz") + c.split(".", 1)[1]
    if c[:2] in ("sh", "sz", "bj"):
        return c
    if not c:
        return c
    return ("sh" if c[0] in "569" else "sz") + c


def _sina_snaps(code, lmt=130):
    """新浪日K兜底（kline_store 缺票/落后期）：全字段 snaps（volume 统一为手）。"""
    try:
        url = ("https://quotes.sina.cn/cn/api/jsonp_v2.php/=/CN_MarketDataService."
               "getKLineData?symbol=%s&scale=240&ma=no&datalen=%d"
               % (code.lower(), lmt))
        t = requests.get(url, headers=dict(_UA, Referer="https://finance.sina.com.cn"),
                         timeout=8).text
        i = t.find("=(")               # 响应形如 "=([...])"
        j = t.rfind("]")
        if i < 0 or j < 0:
            return []
        arr = json.loads(t[i + 2:j + 1])
        snaps, prev = [], None
        for x in arr:
            c = _f(x.get("close"))
            snaps.append({
                "date": str(x.get("day") or "")[:10],
                "open": _f(x.get("open")), "high": _f(x.get("high")),
                "low": _f(x.get("low")), "close": c,
                "volume": (_f(x.get("volume")) or 0) / 100.0,   # 股 → 手（store 量纲）
                "changePct": ((c / prev - 1) * 100) if (c and prev) else 0.0,
            })
            prev = c
        return [s for s in snaps if s["close"]]
    except Exception:  # noqa: BLE001
        return []


class _Bars:
    """K线统一视图：kline_store 优先，新浪兜底合并（新鲜度检查 + 量纲统一）。"""

    def __init__(self):
        self._store = None
        self._miss = {}

    def _load(self):
        if self._store is None:
            try:
                from _kline_store import load_store
                self._store = load_store()
            except Exception as e:  # noqa: BLE001
                print("kline_store 加载失败: %r" % e, file=sys.stderr)
                self._store = {}
        return self._store

    def snaps(self, code):
        """完整 snaps（date/open/high/low/close/volume/changePct），升序。"""
        if code in self._miss:
            return self._miss[code]
        src = (self._load().get(code) or {}).get("snaps") or []
        snaps = [s for s in src if s.get("date") and s.get("close")]
        fb = _sina_snaps(code, 130)
        if fb and (not snaps or fb[-1]["date"] > snaps[-1]["date"]):
            fd = {s["date"] for s in fb}
            snaps = [s for s in snaps if s["date"] not in fd] + fb
        elif not snaps:
            snaps = fb
        self._miss[code] = snaps
        return snaps

    def series(self, code):
        return [(s["date"], s["close"]) for s in self.snaps(code)]

    def ret_fwd(self, code, d0, n):
        """d0 收盘 → n 个交易日后收盘的涨跌%；d0 不在序列返回 None。"""
        bars = self.series(code)
        ds = [d for d, _c in bars]
        if d0 not in ds:
            return None
        i = ds.index(d0)
        if i >= len(bars) - 1 or not bars[i][1]:
            return None
        k = min(n, len(bars) - 1 - i)
        if not bars[i + k][1]:
            return None
        return (bars[i + k][1] / bars[i][1] - 1) * 100


# ─────────────────────────── 技术指标（轻量，同统一表口径） ───────────────────────────

def _kdj_str(snaps, n=9):
    """KDJ(9,3,3)：返回 'J=72↑' / 'J=-5↓'（J>100 短期见顶 / <0 见底）。"""
    if len(snaps) < n + 2:
        return "—"
    k = d = 50.0
    j_hist = []
    for i in range(len(snaps)):
        win = snaps[max(0, i - n + 1):i + 1]
        hh = max(s["high"] or 0 for s in win)
        ll = min(s["low"] or 1e9 for s in win)
        c = snaps[i]["close"]
        rsv = 50.0 if hh <= ll or not c else (c - ll) / (hh - ll) * 100
        k = k * 2 / 3 + rsv / 3
        d = d * 2 / 3 + k / 3
        j_hist.append(3 * k - 2 * d)
    j, j_prev = j_hist[-1], j_hist[-2]
    if j != j:
        return "—"
    arrow = "↑" if j >= j_prev else "↓"
    return "J=%.0f%s" % (j, arrow)


def _boll_str(snaps, n=20, k=2.0):
    """BOLL(20,2) 位置：破上轨/上沿/中上/中下/下沿/破下轨。"""
    if len(snaps) < n:
        return "—"
    cs = [s["close"] for s in snaps[-n:]]
    mid = sum(cs) / n
    sd = (sum((c - mid) ** 2 for c in cs) / n) ** 0.5
    up, dn = mid + k * sd, mid - k * sd
    c = snaps[-1]["close"]
    if up <= dn:
        return "—"
    p = (c - dn) / (up - dn)
    if p > 1:
        return "↑破上轨"
    if p > 0.75:
        return "上轨下沿"
    if p > 0.5:
        return "中上"
    if p > 0.25:
        return "中下"
    if p > 0:
        return "下轨上沿"
    return "↓破下轨"


def _ma_str(snaps):
    """MA5/10/20 形态：多头排列 / 粘合 / 发散。"""
    cs = [s["close"] for s in snaps]
    if len(cs) < 20:
        return "—"
    ma5 = sum(cs[-5:]) / 5
    ma10 = sum(cs[-10:]) / 10
    ma20 = sum(cs[-20:]) / 20
    if ma5 > ma10 > ma20:
        return "多头"
    if ma20 and (max(ma5, ma10, ma20) - min(ma5, ma10, ma20)) / ma20 < 0.02:
        return "粘合"
    return "发散"


def _vol_str(snaps, amount_yi=None, vr=None):
    """VOL：当日成交额（亿）+ 放/缩量标注（量比口径）。"""
    amt = amount_yi
    if amt is None and len(snaps) >= 2:
        s = snaps[-1]
        amt = (s["close"] or 0) * (s["volume"] or 0) * 100 / 1e8   # 手→股→元
    if amt is None or amt <= 0:
        return "—"
    tag = ""
    if vr is not None:
        tag = "↑放" if vr >= 1.2 else ("↓缩" if vr <= 0.8 else "")
    return ("%.1f亿%s" % (amt, tag)) if amt >= 1 else ("%.0f万%s" % (amt * 1e4, tag))


def _dd60(snaps):
    """距 60 日最高收盘 %。"""
    cs = [s["close"] for s in snaps[-60:] if s["close"]]
    if not cs:
        return None
    return (cs[-1] / max(cs) - 1) * 100


def _fund_map(codes_full):
    """统一表基本面 + 换手/量比：{full_code: {pe_static,pb,ystz,sjltz,roe,peg,turn,vr}}。

    PE静/PB/换手/量比 ← _sector_quote.quotes（腾讯批量）；
    营收%/净利%/ROE% ← _board_peg_report.em_perf_map（东财，当日缓存）；
    PEG = PE静 ÷ 营收增速（同统一表口径，PE≤0/增速≤0/>50 → None）。
    """
    out = {c: {} for c in codes_full}
    if not codes_full:
        return out
    q = {}
    try:
        from _sector_quote import quotes
        q = quotes([(c, c[2:], "") for c in codes_full]) or {}
    except Exception as e:  # noqa: BLE001
        print("基本面行情失败: %r" % e, file=sys.stderr)
    perf = {}
    try:
        from _board_peg_report import em_perf_map
        perf = em_perf_map([c[2:] for c in codes_full]) or {}
    except Exception as e:  # noqa: BLE001
        print("业绩取数失败: %r" % e, file=sys.stderr)
    for c in codes_full:
        qq, pf = q.get(c) or {}, perf.get(c[2:]) or {}
        pe = qq.get("pe_static")
        if not pe or pe <= 0:
            pe = None
        pb = qq.get("pb")
        if not pb or pb <= 0:
            pb = None
        ystz = pf.get("ystz")
        peg = None
        if pe and ystz and ystz > 0:
            peg = pe / ystz if pe / ystz <= 50 else None
        out[c] = {"pe_static": pe, "pb": pb, "ystz": ystz,
                  "sjltz": pf.get("sjltz"), "roe": pf.get("roe"), "peg": peg,
                  "turn": qq.get("turn"), "vr": qq.get("vr")}
    return out


def _main_cost(snaps):
    """主力成本（移动筹码：前30%大成交日按量加权典型价）。"""
    try:
        from _chip_dist import compute
        r = compute(snaps[-120:])
        return r.get("main_cost") if r and "error" not in r else None
    except Exception:  # noqa: BLE001
        return None


def _risk_level(pe):
    if pe is None:
        return "—", ""
    if pe < 0:
        return "高风险", "亏损"
    if pe > 50:
        return "高风险", "高估值PE%g" % pe
    if pe > 30:
        return "中风险", "PE%g偏高" % pe
    return "低风险", "PE合理"


def _f1(v):
    return "%.1f" % v if isinstance(v, (int, float)) and v == v else "—"


def _f2(v):
    return "%.2f" % v if isinstance(v, (int, float)) and v == v else "—"


def _pct(v):
    if not isinstance(v, (int, float)) or v != v:
        return "—"
    return "%+.0f%%" % v


def _pct1(v):
    if not isinstance(v, (int, float)) or v != v:
        return "—"
    return "%+.1f%%" % v


# ─────────────────────────── 输出/推送 ───────────────────────────

def _emit(title, header, rows, png_name, note="", push=False, log=print,
          cell_colors=None, color_cols=None):
    os.makedirs(TABLE_DIR, exist_ok=True)
    png = os.path.join(TABLE_DIR, png_name)
    try:
        render_table(title, header, rows, png, note=note or None,
                     cell_colors=cell_colors)
    except Exception as e:  # noqa: BLE001
        png = ""
        log("PNG 渲染失败: %r" % e)
    pushed = False
    if push:
        try:
            import push_channel
            cfg = push_channel.load_notify_cfg()
            if png:
                pushed = bool(push_channel.send_image(png, cfg))
            pushed = bool(push_channel.push(
                title, "\n".join(" | ".join(map(str, r)) for r in rows[:12]),
                cfg)) or pushed
        except Exception as e:  # noqa: BLE001
            log("推送失败: %r" % e)
    return {"ok": True, "title": title, "header": header, "rows": rows,
            "png": png, "note": note, "pushed": pushed,
            "color_cols": color_cols or []}


def _pct_color(val):
    """数值 → PNG 前景色（涨红/跌绿/None 默认）。"""
    if not isinstance(val, (int, float)) or val != val:
        return None
    return UP_RED if val > 0 else (DN_GREEN if val < 0 else None)


# ─────────────────────────── ① 选股复盘（每日汇总） ───────────────────────────

def cmd_pick(days=6, push=False):
    """近 N 日选股复盘：每日 T+1/T+3 胜率/均收益/超额 vs 上证 + 高PE/亏损占比。"""
    if not os.path.isfile(LEDGER):
        return {"ok": False, "error": "无推送台账 _pick_ledger.jsonl"}
    recs = []
    with open(LEDGER, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                recs.append(json.loads(line))
            except ValueError:
                pass
    by_day = {}
    for r in recs:
        d = str(r.get("asof") or "")[:10]
        if d:
            by_day[d] = r.get("picks") or []      # 同日多条取最后一条
    days_list = sorted(by_day)[-days:]
    if not days_list:
        return {"ok": False, "error": "台账无选股记录"}

    bars = _Bars()
    quotes = _tx_quotes(sorted({p.get("secid") for d in days_list
                                for p in by_day[d] if p.get("secid")}))
    header = ["日期", "只数", "T+1胜%", "T+1均%", "T+1超额%", "T+3胜%",
              "T+3均%", "T+3超额%", "至今均%", "高PE/亏损%", "最差票"]
    rows = []
    for d in days_list:
        picks = {p["secid"]: p for p in by_day[d] if p.get("secid")}
        t1, t3, to_now, hi_pe = [], [], [], 0
        worst = ("—", 0.0)
        for sid, p in picks.items():
            r1 = bars.ret_fwd(sid, d, 1)
            r3 = bars.ret_fwd(sid, d, 3)
            rn = bars.ret_fwd(sid, d, 999)
            if r1 is not None:
                t1.append(r1)
            if r3 is not None:
                t3.append(r3)
            if rn is not None:
                to_now.append(rn)
                if rn < worst[1]:
                    worst = (p.get("name") or sid, rn)
            pe = (quotes.get(sid) or {}).get("pe")
            if pe is None or pe < 0 or pe > 50:
                hi_pe += 1
        i1 = bars.ret_fwd("sh000001", d, 1) or 0.0
        i3 = bars.ret_fwd("sh000001", d, 3) or 0.0

        def _avg(xs):
            return sum(xs) / len(xs) if xs else None

        def _win(xs):
            return (len([x for x in xs if x > 0]) / len(xs) * 100) if xs else None

        rows.append([
            d[5:], len(picks),
            _fmt(_win(t1), 0), _fmt(_avg(t1)), _sub(_avg(t1), i1),
            _fmt(_win(t3), 0), _fmt(_avg(t3)), _sub(_avg(t3), i3),
            _fmt(_avg(to_now)),
            "%.0f%%" % (hi_pe / len(picks) * 100) if picks else "—",
            "%s %.1f%%" % worst if worst[1] < 0 else worst[0],
        ])
    all_t1 = [x for d in days_list for x in _day_list(bars, by_day[d], d, 1)]
    all_t3 = [x for d in days_list for x in _day_list(bars, by_day[d], d, 3)]
    rows.append(["合计%d日" % len(days_list), "",
                 _fmt(_win(all_t1), 0), _fmt(_avg(all_t1)), "",
                 _fmt(_win(all_t3), 0), _fmt(_avg(all_t3)), "", "", "", ""])
    note = ("口径：T+n=选股日收盘→n个交易日后收盘；超额=同口径上证对比；"
            "高PE/亏损=当前PE>50或<0（A2杀估值闸口径）。数据=kline_store+新浪兜底。")
    return _emit("选股复盘（近%d日 T+1/T+3）" % len(days_list), header, rows,
                 "选股复盘_%s.png" % datetime.now().strftime("%Y%m%d_%H%M%S"),
                 note, push=push)


def _day_list(bars, picks, d, n):
    out = []
    for p in picks:
        r = bars.ret_fwd(p.get("secid"), d, n)
        if r is not None:
            out.append(r)
    return out


def _fmt(x, nd=1):
    return ("%%.%df" % nd) % x if x is not None else "—"


def _sub(a, b):
    return a - b if a is not None and b is not None else None


# ─────────────────────────── ② 信号票巡诊（近N日逐票明细） ───────────────────────────

def _ledger_days(days):
    """{asof: {secid: pick}}（同日多条取最后一条；picks 带 secid）。"""
    by_day = {}
    if os.path.isfile(LEDGER):
        with open(LEDGER, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    r = json.loads(line)
                except ValueError:
                    continue
                d = str(r.get("asof") or "")[:10]
                if d:
                    by_day[d] = {p["secid"]: p for p in (r.get("picks") or [])
                                 if p.get("secid")}
    return {d: by_day[d] for d in sorted(by_day)[-days:] if by_day[d]}


def cmd_signal(days=5, push=False):
    """近 N 日信号票巡诊：逐票统一表基本面 + 主力成本 + 入选后 1~5日/至今涨跌（涨红跌绿）。"""
    sel = _ledger_days(days)
    if not sel:
        return {"ok": False, "error": "台账无近%d日选股记录" % days}
    bars = _Bars()
    # 同一票多日入选 → 保留最早入选日（巡诊看全程）
    first = {}
    for d in sorted(sel):
        for sid, p in sel[d].items():
            first.setdefault(sid, (d, p))
    fund = _fund_map(list(first))
    header = ["名称", "代码", "主力成本", "PE静", "PB", "营收%", "净利%", "ROE%",
              "PEG", "距60高", "周期", "入选日", "入选价",
              "1日", "2日", "3日", "4日", "5日", "至今", "来源"]
    rows, colors = [], []
    color_cols = list(range(13, 19))            # 1日 ~ 至今：涨红跌绿
    for sid, (d, p) in sorted(first.items(), key=lambda kv: kv[1][0], reverse=True):
        snaps = bars.snaps(sid)
        fu = fund.get(sid) or {}
        dd = _dd60(snaps)
        ser = dict(bars.series(sid))
        base = ser.get(d)                        # 入选日收盘价
        rts = [bars.ret_fwd(sid, d, n) for n in (1, 2, 3, 4, 5, 999)]
        row = [p.get("name") or sid, sid[2:], _f2(_main_cost(snaps)),
               _f1(fu.get("pe_static")), _f2(fu.get("pb")),
               _pct(fu.get("ystz")), _pct(fu.get("sjltz")), _f1(fu.get("roe")),
               _f2(fu.get("peg")), _pct1(dd), str(p.get("period") or "—"),
               d[5:], _f2(base)]
        row += [_pct1(r) for r in rts]
        row.append(str(p.get("src") or "—"))
        rows.append(row)
        colors.append([None] * 13 + [_pct_color(r) for r in rts] + [None])
    note = ("近%d日信号票 %d 只（同票取最早入选日）。主力成本=移动筹码(前30%%大成交日"
            "量加权)；PE静/PB/换手=腾讯；营收/净利/ROE=东财最新报告期；"
            "N日=入选日收盘→N个交易日后收盘；红=涨 绿=跌。" % (days, len(rows)))
    return _emit("信号票巡诊（近%d日 %d 只）" % (days, len(rows)), header, rows,
                 "信号票巡诊_%s.png" % datetime.now().strftime("%Y%m%d_%H%M%S"),
                 note, push=push, cell_colors=colors, color_cols=color_cols)


# ─────────────────────────── ③ 实仓风险扫描 ───────────────────────────

def cmd_holdings(push=False):
    """实仓：统一表基本面8列 + 主力成本 + 距60高 + 浮盈 + 风险级（A2 杀估值口径）。"""
    if not os.path.isfile(HOLDINGS):
        return {"ok": False, "error": "无 data/_holdings.json"}
    with open(HOLDINGS, encoding="utf-8") as f:
        hs = json.load(f)
    bars = _Bars()
    codes = [_tx_code(h.get("secid") or h.get("code")) for h in hs]
    quotes = _tx_quotes(codes)
    fund = _fund_map(codes)
    header = ["持仓", "代码", "主力成本", "PE静", "PB", "营收%", "净利%", "ROE%",
              "PEG", "距60高", "现价", "持仓成本", "浮盈%", "风险级", "提示"]
    rows, colors, n_hi = [], [], 0
    for h, c in zip(hs, codes):
        snaps = bars.snaps(c)
        q = quotes.get(c) or {}
        fu = fund.get(c) or {}
        avg = h.get("avg") or 0
        pnl = (q.get("price") / avg - 1) * 100 if q.get("price") and avg else None
        lvl, why = _risk_level(fu.get("pe_static") or q.get("pe"))
        if lvl == "高风险":
            n_hi += 1
            why += " · A2杀估值窗口(T+10~60)优先处理"
        rows.append([h.get("name", ""), c[2:], _f2(_main_cost(snaps)),
                     _f1(fu.get("pe_static")), _f2(fu.get("pb")),
                     _pct(fu.get("ystz")), _pct(fu.get("sjltz")), _f1(fu.get("roe")),
                     _f2(fu.get("peg")), _pct1(_dd60(snaps)),
                     _f2(q.get("price")), _f2(avg), _pct1(pnl), lvl, why])
        colors.append([None] * 12 + [_pct_color(pnl), None, None])
    note = ("实仓 %d 只，高风险 %d 只。风险级：亏损/PE静>50=高，30~50=中，其余低；"
            "主力成本=移动筹码；PE静/PB=腾讯，业绩=东财最新报告期。"
            % (len(hs), n_hi))
    return _emit("实仓风险扫描（A2 杀估值口径）", header, rows,
                 "实仓风险扫描_%s.png" % datetime.now().strftime("%Y%m%d_%H%M%S"),
                 note, push=push, cell_colors=colors, color_cols=[12])


# ─────────────────────────── ④ 加息影响表 ───────────────────────────

_FED_TIMELINE = [
    ["黄金/贵金属", "中性", "利空", "利空最深(实际利率升)", "末加确认后转最强"],
    ["铜/铝/稀有金属", "中性偏空", "利空", "利空(美元走强)", "看国内需求对冲"],
    ["煤炭", "中性", "抗跌", "C2实证领涨", "跟煤价，与美联储关系弱"],
    ["银行/红利/公用", "微利好", "抗跌", "抗跌(C2绝对收益正)", "稳，回撤最小"],
    ["高PE成长(半导体/军工/创新药)", "波动大无方向", "利空", "利空最深(C1创业板-55%)", "修复1.8~2.3年"],
    ["出口链(纺服/家电/航运)", "中性", "微利好", "微利好(人民币贬)", "后期看海外需求"],
    ["北向重仓白马/港股", "偏空", "利空", "利空(外资回流)", "末加后修复较快"],
]

_FED_CONCL = [
    ["第一周", "利空已被抢跑定价(C1/C2实证T+5全指数±1.1%内) → 不操作"],
    ["T+10~60", "真正杀估值期，杀序：高PE成长 > 金铜 > 白马 > 煤炭银行"],
    ["关键变量", "国内货币方向：宽松(本轮C2型)=只有成长跌，红利煤炭领涨"],
    ["一年内", "低点在末加前1~3月；先买黄金/铜，成长等国内政策反转"],
    ["本轮定位", "C3(2026-09-17首加)对标C2：沪强成长弱"],
]


def cmd_fedhike(push=False):
    """美联储加息对A股影响表：核心结论 + 时间轴板块矩阵 + 实仓动态扫描。"""
    header = ["项目", "第一周(T+5)", "一个月(T+20)", "前半年(T+60~120)", "一年内"]
    rows = [["【核心结论】", "", "", "", ""]]
    for k, v in _FED_CONCL:
        rows.append([k, v, "", "", ""])
    rows.append(["【时间轴板块矩阵】", "", "", "", ""])
    for r in _FED_TIMELINE:
        rows.append(r)
    if os.path.isfile(HOLDINGS):
        with open(HOLDINGS, encoding="utf-8") as f:
            hs = json.load(f)
        codes = [_tx_code(h.get("secid") or h.get("code")) for h in hs]
        quotes = _tx_quotes(codes)
        fund = _fund_map(codes)
        rows.append(["【实仓扫描】持仓", "PE静", "PB", "风险级", "提示"])
        for h, c in zip(hs, codes):
            q = quotes.get(c) or {}
            fu = fund.get(c) or {}
            lvl, why = _risk_level(fu.get("pe_static") or q.get("pe"))
            if lvl == "低风险":
                why = "抗跌列，持有逻辑不受本次加息直接冲击"
            elif lvl == "高风险":
                why = "利空最重列，T+10起杀估值窗口优先处理"
            rows.append([h.get("name", ""), _f1(fu.get("pe_static")),
                         _f2(fu.get("pb")), lvl, why])
    note = "C1=2004-06 / C2=2022-03 实证；C3=2026-09-17 首加。实仓 PE静/PB 为腾讯实时值。"
    return _emit("美联储加息对A股影响（复盘工具台）", header, rows,
                 "美联储加息对A股影响_%s.png" % datetime.now().strftime("%Y%m%d_%H%M%S"),
                 note, push=push)


# ─────────────────────────── ⑤ 埋伏扫描 ───────────────────────────

def cmd_ambush(universe="etf", push=False):
    """跑 XML DAG 埋伏 usecase；命中票补全技术列 + 基本面列（统一表口径）。"""
    uc = os.path.join(ROOT, "app", "src", "main", "assets", "usecases")
    aq = os.path.join(ROOT, "AutoQuant")
    for p in (uc, aq, HERE):
        if p not in sys.path:
            sys.path.insert(0, p)
    usecase = "etf_ambush" if universe == "etf" else "sector_ambush"
    try:
        import usecase_pipeline as up
        from _full_cycle_backtest import load_cache
        r = up.UseCaseRunner(usecase, cache=load_cache())
        r.run()
        sig = r.ctx.stage_outputs.get("n_ambush_signal") or {}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": "埋伏引擎执行失败: %r" % e}
    rows_in = sig.get("rows") or []
    bars = _Bars()
    fund = _fund_map([_tx_code(r2.get("code")) for r2 in rows_in if r2.get("code")])
    header = ["名称", "代码", "主题/板块", "信号日", "连阳", "KDJ", "BOLL", "均线",
              "换手", "量比", "VOL", "距60高", "OBV", "洗盘", "评分", "收盘",
              "PE静", "PB"]
    rows = []
    for r2 in rows_in:
        c = _tx_code(r2.get("code"))
        snaps = bars.snaps(c)
        fu = fund.get(c) or {}
        vr = fu.get("vr") or r2.get("vr")
        rows.append([
            r2.get("name", ""), c[2:], r2.get("sector", ""), r2.get("date", ""),
            r2.get("bull", ""), _kdj_str(snaps), _boll_str(snaps), _ma_str(snaps),
            (_f1(fu.get("turn")) + "%") if fu.get("turn") is not None else "—",
            _f1(vr), _vol_str(snaps, None, vr), _pct1(_dd60(snaps)),
            "↑" if r2.get("obvUp") else "↓",
            "✓" if r2.get("wash") else "—", _fmt(r2.get("score")),
            _f2(r2.get("close")), _f1(fu.get("pe_static")), _f2(fu.get("pb")),
        ])
    hot = "、".join(sig.get("hotSectors") or []) or "—"
    note = ("候选{u}只/扫描{s}只/命中{n}只 | 热门主题：{h} | "
            "规则：2~3连阳+多头排列+梯量(1.2~3倍)+OBV上行+洗盘不破MA20+距60高-25%~-1%"
            " | KDJ/BOLL/均线/VOL=日K现算；换手/量比/PE静/PB=腾讯实时"
            ).format(u=sig.get("universe", len(rows)), s=sig.get("scanned", 0),
                     n=len(rows), h=hot)
    title = "埋伏扫描·%s（%s）" % ("ETF全景" if universe == "etf" else "热门板块",
                                 str(sig.get("as_of") or ""))
    return _emit(title, header, rows,
                 "埋伏扫描_%s_%s.png" % (universe, datetime.now().strftime("%Y%m%d_%H%M%S")),
                 note, push=push)


# ─────────────────────────── main ───────────────────────────

def main():
    ap = argparse.ArgumentParser(description="复盘工具台（GUI/CLI 共用）")
    ap.add_argument("cmd", choices=["pick", "signal", "holdings", "fedhike", "ambush"])
    ap.add_argument("--days", type=int, default=None,
                    help="pick=6 signal=5")
    ap.add_argument("--universe", default="etf", choices=["etf", "hot_sector"])
    ap.add_argument("--push", action="store_true")
    ap.add_argument("--no-json", action="store_true", help="人类可读输出")
    a = ap.parse_args()
    if a.days is None:
        a.days = 5 if a.cmd == "signal" else 6
    t0 = time.time()
    if a.cmd == "pick":
        res = cmd_pick(days=a.days, push=a.push)
    elif a.cmd == "signal":
        res = cmd_signal(days=a.days, push=a.push)
    elif a.cmd == "holdings":
        res = cmd_holdings(push=a.push)
    elif a.cmd == "fedhike":
        res = cmd_fedhike(push=a.push)
    else:
        res = cmd_ambush(universe=a.universe, push=a.push)
    res["elapsed_s"] = round(time.time() - t0, 1)
    if a.no_json or not res.get("ok", True):
        print(json.dumps(res, ensure_ascii=False, indent=1))
    else:
        print("__JSON__" + json.dumps(res, ensure_ascii=False))
    return 0 if res.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
