# -*- coding: utf-8 -*-
"""增量更新 _kline_cache.json 到最新交易日（仅补缺失日期，保留原有数据）。

同步写公共数据库 StockAnalysis/data/market_data.db（_market_db），
保证 exe / smalltools 使用同一数据源。

v2: 并发拉取（默认 10 workers）——手机端 HistoricalDataFetcher 用并发 10 拉
K 线 + 批量实时接口，比电脑端原先的串行 for+time.sleep(0.2) 快一个数量级。
v3 (2026-09-10):
  - 收盘后日常补「当日根」改走【批量实时快路径】：腾讯 qt.gtimg.cn/q= 一次请求
    30 只，224 只全池仅 8 次请求 ~20s（实测；对比逐只日K force 1058s）。日K接口
    在 network 抖动时整体断连/超时（2026-09-10 凌晨实测东财/腾讯日K全挂），
    批量实时接口稳。
  - fetch 层 Session 复用（keep-alive，backtest_guangmo 模块级 _SESSION）。
  - XD/DR 除权除息当日、批量缺失的票自动用日K接口逐只兜底。
  - 历史缺口/--force 修复仍走并发日K（默认 workers 提高至 30）。
v5 (2026-09-10 决策):
  - 废弃「ETA>5min → T-1 预筛只拉幸存池」降级。全史回放 4514 交易日实测
    漏杀 41.3%(20689/50126)、57% 的交易日有漏杀：预筛判死项里含大量
    「低位埋伏/深跌」通道本来要买的票，与低吸通道天然冲突。
    盘段任何时候都拉全池 —— 宁可本轮晚到，不可丢票。
"""
import json
import os
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import fetch_east, fetch_tencent, HEADERS, PROXIES
import requests
import _market_db

# v4 (2026-09-10): 多源分组抓取层。网络抖动东财/腾讯日K全挂时按 secid 分组
# 轮换到健康 qfq 源(东财多host/腾讯ifzq·sqt)；单源连续失败熔断、自动迁移；
# 未复权实时(qtg/sina)仅收盘后兜底「当日根」。
from _multi_source import fetch_one_qfq, realtime_fallback

CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
_LOCK = threading.Lock()  # 保护 SQLite 串行写（并发网络拉取 + 串行落库）
_BATCH_SIZE = 30          # 批量实时单次请求代码数
_SESSION = requests.Session()


def _probe_end(beg):
    """探测最近可用「已收盘」交易日：优先东财，失败自动回退腾讯。从今天往前最多退 7 天。

    关键修复(2026-09-10)：盘中(<15:00)今天尚未收盘，K线接口返回的是动态K，
    绝不能当作最新交易日写进历史缓存——否则当日盘中快照会被固化(如 9/9 晋控收 16.64
    实为早盘价)，收盘后因"缓存已最新"再无机会修正。
    因此盘中一律从"昨天"开始探测；收盘后(>=15:00)才允许从"今天"探测。
    """
    import datetime as _dt
    now = _dt.datetime.now()
    d = now.date()
    if now.time() < _dt.time(15, 0):
        d -= _dt.timedelta(days=1)  # 今天未收盘：不得把今天当 END
    for _ in range(8):
        end = d.strftime("%Y%m%d")
        snaps = []
        try:
            _, snaps = fetch_east("sh000001", beg, end)
        except Exception:
            snaps = []
        if not snaps:
            try:
                _, snaps = fetch_tencent("sh000001", beg, end)
            except Exception:
                snaps = []
        if snaps:
            return end
        d -= _dt.timedelta(days=1)
    return d.strftime("%Y%m%d")


def _beg_for(end_str):
    """BEG 动态取 END 前 10 个自然日，只补缺失区间。兼容 'YYYY-MM-DD' / 'M-D' / 'YYYYMMDD'。"""
    import datetime as _dt
    parts = end_str.split("-")
    if len(parts) == 3:
        y, m, d = int(parts[0]), int(parts[1]), int(parts[2])
    elif len(parts) == 2:
        y, m, d = _dt.date.today().year, int(parts[0]), int(parts[1])
    else:
        y, m, d = int(end_str[:4]), int(end_str[4:6]), int(end_str[6:])
    beg = _dt.date(y, m, d) - _dt.timedelta(days=10)
    return beg.strftime("%Y%m%d")


