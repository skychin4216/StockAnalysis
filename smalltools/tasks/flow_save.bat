@echo off
rem 每日板块资金流落盘（供活跃度热力图用真资金维度）
cd /d E:\Android\work\dev\StockAnalysis\smalltools
python _sector_heatmap.py --save-flow
