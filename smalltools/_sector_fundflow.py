# -*- coding: utf-8 -*-
"""板块资金流实时数据源（东财 push2delay，当日实时）。

背景：轮动引擎(_rotation_engine)目前用「成分股动量聚合+催化剂+宏观因子」，
缺少资金维度。资金流（主力净流入）是当日实时数据、无法历史回放，
因此设计为「当日候选验证层」：板块动量候选出来后，用资金流做二次过滤/排序。

东财板块资金流字段（clist, fs=m:90+t:2 东财行业板块）：
  f62 主力净流入额(元)   f184 主力净流入占比%   f66 超大单净流入
  f72 大单净流入  f78 中单净流入  f84 小单净流入
  f12 板块代码(BKxxxx) f14 板块名

用法：
  python _sector_fundflow.py                  # 打印当日全板块资金流 Top20/最低10
  python _sector_fundflow.py --names 半导体,生物制品,煤炭行业   # 只查指定板块
  模块内调用 get_sector_fundflow() -> {板块名: {main_yi, main_pct, ...}}
"""
import json
import sys
import time

import requests

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "Referer": "https://quote.eastmoney.com/"}
PX = {"http": None, "https": None}
HOST = "https://push2delay.eastmoney.com/api/qt/clist/get"

# 东财行业板块名 → 轮动引擎候选常用名（仅提示用，实际查询直接用东财板块名）
ALIAS = {
    "煤炭行业": "煤炭行业", "生物制品": "创新药", "通信设备": "光通信",
    "种植业与林业": "农业种植", "贵金属": "黄金贵金属", "半导体": "半导体",
    "化学制药": "创新药", "白酒": "白酒",
}


def fetch_board_flow(progress=False):
    """抓取东财全行业板块资金流。返回 {板块名: {main_yi, main_pct, zdf_pct, code}}"""
    out = {}
    pn = 1
    total = None
    while True:
        params = {
            "pn": pn, "pz": 100, "po": 1, "np": 1,
            "ut": "bd1d9ddb04089700cf9c27f6f7426281",
            "fltt": 2, "invt": 2, "fid": "f62", "fs": "m:90+t:2+f:!50",
            "fields": "f12,f14,f2,f3,f62,f184,f66,f72,f78,f84",
        }
        query = "&".join("%s=%s" % (k, v) for k, v in params.items())
        try:
            d = requests.get(HOST + "?" + query, timeout=15, headers=UA, proxies=PX).json()
        except Exception as e:
            print("  [warn] 第 %d 页失败: %s" % (pn, e))
            break
        data = d.get("data") or {}
        if total is None:
            total = data.get("total") or 0
        diff = data.get("diff") or []
        if not diff:
            break
        for it in diff:
            name = str(it.get("f14") or "")
            if not name:
                continue
            out[name] = {
                "code": str(it.get("f12") or ""),
                "main_yi": (float(it.get("f62") or 0)) / 1e8,   # 主力净流入(亿)
                "main_pct": float(it.get("f184") or 0),          # 主力净流入占比%
                "zdf_pct": float(it.get("f3") or 0),             # 板块涨跌幅%
                "super_yi": (float(it.get("f66") or 0)) / 1e8,
                "large_yi": (float(it.get("f72") or 0)) / 1e8,
            }
        if progress:
            print("  第 %d 页 板块资金流已累计 %d" % (pn, len(out)))
        pn += 1
        if pn * 100 > (total or 0) + 100:
            break
        time.sleep(0.2)
    return out


def get_sector_fundflow(names=None, cached=None):
    """查询指定板块(中文名, 可用轮动板块名/东财名)资金流。
    返回 {查询名: {main_yi, main_pct, zdf_pct, flow_note}}
    cached: 若已抓过全量则传入复用。
    """
    full = cached
    if full is None:
        full = fetch_board_flow()
    if not names:
        return full
    # 名称匹配：先精确、再包含、再别名
    result = {}
    for q in names:
        qq = q.strip()
        hit = None
        if qq in full:
            hit = full[qq]
        else:
            for key, v in full.items():
                if qq in key or key in qq:
                    hit = v
                    break
        if hit is None:
            alias_target = ALIAS.get(qq)
            if alias_target and alias_target in full:
                hit = full[alias_target]
        if hit:
            result[qq] = hit
    return result


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--names", help="逗号分隔的板块名")
    args = ap.parse_args()
    full = fetch_board_flow(progress=True)
    print("共 %d 个东财行业板块" % len(full))
    if args.names:
        wanted = [n.strip() for n in args.names.split(",")]
        res = get_sector_fundflow(wanted, cached=full)
        for k, v in res.items():
            note = "主力净流入 %.2f亿" % v["main_yi"]
            if v["main_yi"] > 0:
                note += " (流入, 板块涨%.2f%%)" % v["zdf_pct"]
            else:
                note += " (流出, 板块涨%.2f%%)" % v["zdf_pct"]
            print("  %-10s %s" % (k, note))
        return
    ranked = sorted(full.items(), key=lambda x: -x[1]["main_yi"])
    print("\n主力净流入 Top15:")
    for k, v in ranked[:15]:
        print("  %-10s %8.2f亿  占比%5.2f%%  涨%6.2f%%" % (k, v["main_yi"], v["main_pct"], v["zdf_pct"]))
    print("\n主力净流出 Bottom10:")
    for k, v in ranked[-10:]:
        print("  %-10s %8.2f亿  占比%5.2f%%  涨%6.2f%%" % (k, v["main_yi"], v["main_pct"], v["zdf_pct"]))


if __name__ == "__main__":
    main()
