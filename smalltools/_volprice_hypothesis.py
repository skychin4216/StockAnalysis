# -*- coding: utf-8 -*-
"""量价速率两条因果命题的条件事件检验

命题（视频口径，用户原话）：
  ① 上涨速率在下降 → 很可能迎来下跌
  ② 下跌速率遇到很大阻碍（跌不动/缩量） → 快要上涨

注意口径歧义：「上涨速率」在本页图②中有两种读法，全部测：
  I-A 橙线值下降 = 每推动 1% 涨幅消耗的成交量在减少（推动变省力）
  I-B 橙线见顶回落 = 先费量推涨（升），后回落（冲高回落）
  I-C 价格涨速下降 = 近5日平均涨幅 < 前5日平均涨幅（价格动量衰减）

「下跌速率遇阻」读法：
  II-A 蓝线值下降 = 每砸 1% 跌幅消耗的量在减少（砸不出量）
  II-B 蓝线冲高后回落 = 恐慌放量砸盘后缩量（抛压被吸完）
  II-C 蓝线走低且处于低位分位

方法：对每个 bar 计算前瞻收益 fwd5/10/20，按信号分桶，与全样本基线对比；
      报告样本数、均值、胜率、相对基线的超额(lift)。不做交易回测。
"""
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from _chanlun_engine import load_klines                    # noqa: E402
from _chanlun_backtest import POOL_JSON, WORKERS           # noqa: E402

HORIZONS = (5, 10, 20)
LOOKBACK = 25


def compute(rows):
    n = len(rows)
    closes = [r["close"] for r in rows]
    vols = [r["volume"] / 1e6 for r in rows]
    pct = [0.0] * n
    rate = [0.0] * n
    for i in range(1, n):
        pct[i] = (closes[i] - closes[i - 1]) / closes[i - 1] * 100.0
        rate[i] = vols[i] / max(abs(pct[i]), 0.05)
    cap = sorted(rate)[int(n * 0.95)] if n > 40 else (max(rate) or 1.0)
    rate = [min(v, cap) for v in rate]
    up5 = [0.0] * n
    dn5 = [0.0] * n
    for i in range(n):
        f = max(0, i - 4)
        us = [rate[j] for j in range(f, i + 1) if j == 0 or closes[j] >= closes[j - 1]]
        ds = [rate[j] for j in range(f, i + 1) if j > 0 and closes[j] < closes[j - 1]]
        up5[i] = sum(us) / len(us) if us else (up5[i - 1] if i else rate[0])
        dn5[i] = sum(ds) / len(ds) if ds else (dn5[i - 1] if i else rate[0])
    return closes, pct, up5, dn5, rate


def slope3(s, i):
    """近3根斜率（相对变化）"""
    b = s[i - 3]
    return (s[i] - b) / b if b > 0 else 0.0


