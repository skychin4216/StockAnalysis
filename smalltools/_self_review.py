# -*- coding: utf-8 -*-
"""每日选股「记忆 + 自检」闭环 v1（2026-09-06 用户需求 #1 落地）

背景（架构一句话）：
  实盘选股 = XML DAG 主线（AutoQuant/usecase_screen.py → dag_screen_latest.json，
  exe/APK 同源）+ smalltools 轮选候选（_publish_candidates.py run_once →
  candidates_quant.json）。两者均只保留"当日最新"文件，历史选股没有记忆。
  本模块把每次实盘选股写成只增账本，待缓存覆盖完整持仓窗口后，用【当时快照的
  卖出规则】确定性回放结算已实现收益，累计为 (周期×大盘状态) 命中率，与
  walk-forward 样本外基线对比，输出带护栏的收紧/放松建议（默认 dry，不自动影响推送）。

三端统一参数事实源：app/src/main/assets/backtest_params.json（sell_rules 9 格矩阵）。
自检口径与 walk-forward（_walk_forward.py / _full_cycle_backtest.simulate_trade）完全一致：
  信号日 asof → 次日开盘买入 → 按当时规则持有/连跌卖/止盈/止损/做T → 已实现 ret%。
到期判定（防尾部截断偏差）：缓存需覆盖到 buy_idx + maxHold（最大持仓窗口）之后，
否则视为 pending 不下结论（长线需约 40 交易日才能结算，属正常记忆周期）。

产出（smalltools/_records/ 下）：
  _pick_ledger.jsonl      只增账本：每交易日一条 {asof,state,rules,picks,recorded_at}
  _pick_reviews.json      结算库：{key: outcome}，key=(asof,period,secid)
  _selfreview_report.json 最近一次复盘摘要（JSON + 可读文本，供 15:10 总结追加）
  _selflearn_state.json   自学习状态：mode(off/dry/live)/policy/baseline/gate/feedback_log

用法：
  python _self_review.py --record            # 收盘后快照当日选股（幂等，需缓存覆盖 asof）
  python _self_review.py --evaluate          # 结算已到期的历史信号
  python _self_review.py --report            # 刷新复盘摘要（含 feedback 漂移检测）
  python _self_review.py --push              # 将摘要推送微信（app_config notify）
  python _self_review.py --status            # 账本/已评估/状态一览
  python _self_review.py --asof 2026-09-04 --record --evaluate --report   # 指定日期
  无参数 = --record --evaluate --report
"""
import argparse
import datetime
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# 轻量依赖：不 import _publish_candidates（其顶层会拉起 network 模块），只取回测核心
from _full_cycle_backtest import (  # noqa: E402
    CACHE as KLINE_CACHE_FILE, INDEXES, load_cache, market_state,
    sell_rule_for, simulate_trade)
import push_channel  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RECORDS = os.path.join(HERE, "_records")
LEDGER = os.path.join(RECORDS, "_pick_ledger.jsonl")
REVIEW_DB = os.path.join(RECORDS, "_pick_reviews.json")
REPORT_FILE = os.path.join(RECORDS, "_selfreview_report.json")
STATE_FILE = os.path.join(RECORDS, "_selflearn_state.json")
LOG_FILE = os.path.join(RECORDS, "_self_review.log")

DAG_FILE = os.path.normpath(os.path.join(ROOT, "AutoQuant", "data", "dag_screen_latest.json"))
CAND_FILE = os.path.normpath(os.path.join(ROOT, "AutoQuant", "data", "candidates_quant.json"))
APP_CONFIG = os.path.join(ROOT, "app", "src", "main", "assets", "data", "app_config.json")

PERIODS = ["超短", "短线", "中线", "长线"]
PERIOD_HORIZON_HINT = {"超短": "1个交易日", "短线": "≤10个交易日", "中线": "≤20个交易日", "长线": "≤40个交易日"}

# 自学习策略（护栏）——防止参数震荡
DEFAULT_POLICY = {
    "mode": "dry",        # off=只记账；dry=记账+出建议不生效；live=由守护消费 adaptive_gate()
    "min_samples": 10,    # 某周期近窗至少 N 笔才允许触发调整
    "window": 30,         # 近窗口径：最近 window 笔已结算
    "tighten_gap_pp": -10.0,  # 近窗胜率 - 基线 ≤ -10pp → 建议收紧 +1
    "ease_gap_pp": 5.0,       # 近窗胜率 - 基线 ≥ +5pp → 允许回退 1 档（最多回 0）
    "max_extra_pass": 2,      # extra_pass 上界（防止过度收紧样本耗尽）
}
RATIO_MIN_PASS_BASE = {"超短": 5, "短线": 5, "中线": 5, "长线": 5}  # 建议文本里的 pass 基准（仅供参考）


def _log(msg):
    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    try:
        os.makedirs(RECORDS, exist_ok=True)
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write("[%s] %s\n" % (ts, msg))
    except OSError:
        pass


def load_notify_cfg():
    try:
        with open(APP_CONFIG, encoding="utf-8") as f:
            return (json.load(f).get("notify") or {})
    except (OSError, ValueError):
        return {}


# ---------------------------------------------------------------- 缓存与日期
_CACHE_MEMO = {"key": None}


def load_cache_maps():
    """返回 (cache, all_dates, date_to_idx)。all_dates = 全池交易日并集（升序）。

    进程内记忆（_kline_cache.json ≈135MB，单次 CLI/守护子进程内避免重复解析）。
    """
    mtime = None
    try:
        mtime = os.path.getmtime(KLINE_CACHE_FILE)
    except OSError:
        pass
    key = mtime
    if _CACHE_MEMO["key"] == key and _CACHE_MEMO.get("maps"):
        return _CACHE_MEMO["maps"]
    cache = load_cache()
    all_dates = sorted({s["date"] for e in cache.values() for s in e.get("snaps", [])})
    date_to_idx = {d: i for i, d in enumerate(all_dates)}
    maps = (cache, all_dates, date_to_idx)
    _CACHE_MEMO["key"] = key
    _CACHE_MEMO["maps"] = maps
    return maps


