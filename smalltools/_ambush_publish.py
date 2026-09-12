# -*- coding: utf-8 -*-
"""
板块埋伏 —— XML 单一事实源发布（仿 ETF 低吸，PC/exe 端）
========================================================================
架构（2026-09-11 双端对齐）：
  - 规则/参数 = app/src/main/assets/usecases/sector_ambush_pipeline.xml（唯一事实源）
  - 执行器   = app/src/main/assets/usecases/usecase_pipeline.py（XML DAG Python
    引擎，XML DAG = 主线；与 APK UseCaseLoader 解析同一份 XML）
  - 数据     = smalltools/_kline_cache.json（全池日线快照）
  - 发布物   = data/_ambush_live_picks.json（PC/exe 展示，与 APK SectorAmbushFragment
    输出字段同构）

APK 端：工作台→埋伏 Tab 本地执行 sector_ambush usecase（本地快照 + sector_daily_record）。
PC/exe 端：运行本脚本生成 _ambush_live_picks.json，供 AutoQuant/exe 展示或回测。

用法：
  python _ambush_publish.py              # 读本地 _kline_cache.json 发布埋伏名单
  python _ambush_publish.py --fetch      # 先增量刷新 _kline_cache.json，再发布
"""
import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
USECASES = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "assets", "usecases"))
for _p in (HERE, USECASES):
    if _p not in sys.path:
        sys.path.insert(0, _p)

CACHE = os.path.join(HERE, "_kline_cache.json")
OUT = os.path.join(HERE, "data", "_ambush_live_picks.json")
PREP_CACHE = os.path.join(HERE, "_update_cache_inc.py")


def load_cache():
    if not os.path.exists(CACHE):
        print("! 本地无 %s —— 请先运行 python _update_cache_inc.py 拉取行情" % CACHE)
        return {}
    with open(CACHE, "r", encoding="utf-8") as f:
        return json.load(f)


def ensure_fresh_cache():
    """增量刷新全池日线缓存（复用 _update_cache_inc.py）。"""
    print("[fetch] 增量刷新全池日线缓存 …")
    rc = subprocess.run([sys.executable, PREP_CACHE], cwd=HERE)
    if rc.returncode != 0:
        raise RuntimeError("_update_cache_inc.py 执行失败，rc=%d" % rc.returncode)
    return load_cache()


def run_live(cache):
    """执行 sector_ambush usecase（读 APK assets/usecases/sector_ambush_usecase.xml）。"""
    import usecase_pipeline as up
    runner = up.UseCaseRunner("sector_ambush", cache=cache)
    res = runner.run(with_stages=True)
    stages = res.get("stages") or {}
    exit_payload = stages.get("n_ambush_exit")
    if exit_payload is None:
        notes = res.get("notes") or []
        raise RuntimeError("sector_ambush 未产出 n_ambush_exit：%s" % (" | ".join(notes) or "未知错误"))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(exit_payload, f, ensure_ascii=False, indent=2)
    hot = exit_payload.get("hotSectors") or []
    today = [r.get("name") or r.get("code") or "?" for r in exit_payload.get("signal_today", [])]
    watch = [r.get("name") or r.get("code") or "?" for r in exit_payload.get("approach", [])]
    print("[live] %s 已发布" % OUT)
    print("[hot] %s" % (", ".join(hot) if hot else "无"))
    print("[signal_today] %s" % (", ".join(today) if today else "无（空仓=正确）"))
    print("[watch] %s" % (", ".join(watch) if watch else "无"))
    return exit_payload


def main():
    ap = argparse.ArgumentParser(description="板块埋伏 usecase 发布（XML 单一源）")
    ap.add_argument("--fetch", action="store_true", help="先增量刷新 _kline_cache.json 再发布")
    args = ap.parse_args()

    cache = ensure_fresh_cache() if args.fetch else load_cache()
    if not cache:
        print("请先运行: python _update_cache_inc.py（首次拉全量行情）")
        return 1

    run_live(cache)
    return 0


if __name__ == "__main__":
    sys.exit(main())
