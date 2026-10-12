# -*- coding: utf-8 -*-
"""批量跑 core_stock_pool 全池票性识别 + 最优/稳健 + 噪声验证，落盘 CSV
用法: python _pool_profile.py [--start N] [--smooth 2]
断点续跑: 已存在于 CSV 的票自动跳过"""
import argparse, csv, importlib.util, json, os, time
import numpy as np

def _load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(os.path.dirname(__file__), name + ".py"))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

tp = _load("_ticket_profiler"); cr = _load("_ssa_cross")

POOL_JSON = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "core_stock_pool.json")
OUT_CSV = os.path.join(os.path.dirname(__file__), "..", "data", "review_tables", "ticket_profile_pool2.csv")
FIELDS = ["code", "label", "hi3", "hi5", "lo5", "cyc_amp", "n_hi", "n_lo", "bench",
          "b_s", "b_l", "b_lc", "b_total", "b_mdd", "b_cnt", "b_wr", "b_pf",
          "r_s", "r_l", "r_lc", "r_total", "r_mdd", "r_cnt", "r_pf",
          "v1_total", "v1_mdd", "v3_total", "v3_mdd", "v1_ok", "v3_ok",
          "buy_n", "buy_w1", "buy_w3", "buy_r5", "sell_n", "sell_w1", "sell_w3", "sell_r5",
          "hp_n", "hp_w3", "he_n", "he_w3", "latest", "latest_date",
          "b03_t", "b03_m", "b05_t", "b05_m", "b07_t", "b07_m", "b10_t", "b10_m",
          "f1_t", "f1_m", "f1_n", "f2_t", "f2_m", "f2_n", "f3_t", "f3_m", "f3_n",
          "f4_t", "f4_m", "f4_n", "f5_t", "f5_m", "f5_n"]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--smooth", type=int, default=2)
    args = ap.parse_args()

    stocks = json.load(open(POOL_JSON, encoding="utf-8"))["stocks"]
    codes = [s.replace("sh", "").replace("sz", "").replace("bj", "") for s in stocks]
    done = set()
    if os.path.exists(OUT_CSV):
        for row in csv.DictReader(open(OUT_CSV, encoding="utf-8-sig")):
            done.add(row["code"])
    new = not os.path.exists(OUT_CSV)
    fp = open(OUT_CSV, "a", newline="", encoding="utf-8-sig")
    w = csv.DictWriter(fp, fieldnames=FIELDS)
    if new:
        w.writeheader(); fp.flush()

    total = len(codes)
    for i, code in enumerate(codes):
        if i < args.start or code in done:
            continue
        t0 = time.time()
        try:
            r = tp.scan_ticket(code, smooth=args.smooth)
        except Exception as e:
            print(f"[{i + 1}/{total}] {code} 失败: {e}", flush=True)
            continue
        if r is None:
            print(f"[{i + 1}/{total}] {code} 数据不足", flush=True)
            continue
        p = r["prof"]; b = r["best"]; ro = r["robust"]
        n1 = r["noise_v1"]; n3 = r["noise_v3"]
        av = r["advice"]; lt = r["latest"]; bs = r["bases"]; fx = r["fixed"]
        fkeys = ["10/20+c60", "5/10+c60", "20/60+c60", "30/60+c60", "5/10+c20"]
        row = dict(code=code, label=p["label"], hi3=round(p["hi3"], 3), hi5=round(p.get("hi5", 0), 3),
                        lo5=round(p["lo5"], 3), cyc_amp=round(p["cyc_amp"], 4), n_hi=p["n_hi"], n_lo=p["n_lo"],
                        bench=round(r["bench"], 2),
                        b_s=b["s"], b_l=b["l"], b_lc=b["lc"], b_total=round(b["total"], 2), b_mdd=round(b["mdd"], 2),
                        b_cnt=b["cnt"], b_wr=round(b["wr"], 1), b_pf=round(b["pf"], 2) if b["pf"] != float("inf") else 999,
                        r_s=ro["s"], r_l=ro["l"], r_lc=ro["lc"], r_total=round(ro["total"], 2), r_mdd=round(ro["mdd"], 2),
                        r_cnt=ro["cnt"], r_pf=round(ro["pf"], 2) if ro["pf"] != float("inf") else 999,
                        v1_total=round(n1[0], 2), v1_mdd=round(n1[1], 2), v3_total=round(n3[0], 2), v3_mdd=round(n3[1], 2),
                        v1_ok=int(n1[0] > b["total"]), v3_ok=int(n3[0] > b["total"]),
                        buy_n=av["buy"]["n"], buy_w1=round(av["buy"]["w1"], 1), buy_w3=round(av["buy"]["w3"], 1),
                        buy_r5=round(av["buy"]["r5"], 2),
                        sell_n=av["sell"]["n"], sell_w1=round(av["sell"]["w1"], 1), sell_w3=round(av["sell"]["w3"], 1),
                        sell_r5=round(av["sell"]["r5"], 2),
                        hp_n=av["hpos"]["n"], hp_w3=round(av["hpos"]["w3"], 1),
                        he_n=av["hemp"]["n"], he_w3=round(av["hemp"]["w3"], 1),
                        latest=lt["rec"], latest_date=lt["date"])
        for frac, tag in ((0.3, "03"), (0.5, "05"), (0.7, "07"), (1.0, "10")):
            row[f"b{tag}_t"] = round(bs[frac][0], 2); row[f"b{tag}_m"] = round(bs[frac][1], 2)
        for tag, k in zip(("f1", "f2", "f3", "f4", "f5"), fkeys):
            row[f"{tag}_t"] = round(fx[k][0], 2); row[f"{tag}_m"] = round(fx[k][1], 2); row[f"{tag}_n"] = fx[k][2]
        w.writerow(row)
        fp.flush()
        print(f"[{i + 1}/{total}] {code} {p['label']} 最优{b['total']:+.1f}% "
              f"买命中{av['buy']['w3']:.0f}%({av['buy']['n']}) 卖命中{av['sell']['w3']:.0f}%({av['sell']['n']}) "
              f"次日建议:{lt['rec']} ({time.time() - t0:.0f}s)", flush=True)
    fp.close()
    print("完成 ->", OUT_CSV)

if __name__ == "__main__":
    main()
