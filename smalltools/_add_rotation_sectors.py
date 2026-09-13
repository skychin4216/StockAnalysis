# -*- coding: utf-8 -*-
"""补齐「轮动板块成分股」到 _kline_cache.json + 公共数据库。

背景：`_rotation_engine.ROTATION_SECTORS` 是显式轮动板块成分表（油运/航运/石油/
军工/银行等）。这些标的多数不在核心池 `_kline_cache.json` 里，导致引擎因
「板块样本 < MIN_STOCKS」而丢弃该板块，地缘断航/油价破百/全球加息等事件加分
无处落地（选不出对应板块个股）。

本脚本：
  1. 读取 `_rotation_engine.ROTATION_SECTORS` 的全部成分 secid；
  2. 缺哪个补哪个（东财优先，失败退腾讯分段拼接，与前序缓存脚本同源）；
  3. 同步 upsert 到市场公共库，保持两端数据一致。

用法：
  python _add_rotation_sectors.py            # 补齐所有轮动板块成分
  python _add_rotation_sectors.py --dry-run  # 只看缺哪些，不拉取
"""
import argparse
import datetime as _dt
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _add_leaders import fetch_full  # noqa: E402
from _rotation_engine import ROTATION_SECTORS  # noqa: E402
import _market_db  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_FILE = os.path.join(HERE, "_kline_cache.json")


def all_secids():
    out = []
    for sector, codes in ROTATION_SECTORS.items():
        for c in codes:
            if c not in out:
                out.append(c)
    return out


def main():
    ap = argparse.ArgumentParser(description="补齐轮动板块成分股 K 线")
    ap.add_argument("--dry-run", action="store_true", help="只列出缺失标的")
    args = ap.parse_args()

    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    secids = all_secids()
    missing = [s for s in secids if s not in cache]
    print(f"轮动板块成分 {len(secids)} 只 | 已在库 {len(secids) - len(missing)} | 需补齐 {len(missing)}")
    for sector, codes in ROTATION_SECTORS.items():
        lack = [c for c in codes if c not in cache]
        if lack:
            print(f"  {sector:<8} 缺 {len(lack)}/{len(codes)}: {' '.join(lack)}")
    if args.dry_run or not missing:
        print("（dry-run，未拉取）" if args.dry_run else "无需补齐。")
        return 0

    # END 取到最近交易日，保证引擎按 asof 截取时不会因数据过期而被跳过
    end = _dt.date.today().strftime("%Y%m%d")
    conn = _market_db.get_conn()
    fail = []
    for i, secid in enumerate(missing):
        try:
            name, snaps, src = fetch_full(secid, end=end)
        except Exception as exc:  # noqa: BLE001
            name, snaps, src = None, [], "err:%s" % exc
        if not snaps:
            fail.append(secid)
            print(f"  [{i + 1}/{len(missing)}] {secid}: 拉取失败")
            continue
        cache[secid] = {"name": name or secid, "snaps": snaps, "src": src}
        try:
            _market_db.upsert_kline(conn, secid, snaps, src=src, name=name or secid)
        except Exception:  # noqa: BLE001
            pass
        print(f"  [{i + 1}/{len(missing)}] {secid} {cache[secid]['name']} "
              f"{len(snaps)} 根 ({snaps[0]['date']} ~ {snaps[-1]['date']})")

    if len(missing) > len(fail):
        with open(CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(cache, f, ensure_ascii=False)
        print(f"[cache] 已写回 {CACHE_FILE}")
    if fail:
        print(f"[warn] 仍有 {len(fail)} 只拉取失败: {' '.join(fail)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
