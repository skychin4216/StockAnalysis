@echo off
rem T+1 自动卖出守护（ATR 止损 + 最长持有 10 日）—— 常驻，30s 一轮
cd /d E:\Android\work\dev\StockAnalysis\smalltools
python _auto_sell.py --daemon --mode atr --max-hold 10
