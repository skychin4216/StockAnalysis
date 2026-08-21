# -*- coding: utf-8 -*-
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from backtest_guangmo import fetch_kline
n, s, src = fetch_kline("sz300308")
print("name=", n)
print("len=", len(s))
print("src=", src)
print("last=", s[-1]["date"] if s else "none")
