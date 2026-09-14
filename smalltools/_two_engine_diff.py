# -*- coding: utf-8 -*-
"""两套选股系统「同 asof」名单对比（研究用，不改推送口径）。

背景：推送表里 📋SmallTool 段会剔除「⭐DAG 已命中」的票（`_build_candidate_sections`
里 `if sid in dag_all: continue`）—— 那只是**展示层去重**，避免同一只票两段重复出现，
并不代表两套系统本身互斥。想看清「同一交易日两套引擎各自选了什么、差在哪」，必须
各自还原完整名单再对齐比较，本工具即为此而做。

  A = XML DAG 主线：AutoQuant/usecase_screen.py → AutoQuant/data/dag_screen_latest.json
      （APK/exe 共用同一份 assets/usecases XML；result 按 超短/短线/中线/长线 分组）
  B = SmallTool 引擎：smalltools → AutoQuant/data/candidates_quant.json
      （groups 按 短线/中线/长线/板块轮动 分组，项内 signal_period 标明实际周期）

用法:
  python _two_engine_diff.py                      # 默认当日两个归档
  python _two_engine_diff.py --dag <f> --st <f>
  python _two_engine_diff.py --json out.json      # 附带机器可读输出
  python _two_engine_diff.py --tech               # 逐条打印 tech 指标串（差异归因）
"""
import argparse
import json
import os

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
DEF_DAG = os.path.join(ROOT, "AutoQuant", "data", "dag_screen_latest.json")
DEF_ST = os.path.join(ROOT, "AutoQuant", "data", "candidates_quant.json")
PERIODS = ("超短", "短线", "中线", "长线", "板块轮动")


def _load(p):
    try:
        with open(p, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError) as e:
        print("读取失败 %s : %s" % (p, e))
        return {}


def side_a(dag):
    """DAG 主线 → {period: {secid: item}}（字段 code/name/score）。"""
    out = {}
    for p, items in (dag.get("result") or {}).items():
        d = {}
        for it in items or []:
            sid = it.get("code") or it.get("secid") or ""
            if sid:
                d[sid] = {"name": it.get("name") or "", "score": it.get("score"),
                          "tech": "", "raw": it}
        out[p] = d
    return out


def side_b(st):
    """SmallTool → {period: {secid: item}}；按 signal_period 归组，缺省落「板块轮动」。"""
    out = {}
    tech = st.get("dag_tech") or {}
    for gk, items in (st.get("groups") or {}).items():
        for it in items or []:
            sid = it.get("secid") or it.get("code") or ""
            if not sid:
                continue
            p = it.get("signal_period") or gk
            out.setdefault(p, {})[sid] = {
                "name": it.get("name") or "", "group": gk,
                "pass": it.get("pass"), "ratio": it.get("ratio"),
                "tech": it.get("tech") or tech.get(sid) or "", "raw": it}
    return out


def _fmt(sid, it):
    bits = "%s(%s)" % (it.get("name") or "?", sid)
    if it.get("score") is not None:
        bits += "[A评分%s]" % it["score"]
    if it.get("pass") is not None:
        bits += "[B过项%s]" % it["pass"]
    if it.get("tech"):
        bits += " " + it["tech"]
    return bits


def compare(a, b, periods):
    """→ (rows, dump)；rows 为逐 period 的对比结果。"""
    rows, dump = [], {}
    for p in periods:
        da, db = a.get(p) or {}, b.get(p) or {}
        if not da and not db:
            continue
        both = sorted(set(da) & set(db))
        only_a = sorted(set(da) - set(db))
        only_b = sorted(set(db) - set(da))
        rows.append((p, da, db, both, only_a, only_b))
        dump[p] = {
            "a_n": len(da), "b_n": len(db), "both_n": len(both),
            "both": [{"secid": k, "name": da[k].get("name") or db[k].get("name")} for k in both],
            "only_a": [{"secid": k, "name": da[k].get("name")} for k in only_a],
            "only_b": [{"secid": k, "name": db[k].get("name")} for k in only_b],
        }
    return rows, dump


