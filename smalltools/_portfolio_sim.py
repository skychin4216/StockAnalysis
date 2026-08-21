# -*- coding: utf-8 -*-
"""
AutoTradePortfolioEngine 原型验证
用真实缓存K线，模拟 100 万资金在 8/11-8/13 的：
1. 趋势跟随选股（超短/短）
2. 自动建仓（单只≤20%即20万、持仓≤5、一次≤3、保留10%现金）
3. 做T/反T自动执行（中线做T为主，振幅≥2%）
4. 止盈/止损 + 腾笼换鸟（新票不强不换）
"""
import json

CACHE = "smalltools/_kline_cache.json"

def ma(v, n):
    if len(v) < n: return None
    return sum(v[-n:]) / n

def load_cache():
    with open(CACHE, encoding="utf-8") as f:
        return json.load(f)

def filter_asof(snaps, asof):
    return [s for s in snaps if s["date"] <= asof]

def is_st(name):
    return "ST" in str(name).upper()

# ── 配置（与 Kotlin AutoTradePortfolioEngine 一致） ──
TOTAL = 1_000_000
MAX_HOLD = 5
MAX_OPEN = 3
SINGLE_RATIO = 0.20
MIN_CASH_RATIO = 0.10
T_RATIO = 0.40

STOP = {"UltraShortQuant": -2.0, "ShortTermQuant": -5.0, "MidTermQuant": -8.0, "LongTermQuant": -10.0}
MIN_AMP = {"UltraShortQuant": 2.5, "ShortTermQuant": 2.0, "MidTermQuant": 2.0, "LongTermQuant": 4.0}

def trend_score(snaps):
    """超短/短趋势跟随：6项，过≥5且当日涨。mode 区分超短(3线)/短(4线)"""
    if len(snaps) < 20: return False, 0, {}
    latest = snaps[-1]
    closes = [s["close"] for s in snaps]
    name = latest.get("name", "")
    if is_st(name): return False, 0, {"f": "ST"}
    ma5, ma10, ma20 = ma(closes,5), ma(closes,10), ma(closes,20)
    ma60 = ma(closes,60)
    res = {}
    res["多头"] = (ma5 > ma10 > ma20) and (ma60 is None or ma20 > ma60)
    hi20 = max(s["high"] for s in snaps[-20:])
    dd = (hi20 - latest["close"])/hi20*100 if hi20>0 else 99
    res["贴新高"] = dd < 8.0
    vols = [s["volume"] for s in snaps]
    vol5 = sum(vols[-6:-1])/5 if len(vols)>=6 else vols[-1]
    vr = latest["volume"]/vol5 if vol5>0 else 1
    res["放量"] = vr >= 1.2
    chg = latest.get("changePct",0) or 0
    res["上涨"] = chg > 0
    closes_p = closes[:-1]
    ma20_prev = ma(closes_p,20)
    res["MA20上行"] = ma20_prev is not None and ma20 > ma20_prev
    res["站5日"] = latest["close"] > ma5
    score = sum(1 for v in res.values() if v)
    passed = score >= 5 and chg > 0
    return passed, score, {"dd": dd, "vr": vr, "chg": chg, "score": score}

def strength_score(snaps, pnl):
    if len(snaps) < 20: return 0
    closes = [s["close"] for s in snaps]
    latest = snaps[-1]
    s = 0
    ma5, ma10, ma20 = ma(closes,5), ma(closes,10), ma(closes,20)
    if ma5 > ma10 > ma20: s += 30
    elif ma5 > ma10: s += 15
    hi20 = max(x["high"] for x in snaps[-20:])
    dd = (hi20-latest["close"])/hi20*100 if hi20>0 else 99
    if dd < 3: s += 25
    elif dd < 8: s += 15
    if len(closes)>=6:
        chg5 = (latest["close"]-closes[-6])/closes[-6]*100
        if chg5>5: s += 20
        elif chg5>0: s += 10
    if pnl>5: s += 15
    elif pnl>0: s += 8
    return s

