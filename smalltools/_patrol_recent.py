# -*- coding: utf-8 -*-
"""最近选股巡诊（口径B：T+5 内 ≥买入价×1.05 且维持 30 分钟）—— 2026-09-20 用户需求。

数据源：`_records/_pick_reviews.json`（`_self_review.py` 的结算账本）
    键 = "asof|period|secid"，值含 `entry`（买入价）、`name`、`period`、`state`、`ret` …
    本脚本**只关心一件事**：这些票在 T+1~T+5 内有没有给出可兑现的 5% 空间。

判定口径与 `_maintain_caliber.py` **完全一致**（直接复用其 `check()`）：
    命中 = T+1~T+N 内存在「连续 2 根 5 分钟K 的 high 都 ≥ entry×1.05 × 6 根」
           （6 根 × 5min = 30 分钟）

同时给出**口径A 对照**（账本里的 `ret`，即实际持有收益），两相对照可看出
「选对没卖好」的比例。

用法：
    python _patrol_recent.py                 # 最近 30 天
    python _patrol_recent.py --days 60
    python _patrol_recent.py --push          # 推到微信群
    python _patrol_recent.py --all           # 不筛日期（全部账本）
"""
import argparse
import json
import os
import sys
import time
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from _maintain_caliber import _bs_sid, _shift, check  # noqa: E402  复用同口径判定

RECORDS = os.path.join(HERE, "_records", "_pick_reviews.json")
OUT_JSON = os.path.join(ROOT, "data", "_patrol_recent.json")
RUN_LEN = 6


def fetch_window(bs, secid, asof):
    """拉 asof 前3天~后10天的 5 分钟线 → {date: [bars]}。"""
    rs = bs.query_history_k_data_plus(
        _bs_sid(secid), "date,time,open,high,low,close,volume",
        start_date=_shift(asof, -3), end_date=_shift(asof, 12),
        frequency="5", adjustflag="2")
    by_day = defaultdict(list)
    while rs.error_code == "0" and rs.next():
        r = rs.get_row_data()
        if not r or not r[2]:
            continue
        try:
            by_day[r[0]].append({"t": r[1], "high": float(r[3]), "low": float(r[4])})
        except (ValueError, IndexError):
            continue
    return by_day


