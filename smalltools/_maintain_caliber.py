# -*- coding: utf-8 -*-
"""「维持口径」胜率统计（2026-09-20 用户需求）。

## 两套口径（用户 2026-09-20 拍板）

    超短 / 短线  →  口径 B（本脚本）：T+1~T+N 内出现「≥ 买入价×1.05 且维持 30 分钟」
    中线 / 长线  →  口径 A（沿用）：日K + tp/sl/maxHold 结算（`_walk_forward` 已有）

**为什么超短/短线要用口径 B**：口径 A 的"失败"里混着「盘中摸到 +6% 但收盘回落触发
止损/到期」——那是**选对了没卖好**，却和"选错票一路跌"记成同一笔，导致**加再多因子
指标也看不出提升**。口径 B 只问一件事：**这只票在 T+1~T+5 有没有给出可兑现的 5% 空间**。

## 判定定义（严格按用户口径）

    买入价 base = 入选日(asof) 收盘价
    阈值   thr  = base × 1.05
    命中   = T+1~T+N 内，存在**连续 6 根 5 分钟K 的 high 都 ≥ thr**
             （6 根 × 5 分钟 = **30 分钟**；用 high 表示"该价挂单能成交"，
               若要求"全程站稳"应改用 low，会更严 —— 本脚本用 high，偏宽松一档）

    N 分层：T+1（隔日即给空间）/ T+1~T+3（**短线主力评价窗口**）/ T+1~T+5（完整）

## 数据源

`baostock` 5 分钟线（免费/免装软件，实测覆盖 **2020-01-02 ~ 2026-09-18**）。
故本口径的**样本区间 = 2020-01 起**（2008~2020 段缺分钟数据，只能用日K近似，另行标注）。

## 用法

    python _maintain_caliber.py --stat                 # 看信号量/可算量（不拉数据）
    python _maintain_caliber.py --limit 20             # 先试 20 只票
    python _maintain_caliber.py                        # 全量（建议后台跑，每票约 3~8 秒）
    python _maintain_caliber.py --start 2020-01-01     # 指定信号起点
    python _maintain_caliber.py --json data/_maintain_caliber.json   # 落盘供报告消费

输出：{period: {n, t1, t3, t5, t1_pct, t3_pct, t5_pct, avg_max}}
"""
import argparse
import glob
import json
import os
import sys
import time
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RECORD_DIR = os.path.join(HERE, "_records")
OUT_JSON = os.path.join(ROOT, "data", "_maintain_caliber.json")
BS_START = "2020-01-01"      # baostock 分钟数据边界（实测）
RUN_LEN = 6                  # 连续 N 根 5 分钟K = 30 分钟


def _shift(date_s, days):
    """'2020-01-02' + N 天（自然日，用于圈定请求窗口）。"""
    import datetime as dt  # noqa: PLC0415
    y, m, d = (int(x) for x in date_s.split("-"))
    return (dt.date(y, m, d) + dt.timedelta(days=days)).isoformat()


def _bs_sid(secid):
    s = secid.lower().replace(".", "")
    return "%s.%s" % (s[:2], s[2:]) if s[:2] in ("sh", "sz", "bj") else s


def load_signals(start, periods=("超短", "短线")):
    """{secid: [(asof, base, period), ...]}（base 从 kline_store 取 asof 收盘）。"""
    sys.path.insert(0, HERE)
    from _kline_store import load_store  # noqa: PLC0415
    store = load_store()

    def close_of(secid, d):
        for s in (store.get(secid) or {}).get("snaps") or []:
            if s.get("date") == d:
                try:
                    return float(s.get("close") or 0)
                except (TypeError, ValueError):
                    return None
        return None

    sig = defaultdict(list)
    n_raw = 0
    for p in sorted(glob.glob(os.path.join(RECORD_DIR, "selected_*.json"))):
        try:
            d = json.load(open(p, encoding="utf-8"))
        except Exception:  # noqa: BLE001
            continue
        for per in periods:
            for s in (d.get("signals") or {}).get(per) or []:
                try:
                    secid, _name, asof = s[0], s[1], s[2]
                except (IndexError, TypeError):
                    continue
                n_raw += 1
                if asof < start:
                    continue
                base = close_of(secid, asof)
                if not base:
                    continue
                sig[secid].append({"asof": asof, "base": base, "period": per})
    return sig, n_raw


