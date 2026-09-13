# -*- coding: utf-8 -*-
"""国家队（汇金）ETF 观测报告 —— 把 `_etf_share_flow.py` 的资产合成「结论 + 简明 CSV + 推送文案」。

■ 回答三个问题
  ① 国家队在买还是在卖？        → 宽基合计净申赎方向 + 拐点（季度）+ 日频漂移
  ② 是谁在动？（散户 还是机构） → 机构/个人持有份额拆分 + 「机构占净流动」
  ③ 是不是汇金？                → 年报 §9.2 前十名持有人（法定点名，含中央汇金双主体）

■ 产物
  控制台结论 + data/_etf_national_report.csv（简明表）+ --push 时输出推送文案

用法：
  python _etf_national_report.py            # 结论 + 简明表 + 写 CSV
  python _etf_national_report.py --push     # 额外输出推送文案（供守护调用）
  python _etf_national_report.py --top 5    # 只列前 N 只（按金额）
"""
import argparse
import csv
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA_DIR = os.path.join(ROOT, "data")
HIST_FILE = os.path.join(DATA_DIR, "_etf_share_hist.json")
DAILY_FILE = os.path.join(DATA_DIR, "_etf_share_daily.json")
SIGNAL_FILE = os.path.join(DATA_DIR, "_etf_share_signal.json")
CSV_FILE = os.path.join(DATA_DIR, "_etf_national_report.csv")


