# -*- coding: utf-8 -*-
"""全史逐日真实 XML DAG 跑单 + T-1 单调判死预筛覆盖验证（2026-09-10）

用户口径：从 2008 至今（market_data.db 有 2008-01-02 起 4543 个交易日）每天真实跑
完整 XML DAG 主线（四周期），产出历史每日订单；同时逐日用「T-1 数据」跑单调判死
预筛，验证：预筛幸存池是否从未漏掉 T 日 DAG 选中的票（漏杀=0 是硬指标），并统计
每日幸存/压缩率 —— 这是「下载 >5min 时只用预筛幸存池降级」方案的全史正确性背书。

正确性（--verify 实测 FAIL 的教训，2026-09-10）：
  引擎部分节点【不按 asof 过滤】（读 snaps[-1] 等），喂「全史缓存+asof」会泄漏未来
  数据 → 每日必须给引擎只含 ≤asof 根的缓存。
  实现：逐日【增量生长】——每只票一个推进指针，日历每前进一天只 append 当日新根，
  任一交易日传给引擎的缓存内容恒 == 截至该日的真值（无未来、无 O(N²) 重建）。
  指针推进总量 = 全池历史总行数(~1M)，4543 天循环开销可忽略。

断点续跑：AutoQuant/data/history_dag_run.jsonl（逐日一条）+ .meta 记录已完成日期。
输出每行 json：{date, pool, surv, dead, compression, orders, miss, miss_det, run_s}

用法：
  python _history_dag_run.py --verify            # 增量缓存 vs 独立重建 全等校验
  python _history_dag_run.py                      # 断点续跑全史（挂机，首 30 天看 ETA）
  python _history_dag_run.py --limit 20           # 冒烟（先看单日耗时估算 ETA）
  python _history_dag_run.py --summarize          # 汇总已生成 jsonl
"""
import argparse
import collections
import datetime
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
AUTOQ = os.path.join(ROOT, "AutoQuant")
USECASES = os.path.join(ROOT, "app", "src", "main", "assets", "usecases")
for _p in (HERE, AUTOQ, USECASES):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import _market_db  # noqa: E402
from usecase_pipeline import UseCaseRunner  # noqa: E402
from usecase_screen import prescreen, INDEX_PREFIX  # noqa: E402

PERIODS = ["ultra_short", "short", "mid", "long"]
OUT_JSONL = os.path.join(AUTOQ, "data", "history_dag_run.jsonl")
META_FILE = os.path.join(AUTOQ, "data", "history_dag_run.meta")
MIN_ELIGIBLE = 30
MIN_BARS = 30


def load_rows():
    """从 market_data.db 读全史：每只票按日期升序的原始行元组（省内存）。
    池 = _kline_cache.json 键集。返回 (rows_by, names, cal)。"""
    with open(os.path.join(HERE, "_kline_cache.json"), encoding="utf-8") as f:
        pool = json.load(f)
    conn = _market_db.get_conn()
    rows_by, names = {}, {}
    cal = None
    for secid, ent in pool.items():
        cur = conn.execute(
            "SELECT date,open,high,low,close,volume,change_pct,turnover,name "
            "FROM kline WHERE secid=? ORDER BY date", (secid,)).fetchall()
        rows_by[secid] = cur
        names[secid] = ent.get("name") or (cur[-1][8] if cur and cur[-1][8] else secid)
        if secid == "sh000001":
            cal = [r[0] for r in cur]
    conn.close()
    if not cal:
        raise SystemExit("market_data.db 无 sh000001 日历数据")
    return rows_by, names, cal


def _snap(row):
    return {
        "date": row[0],
        "open": row[1] if row[1] is not None else 0.0,
        "high": row[2] if row[2] is not None else 0.0,
        "low": row[3] if row[3] is not None else 0.0,
        "close": row[4] if row[4] is not None else 0.0,
        "volume": row[5] if row[5] is not None else 0.0,
        "changePct": row[6] if row[6] is not None else 0.0,
        "turnover": row[7] if row[7] is not None else 0.0,
    }


def fresh_truncated(rows_by, names, asof):
    """独立重建（verify 基准真值）：全行 ≤ asof。"""
    return {sid: {"name": names[sid],
                  "snaps": [_snap(r) for r in rows if r[0] <= asof]}
            for sid, rows in rows_by.items()}


