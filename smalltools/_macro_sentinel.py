# -*- coding: utf-8 -*-
"""四根宏观哨兵（2026-09-10 用户定规则）：美债 / 日元 / 油价 / 费半 → 定向风控建议。

触发规则：
  ① 10Y 美债 > 5.0% 或 30Y 破 5.5%  → 全球成长降权，A股科技/成长进攻仓位收敛
  ② USDJPY 跌破 148                → carry unwind 警报，当日全球科技风险仓降配
  ③ WTI 站稳 100 达 5 个交易日      → 油链/油服/化工上游加配、科技减配
  ④ 费半隔夜跌幅 > 3%              → 次日 A 股第一层供应链(光模块/PCB/半导体设备)
                                      开盘不追高，可等恐慌低吸

数据源（2026-09-10 三源实测）：
  - 美债 10Y/30Y: FRED DGS10/DGS30 公开 CSV（滞后约 1 交易日，节假日为空行跳过）
  - USDJPY:       东财 push2 ulist 119.USDJPY（fltt=2），失败回退新浪 fx_susdjpy
  - WTI:          新浪 hq.sinajs.cn hf_CL（外盘期货格式: idx0=现价 idx7=昨结）
                  失败回退腾讯 qt.gtimg.cn hf_CL
  - 费半 SOX:     新浪 hq.sinajs.cn gb_$sox（idx0=名称 idx1=收盘 idx2=涨跌幅%）

状态/每日收盘积累：smalltools/_macro_sentinel_cache.json
  - y10/y30: FRED 每日拉一次（按本地日期去重，避免盘中反复拉 1MB CSV）
  - wti_daily: {date: 当日最近快照价}，供「站稳 100 N 日」连续判定（自积累，
    无外部历史日K源；低频极端事件从监控首日起算足够）

用法：
  python _macro_sentinel.py            # 渲染文本行（守护晨报/午间/收盘内嵌）
  python _macro_sentinel.py --check    # 只输出状态 json
对外：
  render_sentinel_lines() -> list[str]  供 _publish_candidates / _market_scan 内嵌
  refresh() -> dict                      状态全量（含 alerts，供增量 diff）
  alerts_of(state) -> list[dict]        {id, text}；scan 用 id 去重做「新越阈提醒」
"""
import argparse
import datetime as _dt
import json
import os

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_FILE = os.path.join(HERE, "_macro_sentinel_cache.json")

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
SINA_H = {"Referer": "https://finance.sina.com.cn", **UA}

# ── 阈值（用户 2026-09-10 口径）────────────────────────────────────────
Y10_ALERT = 5.0     # 10Y 美债 %，高于触发
Y30_ALERT = 5.5     # 30Y 美债 %，高于触发
USDJPY_ALERT = 148.0  # USDJPY 跌破触发（日元升值 → carry unwind）
WTI_ABOVE = 100.0   # WTI 美元/桶
WTI_DAYS = 5        # 站稳 N 个交易日（≈ 一周）
SOX_ALERT = -3.0    # 费半隔夜涨跌幅 %，低于触发

# 哨兵中文显示名与行动映射
ALERT_TEXT = {
    "y10": "🚨 ① 美债警报：10Y %.2f%% > %.1f%% → 全球成长降权，A股科技/成长进攻仓位收敛（防守看高股息/红利）",
    "y30": "🚨 ① 美债警报：30Y %.2f%% > %.1f%% → 全球成长降权，A股科技/成长进攻仓位收敛（防守看高股息/红利）",
    "usdjpy": "🚨 ② carry unwind 警报：USDJPY %.1f < %.0f → 当日全球科技风险仓降配、新开仓谨慎",
    "wti": "🚨 ③ 油价警报：WTI 连续 %d 个交易日站稳 $%.0f → 油链/油服/化工上游加配，科技减配（成本端油价抬升）",
    "sox": "🚨 ④ 费半隔夜 %+.1f%% ≤ -3%% → 今日A股第一层供应链(光模块/PCB/半导体设备)开盘不追高，可等恐慌低吸",
}

_CACHE = None  # 惰性


def _load():
    global _CACHE
    if _CACHE is None:
        try:
            with open(CACHE_FILE, encoding="utf-8") as f:
                _CACHE = json.load(f)
        except (OSError, ValueError):
            _CACHE = {}
    return _CACHE


