# -*- coding: utf-8 -*-
"""国家队/大基金信号 —— **去重后的真实期望**统计（回答「不被 3 只股票绑架的胜率」）。

■ 为什么需要
  `_national_flow_detail.py` 给的是**信号级**胜率：同一只股票在一轮行情里被 step=5
  反复触发，一次行情被数成几十笔。实测 industry 池「国家队|大基金 ∩ 社保」174 笔
  只来自 **10 只股票**，涨幅前列几乎全是紫金矿业(2016-17 有色牛)/厦门钨业/北方华创
  —— 3 个事件主导整张表。信号级胜率**系统性虚高**，不能当策略期望。

■ 成功定义
  买入后 120 个交易日内最高价 ≥ 买入价 × 1.2（等价 maxgain ≥ +20%）

■ 两个去重口径（★ 关键：都在「池内全部信号」上取入场点，再按该入场点自身的
  机构层打标签 —— 各层共用**同一套入场点**，统计上严格可比。
  反面教材：各层各自切片会让 `ss` 切出比 `all` 更多的事件（抽稀把间隔拉大、
  把一个事件裂成两个），得出的层间差异全是切分伪影。）

  A. 非重叠入场（主口径）：同一只股票，相邻入场至少间隔 24 个采样点
     （≈120 交易日）→ 收益窗口不重叠，等价于「每半年最多跟一次」
  B. 每股首次入场（最保守）：每只股票在整个窗口只算 1 次

  两个口径都额外报告**涉及股票数** —— 因为入场点之间高度相关，有效独立样本
  更接近「股票数 × 行情轮次」，而不是入场点个数。

用法：
  python _national_flow_event.py                   # 全量（默认 gap=24 ≈120 交易日）
  python _national_flow_event.py --gap 12          # 更密入场（窗口会重叠）
  python _national_flow_event.py --tag 半导体       # 只看某主线
  python _national_flow_event.py --pool wide       # 只看宽基
产物：data/_national_flow_event.json
"""
import argparse
import collections
import csv
import json
import os
import statistics as st

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA_DIR = os.path.join(ROOT, "data")
CSV_PATH = os.path.join(DATA_DIR, "_national_flow_detail.csv")
OUT_JSON = os.path.join(DATA_DIR, "_national_flow_event.json")

BASE_LAYERS = ["nat", "big", "ss", "nb"]          # 国家队 / 大基金 / 社保 / 北向
COMBO_LAYERS = ["natbig", "natbig_ss"]
ALL_LAYERS = ["all"] + BASE_LAYERS + COMBO_LAYERS + ["none"]
REGIMES = ["BULLISH", "OSCILLATION", "BEARISH", "CRASH"]
THR = 20.0
GAP = 24


