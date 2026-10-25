# -*- coding: utf-8 -*-
"""缠论买卖点回测
=========================
基于 chan.py step_load 逐根回放（无未来信息），买卖点策略回测。

规则（可配）：
  买入：出现 is_buy 且 is_sure 且类型∈buy_types（默认一买+二买）
  卖出：出现 is_sell 且 is_sure 且类型∈sell_types，或固定止损/止盈
费用：佣金+印花税+滑点 FEE=0.0013（双边）

用法：
  python smalltools/_chanlun_backtest.py 600188
  python smalltools/_chanlun_backtest.py 600188 --lv day --tp 0.08 --sl 0.05
  python smalltools/_chanlun_backtest.py --pool   # 核心池批量
"""
import argparse
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from _chanlun_engine import CHAN_DIR, LV_MAP, load_klines, _to_klu  # noqa: E402

sys.path.insert(0, CHAN_DIR)
from Chan import CChan                  # noqa: E402
from ChanConfig import CChanConfig      # noqa: E402

OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "data", "chanlun", "backtest")
POOL_JSON = os.path.join(ROOT, "app", "src", "main", "assets", "core_stock_pool.json")
FEE = 0.0013
WORKERS = 8

DEFAULT_BUY = {"1", "1p", "2", "2s"}
DEFAULT_SELL = {"1", "1p", "2", "2s", "3a", "3b"}


def backtest_one(code: str, lv: str = "1h", buy_types=None, sell_types=None,
                 tp: float = 0.0, sl: float = 0.0, max_hold: int = 0) -> dict:
    """单票回测。tp/sl 为固定止盈/止损比例（0=不启用），max_hold 最大持仓 K 线数。"""
    buy_types = buy_types or DEFAULT_BUY
    sell_types = sell_types or DEFAULT_SELL
    kl_type, _ = LV_MAP[lv]
    rows = load_klines(code, lv)
    if len(rows) < 60:
        return {"code": code, "lv": lv, "error": "数据不足", "trades": 0}

    chan = CChan(code=code, lv_list=[kl_type], config=CChanConfig({"trigger_step": True}))
    klus = [_to_klu(r, lv) for r in rows]

    trades = []
    in_pos = False
    buy_price = 0.0
    buy_idx = -1
    buy_type = ""

    # 单实例增量喂：逐根 trigger_load（无未来信息）
    for i in range(len(klus)):
        chan.trigger_load({kl_type: [klus[i]]})
        kl = chan.kl_datas[kl_type]
        bsps = [b for b in kl.bs_point_lst.bsp_store_flat_dict.values() if b.bi.is_sure]
        if not bsps:
            continue
        last = max(bsps, key=lambda b: b.bi.idx)
        last_t = {t.value for t in last.type}
        px = klus[i].close
        idx = klus[i].idx

        if not in_pos:
            if last.is_buy and last_t & buy_types:
                in_pos = True
                buy_price = px * (1 + FEE)
                buy_idx = idx
                buy_type = "/".join(sorted(last_t))
        else:
            hit_tp = tp > 0 and px >= buy_price * (1 + tp)
            hit_sl = sl > 0 and px <= buy_price * (1 - sl)
            hit_sig = (not last.is_buy) and bool(last_t & sell_types)
            hit_hold = max_hold > 0 and (idx - buy_idx) >= max_hold
            if hit_tp or hit_sl or hit_sig or hit_hold:
                sell_price = px * (1 - FEE)
                ret = sell_price / buy_price - 1
                reason = "tp" if hit_tp else "sl" if hit_sl else "sig" if hit_sig else "hold"
                trades.append({
                    "buy_idx": buy_idx, "sell_idx": idx,
                    "buy_price": round(buy_price, 3), "sell_price": round(sell_price, 3),
                    "ret": round(ret, 4), "hold_bars": idx - buy_idx,
                    "buy_type": buy_type, "reason": reason,
                })
                in_pos = False

    if in_pos:
        px = klus[-1].close
        trades.append({"buy_idx": buy_idx, "sell_idx": klus[-1].idx,
                       "buy_price": round(buy_price, 3), "sell_price": round(px, 3),
                       "ret": round(px * (1 - FEE) / buy_price - 1, 4),
                       "hold_bars": klus[-1].idx - buy_idx,
                       "buy_type": buy_type, "reason": "open"})

    wins = [t for t in trades if t["ret"] > 0]
    rets = [t["ret"] for t in trades]
    # 收益曲线 & 回撤
    cum = 1.0
    peak = 1.0
    mdd = 0.0
    for r in rets:
        cum *= (1 + r)
        peak = max(peak, cum)
        mdd = min(mdd, cum / peak - 1)

    return {
        "code": code, "lv": lv,
        "buy_types": sorted(buy_types), "sell_types": sorted(sell_types),
        "tp": tp, "sl": sl, "max_hold": max_hold,
        "trades": len(trades), "win_rate": round(len(wins) / len(trades), 4) if trades else 0,
        "total_return": round(cum - 1, 4), "max_drawdown": round(mdd, 4),
        "avg_hold_bars": round(sum(t["hold_bars"] for t in trades) / len(trades), 1) if trades else 0,
        "avg_ret": round(sum(rets) / len(rets), 4) if rets else 0,
        "detail": trades,
    }


