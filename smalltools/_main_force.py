# -*- coding: utf-8 -*-
"""主力行为分阶段识别（2026-09-19 新增，用户需求）。

背景：此前只有「主力建仓」识别（十大流通股东增持 → `inst_buy_recent` 节点）。
本模块补齐 **拉升 / 洗盘 / 出货** 三态，形成完整的主力生命周期：

    建仓 BUILD → 拉升 PULL → 洗盘 WASH → 出货 DUMP

数据只用**日线量价 + 筹码分布(`_chip_dist`) + 日频资金流(`_stock_fundflow`)**——
无 Level2/逐笔，故每态都带 `conf` 置信度，不谎称精确。

口径对齐（避免与既有实现形成两套标准）：
  · Kotlin 做T侧已有同构枚举 `TTradePipelineNodes.kt::analyzeInstitutionalIntent`
    （ACCUMULATING / SHAKING / PULLING_UP / DISTRIBUTING）——本模块把它移植为
    **全市场选股可用**的独立判定，并补上 Python 侧可得的筹码/资金流维度。
  · 洗盘口径参考板块埋伏 `wash`（缩量不破 MA20）；出货参考 `AutoSellEngine`
    的放量滞涨(Volume Climax) 与祖训规则11「缓跌放量立马撤」。

用法：
    python _main_force.py 300308 600188      # 单票/多票详情
    python _main_force.py --top 20           # 全池扫描（按 stage 优先级）

返回结构：{code, name, stage, conf, reasons[], ma20, vol_ratio, rsi, boll_pos,
          chip_profit, close, chg5, chg20}
  stage ∈ BUILD / PULL / WASH / DUMP / NONE
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

STAGES = ("BUILD", "PULL", "WASH", "DUMP", "NONE")
STAGE_CN = {"BUILD": "建仓吸货", "PULL": "拉升中", "WASH": "震仓洗盘",
            "DUMP": "主力出货", "NONE": "无明确信号"}
# 优先级：出货/拉升对交易决策最要紧 → 排序与展示都优先
STAGE_RANK = {"DUMP": 0, "PULL": 1, "WASH": 2, "BUILD": 3, "NONE": 9}


def _ma(closes, n):
    return sum(closes[-n:]) / float(n) if len(closes) >= n else (closes[-1] if closes else 0.0)


def _boll_pos(closes, n=20):
    """布林带内位置 0~1（<0.4 偏下轨，>0.7 偏上轨）。"""
    if len(closes) < n:
        return 0.5
    seg = closes[-n:]
    mid = sum(seg) / n
    sd = (sum((x - mid) ** 2 for x in seg) / n) ** 0.5
    if sd <= 0:
        return 0.5
    return max(0.0, min(1.0, (closes[-1] - (mid - 2 * sd)) / (4 * sd)))


def detect(snaps, chip=None, flow=None):
    """主力行为判定。snaps: [{date,open,high,low,close,volume,changePct}] 升序。

    chip: `_chip_dist.compute()` 结果（可 None）；flow: `_stock_fundflow.fetch()` 行（可 None）。
    """
    if not snaps or len(snaps) < 25:
        return {"stage": "NONE", "conf": 0.0, "reasons": ["K线不足25根"]}
    closes = [float(s["close"]) for s in snaps]
    highs = [float(s["high"]) for s in snaps]
    lows = [float(s["low"]) for s in snaps]
    opens = [float(s["open"]) for s in snaps]
    vols = [float(s.get("volume") or 0) for s in snaps]
    c0 = closes[-1]
    chg5 = (c0 / closes[-6] - 1) * 100 if len(closes) > 6 else 0.0
    chg20 = (c0 / closes[-21] - 1) * 100 if len(closes) > 21 else 0.0

    ma5, ma10, ma20, ma60 = (_ma(closes, 5), _ma(closes, 10),
                             _ma(closes, 20), _ma(closes, 60))
    ma_bull = ma5 > ma10 > ma20 > 0 and c0 > ma20
    v5 = sum(vols[-5:]) / 5.0
    v20 = sum(vols[-20:]) / 20.0
    vr = (v5 / v20) if v20 > 0 else 1.0            # 5日量 / 20日量（<0.85 缩量，>1.3 放量）
    vol_shrink = vr < 0.85
    vol_expand = vr > 1.3

    # RSI(6)
    gains = losses = 0.0
    for i in range(-6, 0):
        d = closes[i] - closes[i - 1]
        gains += max(d, 0.0)
        losses += max(-d, 0.0)
    rsi = 100.0 if losses == 0 else 100 - 100 / (1 + gains / losses)

    boll = _boll_pos(closes)
    # 近 5 根 K 线形态统计
    n_yang = sum(1 for i in range(-5, 0) if closes[i] >= opens[i])
    n_yin = 5 - n_yang
    up_shadow = sum(1 for i in range(-3, 0)
                    if highs[i] - max(opens[i], closes[i]) > abs(closes[i] - opens[i]) + 1e-9)
    dn_shadow = sum(1 for i in range(-3, 0)
                    if min(opens[i], closes[i]) - lows[i] > abs(closes[i] - opens[i]) + 1e-9)
    # 放量滞涨（Volume Climax）：当日量 > 1.8×20日均量，涨幅却 <1%
    climax = (vols[-1] > 1.8 * v20 and abs((c0 / closes[-2] - 1) * 100) < 1.0)
    # 缓跌放量（祖训11）
    slow_dump = (n_yin >= 4 and vr > 1.5 and chg5 < -2)
    hold_ma20 = c0 >= ma20 * 0.97                   # 洗盘前提：不破 MA20 太远

    chip_profit = None
    if isinstance(chip, dict):
        chip_profit = chip.get("profit_ratio")
    main_net = None
    if flow:
        try:
            main_net = flow[-1].get("main")
        except Exception:  # noqa: BLE001
            pass

    reasons = []
    stage, conf = "NONE", 0.0

    # ① 出货优先判定（风控最要紧，且与拉升互斥时以出货为准）
    if climax and up_shadow >= 2:
        stage, conf = "DUMP", 0.80
        reasons += ["放量滞涨(Volume Climax)", "连续上影线(冲高回落)"]
        if rsi > 65:
            reasons.append("RSI超买(%.0f)" % rsi)
    elif slow_dump:
        stage, conf = "DUMP", 0.72
        reasons += ["缓跌放量立马撤(祖训11)", "5日%.1f%%" % chg5]
    elif vol_expand and n_yin >= 3 and chg5 < -3:
        stage, conf = "DUMP", 0.70
        reasons += ["放量下跌+阴线≥3", "5日%.1f%%" % chg5]
    # ② 拉升
    elif ma_bull and n_yang >= 3 and not vol_shrink and chg5 > 3.0:
        stage, conf = "PULL", 0.70
        reasons += ["均线多头排列", "5日阳线≥3", "5日涨幅%.1f%%" % chg5]
        if chg5 > 15:
            reasons.append("短期涨幅过大(追高风险)")
            conf -= 0.15
    # ③ 洗盘（缩量 + 下影 + 不破位）
    elif vol_shrink and dn_shadow >= 2 and 30 <= rsi <= 52 and boll < 0.45 and hold_ma20:
        stage, conf = "WASH", 0.75
        reasons += ["缩量(5日量/20日量=%.2f)" % vr, "连续下影线(承接)", "未破MA20"]
    # ④ 建仓（比洗盘更早、更弱：量缩价稳 + 低位）
    elif vol_shrink and 28 <= rsi <= 55 and boll < 0.5 and chg20 < 8:
        stage, conf = "BUILD", 0.60
        reasons += ["量缩价稳", "布林下轨区(%.2f)" % boll, "20日%.1f%%" % chg20]
        if chip_profit is not None and chip_profit < 0.85:
            reasons.append("筹码获利盘%.0f%%(低位密集)" % (chip_profit * 100))
            conf += 0.10

    if main_net is not None:
        reasons.append("主力净额%.0f万" % (main_net / 1e4))

    return {"stage": stage, "conf": round(min(1.0, max(0.0, conf)), 2),
            "reasons": reasons, "close": c0, "chg5": round(chg5, 2),
            "chg20": round(chg20, 2), "ma20": round(ma20, 3),
            "vol_ratio": round(vr, 2), "rsi": round(rsi, 1),
            "boll_pos": round(boll, 2), "chip_profit": chip_profit,
            "ma_bull": ma_bull}


def summary_line(code, name, r):
    """一行摘要（供推送/表格复用）。"""
    return "%-8s %-10s %-8s conf=%.2f  %s" % (
        code, (name or "")[:8], STAGE_CN.get(r["stage"], r["stage"]),
        r["conf"], " / ".join(r["reasons"][:3]))


def main():
    ap = argparse.ArgumentParser(description="主力行为识别（建仓/拉升/洗盘/出货）")
    ap.add_argument("codes", nargs="*", help="6位代码，如 300308")
    ap.add_argument("--top", type=int, default=0, help="扫全池并输出前 N 条")
    a = ap.parse_args()
    from _kline_store import load_store
    store = load_store()
    try:
        import _chip_dist
    except Exception:  # noqa: BLE001
        _chip_dist = None

    def run_one(secid, ent):
        snaps = (ent.get("snaps") or [])[-120:]
        chip = None
        if _chip_dist is not None:
            try:
                chip = _chip_dist.compute(snaps)
            except Exception:  # noqa: BLE001
                chip = None
        r = detect(snaps, chip=chip)
        return r

    if a.top:
        rows = []
        for secid, ent in store.items():
            if secid.startswith(("sh000", "sz399")):
                continue
            try:
                r = run_one(secid, ent)
            except Exception:  # noqa: BLE001
                continue
            if r["stage"] != "NONE":
                rows.append((secid, ent.get("name") or "", r))
        rows.sort(key=lambda t: (STAGE_RANK[t[2]["stage"]], -t[2]["conf"]))
        print("=== 全池主力行为扫描（共 %d 只有信号 / 池 %d）===" % (len(rows), len(store)))
        for secid, name, r in rows[:a.top]:
            print(summary_line(secid, name, r))
        return

    for c in (a.codes or ["300308"]):
        secid = c if c[:2] in ("sh", "sz") else ("sh" if c.startswith("6") else "sz") + c
        ent = store.get(secid)
        if not ent:
            print("%s 不在K线存储中" % secid)
            continue
        r = run_one(secid, ent)
        print("=== %s %s ===" % (secid, ent.get("name") or ""))
        print(summary_line(secid, ent.get("name") or "", r))
        print("   收盘%.2f | MA20 %.2f | 5日量/20日量 %.2f | RSI %.1f | 布林位 %.2f | 5日 %.1f%% | 20日 %.1f%%"
              % (r["close"], r["ma20"], r["vol_ratio"], r["rsi"], r["boll_pos"],
                 r["chg5"], r["chg20"]))


if __name__ == "__main__":
    main()