def main(argv=None):
    ap = argparse.ArgumentParser(description="两套选股系统同 asof 名单对比")
    ap.add_argument("--dag", default=DEF_DAG)
    ap.add_argument("--st", default=DEF_ST)
    ap.add_argument("--json", default="", help="附带写出机器可读结果")
    ap.add_argument("--tech", action="store_true", help="逐条打印 tech 指标串")
    args = ap.parse_args(argv)

    dag, st = _load(args.dag), _load(args.st)
    if not dag or not st:
        return 2
    a, b = side_a(dag), side_b(st)
    periods = [p for p in PERIODS if (a.get(p) or b.get(p))]

    print("══ 两套选股系统对比 · asof=%s ══" % (dag.get("asof") or st.get("asof")))
    print("  A XML DAG 主线 : engine=%s fp=%s modules=%s degraded=%s"
          % (dag.get("engine"), dag.get("engine_fingerprint"),
             dag.get("engine_modules"), dag.get("degraded")))
    print("  B SmallTool    : generated_at=%s advice=%s"
          % (st.get("generated_at"), st.get("advice")))
    print("  ⚠ 推送表里 B 段会剔除 A 已命中的票（展示层去重）；本工具不剔除，还原完整名单。")

    rows, dump = compare(a, b, periods)
    for p, da, db, both, only_a, only_b in rows:
        print("\n── %s ──  A %d 只 / B %d 只 → 交集 %d" % (p, len(da), len(db), len(both)))
        if both:
            print("   [共同 %d] %s" % (len(both), "; ".join(
                _fmt(k, da[k]) if args.tech else "%s(%s)" % (da[k].get("name") or db[k].get("name"), k)
                for k in both)))
        if only_a:
            print("   [仅A %d] %s" % (len(only_a), "; ".join(
                _fmt(k, da[k]) if args.tech else "%s(%s)" % (da[k].get("name"), k)
                for k in only_a)))
        else:
            print("   [仅A 0] 无")
        if only_b:
            print("   [仅B %d] %s" % (len(only_b), "; ".join(
                _fmt(k, db[k]) if args.tech else "%s(%s)" % (db[k].get("name"), k)
                for k in only_b)))
        else:
            print("   [仅B 0] 无")

    # 汇总（去重后按 secid 计，跨 period 同一只票只算一次）
    sa, sb, na, nb = set(), set(), {}, {}
    for _p, da, db, _bo, _oa, _ob in rows:
        sa |= set(da)
        sb |= set(db)
        na.update({k: v.get("name") for k, v in da.items()})
        nb.update({k: v.get("name") for k, v in db.items()})
    print("\n── 汇总（跨周期去重）──")
    print("  A %d 只 / B %d 只 → 交集 %d、仅A %d、仅B %d（重叠率 %.0f%%）"
          % (len(sa), len(sb), len(sa & sb), len(sa - sb), len(sb - sa),
             (len(sa & sb) / float(len(sa | sb)) * 100) if (sa | sb) else 0))
    if sa & sb:
        print("  交集(%d): %s" % (len(sa & sb),
                                 ", ".join(sorted(na.get(k) or nb.get(k) or k for k in (sa & sb)))))
    if sa - sb:
        print("  仅A (%d): %s" % (len(sa - sb),
                                 ", ".join(sorted(na.get(k) or k for k in (sa - sb)))))
    if sb - sa:
        print("  仅B (%d): %s" % (len(sb - sa),
                                 ", ".join(sorted(nb.get(k) or k for k in (sb - sa)))))

    if args.json:
        payload = {"asof": dag.get("asof") or st.get("asof"),
                   "dag_file": args.dag, "st_file": args.st,
                   "a_total": len(sa), "b_total": len(sb),
                   "both_total": len(sa & sb), "periods": dump}
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=1)
        print("\n已写出 %s" % args.json)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
