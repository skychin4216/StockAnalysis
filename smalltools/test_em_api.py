# -*- coding: utf-8 -*-
"""打印 F10 gdrs 与 sdltgd 完整字段"""
import json
import urllib.request

HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
           "Referer": "https://emweb.securities.eastmoney.com/"}

def fetch(url):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))

d = fetch("https://emweb.securities.eastmoney.com/PC_HSF10/ShareholderResearch/PageAjax?code=SZ000001")

print("=== gdrs[0] 完整字段 ===")
g = (d.get("gdrs") or [])[0]
print(json.dumps(g, ensure_ascii=False, indent=1))

print("\n=== sdltgd 中 香港中央结算 完整字段 ===")
for rec in d.get("sdltgd") or []:
    if "香港中央结算" in rec.get("HOLDER_NAME", ""):
        print(json.dumps(rec, ensure_ascii=False, indent=1))
        break
