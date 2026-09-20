# -*- coding: utf-8 -*-
"""★超级权限（level=0）两档口径对比回测（2026-09-19 用户需求）。

用户原话：「宽松档（30天/任一）→ 改成只要没有卖出就算可以买入，看看会不会提高
正确率和盈利，但就怕社保和大基金卖出的时候我们不知道」。

对比两档「国家队 + 社保 同时」信号的历史表现：

  A 严格（现状）= 近 noticeDays(7) 天内**双买入**：
      t 时点存在 nat 记录 notice∈[t-7,t] 且 state∈{新进,加仓}，且 ss 同样。
  B 宽松（新口径）= **「没卖出就能买」**：
      t 时点 nat 与 ss 的**最新已披露**记录（notice≤t）都存在（未退出）
      且 state != 减持 —— 不要求近期有增持动作。

## 数据与口径

- 持仓：`data/_national_flow_hist.json`（88 票；国家队 3998 / 社保 1832 条，
  每条含 end(报告期) / notice(披露日) / state(新进·加仓·不变·减持)）。
  **全部要求 notice ≤ 信号日**（无未来函数）。
- 价格：`data/kline_store.json`（全历史日K）。买入价 = 信号日之后**第一个交易日收盘**。
- ⚠️ 历史无分钟K → 用日K近似用户新判定口径：
    成功 = 5 日内存在某日 low ≥ 买入价×1.05（当日全程在 +5% 之上，强于"维持 1h"）
    尚可 = 5 日内存在某日 low ≥ 买入价×1.01（且未达成功）
    失败 = 5 日内最高价 ≤ 买入价×1.01
   收益 = 5 日内 max(high)/买入价 − 1（"可成交高"的日线上限近似）。
- ⚠️ 滞后风险（用户担心点）：披露是季报粒度，卖出可能到下一期才可见（B 档天然带此风险，
  回测里用"下一期是否减持/退出"统计命中率来量化）。

用法：
    python _super_holder_backtest.py                 # 默认 2019-01-01 起
    python _super_holder_backtest.py --since 2021-01-01 --notice-days 7
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA = os.path.join(ROOT, "data")
if HERE not in sys.path:
    sys.path.insert(0, HERE)

BUY_STATES = ("新进", "加仓", "增持")


def load_flow():
    with open(os.path.join(DATA, "_national_flow_hist.json"), encoding="utf-8") as f:
        return (json.load(f).get("stocks") or {})


def load_kline():
    with open(os.path.join(DATA, "kline_store.json"), encoding="utf-8") as f:
        return json.load(f)


def _week_before(d):
    """d 的前 7 天（YYYY-MM-DD）。"""
    import datetime as dt
    try:
        return (dt.date.fromisoformat(d[:10]) - dt.timedelta(days=7)).isoformat()
    except Exception:  # noqa: BLE001
        return "0000-00-00"


def latest_visible(recs, t):
    """notice ≤ t 的最新一条（按 end, notice 排序）。"""
    vis = [r for r in (recs or []) if str(r.get("notice") or "")[:10] <= t[:10]]
    if not vis:
        return None
    return max(vis, key=lambda r: (str(r.get("end") or ""), str(r.get("notice") or "")))


def sig_A(nat, ss, t, notice_days=7):
    """严格：近 notice_days 天内 nat 与 ss **都**出现「新进/加仓」，且**均未退出**。

    2026-09-19 用户需求①：notice_days 可放大到 30（近一月）/90（近三月）对比效果；
    同时补「没有退出」条件——两者在 t 时点的最新已披露期都仍在名单且非「减持」。
    """
    import datetime as _dt
    try:
        lo = (_dt.date.fromisoformat(t[:10])
              - _dt.timedelta(days=int(notice_days))).isoformat()
    except Exception:  # noqa: BLE001
        lo = "0000-00-00"
    fresh_n = [r for r in nat if lo <= str(r.get("notice") or "")[:10] <= t[:10]
               and str(r.get("state")) in BUY_STATES]
    fresh_s = [r for r in ss if lo <= str(r.get("notice") or "")[:10] <= t[:10]
               and str(r.get("state")) in BUY_STATES]
    if not (fresh_n and fresh_s):
        return None
    for x in (latest_visible(nat, t), latest_visible(ss, t)):   # 没有退出
        if not x or str(x.get("state")) == "减持":
            return None
    return (fresh_n[0], fresh_s[0])


def sig_B(nat, ss, t):
    """宽松：t 时 nat 与 ss 的最新已披露记录都在且未减持（"没卖出"）。"""
    ln, ls = latest_visible(nat, t), latest_visible(ss, t)
    if not ln or not ls:
        return None
    if str(ln.get("state")) == "减持" or str(ls.get("state")) == "减持":
        return None
    return (ln, ls)


def sig_C(nat, big, ss, t):
    """C 档（用户 2026-09-19 问）：**国家队 + 大基金 + 社保 三者同时持有**。

    口径：t 时点三者的最新已披露记录（notice ≤ t）都在、且都不是「减持」
    （即三家长期资金同时在十大流通股东里 = 最强"国家队抱团"形态）。
    """
    a, b, c = latest_visible(nat, t), latest_visible(big, t), latest_visible(ss, t)
    if not (a and b and c):
        return None
    for x in (a, b, c):
        if str(x.get("state")) == "减持":
            return None
    return (a, b, c)


def eval_fwd(snaps, t, hold=5):
    """信号日 t 之后的 fwd 表现（日K近似）。返回 dict 或 None。"""
    days = [s for s in snaps if str(s.get("date") or "")[:10] > t[:10]]
    if not days:
        return None
    entry = float(days[0].get("close") or 0)
    if entry <= 0:
        return None
    win = days[:hold]
    hi = max((float(s.get("high") or 0) for s in win), default=0.0)
    lv, best_pct = "fail", (hi / entry - 1) * 100
    if any(float(s.get("low") or 0) >= entry * 1.05 for s in win):
        lv = "success"
    elif any(float(s.get("low") or 0) >= entry * 1.01 for s in win):
        lv = "ok"
    elif hi > entry * 1.01:
        lv = "weak"
    c5 = float(win[-1].get("close") or entry) / entry * 100 - 100
    c10 = 0.0
    if len(days) >= 10:
        c10 = float(days[9].get("close") or entry) / entry * 100 - 100
    return {"entry": entry, "max_pct": best_pct, "c5_pct": c5, "c10_pct": c10,
            "level": lv}


def collect(flow, kl, since, notice_days):
    """返回 {'A': [...], 'B': [...]}，每项 = eval 结果 dict。"""
    out = {"A": [], "B": [], "C": [], "D30": [], "D90": []}
    for code, v in flow.items():
        if not isinstance(v, dict):
            continue
        sid = code if code[:2] in ("sh", "sz", "bj") else (
            ("sh" if code[:1] in "569" else "sz") + code)
        snaps = (kl.get(sid) or kl.get(code) or {}).get("snaps") or []
        if len(snaps) < 30:
            continue
        nat, big, ss = v.get("nat") or [], v.get("big") or [], v.get("ss") or []
        if not nat or not ss:
            continue
        # 候选信号日：两侧所有披露日（去重、≥since）
        cand = sorted({str(r.get("notice") or "")[:10] for r in (nat + ss)
                       if str(r.get("notice") or "")[:10] >= since})
        seen = {"A": set(), "B": set(), "C": set(), "D30": set(), "D90": set()}
        for t in cand:
            for tag, fn in (("A", lambda: sig_A(nat, ss, t, notice_days)),
                            ("B", lambda: sig_B(nat, ss, t)),
                            ("C", lambda: sig_C(nat, big, ss, t)),
                            ("D30", lambda: sig_A(nat, ss, t, 30)),
                            ("D90", lambda: sig_A(nat, ss, t, 90))):
                g = fn()
                if not g:
                    continue
                if tag != "A":
                    # 同一「持仓期组合」只记一次（否则同一票每个披露日都算一次，重复计数）
                    _k = (code,) + tuple(str(x.get("end")) for x in g)
                    if _k in seen[tag]:
                        continue
                    seen[tag].add(_k)
                key = (code, t, tag)
                if key in seen["A"]:
                    continue
                seen["A"].add(key)
                r = eval_fwd(snaps, t)
                if r:
                    r["code"], r["date"] = code, t
                    r["state"] = "/".join(str(x.get("state")) for x in g)
                    out[tag].append(r)
    return out


def summ(rows):
    n = len(rows)
    if not n:
        return {"n": 0}
    # 胜率 = T+5 / T+10 **收盘**正收益（用 5 日内 max(high) 判"胜"会恒为胜，失真）
    win5 = sum(1 for r in rows if r["c5_pct"] > 0)
    win10 = sum(1 for r in rows if r["c10_pct"] > 0)
    ok = sum(1 for r in rows if r["level"] in ("success", "ok"))
    succ = sum(1 for r in rows if r["level"] == "success")
    fail = sum(1 for r in rows if r["level"] == "fail")
    return {
        "n": n,
        "win5": win5 / n * 100,
        "win10": win10 / n * 100,
        "succ_rate": succ / n * 100,
        "ok_rate": ok / n * 100,
        "fail_rate": fail / n * 100,
        "avg_max": sum(r["max_pct"] for r in rows) / n,
        "avg_c5": sum(r["c5_pct"] for r in rows) / n,
        "avg_c10": sum(r["c10_pct"] for r in rows) / n,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default="2019-01-01")
    ap.add_argument("--notice-days", type=int, default=7)
    a = ap.parse_args()
    flow, kl = load_flow(), load_kline()
    print("数据：%d 票持仓历史 + kline_store %d 只" % (len(flow), len(kl)))
    res = collect(flow, kl, a.since, a.notice_days)
    print("\n口径对比（since=%s，A 档 noticeDays=%d，持有窗口 5 交易日）" % (a.since, a.notice_days))
    print("%-26s %6s %8s %8s %8s %7s %7s %8s %8s %8s" % (
        "口径", "信号数", "T+5胜%", "T+10胜%", "✅成功%", "✅+◐%", "❌失败%",
        "均最高%", "均T+5%", "均T+10%"))
    names = {"A": "A 严格(近7天双买入)", "B": "B 宽松(没卖出就能买)",
             "C": "C 三机构同时持有(国+基+社)",
             "D30": "D30 近1月双买入(未退出)", "D90": "D90 近3月双买入(未退出)"}
    for tag in ("A", "B", "C", "D30", "D90"):
        s = summ(res[tag])
        if not s.get("n"):
            print("%-26s %6d" % (names[tag], 0))
            continue
        print("%-26s %6d %8.1f %8.1f %8.1f %7.1f %7.1f %8.2f %8.2f %8.2f" % (
            names[tag], s["n"], s["win5"], s["win10"], s["succ_rate"],
            s["ok_rate"], s["fail_rate"], s["avg_max"], s["avg_c5"], s["avg_c10"]))
    # B 档滞后风险量化：信号后「下一期即减持」或「后续再无记录（已退出）」的比例
    risk = 0
    tot = 0
    for r in res["B"]:
        code, t = r["code"], r["date"]
        v = flow.get(code) or {}
        for tag in ("nat", "ss"):
            recs = sorted((v.get(tag) or []),
                          key=lambda x: (str(x.get("end")), str(x.get("notice"))))
            idx = [i for i, x in enumerate(recs) if str(x.get("notice"))[:10] <= t[:10]]
            if not idx:
                continue
            tot += 1
            i = idx[-1]
            if i + 1 >= len(recs):
                risk += 1            # 信号之后再无该机构记录 → 已退出（卖出）
            elif str(recs[i + 1].get("state")) == "减持":
                risk += 1
    if tot:
        print("\n⚠️ B 档滞后风险：信号后 %s 的比例 = %d/%d = %.1f%%"
              % ("「已退出/减持」", risk, tot, risk / tot * 100))
    # ★ C 档案例清单（用户 2026-09-19 问：历史上哪些票国+基+社同时持有、后期盈利如何）
    if res["C"]:
        rows_c = sorted(res["C"], key=lambda r: -r["max_pct"])
        print("\n★ C 档「国家队+大基金+社保 同时持有」案例 %d 个（按 5 日最高涨幅降序）"
              % len(rows_c))
        print("  %-10s %-8s %-12s %-16s %9s %9s %9s" % (
            "名称", "代码", "起始披露", "三家状态", "最高%", "T+5%", "T+10%"))
        for r in rows_c[:20]:
            _c = r["code"]
            _sid = _c if _c[:2] in ("sh", "sz", "bj") else (
                ("sh" if _c[:1] in "569" else "sz") + _c)
            nm = ((flow.get(_c) or {}).get("name")
                  or (kl.get(_sid) or {}).get("name") or "—")
            print("  %-10s %-8s %-12s %-16s %9.2f %9.2f %9.2f" % (
                str(nm)[:10], r["code"], r["date"], str(r["state"])[:16],
                r["max_pct"], r["c5_pct"], r["c10_pct"]))
        codes = sorted({r["code"] for r in rows_c})
        print("  涉及个股 %d 只：%s" % (len(codes), "、".join(codes)))
    print("说明：历史无分钟K → 用日K近似（成功=某日 low ≥ 买入价×1.05）；"
          "买入价=信号日后首个交易日收盘。")


if __name__ == "__main__":
    main()