def _load(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def build_rows(hist, holders):
    """每只基金一行：份额、变化、四象限、汇金占比、机构持有变化、机构占净流动。"""
    rows = []
    for code, a in sorted((hist or {}).items()):
        ps = a.get("periods") or []
        last = ps[-1] if ps else {}
        h = (holders or {}).get(code) or {}
        at = h.get("attr") or {}
        hl = h.get("holder_list") or {}
        rows.append({
            "code": code,
            "name": (a.get("name") or h.get("name") or code)[:14],
            "end": last.get("end"),
            "shares_yi": round(last.get("shares") or 0, 2),
            "d_shares_pct": round(last.get("d_shares_pct") or 0, 1),
            "d_nav_pct": round(last.get("d_nav_pct") or 0, 1),
            "net_sub_yi": round(last.get("net_sub") or 0, 2),
            "quadrant": last.get("quadrant") or "-",
            "huijin_pct": hl.get("huijin_pct"),
            "org_pct_now": (at.get("now") or {}).get("org_pct"),
            "org_fen_prev": at.get("prev", {}).get("org_fen"),
            "org_fen_now": at.get("now", {}).get("org_fen"),
            "d_org_fen": at.get("d_org_fen"),
            "org_share_of_flow": at.get("org_share_of_flow"),
            "org_asof": (at.get("now") or {}).get("date"),
        })
    return rows


def fmt_huijin(v):
    return ("%.2f%%" % v) if v else "—"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--push", action="store_true", help="输出推送文案")
    ap.add_argument("--top", type=int, default=0, help="只列前 N 只")
    args = ap.parse_args()

    hist_all = _load(HIST_FILE, {})
    hist = hist_all.get("width") or {}
    agg = hist_all.get("agg") or {}
    holders = hist_all.get("holders") or {}
    sig = _load(SIGNAL_FILE, {})
    daily = _load(DAILY_FILE, {})
    j = sig.get("judge") or {}

    rows = build_rows(hist, holders)
    rows.sort(key=lambda r: -(r["shares_yi"] or 0))
    if args.top:
        rows = rows[:args.top]

    print("═" * 104)
    print("国家队（汇金）ETF 观测 —— %s" % time.strftime("%Y-%m-%d %H:%M"))
    print("═" * 104)
    for ln in (j.get("lines") or ["（无判断数据，请先跑 _etf_share_flow.py）"]):
        print("  " + ln)
    print("  ⇒ 判断：%s（置信 %s）" % (j.get("verdict", "?"), j.get("level", "?")))

    print()
    print("  %-7s %-15s %9s %9s %9s %-11s %8s %9s %10s"
          % ("代码", "名称", "份额(亿)", "份额%", "净值%", "四象限", "汇金%(年)", "机构%", "机构占流动"))
    for r in rows:
        print("  %-7s %-15s %9.2f %9.1f %9.1f %-11s %8s %9s %10s"
              % (r["code"], r["name"], r["shares_yi"], r["d_shares_pct"], r["d_nav_pct"],
                 r["quadrant"], fmt_huijin(r["huijin_pct"]),
                 ("%.2f" % r["org_pct_now"]) if r["org_pct_now"] is not None else "—",
                 ("%.0f%%" % (100 * r["org_share_of_flow"])) if r["org_share_of_flow"] else "—"))

    # 宽基合计
    ks = sorted(agg)
    if ks:
        print()
        print("  宽基合计净申赎（近 6 期）：")
        print("  %-12s %4s %16s %14s" % ("期末", "只数", "净申购(亿份)", "份额中位%"))
        for k in ks[-6:]:
            v = agg[k]
            s = v.get("sample") or []
            print("  %-12s %4d %16.2f %14.1f"
                  % (k, v.get("n", 0), v.get("net_sub", 0),
                     (sorted(s)[len(s) // 2] if s else 0)))

    # ── 简明 CSV ──
    cols = ["code", "name", "end", "shares_yi", "d_shares_pct", "d_nav_pct", "net_sub_yi",
            "quadrant", "huijin_pct", "org_asof", "org_pct_now", "org_fen_prev", "org_fen_now",
            "d_org_fen", "org_share_of_flow"]
    with open(CSV_FILE, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow(r)
    print("\n✓ CSV %s（%d 行）" % (CSV_FILE, len(rows)))

    if args.push:
        print("\n" + "─" * 104)
        print("推送文案：")
        print("─" * 104)
        print(build_text(j, rows, agg))
    return 0


def build_text(j, rows, agg):
    """推送文案：结论 + 逐只 + 拐点（清晰、带判断，不复述数字堆）。"""
    L = []
    L.append("🏛 国家队ETF份额｜%s" % (j.get("quarterly", {}).get("at") or time.strftime("%Y-%m-%d")))
    L.append("")
    L.append("📌 结论：%s（置信 %s）" % (j.get("verdict", "?"), j.get("level", "?")))
    for ln in (j.get("lines") or [])[:4]:
        L.append("· " + ln)
    if j.get("huijin"):
        h = j["huijin"]
        L.append("· 汇金身份确认：%s 年报前十名 %s 合计 %.2f%%" % (h["code"], "、".join(h["names"]), h["pct"]))
    L.append("")
    L.append("资金流向（额最大的 %d 只）" % min(len(rows), 8))
    for r in rows[:8]:
        L.append("· %s %s：份额 %.1f 亿（%+.1f%%）/ 净值 %+.1f%% → %s%s"
                 % (r["code"], r["name"], r["shares_yi"], r["d_shares_pct"], r["d_nav_pct"],
                    r["quadrant"],
                    ("；汇金年报持有 %s" % fmt_huijin(r["huijin_pct"])) if r["huijin_pct"] else ""))
    ks = sorted(agg)
    if ks:
        L.append("")
        L.append("拐点")
        lt = j.get("last_turn")
        if lt:
            cur = (j.get("quarterly") or {}).get("current", "")
            L.append("· 最近拐点 %s @ %s → 当前%s" % (lt["dir"], lt["at"], cur))
        L.append("· 近 3 期合计净申赎：" + " / ".join(
            "%s %+.0f亿" % (k, agg[k].get("net_sub", 0)) for k in ks[-3:]))
    L.append("")
    L.append("⚠️ 份额↓而价格↑ = 高位派发；份额↑而价格↓ = 低位承接（国家队在买）。")
    return "\n".join(L)


if __name__ == "__main__":
    sys.exit(main())
