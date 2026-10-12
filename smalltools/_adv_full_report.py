# -*- coding: utf-8 -*-
"""全池 172 只逐K建议明细完整报告：
1. 四类建议（BUY/SELL/HOLD_POS/HOLD_EMPTY）全池胜率+收益
2. 按票性（TREND/MIXED）分组的建议质量
3. 失败建议归因：哪些建议最容易错、错在哪
4. 最近10天全池建议分布 + 命中情况
"""
import csv, numpy as np
from collections import defaultdict, Counter

DETAIL = r"data\review_tables\ticket_advice_detail.csv"
POOL2 = r"data\review_tables\ticket_profile_pool2.csv"

# 读票性
label_of = {}
for r in csv.DictReader(open(POOL2, encoding="utf-8-sig")):
    label_of[r["code"]] = r["label"]

rows = [r for r in csv.DictReader(open(DETAIL, encoding="utf-8-sig"))
        if r["r1"] != "" and r["r3"] != "" and r["r5"] != ""]
for r in rows:
    r["r1"] = float(r["r1"]); r["r3"] = float(r["r3"]); r["r5"] = float(r["r5"])
    r["label"] = label_of.get(r["code"], "?")

codes = set(r["code"] for r in rows)
print(f"全池 {len(codes)} 只票，{len(rows)} 根有效日K建议\n")

want_up = {"BUY": True, "SELL": False, "HOLD_POS": True, "HOLD_EMPTY": False}

def stat(d):
    r1s = [x["r1"] for x in d]; r3s = [x["r3"] for x in d]; r5s = [x["r5"] for x in d]
    def w(arr, up):
        return sum(1 for x in arr if (x > 0) == up) / len(arr) * 100 if arr else 0
    return len(d), w(r1s, True), w(r3s, True), w(r5s, True), np.mean(r5s)

# ── 1. 全池四类建议 ──
print("="*78)
print("【1】全池四类建议成功率")
print("="*78)
print(f"{'建议':>11}{'样本':>8}{'1日胜率':>9}{'3日胜率':>9}{'5日胜率':>9}{'5日均收益':>11}{'盈亏比':>9}")
print("-"*78)
for adv in ("BUY","SELL","HOLD_POS","HOLD_EMPTY"):
    d = [r for r in rows if r["advice"]==adv]
    if not d: continue
    n, w1, w3, w5, m5 = stat(d)
    # 盈亏比：BUY 看上涨幅度/下跌幅度；SELL 取反
    r5s = [x["r5"] for x in d]
    if adv == "SELL": r5s = [-x for x in r5s]
    wins = [x for x in r5s if x > 0]; losses = [abs(x) for x in r5s if x < 0]
    pf = (np.mean(wins)/np.mean(losses)) if wins and losses else 0
    m5_disp = m5 if adv != "SELL" else -m5
    print(f"{adv:>11}{n:>8}{w1:>8.1f}%{w3:>8.1f}%{w5:>8.1f}%{m5_disp:>+10.2f}%{pf:>8.2f}")

# ── 2. 按票性分组 ──
print("\n"+"="*78)
print("【2】按票性分组（TREND vs MIXED vs OSCILLATION）")
print("="*78)
for label in ("TREND","MIXED","OSCILLATION","SLOWBULL"):
    sub = [r for r in rows if r["label"]==label]
    if not sub: continue
    print(f"\n--- {label} ({len(set(r['code'] for r in sub))} 只，{len(sub)} 根) ---")
    print(f"{'建议':>11}{'样本':>8}{'1日胜率':>9}{'3日胜率':>9}{'5日胜率':>9}{'5日均收益':>11}")
    for adv in ("BUY","SELL","HOLD_POS","HOLD_EMPTY"):
        d = [r for r in sub if r["advice"]==adv]
        if not d: continue
        n, w1, w3, w5, m5 = stat(d)
        m5_disp = m5 if adv != "SELL" else -m5
        print(f"{adv:>11}{n:>8}{w1:>8.1f}%{w3:>8.1f}%{w5:>8.1f}%{m5_disp:>+10.2f}%")

# ── 3. 失败建议归因：错的建议后续表现 ──
print("\n"+"="*78)
print("【3】失败建议归因（方向错误的样本，其 5 日平均反向幅度）")
print("="*78)
for adv in ("BUY","SELL"):
    up = want_up[adv]
    d = [r for r in rows if r["advice"]==adv]
    wrong = [r for r in d if (r["r5"] > 0) != up]
    right = [r for r in d if (r["r5"] > 0) == up]
    if not wrong: continue
    wm = np.mean([abs(r["r5"]) for r in wrong])
    rm = np.mean([abs(r["r5"]) for r in right])
    print(f"{adv}: 对 {len(right)} (均幅 {rm:.2f}%) / 错 {len(wrong)} (均幅 {wm:.2f}%) / 盈亏比 {rm/wm:.2f}")

# ── 4. 最近10天全池建议分布 + 实际方向 ──
print("\n"+"="*78)
print("【4】最近 10 个交易日全池建议分布")
print("="*78)
dates = sorted(set(r["date"] for r in rows))[-10:]
print(f"{'日期':>10}{'🟢买':>7}{'🔴卖':>7}{'🔵持':>7}{'⚪望':>7}{'买入3日对率':>13}{'卖出3日对率':>13}")
for dt in dates:
    day = [r for r in rows if r["date"]==dt]
    cnt = Counter(r["advice"] for r in day)
    buy = [r for r in day if r["advice"]=="BUY"]
    sell = [r for r in day if r["advice"]=="SELL"]
    bw = sum(1 for r in buy if r["r3"]>0)/len(buy)*100 if buy else 0
    sw = sum(1 for r in sell if r["r3"]<0)/len(sell)*100 if sell else 0
    print(f"{dt:>10}{cnt['BUY']:>7}{cnt['SELL']:>7}{cnt['HOLD_POS']:>7}{cnt['HOLD_EMPTY']:>7}{bw:>12.0f}%{sw:>12.0f}%")
