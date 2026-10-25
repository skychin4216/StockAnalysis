# -*- coding: utf-8 -*-
"""缠论 × 趋势闸 组合回测（BBI12闸 / 日线多周期闸 / ER闸）
=====================================
入场 = 缠论实买点（一买/二买，is_sure，chan.py 逐根无未来信息）
       **且** 可选趋势闸：
         --bbi      1H BBI12 谷底拐点后仍上升（单票自身时序）
         --mtf bi   日线最后一笔已确认向上（多周期：日线择势、1H择时）
         --mtf zs   日线收盘在最近已形成中枢上方
         --mtf both 两者皆需
         --er       ER(60) ≥ 自身过去 250 根 70 分位
出场 = 缠论实卖点（与 _chanlun_backtest 同口径），可选 --stop 跌破买入价离场
费用 = FEE 0.13%（同 _chanlun_backtest）

日线闸严格只用「早于当日」的日线K线（当日日线未走完），无未来信息。

用法：
  python smalltools/_chanlun_bbi_backtest.py 600206 --mtf bi --stop
  python smalltools/_chanlun_bbi_backtest.py --pool        # 4 个多周期变体池级对照
"""
import argparse
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from _chanlun_engine import CHAN_DIR, LV_MAP, load_klines, _to_klu          # noqa: E402
from _chanlun_backtest import FEE, DEFAULT_BUY, DEFAULT_SELL, POOL_JSON, WORKERS  # noqa: E402
from _er_router_backtest import bbi12_line, bbi12_pivots, er_series, pctile  # noqa: E402

sys.path.insert(0, CHAN_DIR)
from Chan import CChan                  # noqa: E402
from ChanConfig import CChanConfig      # noqa: E402


ER_W = 60        # Kaufman 效率比窗口（同 _er_router_backtest）
ER_ROLL = 250    # 自身时序分位滚动窗口（同报告 erq 口径）
ER_PCT = 70      # 入场阈值分位
ER_MIN = 120     # 分位最少样本


def bbi12_gate(closes):
    """逐根布尔闸：末个已确认拐点=谷底 且 BBI12 当前仍在上升（line[i] >= line[i-1]）。
    拐点记录位置=确认bar（bbi12_pivots 已 +1），无未来信息。"""
    line = bbi12_line(closes)
    piv = bbi12_pivots(line)
    n = len(closes)
    gate = [False] * n
    last_bottom = None     # 最近已确认拐点是否谷底
    pi = 0
    for i in range(n):
        while pi < len(piv) and piv[pi][0] <= i:
            last_bottom = piv[pi][1]
            pi += 1
        rising = (i >= 1 and line[i] == line[i] and line[i - 1] == line[i - 1]
                  and line[i] >= line[i - 1])
        gate[i] = (last_bottom is True) and rising
    return gate


def er_gate_series(closes):
    """逐根布尔闸：ER(60) ≥ 自身过去 250 根的 70 分位（滚动时序，无未来信息）。"""
    er = er_series(closes, ER_W)
    n = len(closes)
    gate = [False] * n
    for i in range(n):
        lo = max(0, i - ER_ROLL)
        win = [x for x in er[lo:i] if x == x]
        if len(win) >= ER_MIN and er[i] == er[i] and er[i] >= pctile(win, ER_PCT):
            gate[i] = True
    return gate


def daily_gate_map(code: str, lv: str = "1h", mode: str = "bi") -> dict:
    """日线趋势闸（多周期共振）：返回 {date: bool}。
    只用严格早于 date 的日线K线计算（当日日线未走完，无未来信息）。
    mode: bi=最后一笔已确认向上；zs=收盘在最近已形成中枢上方；both=两者皆需。
    """
    d_rows = load_klines(code, "day")
    h_rows = load_klines(code, lv)
    if len(d_rows) < 60 or not h_rows:
        return {}
    kl_type, _ = LV_MAP["day"]
    chan = CChan(code=code, lv_list=[kl_type], config=CChanConfig({"trigger_step": True}))
    klus = [_to_klu(r, "day") for r in d_rows]
    di = 0
    out = {}
    for d in sorted({r["time"][:10] for r in h_rows}):
        while di < len(klus) and d_rows[di]["time"][:10] < d:
            chan.trigger_load({kl_type: [klus[di]]})
            di += 1
        if di == 0:
            out[d] = False
            continue
        kl = chan.kl_datas[kl_type]
        bis = [b for b in kl.bi_list.bi_list if b.is_sure]
        up = bool(bis) and bis[-1].dir.value == 1
        zs_ok = False
        if kl.zs_list.zs_lst:
            zs_ok = d_rows[di - 1]["close"] > kl.zs_list.zs_lst[-1].high
        out[d] = up if mode == "bi" else zs_ok if mode == "zs" else (up and zs_ok)
    return out


