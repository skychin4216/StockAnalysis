# -*- coding: utf-8 -*-
"""分段文本表 → XLSX（2026-09-12 新增，与 CSV / 长图同一份 `_table_csv.normalize` 口径）。

用户反馈：CSV 在 Excel/WPS 里「段标题左对齐、内容居中/右对齐」——纯 CSV 不带格式，
表格软件会把数字列自动右对齐、还会把 `000960` 的前导 0 吃掉。故在同口径之上额外产出
一份 XLSX：**所有单元格统一左对齐 + 全部按文本写入**（股票代码保留前导 0）。

用法：write_xlsx(path, sections, default_head=_TABLE_HEAD)
"""
import os

_SEG_HEAD = "分段"


def _col_width(s):
    """显示宽度（CJK 算 2 个半角位）。"""
    return sum(2 if ord(c) > 127 else 1 for c in str(s or ""))


def write_xlsx(path, sections, default_head=None, sheet_name="选股"):
    """sections（与 `_table_csv` 同一入参）→ 全左对齐 XLSX。返回 path。"""
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Font, PatternFill
    from openpyxl.utils import get_column_letter
    import _table_csv as _tc

    left = Alignment(horizontal="left", vertical="center")
    seg_font = Font(bold=True, color="FFFFFF")
    seg_fill = PatternFill("solid", fgColor="4472C4")
    head_font = Font(bold=True)
    head_fill = PatternFill("solid", fgColor="DCE6F1")

    wb = Workbook()
    ws = wb.active
    ws.title = sheet_name
    widths = {}

    def _put(row, values, font=None, fill=None, track=True):
        for i, v in enumerate(values):
            c = ws.cell(row=row, column=i + 1, value="" if v is None else str(v))
            c.alignment = left
            c.number_format = "@"      # 强制文本：左对齐 + 保留股票代码前导 0
            if font:
                c.font = font
            if fill:
                c.fill = fill
            if track:
                widths[i] = max(widths.get(i, 8), _col_width(v))

    r = 1
    for title, header, rows in _tc.normalize(sections, default_head):
        ncol = max([len(header)] + [len(x) for x in rows] + [1])
        _put(r, [title], font=seg_font, fill=seg_fill, track=False)
        if ncol > 1:
            ws.merge_cells(start_row=r, start_column=1, end_row=r, end_column=ncol)
        r += 1
        if header:
            _put(r, header, font=head_font, fill=head_fill)
            r += 1
        for row in rows:
            _put(r, row)
            r += 1

    for i, w in widths.items():
        ws.column_dimensions[get_column_letter(i + 1)].width = min(40, max(9, w + 2))
    ws.freeze_panes = "A2"

    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    wb.save(path)
    return path


if __name__ == "__main__":
    head = ["股票名称", "代码", "距60日高", "今日", "所属ETF", "趋势图谱", "星后形态",
            "RSI", "SAR", "MACD", "OBV", "均线粘合", "换手", "量比", "趋势图", "机构股", "备注"]
    demo = [
        {"title": "a1. 主线 DAG 当日选股（1只）", "rows": [
            {"name": "新·中科曙光", "code": "000960",
             "cells": ["-8.2%", "+1.1%", "半导体", "连跌3天→十字星", "星后第1天大阳", "45",
                       "红↑1", "金叉", "上行", "偏空", "5.8%", "3.4", "↑上涨·早晨之星",
                       "A·机构加仓", "短线"]}]},
        {"title": "a3. ETF 全行业扫描（1只）",
         "rows": [{"name": "工业富联", "code": "601138",
                   "cells": ["-6.1%", "+2.3%", "消费电子", "", "", "52", "红↑2",
                             "绿柱收窄", "下行", "多头", "—", "1.2", "↑上涨·锤子线看涨",
                             "—", "绿转红√"]}]},
    ]
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "data", "_xlsx_selftest.xlsx")
    print(write_xlsx(out, demo, default_head=head))
