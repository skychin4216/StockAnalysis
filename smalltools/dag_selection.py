# -*- coding: utf-8 -*-
"""
AutoQuant 1:1 同步 · 第②步 DAG 选股引擎（smalltools 侧复刻）
==========================================================
复刻 Android DAG 四周期 pipeline 的「选股核心链」：

  data_import → market_context(大盘方向) → adaptive_params(参数自适应)
  → stock_pool(候选池) → pool_filter(池过滤)
  → market_ma_unified(大盘MA收敛检测) → strict_selection(粘合严选)
  → bounce_reversal(反弹加分) → generate_orders(排序取前N)

数据敏感节点（financial_health / smart_money_filter / news_guard /
news_strength / ai_predict / sector_boost / seasonality_boost）在 smalltools
暂无对应数据源，引擎按 _dag_node_params.DATA_GATED_NODES 留接口跳过（日志注明）。

用法：
  python dag_selection.py --date 2026-08-25 --period 超短|短线|中线|长线|all
                          --engine dag|legacy --top 10
  --engine dag   ：完整 DAG 链（默认）
  --engine legacy：仅 strict_selection（原粘合链口径，用于 A/B 对比）

输出：
  stdout 选股清单 + _dag_pool_report.json
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import analyze_snaps, get_index_dir  # noqa: E402
from _full_cycle_backtest import INDEXES, load_cache       # noqa: E402
import _dag_node_params as D                               # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PARAMS_JSON = os.path.normpath(os.path.join(
    ROOT, "app", "src", "main", "assets", "backtest_params.json"))

PERIODS = ["超短", "短线", "中线", "长线"]


def load_shared_params():
    with open(PARAMS_JSON, "r", encoding="utf-8") as f:
        return json.load(f)


def filter_asof(snaps, asof):
    return [s for s in snaps if s["date"] <= asof]


def market_trend_asof(cache, asof):
    """market_context 节点：三指数 MA5/10/20 排列 tripleVote（复刻 StrategyMarketContext）"""
    dirs = []
    for secid in INDEXES:
        e = cache.get(secid) or {}
        sub = filter_asof(e.get("snaps", []), asof)
        if len(sub) < 20:
            dirs.append("UNKNOWN")
            continue
        dirs.append(get_index_dir(e.get("name", ""), sub))
    votes = [d for d in dirs if d != "UNKNOWN"]
    if len(votes) < 2:
        return "UNKNOWN"
    bull = votes.count("BULLISH")
    bear = votes.count("BEARISH")
    if bull >= 2:
        return "BULLISH"
    if bear >= 2:
        return "BEARISH"
    return "OSCILLATION"


def market_ma_convergence(cache, asof, threshold):
    """market_ma_unified 节点：上证指数 MA5/10/20 收敛度(%)，<= threshold 视为收敛"""
    e = cache.get("sh000001") or {}
    snaps = filter_asof(e.get("snaps", []), asof)
    if len(snaps) < 20:
        return None
    closes = [s["close"] for s in snaps]
    ma5 = sum(closes[-5:]) / 5
    ma10 = sum(closes[-10:]) / 10
    ma20 = sum(closes[-20:]) / 20
    mx, mn = max(ma5, ma10, ma20), min(ma5, ma10, ma20)
    return (mx - mn) / mn if mn > 0 else 999.0  # 小数比率（0.0077 = 0.77%）


def is_pool_eligible(code, name):
    """pool_filter 节点：复刻 StockPoolFilterNode 的排除规则"""
    c = code[2:] if code[:2] in ("sh", "sz") else code
    if not c.isdigit():
        return False
    if code.startswith("sh000") or code.startswith("sz399"):
        return False  # 上证/深证指数系列
    if c.startswith("300") or c.startswith("301"):
        return False  # 创业板
    if c.startswith("688") or c.startswith("689"):
        return False  # 科创板
    if c.startswith("8") or c.startswith("4"):
        return False  # 北交所
    if c.startswith("9"):
        return False  # B股
    if "ST" in (name or "").upper():
        return False
    return True


def bounce_bonus(cache, asof):
    """bounce_reversal 节点：大盘连续 N 天不创新低 → 加分"""
    cfg = D.NODE_PARAMS["bounce_reversal"]
    e = cache.get(cfg["indexCode"]) or {}
    snaps = filter_asof(e.get("snaps", []), asof)
    if len(snaps) < 4:
        return False
    prev_low = snaps[-4]["low"]
    return all(s["low"] >= prev_low for s in snaps[-3:])


def select(period, cache, asof, engine, top=10, gated_feed=None):
    """
    DAG 选股核心链执行。
    gated_feed: 可选 dict {code: {"finScore":.., "moneyScore":.., ...}}，
                数据敏感节点（财务健康/资金流/新闻）无此数据时自动跳过。
    返回 (hits, trend, converge, market_conv, bonus)。
    """
    params = load_shared_params().get("select_params", {}).get(period, {})
    trend = market_trend_asof(cache, asof)
    thr = D.COMMON["market_ma_unified"]["threshold"]
    converge = market_ma_convergence(cache, asof, thr)
    market_conv = converge is not None and converge <= thr
    bonus = bounce_bonus(cache, asof)
    bonus_pts = D.NODE_PARAMS["bounce_reversal"]["boostPoints"]

    if engine == "legacy":
        pool = [c for c, e in cache.items() if e.get("snaps")]
    else:
        pool = [c for c, e in cache.items()
                if e.get("snaps") and is_pool_eligible(c, e.get("name", ""))]

    hits = []
    for code in pool:
        e = cache[code]
        sub = filter_asof(e["snaps"], asof)
        if len(sub) < 20:
            continue
        # ── 数据敏感节点（默认跳过）──
        feed = (gated_feed or {}).get(code)
        if feed is not None and feed.get("finScore", 100) < D.NODE_PARAMS["financial_health"]["minScore"]:
            continue  # financial_health 排雷
        # ── strict_selection 粘合严选 ──
        r = analyze_snaps(sub, params, trend)
        if r.get("error") or not r.get("passed"):
            continue
        score = 100.0 - r["convergenceDegree"]
        if bonus:
            score += bonus_pts
        hits.append({
            "code": code,
            "name": e.get("name", ""),
            "close": r["close"],
            "convergenceDegree": round(r["convergenceDegree"], 2),
            "passCount": r["passCount"],
            "totalChecks": r["totalChecks"],
            "changePct": round(r["changePctReal"], 2),
            "drawdownPct": round(r["drawdownPct"], 1),
            "volumeRatio": round(r["volumeRatio"], 2),
            "score": round(score, 1),
            "bounceBonus": bonus,
            "regime": r["regime"],
        })
    hits.sort(key=lambda h: (-h["score"], h["convergenceDegree"]))
    return hits[:top], trend, converge, market_conv, bonus


def main():
    ap = argparse.ArgumentParser(description="DAG 选股引擎（smalltools 复刻）")
    ap.add_argument("--date", default="2026-08-25", help="选股截止日")
    ap.add_argument("--period", default="all", choices=PERIODS + ["all"])
    ap.add_argument("--engine", default="dag", choices=["dag", "legacy"])
    ap.add_argument("--top", type=int, default=10)
    args = ap.parse_args()

    cache = load_cache()
    shared = load_shared_params()
    periods = PERIODS if args.period == "all" else [args.period]

    print(f"===== DAG 选股引擎 ({args.engine}) · 截至 {args.date} =====")
    print(f"缓存 {len(cache)} 只 | 共享参数 backtest_params.json v{shared.get('version')}"
          f" | dag_nodes v{shared.get('dag_nodes', {}).get('version')}")

    result = {"date": args.date, "engine": args.engine, "periods": {}}
    for period in periods:
        hits, trend, converge, market_conv, bonus = select(
            period, cache, args.date, args.engine, args.top)
        result["periods"][period] = {
            "marketTrend": trend,
            "maConvergencePct": round(converge * 100, 2) if converge is not None else None,
            "marketConverged": market_conv,
            "bounceBonus": bonus,
            "hits": hits,
        }
        conv_txt = f" | MA收敛度 {converge * 100:.2f}%" if converge is not None else ""
        if market_conv:
            conv_txt += " ⚠️ 大盘均线收敛(减少开仓)"
        print(f"\n── {period} · 大盘方向 {trend}{conv_txt} · 反弹加分 {'是' if bonus else '否'} ──")
        if not hits:
            print("  无通过个股")
            continue
        for i, h in enumerate(hits, 1):
            print(f"  {i:>2}. {h['code']} {h['name']:<8} 收 {h['close']:>9.2f} "
                  f"粘合 {h['convergenceDegree']:>5.2f}% 过 {h['passCount']}/{h['totalChecks']} "
                  f"涨 {h['changePct']:>6.2f}% 回撤 {h['drawdownPct']:>6.1f}% 分 {h['score']:>6.1f}")

    gated = D.DATA_GATED_NODES
    if gated:
        print(f"\n⚠️ 数据敏感节点跳过(无对应数据源): {', '.join(gated.keys())}")
        print("   留接口: dag_selection.select(gated_feed={...}) 可注入财务/资金流数据后启用")

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_dag_pool_report.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    print(f"\nOK: 报告已写入 {out}")


if __name__ == "__main__":
    main()