def make_t_decision(pos, snaps):
    """做T：日内振幅≥门槛 → 低位做T买/高位反T卖"""
    latest = snaps[-1]
    price = latest["close"]
    win = snaps[-5:]
    hi = max(s["high"] for s in win)
    lo = min(s["low"] for s in win)
    amp = (hi-lo)/lo*100 if lo>0 else 0
    if amp < MIN_AMP.get(pos["period"], 2.0): return None
    pos_ratio = (price-lo)/(hi-lo) if hi>lo else 0.5
    tqty = max((int(pos["qty"]*T_RATIO)//100)*100, 100)
    if pos_ratio < 0.35:
        return {"type":"T_BUY","price":price,"qty":tqty,"reason":f"日内低位{pos_ratio:.0%} 振幅{amp:.1f}%"}
    elif pos_ratio > 0.65:
        tqty = min(tqty, pos["qty"]//100*100)
        return {"type":"RT_SELL","price":price,"qty":tqty,"reason":f"日内高位{pos_ratio:.0%} 振幅{amp:.1f}%"}
    return None

def simulate(cache, dates):
    cash = TOTAL
    holdings = []
    log = []
    def L(m): log.append(m)

    # 取某股票截至某日的快照（并补 name）
    def day_snaps(code, name, asof):
        snaps = filter_asof(cache.get(code, {}).get("snaps") or [], asof)
        if not snaps: return []
        snaps = [dict(s) for s in snaps]
        snaps[-1]["name"] = name
        return snaps

    for d in dates:
        L(f"\n===== 交易日 {d} =====")
        # 1. 扫描全池（用 filter_asof 截取到当日）
        cands = []
        for code, ent in cache.items():
            if code.startswith("sh000") or code.startswith("sz399"): continue
            snaps = day_snaps(code, ent.get("name", code), d)
            if len(snaps) < 20: continue
            for period in ["UltraShortQuant","ShortTermQuant"]:
                passed, score, det = trend_score(snaps)
                if passed:
                    cands.append({"code":code,"name":ent.get("name",code),"period":period,
                                  "price":snaps[-1]["close"],"score":score})
        cands.sort(key=lambda x:-x["score"])
        L(f"  当日超短/短趋势跟随候选 {len(cands)} 只: " + ", ".join(f"{c['name']}({c['period'][:5]}){c['score']}分" for c in cands[:6]))

        # 2. 自动建仓（≤3只/次，持仓≤5，单只≤20%）—— 新票如不强则不换（腾笼换鸟守则）
        opened = 0
        for c in cands:
            if len(holdings) >= MAX_HOLD: break
            if any(h["code"]==c["code"] for h in holdings): continue
            budget = min(TOTAL*SINGLE_RATIO, cash)
            if budget <= 0: break
            lots = int(budget//c["price"]//100)
            if lots <= 0: continue
            cost = lots*100*c["price"]
            if cash - cost < TOTAL*MIN_CASH_RATIO:
                maxlots = int((cash-TOTAL*MIN_CASH_RATIO)//c["price"]//100)
                if maxlots<=0: continue
                lots = maxlots
            qty = lots*100; cost = qty*c["price"]; cash -= cost
            new_score = c["score"]*10
            # 持仓满时：新票不强（≤最弱+15）则不腾笼换鸟
            if len(holdings) >= MAX_HOLD:
                weakest = min(holdings, key=lambda h: h["score"])
                if new_score <= weakest["score"] + 15:
                    L(f"  ⏭️ {c['name']} 强度{new_score} ≤ 最弱持仓{weakest['name']}{weakest['score']}+15，不腾笼换鸟")
                    continue
            pos = {"code":c["code"],"name":c["name"],"period":c["period"],"qty":qty,
                   "cost":c["price"],"mv":cost,"pnl":0.0,"score":new_score}
            holdings.append(pos)
            td = make_t_decision(pos, day_snaps(c["code"], c["name"], d))
            tdstr = f" +做T[{td['type']} {td['qty']}股 @{td['price']:.2f}]" if td else ""
            L(f"  🛒建仓 {pos['name']}({pos['code']}) {qty}股 @{c['price']:.2f} ={cost/1e4:.1f}万 仓位{cost/TOTAL*100:.0f}%{tdstr}")
            opened += 1
            if opened >= MAX_OPEN: break

        # 3. 全持仓做T/反T自动执行（用当日最新快照）
        for pos in holdings:
            snaps = day_snaps(pos["code"], pos["name"], d)
            td = make_t_decision(pos, snaps)
            if td: L(f"  🔁做T {pos['name']} {td['type']} {td['qty']}股 @{td['price']:.2f} | {td['reason']}")

        # 4. 更新市值 + 止盈止损（用当日最新快照）
        for pos in holdings:
            snaps = day_snaps(pos["code"], pos["name"], d)
            price = snaps[-1]["close"]
            pos["mv"] = price*pos["qty"]
            pos["pnl"] = (price-pos["cost"])/pos["cost"]*100
            pos["score"] = strength_score(snaps, pos["pnl"])
        for pos in list(holdings):
            stop = STOP.get(pos["period"], -8.0)
            if pos["pnl"] <= stop:
                cash += pos["mv"]
                L(f"  💸止损清仓 {pos['name']} 亏{pos['pnl']:.1f}%（{pos['period']}）")
                holdings.remove(pos)

        # 收盘报告
        mv = sum(h["mv"] for h in holdings)
        L(f"  ── 收盘: 持仓{len(holdings)}只 市值{mv/1e4:.1f}万 现金{cash/1e4:.1f}万 总资产{(cash+mv)/1e4:.1f}万")
        for h in holdings:
            L(f"      {h['name']}({h['period'][:5]}) {h['qty']}股 盈亏{h['pnl']:+.1f}% 强度{h['score']}")

    return log

if __name__ == "__main__":
    cache = load_cache()
    dates = ["2026-08-11","2026-08-12","2026-08-13"]
    log = simulate(cache, dates)
    with open("smalltools/_portfolio_sim.txt","w",encoding="utf-8") as f:
        f.write("\n".join(log))
    print("written", len(log), "lines")