def analyze(code):
    rows = load_klines(code, "day")
    if not rows or len(rows) < 200:
        return None
    rows = [r for r in rows if r.get("volume", 0) > 0]
    if len(rows) < 200:
        return None
    closes, pct, up5, dn5, rate = compute(rows)
    n = len(closes)
    maxh = max(HORIZONS)

    buckets = {}

    def add(name, i):
        for h in HORIZONS:
            if i + h >= n:
                return
        buckets.setdefault(name, []).append(i)

    for i in range(LOOKBACK + 3, n - maxh):
        lo20 = min(closes[i - 19:i + 1])
        hi20 = max(closes[i - 19:i + 1])
        pos = (closes[i] - lo20) / (hi20 - lo20) if hi20 > lo20 else 0.5

        su = slope3(up5, i)
        sd = slope3(dn5, i)
        # 前3根是否在上升（用于"冲高回落"判定）
        up_rising_before = up5[i - 3] > up5[i - 6] * 1.05
        dn_rising_before = dn5[i - 3] > dn5[i - 6] * 1.05
        # 价格动量（近5日均涨幅 vs 前5日）
        mom_now = sum(pct[i - 4:i + 1]) / 5.0
        mom_prev = sum(pct[i - 9:i - 4]) / 5.0

        add("BASE", i)

        # ① 上涨速率
        if su < -0.10:
            add("A_橙线下降", i)
        if su < -0.10 and up_rising_before:
            add("B_橙线冲高回落", i)
        if su < -0.10 and pos >= 0.5:
            add("A_橙线下降_高位", i)
        if mom_now < mom_prev - 0.3:
            add("C_价格涨速下降", i)
        if mom_now < mom_prev - 0.3 and pos >= 0.5:
            add("C_价格涨速下降_高位", i)

        # ② 下跌速率遇阻
        if sd < -0.10:
            add("IIA_蓝线下降", i)
        if sd < -0.10 and dn_rising_before:
            add("IIB_蓝线冲高回落", i)
        if sd < -0.10 and pos <= 0.5:
            add("IIA_蓝线下降_低位", i)
        if sd < -0.10 and dn_rising_before and pos <= 0.5:
            add("IIB_蓝线冲高回落_低位", i)

        # ── 结构化强条件（更贴视频"关键位/冲击之后"的口径）──
        hi60 = max(closes[max(0, i - 59):i + 1])
        lo60 = min(closes[max(0, i - 59):i + 1])
        at_high = closes[i] >= hi60 * 0.995
        at_low = closes[i] <= lo60 * 1.005
        up_peak = max(up5[i - 10:i]) if i >= 10 else up5[i]
        dn_peak = max(dn5[i - 20:i]) if i >= 20 else dn5[i]

        # ① 冲高后费量推涨见顶：创新高附近 + 橙线从近10日峰值回落20%以上
        if at_high and up_peak > 0 and up5[i] < up_peak * 0.80:
            add("S1_新高_橙线自峰值回落20%", i)
        # ② 创新高但橙线反而低于峰值（价升量效率恶化=背离）
        if at_high and up_peak > 0 and up5[i] < up_peak * 0.90 and up5[i] > 0:
            add("S1b_新高_橙线低于峰值", i)
        # ③ 深跌后蓝线塌缩至近20日峰值的50%以下（砸不出量）
        if at_low and dn_peak > 0 and dn5[i] < dn_peak * 0.50:
            add("S2_新低_蓝线塌缩50%", i)
        # ④ 深跌后蓝线冲高再回落（恐慌量被吸完）
        if at_low and dn_rising_before and sd < -0.10:
            add("S2b_新低_蓝线冲高回落", i)

        # ── 对照：只按位置、不看量（分离"新低天然反弹/新高天然回落"的贡献）──
        if at_low:
            add("CTRL_新低仅位置", i)
            if dn_peak > 0 and dn5[i] >= dn_peak * 0.50:
                add("CTRL_新低_蓝线未塌缩", i)
        if at_high:
            add("CTRL_新高仅位置", i)
            if up_peak > 0 and up5[i] >= up_peak * 0.80:
                add("CTRL_新高_橙线未回落", i)

    out = {}
    for name, idxs in buckets.items():
        rec = {"n": len(idxs)}
        for h in HORIZONS:
            rs = [closes[i + h] / closes[i] - 1.0 for i in idxs]
            rec[f"fwd{h}"] = sum(rs) / len(rs) if rs else 0.0
            rec[f"win{h}"] = sum(1 for r in rs if r > 0) / len(rs) if rs else 0.0
        out[name] = rec
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if args:
        codes = [args[0]]
    else:
        p = json.load(open(POOL_JSON, encoding="utf-8"))
        codes = [s[2:] if s[:2] in ("sh", "sz") else s for s in p["stocks"]]

    agg = {}
    cnt = 0
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = {ex.submit(analyze, c): c for c in codes}
        for i, f in enumerate(as_completed(futs), 1):
            r = f.result()
            if not r:
                continue
            cnt += 1
            for name, rec in r.items():
                a = agg.setdefault(name, {"n": 0, **{f"s{h}": 0.0 for h in HORIZONS},
                                          **{f"w{h}": 0.0 for h in HORIZONS}})
                a["n"] += rec["n"]
                for h in HORIZONS:
                    a[f"s{h}"] += rec[f"fwd{h}"] * rec["n"]
                    a[f"w{h}"] += rec[f"win{h}"] * rec["n"]
            if i % 40 == 0:
                print(f"  {i}/{len(codes)}", flush=True)

    base = agg.get("BASE")
    if not base:
        print("无数据")
        return
    for name, a in agg.items():
        for h in HORIZONS:
            a[f"s{h}"] /= a["n"]
            a[f"w{h}"] /= a["n"]

    order = ["A_橙线下降", "A_橙线下降_高位", "B_橙线冲高回落", "C_价格涨速下降",
             "C_价格涨速下降_高位", "IIA_蓝线下降", "IIA_蓝线下降_低位",
             "IIB_蓝线冲高回落", "IIB_蓝线冲高回落_低位",
             "S1_新高_橙线自峰值回落20%", "S1b_新高_橙线低于峰值",
             "CTRL_新高仅位置", "CTRL_新高_橙线未回落",
             "S2_新低_蓝线塌缩50%", "S2b_新低_蓝线冲高回落",
             "CTRL_新低仅位置", "CTRL_新低_蓝线未塌缩"]

    print(f"\n股票数 {cnt}｜全样本基线 n={base['n']}")
    print(f"基线：fwd5 {base['s5']*100:+.2f}% (胜率{base['w5']*100:.1f}%)｜"
          f"fwd10 {base['s10']*100:+.2f}% ({base['w10']*100:.1f}%)｜"
          f"fwd20 {base['s20']*100:+.2f}% ({base['w20']*100:.1f}%)")
    print("\n{:<26}{:>8}{:>11}{:>9}{:>11}{:>9}{:>11}{:>9}".format(
        "信号", "样本", "fwd5", "胜率", "fwd10", "胜率", "fwd20", "胜率"))
    for name in order:
        a = agg.get(name)
        if not a or a["n"] < 100:
            continue
        print("{:<26}{:>8}{:>10.2f}%{:>8.1f}%{:>10.2f}%{:>8.1f}%{:>10.2f}%{:>8.1f}%".format(
            name, a["n"], a["s5"] * 100, a["w5"] * 100,
            a["s10"] * 100, a["w10"] * 100, a["s20"] * 100, a["w20"] * 100))

    out = os.path.join(HERE, "_records", "_volprice_hypothesis.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump({"stocks": cnt, "base": base, "signals": {k: agg[k] for k in order if k in agg}},
              open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"\n已存 {out}")


if __name__ == "__main__":
    main()
