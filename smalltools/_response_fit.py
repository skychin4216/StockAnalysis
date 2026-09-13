# -*- coding: utf-8 -*-
"""盘面应对 & 放量企稳二次确认 —— 1年/3年回溯拟合（2026-09-06）。

背景需求：
  ① “刚买入的信号票遇大跌怎么处置”是否合理 → E2：按信号日盘面风险分级(L1/L2/L3)+大盘状态
     分组统计全池每笔“T 信号日 → T+1 开盘买入”的规则回放（超短隔日/短线连跌/中长线做T+止盈止损），
     并对比：不开 L3 信号仓 vs 全开；中长线 有无止损线 的差异；买入后3日内大盘急跌的子集。
  ② “低位+放量企稳二次确认”（deepseek ETF 思路）是否值得 → E3：全池低位超卖信号
     直接买(hold20) vs 等放量企稳确认再买(hold20)。
  另 E1：风险分级前瞻有效性（L3 后市场是否继续跌），校准阈值。

口径：与 _full_cycle_backtest.simulate_trade / _self_review.market_risk 完全一致（代码同步复刻，
长线用真实做T现金账户模型）。缓存：smalltools/_kline_cache.json（2008→2026-09-04，218只池股+科创50）。

用法： python _response_fit.py            # 1年 + 3年
      python _response_fit.py --years 1   # 只跑 1 年
"""
import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from _full_cycle_backtest import (  # noqa: E402
    load_cache, market_state, sell_rule_for, T_UP_PCT, T_DOWN_PCT,
)
from _self_review import (  # noqa: E402
    RISK_LV_NORMAL, RISK_LV_ROTATE, RISK_LV_STORM,
    L2_DIV, L2_AVG1D, L3_AVG1D_ANY, L3_AVG1D_SH, L3_MA20_AVG1D, L3_AVG3D,
)
import _volume_confirm as vc  # noqa: E402

PERIODS = ["超短", "短线", "中线", "长线"]
S3 = ["sh000001", "sz399001", "sz399006"]
WINDOWS = {"1y": "2025-09-01", "3y": "2023-09-01"}
# E2 每只股票的抽样步长（日），兼顾全池样本量与时耗
STRIDE = {"1y": {"超短": 1, "短线": 1, "中线": 2, "长线": 3},
          "3y": {"超短": 2, "短线": 3, "中线": 5, "长线": 7}}
BUY_CRASH_AVG1 = -1.5  # “买入后3日内大盘急跌”判定：三指均单日跌幅阈值


def _fmt_pct(x, sign=False):
    return ("%+.2f%%" % x) if sign else ("%.2f%%" % x)


def _stats(rets):
    if not rets:
        return (0, 0.0, 0.0, 0.0, 0.0)
    wins = [r for r in rets if r > 0]
    avg = sum(rets) / len(rets)
    wr = len(wins) / len(rets) * 100
    cum = sum(rets)
    pf = sum(wins) / abs(sum(r for r in rets if r <= 0)) if sum(r for r in rets if r <= 0) else float("inf")
    return len(rets), avg, wr, cum, pf


def bucket(rs, bins):
    """bins 上界数组（收盘形态用），返回 各段占比% """
    from collections import Counter
    cnt = Counter()
    for r in rs:
        for i, b in enumerate(bins):
            if r <= b:
                cnt[i] += 1
                break
        else:
            cnt[len(bins)] += 1
    return cnt


def percentile(xs, q):
    if not xs:
        return 0.0
    s = sorted(xs)
    k = (len(s) - 1) * q
    f = int(k)
    c = k - f
    return s[f] * (1 - c) + s[f + 1] * c if f + 1 < len(s) else s[-1]


