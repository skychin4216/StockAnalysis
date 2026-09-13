# -*- coding: utf-8 -*-
"""节点执行核对 / bypass 审计（XML DAG 主线，双端同源 usecase_pipeline.py）

用途（用户需求「确认一下有哪些 node 没有执行(bypass)」）：
  把全部 usecase 在同一 asof 上跑一遍，逐个 node 判定执行状态，输出三类清单：
    · bypassed  —— **真未执行**：notes 含「未实现 module=」（引擎无该 module 实现）
                    或「降级为透传」（回测/离线无对应数据，node 空跑不产出）
                    或「执行失败」
    · passthrough —— 执行了但 stage 输出为 None（消费型 node，无输出即空跑）
    · executed  —— 有 stage 输出（正常产出）
  与 APK 侧 UseCaseLoader/NodeRegistry 同源：APK 未注册的 module 在手机上同样 bypass，
  故本报告可直接暴露「PC 能跑 / 手机不能跑」的双端缺口。

定位（2026-09-12 用户明确）：
  **内部自检 / 自我调试工具，只给 exe / apk / codebuddy 在跑完之后自己核对用，
  绝不推送给客户、不进企微群。** 不要在任何推送链路里引用本脚本。
  用例清单自动扫描 `*_usecase.xml`（新增 usecase 无需改本脚本即被覆盖）。

用法：
  python _nodes_exec_report.py                        # asof = _kline_cache.json 最新交易日
  python _nodes_exec_report.py --asof 2026-09-11
  python _nodes_exec_report.py --asof 2026-09-11 --json data/_nodes_exec_report.json
  python _nodes_exec_report.py --selfcheck            # 自检模式：精简输出 + 退出码

自检退出码（供 exe / apk / codebuddy 运行完成后判定「这次跑干净了没有」）：
  0 = 全绿（无 bypass / 无静默跳过 / 无异常）
  2 = 有 bypass 或静默跳过（引擎缺 module / 无数据降级 —— 结果可能不完整）
  3 = 有 usecase 执行异常

数据源：smalltools/_kline_cache.json（个股+指数）、smalltools/_etf_cache.json（ETF 本体）。
规则/参数源：app/src/main/assets/usecases/*.xml（唯一事实源）。
"""
import argparse
import datetime
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
USECASES = os.path.join(ROOT, "app", "src", "main", "assets", "usecases")
for _p in (HERE, USECASES):
    if _p not in sys.path:
        sys.path.insert(0, _p)

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import usecase_pipeline as up  # noqa: E402

KLINE_FILE = os.path.join(HERE, "_kline_cache.json")
ETF_FILE = os.path.join(HERE, "_etf_cache.json")

# (usecase_id, period, 数据源 kline|etf|merged)
CASES = [
    ("ultra_short", "ultra_short", "kline"),
    ("short", "short", "kline"),
    ("mid", "mid", "kline"),
    ("long", "long", "kline"),
    ("dip_buy", "ultra_short", "kline"),
    ("real_holding", "mid", "merged"),
    ("t_trade", "short", "kline"),
    ("stock_deep_analysis", "short", "kline"),
    ("direction", "short", "kline"),
    ("screening", "short", "kline"),
    ("sector_ambush", "short", "kline"),
    ("etf_dip", "mid", "etf"),
    ("etf_holdings_top5", "mid", "merged"),
    ("etf_industry_scan", "mid", "merged"),
    ("etf_pure_screen", "mid", "etf"),
]

BYPASS_MARKS = ("未实现 module", "降级为透传", "执行失败")

_CASES_ID = {c[0] for c in CASES}
# 同一条 usecase 的多种写法（与 usecase_pipeline.USECASE_FILE 的别名保持一致）
_CASE_ALIAS = {"short_term": "short", "mid_term": "mid", "long_term": "long"}


def _discover_extra_cases():
    """扫描 usecases/*_usecase.xml，补上 CASES 未显式列出的 usecase。

    用户口径「覆盖全部 usecase」：目录里新增/改名 usecase 后不必再改本脚本也会被审计。
    已显式列出的保留其精确 (period, 数据源)；未列出的按文件名推断周期，数据源统一
    merged（kline+etf 超集，避免因数据源挑错把整段用例静默跳过）。
    """
    known = set(_CASES_ID)
    out = []
    try:
        files = sorted(os.listdir(USECASES))
    except OSError:
        return out
    for fn in files:
        if not fn.endswith("_usecase.xml"):
            continue
        uid = _CASE_ALIAS.get(fn[: -len("_usecase.xml")], fn[: -len("_usecase.xml")])
        if uid in known:
            continue
        known.add(uid)
        period = "mid"
        for pref, per in (("ultra_short", "ultra_short"), ("short", "short"),
                          ("mid", "mid"), ("long", "long")):
            if uid.startswith(pref):
                period = per
                break
        out.append((uid, period, "merged"))
    return out