def state_on(cache, all_dates, date_to_idx, asof):
    idx_snaps = {s: cache.get(s, {}).get("snaps", []) for s in INDEXES}
    return market_state(idx_snaps, asof, all_dates, date_to_idx)


def close_on(cache, secid, asof):
    snaps = cache.get(secid, {}).get("snaps") or []
    for s in snaps:
        if s["date"] == asof:
            return s.get("close")
    return None


def rules_snapshot(state):
    """当时卖出规则快照：按周期取该大盘状态将用的标准矩阵规则（CRASH→BEARISH 由引擎映射）。"""
    out = {}
    for p in PERIODS:
        r = sell_rule_for(p, state)
        out[p] = r
    return out


# ---------------------------------------------------------------- 快照(记忆)
def read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def normalize_dag(dag):
    """dag_screen_latest.json → [(secid, name, period, extra)]"""
    out = []
    if not dag or not isinstance(dag, dict):
        return out
    for period, items in (dag.get("result") or {}).items():
        if period not in PERIODS:
            continue
        for it in items or []:
            secid = (it.get("code") or it.get("secid") or "").strip().lower()
            if not secid:
                continue
            out.append({"secid": secid, "name": it.get("name") or "", "period": period,
                        "src": "dag"})
    return out


def normalize_candidates(cand):
    """candidates_quant.json → [(secid, name, period, pass, ratio)]，来源 round/prepared。"""
    out = []
    if not cand or not isinstance(cand, dict):
        return out
    for period, items in (cand.get("groups") or {}).items():
        if period not in PERIODS:
            continue
        for it in items or []:
            secid = (it.get("secid") or "").strip().lower()
            if not secid:
                continue
            out.append({"secid": secid, "name": it.get("name") or "", "period": period,
                        "src": "round",
                        "pass": it.get("pass"), "ratio": it.get("ratio")})
    for it in cand.get("prepared") or []:
        period = it.get("period")
        secid = (it.get("secid") or "").strip().lower()
        if period not in PERIODS or not secid:
            continue
        out.append({"secid": secid, "name": it.get("name") or "", "period": period,
                    "src": "prepared",
                    "pass": it.get("pass"), "ratio": it.get("ratio")})
    return out


def ledger_already(asof):
    if not os.path.exists(LEDGER):
        return False
    try:
        with open(LEDGER, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    if json.loads(line).get("asof") == asof:
                        return True
                except ValueError:
                    continue
    except OSError:
        return False
    return False


def append_ledger(rec):
    os.makedirs(RECORDS, exist_ok=True)
    with open(LEDGER, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


def record_day(asof=None, dag=None, cand=None, cache=None, all_dates=None, date_to_idx=None):
    """收盘后快照当日选股 → 只增账本（幂等：同日已记录则跳过）。

    asof 默认取 dag 文件自身日期；仅当缓存已覆盖该日才记录（尾盘拉取前不写）。
    """
    dag = dag if dag is not None else read_json(DAG_FILE)
    cand = cand if cand is not None else read_json(CAND_FILE)
    if dag:
        dag_asof = str(dag.get("asof") or "").strip()
        if asof is None:
            asof = dag_asof
        elif dag_asof and dag_asof != asof:
            _log("警告：dag asof=%s 与指定 asof=%s 不一致，以指定为准" % (dag_asof, asof))
    elif cand:
        cand_asof = str(cand.get("asof") or "").strip()
        if asof is None:
            asof = cand_asof
    if not asof:
        print("未找到选股文件（dag/candidates），跳过记录")
        return 1
    if cache is None:
        cache, all_dates, date_to_idx = load_cache_maps()
    if asof not in date_to_idx:
        print("缓存尚未覆盖 %s（待尾盘 K 线拉取后再记录）" % asof)
        return 1
    if ledger_already(asof):
        print("账本已含 %s，幂等跳过" % asof)
        return 0
    # 合并 dag + round/prepared，同日同周期同标的去重（来源合并标注）
    picks = {}
    for it in normalize_dag(dag) + normalize_candidates(cand):
        key = (it["period"], it["secid"])
        cur = picks.get(key)
        if cur is None:
            cur = {"secid": it["secid"], "name": it["name"], "period": it["period"],
                   "src": it["src"], "pass": it.get("pass"), "ratio": it.get("ratio"),
                   "close": close_on(cache, it["secid"], asof)}
            picks[key] = cur
        else:
            srcs = set(str(cur["src"]).split("+"))
            srcs.add(it["src"])
            cur["src"] = "+".join(sorted(srcs))
            if cur.get("pass") is None and it.get("pass") is not None:
                cur["pass"] = it["pass"]
            if cur.get("ratio") is None and it.get("ratio") is not None:
                cur["ratio"] = it["ratio"]
    state = state_on(cache, all_dates, date_to_idx, asof)
    rec = {"asof": asof, "state": state,
           "rules": rules_snapshot(state),
           "picks": sorted(picks.values(), key=lambda x: (PERIODS.index(x["period"]), x["secid"])),
           "recorded_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")}
    append_ledger(rec)
    print("已记录 %s | 大盘 %s | 候选 %d 只%s" % (
        asof, state, len(rec["picks"]),
        "" if rec["picks"] else "（当日无主线/轮选命中，仍留痕）"))
    _log("record %s state=%s picks=%d" % (asof, state, len(rec["picks"])))
    return 0


# ---------------------------------------------------------------- 结算(自检)
def review_key(asof, period, secid):
    return "%s|%s|%s" % (asof, period, secid)


def load_reviews():
    d = read_json(REVIEW_DB) or {}
    return d if isinstance(d, dict) else {}


def save_reviews(d):
    os.makedirs(RECORDS, exist_ok=True)
    tmp = REVIEW_DB + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False)
    os.replace(tmp, REVIEW_DB)


def _matured(buy_idx, rule, n_dates):
    """最大持仓窗口已完整覆盖 → 可结算（防尾部截断偏差）。
    simulate_trade 需访问 buy_idx+maxHold 这根（含），故要求该索引 < n_dates。"""
    hold = int((rule or {}).get("maxHold") or 0)
    if hold <= 0:
        return True
    return buy_idx + hold < n_dates


