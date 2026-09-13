# -*- coding: utf-8 -*-
"""核心池维护：审计 + 剔除 ST/退市风险 + 补齐热点龙头。

背景：核心池(_kline_cache.json)由多个脚本手工扩展，存在三类问题：
  1. ST/*ST/退市风险股混入（如 *ST闻泰、ST惠程），选股会命中这些垃圾；
  2. 热点板块龙头缺失（如 2026 AI 液冷温控），导致永远选不到；
  3. 池子长期不更新，数据覆盖滞后。

本脚本一次性解决：
  - audit：列出 ST/退名单、缺失的热点龙头、各股数据新鲜度；
  - purge：剔除 ST/退市股（默认 dry-run，--commit 生效，同步 market_data.db）；
  - fill ：从 AutoQuant 龙头池补齐缺失标的（网络拉取四年 K 线）。

用法：
  python _pool_maintain.py                 # 只审计，输出报告
  python _pool_maintain.py --purge --commit   # 审计 + 实际剔除 ST/退
  python _pool_maintain.py --fill             # 补齐热点龙头（网络）
  python _pool_maintain.py --all              # 审计 + 剔除 + 补齐
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _add_leaders import fetch_full  # noqa: E402
import _market_db  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_FILE = os.path.join(HERE, "_kline_cache.json")
REPORT_FILE = os.path.join(HERE, "_pool_report.json")


def is_st(name: str) -> bool:
    """ST/*ST/退市 判定：名称含 ST 或 退。"""
    up = (name or "").upper()
    return "ST" in up or "退" in up


def load_cache():
    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def save_cache(cache):
    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(tmp, CACHE_FILE)


def load_leaders_secids():
    """AutoQuant 龙头池 secid 集合（找不到时返回空集）。"""
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for base in (os.path.join(root, "AutoQuant"), root):
        mod = os.path.join(base, "autoquant", "hot_sector_config.py")
        if os.path.exists(mod):
            sys.path.insert(0, base)
            from autoquant.hot_sector_config import get_all_leaders  # noqa: E402
            leaders = get_all_leaders()
            secids = set()
            for k in leaders.keys():
                code, sep, mkt = k.rpartition(".")
                secids.add((mkt.lower() + code) if sep else k.lower())
            return secids
    return set()


def audit():
    cache = load_cache()
    st_list = [(s, v.get("name", "")) for s, v in cache.items() if is_st(v.get("name", ""))]
    leaders = load_leaders_secids()
    missing = sorted(leaders - set(cache.keys())) if leaders else []
    # 数据新鲜度：最近收盘日期分布
    latest_dates = {}
    for s, v in cache.items():
        snaps = v.get("snaps") or []
        if snaps:
            latest_dates[s] = snaps[-1]["date"]
    stale = [s for s, d in latest_dates.items() if d < "2026-01-01"]
    report = {
        "total": len(cache),
        "st_count": len(st_list),
        "st_list": [{"secid": s, "name": n} for s, n in st_list],
        "leader_missing_count": len(missing),
        "leader_missing": missing,
        "stale_count": len(stale),
        "stale_sample": stale[:20],
        "latest_date": max(latest_dates.values(), default=""),
    }
    with open(REPORT_FILE, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("========== 核心池审计 ==========")
    print(f"池子总数: {report['total']} | 最新收盘: {report['latest_date']}")
    print(f"ST/退市股 {report['st_count']} 只: {st_list}")
    print(f"热点龙头缺失 {report['leader_missing_count']} 只: {missing}")
    print(f"数据滞后(2026年前) {report['stale_count']} 只, 示例: {stale[:20]}")
    print(f"报告已写: {REPORT_FILE}")
    return report


def purge(commit: bool):
    cache = load_cache()
    st_list = [(s, v.get("name", "")) for s, v in cache.items() if is_st(v.get("name", ""))]
    if not st_list:
        print("无 ST/退市股，无需剔除。")
        return 0
    print(f"将剔除 {len(st_list)} 只 ST/退市股:")
    for s, n in st_list:
        print(f"  - {s} {n}")
    if not commit:
        print("[dry-run] 加 --commit 实际执行剔除。")
        return 0
    for s, n in st_list:
        cache.pop(s, None)
        try:
            conn = _market_db.get_conn()
            cur = conn.execute("DELETE FROM kline WHERE secid=?", (s,))
            conn.commit()
            conn.close()
            print(f"  已剔除 {s} {n} (market_data.db 同步删除 {cur.rowcount} 行)")
        except Exception as e:
            print(f"  {s}: market_data.db 删除失败 {e}")
    save_cache(cache)
    print(f"完成，池子 {len(cache) + len(st_list)} → {len(cache)}")
    return len(st_list)


def fill():
    leaders = load_leaders_secids()
    if not leaders:
        print("未找到 AutoQuant 龙头池配置，无法补齐。")
        return 0
    cache = load_cache()
    missing = sorted(leaders - set(cache.keys()))
    print(f"龙头池 {len(leaders)} 只 | 已在库 {len(leaders) - len(missing)} | 需补齐 {len(missing)}")
    if not missing:
        return 0
    conn = _market_db.get_conn()
    fail = []
    for i, secid in enumerate(missing):
        try:
            name, snaps, src = fetch_full(secid)
        except Exception:
            name, snaps, src = None, [], "err"
        if not snaps:
            fail.append(secid)
            print(f"  [{i+1}/{len(missing)}] {secid}: 拉取失败")
            continue
        cache[secid] = {"name": name or secid, "snaps": snaps, "src": src}
        _market_db.upsert_kline(conn, secid, snaps, src=src, name=name or secid)
        print(f"  [{i+1}/{len(missing)}] {secid} {cache[secid]['name']} 拉取 {len(snaps)} 根"
              f" ({snaps[0]['date']} ~ {snaps[-1]['date']})")
        time.sleep(0.25)
    save_cache(cache)
    print(f"\n完成。失败 {len(fail)} 只: {fail[:10]}")
    print(f"龙头池覆盖: {sum(1 for s in leaders if s in cache)}/{len(leaders)} | 库内总数: {len(cache)}")
    conn.close()
    return len(missing) - len(fail)


def main():
    ap = argparse.ArgumentParser(description="核心池维护（审计/剔除ST/补齐龙头）")
    ap.add_argument("--purge", action="store_true", help="剔除 ST/退市股")
    ap.add_argument("--commit", action="store_true", help="purge 实际生效（默认 dry-run）")
    ap.add_argument("--fill", action="store_true", help="补齐缺失的热点龙头（网络）")
    ap.add_argument("--all", action="store_true", help="审计 + 剔除(commit) + 补齐")
    args = ap.parse_args()

    if args.all:
        audit()
        purge(commit=True)
        fill()
    elif args.purge:
        audit()
        purge(commit=args.commit)
    elif args.fill:
        audit()
        fill()
    else:
        audit()


if __name__ == "__main__":
    main()