# ─────────────────────────── 盘面风险分级（同步复刻） ───────────────────────────
class RiskSeries:
    """把三指数收盘对齐到 union 交易日 D，按日期逐日复刻 market_risk/大盘状态。"""

    def __init__(self, cache, D, d2i):
        self.D = D
        self.idx_map = {sid: cache.get(sid, {}).get("snaps") or [] for sid in S3}
        self.arr = {}
        for sid in S3:
            mp = {s["date"]: s["close"] for s in self.idx_map[sid]}
            self.arr[sid] = [mp.get(d) for d in D]
        mp688 = {s["date"]: s["close"] for s in (cache.get("sh000688", {}).get("snaps") or [])}
        self.arr688 = [mp688.get(d) for d in D]
        self._state_cache = {}
        self._level_cache = {}
        self.avg1 = [None] * len(D)

    def state_at(self, j):
        st = self._state_cache.get(j)
        if st is None:
            st = market_state(self.idx_map, self.D[j], self.D,
                              {d: i for i, d in enumerate(self.D)})
            self._state_cache[j] = st
        return st

    def _v(self, sid, j, k):
        a = self.arr[sid]
        if a[j] is None:
            return None
        if k == 0:
            return a[j]
        if j - k < 0 or a[j - k] is None:
            return None
        return a[j]

    def level_at(self, j):
        """复刻 _self_review.market_risk 的阈值与顺序；返回 (level, state, avg1d)。"""
        lv = self._level_cache.get(j)
        if lv is not None:
            return lv
        chg1 = {}
        for sid in S3:
            c0 = self._v(sid, j, 0)
            c1 = self._v(sid, j, 1)
            chg1[sid] = (c0 / c1 - 1) * 100 if (c0 and c1) else None
        avail = [v for v in chg1.values() if v is not None]
        avg1 = sum(avail) / len(avail) if avail else None
        self.avg1[j] = avg1
        # 均 3 日
        c3s = []
        for sid in S3:
            c0 = self._v(sid, j, 0)
            c3 = self._v(sid, j, 3)
            if c0 and c3:
                c3s.append((c0 / c3 - 1) * 100)
        avg3 = sum(c3s) / len(c3s) if c3s else None
        # 破 MA20
        below = 0
        for sid in S3:
            c0 = self._v(sid, j, 0)
            if c0 is None or j < 19:
                continue
            win = [self._v(sid, j - t, 0) for t in range(20)]
            if all(win):
                below += 1 if c0 < sum(win) / 20 else 0
        sh1 = chg1["sh000001"]
        cyb1 = chg1["sz399006"]
        kcb1 = None
        if self.arr688[j] is not None and j >= 1 and self.arr688[j - 1]:
            kcb1 = (self.arr688[j] / self.arr688[j - 1] - 1) * 100
        growth1 = cyb1 if kcb1 is None else min(cyb1, kcb1)
        div = (growth1 - sh1) if (growth1 is not None and sh1 is not None) else None
        st = self.state_at(j)
        lv = RISK_LV_NORMAL
        if sh1 is not None and avg1 is not None:
            if st == "CRASH":
                lv = RISK_LV_STORM
            elif avg1 <= L3_AVG1D_ANY:
                lv = RISK_LV_STORM
            elif avg1 <= -2.0 and sh1 <= L3_AVG1D_SH:
                lv = RISK_LV_STORM
            elif below >= 2 and avg1 <= L3_MA20_AVG1D:
                lv = RISK_LV_STORM
            elif avg3 is not None and avg3 <= L3_AVG3D:
                lv = RISK_LV_STORM
            elif div is not None and div <= -L2_DIV and avg1 <= -0.4:
                lv = RISK_LV_ROTATE
            elif div is not None and div >= L2_DIV and avg1 <= -0.4:
                lv = RISK_LV_ROTATE
            elif avg1 <= L2_AVG1D:
                lv = RISK_LV_ROTATE
            elif below >= 2:
                lv = RISK_LV_ROTATE
        self._level_cache[j] = (lv, st, avg1)
        return lv, st, avg1