def fetch_m5(bs, secid, start=BS_START, end=None):
    """拉一只票的 5 分钟线 → {date: [bars...]}（按日分组，升序）。"""
    rs = bs.query_history_k_data_plus(
        _bs_sid(secid), "date,time,open,high,low,close,volume",
        start_date=start, end_date=end or time.strftime("%Y-%m-%d"),
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


def check(by_day, asof, base, run_len=RUN_LEN):
    """返回 (hit1, hit3, hit5, max_chg%)。"""
    days = sorted(by_day)
    idx = {d: i for i, d in enumerate(days)}
    i0 = idx.get(asof)
    if i0 is None:
        return None
    thr = base * 1.05
    hits, max_chg = {}, 0.0
    for n in (1, 3, 5):
        win = days[i0 + 1: i0 + 1 + n]
        ok = False
        for d in win:
            run = 0
            for b in by_day[d]:
                if b["high"] > max_chg * base / 100:      # 记录最大涨幅（%）
                    pass
                if b["high"] >= thr:
                    run += 1
                    if run >= run_len:
                        ok = True
                        break
                else:
                    run = 0
            if ok:
                break
        hits[n] = ok
    # 最大涨幅（T+1~T+5 内最高 high 相对 base）
    top = 0.0
    for d in days[i0 + 1: i0 + 6]:
        for b in by_day[d]:
            top = max(top, b["high"])
    max_chg = (top / base - 1) * 100 if base else 0.0
    return hits[1], hits[3], hits[5], max_chg


def main():
    ap = argparse.ArgumentParser(description="维持口径胜率（超短/短线，T+1~T+5 ≥1.05 且维持30分钟）")
    ap.add_argument("--start", default="2020-01-01", help="信号起点（分钟数据边界）")
    ap.add_argument("--limit", type=int, default=0, help="只算前 N 只票（0=全部）")
    ap.add_argument("--stat", action="store_true", help="只统计信号量，不拉数据")
    ap.add_argument("--json", default=OUT_JSON)
    a = ap.parse_args()

    sig, n_raw = load_signals(a.start)
    n_sig = sum(len(v) for v in sig.values())
    print("信号：原始 %d 条 | %s 起可算 %d 条 | 涉及 %d 只票"
          % (n_raw, a.start, n_sig, len(sig)))
    if a.stat:
        return 0
    if not n_sig:
        print("⚠️ 无可算信号（检查 kline_store 是否含 asof 当日收盘）")
        return 1

    try:
        import baostock as bs  # noqa: PLC0415
    except ImportError:
        print("❌ 未安装 baostock：pip install baostock")
        return 1
    lg = bs.login()
    if lg.error_code != "0":
        print("❌ 登录失败:", lg.error_msg)
        return 1

    codes = sorted(sig)
    if a.limit:
        codes = codes[:a.limit]
    stat = {p: {"n": 0, "t1": 0, "t3": 0, "t5": 0, "sum_max": 0.0} for p in ("超短", "短线")}
    t0, done, skipped = time.time(), 0, 0
    for i, secid in enumerate(codes, 1):
        items = sig[secid]
        for it in items:
            # 2026-09-20 性能优化：原先「每票一次拉 6.7 年全量」（≈78k 根 / 170 秒，
            # 102 只票要 5 小时）。改为**只拉该信号日附近的小窗口**
            # （前 3 天 ~ 后 10 天，≈400 根 / 1~2 秒），总耗时降到十几分钟。
            try:
                by_day = fetch_m5(bs, secid, _shift(it["asof"], -3), _shift(it["asof"], 10))
            except Exception as e:  # noqa: BLE001
                print("  ⚠️ %s %s 拉取失败: %s" % (secid, it["asof"], str(e)[:40]))
                continue
            if not by_day:
                skipped += 1
                continue
            r = check(by_day, it["asof"], it["base"])
            if r is None:
                continue
            h1, h3, h5, mx = r
            s = stat[it["period"]]
            s["n"] += 1
            s["t1"] += 1 if h1 else 0
            s["t3"] += 1 if h3 else 0
            s["t5"] += 1 if h5 else 0
            s["sum_max"] += mx
        done += 1
        if i <= 3 or i % 20 == 0:
            print("  [%d/%d] %-10s 累计信号 %d （%.0fs）"
                  % (i, len(codes), secid, sum(v["n"] for v in stat.values()), time.time() - t0))
    bs.logout()

    out = {}
    print()
    print("=== 维持口径胜率（T+1~T+N 内 ≥买入价×1.05 且连续 %d 根5分钟K 站上） ===" % RUN_LEN)
    print("  %-6s %6s %10s %10s %10s %10s" % ("周期", "样本", "T+1命中", "T+1~3命中", "T+1~5命中", "均最高%"))
    for per in ("超短", "短线"):
        s = stat[per]
        n = s["n"] or 1
        row = {"n": s["n"], "t1": s["t1"], "t3": s["t3"], "t5": s["t5"],
               "t1_pct": round(s["t1"] / n * 100, 1),
               "t3_pct": round(s["t3"] / n * 100, 1),
               "t5_pct": round(s["t5"] / n * 100, 1),
               "avg_max": round(s["sum_max"] / n, 2),
               "start": a.start, "run_len": RUN_LEN}
        out[per] = row
        print("  %-6s %6d %9.1f%% %9.1f%% %9.1f%% %9.2f" % (
            per, s["n"], row["t1_pct"], row["t3_pct"], row["t5_pct"], row["avg_max"]))
    out["_meta"] = {"source": "baostock 5min", "start": a.start, "run_len": RUN_LEN,
                    "generated": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "note": "命中=该价位连续30分钟可成交(high≥thr)；2008~2020无分钟数据不可算"}
    os.makedirs(os.path.dirname(a.json), exist_ok=True)
    with open(a.json, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("\n✅ 落盘:", a.json, "| 处理 %d 只（跳过无数据 %d）| 耗时 %.0fs"
          % (done, skipped, time.time() - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