def evaluate_pending(verbose=True):
    """遍历账本中尚未结算（或结算失败需重试）的信号，到期者确定性回放结算。"""
    if not os.path.exists(LEDGER):
        print("账本为空，无待结算信号")
        return 0
    cache, all_dates, date_to_idx = load_cache_maps()
    n_dates = len(all_dates)
    db = load_reviews()
    changed = 0
    pending = 0
    with open(LEDGER, encoding="utf-8") as f:
        lines = [l for l in f.read().splitlines() if l.strip()]
    for line in lines:
        try:
            rec = json.loads(line)
        except ValueError:
            continue
        asof = rec.get("asof")
        if asof not in date_to_idx:
            continue
        buy_idx = date_to_idx[asof] + 1
        if buy_idx >= n_dates:
            pending += len(rec.get("picks") or [])
            continue
        state = rec.get("state")
        rules = rec.get("rules") or {}
        for it in rec.get("picks") or []:
            secid, period = it.get("secid"), it.get("period")
            if not secid or period not in PERIODS:
                continue
            if secid not in cache:
                continue
            key = review_key(asof, period, secid)
            if key in db:
                continue  # 已结算（确定性结果，不重复）
            rule = rules.get(period) or sell_rule_for(period, state)
            if not _matured(buy_idx, rule, n_dates):
                pending += 1
                continue
            sig = (secid, it.get("name") or secid, asof, buy_idx, state or "UNKNOWN")
            try:
                t = simulate_trade(cache, all_dates, sig, rule)
            except Exception as e:  # noqa: BLE001
                _log("evaluate error %s %s %s: %s" % (asof, period, secid, e))
                continue
            if t is None:
                continue  # 复牌缺口等导致无成交：跳过，不重复尝试
            db[key] = {"secid": secid, "name": it.get("name") or t.get("name") or secid,
                       "period": period, "asof": asof, "state": state or t.get("state"),
                       "src": it.get("src"), "pass": it.get("pass"), "ratio": it.get("ratio"),
                       "close_at_pick": it.get("close"),
                       "buy": t.get("buy"), "sell": t.get("sell"),
                       "entry": t.get("entry"), "exit": t.get("exit"),
                       "ret": t.get("ret"), "reason": t.get("reason"),
                       "evaluated_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")}
            changed += 1
    if changed:
        save_reviews(db)
    if verbose:
        print("自检结算：新增 %d 笔，未到期 %d 笔" % (changed, pending))
    return changed


# ---------------------------------------------------------------- 基线
def build_baseline():
    """walk-forward 样本外基线：(周期, 状态) → {n, wr, avg}。取自 selected_YYYY-MM.json 的已实现 trades。"""
    base = {}
    if not os.path.isdir(RECORDS):
        return base
    files = sorted(f for f in os.listdir(RECORDS)
                   if f.startswith("selected_") and f.endswith(".json"))
    by = {p: {} for p in PERIODS}
    for fn in files:
        try:
            rec = json.load(open(os.path.join(RECORDS, fn), encoding="utf-8"))
        except (OSError, ValueError):
            continue
        for p in PERIODS:
            trades = rec.get("trades", {}).get(p) or []
            agg = by[p]
            for t in trades:
                st = t.get("state") or "UNKNOWN"
                cell = agg.setdefault(st, [0, 0, 0.0])
                cell[0] += 1
                cell[1] += 1 if (t.get("ret") or 0) > 0 else 0
                cell[2] += t.get("ret") or 0
    for p, agg in by.items():
        for st, (n, wins, sret) in agg.items():
            if n >= 3:
                base.setdefault(p, {})[st] = {
                    "n": n, "wr": wins / n * 100, "avg": sret / n}
    return base


def baseline_period_default():
    """summary.json 里各周期总体样本外基线（无状态细分时的兜底锚）。"""
    d = read_json(os.path.join(RECORDS, "summary.json")) or {}
    return {p: v for p, v in d.items() if p in PERIODS and isinstance(v, dict)}


# ---------------------------------------------------------------- 反馈(漂移检测)
def load_state():
    d = read_json(STATE_FILE)
    if not isinstance(d, dict):
        d = {}
    d.setdefault("mode", DEFAULT_POLICY["mode"])
    d.setdefault("policy", DEFAULT_POLICY)
    d.setdefault("gate", {p: {"extra_pass": 0, "state": "", "since": "", "why": ""} for p in PERIODS})
    d.setdefault("feedback_log", [])
    return d


def save_state(d):
    os.makedirs(RECORDS, exist_ok=True)
    d["updated_at"] = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=1)


def feedback(db, state_d, baseline):
    """近窗(最近 window 笔)命中率 vs 基线 → 带护栏的 extra_pass 档位更新。

    仅按周期整体触发（样本量优先）；报告中另给状态细分供人工/后续按状态调节。
    mode=off/dry 只记录档位与建议，不改变任何推送行为。
    """
    policy = state_d["policy"]
    gate = state_d["gate"]
    can_act = policy["mode"] != "off"   # off=只记账不改档位；dry=改档位但无消费方；live=守护消费
    log = []
    for p in PERIODS:
        rows = sorted((v for v in db.values() if v.get("period") == p and v.get("ret") is not None),
                      key=lambda v: v["asof"])
        if not rows:
            continue
        recent = rows[-policy["window"]:]
        n = len(recent)
        wr = sum(1 for v in recent if v["ret"] > 0) / n * 100 if n else 0.0
        avg = sum(v["ret"] for v in recent) / n if n else 0.0
        # 基线：优先该周期主流状态（样本最多）的胜率，否则总体 summary
        st_counts = {}
        for v in rows:
            st_counts[v.get("state")] = st_counts.get(v.get("state"), 0) + 1
        dom_st = max(st_counts, key=st_counts.get) if st_counts else None
        b = (baseline.get(p) or {}).get(dom_st or "") or {}
        if not b:
            b = baseline_period_default().get(p) or {}
        base_wr = b.get("wr")
        gap = (wr - base_wr) if (base_wr is not None and n) else None
        cur = gate[p]
        action = None
        if can_act and n >= policy["min_samples"] and gap is not None:
            if gap <= policy["tighten_gap_pp"] and cur["extra_pass"] < policy["max_extra_pass"]:
                cur["extra_pass"] += 1
                action = "收紧+1"
            elif gap >= policy["ease_gap_pp"] and cur["extra_pass"] > 0:
                cur["extra_pass"] -= 1
                action = "放松-1"
        if action:
            cur["state"] = dom_st or ""
            cur["since"] = datetime.datetime.now().strftime("%Y-%m-%d")
            cur["why"] = "近%d笔wr=%.1f%% vs 基线%s%.1f%%" % (
                n, wr, (dom_st + " ") if dom_st else "", base_wr or 0)
        log.append({"period": p, "n_recent": n, "wr_recent": round(wr, 1),
                    "avg_recent": round(avg, 2), "dom_state": dom_st,
                    "base_wr": round(base_wr, 1) if base_wr is not None else None,
                    "gap_pp": round(gap, 1) if gap is not None else None,
                    "extra_pass": cur["extra_pass"], "action": action or "-",
                    "mode": policy["mode"]})
    if any(x["action"] != "-" for x in log):
        state_d["feedback_log"].append({
            "ts": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "events": [x for x in log if x["action"] != "-"]})
        state_d["feedback_log"] = state_d["feedback_log"][-20:]
    save_state(state_d)
    return log