# ─────────────────────────── E1：风险分级前瞻 ───────────────────────────
def run_e1(rs, D, start):
    j0 = next(i for i, d in enumerate(D) if d >= start)
    rows = {}
    for lv in (RISK_LV_NORMAL, RISK_LV_ROTATE, RISK_LV_STORM):
        rows[lv] = {"n": 0, "fwd": {k: [] for k in (1, 3, 5, 10)}}
    for j in range(j0, len(D) - 10):
        lv, st, avg1 = rs.level_at(j)
        if avg1 is None:
            continue
        # 三指等权前瞻收益
        for k in (1, 3, 5, 10):
            vals = []
            for sid in S3:
                c0 = rs._v(sid, j, 0)
                ck = rs._v(sid, j + k, 0)
                if c0 and ck:
                    vals.append((ck / c0 - 1) * 100)
            if vals:
                rows[lv]["fwd"][k].append(sum(vals) / len(vals))
        rows[lv]["n"] += 1
    print("\n── E1 盘面分级前瞻（%s~，样本日=%s）──" % (start, D[j0]))
    print("等级     天数  次日均  次3日均  次5日均  次10日均  次日P(跌)")
    for lv in (RISK_LV_NORMAL, RISK_LV_ROTATE, RISK_LV_STORM):
        r = rows[lv]
        f1 = r["fwd"][1]
        pdown = sum(1 for v in f1 if v < 0) / len(f1) * 100 if f1 else 0
        def m(k):
            v = r["fwd"][k]
            return "%.2f%%" % (sum(v) / len(v)) if v else "-"
        name = {RISK_LV_NORMAL: "L1 正常", RISK_LV_ROTATE: "L2 转弱/切换", RISK_LV_STORM: "L3 急跌"}[lv]
        print("%-9s %5d  %6s  %6s  %6s  %7s  %7.0f%%" % (
            name, r["n"], m(1), m(3), m(5), m(10), pdown))
    # L3 后 10 日最差分位（验证“别猜底”）
    l3 = rows[RISK_LV_STORM]["fwd"][10]
    if l3:
        print("L3 后次10日均收益：中位 %.2f%% | 最差10%%分位 %.2f%% | 次3日再跌概率 %.0f%%" % (
            percentile(l3, 0.5), percentile(l3, 0.1),
            sum(1 for v in rows[RISK_LV_STORM]["fwd"][3] if v < 0) / len(rows[RISK_LV_STORM]["fwd"][3]) * 100))


# ─────────────────────────── E2：刚买入处置（信号日分级×状态×规则） ───────────────────────────
def _fast_sim(rows, pref, rowmp, D, p, rule, no_stop=False):
    """复刻 simulate_trade（省略 name 等返回字段），返回 (ret%, reason) 或 None。
    rows: 个股升序 snaps；pref: closes 前缀和；rowmp: date→row 下标；p: union 日期下标(=买入日)"""
    n = len(D)
    buy_date = D[p]
    bi = rowmp.get(buy_date)
    if bi is None or (rows[bi].get("open") or 0) <= 0:
        return None
    entry = rows[bi]["open"]
    maxh = rule["maxHold"]
    style = rule["style"]
    closes = [s["close"] for s in rows]
    if style == "nextday":
        for k in range(1, maxh + 1):
            if p + k >= n:
                break
            i = rowmp.get(D[p + k])
            if i is None:
                continue
            return (rows[i]["close"] / entry - 1) * 100, "隔日卖"
        return None
    if style == "streak":
        streak = 0
        prev = entry
        mb = rule["maBreak"]
        for k in range(1, maxh + 1):
            if p + k >= n:
                break
            i = rowmp.get(D[p + k])
            if i is None:
                continue
            c = rows[i]["close"]
            streak = streak + 1 if c < prev else 0
            prev = c
            if streak >= rule["streakDays"]:
                return (c / entry - 1) * 100, "连跌卖"
            if i >= mb:
                ma = (pref[i] - pref[i - mb]) / mb
                if c < ma:
                    return (c / entry - 1) * 100, "破均线"
        k = maxh
        if p + k < n:
            i = rowmp.get(D[p + k])
            if i is not None:
                return (rows[i]["close"] / entry - 1) * 100, "持有到期"
        return None
    # hold：做T现金账户（复刻引擎）
    qty = 1000
    t_qty = int(qty * rule.get("tRatio", 0.4))
    hq = qty
    cash = 0.0
    last = None
    for k in range(1, maxh + 1):
        if p + k >= n:
            break
        i = rowmp.get(D[p + k])
        if i is None:
            continue
        last = (i, k)
        cost = qty * entry
        value = hq * rows[i]["close"] + cash
        pnl = (value / cost - 1) * 100
        if rule["tp"] > 0 and pnl >= rule["tp"]:
            return pnl, "止盈"
        if (not no_stop) and rule["sl"] < 0 and pnl <= rule["sl"]:
            return pnl, "止损"
        hi = (rows[i]["high"] / entry - 1) * 100
        lo = (rows[i]["low"] / entry - 1) * 100
        if hi >= T_UP_PCT and hq >= t_qty:
            cash += rows[i]["high"] * t_qty
            hq -= t_qty
        if lo <= T_DOWN_PCT and hq < qty:
            bb = min(t_qty, qty - hq)
            cash -= rows[i]["low"] * bb
            hq += bb
    k = maxh
    if p + k < n:
        i = rowmp.get(D[p + k])
        if i is not None:
            val = hq * rows[i]["close"] + cash
            return (val / (qty * entry) - 1) * 100, "持有到期"
    if last is not None:  # 数据末端兜底（与引擎一致）
        i = last[0]
        val = hq * rows[i]["close"] + cash
        return (val / (qty * entry) - 1) * 100, "持有到期(数据末端)"
    return None