def _load(path):
    if not os.path.exists(path):
        return {}
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _cache_for(kind, kline, etf):
    if kind == "etf":
        return dict(etf)
    if kind == "kline":
        return dict(kline)
    merged = dict(kline)
    merged.update(etf)   # ETF 键覆盖同名指数键（更新更近）
    return merged


def _latest_asof(kline, etf):
    ds = set()
    for c in (kline, etf):
        for e in c.values():
            for s in e.get("snaps") or []:
                ds.add(s["date"])
    return max(ds) if ds else datetime.date.today().isoformat()


def audit(asof=None):
    kline, etf = _load(KLINE_FILE), _load(ETF_FILE)
    asof = asof or _latest_asof(kline, etf)
    cases = CASES + _discover_extra_cases()
    report = {"asof": asof, "generated_at": datetime.datetime.now().isoformat(timespec="seconds"),
              "cases": [], "bypassed": [], "errors": [], "notes": []}
    print("节点执行核对 asof=%s（kline %d / etf %d，usecase %d 个）"
          % (asof, len(kline), len(etf), len(cases)))
    for uc_id, period, kind in cases:
        cache = _cache_for(kind, kline, etf)
        if not cache:
            print("  [跳过] %s：数据源 %s 为空" % (uc_id, kind))
            continue
        try:
            runner = up.UseCaseRunner(uc_id, cache=cache, asof=asof, period=period)
            res = runner.run(with_stages=True)
        except FileNotFoundError as e:
            print("  [跳过] %s：%s" % (uc_id, e))
            continue
        except Exception as e:  # noqa: BLE001
            print("  [异常] %s：%s: %s" % (uc_id, type(e).__name__, e))
            report["notes"].append("[%s] 执行异常: %s" % (uc_id, e))
            report["errors"].append({"usecase": uc_id, "error": "%s: %s" % (type(e).__name__, e)})
            continue
        stages = res.get("stages") or {}
        notes = res.get("notes") or []
        bypass_detail = {}
        for n in notes:
            if not (n.startswith("[") and any(m in n for m in BYPASS_MARKS)):
                continue
            bypass_detail[n[1:].split("]")[0]] = next(
                (m for m in BYPASS_MARKS if m in n), "其他")
        bypass_nids = sorted(bypass_detail)
        passthrough_nids = sorted(k for k, v in stages.items() if v is None)
        executed_nids = sorted(k for k, v in stages.items() if v is not None)
        # 汇总 pipeline 里声明过、但既无 stage 也无 note 的 node（引擎静默跳过）
        silent = []
        try:
            for ref, _cond, _nm in up.load_usecase(uc_id)["steps"]:
                path = os.path.normpath(os.path.join(USECASES, "..", ref))
                if not os.path.exists(path):
                    path = ref
                pl = up.parse_pipeline(path)
                for nid, node in pl.nodes.items():
                    if nid not in stages and nid not in bypass_nids:
                        silent.append(nid)
        except Exception:  # noqa: BLE001
            pass
        case = {"usecase": uc_id, "period": period, "source": kind,
                "orders": len(res.get("orders") or []),
                "market_state": res.get("market_state"), "direction": res.get("direction"),
                "executed": executed_nids, "passthrough": passthrough_nids,
                "bypassed": bypass_nids, "bypass_reasons": bypass_detail,
                "silent": sorted(set(silent)), "notes": notes}
        report["cases"].append(case)
        for nid in bypass_nids:
            report["bypassed"].append({"usecase": uc_id, "node": nid,
                                       "reason": bypass_detail.get(nid, "其他")})
        if bypass_nids:
            print("  [bypass] %-20s %s" % (uc_id, ", ".join(bypass_nids)))
        else:
            print("  [ok]     %-20s 订单 %d（执行 %d / 透传 %d）"
                  % (uc_id, len(res.get("orders") or []), len(executed_nids),
                     len(passthrough_nids)))
    report["bypass_count"] = len(report["bypassed"])
    # 静默跳过 = pipeline 声明过、但引擎既没执行也没留 note 的 node（"悄悄没跑"）
    report["silent_count"] = sum(len(c.get("silent") or []) for c in report["cases"])
    report["error_count"] = len(report["errors"])
    reasons = {}
    for b in report["bypassed"]:
        reasons[b["reason"]] = reasons.get(b["reason"], 0) + 1
    report["bypass_reasons"] = reasons
    report["ok"] = (report["bypass_count"] == 0 and report["silent_count"] == 0
                    and report["error_count"] == 0)
    return report


