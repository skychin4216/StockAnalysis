# -*- coding: utf-8 -*-
"""放量企稳二次确认 —— deepseek ETF 低位思路落地（2026-09-06）。

参考：deepseek_ETF_选股思路.log
  ① 位置低：close 处近 252 日 [low,high] 低位分位 ≤ pos_max（默认 0.30，≈52周低区）
  ② 超卖：RSI14 < rsi_max（默认 40）
  ③ 不接飞刀：不因"位置低+超卖"立刻买，等 5 个交易日内出现『放量企稳』阳线
     再入场——volume ≥ min_vol_ratio × 前5日均量(不含当日) 且 收盘>昨收 且 收>开。
     （放量 = 换手承接；企稳 = 阳线收复，排除缩量阴跌中接飞刀）

数据口径与 _kline_cache.json 一致：snaps 升序 dict 列表，
字段 date/open/high/low/close/volume（volume 缺省 0 的字段视为无）。

用法（模块内调用）：
    ind = indicators(snaps, win=252)
    ok  = low_signal(ind, i)                 # i 处"低位+超卖"信号
    j   = first_confirm(snaps, i, look=5)    # 其后首个放量企稳阳线下标（无则 -1）
    line = describe(snaps, i, ind)           # 人读摘要，供低吸观察推送标注
"""
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)


def _closes(snaps):
    return [float(s.get("close") or 0) for s in snaps]


def _nums(snaps, key):
    return [float(s.get(key) or 0) for s in snaps]


def rolling_ma(values, w):
    out = [None] * len(values)
    s = 0.0
    for i, v in enumerate(values):
        s += v
        if i >= w:
            s -= values[i - w]
        if i >= w - 1:
            out[i] = s / w
    return out


def rsi14_sma(closes, period=14):
    """RSI(SMA14)：不足 period+1 根的位置为 None。与常见行情软件 SMA 口径一致。"""
    n = len(closes)
    out = [None] * n
    if n <= period:
        return out
    gains = [0.0] * n
    losses = [0.0] * n
    for i in range(1, n):
        d = closes[i] - closes[i - 1]
        if d > 0:
            gains[i] = d
        elif d < 0:
            losses[i] = -d
    for i in range(period, n):
        g = sum(gains[i - period + 1:i + 1])
        l = sum(losses[i - period + 1:i + 1])
        out[i] = 100.0 if l == 0 else 100.0 - 100.0 / (1.0 + g / l)
    return out


def rolling_min_max(lows, highs, w):
    """窗口 [i-w+1..i] 的 min(low) / max(high)（rolling，O(n)）。"""
    from collections import deque
    n = len(lows)
    rmin, rmax = [None] * n, [None] * n
    dmin, dmax = deque(), deque()
    for i in range(n):
        while dmin and dmin[-1][1] >= lows[i]:
            dmin.pop()
        dmin.append((i, lows[i]))
        while dmin and dmin[0][0] <= i - w:
            dmin.popleft()
        while dmax and dmax[-1][1] <= highs[i]:
            dmax.pop()
        dmax.append((i, highs[i]))
        while dmax and dmax[0][0] <= i - w:
            dmax.popleft()
        if i >= w - 1:
            rmin[i] = dmin[0][1]
            rmax[i] = dmax[0][1]
    return rmin, rmax


def indicators(snaps, win=252, rsi_period=14):
    """预计算指标数组（返回 dict，与 snaps 等长）。"""
    closes = _closes(snaps)
    lows = _nums(snaps, "low")
    highs = _nums(snaps, "high")
    vols = _nums(snaps, "volume")
    n = len(snaps)
    rmin, rmax = rolling_min_max(lows, highs, win)
    pos = [None] * n
    for i in range(n):
        if rmin[i] is not None and rmax[i] > rmin[i]:
            pos[i] = min(1.0, max(0.0, (closes[i] - rmin[i]) / (rmax[i] - rmin[i])))
    return {
        "closes": closes, "lows": lows, "highs": highs, "vols": vols,
        "vma5": rolling_ma(vols, 5),
        "pos": pos,
        "rsi": rsi14_sma(closes, rsi_period),
        "win": win,
    }


def low_signal(ind, i, pos_max=0.30, rsi_max=40.0):
    """i 处是否满足『低位分位 ≤ pos_max 且 RSI14 < rsi_max』。"""
    if i < 0 or i >= len(ind["pos"]):
        return False
    p = ind["pos"][i]
    r = ind["rsi"][i]
    return p is not None and r is not None and p <= pos_max and r < rsi_max


def is_confirm(snaps, i, ind=None, min_vol_ratio=1.5):
    """i 处是否『放量企稳』阳线：
    量 ≥ min_vol_ratio × 前5日均量(窗口截至 i-1)，且 收>昨收、收>开。"""
    if i < 5 or i >= len(snaps):
        return False
    ind = ind or indicators(snaps)
    day = snaps[i]
    v = float(day.get("volume") or 0)
    vma_prev = ind["vma5"][i - 1]
    if vma_prev is None or vma_prev <= 0:
        return False
    closes = ind["closes"]
    if closes[i] <= 0 or closes[i - 1] <= 0:
        return False
    return (v >= min_vol_ratio * vma_prev
            and closes[i] > closes[i - 1]
            and float(day.get("close") or 0) > float(day.get("open") or 0))


def first_confirm(snaps, i, look=5, ind=None, min_vol_ratio=1.5):
    """低位信号 i 之后 look 个交易日内首个放量企稳阳线下标；无则 -1。"""
    ind = ind or indicators(snaps)
    n = len(snaps)
    for j in range(i + 1, min(i + 1 + look, n)):
        if is_confirm(snaps, j, ind=ind, min_vol_ratio=min_vol_ratio):
            return j
    return -1


def describe(snaps, i, ind=None, look=5, min_vol_ratio=1.5):
    """人读一行：分位/RSI + 是否已放量企稳。供『热门板块ETF重仓·低吸观察』推送标注。"""
    ind = ind or indicators(snaps)
    parts = []
    p = ind["pos"][i] if 0 <= i < len(ind["pos"]) else None
    r = ind["rsi"][i] if 0 <= i < len(ind["rsi"]) else None
    if p is not None:
        parts.append("分位%.0f%%" % (p * 100))
    if r is not None:
        parts.append("RSI%.0f" % r)
    if is_confirm(snaps, i, ind=ind, min_vol_ratio=min_vol_ratio):
        parts.append("今日放量企稳✓")
    elif i < len(snaps):
        j = first_confirm(snaps, i, look=look, ind=ind, min_vol_ratio=min_vol_ratio)
        if j >= 0:
            parts.append("待企稳(预计%d日)%s" % (j - i, snaps[j]["date"][5:]))
        else:
            parts.append("未企稳·勿接飞刀")
    return " ".join(parts) if parts else "-"


if __name__ == "__main__":
    # 自检：从 _kline_cache 取一只低位票打印当前状态
    from _full_cycle_backtest import load_cache
    cache = load_cache()
    for sid, ent in sorted(cache.items()):
        if sid.startswith(("sh000", "sz399")):
            continue
        snaps = ent.get("snaps") or []
        if len(snaps) < 260:
            continue
        i = len(snaps) - 1
        ind = indicators(snaps)
        if low_signal(ind, i):
            print("%s %s [%s] %s" % (sid, ent.get("name"), snaps[i]["date"],
                                     describe(snaps, i, ind=ind)))
            break
    else:
        print("全池当前无‘低位+超卖’样本（或均已在低位）")