# ---------------------------------------------------------------- 盘面应对（T+1 预案）
# 2026-09-06：把「刚买入/持有中的信号票」从到期才结账升级为每日收盘巡诊 + T+1 预案。
# 输入只依赖 _kline_cache.json + COS phone 镜像，离线可用（三指数近似成长/权重风格：
# 上证=权重蓝筹、创业板=成长/科技杀情绪，科创50/半导体等板块急跌以创业板+个股相对表现代理）。
# 借鉴 deepseek_ETF 思路：①低位≠安全——急跌日不接飞刀，开仓需『放量企稳』二次确认；
#   ②板块/风格切换与系统性下跌分开处理：切换→轮动（可继续选股）；系统性→止损/降仓防守。
RISK_LV_NORMAL, RISK_LV_ROTATE, RISK_LV_STORM = 1, 2, 3
RISK_TITLE = {1: "正常波动", 2: "风格/板块切换·转弱", 3: "系统性急跌·防守"}
IDX_NAME = {"sh000001": "上证", "sz399001": "深成", "sz399006": "创业板",
            "sh000688": "科创50"}  # sh000688 2026-09-06 起纳入 _kline_cache 池（腾讯日K，2021 起全史）
# 分级阈值（单位 %，对三指数平均/单指数 1/3 日涨跌幅）
L3_AVG1D_ANY = -2.8      # 三指均单日 ≤ 此值 → 系统性急跌
L3_AVG1D_SH = -1.2       # 上证也跌破 → 全面杀（配合 avg1<=-2.0）
L3_MA20_AVG1D = -1.0     # 破MA20 ≥2 且均跌 → 破位防守
L3_AVG3D = -3.5
L2_DIV = 1.2             # 成长(创业板)-权重(上证) 背离 ≥ 此值 → 风格切换
L2_AVG1D = -0.8          # 均跌超此值 → 转弱
STOP_DEFAULT = -8.0      # 展示用止损参考（具体以当时规则快照为准）


def _load_records():
    if not os.path.exists(LEDGER):
        return []
    try:
        with open(LEDGER, encoding="utf-8") as f:
            return [json.loads(l) for l in f.read().splitlines() if l.strip()]
    except (OSError, ValueError):
        return []


