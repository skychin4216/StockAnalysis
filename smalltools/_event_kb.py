# -*- coding: utf-8 -*-
"""
事件库共享读取（smalltools 选股 / AutoQuant screen 引擎共用）。

数据单一事实源：<项目>/app/src/main/assets/macro_events/event_library.json
（Python/exe 从磁盘读；APK 打包同一份进 assets）

用法：
    from _event_kb import active_events, eff_map, boost_keywords
    boost_keywords(asof)   # 正向强受益关键词（≥min_delta），供注入 analyze 的 macroSectorKeywords
    active_events(asof)    # 当前窗口活跃实例 [(instance, type_def)]
    eff_map(asof)          # 全部关键词效应 {kw: delta}（含负向），供事后加权
文件缺失/解析失败时返回空，不影响主流程。
"""
import json
import os
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(HERE)  # smalltools 的上一级 = StockAnalysis
EVENT_JSON = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets",
                          "macro_events", "event_library.json")

_lock = threading.Lock()
_cache = {}  # mtime -> (types_dict, instances_list)


def load_lib():
    """返回 (types, instances)；失败返回 (None, None)。"""
    try:
        mt = os.path.getmtime(EVENT_JSON)
    except OSError:
        return None, None
    with _lock:
        hit = _cache.get(mt)
        if hit:
            return hit
        try:
            with open(EVENT_JSON, "r", encoding="utf-8") as f:
                lib = json.load(f)
        except (OSError, ValueError):
            return None, None
        out = ({t["id"]: t for t in lib.get("event_types", [])},
               lib.get("instances", []))
        _cache.clear()
        _cache[mt] = out
        return out


def active_events(asof=None):
    """asof(YYYY-MM-DD) 所在窗口的活跃实例 [(instance, type_def)]。"""
    if not asof:
        return []
    types, insts = load_lib()
    if not types or not insts:
        return []
    out = []
    for ins in insts:
        if (ins.get("start") or "9999") <= asof <= (ins.get("end") or "9999"):
            t = types.get(ins.get("event"))
            if t:
                out.append((ins, t))
    return out


def eff_map(asof=None):
    """活跃事件聚合效应 {关键词: delta×magnitude}（同类型并存取 |delta| 大者）。"""
    eff = {}
    for ins, t in active_events(asof):
        mag = float(ins.get("magnitude", 1.0))
        for kw, d in (t.get("seed_map") or {}).items():
            adj = float(d) * mag
            if kw not in eff or abs(adj) > abs(eff[kw]):
                eff[kw] = adj
    return eff


def boost_keywords(asof=None, min_delta=1.8):
    """正向强受益关键词（delta 方向一致按 |d| 排序）。"""
    eff = eff_map(asof)
    return sorted((kw for kw, d in eff.items() if d >= min_delta),
                  key=lambda kw: -eff[kw])


if __name__ == "__main__":
    import sys
    d = sys.argv[1] if len(sys.argv) > 1 else "2024-10-15"
    print("asof=%s active=%s" % (d, [i["id"] for i, _ in active_events(d)]))
    print("boost_keywords:", boost_keywords(d))
    print("eff:", eff_map(d))
