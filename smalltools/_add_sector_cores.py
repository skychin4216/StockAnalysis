# -*- coding: utf-8 -*-
"""将 27 个行业/主题 ETF 的前五重仓股（板块核心股）补入 K 线池。

背景（2026-09-14）：板块精选池此前只覆盖轮动扫描扫到的板块（军工/银行/油运/
石化/PCB/光通信/煤炭等），医药/地产/传媒/白电/汽车等板块从未入池 —— 一旦这些
板块启动，选股链路结构性选不到股。数据源 data/_etf_holdings.json（fundmob F10
季报口径，ETF→前十大重仓）是现成的"每板块 3-5 只机构共识核心股"映射：
每只行业 ETF 的前五重仓即该板块核心，27 只 ETF / 21 个主题 / 去重 95 只。

与 _add_leaders.py / _add_mlcc.py 同口径：东财全量四年 K 线，腾讯分段兜底，
限频重试 3 次，幂等（已在 cache 的跳过）。港股（如 00700/09988）非 A 股
K 线链路，自动跳过。

用法：python _add_sector_cores.py
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import fetch_east, fetch_tencent  # noqa: E402
import _market_db  # noqa: E402
from _extend_cache import merge_snaps, SEG1_END  # noqa: E402

CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_kline_cache.json")
HOLDINGS_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                             "data", "_etf_holdings.json")
BEG, END = "20220801", "20260914"
A股_RE = re.compile(r"^\d{6}$")  # 6 位数字 A 股代码（排除港股 00700/09988/PDD 等）


def to_secid(code6):
    c = str(code6)
    if c.startswith("6"):
        return "sh" + c
    if c.startswith(("4", "8")):
        return "bj" + c
    return "sz" + c


def fetch_full(secid, beg=BEG, end=END):
    """东财一次全量；失败用腾讯分段拼接。返回 (name, snaps, src)。"""
    name, snaps = fetch_east(secid, beg, end)
    if snaps:
        return name, snaps, "east"
    name1, seg1 = fetch_tencent(secid, beg, SEG1_END)
    name2, seg2 = fetch_tencent(secid, SEG1_END, end)
    snaps = merge_snaps([seg1, seg2])
    if snaps:
        return name1 or name2 or "", snaps, "tencent"
    return None, [], "none"


def load_sector_cores():
    """_etf_holdings.json → {theme: {secid: name}}（每 ETF 取前五重仓，多 ETF 同主题合并）。"""
    with open(HOLDINGS_FILE, encoding="utf-8") as f:
        funds = json.load(f).get("funds") or []
    themes = {}
    skipped = []
    for fu in funds:
        t = fu.get("theme") or fu.get("name") or "未知"
        for s in (fu.get("top") or [])[:5]:
            code, name = str(s.get("code", "")), s.get("name", "")
            if not A股_RE.match(code):
                skipped.append("%s(%s)" % (name, code))
                continue
            themes.setdefault(t, {})[to_secid(code)] = name
    return themes, skipped, len(funds)


def main():
    themes, skipped, n_funds = load_sector_cores()
    with open(CACHE_FILE, "r", encoding="utf-8") as f:
        cache = json.load(f)
    all_codes = {}
    for m in themes.values():
        all_codes.update(m)
    missing = {s: n for s, n in all_codes.items() if s not in cache}
    print("行业ETF %d 只 → 主题板块 %d 个 | 核心股去重 %d | 已在池 %d | 需补 %d | 跳过港股 %d 只(%s)" % (
        n_funds, len(themes), len(all_codes), len(all_codes) - len(missing), len(missing),
        len(skipped), ",".join(skipped[:4])))
    if not missing:
        print("无需补齐。")
        return
    conn = _market_db.get_conn()
    fail = []
    done = []
    for i, (secid, want_name) in enumerate(sorted(missing.items())):
        name, snaps, src = None, [], "none"
        for attempt in range(3):  # 限频偶发失败重试（间隔递增）
            try:
                name, snaps, src = fetch_full(secid)
            except Exception:
                name, snaps, src = None, [], "err"
            if snaps:
                break
            time.sleep(1.5 * (attempt + 1))
        if not snaps:
            fail.append("%s(%s)" % (want_name, secid))
            print("  [%d/%d] %s %s: 拉取失败(重试3次)" % (i + 1, len(missing), secid, want_name))
            continue
        cache[secid] = {"name": name or want_name, "snaps": snaps, "src": src}
        _market_db.upsert_kline(conn, secid, snaps, src=src, name=name or want_name)
        done.append(secid)
        print("  [%d/%d] %s %s 拉取 %d 根 (%s ~ %s)" % (
            i + 1, len(missing), secid, cache[secid]["name"], len(snaps),
            snaps[0]["date"], snaps[-1]["date"]))
        time.sleep(0.25)
    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(tmp, CACHE_FILE)
    # 分板块覆盖报告
    print("\n== 板块核心股覆盖（补后） ==")
    for t in sorted(themes):
        m = themes[t]
        have = sum(1 for s in m if s in cache)
        tag = "" if have == len(m) else "  ⚠缺:%s" % ",".join(
            "%s(%s)" % (m[s], s) for s in m if s not in cache)
        print("  %-6s %d/%d%s" % (t, have, len(m), tag))
    print("\n完成。成功 %d 失败 %d: %s" % (len(done), len(fail), fail[:12]))
    print("池内标的总数: %d" % len(cache))
    print("K线统计:", _market_db.kline_stats(conn))
    conn.close()


if __name__ == "__main__":
    main()