def market_risk(cache, all_dates, date_to_idx, asof):
    """三指数收盘 → 盘面风险分级（离线）。返回 (level, info)。

    info: {state, avg1d, avg3d, below_ma20, div_growth, idx: {secid: {chg1d, chg3d,
    ma20, close, below}}, reasons: [str]}
    """
    info = {"state": state_on(cache, all_dates, date_to_idx, asof),
            "idx": {}, "reasons": []}
    if asof not in date_to_idx:
        return RISK_LV_NORMAL, info
    pos = date_to_idx[asof]
    for sid in INDEXES:
        snaps = cache.get(sid, {}).get("snaps") or []
        rows = [s for s in snaps if s["date"] <= asof]
        closes = [s["close"] for s in rows if s.get("close")]
        d = {"chg1d": None, "chg3d": None, "ma20": None, "below": None, "close": closes[-1] if closes else None}
        if len(closes) >= 2:
            d["chg1d"] = (closes[-1] / closes[-2] - 1) * 100
        if len(closes) >= 4:
            d["chg3d"] = (closes[-1] / closes[-4] - 1) * 100
        if len(closes) >= 20:
            d["ma20"] = sum(closes[-20:]) / 20
            if d["close"]:
                d["below"] = d["close"] < d["ma20"]
        info["idx"][sid] = d
    chg1s = [d["chg1d"] for d in info["idx"].values() if d.get("chg1d") is not None]
    chg3s = [d["chg3d"] for d in info["idx"].values() if d.get("chg3d") is not None]
    avg1d = sum(chg1s) / len(chg1s) if chg1s else None
    avg3d = sum(chg3s) / len(chg3s) if chg3s else None
    info["avg1d"], info["avg3d"] = avg1d, avg3d
    below_ma20 = sum(1 for d in info["idx"].values() if d.get("below"))
    info["below_ma20"] = below_ma20
    sh1 = (info["idx"].get("sh000001") or {}).get("chg1d")
    cyb1 = (info["idx"].get("sz399006") or {}).get("chg1d")
    # 科创50(sh000688) 仅作成长/科技风格观察：不参与三指均跌与 MA20 破位口径，
    # 用于识别"科创50/半导体板块独杀"这类创业板未必反映出的风格切换。
    kcb1 = None
    krows = [s for s in (cache.get("sh000688", {}).get("snaps") or [])
             if s["date"] <= asof and s.get("close")]
    if len(krows) >= 2:
        kcb1 = (krows[-1]["close"] / krows[-2]["close"] - 1) * 100
        d688 = {"chg1d": kcb1, "chg3d": None, "ma20": None, "below": None,
                "close": krows[-1]["close"]}
        if len(krows) >= 4:
            d688["chg3d"] = (krows[-1]["close"] / krows[-4]["close"] - 1) * 100
        if len(krows) >= 20:
            _m = sum(s["close"] for s in krows[-20:]) / 20
            d688["ma20"], d688["below"] = _m, krows[-1]["close"] < _m
        info["idx"]["sh000688"] = d688
    info["kcb1"] = kcb1
    # 成长/科技口径取「创业板 与 科创50 中更弱的一方」→ 科创独杀也能识别
    growth1 = cyb1 if kcb1 is None else min(cyb1, kcb1)
    # div = 成长 − 权重：负 → 成长跑输权重(成长/科技杀)；正 → 权重跑输成长(切成长/权重杀)
    info["div_growth"] = (growth1 - sh1) if (growth1 is not None and sh1 is not None) else None
    if sh1 is None or avg1d is None:
        return RISK_LV_NORMAL, info
    # L3：系统性急跌
    if info["state"] == "CRASH":
        info["reasons"].append("市场状态 CRASH（近 6 日暴跌）")
        return RISK_LV_STORM, info
    if avg1d <= L3_AVG1D_ANY:
        info["reasons"].append("三指数单日均跌 %.1f%%" % avg1d)
        return RISK_LV_STORM, info
    if avg1d <= -2.0 and sh1 <= L3_AVG1D_SH:
        info["reasons"].append("全市场普跌：上证 %.1f%% 且三指均跌 %.1f%%" % (sh1, avg1d))
        return RISK_LV_STORM, info
    if below_ma20 >= 2 and avg1d <= L3_MA20_AVG1D:
        info["reasons"].append("%d/3 指数跌破 MA20 且转弱" % below_ma20)
        return RISK_LV_STORM, info
    if avg3d is not None and avg3d <= L3_AVG3D:
        info["reasons"].append("三指数 3 日累计均跌 %.1f%%" % avg3d)
        return RISK_LV_STORM, info
    # L2：风格/板块切换或明显转弱
    if info["div_growth"] is not None and info["div_growth"] <= -L2_DIV and avg1d <= -0.4:
        weak_txt = ("科创50 %.1f%%" % kcb1) if (kcb1 is not None and cyb1 is not None
                                               and kcb1 < cyb1 - 0.5) else ("创业板 %.1f%%" % cyb1)
        info["reasons"].append("成长/科技杀跌：%s vs 上证 %.1f%%（弱 %.1fpp）"
                               % (weak_txt, sh1, -info["div_growth"]))
        return RISK_LV_ROTATE, info
    if info["div_growth"] is not None and info["div_growth"] >= L2_DIV and avg1d <= -0.4:
        info["reasons"].append("权重杀跌/资金切成长：上证 %.1f%% vs 创业板 %.1f%%（强 %.1fpp）"
                               % (sh1, cyb1, info["div_growth"]))
        return RISK_LV_ROTATE, info
    if avg1d <= L2_AVG1D:
        info["reasons"].append("三指数均跌 %.1f%%（温和转弱）" % avg1d)
        return RISK_LV_ROTATE, info
    if below_ma20 >= 2:
        info["reasons"].append("%d/3 指数位于 MA20 下方（弱势整理）" % below_ma20)
        return RISK_LV_ROTATE, info
    return RISK_LV_NORMAL, info


def active_signal_holds(recs, cache, all_dates, date_to_idx, asof):
    """账本中『已到买入日、持仓窗口未走完』的信号票巡诊（含今日刚买入）。
    返回 [{secid,name,period,sig_date,buy_date,entry,cur,unreal,sl,tp,max_hold,
          held_days,flag,note}]，按浮盈升序。
    """
    n_dates = len(all_dates)
    ai = date_to_idx.get(asof)
    if ai is None:
        return []
    out = []
    for rec in recs:
        a = rec.get("asof")
        if a not in date_to_idx:
            continue
        bi = date_to_idx[a] + 1
        if bi >= n_dates or bi > ai:
            continue  # 买入日未到（明日才买）→ 归入“新信号”
        buy_date = all_dates[bi]
        for it in rec.get("picks") or []:
            secid, period = it.get("secid"), it.get("period")
            if secid not in cache or period not in PERIODS:
                continue
            snaps = cache[secid].get("snaps") or []
            by = {s["date"]: s for s in snaps}
            buy_s = by.get(buy_date)
            cur_s = by.get(asof)
            if not buy_s or (buy_s.get("open") or 0) <= 0 or not cur_s:
                continue
            entry = buy_s["open"]
            cur = cur_s.get("close") or 0
            if cur <= 0:
                continue
            unreal = (cur / entry - 1) * 100
            rule = (rec.get("rules") or {}).get(period) or sell_rule_for(period, rec.get("state"))
            sl = float(rule.get("sl") or STOP_DEFAULT)
            tp = float(rule.get("tp") or 0)
            max_hold = int(rule.get("maxHold") or 0)
            held_days = ai - bi
            if max_hold > 0 and held_days >= max_hold:
                continue  # 窗口走完 → 由 evaluate 做终局结算
            if unreal <= sl:
                flag, note = "止损", "已跌破止损线(%.0f%%)，开盘按纪律出清，不补仓不摊低" % sl
            elif unreal <= -5:
                flag, note = "减仓", "深回撤 %.1f%%，先减半仓防御，破 %.0f%% 清仓" % (unreal, sl)
            elif unreal <= -3:
                flag, note = "警戒", "浮亏 %.1f%%，反弹减仓或上移止损保护" % unreal
            elif tp > 0 and unreal >= tp:
                flag, note = "止盈", "已达止盈线 +%.0f%%，可落袋或上移保护" % tp
            else:
                flag, note = "持有", "浮盈 %.1f%%" % unreal if unreal >= 0 else "浮亏 %.1f%%，未破位按纪律持有" % -unreal
            out.append({"secid": secid, "name": it.get("name") or secid, "period": period,
                        "sig_date": a, "buy_date": buy_date, "entry": round(entry, 2),
                        "cur": round(cur, 2), "unreal": round(unreal, 2), "sl": sl, "tp": tp,
                        "max_hold": max_hold, "held_days": held_days,
                        "flag": flag, "note": note})
    out.sort(key=lambda x: x["unreal"])
    return out


