# -*- coding: utf-8 -*-
"""pool2 汇总：① 50 TREND 底仓分档右尾挽回 ② 5组固定默认参数全池对比 ③ 最新建议分布"""
import csv, numpy as np

rows = []
for r in csv.DictReader(open(r"data\review_tables\ticket_profile_pool2.csv", encoding="utf-8-sig")):
    try:
        for k in ("bench","b_total","b_mdd","b03_t","b03_m","b05_t","b05_m","b07_t","b07_m","b10_t","b10_m",
                  "f1_t","f1_m","f2_t","f2_m","f3_t","f3_m","f4_t","f4_m","f5_t","f5_m"):
            r[k] = float(r[k])
        rows.append(r)
    except Exception:
        pass

def med(a): return float(np.median(a)) if a else 0.0

# ── 1. 底仓分档 ──
print("="*80)
print("【底仓比例】收益中位 / 回撤中位（base=0 是纯波段混合T；base=1.0 是满仓只认金叉死叉）")
print("="*80)
for label in ("TREND","MIXED"):
    sub = [r for r in rows if r["label"]==label]
    print(f"\n--- {label} 组（{len(sub)} 只）---")
    print(f"{'档位':>8}{'收益中位':>10}{'回撤中位':>10}{'vs基准0收益差':>13}{'回撤更小占比':>13}")
    b0t, b0m = med([r["b_total"] for r in sub]), med([r["b_mdd"] for r in sub])
    print(f"{'base=0':>8}{b0t:>+9.1f}%{b0m:>9.1f}%{'—':>13}{'—':>13}")
    for key_t, key_m, name in (("b03_t","b03_m","base=30%"),("b05_t","b05_m","base=50%"),
                                ("b07_t","b07_m","base=70%"),("b10_t","b10_m","base=100%")):
        t = med([r[key_t] for r in sub]); m = med([r[key_m] for r in sub])
        better_mdd = sum(1 for r in sub if r[key_m] < r["b_mdd"])/len(sub)*100
        print(f"{name:>8}{t:>+9.1f}%{m:>9.1f}%{t-b0t:>+12.1f}%{better_mdd:>12.0f}%")
    print(f"{'买持':>8}{med([r['bench'] for r in sub]):>+9.1f}%")

# ── 2. 五组固定默认参数 ──
print("\n"+"="*80)
print("【5组固定默认参数】全池统一口径（每组对每只票跑同一参数）")
print("="*80)
names = {"f1":"t10/20+c60","f2":"t5/10+c60","f3":"t20/60+c60","f4":"t30/60+c60","f5":"t5/10+c20"}
print(f"{'参数':>14}{'全池收益中位':>12}{'TREND中位':>11}{'MIXED中位':>11}{'回撤中位':>10}{'跑赢自选票':>11}")
for f, name in names.items():
    t_all = med([r[f"{f}_t"] for r in rows])
    t_tr  = med([r[f"{f}_t"] for r in rows if r["label"]=="TREND"])
    t_mx  = med([r[f"{f}_t"] for r in rows if r["label"]=="MIXED"])
    m_all = med([r[f"{f}_m"] for r in rows])
    # 跑赢自选最优的比例（自选最优收益 b_total）
    win = sum(1 for r in rows if r[f"{f}_t"] >= r["b_total"])/len(rows)*100
    print(f"{name:>14}{t_all:>+11.1f}%{t_tr:>+10.1f}%{t_mx:>+10.1f}%{m_all:>9.1f}%{win:>10.0f}%")

b_best = med([r["b_total"] for r in rows])
print(f"{'自选最优':>14}{b_best:>+11.1f}%（每只票挑自己最优参数，作为上限参照）")

# ── 3. 最新建议分布 ──
from collections import Counter
c = Counter(r["latest"] for r in csv.DictReader(open(r"data\review_tables\ticket_profile_pool2.csv", encoding="utf-8-sig")))
print("\n【最新一日建议分布】", dict(c))