def macd_hist(closes, fast: int = 12, slow: int = 26, signal: int = 9):
    """MACD 柱（DIF-DEA），用于背驰面积比较。"""
    def ema(vals, n):
        k = 2.0 / (n + 1)
        e = vals[0] if vals else 0.0
        out = []
        for v in vals:
            e = v * k + e * (1 - k)
            out.append(e)
        return out
    ef, es = ema(closes, fast), ema(closes, slow)
    dif = [a - b for a, b in zip(ef, es)]
    dea = ema(dif, signal)
    return [d - s for d, s in zip(dif, dea)]


def _neg_area(hist, u2i: dict, a, b):
    """[a,b] 区间内绿柱面积绝对值；区间非法返回 None。"""
    ia, ib = u2i.get(a), u2i.get(b)
    if ia is None or ib is None or ia > ib:
        return None
    return abs(sum(x for x in hist[ia:ib + 1] if x < 0))


def bottom_divergence(kl, hist, u2i: dict) -> bool:
    """底背驰：最近两个已确认下跌笔，新低但 MACD 绿柱面积缩小。"""
    downs = [b for b in kl.bi_list.bi_list if b.is_sure and b.dir.value == 2]
    if len(downs) < 2:
        return False
    cur, prev = downs[-1], downs[-2]
    if cur.get_end_val() >= prev.get_end_val():
        return False
    a_cur = _neg_area(hist, u2i, cur.get_begin_klu().idx, cur.get_end_klu().idx)
    a_prev = _neg_area(hist, u2i, prev.get_begin_klu().idx, prev.get_end_klu().idx)
    return a_cur is not None and a_prev is not None and a_cur < a_prev


def wave_gate(kl):
    """波浪黄金分割闸（艾略特 2/4 浪回调 + 3 浪推进）：
    最近一笔已确认下跌笔 d 回撤前一上升笔 u 的 30%~70%，
    且 d 的低点高于前一个下跌笔低点（更高低点，趋势未破坏）。
    条件成立时返回 d 的低点（供「价格已回升」判定），否则 None。"""
    bis = [b for b in kl.bi_list.bi_list if b.is_sure]
    if len(bis) < 4 or bis[-1].dir.value != 2 or bis[-2].dir.value != 1:
        return None
    d, u = bis[-1], bis[-2]
    amp = u.get_end_val() - u.get_begin_val()
    if amp <= 0:
        return None
    retr = (u.get_end_val() - d.get_end_val()) / amp
    if not (0.30 <= retr <= 0.70):
        return None
    lows = [b.get_end_val() for b in bis if b.dir.value == 2]
    if len(lows) >= 2 and lows[-1] <= lows[-2]:
        return None
    return d.get_end_val()


def cci_series(rows, n: int = 20):
    """CCI(20)：典型价 (H+L+C)/3 的均值偏差归一化。"""
    tp = [(r["high"] + r["low"] + r["close"]) / 3.0 for r in rows]
    out = [float("nan")] * len(tp)
    for i in range(n - 1, len(tp)):
        win = tp[i - n + 1:i + 1]
        ma = sum(win) / n
        md = sum(abs(x - ma) for x in win) / n
        out[i] = (tp[i] - ma) / (0.015 * md) if md > 0 else 0.0
    return out


def cci_gate(cci, mode: str):
    """CCI 入场闸：pos=CCI>0；strong=CCI>100；cross=近 3 根内上穿 0。"""
    n = len(cci)
    g = [False] * n
    for i in range(n):
        if cci[i] != cci[i]:
            continue
        if mode == "pos":
            g[i] = cci[i] > 0
        elif mode == "strong":
            g[i] = cci[i] > 100
        elif mode == "cross":
            g[i] = cci[i] > 0 and any(cci[j] == cci[j] and cci[j] <= 0
                                     for j in range(max(0, i - 2), i))
    return g


