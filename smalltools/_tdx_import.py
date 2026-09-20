# -*- coding: utf-8 -*-
"""通达信分钟线导入（.lc5/.lc1 → 项目统一 JSON，2026-09-19 新增）。

## 为什么需要它

免费接口的分钟K历史极短（实测 2026-09-19）：
    新浪 scale=5    1023 根 ≈ **21 个交易日**（datalen>1023 直接返回空）
    新浪 scale=60   1023 根 ≈ **1 年**（这是免费源能拿到的最长分钟历史）
    腾讯 proxy m5/m60 恒 320 根
    东财 klt=5/60   本机 5 分片全 ConnectionError
而「买入后价格维持 1 小时以上」这类口径**必须有分钟级数据**，18 年回溯更是遥不可及。
**通达信（免费）** 的分钟线保存在本地文件里（`.lc5`=5分钟 / `.lc1`=1分钟），
持续「盘后数据下载」可累积**数年**，是免费方案里唯一可行的长历史分钟源。

## 通达信目录结构

    <安装目录>\\vipdoc\\sh\\fzline\\sh600000.lc5     5 分钟线（沪）
    <安装目录>\\vipdoc\\sh\\minline\\sh600000.lc1    1 分钟线（沪）
    <安装目录>\\vipdoc\\sz\\fzline\\sz000001.lc5     5 分钟线（深）

## .lc5/.lc1 二进制格式（每条 32 字节，小端）

    offset 0   uint16  date   = 年*2048 + 月*100 + 日（年 = date//2048 + 2004）
    offset 2   uint16  time   = 当日第 N 分钟（如 9:35 → 575）
    offset 4   float   open
    offset 8   float   high
    offset 12  float   low
    offset 16  float   close
    offset 20  float   amount（成交额）
    offset 24  int32   volume（成交量，股）
    offset 28  int32   reserved

## 用法

    python _tdx_import.py --stat                      # 只统计：本地有多少只、覆盖多久
    python _tdx_import.py --dir "C:\\new_tdx"          # 全量导入（sh+sz，5分钟）
    python _tdx_import.py --dir "D:\\new_tdx" --period 1
    python _tdx_import.py --dir ... --codes 600000,300308
    python _tdx_import.py --dir ... --period 60 --out data/intraday_tdx
    python _tdx_import.py --dir ... --to-store         # 合并进 data/kline_store.json
                                                        # 的每分钟序列？(否：另存独立文件)

输出：`data/intraday_tdx/{secid}_{period}.json`
      = {"symbol": "sh600000", "period": 5, "bars": [{"day","open","high","low","close","vol"}]}
"""
import argparse
import glob
import json
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT_DEFAULT = os.path.join(ROOT, "data", "intraday_tdx")
REC = 32
# 通达信周期目录：fzline=5分钟, minline=1分钟；60 分钟同 fzline（.lc5 里含 60 分钟？）
# 实际通达信：\vipdoc\sh\fzline\*.lc5 = 5分钟；\vipdoc\sh\minline\*.lc1 = 1分钟。
# 60 分钟线通达信不单独存文件（由 5 分钟合成），故 period=60 时由 5 分钟聚合。
DIR_OF = {1: "minline", 5: "fzline", 60: "fzline"}
EXT_OF = {1: ".lc1", 5: ".lc5", 60: ".lc5"}