def run_pool(lv: str, tp: float, sl: float, max_hold: int) -> list:
    p = json.load(open(POOL_JSON, encoding="utf-8"))
    pool = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]
    results = []
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = {ex.submit(backtest_one, c, lv, None, None, tp, sl, max_hold): c for c in pool}
        for i, f in enumerate(as_completed(futs), 1):
            r = f.result()
            results.append(r)
            if i % 20 == 0:
                print(f"  进度 {i}/{len(pool)}", flush=True)
    ok = [r for r in results if r.get("trades", 0) > 0]
    print(f"\n池回测：{len(results)} 只，有交易 {len(ok)} 只")
    if ok:
        wr = sum(r["win_rate"] for r in ok) / len(ok)
        tr = sum(r["total_return"] for r in ok) / len(ok)
        md = min(r["max_drawdown"] for r in ok)
        print(f"平均胜率 {wr:.1%}  平均收益 {tr:.1%}  最大回撤 {md:.1%}")
    return results


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("code", nargs="?", default="")
    ap.add_argument("--lv", default="1h", choices=["1h", "day"])
    ap.add_argument("--tp", type=float, default=0.0, help="固定止盈比例")
    ap.add_argument("--sl", type=float, default=0.0, help="固定止损比例")
    ap.add_argument("--max-hold", type=int, default=0, help="最大持仓K线数")
    ap.add_argument("--pool", action="store_true")
    args = ap.parse_args()

    if args.pool:
        run_pool(args.lv, args.tp, args.sl, args.max_hold)
    elif args.code:
        r = backtest_one(args.code, args.lv, tp=args.tp, sl=args.sl, max_hold=args.max_hold)
        os.makedirs(OUT_DIR, exist_ok=True)
        out = os.path.join(OUT_DIR, f"{args.code}_{args.lv}.json")
        with open(out, "w", encoding="utf-8") as f:
            json.dump(r, f, ensure_ascii=False, indent=1)
        print(f"[{r['code']}] lv={r['lv']} 交易 {r['trades']} 笔 胜率 {r['win_rate']:.1%} "
              f"收益 {r['total_return']:.1%} 回撤 {r['max_drawdown']:.1%} 均持 {r['avg_hold_bars']}根")
        for t in r["detail"][-5:]:
            print(f"  买@bar{t['buy_idx']} {t['buy_price']}({t['buy_type']}) → "
                  f"卖@bar{t['sell_idx']} {t['sell_price']} ret={t['ret']:.1%} [{t.get('reason','')}]")
        print(f"已写: {out}")
    else:
        ap.print_help()