def orders_at(cache, asof, periods=None):
    out = {}
    for p in (periods or PERIODS):
        try:
            runner = UseCaseRunner(p, cache=cache, asof=asof, period=p)
            res = runner.run()
            out[p] = [o["secid"] for o in (res.get("orders") or [])]
        except Exception as e:  # noqa: BLE001
            out[p] = ["__err__:%s" % e]
    return out


def verify(rows_by, names, cal, days=5):
    """抽样日：独立重建真值 vs 增量生长缓存 的订单全等校验。"""
    if len(cal) < 100:
        raise SystemExit("日历过短")
    pos_idx = sorted(set(int(len(cal) * x)
                         for x in (0.08, 0.2, 0.4, 0.6, 0.85, 0.98)))[:days]
    samples = set(cal[i] for i in pos_idx)   # 字符串抽样日（勿用整数 vs 字符串比较）
    print("== --verify 增量缓存 vs 独立重建 订单全等校验 抽样=%s =="
          % sorted(samples))
    cur = {sid: {"name": names[sid], "snaps": []} for sid in rows_by}
    pos = {sid: 0 for sid in rows_by}
    all_ok = True
    for date in cal:
        for sid, rows in rows_by.items():
            i = pos[sid]
            while i < len(rows) and rows[i][0] <= date:
                cur[sid]["snaps"].append(_snap(rows[i]))
                i += 1
            pos[sid] = i
        if date in samples:
            t0 = time.time()
            a = orders_at(cur, date)
            b = orders_at(fresh_truncated(rows_by, names, date), date)
            diffs = []
            for p in PERIODS:
                sa, sb = set(a[p]), set(b[p])
                if sa != sb:
                    diffs.append("%s: incr=%d fresh=%d 差=%s"
                                 % (p, len(sa), len(sb), sorted(sa ^ sb)[:6]))
            ok = not diffs
            all_ok &= ok
            print("%s [%s] 耗时%.0fs  %s"
                  % (date, "PASS" if ok else "FAIL", time.time() - t0,
                     " | ".join(diffs) or "四周期一致"))
    print("== verify 结论: %s ==" % ("PASS（增量缓存可用）" if all_ok else "FAIL"))
    return all_ok


def eligible_count(cur):
    n = 0
    for sid, e in cur.items():
        if sid.startswith(INDEX_PREFIX):
            continue
        if any(w in e.get("name", "") for w in ("ST", "退", "PT")):
            continue
        if len(e["snaps"]) >= MIN_BARS:
            n += 1
    return n


