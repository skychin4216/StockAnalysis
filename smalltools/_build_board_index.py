# -*- coding: utf-8 -*-
"""板块/概念索引生成（2026-09-16 用户需求·Q1）

数据源：data/kline_store.json 的 industry 字段 + 名称关键字（概念板块兜底）。
输出：data/board_index.json
  {
    "industry": {"半导体": ["sz002281","sz300308", ...], ...},
    "concept":   {"AI算力": [...], "CPO光模块": [...], "光通信": [...], ...},
    "subindustry": {"通信-光模块": [...], "通信-设备": [...], ...},
    "asof": "2026-09-16",
    "stats": {"industry_n": 23, "concept_n": 12, "total_codes": 688}
  }

概念关键字由人工维护（CN 行业词 → 票名/全称包含即归入），可后续扩充。

用法：
    python smalltools/_build_board_index.py [--asof 2026-09-16] [--json]
"""
from __future__ import annotations
import argparse
import json
import os
import re
import sys
from collections import defaultdict
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _kline_store  # noqa: E402

OUT_PATH = os.path.normpath(os.path.join(HERE, "..", "data", "board_index.json"))

# 概念板块关键字（名称命中即归入）。可按需扩展；空集合 = 不归入任何概念。
CONCEPT_KEYWORDS: dict[str, list[str]] = {
    "AI算力":      ["算力", "CPO", "光模块", "旭创", "新易盛", "天孚", "中际"],
    "CPO光模块":   ["CPO", "光模块", "光迅", "中际旭创", "新易盛", "天孚通信",
                    "博创", "剑桥", "太辰光", "铭普", "华工科技"],
    "光通信":      ["光迅", "光通信", "中际旭创", "新易盛", "亨通", "长飞",
                    "中天", "通鼎", "永鼎", "特发", "剑桥"],
    "机器人":      ["机器人", "埃斯顿", "绿的", "谐波", "汇川", "机器人", "智元",
                    "减速器", "伺服"],
    "固态电池":    ["固态电池", "宁德", "比亚迪", "赣锋", "国轩", "当升", "容百",
                    "蜂巢", "孚能", "清陶"],
    "数字货币":    ["数字货币", "区块链", "Coinbase", "比特币"],
    "国产替代":    ["国产替代", "自主可控", "信创", "鲲鹏", "昇腾", "海光",
                    "龙芯", "兆芯", "申威"],
    "军工":        ["军工", "航空", "航天", "导弹", "舰船", "中航", "航天",
                    "洪都", "西飞", "沈飞", "中国船舶"],
    "新能源车":    ["新能源车", "电动车", "蔚来", "理想", "小鹏", "比亚迪",
                    "赛力斯", "江淮", "北汽蓝谷"],
    "光伏":        ["光伏", "隆基", "通威", "阳光电源", "晶澳", "天合", "晶科",
                    "迈为", "捷佳", "钧达"],
    "储能":        ["储能", "宁德", "派能", "固德威", "锦浪", "禾迈", "昱能",
                    "南都", "阳光电源"],
    "CXO":         ["CXO", "药明", "凯莱英", "康龙", "泰格", "博腾"],
    "创新药":      ["创新药", "百济", "信达", "君实", "再鼎", "恒瑞"],
    "煤炭":        ["煤炭", "陕西煤业", "中国神华", "兖矿", "晋控", "山煤",
                    "兰花", "平煤", "淮北"],
    "高股息":      ["银行", "高速公路", "电力", "煤炭", "石化", "中国神华",
                    "长江电力", "招商银行", "工商银行"],
    "食品饮料":    ["白酒", "茅台", "五粮液", "泸州", "伊利", "海天", "双汇",
                    "东鹏", "古井"],
    "医美":        ["医美", "爱美客", "华熙", "昊海", "朗姿"],
    "猪肉":        ["猪", "牧原", "温氏", "新希望", "正邦", "天邦"],
    "国产软件":    ["国产软件", "金山办公", "用友", "广联达", "中望", "福昕"],
    "GPU/AI芯片":  ["GPU", "海光", "寒武纪", "景嘉微", "摩尔线程", "沐曦",
                    "燧原", "壁仞", "昇腾", "地平线"],
}

