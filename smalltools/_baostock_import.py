# -*- coding: utf-8 -*-
"""baostock 分钟线导入（免费 · 免装软件 · 2020 起 ≈6.7 年，2026-09-19 新增）。

## 为什么用它

其他分钟源实测都不够用（详见 `docs/分钟数据获取计划.md` §2）：
    新浪 5 分钟   1023 根 ≈ 21 个交易日（datalen>1023 返回空）
    新浪 60 分钟  1023 根 ≈ 1 年
    腾讯 proxy    恒 320 根
    东财 klt=5/60 本机 5 分片全断
    通达信        本机软件跑不起来，vipdoc 全空

**baostock 实测（2026-09-19）**：`sh.600000` 5 分钟线 **78,192 根**
覆盖 **2020-01-02 ~ 2026-09-18（≈6.7 年）**，免费、纯 Python、匿名登录、带前复权。
→ 这是目前**支撑「维持 1 小时」口径回溯的最优数据源**。

## 用法

    python _baostock_import.py --stat                      # 先看覆盖（拉一只探针票）
    python _baostock_import.py --codes 600000,300308       # 指定票
    python _baostock_import.py --from-store --limit 100    # 从 data/kline_store.json 取池前 N 只
    python _baostock_import.py --period 60 --from-store --limit 50
    python _baostock_import.py --from-store                # 全池 688 只（耗时长，建议后台跑）

输出：`data/intraday/{secid}_{period}.json`
      = {"symbol":"sh600000","period":5,"bars":[{"day","d","open","high","low","close","vol"}]}
      （与 `_tdx_import.py` 输出格式一致，`_intraday.py` 可直接消费）

⚠️ 增量策略：已存在且覆盖到最新交易日的文件**默认跳过**（`--force` 可强制重拉）。
"""
import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT_DEFAULT = os.path.join(ROOT, "data", "intraday")


def _bs_sid(secid):
    """sh600000 → sh.600000（baostock 格式）。"""
    s = secid.lower().replace(".", "")
    if s[:2] in ("sh", "sz", "bj"):
        return "%s.%s" % (s[:2], s[2:])
    return s


def _our_sid(secid):
    """sh.600000 → sh600000（项目格式）。"""
    return secid.lower().replace(".", "")


def fetch_one(bs, secid, period, start, end):
    rs = bs.query_history_k_data_plus(
        _bs_sid(secid), "date,time,open,high,low,close,volume",
        start_date=start, end_date=end, frequency=str(period), adjustflag="2")
    bars = []
    while rs.error_code == "0" and rs.next():
        r = rs.get_row_data()
        # 字段序：date,time,open,high,low,close,volume
        if not r or not r[2]:
            continue
        try:
            bars.append({
                "day": "%s %s:%s:%s" % (r[0], r[1][8:10], r[1][10:12], r[1][12:14] or "00"),
                "d": r[0],
                "open": round(float(r[2]), 3), "high": round(float(r[3]), 3),
                "low": round(float(r[4]), 3), "close": round(float(r[5]), 3),
                "vol": int(float(r[6] or 0)),
            })
        except (ValueError, IndexError):
            continue
    return bars


def _load_pool(limit=0):
    """从 data/kline_store.json 取股票池（排除指数）。"""
    try:
        sys.path.insert(0, HERE)
        from _kline_store import load_store  # noqa: PLC0415
        store = load_store()
    except Exception as e:  # noqa: BLE001
        print("⚠️ 无法读 kline_store:", e)
        return []
    codes = [s for s in store if not s.startswith(("sh000", "sz399"))]
    return codes[:limit] if limit else codes


def main():
    ap = argparse.ArgumentParser(description="baostock 分钟线导入（2020 起）")
    ap.add_argument("--codes", default="", help="逗号分隔 6 位代码")
    ap.add_argument("--from-store", action="store_true", help="从 kline_store 全池取代码")
    ap.add_argument("--limit", type=int, default=0, help="最多 N 只（0=全部）")
    ap.add_argument("--period", type=int, default=5, choices=[5, 15, 30, 60])
    ap.add_argument("--start", default="2020-01-01")
    ap.add_argument("--end", default=time.strftime("%Y-%m-%d"))
    ap.add_argument("--out", default=OUT_DEFAULT)
    ap.add_argument("--force", action="store_true", help="已存在也重拉")
    ap.add_argument("--stat", action="store_true", help="只探针一只，看覆盖区间")
    a = ap.parse_args()

    try:
        import baostock as bs  # noqa: PLC0415
    except ImportError:
        print("❌ 未安装 baostock：pip install baostock")
        return 1

    lg = bs.login()
    if lg.error_code != "0":
        print("❌ baostock 登录失败:", lg.error_msg)
        return 1

    if a.stat or not (a.codes or a.from_store):
        bars = fetch_one(bs, "sh600000", a.period, a.start, a.end)
        print("探针 sh600000 period=%d → %d 根" % (a.period, len(bars)))
        if bars:
            print("  覆盖 %s ~ %s" % (bars[0]["d"], bars[-1]["d"]))
            print("  首根 %s | 末根 %s" % (bars[0]["day"], bars[-1]["day"]))
        bs.logout()
        return 0

    codes = [x.strip() for x in a.codes.split(",") if x.strip()] if a.codes \
        else _load_pool(a.limit)
    if a.codes and a.limit:
        codes = codes[:a.limit]
    print("待导入 %d 只（period=%d，%s ~ %s）" % (len(codes), a.period, a.start, a.end))

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
        try:
            bars = fetch_one(bs, secid, a.period, a.start, a.end)
        except Exception as e:  # noqa: BLE001
            print("  ⚠️ %s 失败: %s" % (secid, str(e)[:60]))
            continue
        if not bars:
            continue
        with open(dst, "w", encoding="utf-8") as f:
            json.dump({"symbol": secid, "period": a.period, "bars": bars}, f,
                      ensure_ascii=False, separators=(",", ":"))
        n_ok += 1
        n_bar += len(bars)
        if i <= 3 or i % 50 == 0:
            print("  [%d/%d] %-10s %6d 根  %s ~ %s  (%.0fs)"
                  % (i, len(codes), secid, len(bars), bars[0]["d"], bars[-1]["d"],
                     time.time() - t0))
    bs.logout()
    print("✅ 导入 %d 只 / %d 根（跳过已存在 %d）| 输出 %s | 耗时 %.0fs"
          % (n_ok, n_bar, n_skip, a.out, time.time() - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
