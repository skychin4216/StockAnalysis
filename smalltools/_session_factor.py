# -*- coding: utf-8 -*-
"""时段因子（盘中实时）：早盘下杀企稳 / 尾盘 14:30 后不追。

数据源：腾讯当日分时（web.ifzq.gtimg.cn/appstock/app/minute/query），
        返回 [HHMM, 价格, 累计量, 累计额]，仅含当日；历史分钟线受限
        （东财 push2his 被断、腾讯 mkline 重定向失败），因此本模块用于
        **盘中实时过滤**，不参与日线回测（回测走日线级别的其他因子）。

编码规则（参考 A股经验，公式简单，无开源包可复用）：
  1. 早盘下杀后企稳：9:30~10:00 价格较昨收跌幅 ≤ -1.5%（下杀），
     且 10:00~10:30 自低点回升 ≥ +0.5%（企稳）→ 可介入（早盘黄金买点）
  2. 尾盘不追：当前时间 ≥ 14:30，且 14:00 后拉升幅度 ≥ +1.0%
     → 尾盘拉升有坑，禁追高

用法：
    from _session_factor import session_factor_for
    f = session_factor_for("sh600000", prev_close=9.16)
    f.morning_verdict      # "enter" / "wait" / "no_data"
    f.late_zone_active     # True 表示当前处于 14:30 后且尾盘拉升
    f.reason               # 文字说明
"""
import datetime
import json
import os

import requests

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
PROXIES = {"http": None, "https": None}
HERE = os.path.dirname(os.path.abspath(__file__))
KLINE_CACHE = os.path.join(HERE, "_kline_cache.json")

_prev_cache = None


def _prev_close_from_cache(secid):
    """从日K缓存读最近一根收盘价作为昨收近似（优先上一交易日）。"""
    global _prev_cache
    if _prev_cache is None:
        try:
            _prev_cache = json.load(open(KLINE_CACHE, encoding="utf-8"))
        except Exception:
            _prev_cache = {}
    ent = _prev_cache.get(secid) or {}
    snaps = ent.get("snaps") or []
    if len(snaps) >= 2:
        return snaps[-2]["close"]      # 上一交易日收盘
    if snaps:
        return snaps[-1]["close"]
    return None

MORNING_CRASH = -1.5   # 早盘下杀阈值（%）
MORNING_UP = 0.5       # 企稳回升阈值（%）
LATE_LIFT = 1.0        # 尾盘拉升阈值（%）
LATE_START = "14:30"


def _hm_now():
    return datetime.datetime.now().strftime("%H:%M")


def fetch_minutes(secid):
    """腾讯当日分时一次请求 → ([(HHMM, price), ...], prev_close|None)。失败返回 (None, None)。"""
    url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query"
    try:
        r = requests.get(url, params={"code": secid}, timeout=12,
                         headers=HEADERS, proxies=PROXIES)
        node = (r.json().get("data") or {}).get(secid) or {}
        rows = ((node.get("data") or {}).get("data")) or []
        out = []
        for it in rows:
            p = str(it).split()
            if len(p) >= 2:
                try:
                    out.append((p[0], float(p[1])))
                except ValueError:
                    continue
        prev = None
        qt = node.get("qt") or {}
        arr = qt.get(secid) or []
        if len(arr) > 4:
            try:
                prev = float(arr[4])
            except ValueError:
                prev = None
        if not out:
            return None, prev
        return out, prev
    except Exception:
        return None, None


class SessionFactor:
    """单个标的的当日时段因子。"""

    def __init__(self, secid, prev_close=None, minutes=None, now=None):
        self.secid = secid
        if minutes is None:
            minutes, fetched_prev = fetch_minutes(secid)
            if prev_close is None:
                prev_close = fetched_prev
        self.minutes = minutes
        self.prev_close = prev_close
        self.now = now or _hm_now()   # "HH:MM" 当前时刻（测试可注入）
        self.reason = ""
        self.raw = {}
        self._analyze()

    # ── 分析 ─────────────────────────────────────────────────────────────
    def _analyze(self):
        if not self.minutes:
            self.morning_verdict = "no_data"
            self.late_zone_active = False
            self.reason = "分时数据不可用"
            return
        # 昨收未获取到时，回退日K缓存/首根分时价
        if self.prev_close is None:
            self.prev_close = _prev_close_from_cache(self.secid) or self.minutes[0][1]

        def pct_at(t_start, t_end):
            """区间内价格相对昨收涨跌幅(%)。返回 (min_pct, max_pct, last_pct)。"""
            vals = [p for (t, p) in self.minutes if t_start <= t <= t_end]
            if not vals:
                return None, None, None
            pc = self.prev_close or 1.0
            return ((min(vals) / pc - 1) * 100,
                    (max(vals) / pc - 1) * 100,
                    (vals[-1] / pc - 1) * 100)

        # 早盘：9:30~10:00 下杀；10:00~10:30 企稳
        crash_min, crash_max, _ = pct_at("0930", "1000")
        rec_min, rec_max, rec_last = pct_at("1000", "1030")
        if crash_min is None or rec_min is None:
            self.morning_verdict = "no_data"
        elif crash_min <= MORNING_CRASH and (rec_last - crash_min) >= MORNING_UP:
            self.morning_verdict = "enter"
            self.reason = ("早盘下杀 %.2f%% 后企稳回升 %+.2f%% → 可介入"
                           % (crash_min, rec_last - crash_min))
        elif crash_min <= MORNING_CRASH:
            self.morning_verdict = "wait"
            self.reason = ("早盘下杀 %.2f%% 尚未企稳 → 等待企稳信号"
                           % crash_min)
        else:
            self.morning_verdict = "wait"
            self.reason = "早盘无明显下杀（最低 %+.2f%%），无下杀买点" % crash_min
        self.raw["crash"] = crash_min
        self.raw["recover"] = (rec_last - crash_min) if (crash_min is not None and rec_last is not None) else None

        # 尾盘：14:00 后拉升幅度，且当前 ≥ 14:30
        _, late_max, _ = pct_at("1400", "1500")
        now = self.now
        self.late_zone_active = (now >= LATE_START
                                 and late_max is not None and late_max >= LATE_LIFT)
        if self.late_zone_active:
            self.reason += (" | 尾盘 14:00 后拉升 %+.2f%%（≥ %+.1f%%）→ 勿追高"
                            % (late_max, LATE_LIFT))

    # ── 便捷 ─────────────────────────────────────────────────────────────
    @property
    def morning_ok(self):
        """早盘下杀企稳 → 可考虑建仓（超短/短线用）。"""
        return self.morning_verdict == "enter"


def session_factor_for(secid, prev_close=None):
    return SessionFactor(secid, prev_close)


if __name__ == "__main__":
    import sys
    targets = sys.argv[1:] or ["sh600000", "sz000001"]
    for code in targets:
        f = session_factor_for(code)
        print(f"═══ {code} ═══")
        print("  早盘判定: %s | %s" % (f.morning_verdict, f.reason.split(' | ')[0]))
        if f.late_zone_active:
            print("  尾盘警示: ⚠️ %s" % f.reason.split(' | ')[-1])
        else:
            print("  尾盘警示: 无（未触发 14:30 后拉升）")
        if f.raw.get("crash") is not None:
            print("  早盘最低 %+.2f%% | 企稳回升 %+.2f%%"
                  % (f.raw["crash"], f.raw.get("recover") or 0))
