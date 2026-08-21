# -*- coding: utf-8 -*-
"""临时脚本：拉取全市场 A 股行业+总市值，生成 code->(行业, 总市值) 映射与行业内部排名。用完即删。"""
import json
import os
import sys
import time

import requests

H = {"User-Agent": "Mozilla/5.0"}
P = {"http": None, "https": None}
FS = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23"
HERE = os.path.dirname(os.path.abspath(__file__))


def fetch_all():
    rows = {}
    pn, pz = 1, 100
    while pn * pz <= 6000:
        u = ("https://push2.eastmoney.com/api/qt/clist/get?pn=%d&pz=%d&po=1&np=1"
             "&ut=bd1d9ddb04089700cf9c27f6f7426281&fltt=2&invt=2&fid=f20&fs=%s"
             "&fields=f2,f3,f12,f14,f20,f100") % (pn, pz, FS)
        got = False
        for attempt in range(4):
            try:
                j = requests.get(u, timeout=20, headers=H, proxies=P).json()
                diff = j["data"]["diff"]
                for row in diff:
                    rows[str(row["f12"])] = {
                        "name": row.get("f14", ""),
                        "industry": row.get("f100", ""),
                        "mktcap": row.get("f20") or 0,
                        "pct": row.get("f3"),
                    }
                total = j["data"]["total"]
                print("page %d/%d: +%d (共 %d)" % (pn, (total + pz - 1) // pz,
                                                   len(diff), len(rows)), flush=True)
                got = True
                break
            except Exception as e:
                print("page %d attempt %d fail: %s" % (pn, attempt, type(e).__name__), flush=True)
                time.sleep(2)
        if not got:
            print("page %d 跳过" % pn, flush=True)
        pn += 1
        time.sleep(0.5)
    return rows


rows = fetch_all()
# 行业内部按总市值排名
industry_rank = {}
for code, r in rows.items():
    ind = r["industry"]
    if not ind:
        continue
    industry_rank.setdefault(ind, []).append((r["mktcap"], code))
for ind in industry_rank:
    industry_rank[ind].sort(reverse=True)

out = {"stocks": rows, "industry_rank": {
    ind: [(rank, c, rows[c]["name"]) for rank, (_, c) in enumerate(
        [(mc, c) for mc, c in industry_rank[ind]], start=1)]
    for ind in industry_rank}}
json.dump(out, open(os.path.join(HERE, "_industry_map.json"), "w", encoding="utf-8"),
          ensure_ascii=False)
print("已保存 _industry_map.json: %d 只股票, %d 个行业" % (len(rows), len(industry_rank)))
