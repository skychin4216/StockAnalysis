# -*- coding: utf-8 -*-
import requests
r = requests.get("https://qt.gtimg.cn/q=sh601001", timeout=10,
                 headers={"User-Agent": "Mozilla/5.0"}, proxies={"http": None, "https": None})
r.encoding = "gbk"
f = r.text.strip().split("=", 1)[1].strip().strip('"').split("~")
print("名称", f[1])
print("现价", f[3], "昨收", f[4], "今开", f[5])
print("涨跌", f[31], "涨跌%", f[32])
print("最高", f[33], "最低", f[34])
print("成交量(手)", f[36], "成交额(万)", f[37])