def new_signals_next_session(recs, all_dates, date_to_idx, asof):
    """『买入日为下一交易日（可能还未进缓存）』的最新记录里的新信号。
    仅取最近一条买入日在 asof 之后的记录（今日收盘新记录=明日开盘待买）。"""
    ai = date_to_idx.get(asof)
    if ai is None:
        return [], None
    for rec in reversed(recs):
        a = rec.get("asof")
        if a not in date_to_idx:
            continue
        bi = date_to_idx[a] + 1
        if bi <= ai:
            continue  # 已到买入日 → 归 active_signal_holds 巡诊
        return rec.get("picks") or [], rec.get("state")
    return [], None


def _secid_of_pos(raw):
    """实仓镜像里的裸 6 位代码 → 池内 secid（与引擎口径一致，6/5/9 沪，其余深）。"""
    c6 = str(raw or "").strip().lower()
    if c6.startswith(("sh", "sz")):
        return c6
    if len(c6) != 6 or not c6.isdigit():
        return None
    return ("sh" if c6[0] in "569" else "sz") + c6


def load_positions_mirror():
    """读本地最新 COS phone 镜像 real_positions（离现实仓快照）。返回 (pos, asof)。"""
    cloud = os.path.join(RECORDS, "cloud")
    try:
        dirs = [d for d in os.listdir(cloud) if os.path.isdir(os.path.join(cloud, d))] \
            if os.path.isdir(cloud) else []
    except OSError:
        return [], None
    if not dirs:
        return [], None
    for d in sorted(dirs, key=lambda s: s.lower(), reverse=True)[:5]:
        f = os.path.join(cloud, d, "data.json")
        if not os.path.exists(f):
            continue
        try:
            data = json.load(open(f, encoding="utf-8"))
            pos = data.get("real_positions") or []
            if isinstance(pos, list):
                return pos, d
        except (OSError, ValueError):
            continue
    return [], None


def portfolio_risk(pos, cache, all_dates, asof):
    """实仓镜像轻量风控：逐笔现值(缓存收盘补全) + 组合浮盈/集中度。返回 (lines, agg)。"""
    lines, agg = [], {"cost": 0.0, "value": 0.0, "n": 0}
    snaps_last = {sid: (cache.get(sid, {}).get("snaps") or []) for sid in cache}
    for p in pos:
        sid = _secid_of_pos(p.get("stock_code"))
        cost = float(p.get("avg_buy_price") or 0)
        qty = float(p.get("quantity") or 0)
        if not sid or cost <= 0 or qty <= 0:
            continue
        snaps = snaps_last.get(sid) or []
        cur = None
        if snaps:
            cur = snaps[-1].get("close") or None   # 镜像仅到最近一次同步，取池内最新收盘
        cur = cur or float(p.get("current_price") or 0)
        if cur <= 0:
            continue
        pnl = (cur / cost - 1) * 100
        agg["cost"] += qty * cost
        agg["value"] += qty * cur
        agg["n"] += 1
        ma20 = None
        closes = [s["close"] for s in snaps if s.get("close")]
        if len(closes) >= 20:
            ma20 = sum(closes[-20:]) / 20
        flag = "持有"
        if pnl <= STOP_DEFAULT:
            flag = "止损警戒"
        elif ma20 and cur < ma20 and pnl < 0:
            flag = "减仓警戒"
        lines.append("　· %s(%s) 浮盈%+.1f%% %s" % (
            p.get("stock_name") or sid, str(p.get("stock_code") or ""),
            pnl, "→ " + flag if flag != "持有" else ""))
    if agg["cost"] > 0:
        agg["pnl_pct"] = (agg["value"] / agg["cost"] - 1) * 100
    return lines, agg


