# -*- coding: utf-8 -*-
"""从 ticket_profile_pool2.csv 读每只票最优参数，只跑 sim_hybrid_adv 一次拿逐K建议明细，
追加写入 ticket_advice_detail.csv（断点续跑）。每只票约 5~8 秒（只跑1个组合，不扫50组）。"""
import csv, importlib.util, os, time
import numpy as np

def _load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(os.path.dirname(__file__), name + ".py"))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); return m

tp = _load("_ticket_profiler"); cr = _load("_ssa_cross")

POOL_CSV = os.path.join(os.path.dirname(__file__), "..", "data", "review_tables", "ticket_profile_pool2.csv")
OUT_CSV = os.path.join(os.path.dirname(__file__), "..", "data", "review_tables", "ticket_advice_detail.csv")
FIELDS = ["code", "date", "close", "advice", "r1", "r3", "r5"]

def main():
    rows = list(csv.DictReader(open(POOL_CSV, encoding="utf-8-sig")))
    done = set()
    if os.path.exists(OUT_CSV):
        for r in csv.DictReader(open(OUT_CSV, encoding="utf-8-sig")):
            done.add(r["code"])
    new = not os.path.exists(OUT_CSV)
    fp = open(OUT_CSV, "a", newline="", encoding="utf-8-sig")
    w = csv.DictWriter(fp, fieldnames=FIELDS)
    if new:
        w.writeheader(); fp.flush()
    total = len(rows)
    for i, r in enumerate(rows):
        code = r["code"]
        if code in done:
            continue
        s, l, lc = int(r["b_s"]), int(r["b_l"]), int(r["b_lc"])
        t0 = time.time()
        try:
            bars = cr.load_daily(code, "20230101")
            tc = cr.get_trends_cycles(bars)
            tc = {L: (tp.ema(a, 2), tp.ema(b, 2)) for L, (a, b) in tc.items()}
            _, _, _, _, detail = tp.sim_hybrid_adv(bars, tc[s][0], tc[l][0], tc[lc][1], base=0.0)
            for d in detail:
                if d["idx"] >= 2:   # 跳过暖机（前2根无信号）
                    w.writerow(dict(code=code, date=d["date"], close=round(d["close"], 2),
                                    advice=d["rec"],
                                    r1=round(d["r1"], 2) if d["r1"]==d["r1"] else "",
                                    r3=round(d["r3"], 2) if d["r3"]==d["r3"] else "",
                                    r5=round(d["r5"], 2) if d["r5"]==d["r5"] else ""))
            fp.flush()
            print(f"[{i+1}/{total}] {code} t{s}/{l}+c{lc} {len(detail)}根 ({time.time()-t0:.0f}s)", flush=True)
        except Exception as e:
            print(f"[{i+1}/{total}] {code} 失败: {e}", flush=True)
    fp.close()
    print("完成 ->", OUT_CSV)

if __name__ == "__main__":
    main()
