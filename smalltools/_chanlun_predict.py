# -*- coding: utf-8 -*-
"""缠论预测 + 事后验证
=========================
predict：对勾选的一批票生成 3 情景推演，写 predictions.jsonl
verify：对未验证预测取后续 K 线验证，写回结果 + 准确率统计

情景规则（基于当前结构，非 ML）：
  1) 底背驰反弹/顶背驰回落：最后买卖点为买点且 near 中枢下沿 → 目标中枢上沿
  2) 跌破中枢下沿：当前笔向下且在中枢内 → 目标 中枢下沿-(中枢高度)
  3) 中枢震荡：在中枢内 → 区间 [中枢下沿, 中枢上沿]
概率先验（待验证模块贝叶斯校准）：0.45 / 0.30 / 0.25

用法：
  python smalltools/_chanlun_predict.py predict 600188 000858 --lv 1h
  python smalltools/_chanlun_predict.py verify
"""
import argparse
import json
import os
import sys
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from _chanlun_engine import compute_structure, load_klines  # noqa: E402

OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "data", "chanlun")
PRED_JSONL = os.path.join(OUT_DIR, "predictions.jsonl")
HORIZON = 8  # 验证窗口：预测后最多看 8 根 K 线


def _scenarios(r: dict) -> list:
    """基于结构生成 3 情景。"""
    price = r["bsp"][-1]["price"] if r["bsp"] else None
    bi = r["bi"][-1] if r["bi"] else None
    zs = r["zs"][-1] if r["zs"] else None
    cur = bi["end_val"] if bi else price
    out = []
    if zs:
        zl, zh, zmid = zs["low"], zs["high"], (zs["low"] + zs["high"]) / 2
        zheight = zh - zl
        in_zs = zl <= (cur or 0) <= zh
        if in_zs:
            out = [
                {"name": "中枢震荡", "prob": 0.45, "range": [round(zl, 3), round(zh, 3)], "horizon": HORIZON},
                {"name": "跌破下沿", "prob": 0.30, "target": round(zl - zheight, 3), "horizon": 5},
                {"name": "突破上沿", "prob": 0.25, "target": round(zh + zheight, 3), "horizon": 5},
            ]
        elif (cur or 0) < zl:
            out = [
                {"name": "底背驰反弹", "prob": 0.45, "target": round(zmid, 3), "horizon": HORIZON},
                {"name": "延续下跌", "prob": 0.30, "target": round(cur - zheight, 3), "horizon": 5},
                {"name": "低位震荡", "prob": 0.25, "range": [round(cur - zheight / 2, 3), round(cur + zheight / 2, 3)], "horizon": HORIZON},
            ]
        else:
            out = [
                {"name": "延续上涨", "prob": 0.45, "target": round(zh + zheight, 3), "horizon": HORIZON},
                {"name": "回落中枢", "prob": 0.30, "target": round(zmid, 3), "horizon": 5},
                {"name": "高位震荡", "prob": 0.25, "range": [round(zh, 3), round(cur + zheight / 2, 3)], "horizon": HORIZON},
            ]
    return out


def predict(codes: list, lv: str = "1h") -> list:
    """批量生成预测，追加写 predictions.jsonl。"""
    os.makedirs(OUT_DIR, exist_ok=True)
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    recs = []
    for code in codes:
        try:
            r = compute_structure(code, lv)
        except Exception as e:
            print(f"  {code} 失败: {e}")
            continue
        sc = _scenarios(r)
        if not sc:
            print(f"  {code} 无中枢，跳过")
            continue
        rec = {
            "pred_id": f"P{datetime.now().strftime('%Y%m%d%H%M%S')}_{code}",
            "code": code, "lv": lv, "time": now,
            "kline_cnt": r["kline_cnt"],
            "cur_price": r["bi"][-1]["end_val"] if r["bi"] else None,
            "scenarios": sc,
            "verified": False, "hit_scenario": None, "hit_time": None, "actual_return": None,
        }
        recs.append(rec)
    if recs:
        with open(PRED_JSONL, "a", encoding="utf-8") as f:
            for rec in recs:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    return recs


