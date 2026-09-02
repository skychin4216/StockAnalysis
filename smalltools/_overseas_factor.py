# -*- coding: utf-8 -*-
"""隔夜外围因子：韩股/纳指前一日大跌时，A股开盘信号降权/禁开仓。

数据：smalltools/_overseas_cache.json（由 _overseas_fetch.py 每晚抓取，
      用 A股跨境 ETF 作为外围代理：纳指100ETF + 中韩半导体ETF(韩股链)）。

逻辑：A股 T 日开盘时，已知 T-1 夜外围走势（ETF[T-1] 相对 ETF[T-2] 涨跌幅）。
      对多代理按权重加权求「外围加权隔夜跌幅」：
        - 跌幅 ≤ -soft_threshold  → 短周期信号降权（WEAK）
        - 跌幅 ≤ -hard_threshold  → 短周期禁开仓（BLOCKED）
      （经回测验证：全局禁开仓会误杀长线超跌企稳单，因此默认只作用于超短/短线。）

用法（供选股/回测引擎注入，不改动基准逻辑）：
    from _overseas_factor import OverseasFactor
    f = OverseasFactor()
    flag, info = f.check(asof, period)      # period: 超短/短线/中线/长线
    if flag in ("BLOCKED",): continue       # 禁开仓
    # 或降权：flag == "WEAK" 时对信号降权
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_FILE = os.path.join(HERE, "_overseas_cache.json")

# 外围代理权重：纳指(科技链) 0.6 + 韩股半导体链 0.4
PROXY_WEIGHTS = {
    "sh513100": 0.6,   # 纳指100ETF → A股科技/半导体联动
    "sh513310": 0.4,   # 中韩半导体ETF → 韩股三星/SK海力士 → 半导体
}

# 默认阈值（%）：soft 降权 / hard 禁开仓
SOFT_THRESHOLD = -1.0
HARD_THRESHOLD = -1.5

# 受影响周期：默认只影响超短/短线（回测验证长线在外围大跌次日反而是买点）
AFFECTED_PERIODS = ("超短", "短线")

CACHE = None  # 惰性加载缓存


def _load_cache():
    global CACHE
    if CACHE is None:
        if os.path.exists(CACHE_FILE):
            try:
                CACHE = json.load(open(CACHE_FILE, encoding="utf-8"))
            except Exception:
                CACHE = {}
        else:
            CACHE = {}
    return CACHE


class OverseasFactor:
    """隔夜外围因子。线程安全：只读缓存，check() 无副作用。"""

    def __init__(self, cache=None, soft=SOFT_THRESHOLD, hard=HARD_THRESHOLD,
                 weights=None, affected=AFFECTED_PERIODS):
        self.cache = cache if cache is not None else _load_cache()
        self.soft = soft
        self.hard = hard
        self.weights = weights or PROXY_WEIGHTS
        self.affected = affected
        # 预构建每个代理的 {date: close} 与排序日期
        self._closes = {}
        self._dates = {}
        for code in self.weights:
            snaps = sorted((self.cache.get(code) or {}).get("snaps") or [],
                           key=lambda s: s["date"])
            self._closes[code] = {s["date"]: s["close"] for s in snaps}
            self._dates[code] = sorted(self._closes[code])

    # ── 查询 ────────────────────────────────────────────────────────────
    def overnight_change(self, asof, code):
        """asof 前一日该代理的隔夜涨跌幅(%)；数据不足返回 None。"""
        dates = self._dates.get(code) or []
        if not dates:
            return None
        i = None
        for k, d in enumerate(dates):
            if d >= asof:
                i = k
                break
        if i is None or i < 2:
            return None
        c1 = self._closes[code].get(dates[i - 1])
        c2 = self._closes[code].get(dates[i - 2])
        if not c1 or not c2 or c2 <= 0:
            return None
        return (c1 / c2 - 1.0) * 100.0

    def weighted_change(self, asof):
        """外围加权隔夜跌幅(%)。返回 (weighted, {code: chg})；无有效代理返回 (None, {})。"""
        total_w, weighted, per = 0.0, 0.0, {}
        for code, w in self.weights.items():
            chg = self.overnight_change(asof, code)
            if chg is None:
                continue
            per[code] = chg
            weighted += chg * w
            total_w += w
        if total_w <= 0:
            return None, per
        return weighted / total_w, per

    def check(self, asof, period=None):
        """返回 (flag, info)。
        flag: NORMAL / WEAK(降权) / BLOCKED(禁开仓) / NO_DATA。
        info: {weighted, per, affected, reason}。
        """
        weighted, per = self.weighted_change(asof)
        if weighted is None:
            return "NO_DATA", {"weighted": None, "per": per,
                               "affected": False, "reason": "外围数据不足"}
        affected = (period is None) or (period in self.affected)
        if not affected:
            return "NORMAL", {"weighted": weighted, "per": per,
                              "affected": False, "reason": "该周期不受外围因子影响"}
        if weighted <= self.hard:
            return "BLOCKED", {"weighted": weighted, "per": per,
                               "affected": True,
                               "reason": f"外围隔夜大跌 {weighted:.2f}%（阈 {self.hard}%）→ 禁开仓"}
        if weighted <= self.soft:
            return "WEAK", {"weighted": weighted, "per": per,
                            "affected": True,
                            "reason": f"外围隔夜偏弱 {weighted:.2f}%（阈 {self.soft}%）→ 降权"}
        return "NORMAL", {"weighted": weighted, "per": per,
                          "affected": True, "reason": f"外围 {weighted:.2f}% 无碍"}


def make_guard(factor=None, verbose=False):
    """工厂：返回包装 collect_signals_window 的守卫函数（与实验脚本一致的注入方式）。
    注入后：BLOCKED 日禁开仓（信号剔除），WEAK 日信号降权（保留但标记）。
    """
    f = factor or OverseasFactor()

    def guard(cache, all_dates, date_to_idx, period, w_start, w_end, verbose_=False):
        sigs, counter = collect_signals_window_orig(cache, all_dates, date_to_idx,
                                                    period, w_start, w_end, verbose_)
        out, skipped = [], 0
        for s in sigs:
            flag, info = f.check(s[2], period)
            if flag == "BLOCKED":
                skipped += 1
                continue
            out.append(s)
        if verbose and skipped:
            print(f"    外围因子剔除 {skipped} 笔（{period}）")
        return out, counter
    return guard


# 延迟绑定原始实现（被 make_guard 替换前）
collect_signals_window_orig = None


def patch_collect():
    """把 make_guard 包装到 _walk_forward.collect_signals_window（回测引擎用）。
    通过环境变量 AQ_OVERSEAS_GUARD 控制开关，默认关闭（不污染基准）。"""
    import os as _os
    if not _os.environ.get("AQ_OVERSEAS_GUARD"):
        return None
    import _walk_forward as wf
    global collect_signals_window_orig
    collect_signals_window_orig = wf.collect_signals_window
    wf.collect_signals_window = make_guard(verbose=True)
    return wf.collect_signals_window


if __name__ == "__main__":
    # 自检：打印最近几个交易日的外围状态
    f = OverseasFactor()
    for code in f.weights:
        dates = f._dates[code]
        print(f"代理 {code} ({f.cache.get(code,{}).get('name','')})  {len(dates)} 根"
              f"  {dates[0]} ~ {dates[-1]}")
    for asof in ("2026-08-31", "2026-09-01"):
        w, per = f.weighted_change(asof)
        print(f"\nasof={asof} 加权隔夜 {w:.2f}%" if w is not None else f"\nasof={asof} 无数据")
        for c, chg in per.items():
            print(f"   {c} ({f.cache.get(c,{}).get('name','')}) {chg:+.2f}%")
        flag, info = f.check(asof, "超短")
        print(f"   超短判定: {flag}  {info['reason']}")
