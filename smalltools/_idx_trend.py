# -*- coding: utf-8 -*-
import sys, traceback
sys.path.insert(0, "e:/Android/work/dev/StockAnalysis/smalltools")
from backtest_guangmo import fetch_tencent, get_index_dir


def log(m):
    print(m, flush=True)
    sys.stdout.flush()


log("script start")
codes = ['sh000001', 'sz399001', 'sz399006', 'sh000852', 'sh000688',
         'sz399101', 'sz399102', 'sh000016', 'sh000905', 'sh000300']
names = {'sh000001': '上证', 'sz399001': '深成', 'sz399006': '创业板',
         'sh000852': '中证1000', 'sh000688': '科创50', 'sz399101': '中小板综',
         'sz399102': '创业板综', 'sh000016': '上证50', 'sh000905': '中证500',
         'sh000300': '沪深300'}
for code in codes:
    try:
        name, snaps = fetch_tencent(code, '20250601', '20260813')
        if not snaps:
            log(f"{names.get(code, code)}: 拉取失败")
            continue
        closes = [s['close'] for s in snaps]
        ma5 = sum(closes[-5:]) / 5
        ma10 = sum(closes[-10:]) / 10
        ma20 = sum(closes[-20:]) / 20
        last = snaps[-1]
        recent = " ".join(f"{s['date'][5:]}({s['changePct']:+.1f}%)" for s in snaps[-5:])
        log(f"{names.get(code, name)} 最新{last['date']} 收盘{last['close']:.2f} "
            f"涨{last['changePct']:+.2f}% 方向{get_index_dir(name, snaps)} "
            f"MA5={ma5:.1f} MA10={ma10:.1f} MA20={ma20:.1f}")
        log(f"   近5日: {recent}")
    except Exception as e:
        log(f"{names.get(code, code)}: 异常 {type(e).__name__}: {e}")
log("script end")