def backtest_one(code: str, lv: str = "1h", use_gate: bool = True,
                 stop: bool = False, use_er: bool = False, mtf: str = "",
                 buy_types=None, struct_stop: bool = False,
                 require_div: bool = False, wave: bool = False,
                 wave_entry: bool = False, cci: str = "", cci_n: int = 20,
                 seg: int = 0) -> dict:
    bt = buy_types or DEFAULT_BUY
    kl_type, _ = LV_MAP[lv]
    rows = load_klines(code, lv)
    trade_from = 0
    if seg in (1, 2) and len(rows) >= 480:
        mid = len(rows) // 2
        if seg == 1:
            rows = rows[:mid]
        else:
            # 后段：前置 240 根预热（只喂结构、不开仓），确保缠论笔/中枢已就绪
            start = max(0, mid - 240)
            rows = rows[start:]
            trade_from = mid - start
    if len(rows) < 60:
        return {"code": code, "lv": lv, "error": "数据不足", "trades": 0}
    closes = [r["close"] for r in rows]
    gate = bbi12_gate(closes) if use_gate else None
    erg = er_gate_series(closes) if use_er else None
    dgate = daily_gate_map(code, lv, mtf) if mtf else None
    hist = macd_hist(closes) if require_div else None
    cg = cci_gate(cci_series(rows, cci_n), cci) if cci else None

    chan = CChan(code=code, lv_list=[kl_type], config=CChanConfig({"trigger_step": True}))
    klus = [_to_klu(r, lv) for r in rows]
    u2i = {}   # klu.idx -> 数组下标（idx 在 trigger_load 时才赋值，故在循环内登记）

    trades = []
    in_pos = False
    buy_price = 0.0
    buy_raw = 0.0
    buy_idx = -1
    buy_i = -1
    buy_type = ""
    stop_price = 0.0

    for i in range(len(klus)):
        chan.trigger_load({kl_type: [klus[i]]})
        u2i[klus[i].idx] = i
        kl = chan.kl_datas[kl_type]
        bsps = [b for b in kl.bs_point_lst.bsp_store_flat_dict.values() if b.bi.is_sure]
        if not bsps:
            continue
        last = max(bsps, key=lambda b: b.bi.idx)
        last_t = {t.value for t in last.type}
        px = klus[i].close
        idx = klus[i].idx

        if not in_pos:
            allow = (dgate is None or dgate.get(rows[i]["time"][:10], False))
            wave_low = wave_gate(kl) if (wave or wave_entry) else None
            wave_ok = wave_low is not None and px > wave_low
            if wave_entry:
                sig_ok = wave_ok
            else:
                sig_ok = last.is_buy and bool(last_t & bt)
                # 背驰过滤只作用于逆势买点（一买/二买）；三买本身即趋势确认，不要求背驰
                if sig_ok and require_div and (last_t & {"1", "1p", "2", "2s"}):
                    sig_ok = bottom_divergence(kl, hist, u2i)
                if wave:
                    sig_ok = sig_ok and wave_ok
            if (sig_ok and i >= trade_from
                    and (gate is None or gate[i])
                    and (cg is None or cg[i])
                    and (erg is None or erg[i])
                    and allow):
                in_pos = True
                buy_price = px * (1 + FEE)
                buy_raw = px
                buy_idx = idx
                buy_i = i
                buy_type = "W" if wave_entry else "/".join(sorted(last_t))
                if struct_stop:
                    zss = kl.zs_list.zs_lst
                    z = zss[-1] if zss else None
                    if z is None:
                        stop_price = buy_raw * 0.95
                    elif wave_entry or last_t & {"3a", "3b"}:
                        stop_price = z.high      # 三买/波浪：跌破中枢上沿 ZG 即结构破坏
                    else:
                        stop_price = z.low       # 二买：跌破中枢下沿 ZD
                elif stop:
                    stop_price = buy_raw
        else:
            hit_stop = (struct_stop or stop) and px < stop_price
            hit_sig = (not last.is_buy) and bool(last_t & DEFAULT_SELL)
            if hit_stop or hit_sig:
                sell_price = px * (1 - FEE)
                trades.append({
                    "ret": round(sell_price / buy_price - 1, 4),
                    "hold": idx - buy_idx, "buy_type": buy_type,
                    "buy_time": rows[buy_i]["time"], "buy_px": buy_raw,
                    "stop_px": round(stop_price, 3) if (struct_stop or stop) else None,
                    "sell_time": rows[i]["time"], "sell_px": px,
                    "reason": "stop" if hit_stop else "sig"})
                in_pos = False

    if in_pos:
        px = klus[-1].close
        trades.append({"ret": round(px * (1 - FEE) / buy_price - 1, 4),
                       "hold": klus[-1].idx - buy_idx, "buy_type": buy_type,
                       "buy_time": rows[buy_i]["time"], "buy_px": buy_raw,
                       "stop_px": round(stop_price, 3) if (struct_stop or stop) else None,
                       "sell_time": rows[-1]["time"], "sell_px": px,
                       "reason": "eod"})

    rets = [t["ret"] for t in trades]
    cum, peak, mdd = 1.0, 1.0, 0.0
    for r in rets:
        cum *= (1 + r)
        peak = max(peak, cum)
        mdd = min(mdd, cum / peak - 1)
    wins = [r for r in rets if r > 0]
    return {"code": code, "lv": lv, "gate": use_gate,
            "trades": len(trades),
            "win_rate": round(len(wins) / len(trades), 4) if trades else 0,
            "total_return": round(cum - 1, 4), "max_drawdown": round(mdd, 4),
            "avg_hold": round(sum(t["hold"] for t in trades) / len(trades), 1) if trades else 0,
            "detail": trades}


