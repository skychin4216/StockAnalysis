# -*- coding: utf-8 -*-
"""近期市场状态与选股赢率下滑归因（可作为模块导入，供远程控制 backtest.quarter 复用）"""
import json
import glob
import os

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    # 1) 指数近期走势
    cache = json.load(open(os.path.join(HERE, "_kline_cache.json"), encoding="utf-8"))
    print("===== 指数近期走势 =====")
    for idx in ["sh000001", "sz399001", "sz399006"]:
        ent = cache.get(idx) or {}
        snaps = ent.get("snaps") or []
        if len(snaps) < 30:
            continue
        last = snaps[-1]
        print(f"{idx} {ent.get('name','')} 最新 {last['date']} close={last['close']}")
        for n in [5, 10, 20, 60]:
            if len(snaps) >= n:
                base = snaps[-n]["close"]
                print(f"  近{n}日 {(last['close']/base-1)*100:+.2f}%")

    # 2) 月度市场状态分布
    print("\n===== 近14个月市场状态分布（超短口径）=====")
    fs = sorted(glob.glob(os.path.join(HERE, "_records", "selected_*.json")))
    for f in fs[-14:]:
        d = json.load(open(f, encoding="utf-8"))
        sd = (d.get("state_dist") or {}).get("超短") or {}
        mm = os.path.basename(f)[9:16]
        print(f"{mm}  {json.dumps(sd, ensure_ascii=False)}")

    # 3) 月度交易胜率/笔数
    print("\n===== 月度交易笔数与胜率 =====")
    for f in fs[-14:]:
        d = json.load(open(f, encoding="utf-8"))
        tr = d.get("trades") or {}
        total = 0
        wins = 0
        rets = []
        per = []
        for p, arr in tr.items():
            if not isinstance(arr, list):
                continue
            arr = [t for t in arr if isinstance(t, dict)]
            total += len(arr)
            wins += sum(1 for t in arr if (t.get("ret") or 0) > 0)
            rets += [(t.get("ret") or 0) for t in arr]
            if arr:
                avg = sum((t.get("ret") or 0) for t in arr) / len(arr)
                per.append(f"{p}{len(arr)}笔/均{avg:+.2f}%")
        mm = os.path.basename(f)[9:16]
        wr = round(100 * wins / max(total, 1), 1)
        avg = round(sum(rets) / max(len(rets), 1), 2) if rets else 0
        print(f"{mm}  共{total}笔 胜率{wr}% 平均{avg:+.2f}%  |  {' '.join(per)}")


if __name__ == "__main__":
    main()