def response_plan(cache, all_dates, date_to_idx, asof):
    """T 日收盘 → T+1 预案。返回 (lines, info)。"""
    level, risk = market_risk(cache, all_dates, date_to_idx, asof)
    info = {"asof": asof, "level": level, "risk": risk}
    lines = []
    idxs = risk["idx"]
    def fmt1(sid):
        d = idxs.get(sid) or {}
        v = d.get("chg1d")
        return "%s%.2f%%" % (IDX_NAME.get(sid, sid), v) if v is not None else "%s-" % IDX_NAME.get(sid, sid)
    lines.append("🧭 盘面应对（%s 收盘 → T+1 预案）" % asof)
    head = " ".join(fmt1(s) for s in INDEXES)
    k1 = (idxs.get("sh000688") or {}).get("chg1d")
    if k1 is not None:
        head += " 科创50%+.2f%%" % k1
    lines.append("　%s | 三指均跌 %.2f%% | 破MA20 %d/3 | 大盘%s" % (
        head, risk.get("avg1d") or 0.0, risk.get("below_ma20", 0), risk.get("state")))
    lines.append("　%s" % RISK_TITLE[level] + ("：" + "；".join(risk["reasons"]) if risk["reasons"] else ""))
    recs = _load_records()
    holds = active_signal_holds(recs, cache, all_dates, date_to_idx, asof)
    new_picks, new_state = new_signals_next_session(recs, all_dates, date_to_idx, asof)
    info["holds"] = holds
    info["new_n"] = len(new_picks or [])
    # ① 持有/刚买入信号票逐笔处置
    if holds:
        lines.append("　📋 持有中的信号票巡诊（%d 笔，按浮亏排序）：" % len(holds))
        for h in holds[:10]:
            tag = {"止损": "🔴", "减仓": "🟠", "警戒": "🟡", "止盈": "🟢", "持有": "⚪"}.get(h["flag"], "")
            lines.append("　%s %s%s(%s) 信号%s买入%s已%d日 现%+.1f%% %s" % (
                tag, h["name"], h["period"], h["secid"], h["sig_date"], h["buy_date"],
                h["held_days"], h["unreal"], h["note"]))
        flags = {h["flag"] for h in holds}
    else:
        lines.append("　📋 当前无持有中的信号票（新信号尚未到买入日）")
        flags = set()
    # ② 实仓镜像风控
    pos, pos_asof = load_positions_mirror()
    pos_lines, agg = portfolio_risk(pos, cache, all_dates, asof)
    info["pos_n"] = agg.get("n", 0)
    if pos_lines:
        lines.append("　💼 实仓镜像（%s，%d 笔，整体%+.1f%%）：" % (
            pos_asof or "-", agg["n"], agg.get("pnl_pct") or 0.0))
        lines.extend(pos_lines[:8])
        if agg.get("pnl_pct") is not None and agg["pnl_pct"] <= STOP_DEFAULT:
            lines.append("　🔻 组合整体浮亏 %+.1f%% 达纪律线：先降仓防守、反弹再谈机会" % agg["pnl_pct"])
    # ③ 分级行动
    if level == RISK_LV_NORMAL:
        if holds:
            hits = [h for h in holds if h["flag"] in ("止损", "减仓", "警戒")]
            if hits:
                lines.append("　✅ 市场平稳：仅处理破位信号票（上列🔴🟠🟡），其余按纪律持有")
            else:
                lines.append("　✅ 市场平稳：无破位信号票，按纪律持有；止盈位(🟢)照常落袋")
        if new_picks:
            lines.append("　🆕 明日新信号 %d 只：可按纪律执行；开仓参考——低位标的等『放量企稳』"
                         "二次确认再上，不接缩量阴跌的飞刀" % len(new_picks))
    elif level == RISK_LV_ROTATE:
        lines.append("　🔄 风格/板块在切换或明显转弱：先分清方向再动手，不无脑清仓也不逆势追")
        weak = [h for h in holds if h["unreal"] <= -3]
        if weak:
            lines.append("　　- 跑输的信号票（上列🔴🟠🟡）优先处理：破止损(约%.0f%%)必清、反弹减仓；"
                         "你的方向明显弱于大盘 → 考虑轮动到当日资金流入方向" % STOP_DEFAULT)
        if new_picks:
            lines.append("　　- 新信号 %d 只：可继续选股但只取逆势新主线/资金流入方向，避开本轮杀跌风格，"
                         "试仓≤半仓" % len(new_picks))
        else:
            lines.append("　　- 明日可按新主线继续选股（切换≠结束，轮动应对）")
    else:
        lines.append("　⛈ 系统性急跌/破位：风险第一，不猜底、不接飞刀，先守住本金")
        stop_flags = {"止损", "减仓", "警戒"}
        if holds:
            stop_hits = [h for h in holds if h["flag"] in stop_flags]
            lines.append("　　- 信号票：%d 笔已破位/深回撤 → 开盘出清或减半仓；未破位也降至半仓防守" % len(stop_hits))
        lines.append("　　- 明日不开新仓（暂停执行今日 %d 只新信号），等指数收复 MA20 并放量再恢复" % (len(new_picks) or 0))
        if agg.get("cost", 0) > 0 and agg.get("pnl_pct") is not None:
            lines.append("　　- 实仓整体%+.1f%%：破 -8%% 先降仓防守；若单票集中度>30%%/前2>55%%，反弹中消化"
                         % agg["pnl_pct"])
    info["text"] = "\n".join(lines)
    return lines, info


# ---------------------------------------------------------------- 报告
def fmt_gate(period, extra_pass, mode):
    if extra_pass <= 0:
        return "保持当前口径"
    if mode == "live":
        return "推送已收紧 pass≥%d" % (RATIO_MIN_PASS_BASE.get(period, 0) + extra_pass)
    return "建议 pass 收紧 +%d（mode=%s 未生效）" % (extra_pass, mode)


def report_text(log, n_eval, n_pending, last_asof, base=None, resp=None):
    lines = []
    base = base if base else build_baseline()
    period_total = {p: {"n": 0, "wr": 0.0, "avg": 0.0} for p in PERIODS}
    for v in load_reviews().values():
        p = v.get("period")
        if p not in period_total:
            continue
        c = period_total[p]
        c["n"] += 1
        c["wr"] += 1 if (v.get("ret") or 0) > 0 else 0
        c["avg"] += v.get("ret") or 0
    heads = []
    for p in PERIODS:
        c = period_total[p]
        if c["n"]:
            c["wr"] = c["wr"] / c["n"] * 100
            c["avg"] = c["avg"] / c["n"]
            heads.append("%s%d笔 wr%.0f%% avg%+.1f%%" % (
                p, c["n"], c["wr"], c["avg"]))
        else:
            heads.append("%s待积累" % p)
    lines.append("🧠 自检账本：已记录至 %s | 累计结算 %d 笔 | 未到期 %d 笔"
                 % (last_asof or "-", n_eval, n_pending))
    lines.append("　" + ("；".join(heads) if heads else "尚无已结算样本"))
    if resp:  # 🧭 盘面应对（T+1 预案）：紧跟账本头，先讲“明天怎么办”
        lines.append("")
        lines.extend(resp)
        lines.append("")
    if n_eval:
        # 最近窗口高光：胜率最优/最差周期 + 漂移建议
        warn = [x for x in log if x.get("extra_pass", 0) > 0]
        if warn:
            for x in warn:
                lines.append("⚠️ %s：近%d笔命中%.0f%%（基线%s%.0f%%），%s" % (
                    x["period"], x["n_recent"], x["wr_recent"],
                    (x["dom_state"] + " ") if x.get("dom_state") else "", x["base_wr"] or 0,
                    fmt_gate(x["period"], x["extra_pass"], x["mode"])))
        else:
            lines.append("✅ 各周期近窗命中率未触发收紧阈值，维持当前选股口径")
    # 状态细分（样本充足时展示，帮助判断是否按状态人工收紧）
    sub = []
    for p, stmap in sorted(base.items(), key=lambda kv: PERIODS.index(kv[0])):
        for st, b in sorted(stmap.items(), key=lambda kv: -kv[1]["n"])[:1]:
            ev = [v for v in load_reviews().values()
                  if v.get("period") == p and v.get("state") == st and v.get("ret") is not None]
            if len(ev) >= 5:
                wr = sum(1 for v in ev if v["ret"] > 0) / len(ev) * 100
                sub.append("%s(%s) 实测%d笔wr%.0f%%/基线%d笔wr%.0f%%" % (
                    p, st, len(ev), wr, b["n"], b["wr"]))
    if sub:
        lines.append("📊 按状态对比（样本≥5）：")
        lines.extend("　· " + s for s in sub[:6])
    return "\n".join(lines)


