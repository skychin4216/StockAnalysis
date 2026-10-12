# -*- coding: utf-8 -*-
"""选股子编码（2026-09-21 用户需求 · Q1）

把当日选股清单映射成 `11 / 12 / 21 …` 两位子编码，用户在 APK 直接发
「买 11」「买 11 12 21」即可下单（由 `_remote_cmd.py` 解析执行）。

编码规则：
    十位 = 组别：1=短线  2=中线  3=长线  4=板块轮动（可扩展）
    个位 = 组内序号（1 起）

落盘 `data/_pick_codes.json`（含 asof，跨日自动失效由 `_remote_cmd` 判断）。
"""
from __future__ import annotations

import json
import os
import re
from typing import Any, Dict, List, Optional

DATA_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data")
CODES_FILE = os.path.join(DATA_DIR, "_pick_codes.json")

GROUP_PREFIX = {"短线": 1, "中线": 2, "长线": 3, "板块轮动": 4, "主线DAG": 1}


def assign(groups: Dict[str, List[Dict[str, Any]]], asof: str = "",
           max_per_group: int = 5) -> Dict[str, Dict[str, Any]]:
    """给各组选股分配子编码并落盘。返回 {子编码: pick}。"""
    mapping: Dict[str, Dict[str, Any]] = {}
    for gname, picks in (groups or {}).items():
        pre = GROUP_PREFIX.get(gname)
        if not pre:
            continue
        for i, p in enumerate((picks or [])[:max_per_group], start=1):
            code = "%d%d" % (pre, i)
            ent = dict(p or {})
            ent["pick_code"] = code
            ent["group"] = gname
            mapping[code] = ent
    save(mapping, asof)
    return mapping


def save(mapping: Dict[str, Dict[str, Any]], asof: str = "") -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = CODES_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"asof": asof, "codes": mapping}, f, ensure_ascii=False, indent=1)
    os.replace(tmp, CODES_FILE)


def load() -> Dict[str, Any]:
    try:
        return json.load(open(CODES_FILE, encoding="utf-8"))
    except Exception:                                # noqa: BLE001
        return {"asof": "", "codes": {}}


def resolve(code: str) -> Optional[Dict[str, Any]]:
    return (load().get("codes") or {}).get(str(code).strip())


def parse_buy(text: str) -> List[str]:
    """从消息里解析买入子编码：`买 11` / `买 11 12 21` / `buy 11,12` → ['11','12','21']"""
    t = (text or "").strip()
    m = re.search(r"(?:买|买入|buy)\s*([\d\s,，、]+)", t, re.IGNORECASE)
    if not m:
        return []
    raw = re.split(r"[\s,，、]+", m.group(1))
    out = []
    for x in raw:
        x = x.strip()
        if re.fullmatch(r"\d{2}", x):
            out.append(x)
    return out


def card_text(mapping: Dict[str, Dict[str, Any]], title: str = "当日选股") -> str:
    """按用户要求的格式生成清单卡片（放进「对话」Tab）。"""
    by_group: Dict[str, List[Dict[str, Any]]] = {}
    for code, p in mapping.items():
        by_group.setdefault(p.get("group") or "选股", []).append(p)
    lines = ["📊 %s（回复「买 编码」下单）" % title]
    for gname, items in by_group.items():
        lines.append("◆ %s" % gname)
        for p in sorted(items, key=lambda x: x.get("pick_code", "")):
            line = "%s. %s %s" % (p.get("pick_code"), p.get("name"), code6(p))
            if p.get("price"):
                line += " 现价%.2f" % float(p["price"])
            if p.get("main_cost"):
                line += " 主力成本%.2f" % float(p["main_cost"])
            if p.get("chg_pct") is not None:
                line += " %+.2f%%" % float(p["chg_pct"])
            if p.get("score"):
                line += " 评分%.2f" % float(p["score"])
            if p.get("reason"):
                line += " %s" % str(p["reason"])[:24]
            lines.append(line)
    return "\n".join(lines)


def code6(p: Dict[str, Any]) -> str:
    s = str(p.get("secid") or p.get("code") or "")
    return s[2:] if s[:2].lower() in ("sh", "sz") else s


if __name__ == "__main__":
    d = load()
    print("asof:", d.get("asof"), "| 编码数:", len(d.get("codes") or {}))
    for c, p in sorted((d.get("codes") or {}).items()):
        print("  %s. %-8s %-10s 现价%s 主力成本%s" % (
            c, p.get("name"), code6(p), p.get("price"), p.get("main_cost")))