def _bypass_keys(items):
    return {"%s::%s" % (b["usecase"], b["node"]) for b in (items or [])}


def baseline_diff(rep, baseline):
    """与基线对比 → (本次新增未执行节点, 本次已恢复节点)。

    审计跑在"只有 kline+etf 缓存"的 PC 环境，天然会有一批「数据缺失降级为透传」
    的节点，逐次都红等于没红。所以自检以**回归**为准：基线里已有的不算问题，
    只报本次新增（新漏跑）和本次恢复（修好了）。
    """
    cur = _bypass_keys(rep["bypassed"])
    old = _bypass_keys(baseline.get("bypassed"))
    return sorted(cur - old), sorted(old - cur)


def selfcheck_code(rep, baseline=None):
    """自检退出码：0 无异常且无回归 / 1 相比基线新增 bypass / 3 有用例抛异常。

    供 exe / apk / codebuddy 在跑完一轮后调用，判定"这次跑干净了没有"。
    首次使用先 `--update-baseline` 落一份基线，之后每次跑只看增量。
    """
    if rep.get("error_count"):
        return 3
    if baseline:
        new, _ = baseline_diff(rep, baseline)
        return 1 if new else 0
    return 1 if (rep.get("bypass_count") or rep.get("silent_count")) else 0


def main():
    ap = argparse.ArgumentParser(description="XML DAG 节点执行核对 / bypass 审计（内部自检，不对外推送）")
    ap.add_argument("--asof", default=None, help="核对日期 YYYY-MM-DD（默认缓存最新交易日）")
    ap.add_argument("--json", default=os.path.join(HERE, "data", "_nodes_exec_report.json"),
                    help="报告输出 json 路径")
    ap.add_argument("--selfcheck", action="store_true",
                    help="自检模式：精简输出 + 退出码（0 无回归 / 1 新增 bypass / 3 有异常）")
    ap.add_argument("--baseline", default=os.path.join(HERE, "data", "_nodes_exec_baseline.json"),
                    help="自检基线 json（--selfcheck 时据此判定回归）")
    ap.add_argument("--update-baseline", action="store_true",
                    help="把本次结果写为自检基线（确认当前 bypass 都属预期后再执行）")
    args = ap.parse_args()
    rep = audit(args.asof)

    baseline = None
    if os.path.exists(args.baseline):
        try:
            with open(args.baseline, encoding="utf-8") as f:
                baseline = json.load(f)
        except Exception:  # noqa: BLE001
            baseline = None
    new, fixed = baseline_diff(rep, baseline) if baseline else ([], [])
    code = selfcheck_code(rep, baseline)

    if args.update_baseline:
        os.makedirs(os.path.dirname(os.path.abspath(args.baseline)), exist_ok=True)
        with open(args.baseline, "w", encoding="utf-8") as f:
            json.dump({"asof": rep["asof"], "generated_at": rep["generated_at"],
                       "bypassed": rep["bypassed"], "silent_count": rep["silent_count"]},
                      f, ensure_ascii=False, indent=1)
        print("基线 → %s（%d 条 bypass 记为已知）" % (args.baseline, rep["bypass_count"]))
    if args.json:
        os.makedirs(os.path.dirname(os.path.abspath(args.json)), exist_ok=True)
        rep["selfcheck"] = {"code": code, "ok": rep["ok"],
                            "bypass_count": rep["bypass_count"],
                            "silent_count": rep["silent_count"],
                            "error_count": rep["error_count"],
                            "new": new, "fixed": fixed,
                            "baseline": os.path.basename(args.baseline) if baseline else None}
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(rep, f, ensure_ascii=False, indent=1)
        print("报告 → %s" % args.json)
    if args.selfcheck:
        # 单行机器可读摘要：exe / apk / codebuddy 跑完后直接抓这一行判定
        print("SELFCHECK code=%d bypass=%d new=%d fixed=%d silent=%d error=%d asof=%s"
              % (code, rep["bypass_count"], len(new), len(fixed),
                 rep["silent_count"], rep["error_count"], rep["asof"]))
        for k in new:
            print("  NEW  : %s" % k)
        for k in fixed:
            print("  FIXED: %s" % k)
        for e in rep["errors"]:
            print("  ERROR: %s :: %s" % (e["usecase"], e["error"]))
        return code
    print("bypass 节点合计：%d %s" % (rep["bypass_count"], rep["bypass_reasons"]))
    for b in rep["bypassed"]:
        print("  - %s :: %s (%s)" % (b["usecase"], b["node"], b["reason"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