def load():
    rows = []
    with open(CSV_PATH, encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            r["layers"] = set(r["layers"].split(","))
            for k in ("buy", "g20", "g60", "g120", "maxgain",
                      "maxdd", "post_peak_dd", "r10", "end_ret"):
                try:
                    r[k] = float(r[k])
                except (TypeError, ValueError):
                    r[k] = 0.0
            for k in ("peak_off", "dd_off"):
                try:
                    r[k] = int(r[k])
                except (TypeError, ValueError):
                    r[k] = 0
            r["in_top5"] = r["in_top5"] == "True"
            r["in_wide"] = r["in_wide"] == "True"
            rows.append(r)
    ordm = {d: i for i, d in enumerate(sorted({r["sig_date"] for r in rows}))}
    for r in rows:
        r["ord"] = ordm[r["sig_date"]]
    return rows


def agg(rs):
    if not rs:
        return {"n": 0}
    mg = [r["maxgain"] for r in rs]
    return {
        "n": len(rs), "codes": len({r["code"] for r in rs}),
        "succ": 100.0 * sum(1 for x in mg if x >= THR) / len(rs),
        "mg_mean": st.mean(mg), "mg_med": st.median(mg), "mg_min": min(mg),
        "dd_med": st.median([r["maxdd"] for r in rs]),
        "post_med": st.median([r["post_peak_dd"] for r in rs]),
        "end_med": st.median([r["end_ret"] for r in rs]),
        "end_win": 100.0 * sum(1 for r in rs if r["end_ret"] > 0) / len(rs),
    }


def nonoverlap(rs, gap):
    """非重叠入场点：每股按时间贪心跳 gap 个采样点（在传入的整个信号集上取）。"""
    by = collections.defaultdict(list)
    for r in rs:
        by[r["code"]].append(r)
    out = []
    for code in sorted(by):
        seq = sorted(by[code], key=lambda r: r["ord"])
        i = 0
        while i < len(seq):
            out.append(seq[i])
            bound = seq[i]["ord"] + gap
            j = i + 1
            while j < len(seq) and seq[j]["ord"] < bound:
                j += 1
            i = j
    return out


def firsts(rs):
    """每股首次入场（最保守）。"""
    by = collections.defaultdict(list)
    for r in rs:
        by[r["code"]].append(r)
    return [min(v, key=lambda r: r["ord"]) for v in by.values()]


def fmt(tag, s):
    if not s.get("n"):
        return "  %-28s %s" % (tag, "无样本")
    return ("  %-28s n=%-4d 股数%-3d 成功%5.1f%%  均最高%6.1f%%  中位%6.1f%%  "
            "最差%6.1f%%  末收益中位%6.1f%%  末胜率%5.1f%%"
            % (tag, s["n"], s["codes"], s["succ"], s["mg_mean"], s["mg_med"],
               s["mg_min"], s["end_med"], s["end_win"]))


def pool_filter(rows, name):
    return {"industry": rows,
            "top5": [r for r in rows if r["in_top5"]],
            "wide": [r for r in rows if r["in_wide"]]}[name]


def block(entries, title, report, key):
    """打印一组入场点在 基线/分层/互斥渠道 上的对比。"""
    print("─" * 122)
    print(" %s（入场点 %d 个，涉及 %d 只股票）"
          % (title, len(entries), len({e["code"] for e in entries})))
    print("─" * 122)
    report[key] = {"all": agg(entries)}
    print(fmt("基线：全部入场点", agg(entries)))
    for L in ALL_LAYERS[1:]:
        g = [e for e in entries if L in e["layers"]]
        report[key][L] = agg(g)
        print(fmt("  层 %s" % L, agg(g)))
    any_l = [e for e in entries if e["layers"] & set(BASE_LAYERS)]
    none_l = [e for e in entries if "none" in e["layers"]]
    print("  ★ 互斥渠道对比：")
    print(fmt("   有任一机构(nat/big/ss/nb)", agg(any_l)))
    print(fmt("   无任何机构(none=对照组)", agg(none_l)))
    report[key]["any_inst"] = agg(any_l)
    report[key]["no_inst"] = agg(none_l)
    print()
    return any_l, none_l


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gap", type=int, default=GAP)
    ap.add_argument("--tag", default="")
    ap.add_argument("--pool", default="")
    args = ap.parse_args()

    rows = load()
    print("读入 %d 条信号（%s ~ %s）" % (len(rows), min(r["sig_date"] for r in rows),
                                        max(r["sig_date"] for r in rows)))
    print("成功定义：买入后 120 交易日内最高价 ≥ 买入价 × 1.2（maxgain ≥ +20%）")
    print("去重口径：在「池内全部信号」上取入场点（非重叠间隔 %d 采样点 ≈%d 交易日），"
          "各层共用同一批入场点 → 层间可比\n" % (args.gap, args.gap * 5))
    if args.tag:
        rows = [r for r in rows if args.tag in r["tag"]]
        print("按 tag 过滤「%s」→ %d 条\n" % (args.tag, len(rows)))

    report = {"meta": {"n_signals": len(rows), "gap": args.gap, "tag": args.tag,
                       "threshold": THR}}
    pools = [args.pool] if args.pool else ["industry", "top5", "wide"]

    for pool in pools:
        sub = pool_filter(rows, pool)
        if not sub:
            continue
        print("═" * 122)
        print("池 = %-9s 信号 %d 条 / %d 只股票" % (pool, len(sub), len({r["code"] for r in sub})))
        print("═" * 122)
        report[pool] = {}
        print(fmt("信号级（原报告口径, 虚高对照）", agg(sub)))
        print()
        ent = nonoverlap(sub, args.gap)
        block(ent, "口径A 非重叠入场（主口径）", report[pool], "nonoverlap")
        block(firsts(sub), "口径B 每股首次入场（最保守）", report[pool], "first_entry")
        # 原始事件数参考
        print("  参考：口径A 相对信号级的压缩比 = %.1f 倍\n" % (len(sub) / max(1, len(ent))))

    # ── 分市场环境（口径A）────────────────────────────
    print("═" * 122)
    print("分市场环境（口径A 非重叠入场）—— 「现在下跌趋势跟买成功率」看 BEARISH / CRASH")
    print("═" * 122)
    report["by_regime"] = {}
    for pool in pools:
        sub = pool_filter(rows, pool)
        if not sub:
            continue
        ent = nonoverlap(sub, args.gap)
        print(" 【%s】" % pool)
        report["by_regime"][pool] = {}
        for R in REGIMES:
            a = [e for e in ent if e["regime"] == R]
            i = [e for e in a if e["layers"] & set(BASE_LAYERS)]
            n = [e for e in a if "none" in e["layers"]]
            report["by_regime"][pool][R] = {"all": agg(a), "inst": agg(i), "none": agg(n)}
            print(fmt("   %-12s 全部" % R, agg(a)))
            print(fmt("   %-12s └有机构" % "", agg(i)))
            print(fmt("   %-12s └无机构" % "", agg(n)))
        print()

    # ── 主线(tag) ──────────────────────────────────
    print("═" * 122)
    print("主线(tag)（口径A，入场点 ≥ 3）—— 「设备/半导体」在这里")
    print("═" * 122)
    out = []
    for tg in sorted({r["tag"] for r in rows}):
        ls = [r for r in rows if r["tag"] == tg]
        s = agg(nonoverlap(ls, args.gap))
        if s["n"] >= 3:
            out.append((tg, s))
    out.sort(key=lambda x: -x[1]["succ"])
    print("  %-16s %6s %6s %8s %9s %9s" % ("主线", "入场", "股数", "成功率", "均最高", "最差"))
    for tg, s in out[:20]:
        print("  %-16s %6d %6d %7.1f%% %8.1f%% %8.1f%%"
              % (tg[:14], s["n"], s["codes"], s["succ"], s["mg_mean"], s["mg_min"]))
    report["by_tag"] = {tg: s for tg, s in out}

    # ── 失败样本 ────────────────────────────────────
    print()
    print("═" * 122)
    print("有机构入场里最差的 12 个入场点（口径A）—— 失败长什么样（用于设止损）")
    print("═" * 122)
    ent = [e for e in nonoverlap(rows, args.gap) if e["layers"] & set(BASE_LAYERS)]
    bad = sorted(ent, key=lambda r: r["maxgain"])[:12]
    print("  %-11s %-8s %-9s %-9s %8s %8s %10s"
          % ("信号日", "代码", "名称", "环境", "最高涨", "买入价", "末收益"))
    for r in bad:
        print("  %-11s %-8s %-9s %-9s %7.1f%% %8.3f %9.1f%%"
              % (r["sig_date"], r["code"], r["name"][:8], r["regime"][:6],
                 r["maxgain"], r["buy"], r["end_ret"]))
    report["worst_entries"] = [{k: r[k] for k in ("sig_date", "code", "name", "tag",
                                                 "regime", "buy", "maxgain", "maxdd",
                                                 "post_peak_dd", "end_ret")} for r in bad]

    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2, default=str)
        f.write("\n")
    print("\n✓ 汇总 %s" % OUT_JSON)


if __name__ == "__main__":
    main()