def run_e2(rs, cache, D, d2i, start, tag):
    pool = [k for k in cache if not k.startswith(("sh000", "sz399"))]
    j0 = d2i[start]
    res = {p: {"lv": {RISK_LV_NORMAL: [], RISK_LV_ROTATE: [], RISK_LV_STORM: []},
               "state": {}} for p in PERIODS}
    res_stop = {p: {"sl_on": [], "sl_off": []} for p in PERIODS}
    res_crash3 = {p: {"crash": [], "calm": []} for p in PERIODS}
    seen = 0
    t0 = time.time()
    for sid in pool:
        rows = cache[sid].get("snaps") or []
        if len(rows) < 60:
            continue
        rowmp = {s["date"]: i for i, s in enumerate(rows)}
        closes = [s["close"] for s in rows]
        pref = [0.0]
        for c in closes:
            pref.append(pref[-1] + c)
        for period in PERIODS:
            stride = STRIDE[tag][period]
            for j in range(j0, len(D) - 1, stride):
                lv, st, avg1 = rs.level_at(j)
                pj = j + 1                       # 买入日 = 信号日 T+1
                rule = sell_rule_for(period, st)
                maxh = int(rule.get("maxHold") or 0)
                if pj + maxh >= len(D):          # 需覆盖完整持仓窗口
                    continue
                tr = _fast_sim(rows, pref, rowmp, D, pj, rule)
                if tr is None:
                    continue
                ret, reason = tr
                res[period]["lv"][lv].append(ret)
                res[period]["state"].setdefault(st, []).append(ret)
                seen += 1
                if period in ("中线", "长线"):
                    res_stop[period]["sl_on"].append(ret)
                    tr2 = _fast_sim(rows, pref, rowmp, D, pj, rule, no_stop=True)
                    if tr2:
                        res_stop[period]["sl_off"].append(tr2[0])
                    # 买入后 3 日内大盘急跌子集（用信号日后的实际盘面分级）
                    crash = any((rs.level_at(j2)[2] or 0) <= BUY_CRASH_AVG1
                                for j2 in range(pj + 1, min(pj + 4, len(D))))
                    (res_crash3[period]["crash"] if crash else res_crash3[period]["calm"]).append(ret)
    print("\n── E2 全池“T信号→T+1开盘买入”规则回放（%s，%d 笔）──" % (tag, seen))
    for p in PERIODS:
        print("\n▶ %s（规则快照=信号日大盘状态）" % p)
        print("  信号日分级 |  笔数 |  胜率  |  均收益 |  亏损笔均 |  P(亏≤-5%)")
        for lv in (RISK_LV_NORMAL, RISK_LV_ROTATE, RISK_LV_STORM):
            rs2 = res[p]["lv"][lv]
            if not rs2:
                continue
            n, avg, wr, cum, pf = _stats(rs2)
            loss = [r for r in rs2 if r <= 0]
            loss_avg = sum(loss) / len(loss) if loss else 0.0
            big = sum(1 for r in rs2 if r <= -5) / n * 100
            name = {RISK_LV_NORMAL: "L1 正常", RISK_LV_ROTATE: "L2 转弱", RISK_LV_STORM: "L3 急跌"}[lv]
            print("  %-9s | %5d | %4.0f%% | %+7.2f%% | %+7.2f%% | %5.0f%%" % (
                name, n, wr, avg, loss_avg, big))
        print("  信号日状态 |  笔数 |  胜率  |  均收益")
        for st in sorted(res[p]["state"]):
            n, avg, wr, _, _ = _stats(res[p]["state"][st])
            print("  %-9s | %5d | %4.0f%% | %+7.2f%%" % (st, n, wr, avg))
        allv = res[p]["lv"][RISK_LV_NORMAL] + res[p]["lv"][RISK_LV_ROTATE] + res[p]["lv"][RISK_LV_STORM]
        no_l3 = res[p]["lv"][RISK_LV_NORMAL] + res[p]["lv"][RISK_LV_ROTATE]
        a_all = sum(allv) / len(allv) if allv else 0
        a_nol3 = sum(no_l3) / len(no_l3) if no_l3 else 0
        print("  → 不开L3新仓(均%+.2f%%) vs 全开(均%+.2f%%)  差%+.2fpp" % (a_nol3, a_all, a_nol3 - a_all))
        if p in ("中线", "长线"):
            o = res_stop[p]["sl_on"]
            f = res_stop[p]["sl_off"]
            no, ao, wo, _, _ = _stats(o)
            nf, af, wf, _, _ = _stats(f)
            print("  → 止损纪律: 带止损 %5d笔 均%+6.2f%% 胜%3.0f%% | 无止损 %5d笔 均%+6.2f%% 胜%3.0f%%" % (
                no, ao, wo, nf, af, wf))
            cc = res_crash3[p]
            if cc["crash"]:
                nc, ac, wc, _, _ = _stats(cc["crash"])
                nm, am, wm, _, _ = _stats(cc["calm"])
                print("  → 买入后3日内大盘急跌 %d笔 均%+6.2f%% 胜%3.0f%% | 平静 %d笔 均%+6.2f%% 胜%3.0f%%" % (
                    nc, ac, wc, nm, am, wm))
    print("\nE2 耗时 %.0fs" % (time.time() - t0))


