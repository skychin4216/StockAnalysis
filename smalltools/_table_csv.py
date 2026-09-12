# -*- coding: utf-8 -*-
"""表格分段 → 单文件 CSV → 统一长截图（2026-09-12 用户方案）。

背景
----
此前推送/复盘的各段表格是「各自构造 → 直接渲染成一张 PNG」，表格数据不落盘，
无法把「原始表格」发出去。用户要求：先把各段「填充到一份 CSV」，再由这份 CSV
统一渲染成一张长图（保证图与 CSV 内容严格一致），并把 CSV 文件本身一并推送。

CSV 结构（单文件；首列『分段』标明每行属于哪一段）
--------------------------------------------------
    分段,<段1列名1>,<段1列名2>,...
    <段1标题>,<值...>
    ...
    (空行)
    分段,<段2列名1>,...
    <段2标题>,<值...>

读取时以「首列 == 分段」的行作为段头（其后各列即该段表头），其余行的首列为段标题、
其余列按当前段表头解析。这样同一份 CSV 可容纳列数列名各不相同的多张表（各表表头
得以保留）；Excel 打开后按『分段』列筛选即可逐表查看。

用法
----
    import _table_csv as tcsv
    csv_path, png_path = tcsv.export(csv, png, "标题", sections,
                                     default_head=_TABLE_HEAD, note="说明")
sections 支持推送端既有两种形态（可混用）：
    {"title":.., "rows":[{name,code,cells}]}      # 共用 default_head
    {"title":.., "header":[...], "body":[[...]]}  # 该段自有表头
"""
import csv
import os

MARK = "分段"          # 段头标识（首列）
DASH = "—"             # 空单元格占位（与渲染口径一致）
MAX_PNG_BYTES = 2 * 1024 * 1024   # 企微 image 消息上限

_FALLBACK_DPI = (110, 92, 76, 62)  # PNG 超限时逐档降 dpi 重渲染


def _cell(v):
    """单元格文本 → 与渲染层同一套字符规整（`_table_img._clean`）。

    渲染长图时每个格子都会过 `_clean`（emoji → 「新·/封板·/流出·」等等效文字，超 BMP
    与 ⭐ 类杂符删除）。若 CSV 不动同样处理，就会出现「CSV 是 🆕、图里是 新·」的不一致；
    这里提前规整 → **CSV 与长图逐格严格一致**（`_clean` 幂等，重复调用无副作用）。
    """
    s = "" if v is None else str(v)
    try:
        import _table_img as ti
        return ti._clean(s)
    except Exception:  # noqa: BLE001
        return s


def normalize(sections, default_head):
    """sections → [(title, header, rows2d), ...]（写盘/渲染共用，剔除空段）。"""
    out = []
    for s in sections or []:
        if not s:
            continue
        title = _cell(s.get("title"))
        header = [_cell(h) for h in (s.get("header") or default_head or [])]
        rows = []
        if s.get("body"):
            for r in s["body"]:
                rows.append([_cell(c) for c in r])
        else:
            for r in s.get("rows") or []:
                if isinstance(r, dict):
                    rows.append([_cell(r.get("name")), _cell(r.get("code"))]
                                + [_cell(c) for c in (r.get("cells") or [])])
                else:
                    rows.append([_cell(c) for c in r])
        if not rows and not header:
            continue
        out.append((title, header, rows))
    return out


def write_csv(path, sections, default_head):
    """各段 → 单文件 CSV（utf-8-sig，Excel 直接可读）。无有效段返回 None。"""
    secs = normalize(sections, default_head)
    if not secs:
        return None
    parent = os.path.dirname(os.path.abspath(path))
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        first = True
        for title, header, rows in secs:
            if not first:
                w.writerow([])
            first = False
            w.writerow([MARK] + header)
            for r in rows:
                w.writerow([title] + r)
    return path


def read_csv(path):
    """单文件 CSV → [(title, header, rows2d), ...]（与 write_csv 严格互逆）。"""
    secs = []
    with open(path, encoding="utf-8-sig", newline="") as f:
        for row in csv.reader(f):
            if not row or all(not str(c).strip() for c in row):
                continue
            if str(row[0]).strip() == MARK:
                secs.append([None, [str(c) for c in row[1:]], []])
                continue
            if not secs:
                continue
            title, header, rows = secs[-1]
            if title is None:
                secs[-1][0] = str(row[0])
                secs[-1][2].append([str(c) for c in row[1:]])
            else:
                rows.append([str(c) for c in row[1:]])
    return [(t or "", h, r) for t, h, r in secs if r]


def render_png(csv_path, png_path, title=None, note=None, max_bytes=MAX_PNG_BYTES):
    """读 CSV → 统一渲染成一张长 PNG（超 2MB 自动逐档降 dpi）。"""
    import _table_img as ti
    secs = read_csv(csv_path)
    pack = [{"title": t, "header": h, "rows": r} for t, h, r in secs if r]
    if not pack:
        return None
    parent = os.path.dirname(os.path.abspath(png_path))
    if parent:
        os.makedirs(parent, exist_ok=True)
    for dpi in _FALLBACK_DPI:
        ti.render_multi_table(title, pack, png_path, note=note, dpi=dpi)
        try:
            if os.path.getsize(png_path) <= max_bytes:
                break
        except OSError:
            break
    return png_path


def export(csv_path, png_path, title, sections, default_head, note=None):
    """各段 → 单文件 CSV →（据该 CSV）统一长图。返回 (csv_path, png_path|None)。"""
    csv_path = write_csv(csv_path, sections, default_head)
    if not csv_path:
        return None, None
    try:
        return csv_path, render_png(csv_path, png_path, title=title, note=note)
    except Exception as e:  # noqa: BLE001
        print("合并表格渲染失败(保留CSV):", type(e).__name__, e)
        return csv_path, None


if __name__ == "__main__":
    head = ["股票名称", "代码", "距60日高", "今日", "所属ETF", "趋势图谱", "星后形态",
            "RSI", "SAR", "MACD", "OBV", "均线粘合", "换手", "量比", "趋势图", "备注"]
    demo = [
        {"title": "a1. 主线 DAG 当日选股（1只）", "rows": [
            {"name": "新·中科曙光", "code": "603019",
             "cells": ["-8.2%", "+1.1%", "科创50", "回踩MA20", "星后大阳", "45.1", "SAR红5",
                       "金叉", "↑量能配合", "粘合", "5.8%", "3.4", "↑上涨·早晨之星",
                       "短线"][:14]}]},
        {"title": "a3. ETF 全行业扫描（1只）",
         "rows": [{"name": "工业富联", "code": "601138",
                   "cells": ["-6.1%", "+2.3%", "消费电子", "跌后十字星", "", "52", "绿转红√",
                             "绿柱收窄", "下行", "多头", "—", "1.2", "↑上涨·早晨之星",
                             "绿转红√ RSI52 SAR红↑1"][:14]}]},
    ]
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
    c, p = export(os.path.join(out_dir, "_table_csv_selftest.csv"),
                  os.path.join(out_dir, "_table_csv_selftest.png"),
                  "自检样例（CSV → 统一长图）", demo, head)
    print("CSV :", c)
    print("PNG :", p)
    print("回读段数:", len(read_csv(c)))
