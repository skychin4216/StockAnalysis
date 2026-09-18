# -*- coding: utf-8 -*-
"""A 股交易日历（周末 + 节假日），守护据此实现「非交易日全天睡眠」。

数据源：akshare.tool_trade_date_hist_sina（新浪，覆盖 1990-12-19 ~ 当年 12-31）。
本地缓存 _records/_trade_calendar.json：
  {"updated": "...", "end": "2026-12-31", "days": ["1990-12-19", ...]}

判定原则：
  - 查询日落在缓存覆盖范围内 → 直接查表（毫秒级，不打网络）
  - 查询日超出覆盖（跨年）→ best-effort 刷新一次；失败则继续用旧缓存
  - **完全拿不到日历时宁可当交易日**（守护空转一轮无害，错过选股有害）
"""
import datetime
import json
import os
import threading

_DIR = os.path.dirname(os.path.abspath(__file__))
CAL_FILE = os.path.join(_DIR, "_records", "_trade_calendar.json")
_lock = threading.Lock()
_cache = None  # {"end": date, "days": set}
_refresh_day = None  # 节流：同一天最多刷新一次（跨年时防反复打网络）


def _load():
    global _cache
    if _cache is not None:
        return _cache
    try:
        with open(CAL_FILE, encoding="utf-8") as f:
            d = json.load(f)
        days = set(d.get("days") or [])
        if days:
            _cache = {"end": datetime.date.fromisoformat(d["end"]), "days": days}
            return _cache
    except Exception as e:  # noqa: BLE001
        print("交易日历缓存不可读:", type(e).__name__, e)
    _cache = {"end": datetime.date(1970, 1, 1), "days": set()}
    return _cache


def _refresh():
    """best-effort 拉新浪全年交易日历并写缓存（同一天最多试一次）。成功返回 True。"""
    global _cache, _refresh_day
    today = datetime.date.today()
    if _refresh_day == today:
        return False  # 今天已尝试过，别再打网络
    _refresh_day = today
    try:
        import akshare as ak
        df = ak.tool_trade_date_hist_sina()
        days = [str(x) for x in df["trade_date"]]
        if not days:
            return False
        os.makedirs(os.path.dirname(CAL_FILE), exist_ok=True)
        tmp = CAL_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"updated": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                       "end": days[-1], "days": days}, f)
        os.replace(tmp, CAL_FILE)
        _cache = {"end": datetime.date.fromisoformat(days[-1]), "days": set(days)}
        print("交易日历已刷新：%d 天，覆盖至 %s" % (len(days), days[-1]))
        return True
    except Exception as e:  # noqa: BLE001
        print("交易日历刷新失败:", type(e).__name__, e)
        return False


def is_trading_day(d=None):
    """d 是否 A 股交易日（周末必非；工作日查节假日表；拿不到表当交易日）。"""
    d = d or datetime.date.today()
    if d.weekday() >= 5:
        return False
    with _lock:
        c = _load()
        if d.isoformat() in c["days"]:
            return True
        if d <= c["end"]:  # 在覆盖范围内且不在列表 → 节假日休市
            return False
        # 超出覆盖（跨年）→ 刷新一次再判；仍覆盖不到 → 工作日保守当交易日
        if _refresh() and d.isoformat() in _cache["days"]:
            return True
        return d.weekday() < 5


def next_trading_day(d=None):
    """d（默认今天）之后的下一个交易日。"""
    d = (d or datetime.date.today()) + datetime.timedelta(days=1)
    for _ in range(30):
        if is_trading_day(d):
            return d
        d += datetime.timedelta(days=1)
    return d  # 兜底（理论上 30 天内必有交易日）


def next_trading_at(now, hour=8, minute=0):
    """下一个交易日 hour:minute 的时刻；今天就是交易日且还没到则返回今天。"""
    if is_trading_day(now.date()):
        t = now.replace(hour=hour, minute=minute, second=0, microsecond=0)
        if now < t:
            return t
    return datetime.datetime.combine(next_trading_day(now.date()),
                                     datetime.time(hour, minute))


if __name__ == "__main__":
    # CLI 自检：python _trade_calendar.py [YYYY-MM-DD ...]
    import sys
    if len(sys.argv) > 1:
        for a in sys.argv[1:]:
            d = datetime.date.fromisoformat(a)
            print("%s 周%s %s 下一交易日 %s" % (
                d, "一二三四五六日"[d.weekday()],
                "交易日" if is_trading_day(d) else "休市",
                next_trading_day(d)))
    else:
        today = datetime.date.today()
        print("缓存: end=%s days=%d" % (_load()["end"], len(_load()["days"])))
        print("今天 %s 周%s → %s" % (today, "一二三四五六日"[today.weekday()],
              "交易日" if is_trading_day(today) else "休市"))
        print("下一交易日:", next_trading_day(today))
