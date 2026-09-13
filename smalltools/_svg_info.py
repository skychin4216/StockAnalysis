# -*- coding: utf-8 -*-
import re
s = open('e:/Android/work/dev/StockAnalysis/smalltools/output/full_architecture.svg', encoding='utf-8').read()
# 第一个 ds_tencent 节点的 text（id=n_ds_tencent_0 是大文本，id=n_ds_tencent 是 id 小文本）
for m in re.finditer(r'<text[^>]*>([^<]+)</text>', s):
    print(m.group(0))
    print('---')
    if m.start() > 4000:
        break