def _hit(sc: dict, bars: list, cur: float) -> tuple:
    """判断情景是否命中。返回 (hit: bool, hit_time: str|None, ret: float|None)。"""
    for b in bars[:sc.get("horizon", HORIZON)]:
        lo, hi, t = b["low"], b["high"], b["time"]
        if "target" in sc:
            # 反弹/上涨：高点触目标；下跌/跌破：低点触目标
            tgt = sc["target"]
            if tgt >= cur and hi >= tgt:
                return True, t, round(tgt / cur - 1, 4)
            if tgt < cur and lo <= tgt:
                return True, t, round(tgt / cur - 1, 4)
        elif "range" in sc:
            rl, rh = sc["range"]
            # 震荡：窗口内价格始终落在区间内 → 验证结束时判定
            pass
    if "range" in sc:
        rl, rh = sc["range"]
        bars2 = bars[:sc.get("horizon", HORIZON)]
        if bars2 and all(rl <= (b["low"] + b["high"]) / 2 <= rh for b in bars2):
            return True, bars2[-1]["time"], round(bars2[-1]["close"] / cur - 1, 4)
    return False, None, None


def verify() -> dict:
    """对未验证预测取后续 K 线验证，写回 + 统计。"""
    if not os.path.isfile(PRED_JSONL):
        return {"total": 0}
    recs = [json.loads(l) for l in open(PRED_JSONL, encoding="utf-8") if l.strip()]
    updated = 0
    for rec in recs:
        if rec.get("verified"):
            continue
        rows = load_klines(rec["code"], rec.get("lv", "1h"), use_cache=False)
        if not rows:
            continue
        # 找预测时刻之后的新 K 线（pred 时 kline_cnt 之后的就是新数据）
        n0 = rec.get("kline_cnt", 0)
        new_bars = rows[n0:]
        if len(new_bars) < 1:
            continue
        cur = rec.get("cur_price") or rows[n0 - 1]["close"]
        for sc in rec["scenarios"]:
            hit, ht, ret = _hit(sc, new_bars, cur)
            if hit:
                rec["verified"] = True
                rec["hit_scenario"] = sc["name"]
                rec["hit_time"] = ht
                rec["actual_return"] = ret
                updated += 1
                break
        # 超窗未命中 → 标记未中
        max_h = max(sc.get("horizon", HORIZON) for sc in rec["scenarios"])
        if not rec["verified"] and len(new_bars) >= max_h:
            rec["verified"] = True
            rec["hit_scenario"] = "未命中"
            rec["actual_return"] = round(new_bars[max_h - 1]["close"] / cur - 1, 4)
            updated += 1
    if updated:
        with open(PRED_JSONL, "w", encoding="utf-8") as f:
            for rec in recs:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    done = [r for r in recs if r.get("verified")]
    hit = [r for r in done if r.get("hit_scenario") not in (None, "未命中")]
    return {"total": len(recs), "verified": len(done), "hit": len(hit),
            "acc": round(len(hit) / len(done), 4) if done else 0, "updated": updated}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd")
    pp = sp.add_parser("predict")
    pp.add_argument("codes", nargs="+")
    pp.add_argument("--lv", default="1h", choices=["1h", "day"])
    sp.add_parser("verify")
    args = ap.parse_args()

    if args.cmd == "predict":
        recs = predict(args.codes, args.lv)
        for r in recs:
            print(f"{r['pred_id']} cur={r['cur_price']}")
            for sc in r["scenarios"]:
                tgt = sc.get("target") or sc.get("range")
                print(f"  {sc['name']} {sc['prob']:.0%} → {tgt} ({sc['horizon']}根)")
    elif args.cmd == "verify":
        s = verify()
        print(f"验证完成：总{s['total']} 已验证{s['verified']} 命中{s['hit']} 准确率{s['acc']:.1%} 本轮更新{s['updated']}")
    else:
        ap.print_help()