# ─────────────────────────── E3：放量企稳二次确认 ───────────────────────────
def run_e3(cache, start, tag, hold=20, look=5):
    pool = [k for k in cache if not k.startswith(("sh000", "sz399"))]
    A, B = [], []
    t0 = time.time()
    for sid in pool:
        rows = cache[sid].get("snaps") or []
        if len(rows) < 280:
            continue
        ind = vc.indicators(rows)
        dates = [s["date"] for s in rows]
        j0 = next((i for i, d in enumerate(dates) if d >= start), len(rows))
        for i in range(j0, len(rows) - hold - look - 2):
            if not vc.low_signal(ind, i):
                continue
            p = ind["pos"][i]
            if p is None or p > 0.30:
                continue
            # A：直接低吸（等权 hold N 根）
            ent_a = rows[i]["close"]
            ret_a = (rows[i + hold]["close"] / ent_a - 1) * 100 if ent_a else None
            if ret_a is None:
                continue
            A.append(ret_a)
            # B：等放量企稳再买
            j = vc.first_confirm(rows, i, look=look, ind=ind)
            if j < 0 or j + hold >= len(rows):
                continue
            ent_b = rows[j]["close"]
            if ent_b <= 0:
                continue
            ret_b = (rows[j + hold]["close"] / ent_b - 1) * 100
            B.append(ret_b)
    print("\n── E3 低位超卖：直接买 vs 等放量企稳确认再买（%s，hold=%d日）──" % (tag, hold))
    for nm, rs_ in (("A 直接低吸", A), ("B 等放量企稳(5日内)", B)):
        n, avg, wr, cum, pf = _stats(rs_)
        if n:
            med = percentile(rs_, 0.5)
            loss = [r for r in rs_ if r <= 0]
            print("  %-18s %5d笔 均%+6.2f%% 中位%+6.2f%% 胜%3.0f%% | 亏损笔均%+5.2f%%" % (
                nm, n, avg, med, wr, sum(loss) / len(loss) if loss else 0))
    print("  B 对 A：样本%d/%d（确认率%.0f%%）" % (len(B), len(A), len(B) / len(A) * 100 if A else 0))
    print("E3 耗时 %.0fs" % (time.time() - t0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--years", choices=["1", "3", "all"], default="all")
    args = ap.parse_args()
    cache = load_cache()
    D = sorted({d for ent in cache.values() for s in (ent.get("snaps") or []) for d in [s["date"]]})
    d2i = {d: i for i, d in enumerate(D)}
    print("缓存：%d 只标的 / 交易日 %s ~ %s (%d)" % (
        len(cache), D[0], D[-1], len(D)))
    rs = RiskSeries(cache, D, d2i)
    for key, start in WINDOWS.items():
        if args.years != "all" and key != args.years + "y":
            continue
        print("\n" + "=" * 70 + "\n窗口：%s（%s ~）" % (key, start))
        run_e1(rs, D, start)
        run_e2(rs, cache, D, d2i, start, key)
        run_e3(cache, start, key)


if __name__ == "__main__":
    main()