def _cache_date(end_ymd):
    """YYYYMMDD -> 缓存 'YYYY-MM-DD' 格式。"""
    y, m, d = end_ymd[:4], int(end_ymd[4:6]), int(end_ymd[6:])
    return f"{y}-{m:02d}-{d:02d}"


def _strip_unclosed_today(snaps):
    """兜底过滤：去掉未收盘的当日K（盘中动态K禁止落盘）。

    15:00 之后东财/腾讯日K接口返回的当日行即为收盘定格值，才允许保留。
    与 _probe_end 的"盘中从昨天探测"双保险，防其它调用路径再次泄漏。
    """
    import datetime as _dt
    if not snaps:
        return snaps
    now = _dt.datetime.now()
    if snaps[-1]["date"] == now.date().isoformat() and now.time() < _dt.time(15, 0):
        return snaps[:-1]
    return snaps


def _fetch_one(secid, beg, end):
    """拉取单只：多源分组调度（东财多host/腾讯ifzq·sqt，健康序迁移）。
    返回 (secid, name, snaps, src) 或 (secid, None, [], 'err')。"""
    try:
        sid, name, snaps, src = fetch_one_qfq(secid, beg, end)
        return sid, name, _strip_unclosed_today(snaps), src
    except Exception:
        return secid, None, [], "err"


def _maybe_restrict_codes(cache, codes, beg, end, workers):
    """慢路径 ETA 观测（**已废弃缩池，始终返回全池**）。

    2026-09-10 决策（用户拍板「彻底放弃」）：原方案「ETA>5min → 用 T-1 单调判死
    预筛只拉幸存池」经全史回放验证被否决 —— 4514 交易日实测
    漏杀 20689/50126 = **41.3%**、2560/4514 日(57%)存在漏杀。根因：预筛判死项里
    含大量「低位埋伏/深跌」通道本来要买的票（TOP 原因 `MA20<MA250*0.95 且收于年线下`
    ×1615，四周期均匀中招），即预筛与低吸通道天然冲突，不是调参能救的。
    故此处降级为纯观测：算 ETA、超阈值告警留痕，但**永不缩池**。
    慢的根因改由「当日根批量快路径(224只≈20s) + 历史缺口留到非盘段」解决。
    返回 (codes, restricted_bool) —— restricted 恒为 False。
    """
    import time as _t
    probe = [c for c in codes[:3]]
    t0 = _t.time()
    for c in probe:
        fetch_one_qfq(c, beg, end, max_attempts=2)
    probe_cost = (_t.time() - t0) / max(1, len(probe))
    eta = probe_cost * len(codes) / max(1, workers)
    if eta > 300:
        print("[ETA观测] 全池估算 %.0fs > 300s —— 仍拉全池"
              "（缩池方案已废弃：全史实测漏杀 41.3%%）" % eta)
    return codes, False


def _write_cache(cache):
    """原子落盘 _kline_cache.json（tmp + replace）。"""
    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(tmp, CACHE_FILE)


def _stale_codes(cache, end_iso):
    """逐只找出「最新一根 < 目标交易日」的票 → [(secid, 末位日期), ...]。

    缓存**整体**最新 ≠ **每只**都最新：多源迁移/网络抖动会让个别票停在旧日期，而旧
    逻辑只看全池最大日期（`ds[-1]`）就直接 return「缓存已是最新」→ 缺口永远不自愈。
    2026-09-12 实测：整体已是 9/11，但 sh600011 停 9/8、sh600105/sh600111 停 9/10，
    每天跑增量都报「无需更新」，危险信号被静默吞掉。
    """
    out = []
    for secid, ent in cache.items():
        snaps = ent.get("snaps") or []
        last = snaps[-1].get("date") if snaps else ""
        if (not last) or last < end_iso:
            out.append((secid, last or "-"))
    return out