def run_full(rows_by, names, cal, start=None, limit=None):
    os.makedirs(os.path.dirname(OUT_JSONL), exist_ok=True)
    meta = {}
    try:
        with open(META_FILE, encoding="utf-8") as f:
            meta = json.load(f)
    except (OSError, ValueError):
        pass
    last_done = meta.get("last_done")
    t_total0 = time.time()
    n_skip = 0
    n_miss_days = 0
    exists = os.path.exists(OUT_JSONL)
    fh = open(OUT_JSONL, "a", encoding="utf-8") if exists else \
        open(OUT_JSONL, "w", encoding="utf-8")
    cur = {sid: {"name": names[sid], "snaps": []} for sid in rows_by}
    pos = {sid: 0 for sid in rows_by}
    run_n = 0
    started = False
    last_date = None
    for date in cal:
        if start and date < start:
            continue
        if last_done and date <= last_done:
            continue
        for sid, rows in rows_by.items():
            i = pos[sid]
            while i < len(rows) and rows[i][0] <= date:
                cur[sid]["snaps"].append(_snap(rows[i]))
                i += 1
            pos[sid] = i
        if not started:
            print("全史跑单开始：%s（断点=%s）" % (date, last_done or "-"))
            started = True
        if eligible_count(cur) < MIN_ELIGIBLE:
            n_skip += 1
            continue
        t0 = time.time()
        prev = None
        for d in cal:
            if d >= date:
                break
            prev = d
        surv, dead = prescreen(cur, prev or date)
        ords = orders_at(cur, date)
        miss, miss_det = [], []
        for p in PERIODS:
            for c in ords[p]:
                if c.startswith("__err__"):
                    continue
                if c not in surv:
                    miss.append(c)
                    miss_det.append({"code": c, "period": p,
                                     "reason": (dead.get(c) or {}).get("reason", "不在幸存池")})
        pool = len(cur)
        row = {
            "date": date, "pool": pool, "surv": len(surv), "dead": len(dead),
            "compression": round((1 - len(surv) / pool) * 100, 1) if pool else 0,
            "orders": ords, "miss": miss, "miss_det": miss_det,
            "run_s": round(time.time() - t0, 1),
        }
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        fh.flush()
        run_n += 1
        last_date = date
        if miss:
            n_miss_days += 1
        if run_n % 10 == 0 or run_n == 1:
            with open(META_FILE, "w", encoding="utf-8") as f:
                json.dump({"last_done": date, "updated_at": datetime.datetime.now().isoformat(),
                           "days_done": run_n}, f)
            el = time.time() - t_total0
            per_day = el / max(1, run_n)
            remain = (len(cal) - cal.index(date) - 1) * per_day / 3600
            print("[%s] done=%d 幸存=%d 压缩=%.0f%% 订单=%s 单日%.1fs 累计%.0fs ETA约%.1fh"
                  % (date, run_n, len(surv), row["compression"],
                     {p: len(v) for p, v in ords.items()}, time.time() - t0, el, remain))
            if miss:
                print("    ⚠ 漏杀 %d: %s" % (len(miss), miss_det[:4]))
        if limit and run_n >= limit:
            break
    with open(META_FILE, "w", encoding="utf-8") as f:
        json.dump({"last_done": last_date or cal[-1],
                   "updated_at": datetime.datetime.now().isoformat(),
                   "days_done": run_n}, f)
    fh.close()
    print("完成/中止：共跑 %d 个交易日（跳过 %d 个过早期），漏杀日 %d，总耗时 %.0fs"
          % (run_n, n_skip, n_miss_days, time.time() - t_total0))


def summarize():
    if not os.path.exists(OUT_JSONL):
        print("尚无 %s" % OUT_JSONL)
        return
    n = miss_days = miss_codes = 0
    first = last = None
    comps = []
    reason_cnt = collections.Counter()
    with open(OUT_JSONL, encoding="utf-8") as f:
        for ln in f:
            ln = ln.strip()
            if not ln:
                continue
            r = json.loads(ln)
            n += 1
            first = first or r["date"]
            last = r["date"]
            comps.append(r["compression"])
            if r["miss"]:
                miss_days += 1
                miss_codes += len(r["miss"])
                reason_cnt[(r["miss_det"][0]["reason"] if r["miss_det"] else "?")] += 1
    avg = sum(comps) / len(comps) if comps else 0
    print("汇总：%d 个交易日 %s → %s，平均压缩 %.1f%%（min %.0f%% / max %.0f%%）"
          % (n, first, last, avg, min(comps) if comps else 0, max(comps) if comps else 0))
    print("漏杀日 %d 个 / 漏杀票 %d 个" % (miss_days, miss_codes))
    for reason, c in reason_cnt.most_common(10):
        print("  - [%s] ×%d" % (reason, c))


def main():
    ap = argparse.ArgumentParser(description="全史逐日 XML DAG 跑单 + 预筛覆盖验证")
    ap.add_argument("--verify", action="store_true", help="抽样日 增量vs重建 订单全等校验")
    ap.add_argument("--verify-days", type=int, default=5)
    ap.add_argument("--from", dest="start", default=None, help="起始日 YYYY-MM-DD")
    ap.add_argument("--limit", type=int, default=None, help="最多跑 N 个交易日（冒烟）")
    ap.add_argument("--summarize", action="store_true", help="只汇总已生成结果")
    args = ap.parse_args()
    if args.summarize:
        summarize()
        return
    print("载入 market_data.db 全史（元组行，省内存）…")
    rows_by, names, cal = load_rows()
    print("池 %d 只，交易日历 %s → %s 共 %d 日" % (len(rows_by), cal[0], cal[-1], len(cal)))
    if args.verify:
        ok = verify(rows_by, names, cal, days=args.verify_days)
        sys.exit(0 if ok else 3)
    run_full(rows_by, names, cal, start=args.start, limit=args.limit)


if __name__ == "__main__":
    main()