def parse_lc(path):
    """解析 .lc5/.lc1 → [{day, open, high, low, close, vol}]（升序）。"""
    with open(path, "rb") as f:
        buf = f.read()
    n = len(buf) // REC
    out = []
    for i in range(n):
        b = buf[i * REC:(i + 1) * REC]
        di = int.from_bytes(b[0:2], "little")
        ti = int.from_bytes(b[2:4], "little")
        if di == 0:
            continue
        o, h, l, c = struct.unpack("<4f", b[4:20])
        vol = struct.unpack("<i", b[24:28])[0]
        y = di // 2048 + 2004
        mo = (di % 2048) // 100
        da = (di % 2048) % 100
        if not (1990 <= y <= 2100 and 1 <= mo <= 12 and 1 <= da <= 31):
            continue
        out.append({
            "day": "%04d-%02d-%02d %02d:%02d" % (y, mo, da, ti // 60, ti % 60),
            "d": "%04d-%02d-%02d" % (y, mo, da),
            "open": round(float(o), 3), "high": round(float(h), 3),
            "low": round(float(l), 3), "close": round(float(c), 3),
            "vol": int(vol),
        })
    out.sort(key=lambda x: x["day"])
    return out


def aggregate(bars5, minutes=60):
    """5 分钟线 → 60 分钟线（通达信不单独存 60 分钟文件）。"""
    per = max(1, minutes // 5)
    out = []
    for i in range(0, len(bars5), per):
        grp = bars5[i:i + per]
        out.append({
            "day": grp[0]["day"], "d": grp[0]["d"],
            "open": grp[0]["open"],
            "high": max(g["high"] for g in grp),
            "low": min(g["low"] for g in grp),
            "close": grp[-1]["close"],
            "vol": sum(g["vol"] for g in grp),
        })
    return out


def discover(base, period, codes=None):
    """扫描 vipdoc → [(secid, path)]。"""
    ext, sub = EXT_OF[period], DIR_OF[period]
    found = []
    for mkt in ("sh", "sz", "bj"):
        pat = os.path.join(base, "vipdoc", mkt, sub, "*" + ext)
        for p in glob.glob(pat):
            name = os.path.basename(p)[:-len(ext)]
            if codes and name[-6:] not in codes:
                continue
            found.append((name, p))
    return sorted(found)


def main():
    ap = argparse.ArgumentParser(description="通达信分钟线导入（.lc5/.lc1 → JSON）")
    ap.add_argument("--dir", help="通达信安装目录（含 vipdoc）")
    ap.add_argument("--period", type=int, default=5, choices=[1, 5, 60])
    ap.add_argument("--codes", default="", help="逗号分隔 6 位代码（默认全部）")
    ap.add_argument("--out", default=OUT_DEFAULT)
    ap.add_argument("--stat", action="store_true", help="只统计本地分钟数据覆盖情况")
    ap.add_argument("--limit", type=int, default=0, help="最多导入 N 只（0=全部）")
    a = ap.parse_args()

    if not a.dir:
        # 自动探测常见安装路径
        for c in ("C:\\new_tdx", "C:\\new_tdx64", "D:\\new_tdx", "E:\\new_tdx",
                  "D:\\Program Files\\new_tdx", "C:\\Program Files\\new_tdx"):
            if os.path.isdir(os.path.join(c, "vipdoc")):
                a.dir = c
                print("自动探测到通达信目录:", c)
                break
        if not a.dir:
            print("❌ 未找到通达信目录，请用 --dir 指定（目录下应有 vipdoc\\sh\\fzline\\*.lc5）")
            print("   本机探测结果：常见路径均不存在 → 需先安装通达信并做「盘后数据下载」")
            return 1

    codes = set(x.strip()[-6:] for x in a.codes.split(",") if x.strip())
    items = discover(a.dir, a.period, codes or None)
    print("发现 %d 个 .%s 文件（period=%d）" % (len(items), EXT_OF[a.period].lstrip("."), a.period))
    if not items:
        print("❌ 无数据文件。请在通达信里执行：系统 → 盘后数据下载 → 勾选「分钟线」→ 下载。")
        return 1
    if a.limit:
        items = items[:a.limit]

    os.makedirs(a.out, exist_ok=True)
    n_ok, n_bar, first_d, last_d = 0, 0, "9999", "0000"
    for secid, p in items:
        try:
            bars = parse_lc(p)
            if a.period == 60 and bars:
                bars = aggregate(bars, 60)
            if not bars:
                continue
        except Exception as e:  # noqa: BLE001
            print("  ⚠️ %s 解析失败: %s" % (secid, e))
            continue
        dst = os.path.join(a.out, "%s_%d.json" % (secid, a.period))
        with open(dst, "w", encoding="utf-8") as f:
            json.dump({"symbol": secid, "period": a.period, "bars": bars}, f,
                      ensure_ascii=False, separators=(",", ":"))
        n_ok += 1
        n_bar += len(bars)
        first_d = min(first_d, bars[0]["d"])
        last_d = max(last_d, bars[-1]["d"])
        if a.stat or n_ok <= 3:
            print("  %-10s %6d 根  %s ~ %s" % (secid, len(bars), bars[0]["d"], bars[-1]["d"]))
    print("✅ 导入 %d 只 / %d 根 | 覆盖 %s ~ %s | 输出 %s"
          % (n_ok, n_bar, first_d, last_d, a.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