def _save():
    try:
        with open(CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(_CACHE, f, ensure_ascii=False, indent=1)
    except OSError:
        pass


def _f(v, default=None):
    try:
        x = float(v)
        return x if x == x else default  # noqa: PLR0124  NaN 过滤
    except (TypeError, ValueError):
        return default


# ── 数据源 ──────────────────────────────────────────────────────────────
def _fred_last(series_id):
    """FRED 官方 CSV（公开免 key）最后一条非空 (date, value)；失败 None。"""
    try:
        r = requests.get("https://fred.stlouisfed.org/graph/fredgraph.csv",
                         params={"id": series_id}, timeout=12, headers=UA)
        rows = r.text.strip().splitlines()
        for ln in reversed(rows[1:]):
            d, _, v = ln.partition(",")
            vv = _f(v)
            if vv is not None:
                return d, vv
    except Exception:  # noqa: BLE001
        pass
    return None


def _east_quote(secid):
    """东财 push2 实时（fltt=2）→ {last, pct, name}；失败 None。"""
    for host in ("https://push2.eastmoney.com", "https://90.push2.eastmoney.com"):
        try:
            r = requests.get(host + "/api/qt/ulist.np/get", timeout=8,
                             params={"secids": secid, "fields": "f2,f3,f14",
                                     "pn": 1, "pz": 10, "po": 1, "np": 1, "fltt": 2},
                             headers=UA)
            diff = (r.json().get("data") or {}).get("diff") or []
            if diff:
                it = diff[0]
                last = _f(it.get("f2"))
                if last is not None:
                    return {"last": last, "pct": _f(it.get("f3")),
                            "name": it.get("f14")}
        except Exception:  # noqa: BLE001
            continue
    return None


def _sina_field(sym):
    """新浪 hq.sinajs.cn 原始字段；失败返回 None。"""
    try:
        r = requests.get("https://hq.sinajs.cn/list=" + sym, timeout=8, headers=SINA_H)
        r.encoding = "gbk"
        body = r.text.split("=", 1)[1].split('"', 1)[1].rsplit('"', 1)[0]
        return body.split(",")
    except Exception:  # noqa: BLE001
        return None


def _tencent_field(sym):
    """腾讯 qt.gtimg.cn（外盘期货逗号分隔）→ 字段列表；失败 None。"""
    try:
        r = requests.get("https://qt.gtimg.cn/q=" + sym, timeout=8, headers=UA)
        r.encoding = "gbk"
        txt = r.text.split('="', 1)[1].rstrip('";\n')
        return txt.split(",")
    except Exception:  # noqa: BLE001
        return None


def _usdjpy_live():
    """东财 119.USDJPY 实时；失败回退新浪 fx_susdjpy。→ {last, pct}"""
    q = _east_quote("119.USDJPY")
    if q and q.get("last"):
        return q
    b = _sina_field("fx_susdjpy")  # 新浪外汇：idx9=名称；汇率字段尺度与东财一致
    if b and len(b) > 2:
        last = _f(b[1])  # idx1 = 现价（实测 153.630）
        if last:
            return {"last": last, "pct": None, "name": b[9] if len(b) > 9 else ""}
    return None


def _wti_live():
    """新浪 hf_CL（idx0 现价 / idx7 昨结）；失败回退腾讯。→ {last, prev, date, name}"""
    b = _sina_field("hf_CL")
    if b and len(b) > 13:
        last = _f(b[0])
        if last:
            return {"last": last, "prev": _f(b[7]),
                    "date": b[12], "name": b[13]}
    t = _tencent_field("hf_CL")
    if t and len(t) > 13:
        last = _f(t[0])
        if last:
            return {"last": last, "prev": _f(t[7]),
                    "date": t[12], "name": t[13]}
    return None


def _sox_live():
    """新浪美股指数 gb_$sox（费城半导体）。idx0 名称 idx1 收盘 idx2 涨跌幅% idx3 时间。"""
    b = _sina_field("gb_$sox")
    if b and len(b) > 3:
        last = _f(b[1])
        if last:
            return {"name": "费城半导体(SOX)", "last": last,
                    "pct": _f(b[2]), "ts": b[3], "prev": _f(b[5])}
    return None


# ── 核心：刷新 + 判定 ──────────────────────────────────────────────────
def _fred_daily(cache):
    """FRED 美债 10Y/30Y：每个本地日期只拉一次（CSV 较大且数据本身按日更新）。"""
    today = _dt.date.today().isoformat()
    if cache.get("fred_fetched") != today:
        for key, sid in (("y10", "DGS10"), ("y30", "DGS30")):
            got = _fred_last(sid)
            if got:
                cache[key] = {"date": got[0], "value": got[1]}
            else:
                cache[key] = {"date": None, "value": None, "err": True}
        cache["fred_fetched"] = today
        _save()
    return cache


def _wti_consecutive(cache, above):
    """wti_daily 里从最近日期倒推连续 > above 的交易日数（日期间隔 >4 视为断链）。"""
    series = {d: v for d, v in (cache.get("wti_daily") or {}).items()
              if _f(v) is not None and _f(v) > above}
    days = sorted(series)
    if not days:
        return 0
    count = 0
    prev = None
    for d in reversed(days):
        if prev is not None:
            try:
                gap = (_dt.date.fromisoformat(prev) - _dt.date.fromisoformat(d)).days
            except ValueError:
                gap = 5
            if gap > 4:  # 跳过一个周末=3 天；>4 视为有缺口，不再往前算
                break
        count += 1
        prev = d
    return count


def refresh():
    """拉取四源并落缓存，返回状态 dict（含 alerts）。网络失败保留旧值并标记 err。"""
    cache = _load()
    today = _dt.date.today().isoformat()
    cache = _fred_daily(cache)

    u = _usdjpy_live()
    if u:
        cache["usdjpy"] = {"ts": _dt.datetime.now().strftime("%H:%M:%S"),
                           "last": u["last"], "pct": u.get("pct")}
    w = _wti_live()
    if w:
        cache["wti"] = {"date": w["date"], "last": w["last"],
                        "prev": w["prev"], "name": w["name"]}
        # 每日收盘快照积累：当天同一日期用最近一次快照覆盖（收盘定格即最终值）
        daily = dict(cache.get("wti_daily") or {})
        daily[today] = w["last"]
        cache["wti_daily"] = {k: daily[k] for k in sorted(daily)[-60:]}
    s = _sox_live()
    if s:
        cache["sox"] = s
    _save()

    state = {
        "y10": cache.get("y10") or {},
        "y30": cache.get("y30") or {},
        "usdjpy": cache.get("usdjpy") or {},
        "wti": cache.get("wti") or {},
        "wti_days": _wti_consecutive(cache, WTI_ABOVE),
        "sox": cache.get("sox") or {},
    }
    state["alerts"] = alerts_of(state)
    return state


def alerts_of(state):
    """按阈值判定越阈警报。返回 [{id, text}]，id 稳定供增量去重。"""
    out = []
    y10 = (state.get("y10") or {}).get("value")
    y30 = (state.get("y30") or {}).get("value")
    if y10 is not None and y10 > Y10_ALERT:
        out.append({"id": "y10", "text": ALERT_TEXT["y10"] % (y10, Y10_ALERT)})
    elif y30 is not None and y30 > Y30_ALERT:
        out.append({"id": "y30", "text": ALERT_TEXT["y30"] % (y30, Y30_ALERT)})
    uj = (state.get("usdjpy") or {}).get("last")
    if uj is not None and uj < USDJPY_ALERT:
        out.append({"id": "usdjpy", "text": ALERT_TEXT["usdjpy"] % (uj, USDJPY_ALERT)})
    wti_days = state.get("wti_days") or 0
    if wti_days >= WTI_DAYS:
        out.append({"id": "wti", "text": ALERT_TEXT["wti"] % (wti_days, WTI_ABOVE)})
    sp = (state.get("sox") or {}).get("pct")
    if sp is not None and sp <= SOX_ALERT:
        out.append({"id": "sox", "text": ALERT_TEXT["sox"] % sp})
    return out


# ── 文本渲染（守护晨报/午间/收盘内嵌）──────────────────────────────────
def render_sentinel_lines(record_daily=True):
    """刷新并渲染文本行；四源全挂返回 []（调用方静默降级）。"""
    state = refresh()
    if not ((state.get("y10") or {}).get("value") or (state.get("usdjpy") or {}).get("last")
            or (state.get("wti") or {}).get("last") or (state.get("sox") or {}).get("last")):
        return []
    lines = ["🛰 宏观哨兵"]
    for a in state["alerts"]:
        lines.append("  " + a["text"])
    # 状态压缩行（不重复长文案）
    seg = []
    y10 = (state.get("y10") or {}).get("value")
    y30 = (state.get("y30") or {}).get("value")
    seg.append("①美债10Y%s%%/30Y%s%%" % (
        ("%.2f" % y10) if y10 is not None else "--",
        ("%.2f" % y30) if y30 is not None else "--"))
    uj = (state.get("usdjpy") or {}).get("last")
    seg.append("②USDJPY%s" % ("%.1f" % uj if uj is not None else "--"))
    wt = (state.get("wti") or {}).get("last")
    seg.append("③WTI%s(>100×%d日)" % (
        ("%.1f" % wt) if wt is not None else "--", state.get("wti_days") or 0))
    sp = (state.get("sox") or {}).get("pct")
    seg.append("④费半%s" % ("%+.1f%%" % sp if sp is not None else "--"))
    lines.append("  📊 " + "｜".join(seg))
    return lines


def state_json():
    state = refresh()
    state.pop("alerts", None)  # 文本已在各自行，json 里保留轻量
    return state


def main():
    ap = argparse.ArgumentParser(description="四根宏观哨兵（美债/日元/油价/费半）")
    ap.add_argument("--check", action="store_true", help="输出状态 json")
    args = ap.parse_args()
    if args.check:
        print(json.dumps(state_json(), ensure_ascii=False, indent=1))
        return 0
    lines = render_sentinel_lines()
    if not lines:
        print("宏观哨兵数据获取失败（网络/解析），本次跳过该段", file=os.sys.stderr)
        return 1
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