def _backfill_stale(cache, stale_codes, end_ymd, workers, conn):
    """只对落后个股并发补拉日K（BEG=目标日前 10 自然日）。返回补成功的 secid 列表。"""
    t0 = time.time()
    BEG = _beg_for(end_ymd)
    fixed = []
    with ThreadPoolExecutor(max_workers=max(1, min(workers, len(stale_codes)))) as pool:
        futs = {pool.submit(_fetch_one, c, BEG, end_ymd): c for c in stale_codes}
        for fut in as_completed(futs):
            secid = futs[fut]
            try:
                secid, name, snaps, src = fut.result()
            except Exception:
                secid, name, snaps, src = secid, None, [], "err"
            if not snaps:
                continue
            old = {s["date"]: s for s in (cache[secid].get("snaps") or [])}
            old.update({s["date"]: s for s in snaps})
            cache[secid]["snaps"] = sorted(old.values(), key=lambda x: x["date"])
            if name:
                cache[secid]["name"] = name
            cache[secid]["src"] = src
            if conn:
                with _LOCK:
                    _market_db.upsert_kline(conn, secid, snaps, src=src,
                                            name=name or cache[secid].get("name"))
            fixed.append(secid)
    return fixed, time.time() - t0


# ───────────────────────── 批量实时快路径 ─────────────────────────
def batch_quote_day(secids, end_ymd):
    """腾讯批量实时接口拉某「已收盘交易日」定格根（30只/请求）。

    返回 {secid: snap}；snap: {date:'YYYY-MM-DD', open,close,high,low,volume(手),
    changePct, turnover, src:'tqb'}。时间戳校验 f30[:8]==end_ymd 才收（防盘中动态价）。
    指数（sh000/sz399）换手率接口返回 0，保持与缓存一致。
    """
    import datetime as _dt
    end_iso = _cache_date(end_ymd)
    out = {}
    for i in range(0, len(secids), _BATCH_SIZE):
        part = secids[i:i + _BATCH_SIZE]
        url = "https://qt.gtimg.cn/q=" + ",".join(part)
        got = {}
        for attempt in range(3):
            try:
                r = _SESSION.get(url, timeout=20, headers=HEADERS, proxies=PROXIES)
                r.encoding = "gbk"
                for m in re.finditer(r'v_(\w+)="([^"]*)"', r.text):
                    sid = m.group(1)
                    f = m.group(2).split("~")
                    if len(f) < 46 or sid not in part:
                        continue
                    try:
                        if f[30][:8] != end_ymd:  # 时间戳非该收盘日 → 丢弃
                            continue
                        snap = {
                            "date": end_iso,
                            "open": float(f[5]), "close": float(f[3]),
                            "high": float(f[33]), "low": float(f[34]),
                            "volume": float(f[6]),          # 手（与东财/腾讯日K同单位）
                            "changePct": float(f[32]),
                            "turnover": float(f[38]) if not sid.startswith(("sh000", "sz399")) else 0.0,
                            "prev_close": float(f[4]),
                        }
                        got[sid] = snap
                    except (ValueError, IndexError):
                        pass
                if got:
                    break
            except Exception:
                time.sleep(1)
        out.update(got)
    return out


def _batch_missing_fallbacks(batch_snaps, cache, end_ymd):
    """批量缺失 + XD/DR 除权日票 → 日K接口兜底补 END 单日。返回补回 dict。"""
    import datetime as _dt
    end_iso = _cache_date(end_ymd)
    miss = []
    for secid in cache:
        prev_close = None
        snaps = cache[secid].get("snaps") or []
        for s in reversed(snaps):
            if s["date"] < end_iso:
                prev_close = s["close"]
                break
        bs = batch_snaps.get(secid)
        if bs is None:
            miss.append(secid)
        elif prev_close and abs(bs["prev_close"] - prev_close) / prev_close > 0.005:
            # 除权除息（昨收被下调）或复权口径跳变 → 用日K接口拿真实复权根
            miss.append(secid)
    if not miss:
        return {}
    fixed = {}
    with ThreadPoolExecutor(max_workers=min(10, len(miss))) as pool:
        futs = {pool.submit(_fetch_one, c, end_ymd, end_ymd): c for c in miss}
        for fut in as_completed(futs):
            secid = futs[fut]
            try:
                _, _, snaps, src = fut.result()
            except Exception:
                snaps, src = [], "err"
            if snaps and snaps[-1]["date"] == end_iso:
                s = dict(snaps[-1])
                s["src"] = src
                fixed[secid] = s
    return fixed