# 子板块关键字（在 industry 基础上再细一层）
SUBINDUSTRY_KEYWORDS: dict[str, list[str]] = {
    "通信-光模块":     ["光模块", "CPO", "光迅", "中际旭创", "新易盛", "天孚"],
    "通信-设备":       ["通信设备", "交换机", "华为", "中兴", "星网", "烽火"],
    "通信-光纤光缆":   ["光纤", "亨通", "长飞", "中天", "通鼎"],
    "半导体-设备":     ["半导体设备", "北方华创", "中微", "盛美", "拓荆", "华海清科"],
    "半导体-材料":     ["半导体材料", "沪硅", "立昂微", "雅克", "彤程", "南大光电"],
    "半导体-设计":     ["芯片设计", "韦尔", "兆易", "卓胜", "圣邦", "思瑞浦",
                        "寒武纪", "海光"],
    "新能源-电池":     ["电池", "宁德", "比亚迪", "国轩", "亿纬", "孚能"],
    "新能源-锂矿":     ["锂矿", "赣锋", "天齐", "盐湖", "藏格", "永兴"],
    "新能源-光伏":     ["光伏", "隆基", "通威", "晶澳", "天合"],
    "新能源-风电":     ["风电", "金风", "明阳", "运达", "大金", "天顺"],
    "医药-CXO":        ["CXO", "药明", "凯莱英", "康龙", "泰格"],
    "医药-器械":       ["医疗器械", "迈瑞", "联影", "鱼跃", "乐普"],
    "消费-白酒":       ["白酒", "茅台", "五粮液", "泸州", "古井"],
    "消费-家电":       ["家电", "美的", "格力", "海尔", "海信", "TCL"],
    "金融-银行":       ["银行", "招商银行", "工商银行", "建设银行", "农业银行",
                        "中国银行"],
    "金融-保险":       ["保险", "中国平安", "中国人寿", "新华", "太保"],
    "金融-证券":       ["证券", "中信证券", "华泰", "国泰", "海通", "招商"],
    "军工-航空":       ["航空", "沈飞", "西飞", "成飞", "洪都"],
    "军工-船舶":       ["船舶", "中国船舶", "中国重工", "中船防务"],
    "周期-煤炭":       ["煤炭", "中国神华", "陕西煤业", "兖矿", "晋控"],
    "周期-钢铁":       ["钢铁", "宝钢", "鞍钢", "河钢", "首钢", "太钢"],
    "周期-化工":       ["化工", "万华", "恒力", "荣盛", "桐昆"],
}


def build(asof: str = "") -> dict:
    store = _kline_store.load_store()
    if not asof:
        asof = max((v.get("snaps") or [{}])[-1].get("date", "")[:10]
                   for v in store.values()) or date.today().isoformat()

    industry: dict[str, list[str]] = defaultdict(list)
    concept: dict[str, list[str]] = defaultdict(list)
    subindustry: dict[str, list[str]] = defaultdict(list)

    for code, ent in store.items():
        name = ent.get("name") or code
        ind = ent.get("industry") or ""
        if ind and ind != "-":
            industry[ind].append(code)
        # 名称/全称命中概念关键字（去重）
        haystack = (name + " " + ent.get("fullName", "") + " " + code).upper()
        for cname, keys in CONCEPT_KEYWORDS.items():
            for kw in keys:
                if kw.upper() in haystack or kw in name:
                    concept[cname].append(code)
                    break
        for sname, keys in SUBINDUSTRY_KEYWORDS.items():
            for kw in keys:
                if kw.upper() in haystack or kw in name:
                    subindustry[sname].append(code)
                    break

    industry = {k: sorted(set(v)) for k, v in industry.items() if v}
    concept = {k: sorted(set(v)) for k, v in concept.items() if v}
    subindustry = {k: sorted(set(v)) for k, v in subindustry.items() if v}

    # 总覆盖统计
    covered = set()
    for vs in industry.values():
        covered.update(vs)

    return {
        "asof": asof,
        "industry": dict(sorted(industry.items())),
        "concept": dict(sorted(concept.items())),
        "subindustry": dict(sorted(subindustry.items())),
        "stats": {
            "industry_n": len(industry),
            "concept_n": len(concept),
            "subindustry_n": len(subindustry),
            "total_codes": len(store),
            "industry_covered": len(covered),
            "industry_missing": len(store) - len(covered),
        },
    }


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--asof", default="")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    idx = build(asof=args.asof)
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(idx, f, ensure_ascii=False, indent=1)
    s = idx["stats"]
    if args.json:
        print(json.dumps({"out": OUT_PATH, **s}, ensure_ascii=False, indent=1))
    else:
        print("📚 板块索引已生成 → %s" % OUT_PATH)
        print("  申万行业 %d 类（覆盖 %d/%d 只，缺失 %d 只 industry 字段）"
              % (s["industry_n"], s["industry_covered"], s["total_codes"],
                 s["industry_missing"]))
        print("  概念板块 %d 类｜子板块 %d 类" % (s["concept_n"], s["subindustry_n"]))
        print()
        print("  === 概念板块速览 ===")
        for k, v in idx["concept"].items():
            print("    %-12s %2d 只  e.g. %s"
                  % (k, len(v), " ".join(v[:3])))
        print()
        print("  === 子板块速览 ===")
        for k, v in idx["subindustry"].items():
            print("    %-14s %2d 只  e.g. %s"
                  % (k, len(v), " ".join(v[:3])))