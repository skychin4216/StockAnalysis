# -*- coding: utf-8 -*-
"""选股信号回测（用户 2026-09-19 判定口径，2026-09-19 新增）。

## 判定口径（与复盘巡诊 d 段完全一致）

    ✅成功 = 5 日内价格 > 入选价×1.05 且**维持 1 小时以上**
    ◐尚可 = 涨幅 +1%~5% 且**站稳 30 分钟以上**
    ⚠未站稳 = 摸到 >+1% 但没站稳 30 分钟
    ❌失败 = 5 日内最高（可成交）价 ≤ 入选价×1.01

## 数据与限制（重要）

- 信号：`smalltools/_records/_pick_ledger.jsonl`（选股账本，每行 = 一个交易日的 picks）。
  **账本只保留最近约 10 个交易日** → 本回测是「近期滚动」口径，不是长历史。
- 价格：
  · 优先 `_intraday`（5 分钟K，覆盖近 ~8 个交易日）→ 可精确算「维持 1h / 站稳 30m」；
  · 分钟K覆盖不到的日期 → 用 kline_store 日K**近似**：
    成功 = 某日 low ≥ 入选价×1.05（当日全程在 +5% 之上，比"维持 1h"更严），
    尚可 = 某日 low ≥ ×1.01；收益 = 5 日内 max(high)/入选价 − 1。会被单独标注 `approx`。
- 入选价：账本 close（缺失则回填入选日 K 线收盘）。

用法：
    python _review_backtest.py                # 全部账本信号
    python _review_backtest.py --days 5       # 只看最近 5 个交易日
    python _review_backtest.py --top 15       # 明细行数
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

LEDGER = os.path.join(HERE, "_records", "_pick_ledger.jsonl")
LV_CN = {"success": "✅成功", "ok": "◐尚可", "weak": "⚠未站稳",
         "fail": "❌失败", "nodata": "—"}


def load_signals(days=None):
    """账本 → {(secid, asof): {name, period, asof, base, src}}（同票同月取最新）。"""
    rows = []
    try:
        with open(LEDGER, encoding="utf-8") as f:
            for ln in f:
                ln = ln.strip()
                if ln:
                    try:
                        rows.append(json.loads(ln))
                    except ValueError:
                        continue
    except OSError:
        return []
    if days:
        rows = rows[-int(days):]
    sig = {}
    for lg in rows:
        asof = (lg.get("asof") or "")[:10]
        for p in (lg.get("picks") or []):
            sid = p.get("secid")
            if not sid or not asof:
                continue
            k = (sid, asof)
            cur = sig.get(k)
            if cur is None or (p.get("close") or 0):
                sig[k] = {"secid": sid, "asof": asof, "name": p.get("name") or sid,
                          "period": p.get("period"), "base": p.get("close"),
                          "src": p.get("src")}
    return list(sig.values())


def load_kline():
    try:
        with open(os.path.join(DATA, "kline_store.json"), encoding="utf-8") as f:
            return json.load(f)
    except Exception:  # noqa: BLE001
        return {}


def eval_daily(snaps, base, asof, days=5):
    """日K近似判定（分钟K不可用时）。"""
    fut = [s for s in (snaps or []) if str(s.get("date") or "")[:10] > asof][:days]
    if not fut or not base:
        return None
    entry = base
    hi = max((float(s.get("high") or 0) for s in fut), default=0.0)
    lv = "fail"
    if any(float(s.get("low") or 0) >= entry * 1.05 for s in fut):
        lv = "success"
    elif any(float(s.get("low") or 0) >= entry * 1.01 for s in fut):
        lv = "ok"
    elif hi > entry * 1.01:
        lv = "weak"
    return {"level": lv, "max_pct": (hi / entry - 1) * 100 if entry else 0.0,
            "approx": True, "n": len(fut)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=None)
    ap.add_argument("--top", type=int, default=12)
    a = ap.parse_args()
    sigs = load_signals(a.days)
    kl = load_kline()
    try:
        import _intraday
    except Exception:  # noqa: BLE001
        _intraday = None
    rows = []
    for s in sigs:
        sid = s["secid"]
        snaps = (kl.get(sid) or kl.get(sid[2:]) or {}).get("snaps") or []
        base = s.get("base")
        if not base:                       # 回填入选日收盘
            for x in snaps:
                if str(x.get("date") or "")[:10] == s["asof"]:
                    base = float(x.get("close") or 0)
                    break
        if not base:
            continue
        r = None
        if _intraday is not None:
            try:
                j = _intraday.eval_trade(sid, base, since=s["asof"])
                if j.get("level") != "nodata":
                    r = {"level": j["level"], "max_pct": j.get("best_pct") or 0.0,
                         "approx": False, "run_1h": j.get("run_1h"), "run_30m": j.get("run_30m")}
            except Exception:  # noqa: BLE001
                r = None
        if r is None:
            r = eval_daily(snaps, base, s["asof"])
        if r is None:
            continue
        r.update({"name": s["name"], "secid": sid, "asof": s["asof"],
                  "period": s.get("period"), "base": base})
        rows.append(r)

    n = len(rows)
    if not n:
        print("无可用信号（账本为空或缺价）")
        return
    cnt = {}
    for r in rows:
        cnt[r["level"]] = cnt.get(r["level"], 0) + 1
    succ = cnt.get("success", 0)
    ok = cnt.get("ok", 0)
    weak = cnt.get("weak", 0)
    fail = cnt.get("fail", 0)
    avg_max = sum(r["max_pct"] for r in rows) / n
    avg_win = sum(r["max_pct"] for r in rows if r["max_pct"] > 0) / max(
        1, sum(1 for r in rows if r["max_pct"] > 0))
    print("选股信号回测（账本 %s 条信号，判定口径 = 用户 2026-09-19 版）"
          % ("最近 %d 日 " % a.days if a.days else ""))
    print("  ✅成功 %d (%.1f%%)  ◐尚可 %d (%.1f%%)  ⚠未站稳 %d (%.1f%%)  ❌失败 %d (%.1f%%)"
          % (succ, succ / n * 100, ok, ok / n * 100, weak, weak / n * 100,
             fail, fail / n * 100))
    print("  成功率(✅/全部) = %.1f%%   ｜  ✅+◐ = %.1f%%   ｜  必亏率(❌) = %.1f%%"
          % (succ / n * 100, (succ + ok) / n * 100, fail / n * 100))
    print("  平均最高涨幅 = %+.2f%%  ｜  盈利样本均幅 = %+.2f%%"
          % (avg_max, avg_win))
    ap_cnt = sum(1 for r in rows if r.get("approx"))
    print("  样本构成：分钟K精确 %d 条 / 日K近似 %d 条（分钟K仅覆盖近 ~8 个交易日）"
          % (n - ap_cnt, ap_cnt))
    rows.sort(key=lambda r: -r["max_pct"])
    print("\n  ── 最强 %d ──" % a.top)
    for r in rows[:a.top]:
        print("   %-8s %-8s 入选%s base=%-8.2f %-8s 最高%+.2f%%%s"
              % (r["name"][:8], r["secid"][2:], r["asof"], r["base"],
                 LV_CN.get(r["level"], r["level"]), r["max_pct"],
                 "(日K近似)" if r.get("approx") else ""))
    print("\n  ── 最弱 %d ──" % min(8, n))
    for r in rows[-min(8, n):]:
        print("   %-8s %-8s 入选%s base=%-8.2f %-8s 最高%+.2f%%%s"
              % (r["name"][:8], r["secid"][2:], r["asof"], r["base"],
                 LV_CN.get(r["level"], r["level"]), r["max_pct"],
                 "(日K近似)" if r.get("approx") else ""))


if __name__ == "__main__":
    main()