def _count_pending(db):
    """账本内所有尚未结算且未到期的信号笔数（近似 = 未结算中未到期的）。"""
    if not os.path.exists(LEDGER):
        return 0
    try:
        cache, all_dates, date_to_idx = load_cache_maps()
    except Exception:  # noqa: BLE001
        return 0
    n_dates = len(all_dates)
    n_pending = 0
    with open(LEDGER, encoding="utf-8") as f:
        for line in f.read().splitlines():
            if not line.strip():
                continue
            try:
                rec = json.loads(line)
            except ValueError:
                continue
            asof = rec.get("asof")
            if asof not in date_to_idx:
                continue
            buy_idx = date_to_idx[asof] + 1
            rules = rec.get("rules") or {}
            for it in rec.get("picks") or []:
                secid, period = it.get("secid"), it.get("period")
                if period not in PERIODS or secid not in cache:
                    continue
                if review_key(asof, period, secid) in db:
                    continue
                if not _matured(buy_idx, rules.get(period), n_dates):
                    n_pending += 1
    return n_pending


def refresh_report():
    db = load_reviews()
    state_d = load_state()
    baseline = state_d.get("baseline")
    if not baseline:
        baseline = build_baseline()
        state_d["baseline"] = baseline
    log = feedback(db, state_d, baseline)
    n_eval = len([v for v in db.values() if v.get("ret") is not None])
    n_pending = _count_pending(db)
    last_asof = None
    if os.path.exists(LEDGER):
        with open(LEDGER, encoding="utf-8") as f:
            lines = [l for l in f.read().splitlines() if l.strip()]
        if lines:
            last_asof = json.loads(lines[-1]).get("asof")
    resp_lines = []
    resp_info = None
    try:  # 🧭 盘面应对：asof=缓存末端（尾盘拉取后=今日收盘），失败不阻塞复盘
        cache, all_dates, date_to_idx = load_cache_maps()
        resp_lines, resp_info = response_plan(cache, all_dates, date_to_idx, all_dates[-1])
    except Exception as e:  # noqa: BLE001
        _log("response_plan error: %s" % e)
    txt = report_text(log, n_eval, n_pending, last_asof, base=baseline, resp=resp_lines or None)
    os.makedirs(RECORDS, exist_ok=True)
    with open(REPORT_FILE, "w", encoding="utf-8") as f:
        json.dump({"asof": last_asof, "text": txt, "n_eval": n_eval,
                   "n_pending": n_pending, "feedback": log,
                   "response": resp_info,
                   "generated_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")},
                  f, ensure_ascii=False, indent=1)
    print(txt)
    return txt


# ---------------------------------------------------------------- CLI
def cmd_status():
    cache, all_dates, date_to_idx = load_cache_maps()
    print("缓存：%d 只 | 交易日 %s ~ %s（%d 根）" % (
        len(cache), all_dates[0], all_dates[-1], len(all_dates)))
    if os.path.exists(LEDGER):
        with open(LEDGER, encoding="utf-8") as f:
            lines = [l for l in f.read().splitlines() if l.strip()]
        print("账本：%d 条记录" % len(lines))
        for l in lines[-5:]:
            try:
                r = json.loads(l)
            except ValueError:
                continue
            print("  %s 大盘%s 候选%d" % (r.get("asof"), r.get("state"), len(r.get("picks") or [])))
    db = load_reviews()
    print("已结算：%d 笔（%d 笔有收益结果）" % (
        len(db), len([v for v in db.values() if v.get("ret") is not None])))
    state_d = load_state()
    mode = state_d.get("mode")
    print("自学习模式：%s（off=只记账 / dry=出建议 / live=守护消费 extra_pass）" % mode)
    for p in PERIODS:
        g = (state_d.get("gate") or {}).get(p) or {}
        print("  %s：extra_pass=%s %s" % (p, g.get("extra_pass"), g.get("why") or ""))


def main():
    ap = argparse.ArgumentParser(description="每日选股记忆+自检闭环")
    ap.add_argument("--record", action="store_true", help="快照当日选股入账本")
    ap.add_argument("--evaluate", action="store_true", help="结算到期信号")
    ap.add_argument("--report", action="store_true", help="刷新复盘摘要")
    ap.add_argument("--push", action="store_true", help="推送复盘摘要到微信")
    ap.add_argument("--status", action="store_true", help="查看账本/结算/状态")
    ap.add_argument("--asof", default=None, help="指定快照交易日 YYYY-MM-DD")
    ap.add_argument("--refresh-baseline", action="store_true", help="重算 walk-forward 基线")
    args = ap.parse_args()

    if args.status:
        cmd_status()
        return 0

    if args.refresh_baseline:
        state_d = load_state()
        state_d["baseline"] = build_baseline()
        save_state(state_d)
        print("基线已刷新（%d 周期×状态）" % sum(
            len(v) for v in (state_d["baseline"] or {}).values()))
        return 0

    # 无任何动作参数时，默认执行完整链路：record → evaluate → report
    if not (args.record or args.evaluate or args.report or args.push):
        args.record = args.evaluate = args.report = True

    if args.record:
        record_day(asof=args.asof)
    if args.evaluate:
        evaluate_pending()
    if args.report:
        refresh_report()
    if args.push:
        txt = refresh_report() if not args.report else None
        if txt is None:
            try:
                with open(REPORT_FILE, encoding="utf-8") as f:
                    txt = json.load(f).get("text") or "（尚无复盘内容）"
            except (OSError, ValueError):
                txt = "（尚无复盘内容）"
        ok = push_channel.push("🧠 选股自检复盘", txt, load_notify_cfg())
        print("复盘推送 %s" % ("成功" if ok else "未发送(未配置/失败)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
