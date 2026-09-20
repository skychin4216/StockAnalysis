# -*- coding: utf-8 -*-
"""QMT（迅投 xtquant）分钟线导入 —— **备用通道，开户后即可用**（2026-09-20 预置）。

## 为什么预留它

现有分钟数据源的能力边界（详见 `docs/分钟数据获取计划.md`）：

| 源 | 深度 | 缺点 |
|---|---|---|
| baostock（已用） | **2020-01 起**（≈6.7 年） | 免费但**不能实盘** |
| 通达信 PC | 1~3 年（每日下载可累积） | 本机软件跑不起来 |
| **QMT（本脚本）** | **1~3 年**（券商服务器决定）且**可每日累积** | 需开户（**10 万起**，多家已下调） |
| Wind / 米筐 | 完整 | 数万/年 |

**QMT 的真正价值**：同一个 `xtquant` 包**既能取数据、又能实盘下单** ——
一条路同时解决"分钟数据深度"与"交易接口"（另见 `_trade_gateway.py`）。

## 前置条件

    1. 在支持 QMT 的券商开户并开通量化权限（国金 / 华鑫 / 国盛 / 华泰 等，门槛 10 万起）
    2. 安装券商提供的 QMT 客户端（含 `xtquant` 包），或 `pip install xtquant`
    3. 启动 QMT 客户端并登录（`xtdata` 需客户端在运行，或至少完成过数据下载）

## 用法（与 `_baostock_import.py` 完全同构，输出同一份格式）

    python _qmt_import.py --stat                       # 探针：能不能连上、覆盖多久
    python _qmt_import.py --codes 600000,300308        # 指定票
    python _qmt_import.py --from-store --limit 112     # 从 kline_store 取池前 N 只
    python _qmt_import.py --period 60 --from-store     # 60 分钟
    python _qmt_import.py --codes-file _codes_2020.txt # 从文件读代码列表

输出：`data/intraday_qmt/{secid}_{period}.json`
      = {"symbol":"sh600000","period":5,"bars":[{"day","d","open","high","low","close","vol"}]}
      （与 `_baostock_import.py` / `_tdx_import.py` **同一格式**，`_intraday.py` 可直接消费）

⚠️ QMT 代码格式为 `600000.SH`（大写在前后缀），本脚本内部自动与项目格式 `sh600000` 互转。
"""
import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT_DEFAULT = os.path.join(ROOT, "data", "intraday_qmt")

PERIOD_MAP = {1: "1m", 5: "5m", 15: "15m", 30: "30m", 60: "60m"}


def to_qmt(secid):
    """sh600000 → 600000.SH（QMT 格式）。"""
    s = secid.lower().replace(".", "")
    if s[:2] in ("sh", "sz", "bj"):
        return "%s.%s" % (s[2:], s[:2].upper())
    return s


def to_our(code):
    """600000.SH → sh600000（项目格式）。"""
    c = code.upper().replace(".", "")
    tail = c[-2:]
    if tail in ("SH", "SZ", "BJ"):
        return tail.lower() + c[:-2]
    return code.lower()


def load_pool(limit=0):
    try:
        sys.path.insert(0, HERE)
        from _kline_store import load_store  # noqa: PLC0415
        codes = [s for s in load_store() if not s.startswith(("sh000", "sz399"))]
    except Exception as e:  # noqa: BLE001
        print("⚠️ 无法读 kline_store:", e)
        return []
    return codes[:limit] if limit else codes