def main():
    import argparse
    ap = argparse.ArgumentParser(description="并发增量更新日线缓存")
    ap.add_argument("--workers", type=int, default=30, help="并发数（日K路径，默认 30）")
    ap.add_argument("--no-db", action="store_true", help="不写公共数据库（仅缓存 JSON）")
    ap.add_argument("--no-batch", action="store_true",
                    help="关闭批量实时快路径，仅用逐只日K接口（缺口>1日/历史回填时自动日K）")
    ap.add_argument("--force", action="store_true",
                    help="强制日K重拉最新已收盘交易日区间并覆盖——即使缓存日期已最新。"
                         "用于修正盘中泄漏写死的当日K(收盘后跑一次即可)。")
    args = ap.parse_args()

    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    conn = _market_db.get_conn() if not args.no_db else None
    codes = list(cache.keys())
    ds = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    end0 = _probe_end(_beg_for(ds[-1]))
    end_iso = _cache_date(end0)
    if not args.force and end_iso <= ds[-1]:
        # 全池最大日期已最新，但必须逐只校验：个别票因网络/多源迁移停在旧日期的「缺口」
        # 不能因为整体 OK 就放过（旧逻辑直接 return，缺口永不自愈）。
        stale = _stale_codes(cache, ds[-1])
        if not stale:
            print(f"缓存已是最新({ds[-1]}), 无需更新 (若怀疑某日被盘中快照污染: python _update_cache_inc.py --force)")
            if conn:
                _market_db.set_meta(conn, "latest_date", ds[-1])
                conn.close()
            return
        print(f"⚠ 全池最新 {ds[-1]}，但有 {len(stale)} 只落后 → 逐只补拉（目标 {ds[-1]}）：")
        for secid, last in stale[:30]:
            print(f"    {secid} 末={last}")
        fixed, cost = _backfill_stale(cache, [c for c, _ in stale], end0, args.workers, conn)
        _write_cache(cache)
        nd = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
        if conn:
            _market_db.set_meta(conn, "latest_date", nd[-1])
            conn.close()
        left = _stale_codes(cache, ds[-1])
        print(f"补拉完成 {len(fixed)}/{len(stale)} 只，耗时 {cost:.0f}s")
        if left:
            print(f"⚠ 仍落后 {len(left)} 只（源无该日数据/长期停牌，需人工确认）：{left[:10]}")
        print(f"新日期范围: {nd[0]} ~ {nd[-1]} 共 {len(nd)} 交易日")
        return

    # 判断是否「仅缺当日收盘根」→ 走批量实时快路径（224只 ≈ 8请求 ~20s）
    cal = sorted({s["date"] for s in (cache.get("sh000001", {}).get("snaps") or [])})
    prev_day = None
    if end_iso in cal:
        i = cal.index(end_iso)
        prev_day = cal[i - 1] if i > 0 else None
    only_latest_missing = (not args.force and not args.no_batch and prev_day is not None
                           and ds[-1] == prev_day)
    BEG = _beg_for(end0)
    END = end0
    print(f"当前缓存末端: {ds[-1]} 共 {len(ds)} 交易日, 目标 {END} ({len(codes)} 只)")
    t0 = time.time()
    fail = []

    if only_latest_missing:
        # ── 快路径：批量实时补当日根 ──
        print(f"[批量实时快路径] 仅缺当日根 {END}, {len(codes)}只 / {_BATCH_SIZE}只每批 "
              f"≈{ (len(codes)+_BATCH_SIZE-1)//_BATCH_SIZE} 请求 ...")
        batch = batch_quote_day(codes, END)
        fixed = _batch_missing_fallbacks(batch, cache, END)  # XD/缺失票日K兜底
        batch.update(fixed)
        # 收盘后仍缺的票 → 未复权实时(qtg/sina)再兜底当日根（已收盘才放行）
        missing = [c for c in codes if c not in batch]
        if missing:
            fill = realtime_fallback(missing, END)
            if fill:
                batch.update(fill)
                print(f"  [实时兜底] 补回 {len(fill)}/{len(missing)} 只")
        done = 0
        for secid in codes:
            s = batch.get(secid)
            if not s:
                fail.append(secid)
                continue
            old = {x["date"]: x for x in cache[secid].get("snaps", [])}
            snap = {k: v for k, v in s.items() if k != "prev_close"}  # prev_close 仅用于校验
            old[snap["date"]] = snap
            cache[secid]["snaps"] = sorted(old.values(), key=lambda x: x["date"])
            cache[secid]["src"] = s.get("src", "tqb")
            if conn:
                with _LOCK:
                    _market_db.upsert_kline(conn, secid, [snap], src=s.get("src", "tqb"),
                                            name=cache[secid].get("name"))
            done += 1
        dt = time.time() - t0
        print(f"  [批量] 成功 {done}/{len(codes)} 耗时 {dt:.0f}s")
        # 批量实时只能补「当日一根」：若某票还缺更早的日（多日缺口），再逐只日K补齐
        left = _stale_codes(cache, end_iso)
        if left:
            print(f"  [补漏] 仍有 {len(left)} 只落后于 {end_iso} → 逐只日K补拉")
            fixed, cost = _backfill_stale(cache, [c for c, _ in left], END,
                                          args.workers, conn)
            print(f"  [补漏] 补回 {len(fixed)}/{len(left)} 只，耗时 {cost:.0f}s")
    else:
        # ── 慢路径：并发日K（历史缺口 / --force 修复 / --no-batch） ──
        codes_run = list(codes)
        if not args.force:
            codes_run, _restricted = _maybe_restrict_codes(cache, codes, BEG, END,
                                                           args.workers)
        print(f"{'强制刷新' if args.force else '日K并发更新'} {len(codes_run)} 只至 {END} "
              f"(workers={args.workers}, 多源分组, Session复用)")
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = {pool.submit(_fetch_one, c, BEG, END): c for c in codes_run}
            n_ok = 0
            for fut in as_completed(futures):
                secid = futures[fut]
                try:
                    secid, name, snaps, src = fut.result()
                except Exception:
                    secid, name, snaps, src = secid, None, [], "err"
                if not snaps:
                    fail.append(secid)
                    with _LOCK:
                        print(f"  {secid}: 拉取失败")
                    continue
                old = {s["date"]: s for s in cache[secid].get("snaps", [])}
                old.update({s["date"]: s for s in snaps})
                cache[secid]["snaps"] = sorted(old.values(), key=lambda x: x["date"])
                if name:
                    cache[secid]["name"] = name
                cache[secid]["src"] = src
                if conn:
                    with _LOCK:
                        _market_db.upsert_kline(conn, secid, snaps, src=src,
                                                name=name or cache[secid].get("name"))
                n_ok += 1
                if n_ok % 40 == 0 or n_ok == len(codes_run):
                    with _LOCK:
                        print(f"  [{n_ok}/{len(codes_run)}] 耗时 {time.time()-t0:.0f}s")
        dt = time.time() - t0
        print(f"  [日K] 完成 {n_ok}/{len(codes_run)} 耗时 {dt:.0f}s")

    _write_cache(cache)
    nd = sorted({s["date"] for ent in cache.values() for s in ent.get("snaps", [])})
    if conn:
        _market_db.set_meta(conn, "latest_date", nd[-1])
        conn.close()
    print(f"\n完成。失败 {len(fail)} 只: {fail[:10]}")
    print(f"新日期范围: {nd[0]} ~ {nd[-1]} 共 {len(nd)} 交易日, 总耗时 {time.time()-t0:.0f}s")


if __name__ == "__main__":
    main()