def main():
    ap = argparse.ArgumentParser(description="最近选股巡诊（口径B）")
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--push", action="store_true")
    ap.add_argument("--json", default=OUT_JSON)
    a = ap.parse_args()

    if not os.path.isfile(RECORDS):
        print("❌ 无选股账本:", RECORDS)
        return 1
    raw = json.load(open(RECORDS, encoding="utf-8"))
    rows = [v for v in raw.values() if isinstance(v, dict) and v.get("entry")]
    if not a.all:
        import datetime as dt  # noqa: PLC0415
        cut = (dt.date.today() - dt.timedelta(days=a.days)).isoformat()
        rows = [v for v in rows if (v.get("asof") or "") >= cut]
    if not rows:
        print("⚠️ 该区间无选股记录")
        return 1
    rows.sort(key=lambda x: x.get("asof") or "")
    print("巡诊样本 %d 笔（%s ~ %s）" % (len(rows), rows[0]["asof"], rows[-1]["asof"]))

    try:
        import baostock as bs  # noqa: PLC0415
    except ImportError:
        print("❌ 未安装 baostock")
        return 1
    bs.login()

    stat = defaultdict(lambda: {"n": 0, "t1": 0, "t3": 0, "t5": 0,
                                "sum_max": 0.0, "a_win": 0, "a_sum": 0.0})
    detail = []
    for i, r in enumerate(rows, 1):
        try:
            by_day = fetch_window(bs, r["secid"], r["asof"])
            res = check(by_day, r["asof"], float(r["entry"]), RUN_LEN) if by_day else None
        except Exception:  # noqa: BLE001
            res = None
        if res is None:
            continue
        h1, h3, h5, mx = res
        s = stat[r.get("period") or "?"]
        s["n"] += 1
        s["t1"] += 1 if h1 else 0
        s["t3"] += 1 if h3 else 0
        s["t5"] += 1 if h5 else 0
        s["sum_max"] += mx
        ret = r.get("ret")
        if isinstance(ret, (int, float)):
            s["a_sum"] += ret
            s["a_win"] += 1 if ret > 0 else 0
        detail.append({"asof": r["asof"], "period": r.get("period"), "secid": r["secid"],
                       "name": r.get("name"), "entry": r["entry"], "t1": h1, "t3": h3,
                       "t5": h5, "max_chg": round(mx, 2),
                       "ret_a": ret, "reason": r.get("reason")})
        if i % 15 == 0:
            print("  [%d/%d]" % (i, len(rows)))
    bs.logout()

    lines = ["🔍 最近选股巡诊（口径B：T+5 内 ≥买入价×1.05 且维持 30 分钟）",
             "样本区间 %s ~ %s ｜ 共 %d 笔" % (rows[0]["asof"], rows[-1]["asof"], len(detail)), ""]
    out = {}
    print()
    print("  %-6s %5s %9s %10s %10s %9s | %9s" % (
        "周期", "样本", "T+1命中", "T+1~3", "T+1~5", "均最高%", "口径A胜率"))
    for per in ("超短", "短线", "中线", "长线"):
        s = stat.get(per)
        if not s or not s["n"]:
            continue
        n = s["n"]
        row = {"n": n, "t1_pct": round(s["t1"] / n * 100, 1),
               "t3_pct": round(s["t3"] / n * 100, 1),
               "t5_pct": round(s["t5"] / n * 100, 1),
               "avg_max": round(s["sum_max"] / n, 2),
               "a_win_pct": round(s["a_win"] / n * 100, 1) if n else 0.0,
               "a_avg_ret": round(s["a_sum"] / n, 2) if n else 0.0}
        out[per] = row
        print("  %-6s %5d %8.1f%% %9.1f%% %9.1f%% %8.2f | %8.1f%%" % (
            per, n, row["t1_pct"], row["t3_pct"], row["t5_pct"],
            row["avg_max"], row["a_win_pct"]))
        lines.append("%s：样本 %d ｜ 口径B 命中 T+1 %.1f%% / T+1~3 %.1f%% / T+1~5 %.1f%%"
                     "（均最高 %+.2f%%）｜ 口径A 胜率 %.1f%%" % (
                         per, n, row["t1_pct"], row["t3_pct"], row["t5_pct"],
                         row["avg_max"], row["a_win_pct"]))

    # 诊断：口径B命中但口径A亏 → 「选对没卖好」
    both = [d for d in detail if d["t5"] and isinstance(d["ret_a"], (int, float)) and d["ret_a"] <= 0]
    if both:
        lines.append("")
        lines.append("⚠️ 「选对没卖好」%d 笔（口径B命中但口径A亏损）：%s"
                     % (len(both), "、".join("%s(%s %.1f→%.1f%%)" % (
                         d["name"] or d["secid"], d["asof"][5:], d["entry"],
                         d["max_chg"]) for d in both[:5])))
    text = "\n".join(lines)
    print("\n" + text)

    out["_meta"] = {"generated": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "range": [rows[0]["asof"], rows[-1]["asof"]], "n": len(detail),
                    "run_len": RUN_LEN, "source": "baostock 5min"}
    os.makedirs(os.path.dirname(a.json), exist_ok=True)
    with open(a.json, "w", encoding="utf-8") as f:
        json.dump({"summary": out, "detail": detail}, f, ensure_ascii=False, indent=1)
    print("✅ 落盘:", a.json)

    if a.push:
        try:
            import push_channel as PC  # noqa: PLC0415
            cfg = PC.load_notify_cfg()
            ok = PC.push("🔍 最近选股巡诊（口径B）", text, cfg, kind="notice")
            print("推送:", "✅" if ok else "❌")
        except Exception as e:  # noqa: BLE001
            print("推送异常:", e)
    return 0


if __name__ == "__main__":
    sys.exit(main())