def summarize(tag: str, results: list):
    ok = [r for r in results if r.get("trades", 0) > 0]
    print(f"\n[{tag}] {len(results)} 只，有交易 {len(ok)} 只")
    if ok:
        wr = sum(r["win_rate"] for r in ok) / len(ok)
        tr = sum(r["total_return"] for r in ok) / len(ok)
        md = min(r["max_drawdown"] for r in ok)
        nt = sum(r["trades"] for r in ok) / len(ok)
        print(f"  平均胜率 {wr:.1%}  平均收益 {tr:.1%}  最差单票回撤 {md:.1%}  平均交易 {nt:.1f} 笔")


def run_pool(lv: str) -> None:
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    pool = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]
    variants = [
        ("① 日线趋势bi + 1H缠论", dict(use_gate=False, mtf="bi")),
        ("② 日线趋势bi + 1H缠论 + 止损", dict(use_gate=False, mtf="bi", stop=True)),
        ("③ 日线趋势bi+zs + 1H缠论 + 止损", dict(use_gate=False, mtf="both", stop=True)),
        ("④ 日线趋势bi + 1H缠论 + 止损 + ER闸", dict(use_gate=False, mtf="bi", stop=True, use_er=True)),
    ]
    for tag, kw in variants:
        results = []
        with ThreadPoolExecutor(max_workers=WORKERS) as ex:
            futs = {ex.submit(backtest_one, c, lv, **kw): c for c in pool}
            for i, f in enumerate(as_completed(futs), 1):
                results.append(f.result())
                if i % 40 == 0:
                    print(f"  [{tag}] {i}/{len(pool)}", flush=True)
        summarize(tag, results)


def run_pool3(lv: str) -> None:
    """三买（3a/3b）变体：缠论里唯一「顺势」买点，此前从未测过。"""
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    pool = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]
    b3 = {"3a", "3b"}
    variants = [
        ("⑤ 1H三买 + 止损", dict(use_gate=False, stop=True, buy_types=b3)),
        ("⑥ 1H三买 + BBI12闸 + 止损", dict(use_gate=True, stop=True, buy_types=b3)),
        ("⑦ 日线bi+zs闸 + 1H三买 + 止损", dict(use_gate=False, mtf="both", stop=True, buy_types=b3)),
    ]
    for tag, kw in variants:
        results = []
        with ThreadPoolExecutor(max_workers=WORKERS) as ex:
            futs = {ex.submit(backtest_one, c, lv, **kw): c for c in pool}
            for i, f in enumerate(as_completed(futs), 1):
                results.append(f.result())
                if i % 40 == 0:
                    print(f"  [{tag}] {i}/{len(pool)}", flush=True)
        summarize(tag, results)