def main():
    ap = argparse.ArgumentParser(description="QMT(xtquant) 分钟线导入（备用通道）")
    ap.add_argument("--codes", default="", help="逗号分隔 6 位代码或 sh600000 格式")
    ap.add_argument("--codes-file", default="", help="从文本文件读代码（逗号或换行分隔）")
    ap.add_argument("--from-store", action="store_true", help="从 kline_store 取全池")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--period", type=int, default=5, choices=list(PERIOD_MAP))
    ap.add_argument("--start", default="2020-01-01")
    ap.add_argument("--end", default="")
    ap.add_argument("--out", default=OUT_DEFAULT)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--stat", action="store_true", help="探针一只，看能否连通与覆盖区间")
    a = ap.parse_args()

    try:
        from xtquant import xtdata  # noqa: PLC0415
    except ImportError:
        print("❌ 未检测到 xtquant —— 需先开通 QMT 并安装其客户端自带的 Python 包")
        print("   券商开通后，把客户端目录下的 `xtquant` 目录拷到本机 site-packages，")
        print("   或在 QMT 自带的 Python 环境里运行本脚本。")
        print("   （开户指引：见 docs/分钟数据获取计划.md §4 QMT 一行，国金/华鑫门槛 10 万起）")
        return 1

    period_cn = PERIOD_MAP[a.period]

    if a.stat or not (a.codes or a.codes_file or a.from_store):
        code = "600000.SH"
        try:
            xtdata.download_history_data(code, period_cn, a.start.replace("-", ""), a.end.replace("-", ""))
            d = xtdata.get_market_data_ex([], [code], period=period_cn,
                                          start_time=a.start.replace("-", ""),
                                          end_time=a.end.replace("-", ""))
            df = (d or {}).get(code)
            n = 0 if df is None else len(df)
            print("探针 %s period=%s → %d 根" % (code, period_cn, n))
            if n:
                print("  覆盖 %s ~ %s" % (str(df.index[0])[:10], str(df.index[-1])[:10]))
        except Exception as e:  # noqa: BLE001
            print("❌ 连通失败:", type(e).__name__, str(e)[:120])
            print("   提示：QMT 客户端需处于登录状态；历史分钟数据需先在客户端『数据管理』里下载。")
            return 1
        return 0

    codes = []
    if a.codes:
        codes += [x.strip() for x in a.codes.split(",") if x.strip()]
    if a.codes_file and os.path.isfile(a.codes_file):
        raw = open(a.codes_file, encoding="utf-8").read().replace("\n", ",")
        codes += [x.strip() for x in raw.split(",") if x.strip()]
    if a.from_store:
        codes = load_pool(a.limit) or codes
    if a.limit and not a.from_store:
        codes = codes[:a.limit]
    print("待导入 %d 只（period=%s，%s ~ %s）" % (len(codes), period_cn, a.start, a.end or "今"))

    os.makedirs(a.out, exist_ok=True)
    n_ok, n_bar, n_skip = 0, 0, 0
    t0 = time.time()
    for i, c in enumerate(codes, 1):
        secid = c if c[:2] in ("sh", "sz", "bj") else \
            (("sh" if c[0] == "6" else "sz") + c)
        dst = os.path.join(a.out, "%s_%d.json" % (secid, a.period))
        if os.path.isfile(dst) and not a.force:
            n_skip += 1
            continue
        qcode = to_qmt(secid)
        try:
            xtdata.download_history_data(qcode, period_cn,
                                         a.start.replace("-", ""), (a.end or "").replace("-", ""))
            d = xtdata.get_market_data_ex([], [qcode], period=period_cn,
                                          start_time=a.start.replace("-", ""),
                                          end_time=(a.end or "").replace("-", ""))
            df = (d or {}).get(qcode)
            if df is None or len(df) == 0:
                continue
            bars = []
            for idx, row in df.iterrows():
                ts = str(idx)
                day = "%s-%s-%s" % (ts[0:4], ts[4:6], ts[6:8])
                hhmm = ts[9:13] if len(ts) >= 13 else "1500"
                bars.append({
                    "day": "%s %s:%s:00" % (day, hhmm[:2], hhmm[2:4]),
                    "d": day,
                    "open": round(float(row.get("open", 0)), 3),
                    "high": round(float(row.get("high", 0)), 3),
                    "low": round(float(row.get("low", 0)), 3),
                    "close": round(float(row.get("close", 0)), 3),
                    "vol": int(float(row.get("volume", 0) or 0)),
                })
            if not bars:
                continue
            bars.sort(key=lambda x: x["day"])
            with open(dst, "w", encoding="utf-8") as f:
                json.dump({"symbol": secid, "period": a.period, "bars": bars}, f,
                          ensure_ascii=False, separators=(",", ":"))
            n_ok += 1
            n_bar += len(bars)
            if i <= 3 or i % 50 == 0:
                print("  [%d/%d] %-10s %6d 根  %s ~ %s (%.0fs)"
                      % (i, len(codes), secid, len(bars), bars[0]["d"], bars[-1]["d"],
                         time.time() - t0))
        except Exception as e:  # noqa: BLE001
            print("  ⚠️ %s 失败: %s" % (secid, str(e)[:60]))
    print("✅ 导入 %d 只 / %d 根（跳过 %d）| 输出 %s | 耗时 %.0fs"
          % (n_ok, n_bar, n_skip, a.out, time.time() - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
