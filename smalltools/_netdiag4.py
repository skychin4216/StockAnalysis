# -*- coding: utf-8 -*-
import re
import requests

r = requests.get("https://qt.gtimg.cn/q=sz300308,sz300502,sz300394,sz002281,sh600498,sh688498,sz300620,sz002475,sh603236,sz002463,sh000001",
                 timeout=12, headers={"User-Agent": "Mozilla/5.0"}, proxies={"http": None, "https": None})
r.encoding = "gbk"
for line in r.text.strip().split(";"):
    line = line.strip()
    if not line or "=" not in line:
        continue
    m = re.search(r'="([^"]*)"', line)
    if not m:
        continue
    f = m.group(1).split("~")
    print(f"{f[1]:8s} 现价={f[3]:>9s} 涨跌幅={f[32]:>7s}% 换手率={f[38]:>6s}%")