def run_pool_best(lv: str) -> None:
    """正确路线验证：结构止损（ZG/ZD）+ 背驰过滤 + 三买为主。"""
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    pool = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]
    b2 = {"2", "2s"}
    b3 = {"3a", "3b"}
    b23 = {"2", "2s", "3a", "3b"}
    variants = [
        ("⑧ 三买+结构止损+BBI12闸", dict(use_gate=True, buy_types=b3, struct_stop=True)),
        ("⑨ 二买+结构止损+背驰+BBI12闸", dict(use_gate=True, buy_types=b2, struct_stop=True, require_div=True)),
        ("⑩ 二买+三买+结构止损+背驰+BBI12闸", dict(use_gate=True, buy_types=b23, struct_stop=True, require_div=True)),
        ("⑪ 三买+结构止损+日线bi+zs闸", dict(use_gate=False, mtf="both", buy_types=b3, struct_stop=True)),
    ]
    for tag, kw in variants:
        results = []
        with ThreadPoolExecutor(max_workers=WORKERS) as ex:
            futs = {ex.submit(backtest_one, c, lv, **kw): c for c in pool}
            for i, f in enumerate(as_completed(futs), 1):
                results.append(f.result())
                if i % 40 == 0:
                    print(f"  [{tag}] {i}/{len(pool)}", flush=True)
        summarize(tag, results)


def run_pool_div(lv: str) -> None:
    """重跑含背驰过滤的两个变体（其余两个不含背驰，不受索引 bug 影响）。"""
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    pool = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]
    variants = [
        ("⑨ 二买+结构止损+背驰+BBI12闸",
         dict(use_gate=True, buy_types={"2", "2s"}, struct_stop=True, require_div=True)),
        ("⑩ 二买+三买+结构止损+背驰+BBI12闸",
         dict(use_gate=True, buy_types={"2", "2s", "3a", "3b"}, struct_stop=True, require_div=True)),
    ]
    for tag, kw in variants:
        results = []
        with ThreadPoolExecutor(max_workers=WORKERS) as ex:
            futs = {ex.submit(backtest_one, c, lv, **kw): c for c in pool}
            for i, f in enumerate(as_completed(futs), 1):
                results.append(f.result())
                if i % 40 == 0:
                    print(f"  [{tag}] {i}/{len(pool)}", flush=True)
        summarize(tag, results)


def run_top(lv: str, n: int, **kw) -> None:
    """按指定口径跑全池，列出收益最高的 N 只（含最差 5 只对照）。"""
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    pool = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]
    results = []
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = {ex.submit(backtest_one, c, lv, **kw): c for c in pool}
        for i, f in enumerate(as_completed(futs), 1):
            results.append(f.result())
            if i % 60 == 0:
                print(f"  [top] {i}/{len(pool)}", flush=True)
    ok = [r for r in results if r.get("trades", 0) > 0]
    ok.sort(key=lambda r: r["total_return"], reverse=True)
    print(f"\n[Top {n}] 有交易 {len(ok)} 只 · 口径 {kw}")
    for r in ok[:n]:
        print(f"  {r['code']}  收益 {r['total_return']:+.1%}  胜率 {r['win_rate']:.1%}  "
              f"交易 {r['trades']} 笔  回撤 {r['max_drawdown']:.1%}  均持 {r.get('avg_hold', 0)}根")
    print("  --- 最差 5 只 ---")
    for r in ok[-5:]:
        print(f"  {r['code']}  收益 {r['total_return']:+.1%}  胜率 {r['win_rate']:.1%}  "
              f"交易 {r['trades']} 笔  回撤 {r['max_drawdown']:.1%}")


