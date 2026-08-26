# -*- coding: utf-8 -*-
"""五大板块(有色/黄金/科技/医药/电池)动量扫描,基于 _kline_cache.json
输出:板块内近 5/10/20 日动量、MA 排列、距 60 日高点回撤、防守/进攻属性
"""
import json
import os

CACHE = os.path.join(os.path.dirname(__file__), "_kline_cache.json")

# 板块关键词分组(按股票名称)
SECTORS = {
    "黄金": ["黄金", "紫金", "山金", "赤峰", "中金"],
    "有色": ["铝", "铜", "钨", "稀土", "锡", "锌", "锗", "钼", "钛", "钒", "钽", "锆",
             "中稀", "宝钛", "西部材料", "钢研", "铂", "镍", "锂", "钴", "金钼", "东方钽业"],
    "科技": ["微", "芯", "光", "电", "曙光", "寒武", "澜起", "兆易", "圣邦", "卓胜", "汇顶",
             "三安", "华天", "长电", "通富", "华润", "沪电", "胜宏", "深南", "景旺", "生益",
             "旭创", "新易盛", "天孚", "光迅", "仕佳", "源杰", "富联", "浪潮", "立讯",
             "传音", "科大讯飞", "金山", "用友", "中科", "北方华创", "海光", "韦尔", "豪威",
             "斯达", "华峰测控", "圣邦"],
    "医药": ["药", "医", "生", "血", "疫苗", "健康", "迈瑞", "恒瑞", "复星", "百济",
             "药明", "智飞", "沃森", "片仔", "白药", "长春高新", "泰格", "凯莱", "联影"],
    "电池": ["电池", "锂", "宁德", "国轩", "亿纬", "阳光", "储能", "新能源", "比亚迪",
             "赣锋", "天齐", "永兴", "永杉", "江特", "格林美", "华友", "天赐", "恩捷",
             "璞泰来", "容百", "当升"],
}


def ma(xs, n):
    return sum(xs[-n:]) / n if len(xs) >= n else None


def scan():
    cache = json.load(open(CACHE, encoding="utf-8"))
    rows = []
    for secid, e in cache.items():
        if secid.startswith(("sh000", "sz399")):
            continue
        snaps = e.get("snaps") or []
        if len(snaps) < 70:
            continue
        name = e.get("name") or secid
        closes = [s["close"] for s in snaps]
        latest = snaps[-1]
        ma5, ma10, ma20, ma60 = ma(closes, 5), ma(closes, 10), ma(closes, 20), ma(closes, 60)
        if not ma5 or not ma60:
            continue
        c = closes[-1]
        chg5 = (c / closes[-6] - 1) * 100 if len(closes) >= 6 else 0
        chg10 = (c / closes[-11] - 1) * 100 if len(closes) >= 11 else 0
        chg20 = (c / closes[-21] - 1) * 100 if len(closes) >= 21 else 0
        hi60 = max(closes[-60:])
        drawdown = (c / hi60 - 1) * 100
        bull = (ma5 > ma10 > ma20)
        # 近20日均量
        vols = [s["volume"] for s in snaps[-20:]]
        vol20 = sum(vols) / len(vols)
        v5 = sum(s["volume"] for s in snaps[-5:]) / 5
        vol_ratio = v5 / vol20 if vol20 > 0 else 0
        rows.append({
            "secid": secid, "name": name, "close": c,
            "chg5": chg5, "chg10": chg10, "chg20": chg20,
            "dd60": drawdown, "bull": bull, "vol_ratio": vol_ratio,
            "ma5": ma5, "ma20": ma20,
        })

    # 按板块分组(一只股可属于多个板块,去重展示)
    for sec in SECTORS:
        kws = SECTORS[sec]
        group = []
        for r in rows:
            if any(kw in r["name"] for kw in kws):
                group.append(r)
        # 去重
        seen = {}
        for r in group:
            seen.setdefault(r["name"], r)
        group = sorted(seen.values(), key=lambda x: x["chg10"], reverse=True)
        print(f"\n{'='*70}\n【{sec}】共 {len(group)} 只\n{'='*70}")
        print(f"{'名称':<10}{'现价':>8}{'近5日':>8}{'近10日':>8}{'近20日':>9}{'距60高':>8}{'量比':>6}  {'形态'}")
        for r in group:
            tag = "多头" if r["bull"] else ("超跌" if r["dd60"] < -12 else "震荡")
            print(f"{r['name']:<10}{r['close']:>8.2f}{r['chg5']:>7.1f}%{r['chg10']:>7.1f}%"
                  f"{r['chg20']:>8.1f}%{r['dd60']:>7.1f}%{r['vol_ratio']:>6.2f}  {tag}")


if __name__ == "__main__":
    scan()
