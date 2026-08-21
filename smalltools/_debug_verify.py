# -*- coding: utf-8 -*-
"""调试：打印光模块个股 8/12 前后 K 线明细与 MA，验证复刻逻辑"""
import sys
sys.path.insert(0, "e:/Android/work/dev/StockAnalysis/smalltools")
from backtest_guangmo import fetch_kline, avg, find_swing_high_index

for secid in ["sz300308", "sz300394"]:
    name, snaps, src = fetch_kline(secid)
    print(f"\n=== {name}({secid})[{src}] 最近16个交易日 ===")
    tail = snaps[-16:]
    closes = [s["close"] for s in snaps]
    ma5 = avg(closes[-5:])
    ma10 = avg(closes[-10:])
    ma20 = avg(closes[-20:])
    ma60 = avg(closes[-60:])
    print(f"MA5={ma5:.2f} MA10={ma10:.2f} MA20={ma20:.2f} MA60={ma60:.2f}")
    print(f"多头(3线): {ma5:.2f}>{ma10:.2f}>{ma20:.2f} -> {ma5>ma10>ma20}")
    print(f"多头(4线): {ma5:.2f}>{ma10:.2f}>{ma20:.2f}>{ma60:.2f} -> {ma5>ma10>ma20>ma60}")
    # 三日不新低
    prev_low = snaps[-4]["low"]
    lows = [(s["date"], s["low"]) for s in snaps[-3:]]
    print(f"三日不新低: 基准low(4日前)={prev_low}, 最近3日low={lows} -> "
          f"{all(s['low'] >= prev_low for s in snaps[-3:])}")
    for s in tail:
        print(f"  {s['date']} O={s['open']:.2f} C={s['close']:.2f} H={s['high']:.2f} L={s['low']:.2f} "
              f"V={s['volume']:.0f} 涨幅={s['changePct']:.2f}% 换手={s['turnover']:.2f}%")