def run_pool_wave(lv: str) -> None:
    """缠论 × 波浪（黄金分割回调闸）池级测试。"""
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    pool = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]
    b3 = {"3a", "3b"}
    variants = [
        ("⑫ 三买+波浪闸", dict(use_gate=False, buy_types=b3, wave=True)),
        ("⑬ 波浪闸独立入场+结构止损", dict(use_gate=False, wave_entry=True, struct_stop=True)),
        ("⑭ 三买+波浪闸+BBI12+结构止损",
         dict(use_gate=True, buy_types=b3, wave=True, struct_stop=True)),
    ]
    for tag, kw in variants:
        results = []
        with ThreadPoolExecutor(max_workers=WORKERS) as ex:
            futs = {ex.submit(backtest_one, c, lv, **kw): c for c in pool}
            for i, f in enumerate(as_completed(futs), 1):
                results.append(f.result())
                if i % 40 == 0:
                    print(f"  [{tag}] {i}/{len(pool)}", flush=True)
        summarize(tag, results)


def run_pool_cci(lv: str) -> None:
    """缠论 × CCI 池级测试（三买为基准）。"""
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    pool = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]
    b3 = {"3a", "3b"}
    variants = [
        ("⑮ 三买+CCI>0闸", dict(use_gate=False, buy_types=b3, cci="pos")),
        ("⑯ 三买+CCI上穿0闸", dict(use_gate=False, buy_types=b3, cci="cross")),
        ("⑰ 三买+CCI>100闸", dict(use_gate=False, buy_types=b3, cci="strong")),
        ("⑱ 三买+CCI>0+BBI12+结构止损",
         dict(use_gate=True, buy_types=b3, cci="pos", struct_stop=True)),
    ]
    for tag, kw in variants:
        results = []
        with ThreadPoolExecutor(max_workers=WORKERS) as ex:
            futs = {ex.submit(backtest_one, c, lv, **kw): c for c in pool}
            for i, f in enumerate(as_completed(futs), 1):
                results.append(f.result())
                if i % 40 == 0:
                    print(f"  [{tag}] {i}/{len(pool)}", flush=True)
        summarize(tag, results)


def run_pool_cci_n(lv: str) -> None:
    """CCI 周期对比：14 / 20 / 40 / 88（口径统一为三买 + CCI>0）。"""
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    pool = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]
    b3 = {"3a", "3b"}
    variants = [
        ("CCI14 三买+CCI>0", dict(use_gate=False, buy_types=b3, cci="pos", cci_n=14)),
        ("CCI20 三买+CCI>0", dict(use_gate=False, buy_types=b3, cci="pos", cci_n=20)),
        ("CCI40 三买+CCI>0", dict(use_gate=False, buy_types=b3, cci="pos", cci_n=40)),
        ("CCI88 三买+CCI>0", dict(use_gate=False, buy_types=b3, cci="pos", cci_n=88)),
    ]
    for tag, kw in variants:
        results = []
        with ThreadPoolExecutor(max_workers=WORKERS) as ex:
            futs = {ex.submit(backtest_one, c, lv, **kw): c for c in pool}
            for i, f in enumerate(as_completed(futs), 1):
                results.append(f.result())
                if i % 40 == 0:
                    print(f"  [{tag}] {i}/{len(pool)}", flush=True)
        summarize(tag, results)


