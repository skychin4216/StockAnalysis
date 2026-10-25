# -*- coding: utf-8 -*-
"""缠论荐股扫描（核心池）
=========================
扫描 core_stock_pool.json（172 只），对每只跑 compute_structure，
筛最近 N 根 K 线内的缠论买点（一/二/三买），打分排序写 pool_signals.json。

打分规则：
  一买(T1) +30 | 二买(T2/T2S) +25 | 三买(T3A/T3B) +20
  is_sure=True +20
  信号距最新 K 线：≤1根 +15 | ≤3根 +10 | ≤5根 +5
用法：
  python smalltools/_chanlun_scan.py            # 核心池 1H
  python smalltools/_chanlun_scan.py --lv day   # 日线
  python smalltools/_chanlun_scan.py --all      # 全 kline_store 池（暂无数据时退核心池）
"""
import argparse
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from _chanlun_engine import compute_structure, load_klines  # noqa: E402

POOL_JSON = os.path.join(ROOT, "app", "src", "main", "assets", "core_stock_pool.json")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "data", "chanlun")
OUT_JSON = os.path.join(OUT_DIR, "pool_signals.json")
RECENT_KLU = 5          # 最近 N 根 K 线内出现的买点才算
WORKERS = 8


def _strip_prefix(code: str) -> str:
    return code[2:] if code[:2] in ("sh", "sz") else code


def load_pool(all_pool: bool = False) -> list:
    """核心池；--all 时若有 kline_store 则合并。"""
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    pool = [_strip_prefix(s) for s in p["stocks"]]
    if all:
        ks_path = os.path.join(ROOT, "data", "kline_store.json")
        if os.path.isfile(ks_path):
            ks = json.load(open(ks_path, encoding="utf-8"))
            pool = sorted(set(pool) | {_strip_prefix(k) for k in ks.keys()})
    return pool


def _score(bsp: dict, klu_total: int) -> int:
    """单信号打分。"""
    t = bsp["type"][0] if bsp["type"] else ""
    base = {"1": 30, "1p": 28, "2": 25, "2s": 25, "3a": 20, "3b": 20}.get(t, 15)
    sure_bonus = 20 if bsp["is_sure"] else 0
    dist = klu_total - 1 - bsp["klu_idx"]          # 距最新 K 线几根
    rec = 15 if dist <= 1 else 10 if dist <= 3 else 5 if dist <= 5 else 0
    return base + sure_bonus + rec


def scan_one(code: str, lv: str) -> dict | None:
    """扫描单只，返回信号行或 None。"""
    try:
        r = compute_structure(code, lv)
    except Exception:
        return None
    n = r["kline_cnt"]
    buys = [b for b in r["bsp"] if b["is_buy"] and n - 1 - b["klu_idx"] <= RECENT_KLU]
    if not buys:
        return None
    best = max(buys, key=lambda b: _score(b, n))
    # 背驰度：chan.py 内部 divergence_rate 判断，此处用中枢数辅助
    zs_cnt = len(r["zs"])
    return {
        "code": code,
        "bsp_type": best["type"][0] if best["type"] else "?",
        "is_sure": best["is_sure"],
        "price": best["price"],
        "klu_idx": best["klu_idx"],
        "dist": n - 1 - best["klu_idx"],
        "zs_cnt": zs_cnt,
        "score": _score(best, n),
        "seg_dir": r["seg"][-1]["dir"] if r["seg"] else "?",
        "bi_cnt": len(r["bi"]),
    }


def run(lv: str = "1h", all_pool: bool = False) -> dict:
    pool = load_pool(all_pool)
    signals = []
    done = 0
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = {ex.submit(scan_one, c, lv): c for c in pool}
        for f in as_completed(futs):
            done += 1
            r = f.result()
            if r:
                signals.append(r)
            if done % 20 == 0:
                print(f"  进度 {done}/{len(pool)} 信号 {len(signals)}", flush=True)
    signals.sort(key=lambda x: -x["score"])
    out = {
        "scan_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "lv": lv, "pool_size": len(pool), "signal_cnt": len(signals),
        "signals": signals,
    }
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--lv", default="1h", choices=["1h", "day"])
    ap.add_argument("--all", action="store_true")
    args = ap.parse_args()
    res = run(args.lv, args.all)
    print(f"\n扫描完成：池 {res['pool_size']} 只 → 信号 {res['signal_cnt']} 只")
    print(f"已写: {OUT_JSON}")
    for s in res["signals"][:10]:
        sure = "✔" if s["is_sure"] else "✗"
        print(f"  {s['code']} {s['bsp_type']}{sure} score={s['score']} "
              f"price={s['price']} 中枢{s['zs_cnt']} dist={s['dist']}")
