# -*- coding: utf-8 -*-
"""
ETF 低位低吸 —— XML 单一事实源发布 + 手机行情推送
========================================================================
架构（2026-09-07 与三周期同构）：
  - 规则/参数 = app/src/main/assets/usecases/etf_dip_pipeline.xml（唯一事实源）
  - 执行器   = AutoQuant/usecase_pipeline.py（Python 引擎）== APK UseCaseLoader
    （usecase etf_dip：门控 n_etf_gate → 信号 n_etf_dip_signal → 离场 n_etf_exit）
  - 数据     = smalltools/_etf_cache.json（13只ETF + sh000300，腾讯 qfq 前复权日K）
  - 发布物   = data/_etf_live_picks.json（PC/exe 展示 + APK 桥回退，与旧版同构）
  - 推送     = 把 _etf_cache.json 推送到手机（adb），APK ETF 页本地执行同一 usecase

用法：
  python _etf_publish.py              # 只发布名单（读 XML + 本地缓存）
  python _etf_publish.py --push       # 发布 + 推送行情到手机
  python _etf_publish.py --all        # 刷新缓存行情(腾讯) + 发布 + 推送
  python _etf_publish.py --fetch      # 只刷新缓存行情
  python _etf_publish.py --push --serial 设备序列号
"""
import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
AUTOQUANT = os.path.join(ROOT, "AutoQuant")
for p in (HERE, AUTOQUANT):
    if p not in sys.path:
        sys.path.insert(0, p)

CACHE = os.path.join(HERE, "_etf_cache.json")
OUT = os.path.join(HERE, "data", "_etf_live_picks.json")
PKG = "com.chin.stockanalysis"


def load_cache():
    if not os.path.exists(CACHE):
        print("! 本地无 _etf_cache.json —— 请先运行 python _etf_publish.py --fetch 拉取行情")
        return {}
    with open(CACHE, "r", encoding="utf-8") as f:
        return json.load(f)


def run_live(cache):
    """执行 etf_dip usecase（读 APK assets/usecases/etf_dip_usecase.xml），产出发布 JSON。"""
    import usecase_pipeline as up
    runner = up.UseCaseRunner("etf_dip", cache=cache)
    res = runner.run(with_stages=True)
    stages = res.get("stages") or {}
    exit_payload = stages.get("n_etf_exit")
    if exit_payload is None:
        notes = res.get("notes") or []
        raise RuntimeError("etf_dip 未产出 n_etf_exit：%s" % (" | ".join(notes) or "未知错误"))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(exit_payload, f, ensure_ascii=False, indent=2)
    gate = exit_payload.get("gate") or {}
    today = [r["name"] for r in exit_payload.get("signal_today", [])]
    watch = [r["name"] for r in exit_payload.get("approach", [])]
    print("[live] %s 已发布（%d 标的）" % (OUT, len(cache)))
    print("[gate] %s" % gate.get("note", "?") if gate else "[gate] (无)")
    print("[signal_today] %s" % (", ".join(today) if today else "无（空仓=正确）"))
    print("[watch] %s" % (", ".join(watch) if watch else "无"))
    return exit_payload


def _adb(args, check=True):
    return subprocess.run(["adb"] + args, capture_output=True, text=True, check=check)


def _online_devices():
    r = _adb(["devices"], check=False)
    return [ln.split("\t")[0] for ln in r.stdout.splitlines()[1:]
            if ln.strip() and "\tdevice" in ln]


def push_cache_to_phone(serial=None):
    """把 etf_cache.json 推到手机 App 外部 files 目录（首选），失败回退 run-as 到内部 files。"""
    devices = _online_devices()
    if not devices:
        print("! 无在线 adb 设备 —— 跳过推送（手机联网时可在 ETF 页等待 PC 桥，或稍后重跑 --push）")
        return False
    target = serial or devices[0]
    print("[push] 目标设备: %s" % target)
    cache_bytes = open(CACHE, "rb").read()
    # 1) 外部存储 files 目录（App getExternalFilesDir(null) 直接可读，最优先）
    ext_dir = "/sdcard/Android/data/%s/files" % PKG
    _adb(["-s", target, "shell", "mkdir", "-p", ext_dir], check=False)
    r1 = _adb(["-s", target, "push", CACHE, ext_dir + "/etf_cache.json"], check=False)
    if r1.returncode == 0:
        print("[push] ✓ 已推送到外部 files：%s/etf_cache.json（%d 字节）" % (ext_dir, len(cache_bytes)))
    else:
        # 2) 内部 files 目录（需 App 为 debuggable，run-as 直写 /data/data/<pkg>/files）
        p = subprocess.run(
            ["adb", "-s", target, "shell", "run-as", PKG,
             "sh", "-c", "mkdir -p files && cat > files/etf_cache.json"],
            input=cache_bytes, capture_output=True, check=False)
        if p.returncode == 0:
            print("[push] ✓ run-as 已写入 App 内部 files/etf_cache.json（%d 字节）" % len(cache_bytes))
        else:
            print("! 推送失败：sdcard 受限且 App 非 debuggable。手动方式：\n"
                  "  将 %s 复制到手机后交给 App（或连接 exe data_service 用桥拉取）" % CACHE)
            return False
    print("[push] 提示：手机打开 工作台→ETF 点“刷新”即可本地执行 etf_dip usecase（无需 PC）")
    return True


def ensure_fresh_cache():
    """拉取/续拉 ETF 行情（13只+沪深300，腾讯 qfq）。"""
    import _etf_buy
    print("[fetch] 断点续拉行情（可能较慢）…")
    cache = _etf_buy.ensure_data(_etf_buy.ETF_POOL + [_etf_buy.IDX_300])
    print("[fetch] 完成：%d 标的" % len(cache))
    return cache


def main():
    ap = argparse.ArgumentParser(description="ETF 低位 usecase 发布与行情推送（XML 单一源）")
    ap.add_argument("--fetch", action="store_true", help="只刷新缓存行情（腾讯 qfq）")
    ap.add_argument("--push", action="store_true", help="发布后把 etf_cache.json 推送到手机")
    ap.add_argument("--all", action="store_true", help="刷新行情 + 发布 + 推送")
    ap.add_argument("--serial", default=None, help="adb 设备序列号（多设备时）")
    args = ap.parse_args()

    if args.fetch or args.all:
        cache = ensure_fresh_cache()
    else:
        cache = load_cache()
        if not cache:
            print("请先运行: python _etf_publish.py --all（首次拉全量行情）")
            return 1

    run_live(cache)
    if args.push or args.all:
        push_cache_to_phone(args.serial)
    return 0


if __name__ == "__main__":
    sys.exit(main())