def run_pool_seg(lv: str) -> None:
    """分段稳健性：两个 CCI 冠军 + ⑧ 参照，各自跑前半段/后半段。"""
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    pool = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]
    b3 = {"3a", "3b"}
    configs = [
        ("A CCI14>100+BBI+结构止损",
         dict(use_gate=True, buy_types=b3, cci="strong", cci_n=14, struct_stop=True)),
        ("B CCI14上穿0+BBI+结构止损",
         dict(use_gate=True, buy_types=b3, cci="cross", cci_n=14, struct_stop=True)),
        ("C 三买+BBI+结构止损(⑧)",
         dict(use_gate=True, buy_types=b3, struct_stop=True)),
    ]
    for seg in (1, 2):
        for tag, kw in configs:
            results = []
            with ThreadPoolExecutor(max_workers=WORKERS) as ex:
                futs = {ex.submit(backtest_one, c, lv, seg=seg, **kw): c for c in pool}
                for i, f in enumerate(as_completed(futs), 1):
                    results.append(f.result())
                    if i % 60 == 0:
                        print(f"  [{tag} seg{seg}] {i}/{len(pool)}", flush=True)
            summarize(f"{tag} · {'前半段' if seg == 1 else '后半段'}", results)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("code", nargs="?", default="")
    ap.add_argument("--lv", default="1h", choices=["1h", "day"])
    ap.add_argument("--pool", action="store_true")
    ap.add_argument("--pool3", action="store_true", help="三买(3a/3b)变体池级测试")
    ap.add_argument("--pool-best", action="store_true", help="结构止损+背驰过滤 池级测试")
    ap.add_argument("--pool-div", action="store_true", help="仅重跑含背驰过滤的变体")
    ap.add_argument("--top", type=int, default=0, help="按当前口径列出收益前 N 的股票")
    ap.add_argument("--buy3", action="store_true", help="单票用三买替代默认一买/二买")
    ap.add_argument("--stop", action="store_true", help="固定止损：跌破买入价离场")
    ap.add_argument("--struct-stop", action="store_true", help="结构止损：三买跌破 ZG / 二买跌破 ZD")
    ap.add_argument("--div", action="store_true", help="底背驰过滤（仅作用于一二买）")
    ap.add_argument("--er", action="store_true", help="ER(60) 自身时序 70 分位入场闸")
    ap.add_argument("--mtf", default="", choices=["", "bi", "zs", "both"],
                    help="日线趋势闸：bi=最后一笔向上 / zs=中枢上方 / both=两者皆需")
    ap.add_argument("--bbi", action="store_true", help="叠加 1H BBI12 上行闸")
    ap.add_argument("--wave", action="store_true", help="波浪黄金分割闸（回调30%~70%+更高低点）")
    ap.add_argument("--wave-entry", action="store_true", help="波浪闸独立入场（不要求缠论买点）")
    ap.add_argument("--pool-wave", action="store_true", help="缠论×波浪 池级三变体")
    ap.add_argument("--cci", default="", choices=["", "pos", "cross", "strong"],
                    help="CCI(20)闸：pos>0 / cross近3根上穿0 / strong>100")
    ap.add_argument("--pool-cci", action="store_true", help="缠论×CCI 池级四变体")
    ap.add_argument("--cci-n", type=int, default=20, help="CCI 周期（默认 20）")
    ap.add_argument("--pool-cci-n", action="store_true", help="CCI 周期对比 14/20/40/88")
    ap.add_argument("--seg", type=int, default=0, choices=[0, 1, 2],
                    help="分段回测：1=前半段 / 2=后半段")
    ap.add_argument("--pool-seg", action="store_true", help="分段稳健性检验")
    args = ap.parse_args()

    if args.pool:
        run_pool(args.lv)
    elif args.pool3:
        run_pool3(args.lv)
    elif args.pool_best:
        run_pool_best(args.lv)
    elif args.pool_div:
        run_pool_div(args.lv)
    elif args.pool_wave:
        run_pool_wave(args.lv)
    elif args.pool_cci:
        run_pool_cci(args.lv)
    elif args.pool_cci_n:
        run_pool_cci_n(args.lv)
    elif args.pool_seg:
        run_pool_seg(args.lv)
    elif args.top > 0:
        bt = {"3a", "3b"} if args.buy3 else None
        run_top(args.lv, args.top, use_gate=args.bbi, stop=args.stop, use_er=args.er,
                mtf=args.mtf, buy_types=bt, struct_stop=args.struct_stop,
                require_div=args.div)
    elif args.code:
        bt = {"3a", "3b"} if args.buy3 else None
        r = backtest_one(args.code, args.lv, args.bbi, args.stop, args.er, args.mtf,
                         bt, args.struct_stop, args.div,
                         wave=args.wave, wave_entry=args.wave_entry, cci=args.cci,
                         cci_n=args.cci_n, seg=args.seg)
        print(f"[{r['code']}] 买点={'三买' if args.buy3 else '一/二买'} 日线闸={args.mtf or '-'} "
              f"BBI={args.bbi} 固定止损={args.stop} 结构止损={args.struct_stop} 背驰={args.div} "
              f"lv={r['lv']} 交易 {r['trades']} 笔 胜率 {r['win_rate']:.1%} "
              f"收益 {r['total_return']:.1%} 回撤 {r['max_drawdown']:.1%} "
              f"均持 {r.get('avg_hold', 0)}根")
        for t in r["detail"]:
            print(f"    {t}")
    else:
        ap.print_help()
