# -*- coding: utf-8 -*-
"""
XML DAG Python 引擎 —— APK usecase XML 的 Python 执行端（选股主线）。

归置（2026-09-07）：XML DAG = 选股主线。本引擎与它的单一事实源 XML 同居
app/src/main/assets/usecases/（主仓库版本管理），exe(AutoQuant) 与
smalltools(PC 桥/盘段守护) 两端都指向此处 import/调用；smalltools 旧选股
引擎仅作对照参考（非主线）。本文件内自举定位 smalltools 目录以桥接其引擎
函数；本目录中的 .py 会被 APK 侧 listUseCases/listPipelines 的
"_usecase.xml"/"_pipeline.xml" 后缀过滤自动忽略，不会被打包执行或解析。

设计：直接解析本目录 assets/usecases/*.xml（单一事实源），APK 改 XML 后
exe/PC 无需改代码即可同步。与 smalltools 引擎桥接：
  - data_import / market_context / adaptive_params / stock_pool / pool_filter
    / market_ma_unified / candidate_pool / signal_merge / sector_boost / bounce
    / strict_selection / ancestral_rules / inst_tips / smart_money / news_guard
    / generate_orders / swap_weak / position_merge / etf_gate / etf_dip_signal
    / etf_exit_policy 等映射到 smalltools 函数或同构实现。
  - 依赖 Android 特有上下文（AI/新闻/持仓）的 node 在回测中降级为"通过/空操作"
    并记录到 ctx.notes。

用法（调用方需把本目录 app/src/main/assets/usecases 加入 sys.path）：
    import usecase_pipeline as up
    runner = up.UseCaseRunner(usecase_id="etf_dip", cache=cache)
    result = runner.run(with_stages=True)   # -> {orders, market_state, stages, ...}
"""
import importlib
import json
import os
import sys
import xml.etree.ElementTree as ET

_HERE = os.path.dirname(os.path.abspath(__file__))
USECASES_DIR = _HERE   # 与 usecase/pipeline XML 单一事实源同目录

# 自举：向上定位仓库根（含 smalltools 的祖先目录），供 _st() 桥接 smalltools 引擎。
_REPO_ROOT = _HERE
for _ in range(12):
    if os.path.isdir(os.path.join(_REPO_ROOT, "smalltools")):
        break
    _parent = os.path.dirname(_REPO_ROOT)
    if _parent == _REPO_ROOT:
        break
    _REPO_ROOT = _parent
_SMALLTOOLS_DIR = os.path.join(_REPO_ROOT, "smalltools")
for _p in (_HERE, _SMALLTOOLS_DIR):
    if os.path.isdir(_p) and _p not in sys.path:
        sys.path.insert(0, _p)


def _st(name):
    """加载 smalltools 顶层模块（smalltools 目录无 __init__.py，按顶层包导入）。"""
    return importlib.import_module(name)


# ──────────────────────────────────────────────────────────────────────────
# XML 解析
# ──────────────────────────────────────────────────────────────────────────
class Node:
    __slots__ = ("id", "name", "module", "config")

    def __init__(self, node_id, name, module, config):
        self.id = node_id
        self.name = name
        self.module = module
        self.config = config or {}

    def __repr__(self):
        return "<Node %s:%s>" % (self.id, self.module)


class Pipeline:
    """DagPipeline：nodes + links + 分组（Pipeline 内的节点视为原子子图）。"""

    def __init__(self, pid, name, description=""):
        self.id = pid
        self.name = name
        self.description = description
        self.nodes = {}
        self.links = []          # [(src_id, dst_id)]
        self.deps = {}           # node_id -> set(node_id)
        self.order = []          # 拓扑序

    def add_node(self, node):
        self.nodes[node.id] = node

    def add_link(self, src, dst):
        if src in self.nodes and dst in self.nodes:
            self.links.append((src, dst))
            self.deps.setdefault(dst, set()).add(src)

    def build(self):
        """Kahn 拓扑排序（保留并行节点的稳定顺序）。"""
        deg = {nid: len(self.deps.get(nid, ())) for nid in self.nodes}
        out = {nid: [] for nid in self.nodes}
        for src, dst in self.links:
            out[src].append(dst)
        ready = [nid for nid, d in deg.items() if d == 0]
        ready.sort(key=lambda x: self._node_order(x))
        self.order = []
        while ready:
            nid = ready.pop(0)
            self.order.append(nid)
            for nd in out[nid]:
                deg[nd] -= 1
                if deg[nd] == 0:
                    ready.append(nd)
                    ready.sort(key=lambda x: self._node_order(x))
        if len(self.order) != len(self.nodes):
            raise ValueError("pipeline %s 存在环: 已排 %d/%d"
                             % (self.id, len(self.order), len(self.nodes)))
        return self.order

    def _node_order(self, nid):
        """稳定排序：优先按 XML 中出现顺序。"""
        return self._xml_index.get(nid, 10 ** 9)

    def _set_xml_index(self, idx_map):
        self._xml_index = idx_map


def parse_pipeline(xml_path):
    """解析 DagPipeline XML → Pipeline。"""
    tree = ET.parse(xml_path)
    root = tree.getroot()
    pl = Pipeline(root.get("id", os.path.basename(xml_path)),
                  root.get("name", ""), root.get("description", ""))
    idx = {}
    for nl in root.findall(".//NodeList"):
        for n in nl.findall("Node"):
            cfg = {}
            for c in n.findall("config/param"):
                cfg[c.get("name")] = c.get("value")
            nid = n.get("id")
            idx[nid] = len(idx)
            pl.add_node(Node(nid, n.get("name", ""), n.get("module", ""), cfg))
    pl._set_xml_index(idx)
    for lk in root.findall(".//Links/Link"):
        src = lk.findtext("SourceNodeId")
        dst = lk.findtext("TargetNodeId")
        if src and dst:
            pl.add_link(src.strip(), dst.strip())
    pl.build()
    return pl


USECASE_FILE = {
    "ultra_short": "ultra_short_usecase.xml",
    "short": "short_term_usecase.xml",
    "short_term": "short_term_usecase.xml",
    "mid": "mid_term_usecase.xml",
    "mid_term": "mid_term_usecase.xml",
    "long": "long_term_usecase.xml",
    "long_term": "long_term_usecase.xml",
    "screening": "screening_usecase.xml",
    "common": "common_usecase.xml",
    "closed_loop": "complete_closed_loop_usecase.xml",
}


def load_usecase(usecase_id):
    """读取 usecase XML → {id, name, steps:[(ref, if_cond)], config}。"""
    fname = USECASE_FILE.get(usecase_id, "%s_usecase.xml" % usecase_id)
    path = os.path.join(USECASES_DIR, fname)
    if not os.path.exists(path):
        # 直接给文件名时也支持
        path = os.path.join(USECASES_DIR, usecase_id)
    if not os.path.exists(path):
        raise FileNotFoundError("usecase XML 不存在: %s" % path)
    tree = ET.parse(path)
    root = tree.getroot()
    steps = []
    for p in root.findall(".//steps/pipeline"):
        steps.append((p.get("ref"), p.get("if"), p.get("name", "")))
    cfg = {}
    for c in root.findall("config/param"):
        cfg[c.get("name")] = c.get("value")
    return {"id": root.get("id"), "name": root.get("name"),
            "steps": steps, "config": cfg}


def resolve_usecase_pipeline(usecase_id):
    """按 usecase 的 steps 加载，返回 (usecase_dict, pipeline_files)。"""
    u = load_usecase(usecase_id)
    return u


# ──────────────────────────────────────────────────────────────────────────
# Node 注册表 + 上下文
# ──────────────────────────────────────────────────────────────────────────
class Ctx:
    """跨 pipeline 共享上下文（对齐 APK PipelineContext.stageOutputs）。"""

    def __init__(self, cache=None, asof=None, all_dates=None, date_to_idx=None):
        self.cache = cache or {}
        self.asof = asof
        self.all_dates = all_dates or []
        self.date_to_idx = date_to_idx or {}
        self.stage_outputs = {}       # node_id -> 输出
        self.adaptive = {}            # 自适应参数
        self.market = {}              # 大盘状态
        self.direction = "UNKNOWN"    # BULLISH/OSCILLATION/BEARISH
        self.orders = []              # 最终订单
        self.notes = []               # 降级/跳过记录
        self.stock_pool = []          # 股票池 [secid, ...]
        self.candidates = []          # 候选池（带评分）
        self.node_configs = {}        # module -> config（pipeline 内聚合，供 signal_merge 覆盖参数）
        self.event_state = None       # 事件状态（懒计算缓存：active 列表/关键词效应）

    def stage(self, nid, val):
        self.stage_outputs[nid] = val

    def get(self, nid, default=None):
        return self.stage_outputs.get(nid, default)


NODE_IMPLS = {}   # module -> fn(ctx, node, inputs) -> output


def register(*modules):
    def deco(fn):
        for m in modules:
            NODE_IMPLS[m] = fn
        return fn
    return deco


def _inputs(ctx, node):
    """读取该节点所有上游输出（供实现消费）。"""
    return ctx.stage_outputs


# ──────────────────────────────────────────────────────────────────────────
# 通用/降级 node（依赖 Android 上下文的，回测中降级）
# ──────────────────────────────────────────────────────────────────────────
@register("bg_manager", "news_strength", "news_guard", "candle_pattern",
          "financial_health", "inst_tips", "crosstab_publish",
          "intraday_analysis", "zipline_factor", "multi_period_hot",
          "heat_score", "market_sector_leaders", "real_holding_eval",
          "holding_diagnostic", "holding_prediction", "stock_deep_analysis",
          "sector_relative_pe",
          # ── t_trade / real_holding 链路（2026-09-13）：均为「Android 运行时」节点 ──
          #    入参全部来自 Room 持仓库(strategyTradeOrderDao/tTradeRecordDao/
          #    realPositionDao) + 实时行情 + 分时 + 墙上时钟时段，PC 回测均无此数据源，
          #    故按既有约定声明式透传（原因见 _PASSTHROUGH_WHY）。
          "t_trade_eval", "sector_leader_analysis", "t1_auto_sell",
          "t_holdings_load", "t_inst_intent", "t_signal_synthesize", "t_recommend_save")
def _passthrough(ctx, node, inputs):
    """回测中无对应数据的 node：透传，记录降级。"""
    why = _PASSTHROUGH_WHY.get(node.module, "无回测数据")
    ctx.notes.append("[%s] %s %s，已降级为透传" % (node.id, node.module, why))
    return None


# 声明式透传的原因表（让审计报告自解释：是「代码缺失」还是「数据源缺失」）
_PASSTHROUGH_WHY = {
    "t_holdings_load": "需 Android Room 持仓库（realPositionDao + strategyTradeOrderDao），PC 无持仓库",
    "t_inst_intent": "需真实持仓（Android Room strategyTradeOrderDao）+ 日内快照，PC 无持仓库",
    "t_signal_synthesize": "需真实持仓 + 实时行情 + 分时 + K线形态（Android），PC 无持仓库",
    "t_recommend_save": "需写入 Android Room t_trade_recommendations 表并推送微信，PC 无此表",
    "t_trade_eval": "需真实持仓（Android Room realPositionDao）+ TTradeEngine 实时快照",
    "sector_leader_analysis": "需 SectorSignalStore（Android 盘中每 10 分钟刷新的板块龙头状态）",
    "t1_auto_sell": "需超短线持仓库(strategyTradeOrderDao) + 实时价格，PC 无持仓库",
}


@register("data_import")
def _data_import(ctx, node, inputs):
    min_snaps = int(node.config.get("minSnapshots", 100))
    ok = {cid for cid, e in ctx.cache.items()
          if len(e.get("snaps", [])) >= min_snaps}
    ctx.stage("n_pool_hint", sorted(ok))
    return len(ok)


@register("market_context", "a_market_analysis", "market_direction")
def _market_direction(ctx, node, inputs):
    """大盘方向：复用 smalltools 大盘状态推断（BULLISH/OSCILLATION/BEARISH）。"""
    market_state = _st("_full_cycle_backtest").market_state
    idx_snaps = {s: ctx.cache.get(s, {}).get("snaps", [])
                 for s in ("sh000001", "sz399001", "sz399006")}
    st = market_state(idx_snaps, ctx.asof, ctx.all_dates, ctx.date_to_idx)
    if isinstance(st, dict):
        s = st.get("state", "OSCILLATION")
    else:
        s = str(st)
    if s == "CRASH":
        s = "BEARISH"
    ctx.market = {"state": s, "detail": st}
    if s in ("BULLISH", "OSCILLATION", "BEARISH"):
        ctx.direction = s
    return ctx.market


@register("adaptive_params")
def _adaptive_params(ctx, node, inputs):
    """大盘分析+自适应参数：按方向给周期参数。"""
    state = ctx.direction
    base = dict(lookbackDays=60, convergenceThreshold=2.5, useMA60=True,
                volumeBreakoutRatio=2.0, minDrawdownPct=20.0, requireChangePct=True,
                convergenceDurationDays=10, convergenceDurationRatio=0.8,
                swingLeftN=5, swingRightN=2, requireAboveAllMAs=True,
                requireThreeDayConfirm=True, minPassCount=6, allowQuietRise=True)
    if state == "BULLISH":
        base.update(convergenceThreshold=3.75, requireThreeDayConfirm=False)
    elif state == "BEARISH":
        base.update(convergenceThreshold=2.0, minDrawdownPct=25.0)
    ctx.adaptive = base
    return base


@register("stock_pool", "pool_filter")
def _stock_pool(ctx, node, inputs):
    """股票池：缓存全量（排除指数）+ 名称黑名单。"""
    if not ctx.stock_pool:
        pool = [cid for cid in ctx.cache
                if not cid.startswith(("sh000", "sz399", "sh880", "bj"))
                and len(ctx.cache[cid].get("snaps", [])) >= 20]
        black = ("ST", "退", "北交")
        pool = [c for c in pool
                if not any(b in (ctx.cache[c].get("name") or "") for b in black)]
        ctx.stock_pool = pool
    return ctx.stock_pool


@register("market_ma_unified")
def _market_ma_unified(ctx, node, inputs):
    """大盘均线统一检查：BULLISH 要求上证/深证站上 MA20。"""
    def above_ma20(cid):
        snaps = ctx.cache.get(cid, {}).get("snaps", [])
        if len(snaps) < 20:
            return False
        ma20 = sum(s["close"] for s in snaps[-20:]) / 20
        return snaps[-1]["close"] >= ma20
    ok = above_ma20("sh000001") and above_ma20("sz399001")
    return {"above_ma20": ok}


@register("holding_guard")
def _holding_guard(ctx, node, inputs):
    return {"holdings": []}


# ──────────────────────────────────────────────────────────────────────────
# 选股核心 node —— 桥接 smalltools 引擎
# ──────────────────────────────────────────────────────────────────────────
_PERIOD_TO_CN = {"ultra_short": "超短线", "short": "短线", "mid": "中线", "long": "长线"}


@register("candidate_pool")
def _candidate_pool(ctx, node, inputs):
    """候选池过滤：ST/主板/板块代理过滤（对齐 smalltools extra_filter 中文周期口径）。"""
    extra_filter = _st("_pool_filters").extra_filter
    pool = ctx.stock_pool or _stock_pool(ctx, node, inputs)
    cn = _PERIOD_TO_CN.get(ctx.adaptive.get("period", "short"), "短线")
    cands = []
    for cid in pool:
        r = extra_filter(cid, ctx.cache[cid].get("name", ""), {},
                         cn, ctx.cache, ctx.all_dates, ctx.date_to_idx, ctx.asof)
        if r is not None and r is not False:
            cands.append(cid)
    # 板块精选池并入（对齐 APK CandidatePoolNode 合并 sectorStockCodes 口径）
    sec_codes = ctx.get("sector_stock_codes") or []
    added = []
    for cid in sec_codes:
        if cid in cands or cid not in ctx.cache:
            continue
        r = extra_filter(cid, ctx.cache[cid].get("name", ""), {},
                         cn, ctx.cache, ctx.all_dates, ctx.date_to_idx, ctx.asof)
        if r is not None and r is not False:
            cands.append(cid)
            added.append(cid)
    if added:
        ctx.notes.append("[%s] 🧺 板块精选池并入候选 %d 只" % (node.id, len(added)))
    ctx.candidates = cands
    return cands


_XML_PARAM_KEYS = {
    "convergenceThreshold": "convergenceThreshold", "useMA60": "useMA60",
    "convergenceDurationDays": "convergenceDurationDays",
    "convergenceDurationRatio": "convergenceDurationRatio",
    "volumeBreakoutRatio": "volumeBreakoutRatio",
    "minChangePct": "minChangePct", "requireChangePct": "requireChangePct",
    "minDrawdownPct": "minDrawdownPct", "requireCloseAboveConvergenceTop": "requireCloseAboveConvergenceTop",
    "requireOpenBelowMAs": "requireOpenBelowMAs", "requireThreeDayConfirm": "requireThreeDayConfirm",
    "swingLeftN": "swingLeftN", "swingRightN": "swingRightN",
    "lookbackDays": "lookbackDays", "minPassCount": "minPassCount",
    "allowQuietRise": "allowQuietRise", "quietVolumeRatio": "quietVolumeRatio",
    "requireAboveAllMAs": "requireAboveAllMAs",
    "allowStableDip": "allowStableDip",
    "period": "period",
}


def _xml_params(node, base_params):
    """用 XML node config 覆盖 PARAMS 中的同名键（对齐 APK 参数化粘合引擎）。"""
    p = dict(base_params)
    for k in _XML_PARAM_KEYS:
        v = (node.config or {}).get(k)
        if v is None:
            continue
        if k in ("useMA60", "requireChangePct", "requireCloseAboveConvergenceTop",
                 "requireOpenBelowMAs", "requireThreeDayConfirm", "allowQuietRise",
                 "requireAboveAllMAs", "allowStableDip"):
            p[k] = str(v).lower() == "true"
        elif k == "period":
            p["period"] = v
        elif k in ("convergenceDurationDays", "swingLeftN", "swingRightN",
                   "lookbackDays", "minPassCount"):
            p[k] = int(float(v))
        else:
            try:
                p[k] = float(v)
            except ValueError:
                p[k] = v
    return p


# usecase 周期 key -> smalltools PARAMS 键（原实现 mid/long 错误落到"短线"基线，
# 导致中线/长线 DAG 回测参数错配。2026-09-04 修复按周期取对应 PARAMS。）
_PERIOD_PARAM_KEY = {"ultra_short": "超短", "short": "短线", "mid": "中线", "long": "长线"}

_TF_GUARD_MEMO = {}


def _tf_guard_cfg(period):
    """牛市出单二次过滤 guard（对齐 _self_fit_pipeline.scan_day 的部署口径）：
    读 backtest_params.json trend_follow[中文周期].guard_chg/guard_vr。
    未配置/缺失 → (0.0, 0.0) 表示不过滤。按文件 mtime 缓存。"""
    path = os.path.normpath(os.path.join(_REPO_ROOT, "app", "src", "main",
                                         "assets", "backtest_params.json"))
    try:
        mt = os.path.getmtime(path)
    except OSError:
        return 0.0, 0.0
    hit = _TF_GUARD_MEMO.get(period)
    if hit and hit[0] == mt:
        return hit[1]
    gchg = gvr = 0.0
    try:
        with open(path, encoding="utf-8") as f:
            tf = (json.load(f).get("trend_follow") or {}).get(
                _PERIOD_PARAM_KEY.get(period, "短线")) or {}
        gchg = float(tf.get("guard_chg", 0.0) or 0.0)
        gvr = float(tf.get("guard_vr", 0.0) or 0.0)
    except (OSError, ValueError, TypeError):
        pass
    _TF_GUARD_MEMO[period] = (mt, (gchg, gvr))
    return gchg, gvr


@register("signal_merge")
def _signal_merge(ctx, node, inputs):
    """信号合并：策略动态注入（对齐 APK signal_merge）。

    牛市 → 趋势跟随引擎（S4 强反转）；震荡/熊市 → 参数化粘合引擎
    （XML strict_selection config 覆盖 PARAMS，对齐 APK 六项严选参数）。
    """
    period = ctx.adaptive.get("period", "short")
    mode = "ultra_short" if period == "ultra_short" else "short"
    trend_follow_scan = _st("_trend_proto").trend_follow_scan
    bg = _st("backtest_guangmo")
    analyze_snaps, PARAMS = bg.analyze_snaps, bg.PARAMS
    base = PARAMS.get(_PERIOD_PARAM_KEY.get(period, "短线"), PARAMS["短线"])
    cfg = ctx.node_configs.get("strict_selection") or node.config
    base = _xml_params(type("_n", (), {"config": cfg}), base)
    # 事件驱动：活跃宏观事件的强受益关键词注入 analyze 放宽通道（macroSectorKeywords）
    _kws = _ensure_event_state(ctx)["boost_kws"]
    if _kws:
        base["macroSectorKeywords"] = _kws
    tf_guard = _tf_guard_cfg(period)   # (guard_chg, guard_vr)，0=不过滤
    # 改进B(2026-09-10)：弱市/震荡『强势延续』旁路参数（XML n_strict config，缺省安全默认）
    s_on = str(cfg.get("strongEnable", "true")).lower() == "true"
    s_rsi_lo = float(cfg.get("strongRsiLo", 55.0))
    s_rsi_hi = float(cfg.get("strongRsiHi", 70.0))
    s_near = float(cfg.get("strongNearHigh", 3.0))
    s_vr = float(cfg.get("strongVolRatio", 1.5))
    s_score = float(cfg.get("strongScore", 70.0))
    strong = set()
    # 防守高息候选并入（对齐 APK DefensiveDividend → signal_merge 口径）
    pool = list(ctx.candidates or ctx.stock_pool or [])
    for cid in (ctx.get("defensive_codes") or []):
        if cid in ctx.cache and cid not in pool:
            pool.append(cid)
    scored = []
    for cid in pool:
        snaps = [dict(s) for s in ctx.cache.get(cid, {}).get("snaps", [])
                 if s["date"] <= ctx.asof]
        if len(snaps) < 30:
            continue
        snaps[-1]["name"] = ctx.cache.get(cid, {}).get("name", "")
        try:
            if ctx.direction == "BULLISH" and period in ("ultra_short", "short"):
                res = trend_follow_scan(snaps, ctx.direction, mode)
                passed = bool(res[0]) if isinstance(res, tuple) else bool(res)
                if passed and (tf_guard[0] > 0 or tf_guard[1] > 0):
                    extra = res[4] if isinstance(res, tuple) and len(res) > 4 else {}
                    if (float(extra.get("chg", -99.0)) < tf_guard[0]
                            or float(extra.get("vr", 0.0)) < tf_guard[1]):
                        passed = False
                if passed:
                    scored.append((cid, 100.0))
            else:
                r = analyze_snaps(snaps, base, ctx.direction)
                if r and r.get("passed"):
                    scored.append((cid, float(r.get("score", 0))))
                elif s_on:
                    # 改进B：粘合低吸未过 → 试『强势延续』（多头排列+近高+放量+RSI中强区）
                    ok_s, _det_s = _strong_continuation(snaps, s_near, s_vr, s_rsi_lo, s_rsi_hi)
                    if ok_s:
                        strong.add(cid)
                        scored.append((cid, s_score))
        except Exception:
            continue
    scored.sort(key=lambda x: -x[1])
    if strong:
        ctx.stage_outputs["strong_codes"] = strong
    ctx.stage("n_merge", scored)
    return scored


@register("sector_boost", "bounce_reversal")
def _sector_boost(ctx, node, inputs):
    """板块加权/跌后反弹：sector 数据回测中缺省，保持原序。"""
    scored = ctx.get("n_merge") or []
    ctx.stage("n_boost", scored)
    return scored


# ──────────────────────────────────────────────────────────────────────────
# volume_price_factor：量价因子加权（2026-09-07 新增）
#   APK/Kotlin VolumePriceFactorNode 同构（双端读取同一 pipeline XML 参数）。
#   作用对象：signal_merge 输出的 scored[(cid, score)]，对每只叠加可正可负的
#   delta 后重排；结果覆写回 n_merge（并输出 n_vp），保证下游
#   sector_boost/bounce_reversal(strict_selection) 与 generate_orders 拿到的
#   即已含量价修正后的列表。
#   三项量价判定（默认参数全在 XML config，缺失用此兜底）：
#     ① 相对强度：个股近 relWinDays 日涨幅 − 基准指数(默认 sh000300 沪深300)
#        同窗口涨幅。跑赢 rsHi → +rsBonus；跑输 ≤ rsLo → rsPenalty。
#        基准缺失时该项跳过（不误伤）。
#     ② 缩量档位：量比 vr=当日量/前5日均量。vr< thinVr(0.5) 无量 → thinPenalty；
#        vr<thinVr~shrinkVr(0.8) 缩量 → shrinkPenalty。
#     ③ 低价龙头加分：收盘价≤ lowPriceMax 且 scored 名次≤ leaderTopK
#        → +leaderBonus（低价中的前排强势近似低价龙头）。
# ──────────────────────────────────────────────────────────────────────────
@register("volume_price_factor")
def _volume_price_factor(ctx, node, inputs):
    cfg = node.config or {}
    # 对拍开关：XML 无 enabled 时默认启用；拟合实验用 overrides 注入
    # {volume_price_factor: {enabled: "false"}} 即"未加量价因子"对照组（纯透传）。
    ov = ctx.node_configs.get("volume_price_factor") or {}
    enabled = str(ov.get("enabled", cfg.get("enabled", "true"))).lower() == "true"
    # mode（A/B 拟合实验收敛，2026-09 定稿）：
    #   auto   = 默认。牛市(BULLISH/趋势语境)全量加权，非牛市(analyze 低吸语境)只惩罚——
    #            对拍显示"追强加分(rsHi/低价龙头)"在震荡/熊市低吸链上为负贡献。
    #   full/boost = 全量加权（含追强加分+惩罚）。
    #   penalty = 仅惩罚项（无量/缩量/rs弱），去掉所有加分。
    mode = str(ov.get("mode", cfg.get("mode", "auto"))).lower()
    full_boost = mode in ("full", "boost") or (mode == "auto" and ctx.direction == "BULLISH")
    # overrides 注入优先于 XML param（拟合实验用），未注入时退回 XML 默认。
    g = lambda k, d=None: ov.get(k, cfg.get(k, d))
    scored = ctx.get("n_merge") or []
    if not scored:
        ctx.stage("n_vp", [])
        return []
    if not enabled:
        ctx.stage("n_vp", scored)
        return scored
    n_days = max(3, int(g("relWinDays", 10)))
    bench_code = g("benchmark", "sh000300")
    rs_hi = float(g("rsHi", 3.0)); rs_bonus = float(g("rsBonus", 4.0))
    rs_lo = float(g("rsLo", -2.0)); rs_pen = float(g("rsPenalty", -3.0))
    thin_vr = float(g("thinVr", 0.5)); thin_pen = float(g("thinPenalty", -4.0))
    shrink_vr = float(g("shrinkVr", 0.8)); shrink_pen = float(g("shrinkPenalty", -2.0))
    low_max = float(g("lowPriceMax", 10.0))
    leader_topk = max(1, int(g("leaderTopK", 5)))
    leader_bonus = float(g("leaderBonus", 3.0))
    bench = _bench_snaps(ctx, bench_code)
    bench_ret = _last_ret(bench, n_days) if bench else None
    need = max(n_days + 6, 30)
    out, det = [], {}
    for idx, (cid, sc) in enumerate(scored):
        snaps = [s for s in ctx.cache.get(cid, {}).get("snaps", [])
                 if s["date"] <= ctx.asof]
        delta = 0.0
        tags = []
        if len(snaps) >= need and snaps[-1].get("close", 0) > 0:
            closes = [float(s["close"]) for s in snaps]
            vols = [float(s.get("volume") or 0) for s in snaps]
            cur = closes[-1]
            base = closes[-1 - n_days]
            if bench_ret is not None and base > 0:
                rel = (cur / base - 1.0) * 100.0 - bench_ret
                if rel >= rs_hi:
                    if full_boost:
                        delta += rs_bonus
                        tags.append("rs强%.1f%%+%.1f" % (rel, rs_bonus))
                elif rel <= rs_lo:
                    delta += rs_pen
                    tags.append("rs弱%.1f%%%.1f" % (rel, rs_pen))
            prev5 = sum(vols[-6:-1])
            if prev5 > 0:
                vr = vols[-1] / (prev5 / 5.0)
                if vr < thin_vr:
                    delta += thin_pen
                    tags.append("无量vr=%.2f%.1f" % (vr, thin_pen))
                elif vr < shrink_vr:
                    delta += shrink_pen
                    tags.append("缩量vr=%.2f%.1f" % (vr, shrink_pen))
            if full_boost and cur <= low_max and idx < leader_topk:
                delta += leader_bonus
                tags.append("低价龙头+%.1f" % leader_bonus)
        if delta != 0.0:
            det[cid] = tags
        out.append((cid, min(100.0, sc + delta)))
    out.sort(key=lambda x: -x[1])
    ctx.stage("n_vp", out)
    ctx.stage("n_merge", out)          # 覆写，下游（boost/strict/orders）直接可见
    if det:
        ctx.stage("vp_detail", det)
    return out


def _bench_snaps(ctx, code):
    """基准指数日K：优先 ctx.cache（已含则免桥接），否则桥接 smalltools/_etf_cache.json。"""
    ent = ctx.cache.get(code) or {}
    snaps = [s for s in ent.get("snaps", []) if s["date"] <= ctx.asof]
    if snaps:
        return snaps
    try:
        path = os.path.join(_SMALLTOOLS_DIR, "_etf_cache.json")
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as f:
                j = json.load(f)
            snaps = [s for s in (j.get(code) or {}).get("snaps", [])
                     if s["date"] <= ctx.asof]
    except Exception as _e:
        ctx.notes.append("volume_price_factor: 基准 %s 不可用(%s)，相对强度项跳过" % (code, _e))
        return []
    return snaps


def _last_ret(snaps, n):
    """snaps 最后一日相对 n 日前收盘的涨跌幅(%)，数据不足返回 None。"""
    if not snaps:
        return None
    closes = [float(s["close"]) for s in snaps]
    if len(closes) <= n or closes[-1 - n] <= 0:
        return None
    return (closes[-1] / closes[-1 - n] - 1.0) * 100.0


# ──────────────────────────────────────────────────────────────────────────
# macro_event_bias：宏观/事件驱动加权（事件库节点，2026-09-05 新增）
#   数据源 = app/src/main/assets/macro_events/event_library.json（与 APK 共用单一事实源）
#   机制：当前窗口内活跃实例(start<=asof<=end) → 事件类型 seed_map(关键词→delta)
#         × 实例 magnitude × boostScale → 候选名命中即加(取 max，不叠加)。
#         · 动态：不硬编码"哪次事件利好哪板块"——每次靠实例 start/end/magnitude
#           区分强度与窗口，影响面由 seed_map 关键词按当期候选名动态命中；
#         · 经验：end < asof 的已结束历史实例为无未来函数样本，供后续学习脚本
#           回写 event_types[].learned 校正（当前版本先走种子先验）。
# ──────────────────────────────────────────────────────────────────────────
_EVENT_JSON = os.path.normpath(os.path.join(
    USECASES_DIR, "..", "macro_events", "event_library.json"))
_event_lib_cache = {}   # mtime -> (types, instances)


def _load_event_library():
    """事件库加载（按 mtime 缓存，改文件即生效）。"""
    try:
        mt = os.path.getmtime(_EVENT_JSON)
    except OSError:
        return None, None
    hit = _event_lib_cache.get(mt)
    if hit:
        return hit
    try:
        with open(_EVENT_JSON, "r", encoding="utf-8") as f:
            lib = json.load(f)
    except (OSError, ValueError):
        return None, None
    out = ({t["id"]: t for t in lib.get("event_types", [])},
           lib.get("instances", []))
    _event_lib_cache[mt] = out
    return out


def _ensure_event_state(ctx):
    """事件上下文懒计算（一次/run）：活跃事件聚合 eff 映射 + 正向强受益词。
    boost_kws 供 signal_merge/strict_selection 注入 macroSectorKeywords（放宽过筛）；
    eff 供 macro_event_bias 节点事后加权。"""
    if ctx.event_state is not None:
        return ctx.event_state
    types, insts = _load_event_library()
    active = []
    if types and insts and ctx.asof:
        for ins in insts:
            if (ins.get("start") or "9999") <= ctx.asof <= (ins.get("end") or "9999"):
                t = types.get(ins.get("event"))
                if t:
                    active.append((ins, t))
    eff = {}
    for ins, t in active:
        mag = float(ins.get("magnitude", 1.0))
        for kw, d in (t.get("seed_map") or {}).items():
            adj = float(d) * mag
            if kw not in eff or abs(adj) > abs(eff[kw]):
                eff[kw] = adj
    # 正向强受益词（放宽筛选用，默认阈值 1.8，可被 XML boostKwDelta 调整）
    kw_th = float((ctx.node_configs.get("macro_event_bias") or {}).get("boostKwDelta", 1.8))
    boost_kws = [kw for kw, d in eff.items() if d >= kw_th]
    st = {"active": active, "eff": eff, "boost_kws": boost_kws}
    ctx.event_state = st
    return st


@register("macro_event_bias")
def _macro_event_bias(ctx, node, inputs):
    """事件窗口加权（读链上最近有数据的一级；无活跃事件则原序透传）。"""
    scored = (ctx.get("n_idiom") or ctx.get("n_ancestral") or ctx.get("n_strict")
              or ctx.get("n_boost") or ctx.get("n_merge") or [])
    st = _ensure_event_state(ctx)
    active, eff = st["active"], st["eff"]
    if not scored or not active:
        ctx.stage("n_event", scored)
        return scored
    boost_scale = float((node.config or {}).get("boostScale", 1.0))
    if boost_scale != 1.0:
        eff = {k: v * boost_scale for k, v in eff.items()}
    out = []
    for cid, sc in scored:
        name = (ctx.cache.get(cid, {}) or {}).get("name", "")
        add = 0.0
        for kw, d in eff.items():
            if kw and kw in name:
                add = d if abs(d) > abs(add) else add
        out.append((cid, sc + add))
    out.sort(key=lambda x: -x[1])
    ctx.stage("n_event", out)
    return out


@register("strict_selection")
def _strict_selection(ctx, node, inputs):
    """均线粘合严选（六项严选）：震荡/熊市用 XML config 参数复检剔除；牛市趋势跟随已严选，直接放行。"""
    scored = ctx.get("n_boost") or ctx.get("n_merge") or []
    if ctx.direction == "BULLISH":
        ctx.stage("n_strict", scored)
        return scored
    bg = _st("backtest_guangmo")
    analyze_snaps, PARAMS = bg.analyze_snaps, bg.PARAMS
    period = ctx.adaptive.get("period", "short")
    base = PARAMS.get(_PERIOD_PARAM_KEY.get(period, "短线"), PARAMS["短线"])
    base = _xml_params(node, base)
    # 事件驱动：宏观事件强受益词放宽（与 signal_merge 一致）
    _kws = _ensure_event_state(ctx)["boost_kws"]
    if _kws:
        base["macroSectorKeywords"] = _kws
    strong = (ctx.stage_outputs or {}).get("strong_codes") or set()
    out = []
    for cid, sc in scored:
        if cid in strong:   # 改进B：强势延续旁路已判通过 → 不再受均线粘合严选剔除
            out.append((cid, sc))
            continue
        snaps = [dict(s) for s in ctx.cache.get(cid, {}).get("snaps", [])
                 if s["date"] <= ctx.asof]
        if len(snaps) < 30:
            continue
        snaps[-1]["name"] = ctx.cache.get(cid, {}).get("name", "")
        try:
            r = analyze_snaps(snaps, base, ctx.direction)
            if r and r.get("passed"):
                out.append((cid, sc))
        except Exception:
            continue
    ctx.stage("n_strict", out)
    return out


def _base_position_analyze(snaps):
    """BasePositionAnalyzer.analyze 同构（convergenceThreshold=0.02，纯 K 线，无 IO）。

    总纲：长线看势，中线看价，短线看量，超短看情绪；逃顶要快，抄底要慢。
      ① 三天不新低：近 3 日 low 全部 > 之前所有日最低 low（StabilityChecker.ABOVE_PRIOR_MIN）
      ② MA5/MA10/MA30 离散率 < 2% 且 MA5 上翘 → 粘合向上
      ③ 逃顶急：3 日跌幅 < -5% 或仍在创新低
    返回 dict(ready/converged_up/escape_urgent/hint)。
    """
    closes = [float(s["close"]) for s in snaps]
    lows = [float(s.get("low") if s.get("low") is not None else s["close"]) for s in snaps]
    prev = lows[:-3]
    no_new_low = bool(prev) and all(v > min(prev) for v in lows[-3:])
    ma5 = sum(closes[-5:]) / 5.0
    ma10 = sum(closes[-10:]) / 10.0
    ma30 = sum(closes[-30:]) / 30.0
    lo, hi = min(ma5, ma10, ma30), max(ma5, ma10, ma30)
    divergence = (hi - lo) / lo if lo > 0 else 1.0
    converged = divergence < 0.02
    y_ma5 = sum(closes[-6:-1]) / 5.0 if len(closes) >= 6 else ma5
    upward = ma5 > y_ma5
    chg3 = ((closes[-1] - closes[-4]) / closes[-4] * 100.0
            if len(snaps) >= 4 and closes[-4] > 0 else 0.0)
    ready = no_new_low and converged and upward
    escape = (chg3 < -5.0 or not no_new_low) and not ready
    hint = "%s | MA离散%.1f%%%s%s" % (
        "✅3天不新低" if no_new_low else "⚠️仍在创新低", divergence * 100.0,
        "✅粘合" if converged else "⚠️分散", "↑" if upward else "↓")
    return {"ready": ready, "converged_up": converged and upward,
            "escape_urgent": escape, "hint": hint}


@register("base_position_guard")
def _base_position_guard(ctx, node, inputs):
    """打底仓守门 + 逃顶要快（2026-09-12 新增：APK BasePositionGuardNode 同构移植）。

    背景：本 module 此前在 PC/Python 引擎「未实现」→ exe 跑同一份 pipeline XML 时
    n_base_guard 被 bypass，中线/长线买入缺「打底仓」门控，打分与 APK 漂移
    （见 smalltools/_nodes_exec_report.py 的 bypass 清单）。

    config：holdingPeriod = MID|SHORT|ULTRA_SHORT|LONG（默认 MID）
    规则（与 APK 逐字一致，score 为合并池强度 0~100）：
      买入(>50) 且打底仓未就绪 → −20（LONG −30）
      买入 且 MA 粘合向上      → +12（LONG +20）
      卖出(≤50) 且逃顶要快     → +12（LONG +18）
    上游 n_strict，输出 stage 到本节点 id（pipeline 固定 n_base_guard）。
    """
    hp = str(node.config.get("holdingPeriod") or "MID").upper()
    pool = ctx.get("n_strict") or ctx.get("n_boost") or ctx.get("n_merge") or []
    if not pool:
        ctx.stage(node.id, pool)
        return pool
    cache = ctx.cache or {}
    out, hit = [], 0
    for cid, sc in pool:
        snaps = sorted((cache.get(cid) or {}).get("snaps") or [],
                       key=lambda s: s["date"])
        if len(snaps) < 30:
            out.append((cid, sc))
            continue
        a = _base_position_analyze(snaps)
        is_buy = sc > 50
        ns = sc
        if is_buy and not a["ready"]:
            ns = sc - (30 if hp == "LONG" else 20)
            hit += 1
        elif is_buy and a["converged_up"]:
            ns = sc + (20 if hp == "LONG" else 12)
            hit += 1
        elif (not is_buy) and a["escape_urgent"]:
            ns = sc + (18 if hp == "LONG" else 12)
            hit += 1
        out.append((cid, max(0.0, min(100.0, float(ns)))))
    out.sort(key=lambda x: -x[1])
    ctx.stage(node.id, out)
    if hit:
        ctx.notes.append("[%s] 🛡️ 打底仓守门(%s): %d/%d 只触发"
                         % (node.id, hp, hit, len(pool)))
    return out


# ── 板块强弱 / 风格轮动（2026-09-12 新增：APK MarketPublicPipelineNodes.kt 同构移植）──
# 跨端口径说明：APK 的 SectorStrengthNode 从 `sector_daily_record` 表读板块热度记录；
# PC 无该表，改用 `hot_sector_config.HOT_SECTOR_CONFIG` 的板块成分股 K 线重算
# （与本文件 sector_ambush 同源口径，见 2678 行说明）。排序规则与 APK 逐条一致：
#     热门天数 ↓ → 综合分 ↓ → 日均涨跌 ↓ → 主力净流入 ↓
# 其中「主力净流入」PC 侧无资金流数据，恒为 0.0（不引入额外排序差异）。


def _sector_members(hs):
    """板块名 → 成分股 key 列表（'601969.SH' → 'sh601969'）。"""
    out = {}
    for board, bv in (hs or {}).items():
        codes = set()
        for _sub, sv in ((bv or {}).get("sub_sectors") or {}).items():
            for secid in ((sv or {}).get("leaders") or {}):
                codes.add(_amb_secid_to_key(secid))
        if codes:
            out[board] = sorted(codes)
    return out


def _sector_avg_ret(cache, ctx, codes, win):
    """板块成分股近 win 日平均涨幅（%）；样本不足返回 None。"""
    rs = []
    for c in codes:
        snaps = (cache.get(c) or {}).get("snaps") or []
        i = _dip_row_at(ctx, snaps)
        if i < win or not snaps[i].get("close"):
            continue
        c0 = snaps[i - win].get("close")
        if c0:
            rs.append((snaps[i]["close"] / c0 - 1) * 100.0)
    return (sum(rs) / len(rs)) if rs else None


@register("sector_strength")
def _sector_strength(ctx, node, inputs):
    """板块强弱监测：Top N 强势板块 + 热门板块代码。

    config：lookbackDays="20"（统计窗口）、topN="15"（输出数量）
    输出 stage：{as_of, topSectors[], hotSectorCodes[], text}
    下游：style_rotation 读 n_sector_strength 拿强势板块。
    """
    cfg = node.config or {}
    lookback = int(cfg.get("lookbackDays") or 20)
    top_n = int(cfg.get("topN") or 15)
    cache = ctx.cache or {}
    members = _sector_members(_load_hot_sector_config())
    if not members:
        ctx.notes.append("[%s] ⚠️ 无板块成分股映射（hot_sector_config 缺失），板块强弱跳过"
                         % node.id)
        out = {"as_of": "", "topSectors": [], "hotSectorCodes": [], "text": "无板块数据"}
        ctx.stage(node.id, out)
        return out

    stats, as_of = [], ""
    for board, codes in members.items():
        rets, up_days, used = [], [], 0
        for c in codes:
            snaps = (cache.get(c) or {}).get("snaps") or []
            i = _dip_row_at(ctx, snaps)
            if i < lookback or not snaps[i].get("close"):
                continue
            seg = snaps[i - lookback:i + 1]
            c0 = seg[0].get("close")
            if not c0:
                continue
            rets.append((seg[-1]["close"] / c0 - 1) * 100.0)
            up_days.append(sum(1 for k in range(1, len(seg))
                               if seg[k]["close"] > seg[k - 1]["close"]))
            used += 1
            if snaps[i]["date"] > as_of:
                as_of = snaps[i]["date"]
        if not rets:
            continue
        r5 = _sector_avg_ret(cache, ctx, codes, 5)
        r10 = _sector_avg_ret(cache, ctx, codes, 10)
        r20 = _sector_avg_ret(cache, ctx, codes, 20)
        stats.append({
            "code": board, "name": board,
            "avgChangePct": sum(rets) / len(rets),
            "hotDays": (sum(up_days) / len(up_days)) if up_days else 0.0,
            "compositeScore": 0.5 * (r10 or 0.0) + 0.3 * (r20 or 0.0) + 0.2 * (r5 or 0.0),
            "mainNetInflow": 0.0, "memberCount": used,
        })
    if not stats:
        ctx.notes.append("[%s] ⚠️ 板块成分股在缓存中无数据，无法计算板块强弱" % node.id)
        out = {"as_of": "", "topSectors": [], "hotSectorCodes": [], "text": "无板块数据"}
        ctx.stage(node.id, out)
        return out

    stats.sort(key=lambda s: (-s["hotDays"], -s["compositeScore"],
                              -s["avgChangePct"], -s["mainNetInflow"]))
    for k, s in enumerate(stats[:top_n]):
        s["rank"] = k + 1
    top = stats[:top_n]

    lines = ["📊 板块强弱监测（近 %d 日）" % lookback, "最强板块 Top %d：" % len(top)]
    for s in top[:8]:
        lines.append("  #%d %s  日均涨跌 %+.2f%%  上涨 %d 天  动量 %.2f"
                     % (s["rank"], s["name"], s["avgChangePct"],
                        int(round(s["hotDays"])), s["compositeScore"]))
    if len(top) > 8:
        lines.append("  ... 共 %d 个板块上榜" % len(top))
    lines.append("热门板块: %s" % "、".join(s["name"] for s in top[:8]))

    out = {"as_of": as_of, "lookbackDays": lookback, "topSectors": top,
           "hotSectorCodes": [s["name"] for s in top], "text": "\n".join(lines)}
    ctx.stage(node.id, out)
    ctx.stage("n_sector_strength", out)
    ctx.notes.append("[%s] 📊 板块强弱：%s | 共 %d 个板块"
                     % (node.id, "、".join(s["name"] for s in top[:5]), len(stats)))
    return out


_STYLE_SEASON = {
    12: "📅 冬播春耕季：关注化肥/草甘膦/农化（12-1月备耕、2-4月主升浪）",
    1: "📅 冬播春耕季：关注化肥/草甘膦/农化（12-1月备耕、2-4月主升浪）",
    2: "📅 春季主升浪窗口：题材活跃度提升，可适当提高短线参与度",
    3: "📅 春季主升浪窗口：题材活跃度提升，可适当提高短线参与度",
    4: "📅 春季主升浪窗口：题材活跃度提升，可适当提高短线参与度",
    5: "📅 年中震荡期：业绩窗口临近，回避纯题材炒作",
    6: "📅 年中震荡期：业绩窗口临近，回避纯题材炒作",
    7: "📅 年中震荡期：业绩窗口临近，回避纯题材炒作",
    8: "📅 中报密集期：关注业绩确定性（银行/资源/高股息）",
    9: "📅 中报密集期：关注业绩确定性（银行/资源/高股息）",
    10: "📅 四季度：关注低估值修复 + 来年春季行情预演",
    11: "📅 四季度：关注低估值修复 + 来年春季行情预演",
}


@register("style_rotation")
def _style_rotation(ctx, node, inputs):
    """风格轮动判断：大盘环境 + 板块强弱 → 风格 / 风险 / 建议持仓周期 + 提示。

    依赖：n_a_market（a_market_analysis → ctx.market）、n_sector_strength
    输出 stage：{styleLabel, leadingSectors[], suggestedPeriod, riskLevel, text}
    与 APK 一致：大盘缺失时走「均衡震荡」兜底分支（APK 的 market == null 分支）。
    """
    import datetime as _dt
    sec = ctx.get("n_sector_strength") or {}
    leading = [s.get("name") for s in (sec.get("topSectors") or [])[:5] if s.get("name")]
    market = getattr(ctx, "market", None) or {}
    detail = market.get("detail") if isinstance(market.get("detail"), dict) else {}
    state = market.get("state") or getattr(ctx, "direction", None) or "OSCILLATION"

    if state == "BEARISH":
        style, risk, period = "弱势防守（价值防御）", "中高", "长线/高股息防御"
    elif state == "BULLISH":
        style, risk, period = "趋势上行（顺势进攻）", "中低", "中期/趋势跟随"
    else:
        style, risk, period = "均衡震荡（结构性行情）", "中", "超短/短线快进快出"

    names = "".join(leading)
    if any(k in names for k in ("银行", "煤炭", "电力", "保险", "石油", "高速公路")):
        style_hint = "（偏价值/高股息防守）"
    elif any(k in names for k in ("半导体", "通信", "软件", "电子", "传媒", "游戏")):
        style_hint = "（偏成长/科技）"
    elif any(k in names for k in ("有色", "化工", "钢铁", "基建", "地产")):
        style_hint = "（偏周期）"
    else:
        style_hint = ""

    temp = str(detail.get("temp") or detail.get("marketTemp") or "")
    season = _STYLE_SEASON.get(_dt.date.today().month, "")
    lines = ["🎨 风格轮动判断：%s %s" % (style, style_hint),
             "   当前强势板块: %s" % ("、".join(leading) if leading else "暂无"),
             "   建议持仓周期: %s" % period,
             "   风险等级: %s" % risk]
    if temp:
        lines.append("   市场温度: %s" % temp)
    if season:
        lines.append("   " + season)

    out = {"styleLabel": style + style_hint, "leadingSectors": leading,
           "suggestedPeriod": period, "riskLevel": risk,
           "text": "\n".join(lines)}
    ctx.stage(node.id, out)
    ctx.stage("n_style_rotation", out)
    ctx.notes.append("[%s] 🎨 风格轮动：%s | 强势板块 %s"
                     % (node.id, out["styleLabel"], "、".join(leading[:3]) or "无"))
    return out


_DIR_CN = {"UPTREND": "上升趋势", "DOWNTREND": "下降趋势",
           "ACCUMULATION": "横盘蓄势", "BREAKOUT": "放量突破",
           "OSCILLATION": "区间震荡"}


def _direction_of(snaps, price=None):
    """DirectionLabelNode.computeDirection 的「从 K 线重算」分支 + DirectionAnalyzer.analyze 同构。

    PC 引擎没有 APK 的 `strict_selection_eval` 中间产物，故一律走重算路径
    （snaps < 30 根时 APK 亦返回 OSCILLATION，此处保持一致）。
    判定优先级：突破 > 蓄势 > 上升 > 下降 > 震荡。
    """
    if not snaps:
        return "OSCILLATION"
    closes = [float(s["close"]) for s in snaps]
    highs = [float(s.get("high") if s.get("high") is not None else s["close"]) for s in snaps]
    lows = [float(s.get("low") if s.get("low") is not None else s["close"]) for s in snaps]
    vols = [float(s.get("volume") or 0) for s in snaps]
    if len(closes) < 30:
        return "OSCILLATION"
    ma5 = sum(closes[-5:]) / 5.0
    ma10 = sum(closes[-10:]) / 10.0
    ma20 = sum(closes[-20:]) / 20.0
    ma60 = sum(closes[-60:]) / 60.0 if len(closes) >= 60 else None
    ma60_rising = (ma60 is not None and len(closes) >= 66
                   and closes[len(closes) - 61] < ma60)
    high20, low20 = max(highs[-20:]), min(lows[-20:])
    conv = (high20 - low20) / low20 * 100.0 if low20 > 0 else 999.0
    conv_ok = conv <= 6.0
    close_above_top = closes[-1] >= high20 * 0.995
    above_all = closes[-1] > ma20
    vol5 = sum(vols[-5:]) / 5.0
    vol20 = sum(vols[-20:]) / 20.0
    vr = vol5 / vol20 if vol20 > 0 else 1.0
    close = float(price) if price else closes[-1]
    # 粘合持续天数：APK 用 convergenceOk 折算 (10 天 / 0 天)
    converging = 0.1 <= conv <= 6.0 and (10 if conv_ok else 0) >= 8
    if converging and close_above_top and vr >= 1.15 and above_all:
        return "BREAKOUT"
    if converging:
        return "ACCUMULATION"
    if ma5 > ma10 > ma20 and close > ma5 and ma60_rising:
        return "UPTREND"
    if ma5 < ma10 < ma20 and close < ma5 and not ma60_rising:
        return "DOWNTREND"
    return "OSCILLATION"


@register("direction_label")
def _direction_label(ctx, node, inputs):
    """个股方向标签（先判方向，再定周期，2026-09-12 新增：APK DirectionLabelNode 同构移植）。

    config：exclude="DOWNTREND,OSCILLATION"（要剔除的方向）、penalty="25"（剔除方向扣分）
    上游 n_idiom（经 n_inst_tips 透传），输出 stage 到本节点 id，并把逐股标签写入
    stage 键 `direction_labels`（供 leader_track 复用）。剔除只扣分不硬删，
    与 APK 一致（低于后续过滤线自然被淘汰）。
    """
    cfg = node.config or {}
    exclude = [x.strip().upper() for x in
               str(cfg.get("exclude") or "DOWNTREND,OSCILLATION").split(",") if x.strip()]
    penalty = float(cfg.get("penalty") or 25)
    pool = (ctx.get("n_inst_tips") or ctx.get("n_idiom") or ctx.get("n_ancestral")
            or ctx.get("n_boost") or ctx.get("n_merge") or [])
    if not pool:
        ctx.stage(node.id, pool)
        return pool
    cache = ctx.cache or {}
    labels, out, excluded = {}, [], 0
    for cid, sc in pool:
        snaps = sorted((cache.get(cid) or {}).get("snaps") or [],
                       key=lambda s: s["date"])
        d = _direction_of(snaps)
        labels[cid] = d
        if d in exclude:
            excluded += 1
            out.append((cid, max(0.0, min(100.0, float(sc) - penalty))))
        else:
            out.append((cid, sc))
    out.sort(key=lambda x: -x[1])
    ctx.stage(node.id, out)
    ctx.stage("direction_labels", labels)
    dist = " ".join("%s:%d" % (k, sum(1 for v in labels.values() if v == k))
                    for k in ("UPTREND", "ACCUMULATION", "BREAKOUT",
                              "DOWNTREND", "OSCILLATION")
                    if any(v == k for v in labels.values()))
    ctx.notes.append("[%s] 🧭 方向标签 %d 只(%s)，剔除[%s] %d 只"
                     % (node.id, len(labels), dist, "/".join(exclude), excluded))
    return out


# ──────────────────────────────────────────────────────────────────────────
# v6 三节点（季节日历 / 龙头跟踪）+ 板块精选池 + 跨日聚合 + 轮动惩罚 + 防守高息
# 2026-09-13 新增：APK PeriodV23Nodes.kt / QuantTradingPipeline.kt /
# HardcodeCompatNodes.kt / DefensiveDividendNode.kt 同构移植，补齐
# smalltools/_nodes_exec_report.py 的 P1「未实现 module」清单：
#   seasonality_boost / leader_track / sector_stock_pool /
#   cross_day_aggregation / rotation_penalty / defensive_dividend
# ──────────────────────────────────────────────────────────────────────────

# ── ① 行业季节/周期日历（与 APK IndustrySeasonalityCalendar.ENTRIES 逐条一致）──
# (主题, 名称关键词, monthFrom, monthTo, weight, anchor, anchorUp)
_SEASON_ENTRIES = (
    ("春耕备耕", ("化肥", "农药", "种业", "种子", "农机", "尿素", "钾肥", "磷肥"), 2, 4, 1.2, None, True),
    ("年报预增季", ("预增",), 1, 4, 0.8, None, True),
    ("汛期防汛", ("水利", "防汛", "管网", "水泵"), 5, 7, 0.9, None, True),
    ("夏季用电高峰", ("电力", "电网", "特高压", "智能电网", "变压器", "电线电缆", "光伏", "储能"), 6, 8, 1.1, None, True),
    ("光伏装机旺季", ("光伏", "太阳能", "多晶硅", "逆变器"), 6, 11, 0.9, None, True),
    ("中报预增季", ("预增",), 7, 8, 0.8, None, True),
    ("金九银十", ("消费电子", "汽车电子", "家电", "PCB", "铜缆"), 9, 10, 1.0, None, True),
    ("年底备货", ("半导体", "算力", "存储", "光模块", "光通信", "服务器", "PCB"), 11, 12, 1.0, None, True),
    ("春季拉货", ("半导体", "面板", "PCB"), 2, 3, 0.9, None, True),
    ("供暖季", ("燃气", "煤炭", "供热", "电力"), 11, 1, 0.9, None, True),
    ("锂价上涨", ("锂", "盐湖"), 1, 12, 1.0, "锂价", True),
    ("锂价下跌(电池受益)", ("电池", "正极", "负极", "电解液"), 1, 12, 0.8, "锂价", False),
    ("油价上涨", ("石油", "油气", "油服", "石化"), 1, 12, 1.0, "油价", True),
    ("油价下跌(化工受益)", ("化工", "化纤", "塑料", "PTA"), 1, 12, 0.8, "油价", False),
    ("铜价上涨", ("铜", "电缆"), 1, 12, 1.0, "铜价", True),
    ("金价上涨", ("黄金", "贵金属"), 1, 12, 0.9, "金价", True),
)


def _season_month(ctx):
    """当前月（1..12）：优先 asof 交易日，缺省用系统月（对齐 APK tradeDate 取值）。"""
    d = str(getattr(ctx, "asof", "") or "")
    if len(d) >= 7:
        try:
            return int(d[5:7])
        except ValueError:
            pass
    import datetime as _dt
    return _dt.date.today().month


def _season_anchors():
    """backtest_params.seasonality → (enabled, anchors)。缺文件按(True, {})兜底。"""
    try:
        p = os.path.normpath(os.path.join(_HERE, os.pardir, "backtest_params.json"))
        with open(p, encoding="utf-8") as f:
            s = (json.load(f) or {}).get("seasonality") or {}
        return bool(s.get("enabled", True)), dict(s.get("anchors") or {})
    except Exception:
        return True, {}


def _season_active_themes(month, anchors):
    """当期生效主题 [(theme, keywords, weight)]（含锚定方向确认，支持跨年窗口）。"""
    out = []
    for theme, kws, mf, mt, w, anchor, up in _SEASON_ENTRIES:
        in_win = (mf <= month <= mt) if mf <= mt else (month >= mf or month <= mt)
        if not in_win:
            continue
        if anchor is not None and anchors.get(anchor) != ("up" if up else "down"):
            continue
        out.append((theme, kws, w))
    return out


@register("seasonality_boost")
def _seasonality_boost(ctx, node, inputs):
    """行业季节日历加分（APK SeasonalityBoostNode 同构移植）。

    命中当期主题 → strength += bonus，bonus = int(命中权重和 × 10 × multiplier)
    并 clamp [1,30]；重排后 stage 到本节点 id，生效主题写入 `seasonality_themes`。
    上游 n_direction；config：multiplier（默认 1.0）。
    """
    pool = (ctx.get("n_direction") or ctx.get("n_inst_tips") or ctx.get("n_idiom")
            or ctx.get("n_ancestral") or ctx.get("n_boost") or ctx.get("n_merge") or [])
    if not pool:
        ctx.stage(node.id, pool)
        return pool
    enabled, anchors = _season_anchors()
    if not enabled:
        ctx.notes.append("[%s] 📅 seasonality.enabled=false，跳过" % node.id)
        ctx.stage(node.id, pool)
        return pool
    month = _season_month(ctx)
    active = _season_active_themes(month, anchors)
    if not active:
        ctx.notes.append("[%s] 📅 %d月无生效季节主题（锚定:%s）" % (node.id, month, anchors or {}))
        ctx.stage(node.id, pool)
        return pool
    multiplier = float((node.config or {}).get("multiplier") or 1.0)
    cache = ctx.cache or {}
    out, hit_n = [], 0
    for cid, sc in pool:
        name = (cache.get(cid) or {}).get("name") or ""
        matched = [w for _t, kws, w in active if any(k in name for k in kws)]
        if not matched:
            out.append((cid, sc))
            continue
        hit_n += 1
        bonus = int(max(1, min(30, sum(matched) * 10 * multiplier)))
        out.append((cid, max(0.0, min(100.0, float(sc) + bonus))))
    out.sort(key=lambda x: -x[1])
    ctx.stage(node.id, out)
    ctx.stage("seasonality_themes", [t for t, _k, _w in active])
    ctx.notes.append("[%s] 📅 %d月生效 %d 个主题，%d 只命中（%s）"
                     % (node.id, month, len(active), hit_n,
                        " | ".join(t for t, _k, _w in active)))
    return out


# ── ② 龙头股跟踪（与 APK LeaderTracker 同分 / LeaderTrackNode 同序）──
def _leader_mainline_configs():
    """产业主线板块 → 龙头 secid 列表（PC 侧 data/_industry_leader_map.json）。

    对齐 APK LeaderStockPool.getMainlineConfigs（cfg.name + subSectors[].stocks）。
    """
    out, path = [], os.path.join(_REPO_ROOT, "data", "_industry_leader_map.json")
    try:
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        for sec in (d.get("sectors") or []):
            tname = sec.get("track") or sec.get("board") or ""
            codes = [l.get("secid") for l in (sec.get("core_leaders") or []) if l.get("secid")]
            if tname and codes:
                out.append((tname, codes))
    except Exception:
        pass
    return out


def _leader_dir_bonus(direction):
    """方向加成：突破 4 / 上升 3 / 蓄势 2，其余 0（APK LeaderTracker.directionBonus）。"""
    return {"BREAKOUT": 4.0, "UPTREND": 3.0, "ACCUMULATION": 2.0}.get(direction, 0.0)


@register("leader_track")
def _leader_track(ctx, node, inputs):
    """龙头股跟踪（APK LeaderTrackNode + LeaderTracker 同构移植）。

    同产业主线板块内按 leaderScore 取最高者为龙头 → strength += bonus（默认 8）；
    板块龙头 / 状态写入 `leader_map` / `leader_status`。上游 n_season。
    config：bonus（默认 8）、onlyMainline（PC 只有主线一份口径）。
    """
    pool = (ctx.get("n_season") or ctx.get("n_direction") or ctx.get("n_inst_tips")
            or ctx.get("n_boost") or ctx.get("n_merge") or [])
    if not pool:
        ctx.stage(node.id, pool)
        return pool
    cfg = node.config or {}
    bonus = int(cfg.get("bonus") or 8)
    sector_of = {}
    for tname, codes in _leader_mainline_configs():
        for c in codes:
            sector_of.setdefault(c, tname)
    if not sector_of:
        ctx.notes.append("[%s] 🐲 未找到产业主线板块映射（_industry_leader_map.json），跳过"
                         % node.id)
        ctx.stage(node.id, pool)
        return pool
    labels = ctx.get("direction_labels") or {}
    cache = ctx.cache or {}
    groups = {}
    for cid, sc in pool:
        sec = sector_of.get(cid)
        if not sec:
            continue
        snaps = (cache.get(cid) or {}).get("snaps") or []
        chg = float((snaps[-1].get("changePct") if snaps else 0.0) or 0.0)
        # APK 入参固定 volumeRatio=1.0 / passCount=1 / drawdownPct=0.0
        score = (float(sc) * 0.5 + chg * 0.8 + 1.0 * 0.5 + 1 * 1.5
                 + _leader_dir_bonus(labels.get(cid)))
        groups.setdefault(sec, []).append((score, cid))
    leader_map = {sec: max(v)[1] for sec, v in groups.items()}
    if not leader_map:
        ctx.notes.append("[%s] 🐲 候选股未命中产业主线板块，跳过" % node.id)
        ctx.stage(node.id, pool)
        return pool
    leader_codes = set(leader_map.values())
    out, n = [], 0
    for cid, sc in pool:
        if cid in leader_codes:
            n += 1
            out.append((cid, max(0.0, min(100.0, float(sc) + bonus))))
        else:
            out.append((cid, sc))
    out.sort(key=lambda x: -x[1])
    ctx.stage(node.id, out)
    ctx.stage("leader_map", leader_map)
    names = {cid: (cache.get(cid) or {}).get("name", cid) for cid, _ in pool}
    ctx.stage("leader_status", ";".join("%s:%s(%s)" % (s, c, names.get(c, c))
                                        for s, c in leader_map.items()))
    ctx.notes.append("[%s] 🐲 %d 个板块标龙头（%s），龙头加分 %d，共 %d 只"
                     % (node.id, len(leader_map),
                        " | ".join("%s→%s" % (s, names.get(c, c)) for s, c in leader_map.items()),
                        bonus, n))
    return out


# ── ③ 板块精选池（APK SectorStockPoolNode + HotSectorStockPool 同构移植）──
def _amb_is_main_board(code):
    """主板判定（与 APK HotSectorStockPool.isMainBoard 一致）。"""
    return not code.startswith(("sz300", "sz301", "sh688", "bj"))


@register("sector_stock_pool")
def _sector_stock_pool(ctx, node, inputs):
    """板块精选池：热门板块 → 每(子)板块「前 5 主板 + 前 5 科创/创业」合并去重。

    config：hotDims="today,weekly,rotation"（逗号分隔，可含 monthly/quarterly）
    输出 stage `sector_stock_codes`，供 candidate_pool 合并；返回市场上下文透传。
    跨端说明：APK 从 StrategyMarketContext 取各周期热门板块并用 SectorSubDivision
    展开子板块；PC 以 n_sector_strength 折算 today（hotDays 序）/weekly（综合分序）/
    monthly|quarterly（日均涨跌序），rotation 取 n_style_rotation.leadingSectors，
    子板块直接用 hot_sector_config 已内置的 sub_sectors。
    """
    cfg = node.config or {}
    dims = [d.strip() for d in str(cfg.get("hotDims") or "today").split(",") if d.strip()]
    top = list((ctx.get("n_sector_strength") or {}).get("topSectors") or [])
    names = []
    if "today" in dims:
        names += [s["name"] for s in top[:8]]
    if "weekly" in dims:
        names += [s["name"] for s in sorted(top, key=lambda x: -x.get("compositeScore", 0))[:8]]
    if "monthly" in dims or "quarterly" in dims:
        names += [s["name"] for s in sorted(top, key=lambda x: -x.get("avgChangePct", 0))[:8]]
    if "rotation" in dims:
        names += list((ctx.get("n_style_rotation") or {}).get("leadingSectors") or [])
    if not names:
        names = [s["name"] for s in top[:8]]
    names = [n for n in dict.fromkeys(names) if n]
    hs = _load_hot_sector_config() or {}
    picked = []
    for board in names:
        subs = ((hs.get(board) or {}).get("sub_sectors") or {})
        for _sub, sv in subs.items():
            main_b, sci = [], []
            for secid in ((sv or {}).get("leaders") or {}):
                c = _amb_secid_to_key(secid)
                (main_b if _amb_is_main_board(c) else sci).append(c)
            picked += main_b[:5] + sci[:5]
    codes = sorted(set(picked))
    ctx.stage("sector_stock_codes", codes)
    ctx.notes.append("[%s] 🧺 板块精选池：%d 只（维度 %s，覆盖板块 %s）"
                     % (node.id, len(codes), ",".join(dims) or "today",
                        "、".join(names[:8]) or "无"))
    return ctx.get("n_ctx") or ctx.market or {}


# ── ④ 跨日聚合（APK CrossDayAggregationNode 同构移植）──
@register("cross_day_aggregation")
def _cross_day_aggregation(ctx, node, inputs):
    """跨日聚合：回溯最近 windowDays 个交易日逐日重跑策略，统计命中天数取 Top N。

    config：windowDays（默认 5）、topN（默认 20）
    输出 [(code, days), ...] stage 到本节点 id（中线需连续性验证，短线用不上）。
    跨端说明：APK 由 `_strategies` 注入策略列表；PC 用同一套引擎
    （BULLISH+短周期 → trend_follow_scan，否则 → 粘合引擎 analyze_snaps）。
    """
    cfg = node.config or {}
    window = int(cfg.get("windowDays") or 5)
    top_n = int(cfg.get("topN") or 20)
    pool = list(ctx.candidates or ctx.stock_pool or ctx.get("n_multihot") or [])
    if not pool:
        ctx.notes.append("[%s] 🔁 股票池为空，跳过跨日聚合" % node.id)
        ctx.stage(node.id, [])
        return []
    dates = [d for d in (ctx.all_dates or []) if d <= (ctx.asof or "")][-window:]
    if len(dates) < 2:
        ctx.notes.append("[%s] 🔁 可用交易日仅 %d 天，数据不足" % (node.id, len(dates)))
        ctx.stage(node.id, [])
        return []
    period = ctx.adaptive.get("period", "short")
    bg = _st("backtest_guangmo")
    analyze_snaps, PARAMS = bg.analyze_snaps, bg.PARAMS
    base = PARAMS.get(_PERIOD_PARAM_KEY.get(period, "短线"), PARAMS["短线"])
    base = _xml_params(type("_n", (), {"config": ctx.node_configs.get("strict_selection") or {}}),
                       base)
    tf_scan = _st("_trend_proto").trend_follow_scan
    cache = ctx.cache or {}
    hits = {}
    for d in dates:
        for cid in pool:
            snaps = [s for s in (cache.get(cid) or {}).get("snaps") or [] if s["date"] <= d]
            if len(snaps) < 30:
                continue
            snaps = [dict(s) for s in snaps]
            snaps[-1]["name"] = (cache.get(cid) or {}).get("name", "")
            try:
                if ctx.direction == "BULLISH" and period in ("ultra_short", "short"):
                    res = tf_scan(snaps, ctx.direction,
                                  "ultra_short" if period == "ultra_short" else "short")
                    ok = bool(res[0]) if isinstance(res, tuple) else bool(res)
                else:
                    r = analyze_snaps(snaps, base, ctx.direction)
                    ok = bool(r and r.get("passed"))
            except Exception:
                ok = False
            if ok:
                hits[cid] = hits.get(cid, 0) + 1
    ranking = sorted(hits.items(), key=lambda kv: (-kv[1], kv[0]))[:top_n]
    ctx.stage(node.id, ranking)
    ctx.notes.append("[%s] 🔁 跨日聚合：回溯 %d 天 / %d 只股票，%d 只入围 Top%d%s"
                     % (node.id, len(dates), len(pool), len(ranking), top_n,
                        ("（" + ", ".join("%s %d天" % (c, n) for c, n in ranking[:3]) + "）")
                        if ranking else ""))
    return ranking


# ── ⑤ 板块轮动惩罚（APK RotationPenaltyNode v2 同构移植）──
_STOCK_SECTOR_MAP = None


def _stock_sector_map():
    """个股 → 板块名列表（反查 hot_sector_config，对齐 APK StockDataCenter.getSectorsByStock）。"""
    global _STOCK_SECTOR_MAP
    if _STOCK_SECTOR_MAP is None:
        rev = {}
        for board, codes in _sector_members(_load_hot_sector_config()).items():
            for c in codes:
                rev.setdefault(c, []).append(board)
        _STOCK_SECTOR_MAP = rev
    return _STOCK_SECTOR_MAP


@register("rotation_penalty")
def _rotation_penalty(ctx, node, inputs):
    """板块轮动惩罚 v2：集中度(对数衰减) × 生命周期 × 跨日轮动因子。

    config：thresholdDays（默认 3）、penaltyPerExcess（默认 10）
    输出 {rotationPenalty, sectorPenalties, rotationSpeedFactor, overlap}，下游
    smart_money_filter 据此对受罚板块个股降分（惩罚闭环）。
    跨端说明：APK 的 sectorDailyRecord（板块连续热门天数 / 昨日 Top10 板块）PC 无此表，
    hotDays 改由 n_sector_strength.hotDays 折算；昨日板块 PC 无历史 → overlap 取 0.5
    默认中等轮动（rotationSpeedFactor=1.0），不引入额外惩罚偏差。
    """
    import math
    cfg = node.config or {}
    threshold = int(cfg.get("thresholdDays") or 3)
    per_excess = int(cfg.get("penaltyPerExcess") or 10)
    pool = ctx.get("n_boost") or ctx.get("n_merge") or []
    if not pool:
        res = {"rotationPenalty": 0, "sectorPenalties": {},
               "rotationSpeedFactor": 1.0, "overlap": 0.5}
        ctx.stage(node.id, res)
        return res
    rev = _stock_sector_map()
    counts = {}
    for cid, _sc in pool:
        for s in rev.get(cid, ()):
            counts[s] = counts.get(s, 0) + 1
    hot_days = {s.get("name"): int(round(s.get("hotDays") or 0))
                for s in ((ctx.get("n_sector_strength") or {}).get("topSectors") or [])}
    today_top = {s for s, c in counts.items() if c >= 2}
    y_sectors = set(ctx.get("yesterday_sectors") or ())
    overlap = (len(today_top & y_sectors) / float(len(today_top))
               if (today_top and y_sectors) else 0.5)
    factor = 1.3 if overlap < 0.3 else (0.7 if overlap > 0.7 else 1.0)
    penalty, sector_pen, labels = 0, {}, []
    for s, c in counts.items():
        if c < threshold:
            continue
        base_p = int(math.log2(c - threshold + 2.0) * per_excess)   # log2(excess+1)
        hd = hot_days.get(s, 0)
        lf = 0.5 if hd <= 2 else (1.0 if hd <= 4 else 1.5)
        sp = int(base_p * lf)
        penalty -= sp
        sector_pen[s] = -sp
        labels.append("%s(%d次/%s/-%d)"
                      % (s, c, ("启动%d d" % hd) if hd <= 2 else
                         (("高潮%d d" % hd) if hd <= 4 else ("退潮%d d" % hd)), sp))
    final = max(-100, min(0, int(penalty * factor)))
    scaled = {s: max(-100, min(0, int(p * factor))) for s, p in sector_pen.items()}
    res = {"rotationPenalty": final, "sectorPenalties": scaled,
           "rotationSpeedFactor": factor, "overlap": overlap}
    ctx.stage(node.id, res)
    if final < 0:
        ctx.notes.append("[%s] ⚠️ 轮动惩罚v2 %d 分（轮动因子 %.1f，跨日重合度 %.0f%%）：%s"
                         % (node.id, final, factor, overlap * 100, ", ".join(labels)))
    else:
        ctx.notes.append("[%s] ⚠️ 轮动惩罚v2：无惩罚（板块分散度正常）" % node.id)
    return res


# ── ⑥ 防守高息（APK DefensiveDividendNode 同构移植）──
_DEFENSIVE_SECTORS = ("银行", "保险", "电力", "高速公路", "煤炭", "石油", "电信",
                      "水务", "燃气", "铁路", "港口", "机场")
_HIGH_DEBT_EXEMPT = ("银行", "保险")


def _defensive_fundamentals(cid, cache):
    """取基本面字段（APK daily_snapshot v12）：pb/pe/marketCap/roeTTM/debtToAsset。

    PC 缓存当前只有价量 → 返回 None，节点按「降级口径」运行并在 notes 说明；
    一旦缓存补齐这些字段即自动切到完整口径。
    """
    e = cache.get(cid) or {}
    snaps = e.get("snaps") or []
    if not snaps:
        return None
    s = snaps[-1]
    if not any(k in s for k in ("pb", "pe", "marketCap", "roeTTM", "debtToAsset")):
        return None
    return {"pb": float(s.get("pb") or 0), "pe": float(s.get("pe") or 0),
            "marketCap": float(s.get("marketCap") or 0),
            "roe": float(s.get("roeTTM") or 0),
            "debtToAsset": float(s.get("debtToAsset") or 0)}


def _defensive_cap_proxy(cid):
    """市值代理（亿 → 元）：用 _industry_leader_map 的 mv_yi；无则 0（视为未知）。"""
    _ensure_leader_mv()
    return (_LEADER_MV.get(cid) or 0.0) * 1e8


_LEADER_MV = {}
_LEADER_MV_READY = False


def _ensure_leader_mv():
    global _LEADER_MV, _LEADER_MV_READY
    if _LEADER_MV_READY:
        return
    _LEADER_MV_READY = True
    for sec in (_load_leader_map_raw().get("sectors") or []):
        for l in (sec.get("core_leaders") or []):
            if l.get("secid") and l.get("mv_yi"):
                _LEADER_MV[l["secid"]] = float(l["mv_yi"])


_LEADER_RAW = None


def _load_leader_map_raw():
    global _LEADER_RAW
    if _LEADER_RAW is None:
        try:
            with open(os.path.join(_REPO_ROOT, "data", "_industry_leader_map.json"),
                      encoding="utf-8") as f:
                _LEADER_RAW = json.load(f) or {}
        except Exception:
            _LEADER_RAW = {}
    return _LEADER_RAW


@register("defensive_dividend")
def _defensive_dividend(ctx, node, inputs):
    """防守高息：仅在 BEARISH/OSCILLATION 启动，找防御板块的低估值稳定标的。

    config：maxPb / minMarketCap / maxDebt / maxCandidates / maxPerSector。
    输出候选代码列表并 stage `defensive_codes`（供 signal_merge 并入主流程）。
    跨端差异（重要）：APK 从 daily_snapshot v12 读 PB/PE/市值/ROE/负债率做硬性过滤；
    PC 缓存无基本面字段 → 本实现在缺字段时**跳过数值硬过滤**、改用
    「防御板块(名称/板块双口径) + 近 20 日稳定性(≥-10%) + 市值代理」近似评分，
    并在 ctx.notes 明确标注降级；缓存补齐基本面后自动走完整口径。
    """
    cfg = node.config or {}
    max_pb = float(cfg.get("maxPb") or 1.5)
    min_cap = float(cfg.get("minMarketCap") or 5e11)
    max_debt = float(cfg.get("maxDebt") or 70)
    max_cand = int(cfg.get("maxCandidates") or 5)
    max_per_sec = int(cfg.get("maxPerSector") or 2)
    if ctx.direction not in ("BEARISH", "OSCILLATION"):
        ctx.notes.append("[%s] 🛡 大盘 %s，非防守模式，跳过" % (node.id, ctx.direction))
        ctx.stage(node.id, [])
        ctx.stage("defensive_codes", [])
        return []
    cache = ctx.cache or {}
    rev = _stock_sector_map()
    pool = ctx.stock_pool or [cid for cid in cache
                              if not cid.startswith(("sh000", "sz399", "sh880", "bj"))]
    degraded = 0
    rows = []
    for cid in pool:
        name = (cache.get(cid) or {}).get("name") or ""
        sectors = rev.get(cid, ())
        kw = next((k for k in _DEFENSIVE_SECTORS
                   if any(k in s for s in sectors) or k in name), None)
        if kw is None:
            continue
        f = _defensive_fundamentals(cid, cache)
        if f is not None:
            if f["pb"] <= 0 or f["pb"] >= max_pb:
                continue
            if f["pe"] <= 0:
                continue
            if f["marketCap"] < min_cap:
                continue
            exempt = any(k in s for s in sectors for k in _HIGH_DEBT_EXEMPT)
            if not exempt and f["debtToAsset"] > max_debt > 0:
                continue
        else:
            degraded += 1
        snaps = (cache.get(cid) or {}).get("snaps") or []
        closes = [s.get("close") or 0.0 for s in snaps]
        if len(closes) >= 10:
            base = closes[-20] if len(closes) >= 20 else closes[0]
            chg20 = ((closes[-1] / base - 1) * 100.0) if base else 0.0
        else:
            chg20 = 0.0
        if chg20 < -10.0:
            continue
        if f is not None:
            pb_score = (max_pb - f["pb"]) / max_pb * 30.0
            roe_score = min(f["roe"], 20.0) / 20.0 * 20.0
            cap = f["marketCap"] or _defensive_cap_proxy(cid)
        else:
            pb_score, roe_score = 15.0, 10.0        # 缺基本面 → 中性
            cap = _defensive_cap_proxy(cid)
        cap_score = min(cap / 2e12, 1.0) * 20.0 if cap else 10.0
        stab_score = max(0.0, min(1.0, 1.0 - abs(chg20) / 10.0)) * 15.0
        rows.append((cid, kw, chg20, pb_score + roe_score + cap_score + 15.0 + stab_score))
    rows.sort(key=lambda r: -r[3])
    picked, per_sec = [], {}
    for cid, kw, _chg, _sc in rows:
        if per_sec.get(kw, 0) >= max_per_sec:
            continue
        per_sec[kw] = per_sec.get(kw, 0) + 1
        picked.append((cid, kw))
        if len(picked) >= max_cand:
            break
    codes = [c for c, _k in picked]
    ctx.stage(node.id, codes)
    ctx.stage("defensive_codes", codes)
    ctx.notes.append("[%s] 🛡 防守高息：%d 只候选（%s）%s"
                     % (node.id, len(codes),
                        ", ".join("%s(%s)" % ((cache.get(c) or {}).get("name", c), k)
                                  for c, k in picked) or "无",
                        "｜⚠️ 缺基本面字段，走降级口径(%d 只)" % degraded if degraded else ""))
    return codes


@register("ancestral_rules")
def _ancestral_rules(ctx, node, inputs):
    """大A祖训：12 条规则对候选加减分（与 APK AncestralRulesNode 同口径）。

    2026-09-10：补齐原空实现（原仅 stage 透传 → exe 侧祖训完全失效、与 APK
    打分漂移）。规则 1~5 为原有祖训；6~12 为 2026-09-10 新增口诀七条。
    2026-09-12：接线修正 —— mid/long/direction 链为 n_strict → **n_base_guard** → n_ancestral，
    优先读 n_base_guard（无该节点的管线自动回落 n_strict）。"""
    scored = (ctx.get("n_base_guard") or ctx.get("n_strict")
              or ctx.get("n_boost") or [])
    hp = str(node.config.get("holdingPeriod") or "SHORT").upper()
    cache = ctx.cache or {}
    out, hit = [], 0
    for cid, sc in scored:
        snaps = (cache.get(cid) or {}).get("snaps") or []
        if len(snaps) < 20:
            out.append((cid, sc))
            continue
        adj, _summary = _ancestral_adjust(snaps, hp)
        if adj:
            hit += 1
            sc = max(0.0, min(100.0, sc + adj))
        out.append((cid, sc))
    out.sort(key=lambda x: -x[1])
    ctx.notes.append("[%s] 📜 大A祖训: %d/%d 只触发" % (node.id, hit, len(scored)))
    ctx.stage("n_ancestral", out)
    return out


@register("kline_idiom")
def _kline_idiom_node(ctx, node, inputs):
    """K 线口诀节点（2026-09-12 新增；《常见K线图.txt》口诀五条 + 经典/大型形态）。

    口径 = `_kline_idiom(snaps)`（与 APK `KlineIdiomNode` 同源），在祖训链之后对候选
    逐只加减分，并可对「离场级」看跌形态直接否决：

      · 看涨：score += bullBoost × 看涨强度（上限 maxBull）
      · 看跌：score -= bearPenalty × 看跌强度（上限 maxBear）
      · 否决：任一看跌形态强度 ≥ vetoStrength 且 veto=true → 剔出候选
        （断头铡刀 4 / 两阴夹一阳·空方炮 4 / 头肩顶 3 视配置）

    config（均可省略，取默认）：bullBoost=0.6 bearPenalty=1.2 veto=true
      vetoStrength=4 maxBull=6 maxBear=6 minBars=30
    上游：n_ancestral → n_strict → n_boost → n_merge；输出 stage 到本节点 id
    （pipeline 里固定为 n_idiom），供 n_event/n_smart/n_ai 继续消费。
    """
    cfg = dict(node.config or {})
    try:
        bull_boost = float(cfg.get("bullBoost", 0.6))
        bear_pen = float(cfg.get("bearPenalty", 1.2))
        max_bull = float(cfg.get("maxBull", 6))
        max_bear = float(cfg.get("maxBear", 6))
        min_bars = int(float(cfg.get("minBars", 30)))
        veto = str(cfg.get("veto", "true")).lower() in ("1", "true", "yes", "on")
        veto_st = int(float(cfg.get("vetoStrength", 4)))
    except (TypeError, ValueError):
        bull_boost, bear_pen, max_bull, max_bear = 0.6, 1.2, 6.0, 6.0
        min_bars, veto, veto_st = 30, True, 4

    scored = (ctx.get("n_ancestral") or ctx.get("n_strict")
              or ctx.get("n_boost") or ctx.get("n_merge") or [])
    cache = ctx.cache or {}
    out, n_bull, n_bear, n_veto = [], 0, 0, 0
    for cid, sc in scored:
        snaps = (cache.get(cid) or {}).get("snaps") or []
        if len(snaps) < min_bars:
            out.append((cid, sc))
            continue
        try:
            r = _kline_idiom(snaps)
        except Exception:  # noqa: BLE001
            out.append((cid, sc))
            continue
        hard = [nm for nm, st in (r.get("bearPairs") or []) if st >= veto_st]
        if veto and hard:
            n_veto += 1
            ctx.notes.append("[%s] 🚫 K线口诀否决 %s: %s" % (node.id, cid, "/".join(hard)))
            continue
        if r["bull"]:
            n_bull += 1
            sc += min(max_bull, bull_boost * r["bullStrength"])
        if r["bear"]:
            n_bear += 1
            sc -= min(max_bear, bear_pen * r["bearStrength"])
        out.append((cid, max(0.0, min(150.0, sc))))

    out.sort(key=lambda x: -x[1])
    ctx.stage(node.id, out)
    ctx.notes.append("[%s] 🕯 K线口诀: 看涨%d 看跌%d 否决%d / %d 只"
                     % (node.id, n_bull, n_bear, n_veto, len(scored)))
    return out


def _ancestral_adjust(snaps, hp):
    """12 条祖训/口诀打分（与 APK AncestralRulesNode.evaluateRules 同口径）。

    snaps 按日期升序，≥20 根。返回 (adjustment:int, summary:str)。"""
    def chg(i):
        if i < 1:
            return 0.0
        base = snaps[i - 1].get("close") or 0
        cur = snaps[i].get("close") or 0
        return (cur / base - 1) * 100 if base else 0.0

    today, prev = snaps[-1], snaps[-2]
    win = snaps[-60:]
    closes = [s.get("close") or 0 for s in win]
    hi60 = max(closes) if closes else 0.0
    lo60 = min(closes) if closes else 0.0
    c = today.get("close") or 0
    pos = (c - lo60) / (hi60 - lo60) if hi60 > lo60 else 0.5
    # 位置口径与 APK PricePositionAnalyzer.fromHighLow 一致（0=最低, 1=最高）
    o_ = today.get("open") or 0
    h_ = today.get("high") or 0
    l_ = today.get("low") or 0
    body = abs(c - o_)
    vols = [float(s.get("volume") or 0) for s in win]
    avg_vol = sum(vols) / len(vols) if vols else 0.0
    vol_ratio = (float(today.get("volume") or 0) / avg_vol) if avg_vol > 0 else 1.0
    today_pct = today.get("changePct")
    if today_pct is None:
        today_pct = chg(len(snaps) - 1)
    turnover = float(today.get("turnoverRate") or 0)

    low_pos = pos < 0.15
    high_pos = pos > 0.95
    tags, adj = [], 0

    # 规则1 高开要跑
    gap = ((o_ / (prev.get("close") or 1)) - 1) if (prev.get("close") or 0) else 0.0
    if gap > 0.02 and c < o_:
        pen = {"ULTRA_SHORT": -15, "SHORT": -10, "MID": -5}.get(hp, -3)
        adj += pen
        tags.append("高开要跑(%.1f%%高开收阴%d)" % (gap * 100, pen))

    # 规则2 买无人问津时
    if low_pos and (0 < turnover < 1.0 or vol_ratio < 0.5):
        bon = {"LONG": 15, "MID": 12, "SHORT": 5}.get(hp, 3)
        adj += bon
        tags.append("买无人问津(低位+低量+%d)" % bon)

    # 规则3 卖人声鼎沸时
    if high_pos and (turnover > 8.0 or (vol_ratio > 2.0 and abs(today_pct) < 1.0)):
        pen = {"ULTRA_SHORT": -12, "SHORT": -15, "MID": -10}.get(hp, -8)
        adj += pen
        tags.append("卖人声鼎沸(高位+放量滞涨%d)" % pen)

    # 规则4 低位利空=利好
    lower_shadow = (o_ - l_) > 2 * body and l_ < o_
    if low_pos and (today_pct < -3.0 or lower_shadow):
        bon = {"LONG": 18, "MID": 15, "SHORT": 8}.get(hp, 5)
        adj += bon
        reason = "大跌%.1f%%" % today_pct if today_pct < -3.0 else "长下影线"
        tags.append("低位利空=利好(%s+%d)" % (reason, bon))

    # 规则5 高位利好=利空
    upper_shadow = (h_ - o_) > 2 * body and h_ > o_
    if high_pos and (today_pct > 3.0 or upper_shadow):
        pen = {"ULTRA_SHORT": -10, "SHORT": -12, "MID": -15}.get(hp, -18)
        adj += pen
        reason = "大涨%.1f%%" % today_pct if today_pct > 3.0 else "长上影线"
        tags.append("高位利好=利空(%s%d)" % (reason, pen))

    # 规则6 买横买坑不买竖
    win20 = win[-20:]
    hi20 = max((s.get("high") or s.get("close") or 0) for s in win20) if win20 else 0.0
    lo20 = min((s.get("low") or s.get("close") or 0) for s in win20) if win20 else 0.0
    amp20 = (hi20 / lo20 - 1) * 100 if lo20 > 0 else 0.0
    base5 = closes[-6] if len(closes) > 5 else c
    gain5 = (c / base5 - 1) * 100 if base5 > 0 else 0.0
    dd60 = (c / hi60 - 1) * 100 if hi60 > 0 else 0.0
    if gain5 >= 15.0:
        pen = {"ULTRA_SHORT": -12, "SHORT": -10, "MID": -8}.get(hp, -5)
        adj += pen
        tags.append("不买竖(5日%.1f%%%d)" % (gain5, pen))
    elif (0.001 <= amp20 <= 8.0 and not high_pos) or (dd60 <= -12.0 and pos < 0.35):
        bon = {"ULTRA_SHORT": 5, "SHORT": 6, "MID": 8}.get(hp, 10)
        adj += bon
        if dd60 <= -12.0 and pos < 0.35:
            tags.append("买坑(回撤%.1f%%+%d)" % (dd60, bon))
        else:
            tags.append("买横(20日振幅%.1f%%+%d)" % (amp20, bon))

    # 规则7 连续小涨是真涨
    c1, c2, c3 = chg(len(snaps) - 1), chg(len(snaps) - 2), chg(len(snaps) - 3)
    cum3 = ((1 + c1 / 100) * (1 + c2 / 100) * (1 + c3 / 100) - 1) * 100
    if all(0.3 <= x <= 3.0 for x in (c1, c2, c3)) and cum3 <= 6.0:
        bon = {"ULTRA_SHORT": 8, "SHORT": 8, "MID": 6}.get(hp, 5)
        adj += bon
        tags.append("连续小涨(3日%.1f%%+%d)" % (cum3, bon))

    # 规则8 连续大涨要离场
    big_up = sum(1 for x in (c1, c2, c3) if x >= 6.0)
    if cum3 >= 12.0 or big_up >= 2:
        pen = {"ULTRA_SHORT": -15, "SHORT": -12, "MID": -10}.get(hp, -8)
        adj += pen
        tags.append("连续大涨(3日%.1f%%%d)" % (cum3, pen))

    # 规则9 大幅冲高易回踩
    rng_today = (h_ / l_ - 1) * 100 if l_ > 0 else 0.0
    if rng_today >= 7.0 and (h_ - max(o_, c)) > 2 * body:
        pen = {"ULTRA_SHORT": -10, "SHORT": -8, "MID": -6}.get(hp, -5)
        adj += pen
        tags.append("冲高易回踩(振幅%.1f%%%d)" % (rng_today, pen))

    # 规则10 急跌无量是洗盘
    if today_pct <= -3.0 and vol_ratio < 0.8:
        bon = {"ULTRA_SHORT": 8, "SHORT": 6, "MID": 4}.get(hp, 3)
        adj += bon
        tags.append("急跌无量=洗盘(%.1f%%缩量+%d)" % (today_pct, bon))

    # 规则11 缓跌放量立马撤
    if (-3.0 <= c1 <= -0.1) and (-3.0 <= c2 <= -0.1) and vol_ratio > 1.5:
        pen = {"ULTRA_SHORT": -12, "SHORT": -12, "MID": -10}.get(hp, -8)
        adj += pen
        tags.append("缓跌放量撤(量比%.2f%d)" % (vol_ratio, pen))

    # 规则12 不挖深坑不大买
    if dd60 <= -20.0:
        bon = {"ULTRA_SHORT": 4, "SHORT": 5, "MID": 6}.get(hp, 8)
        adj += bon
        tags.append("深坑大买(回撤%.1f%%+%d)" % (dd60, bon))

    return adj, ("; ".join(tags) if tags else "无触发")


@register("smart_money_filter")
def _smart_money_filter(ctx, node, inputs):
    """主力资金过滤：无资金数据时取前 minScore 名排序；并施加板块轮动惩罚(v6)。

    惩罚闭环对齐 APK SmartMoneyFilter：读上游 n_rot_pen.sectorPenalties 对所属
    受罚板块的个股降分；防守模式下（defensive_codes 非空）保留防守高息候选。
    """
    scored = (ctx.get("n_event") or ctx.get("n_idiom") or ctx.get("n_ancestral")
              or ctx.get("n_boost") or [])
    scored = sorted(scored, key=lambda x: -x[1])[:50]
    pen = (ctx.get("n_rot_pen") or {}).get("sectorPenalties") or {}
    if pen:
        rev = _stock_sector_map()
        adj, n_pen = [], 0
        for cid, sc in scored:
            d = sum(pen[s] for s in rev.get(cid, ()) if s in pen)
            if d:
                n_pen += 1
                adj.append((cid, max(0.0, min(100.0, float(sc) + max(-100, d)))))
            else:
                adj.append((cid, sc))
        adj.sort(key=lambda x: -x[1])
        scored = adj
        ctx.notes.append("[%s] 🧊 轮动惩罚施加：%d 只降分" % (node.id, n_pen))
    # 防守高息候选兜底保留（APK SmartMoneyFilter 防守模式放宽 minScore）
    have = {c for c, _s in scored}
    kept = [c for c in (ctx.get("defensive_codes") or []) if c not in have]
    if kept:
        scored = sorted(scored + [(c, 25.0) for c in kept], key=lambda x: -x[1])
        ctx.notes.append("[%s] 🛡 防守高息保留 %d 只" % (node.id, len(kept)))
    ctx.stage("n_smart", scored)
    return scored


@register("ai_predict")
def _ai_predict(ctx, node, inputs):
    """AI 精选（回测近似，与 APK AIPredictNode 同语义）：
    仅当过滤后候选 >3 才收敛 top take（真实 APK 在此对 >3 候选调 LLM 精选取 top5）；
    ≤3 只全保留，不调用 AI。"""
    scored = (ctx.get("n_smart") or ctx.get("n_idiom")
              or ctx.get("n_ancestral") or ctx.get("n_boost") or [])
    take = int(node.config.get("take", 5))
    top = scored if len(scored) <= 3 else scored[:take]
    ctx.stage("n_ai", top)
    return top


def _candle_veto_bearish(snaps):
    """近端强看空形态否决（与 APK CandlePatternDetector 同口径，供 generate_orders）。

    形态匹配只做标注、看涨才买入——候选近端(最近≤6 根 K 内收口)命中强看跌反转形态
    (看跌吞没/乌云盖顶/三乌鸦/黄昏之星，对应 APK strength≥3 的 BEARISH 形态)直接剔除。
    规则尽量贴近 APK 实现并宁缺毋滥（宁可放过、不可误杀），返回命中形态名或 None。"""
    try:
        if not snaps or len(snaps) < 30:
            return None
        tail = snaps[-6:]
        n = len(tail)

        def fv(k, i, dflt=0.0):
            try:
                return float(tail[i].get(k, dflt))
            except (TypeError, ValueError):
                return dflt

        def body(i):
            return abs(fv("close", i) - fv("open", i))

        mb = sum(body(i) for i in range(n - 1)) / max(n - 1, 1)
        up = lambda i: fv("close", i) >= fv("open", i)  # noqa: E731
        dn = lambda i: fv("close", i) < fv("open", i)   # noqa: E731

        def engulfs_bearish(i):
            """末根大阴实体完全包裹前一根阳线实体 → 看跌吞没(APK strength=4)。"""
            if i < 1 or not (dn(i) and up(i - 1)):
                return False
            o1, c1 = fv("open", i - 1), fv("close", i - 1)
            o2, c2 = fv("open", i), fv("close", i)
            return (c2 < o1 and o2 >= c1 and (o2 - c2) > 1.2 * max(mb, body(i - 1), 1e-9))

        def dark_cloud(i):
            """前阳线收阴、收盘跌入前阳实体下半且高开 → 乌云盖顶(APK strength=3)。"""
            if i < 1 or not (dn(i) and up(i - 1)):
                return False
            o1, c1 = fv("open", i - 1), fv("close", i - 1)
            o2, c2 = fv("open", i), fv("close", i)
            return (o2 >= c1 and c2 < c1 and c2 > o1
                    and (c1 - c2) >= 0.5 * (c1 - o1) and body(i) > 0.8 * max(mb, 1e-9))

        def three_crows(i):
            """末尾连续 3 根阴线、收盘逐级走低 → 三乌鸦(APK strength=3)。"""
            if i < 2:
                return False
            for j in range(i - 2, i + 1):
                if not dn(j):
                    return False
                if j > i - 2 and fv("close", j) >= fv("close", j - 1):
                    return False
            return True

        def evening_star(i):
            """长阳 → 小实体星 → 长阴(收于首阳实体中下部) → 黄昏之星(APK strength=5)。"""
            if i < 2:
                return False
            if not (up(i - 2) and dn(i) and up(i) is False):
                return False
            o1, c1 = fv("open", i - 2), fv("close", i - 2)
            o2, c2 = fv("open", i - 1), fv("close", i - 1)
            o3, c3 = fv("open", i), fv("close", i)
            star = abs(c2 - o2) <= 0.5 * max(mb, 1e-9)
            mid1 = (o1 + c1) / 2.0
            return (o2 > c1 and star and c3 < mid1 and o3 > o2 and c1 > o1)

        # 只检查近端：从窗口倒数第 1 根(收口处)向前最多 3 根（吞没/乌云=1, 三鸦/星=2）
        for i in range(n - 1, max(n - 3, 0) - 1, -1):
            if engulfs_bearish(i):
                return "看跌吞没"
            if dark_cloud(i):
                return "乌云盖顶"
            if three_crows(i):
                return "三乌鸦"
            if evening_star(i):
                return "黄昏之星"
        return None
    except Exception:  # noqa: BLE001 - 形态否决属增强逻辑，异常时放行不阻塞选股
        return None


def _tail_fv(s, k, dflt=0.0):
    try:
        return float(s.get(k, dflt))
    except (TypeError, ValueError):
        return dflt


def _near_pattern_tail(tail):
    """近端(≤6根)强看涨/看跌经典形态——逐条镜像 APK CandlePatternDetector 对
    candles.takeLast(6) 的判定（复杂度≥30 根的形态在 6 根切片内天然不触发）。

    返回 (bull_name, bull_strength, bear_name, bear_strength)；每方向取 strength≥3
    的最高强度形态：bull=早晨之星5/看涨吞没4/上升三法4/红三兵3/刺透3/锤子线3，
    bear=黄昏之星5/看跌吞没4/下降三法4/三乌鸦3/乌云盖顶3/射击之星3。"""
    n = len(tail)
    if n < 3:
        return None, 0, None, 0

    def fv(i, k):
        return _tail_fv(tail[i], k)

    def body(i):
        return abs(fv(i, "close") - fv(i, "open"))

    mb = sum(body(i) for i in range(n)) / n
    up = lambda i: fv(i, "close") >= fv(i, "open")  # noqa: E731
    dn = lambda i: fv(i, "close") < fv(i, "open")   # noqa: E731

    # ── 看多 ──
    def morning_star():
        if n < 3:
            return False
        o1, c1 = fv(n - 3, "open"), fv(n - 3, "close")
        b2 = abs(fv(n - 2, "close") - fv(n - 2, "open"))
        b3 = fv(n - 1, "close") - fv(n - 1, "open")
        if (o1 - c1) <= 0 or (o1 - c1) < mb * 1.2 or b2 > mb * 0.4:
            return False
        if b3 <= 0 or fv(n - 1, "close") < (o1 + c1) / 2:
            return False
        return fv(n - 2, "low") <= c1

    def rising_three():
        if n < 5:
            return False
        o1, c1 = fv(n - 5, "open"), fv(n - 5, "close")
        b1 = c1 - o1
        b5 = fv(n - 1, "close") - fv(n - 1, "open")
        if b1 <= 0 or b1 < mb * 1.3 or b5 <= 0 or fv(n - 1, "close") <= c1:
            return False
        small = b1 * 0.6
        for off in (n - 4, n - 3, n - 2):
            b = fv(off, "close") - fv(off, "open")
            if b > 0 and abs(b) > small:
                return False
            if fv(off, "close") < o1 or fv(off, "close") > c1:
                return False
        return True

    def bull_engulf():
        if n < 2 or fv(n - 2, "close") >= fv(n - 2, "open"):
            return False
        o2, c2 = fv(n - 1, "open"), fv(n - 1, "close")
        if c2 <= o2 or o2 > fv(n - 2, "open") or c2 < fv(n - 2, "close"):
            return False
        return (c2 - o2) >= mb * 0.8

    def three_white():
        if n < 3:
            return False
        c1, c2, c3 = (fv(n - 3, "close"), fv(n - 2, "close"), fv(n - 1, "close"))
        o1, o2, o3 = (fv(n - 3, "open"), fv(n - 2, "open"), fv(n - 1, "open"))
        for i in (n - 3, n - 2, n - 1):
            if fv(i, "close") <= fv(i, "open") or (fv(i, "close") - fv(i, "open")) < mb * 0.6:
                return False
            if (fv(i, "high") - fv(i, "close")) > (fv(i, "close") - fv(i, "open")) * 0.3:
                return False
        return c2 > c1 and c3 > c2 and o2 >= o1 and o3 >= o2

    def piercing():
        if n < 2 or fv(n - 2, "close") >= fv(n - 2, "open"):
            return False
        o2, c2 = fv(n - 1, "open"), fv(n - 1, "close")
        if c2 <= o2 or o2 > fv(n - 2, "low"):
            return False
        mid1 = (fv(n - 2, "open") + fv(n - 2, "close")) / 2
        return c2 >= mid1 and c2 <= fv(n - 2, "open")

    def hammer():
        if n < 5:
            return False
        b = body(n - 1)
        low_sh = min(fv(n - 1, "open"), fv(n - 1, "close")) - fv(n - 1, "low")
        up_sh = fv(n - 1, "high") - max(fv(n - 1, "open"), fv(n - 1, "close"))
        if b <= 0 or low_sh < b * 2 or up_sh > b * 0.3:
            return False
        return fv(n - 1, "close") < fv(n - 5, "close") * 0.98

    # ── 看空 ──
    def evening_star():
        if n < 3:
            return False
        o1, c1 = fv(n - 3, "open"), fv(n - 3, "close")
        b2 = abs(fv(n - 2, "close") - fv(n - 2, "open"))
        b3 = fv(n - 1, "open") - fv(n - 1, "close")
        if (c1 - o1) <= 0 or (c1 - o1) < mb * 1.2 or b2 > mb * 0.4:
            return False
        if b3 <= 0 or fv(n - 1, "close") > (o1 + c1) / 2:
            return False
        return fv(n - 2, "high") >= c1

    def falling_three():
        if n < 5:
            return False
        o1, c1 = fv(n - 5, "open"), fv(n - 5, "close")
        b1 = o1 - c1
        b5 = fv(n - 1, "open") - fv(n - 1, "close")
        if b1 <= 0 or b1 < mb * 1.3 or b5 <= 0 or fv(n - 1, "close") >= c1:
            return False
        small = b1 * 0.6
        for off in (n - 4, n - 3, n - 2):
            b = fv(off, "open") - fv(off, "close")
            if b > 0 and abs(fv(off, "close") - fv(off, "open")) > small:
                return False
            if fv(off, "close") > o1 or fv(off, "close") < c1:
                return False
        return True

    def bear_engulf():
        if n < 2 or fv(n - 2, "close") <= fv(n - 2, "open"):
            return False
        o2, c2 = fv(n - 1, "open"), fv(n - 1, "close")
        if c2 >= o2 or o2 < fv(n - 2, "open") or c2 > fv(n - 2, "close"):
            return False
        return (o2 - c2) >= mb * 0.8

    def three_crows():
        if n < 3:
            return False
        c1, c2, c3 = (fv(n - 3, "close"), fv(n - 2, "close"), fv(n - 1, "close"))
        o1, o2, o3 = (fv(n - 3, "open"), fv(n - 2, "open"), fv(n - 1, "open"))
        for i in (n - 3, n - 2, n - 1):
            if fv(i, "close") >= fv(i, "open") or (fv(i, "open") - fv(i, "close")) < mb * 0.6:
                return False
            if (fv(i, "low") - fv(i, "close")) > (fv(i, "open") - fv(i, "close")) * 0.3:
                return False
        return c2 < c1 and c3 < c2 and o2 <= o1 and o3 <= o2

    def dark_cloud():
        if n < 2 or fv(n - 2, "close") <= fv(n - 2, "open"):
            return False
        o2, c2 = fv(n - 1, "open"), fv(n - 1, "close")
        if c2 >= o2 or o2 < fv(n - 2, "high"):
            return False
        mid1 = (fv(n - 2, "open") + fv(n - 2, "close")) / 2
        return c2 <= mid1 and c2 >= fv(n - 2, "open")

    def shooting_star():
        if n < 5:
            return False
        b = body(n - 1)
        up_sh = fv(n - 1, "high") - max(fv(n - 1, "open"), fv(n - 1, "close"))
        low_sh = min(fv(n - 1, "open"), fv(n - 1, "close")) - fv(n - 1, "low")
        if b <= 0 or up_sh < b * 2 or low_sh > b * 0.3:
            return False
        return fv(n - 1, "close") > fv(n - 5, "close") * 1.02

    bull = [("早晨之星", 5, morning_star), ("看涨吞没", 4, bull_engulf),
            ("上升三法", 4, rising_three), ("红三兵", 3, three_white),
            ("刺透形态", 3, piercing), ("锤子线", 3, hammer)]
    bear = [("黄昏之星", 5, evening_star), ("看跌吞没", 4, bear_engulf),
            ("下降三法", 4, falling_three), ("三乌鸦", 3, three_crows),
            ("乌云盖顶", 3, dark_cloud), ("射击之星", 3, shooting_star)]
    # 每方向取「命中形态中的最高强度」计分（与 APK detect(takeLast(6)).maxOfOrNull 同口径）
    bull_hit = [st for nm, st, fn in bull if fn()]
    bear_hit = [st for nm, st, fn in bear if fn()]
    bull_st = max(bull_hit) if bull_hit else 0
    bear_st = max(bear_hit) if bear_hit else 0
    bull_name = next((nm for nm, st, fn in bull if fn() and st == bull_st), None)
    bear_name = next((nm for nm, st, fn in bear if fn() and st == bear_st), None)
    return bull_name, bull_st, bear_name, bear_st


# ══════════════════════════════════════════════════════════════════
# K 线口诀识别（2026-09-12 用户新增；《常见K线图.txt》前 25 行）
# ══════════════════════════════════════════════════════════════════

def _idiom_ma(closes, k, i):
    """以 i 为末位的 k 日均线；样本不足返回 None。"""
    if i + 1 < k or i < 0:
        return None
    return sum(closes[i - k + 1:i + 1]) / k


def _kline_idiom(snaps):
    """K 线口诀／经典形态识别（与 APK KlineIdiomNode 同口径）。

    口诀五条（用户原文）：
      ① 三阳不过阴撤退（看跌 3）    ② 三阴不过阳进场（看涨 3）
      ③ 一阳吞三线撤离（高位看跌 2；低位视作反转看涨 2）
      ④ 两阳夹一阴会涨（看涨 4 = 多方炮）  ⑤ 两阴夹一阳离场（看跌 4 = 空方炮）
    经典/大型形态：一阳穿三线(出水芙蓉)、连续下跌T线见、前进红三兵、平底镊子线、
      断头铡刀、倒V型/倒锤线、剧涨并排红、头肩顶、圆弧顶/圆底、底部直角三角形；
      并并入 _near_pattern_tail 的近端 12 条经典形态（避免两份口径打架）。

    snaps 升序、字段 open/high/low/close/volume。返回
      {"bull":[名], "bear":[名], "score":int(正=偏多，负=偏空),
       "bullStrength"/"bearStrength":int, "bullPairs"/"bearPairs":[(名, 强度)],
       "text": "↑名 ↓名"}；样本不足返回空结构。
    """
    empty = {"bull": [], "bear": [], "score": 0, "bullStrength": 0, "bearStrength": 0,
             "bullPairs": [], "bearPairs": [], "text": ""}
    n = len(snaps or [])
    if n < 6:
        return empty
    try:
        o = [float(s.get("open") or 0) for s in snaps]
        c = [float(s.get("close") or 0) for s in snaps]
        hi = [float(s.get("high") or 0) for s in snaps]
        lo = [float(s.get("low") or 0) for s in snaps]
        vol = [float(s.get("volume") or 0) for s in snaps]
    except (TypeError, ValueError):
        return empty
    if not c[-1]:
        return empty

    def ib(i):
        """K 线实体（带符号）：正=阳，负=阴。"""
        return c[i] - o[i]

    def body(i):
        return abs(ib(i))

    recent = range(max(0, n - 10), n)
    mb = sum(body(i) for i in recent) / max(1, len(list(recent))) or c[-1] * 0.005
    win = c[-60:] if n >= 60 else c
    hi60, lo60 = max(win), min(win)
    pos = (c[-1] - lo60) / (hi60 - lo60) if hi60 > lo60 else 0.5
    av = vol[-20:] if len(vol) >= 20 else vol
    avg_vol = sum(av) / len(av) if av else 0.0

    bull, bear = [], []

    # ① 三阳不过阴撤退：连三阳仍未吃掉前一根阴线实体
    if n >= 4 and ib(-4) < 0 and all(ib(j) > 0 for j in (-3, -2, -1)):
        if max(c[-3:]) < o[-4] * 0.998:
            bear.append(("三阳不过阴", 3))

    # ② 三阴不过阳进场：连三阴仍未破前一根阳线实体下沿
    if n >= 4 and ib(-4) > 0 and all(ib(j) < 0 for j in (-3, -2, -1)):
        if min(c[-3:]) > o[-4] * 1.002:
            bull.append(("三阴不过阳", 3))

    # ③ 一阳吞三线：一根放量阳线实体吞没前三根实体
    if n >= 4 and ib(-1) > 0 and body(-1) >= mb * 1.5:
        top3 = max(max(o[j], c[j]) for j in (-4, -3, -2))
        bot3 = min(min(o[j], c[j]) for j in (-4, -3, -2))
        if o[-1] <= bot3 and c[-1] >= top3:
            if pos >= 0.55:
                bear.append(("一阳吞三线(高位诱多)", 2))
            else:
                bull.append(("一阳吞三线(低位反转)", 2))

    # ④ 两阳夹一阴（多方炮）：阳-阴(缩量)-阳且第三根创新高
    if n >= 3 and ib(-3) > 0 and ib(-2) < 0 and ib(-1) > 0:
        if c[-1] > c[-3] and vol[-2] <= vol[-3] and vol[-1] >= vol[-2]:
            bull.append(("两阳夹一阴(多方炮)", 4))

    # ⑤ 两阴夹一阳（空方炮）：阴-阳(缩量)-阴且第三根创新低
    if n >= 3 and ib(-3) < 0 and ib(-2) > 0 and ib(-1) < 0:
        if c[-1] < c[-3] and vol[-2] <= vol[-3] and vol[-1] >= vol[-2]:
            bear.append(("两阴夹一阳(空方炮)", 4))

    # ⑥ 一阳穿三线 / 出水芙蓉：放量阳线上穿 MA5/10/30
    if n >= 31:
        p = (_idiom_ma(c, 5, n - 2), _idiom_ma(c, 10, n - 2), _idiom_ma(c, 30, n - 2))
        q = (_idiom_ma(c, 5, n - 1), _idiom_ma(c, 10, n - 1), _idiom_ma(c, 30, n - 1))
        if None not in p + q and ib(-1) > 0:
            if (c[-2] < min(p) and c[-1] > max(q)
                    and avg_vol > 0 and vol[-1] >= avg_vol * 1.5):
                bull.append(("一阳穿三线(出水芙蓉)", 3))

    # ⑦ 连续下跌 T 线见：连跌≥4 天后长下影小实体（上影极短）
    if n >= 5 and all(c[j] < c[j - 1] for j in range(n - 4, n)):
        low_sh = min(o[-1], c[-1]) - lo[-1]
        up_sh = hi[-1] - max(o[-1], c[-1])
        if body(n - 1) <= mb * 0.8 and low_sh >= max(body(n - 1), c[-1] * 0.01) * 2 \
                and up_sh <= max(body(n - 1), c[-1] * 0.003):
            bull.append(("连续下跌T线见", 2))

    # ⑧ 前进红三兵：低位/盘整后三根连续小阳、逐根收高
    if n >= 22 and all(ib(j) > 0 and body(j) <= mb * 1.2 for j in (-3, -2, -1)):
        if c[-2] > c[-3] and c[-1] > c[-2] and pos <= 0.4:
            bull.append(("前进红三兵", 2))

    # ⑨ 平底镊子线：最近两根最低价几乎相同且落在近 9 日最低区
    if n >= 9 and abs(lo[-1] - lo[-2]) <= max(c[-1] * 0.005, 1e-9):
        if min(lo[-1], lo[-2]) <= min(lo[-9:-2]) * 1.002:
            bull.append(("平底镊子线", 2))

    # ⑩ 断头铡刀：大阴线一刀切断 MA5/10/20（均线取【前一根】位置，避免自证）
    if n >= 21 and ib(-1) < 0 and c[-2] and (c[-1] / c[-2] - 1) <= -0.03:
        m = (_idiom_ma(c, 5, n - 2), _idiom_ma(c, 10, n - 2), _idiom_ma(c, 20, n - 2))
        if None not in m and c[-2] > max(m) and c[-1] < min(m):
            bear.append(("断头铡刀", 4))

    # ⑪ 倒 V 型 / 倒锤线：上涨 8%+ 后的高位长上影
    if n >= 6 and body(n - 1) > 0 and c[-6]:
        if (hi[-1] - max(o[-1], c[-1])) >= body(n - 1) * 2 \
                and (c[-1] / c[-6] - 1) >= 0.08 and pos >= 0.7:
            bear.append(("倒V型/倒锤线", 2))

    # ⑫ 剧涨并排红：短期剧涨后两根同高并排阳（上攻乏力）
    if n >= 4 and c[-4] and (c[-1] / c[-4] - 1) >= 0.15 and ib(-2) > 0 and ib(-1) > 0:
        if abs(c[-1] - c[-2]) <= c[-1] * 0.005 and abs(o[-1] - o[-2]) <= c[-1] * 0.005:
            bear.append(("剧涨并排红", 2))

    # ⑬ 头肩顶：40 根窗口 左肩-头-右肩 + 跌破颈线
    if n >= 40:
        ls, hd, rs = max(c[-40:-25]), max(c[-25:-13]), max(c[-13:])
        if hd > ls and hd > rs and abs(ls - rs) <= max(ls, rs) * 0.04:
            if c[-1] < min(min(c[-40:-25]), min(c[-13:])):
                bear.append(("头肩顶", 3))

    # ⑭ 圆弧顶 / 圆底：30 根三段均线
    if n >= 30:
        a1, a2, a3 = sum(c[-30:-20]) / 10, sum(c[-20:-10]) / 10, sum(c[-10:]) / 10
        if a2 < a1 and a2 < a3:
            bull.append(("圆底/圆弧底", 2))
        elif a2 > a1 and a2 > a3:
            bear.append(("圆弧顶", 3))

    # ⑮ 底部直角三角形：三次探底位置相近 + 放量突破 30 日上边线
    if n >= 31:
        b1, b2, b3 = min(lo[-30:-20]), min(lo[-20:-10]), min(lo[-10:])
        if abs(b1 - b2) <= max(b1, b2) * 0.02 and abs(b2 - b3) <= max(b2, b3) * 0.02:
            if c[-1] > max(hi[-31:-1]) and avg_vol > 0 and vol[-1] >= avg_vol * 1.3:
                bull.append(("底部直角三角形", 2))

    # ⑯ 并入近端 12 条经典形态（早晨之星/三乌鸦/吞没…），强度 ≥3 才计入
    try:
        _bn, _bst, _rn, _rst = _near_pattern_tail(snaps[-6:])
    except Exception:  # noqa: BLE001
        _bn = _rn = None
        _bst = _rst = 0
    if _bn and _bst >= 3:
        bull.append((_bn, _bst))
    if _rn and _rst >= 3:
        bear.append((_rn, _rst))

    bs = sum(st for _, st in bull)
    rs = sum(st for _, st in bear)
    txt = " ".join(["↑" + nm for nm, _ in bull] + ["↓" + nm for nm, _ in bear])
    return {"bull": [nm for nm, _ in bull], "bear": [nm for nm, _ in bear],
            "score": bs - rs, "bullStrength": bs, "bearStrength": rs,
            "bullPairs": bull, "bearPairs": bear, "text": txt}


def _is_stabilize(snaps):
    """止跌企稳（晋控能源型）：前期连跌≥3 天后，最近 3 日 不创新低 且 低点逐日抬高。
    对应「连跌后 3 天不新低 + 最低位连续三天上浮」的企稳形态。"""
    try:
        closes = [float(s["close"]) for s in snaps]
        lows = [float(s["low"]) for s in snaps]
    except (TypeError, ValueError, KeyError):
        return False
    n = len(closes)
    if n < 20:
        return False
    # 基座(最近3日)之前存在 ≥3 连跌（结束于 len-4 .. len-12 之间）
    down3 = any(closes[j] < closes[j - 1] and closes[j - 1] < closes[j - 2]
                for j in range(n - 4, max(n - 12, 2), -1))
    rising = lows[-1] > lows[-2] > lows[-3]
    no_new = min(lows[-3:]) > min(lows[max(n - 13, 0):n - 3])
    return down3 and rising and no_new


def _strong_continuation(snaps, near_high=3.0, vr_min=1.5, rsi_lo=55.0, rsi_hi=70.0):
    """『强势延续』判据（2026-09-10 改进B）：多头排列 + 贴近新高 + 放量 + RSI6 中强区。

    背景：弱市/震荡链只走「均线粘合 + 三日不新低」超跌低吸（防守型），
    强势仍在上行的票（例：超声电子 RSI61/换手20%/量比2.1 放量走高）会被
    形态因子整体挡掉。本判据为其开一条旁路，与 _trend_proto.trend_follow_scan
    同源但更宽松（不要求 MA60、不要求突破大阳）。

    阈值全部来自 XML（n_strict 的 strong* 参数），双端可调。
    返回 (ok, detail)；detail 含 dd/vr/rsi 便于日志与新列标注。
    """
    try:
        cs = [float(s["close"]) for s in snaps if s.get("close") is not None]
        vs = [float(s.get("volume") or 0) for s in snaps]
        hs = [float(s.get("high") or 0) for s in snaps]
    except (TypeError, ValueError, KeyError):
        return False, {}
    n = len(cs)
    if n < 25:
        return False, {}
    ma5 = sum(cs[-5:]) / 5.0
    ma10 = sum(cs[-10:]) / 10.0
    ma20 = sum(cs[-20:]) / 20.0
    bull = ma5 > ma10 > ma20 and cs[-1] > ma5
    hi20 = max(hs[-20:]) if len(hs) >= 20 else max(cs[-20:])
    dd = (hi20 - cs[-1]) / hi20 * 100.0 if hi20 > 0 else 99.0
    v5 = sum(vs[-6:-1]) / 5.0 if len(vs) >= 6 else 0.0
    vr = (vs[-1] / v5) if v5 > 0 else 0.0
    r6 = _etf_rsi(cs, 6)
    rv = r6[-1] if r6 else None
    ok = bool(bull and dd <= near_high and vr >= vr_min
              and rv is not None and rsi_lo <= rv <= rsi_hi)
    return ok, {"dd": dd, "vr": vr, "rsi": rv}


def _trend_match_3way(snaps):
    """个股级三类趋势匹配（2026-09-08，需求：趋势图三类 上涨/中性/下跌 都匹配）。
    与 APK TrendClassGate（QuantTradingPipeline.kt）逐条同口径：
      - 取最近 ≤60 根日K（不足 30 根视为中性）；
      - 打分：多头/空头均线结构、MA20 五日斜率、近5日动量、近端强看涨/看跌形态
        （强度 3-5 直接加入）、止跌企稳(+2)、破位新低(+1)；
      - 判定：下跌≥3 且 > 上涨 → 下跌（买入直接拦截）；
              上涨≥3 且 > 下跌 → 上涨（可进入买入 node 由内部判断是否生成订单）；
              其余 → 中性（继续按原流程执行）。
    返回 dict：label/up/down/bull/bear/stable/detail。"""
    try:
        cs = [float(s["close"]) for s in snaps if s.get("close") is not None]
    except (TypeError, ValueError, KeyError):
        return {"label": "中性", "detail": "数据异常"}
    if len(cs) < 30:
        return {"label": "中性", "detail": "K线不足30根"}
    cs = cs[-60:]
    n = len(cs)

    def avg(xs):
        return sum(xs) / len(xs)

    ma5, ma10, ma20 = avg(cs[-5:]), avg(cs[-10:]), avg(cs[-20:])
    up = down = 0
    if ma5 > ma10 > ma20:
        up += 2
    elif ma5 > ma20 and cs[-1] > ma5:
        up += 1
    if ma5 < ma10 < ma20:
        down += 2
    elif ma5 < ma20 and cs[-1] < ma5:
        down += 1
    if n >= 25:
        ma20_5 = avg(cs[-25:-5])
        if ma20 > ma20_5 * 1.004 and cs[-1] > ma20:
            up += 1
        elif ma20 < ma20_5 * 0.996 and cs[-1] < ma20:
            down += 1
    upd = sum(1 for i in range(max(n - 5, 1), n) if cs[i] > cs[i - 1])
    if upd >= 4:
        up += 2
    elif upd == 3:
        up += 1
    elif upd <= 1:
        down += 2
    win = snaps[-60:]
    bull, bull_st, bear, bear_st = _near_pattern_tail(win[-6:])
    if bull_st >= 3:
        up += bull_st
    if bear_st >= 3:
        down += bear_st
    stable = _is_stabilize(win)
    if stable:
        up += 2
    elif n >= 20 and cs[-1] <= min(cs[-21:-1]):
        down += 1

    detail = "涨%+.1f空%+.1f" % (up, down)
    if bull:
        detail += " 看多[%s]" % bull
    if bear:
        detail += " 看空[%s]" % bear
    if stable:
        detail += " 企稳"
    # v11(2026-09-08): 企稳形态(连跌后3日不新低+低点抬高)不判"下跌"——
    # 刚止跌企稳的票是转折点而非下跌图形，避免 generate_orders 趋势门控误拦低吸机会
    if down >= 3 and down > up and not stable:
        label = "下跌"
    elif up >= 3 and up > down:
        label = "上涨"
    else:
        label = "中性"
    return {"label": label, "up": up, "down": down,
            "bull": bull, "bear": bear, "stable": stable, "detail": detail}


def _idiom_buy_veto(snaps):
    """口诀买前否决（2026-09-10，与 APK GenerateOrdersNode 同口径）。

    命中任一口诀 → 直接剔除不买（返回原因串）：
      · 连续大涨要离场：近3日累计 ≥12% 或含 ≥2 根大阳(≥6%)
      · 买横买坑不买竖：近5日累计 ≥15%（陡直拉升不追高）
      · 大幅冲高易回踩：当日振幅 ≥7% 且留长上影（上影 > 2×实体）
      · 缓跌放量立马撤：连续 2 日小阴 + 量比 >1.5（放量阴跌=出货）
    注：「急跌无量是洗盘」不作否决（反而可低吸），由 n_ancestral 加分体现。
    """
    try:
        if not snaps or len(snaps) < 20:
            return None

        def chg(i):
            if i < 1:
                return 0.0
            base = snaps[i - 1].get("close") or 0
            cur = snaps[i].get("close") or 0
            return (cur / base - 1) * 100 if base else 0.0

        n = len(snaps)
        today = snaps[-1]
        c = today.get("close") or 0
        o_ = today.get("open") or 0
        h_ = today.get("high") or 0
        l_ = today.get("low") or 0
        body = abs(c - o_)
        c1, c2, c3 = chg(n - 1), chg(n - 2), chg(n - 3)
        cum3 = ((1 + c1 / 100) * (1 + c2 / 100) * (1 + c3 / 100) - 1) * 100
        base5 = snaps[n - 6].get("close") or 0
        gain5 = (c / base5 - 1) * 100 if base5 > 0 else 0.0
        vols = [float(x.get("volume") or 0) for x in snaps[-20:]]
        avg_vol = sum(vols) / len(vols) if vols else 0.0
        vr = (float(today.get("volume") or 0) / avg_vol) if avg_vol > 0 else 1.0
        rng = (h_ / l_ - 1) * 100 if l_ > 0 else 0.0

        if cum3 >= 12.0 or sum(1 for x in (c1, c2, c3) if x >= 6.0) >= 2:
            return "连续大涨3日%.1f%%" % cum3
        if gain5 >= 15.0:
            return "不买竖(5日%.1f%%)" % gain5
        if rng >= 7.0 and (h_ - max(o_, c)) > 2 * body:
            return "冲高易回踩(振幅%.1f%%)" % rng
        if (-3.0 <= c1 <= -0.1) and (-3.0 <= c2 <= -0.1) and vr > 1.5:
            return "缓跌放量撤(量比%.2f)" % vr
        return None
    except Exception:  # noqa: BLE001
        return None


@register("generate_orders")
def _generate_orders(ctx, node, inputs):
    """买入订单生成：scored → orders。

    2026-09-08 看空形态否决（与 APK GenerateOrdersNode 同口径，exe/APK 双端一致）：
    形态匹配只做标注；近端(最近≤6根K)命中强看跌形态(看跌吞没/乌云盖顶/三乌鸦/黄昏之星)
    的候选剔除不买——看空还买入属于 Bug。

    2026-09-08 个股级三类趋势匹配门控（需求①，超短/短线/中线/长线统一应用）：
    每只待买候选用自己的近端K线匹配 上涨/中性/下跌 三类趋势——
      匹配到「下跌」→ 直接拦截（不进入买入决策，不生成订单）；
      匹配到「上涨」→ 放行进入买入 node，由买入内部再判断是否生成订单；
      匹配到「中性」→ 继续按原流程执行（含下方看空形态否决等全部原检查）。"""
    scored = ctx.get("n_ai") or []
    max_hold = int(node.config.get("maxHoldings", 5))
    cache = ctx.cache or {}
    orders = []
    for cid, sc in scored:
        snaps = (cache.get(cid) or {}).get("snaps") or []
        name = (cache.get(cid) or {}).get("name") or cid
        tm = _trend_match_3way(snaps)
        if tm["label"] == "下跌":
            ctx.notes.append("[%s] ❌ %s(%s) 趋势匹配=下跌(%s)，直接拦截不生成订单"
                             % (node.id, name, cid, tm["detail"]))
            continue
        pat = _candle_veto_bearish(snaps)
        if pat:
            ctx.notes.append("[%s] ❌ %s(%s) 近端命中看空形态[%s·看空]，否决买入（形态仅标注，看涨才买入）"
                             % (node.id, name, cid, pat))
            continue
        veto = _idiom_buy_veto(snaps)
        if veto:
            ctx.notes.append("[%s] ❌ %s(%s) 口诀买前否决[%s]（连续大涨/陡拉/冲高回踩/缓跌放量）"
                             % (node.id, name, cid, veto))
            continue
        orders.append({"secid": cid, "score": sc, "reason": "pipeline"})
        if len(orders) >= max_hold:
            break
    ctx.orders = orders
    return ctx.orders


@register("swap_weak", "position_merge")
def _swap_and_merge(ctx, node, inputs):
    return ctx.orders


# ──────────────────────────────────────────────────────────────────────────
# ETF 低位低吸（etf_dip usecase · 与 APK EtfDipNodes.kt 同源）
# ──────────────────────────────────────────────────────────────────────────
# 规则/参数全部在 app/src/main/assets/usecases/etf_dip_pipeline.xml（单一事实源），
# 本实现与 APK EtfDipNodes.kt 逐行同构（sma/rsi 对齐 smalltools/_etf_buy.py）。
# 数据 = ctx.cache（smalltools/_etf_cache.json：13 只 ETF + sh000300 前复权日K）。

def _etf_sma(vals, n):
    out, acc, q = [], 0.0, []
    for v in vals:
        acc += v
        q.append(v)
        if len(q) > n:
            acc -= q.pop(0)
        out.append(acc / len(q))
    return out


def _etf_rsi(vals, n=6):
    out = [50.0]
    gains, losses = [], []
    for i in range(1, len(vals)):
        ch = vals[i] - vals[i - 1]
        gains.append(max(ch, 0.0))
        losses.append(max(-ch, 0.0))
        if len(gains) > n:
            gains.pop(0)
            losses.pop(0)
        ag = sum(gains) / len(gains)
        al = sum(losses) / len(losses)
        out.append(100.0 if al == 0 else 100 - 100 / (1 + ag / al))
    return out


# ── 技术假设摘要（2026-09-10 双端同源）────────────────────────────────────
# 与 APK `strategy/analysis/TechTags.kt` 逐行同口径，同时对齐
# smalltools/_technicals.py 的 rich_tag 命名。ETF 行 / 三周期选股结果表共用。
def _etf_ema(vals, n):
    """EMA 序列（种子取首值），与 APK TechTags.emaSeries 同口径。"""
    if not vals:
        return []
    k = 2.0 / (n + 1)
    out = [vals[0]]
    for i in range(1, len(vals)):
        out.append(vals[i] * k + out[-1] * (1 - k))
    return out


def _etf_parabolic_sar(highs, lows, closes):
    """Wilder 抛物线 SAR（step=0.02 max=0.2），与 APK TechTags.parabolicSar 同口径。"""
    n = len(closes)
    sar = [None] * n
    if n < 3:
        return sar
    if closes[1] >= closes[0]:
        trend_up, ep = True, highs[1]
    else:
        trend_up, ep = False, lows[1]
    af = 0.02
    sar[0] = lows[0] if trend_up else highs[0]
    for i in range(1, n):
        base = sar[i - 1] if sar[i - 1] is not None else (lows[i - 1] if trend_up else highs[i - 1])
        s = base + af * (ep - base)
        if trend_up:
            s = min(s, lows[i - 1])
            if i >= 2:
                s = min(s, lows[i - 2])
        else:
            s = max(s, highs[i - 1])
            if i >= 2:
                s = max(s, highs[i - 2])
        if trend_up and lows[i] < s:
            sar[i], trend_up, ep, af = ep, False, lows[i], 0.02
        elif (not trend_up) and highs[i] > s:
            sar[i], trend_up, ep, af = ep, True, highs[i], 0.02
        else:
            sar[i] = s
            if trend_up and highs[i] > ep:
                ep, af = highs[i], min(af + 0.02, 0.2)
            elif (not trend_up) and lows[i] < ep:
                ep, af = lows[i], min(af + 0.02, 0.2)
    return sar


def _etf_sar_text(closes, highs, lows):
    """SAR 摘要：刚翻红 / 红↑N / 刚翻绿N天 / 绿↓N（与 APK TechTags.sarText 同口径）。"""
    n = len(closes)
    if n < 3:
        return ""
    sar = _etf_parabolic_sar(highs, lows, closes)
    i = n - 1
    while i >= 0 and sar[i] is None:
        i -= 1
    if i < 0:
        return ""
    last_up = closes[i] >= sar[i]
    bars, flip = 0, None
    j = i
    while j >= 0 and sar[j] is not None:
        up = closes[j] >= sar[j]
        if up == last_up:
            bars += 1
            j -= 1
        else:
            flip = "UP" if up else "DOWN"
            break
    if last_up and flip == "UP" and 1 <= bars <= 3:
        return "刚翻红"
    if last_up:
        return "红↑%d" % bars
    if flip == "DOWN" and 1 <= bars <= 3:
        return "刚翻绿%d天" % bars
    return "绿↓%d" % bars


def _etf_macd_text(closes):
    """MACD(12,26,9) 摘要：金叉 / 死叉 / 红绿柱收窄-扩大（与 APK TechTags.macdText 同口径）。"""
    if len(closes) < 26:
        return ""
    dif = _etf_ema(closes, 12)
    dea = _etf_ema(dif, 9)
    hist = dif[-1] - dea[-1]
    prev = (dif[-2] - dea[-2]) if len(dif) > 1 else hist
    if prev <= 0 and hist > 0:
        return "金叉"
    if prev >= 0 and hist < 0:
        return "死叉"
    color = "红柱" if hist >= 0 else "绿柱"
    trend = "收窄" if abs(hist) < abs(prev) else "扩大"
    return color + trend


def _etf_obv_text(closes, vols):
    """OBV(20日均线)方向：OBV上行 / OBV下行；数据不足返回 None（与 APK TechTags.obvText 同口径）。"""
    if len(closes) < 21:
        return None
    obv = [0.0]
    for i in range(1, len(closes)):
        if closes[i] > closes[i - 1]:
            obv.append(obv[-1] + vols[i])
        elif closes[i] < closes[i - 1]:
            obv.append(obv[-1] - vols[i])
        else:
            obv.append(obv[-1])
    obv20 = sum(obv[-20:]) / 20.0
    if obv20 == 0:
        return None
    return "OBV上行" if obv[-1] >= obv20 else "OBV下行"


# ══════════════════════════════════════════════════════════════════
# 多理论投票止损（2026-09-12 新增；参考《设置止损线.txt》六理论）
# 与 APK StopLossVoteNode.kt 同口径：六路各自给止损价 → 取最保守（最高）者，
# 再叠加「动量收紧」上浮与「棘轮」只上移不下移。
# ══════════════════════════════════════════════════════════════════

# 周期 → (ATR 倍数, 移动止盈回撤%, 趋势均线, 固定比例止损%, 最高价参考窗口)
_STOP_PROFILE = {
    "ULTRA_SHORT": (1.5, 4.0, 20, 5.0, 20),
    "SHORT": (2.0, 6.0, 20, 8.0, 20),
    "MID": (2.5, 8.0, 60, 10.0, 40),
    "LONG": (3.0, 12.0, 120, 15.0, 60),
}


def _atr(snaps, n=14):
    """Wilder ATR(n)（绝对值，单位同价格）；样本不足返回 None。"""
    if len(snaps) < n + 1:
        return None
    trs = []
    for i in range(1, len(snaps)):
        h = float(snaps[i].get("high") or 0)
        l = float(snaps[i].get("low") or 0)
        pc = float(snaps[i - 1].get("close") or 0)
        if not (h and l and pc):
            continue
        trs.append(max(h - l, abs(h - pc), abs(l - pc)))
    if len(trs) < n:
        return None
    atr = sum(trs[:n]) / n
    for tr in trs[n:]:
        atr = (atr * (n - 1) + tr) / n
    return atr


def _stop_loss_vote(snaps, period="SHORT", entry=0.0, highest=0.0,
                    prev_stop=0.0, market_state="OSCILLATION"):
    """六理论投票 → 最终止损价（取最保守，即六路中最高的那个）。

    ① ATR 吊灯：最高价 − k×ATR(14)（超短 k=1.5 … 长线 k=3.0）
    ② 结构止损：信号 K 线低点（近 10 日最低 low，近似"起涨那根 K 的低点"）
    ③ 趋势止损：跌破周期均线（超短/短 MA20、中 MA60、长 MA120）
    ④ 风险预算：入场价 ×(1 − 固定止损%)；弱市(BEARISH)固定比例 ×0.7 收紧
    ⑤ 移动止盈：最高价 ×(1 − 回撤%)（超短 4% … 长线 12%）
    ⑥ 动量收紧：MACD 死叉 / SAR 刚翻绿或绿↓ / OBV 下行 → 最终止损上浮 1.5%
    棘轮：prev_stop 更高时取 prev_stop（只上移，永不下移）。

    返回 {"stop","stopPct","spacePct","broken","action","theory":{名:价},
          "momentum","votes","surge"}；样本不足返回 stop=0 的骨架。
    """
    out = {"stop": 0.0, "stopPct": 0.0, "spacePct": 0.0, "broken": False,
           "action": "", "theory": {}, "momentum": "", "votes": 0, "surge": 0.0}
    n = len(snaps or [])
    if n < 25:
        return out
    try:
        closes = [float(s.get("close") or 0) for s in snaps]
        highs = [float(s.get("high") or 0) for s in snaps]
        lows = [float(s.get("low") or 0) for s in snaps]
        vols = [float(s.get("volume") or 0) for s in snaps]
    except (TypeError, ValueError):
        return out
    px = closes[-1]
    if not px:
        return out

    key = str(period or "SHORT").upper()
    prof = _STOP_PROFILE.get(key, _STOP_PROFILE["SHORT"])
    k, trail, ma_win, fixed_pct, hi_win = prof
    if str(market_state or "").upper() == "BEARISH":
        fixed_pct *= 0.7                       # 弱市：固定比例止损收紧 30%
    top = max(highest or 0.0, max(highs[-hi_win:]))

    cand = {}
    atr = _atr(snaps, 14)
    if atr:
        cand["①ATR吊灯"] = top - k * atr
    cand["②结构低点"] = min(lows[-10:])
    ma = _idiom_ma(closes, ma_win, n - 1)
    if ma:
        cand["③趋势MA%d" % ma_win] = ma
    cand["④固定比例"] = (entry or px) * (1 - fixed_pct / 100.0)
    cand["⑤移动止盈"] = top * (1 - trail / 100.0)

    macd_t = _etf_macd_text(closes) if len(closes) >= 26 else ""
    sar_t = _etf_sar_text(closes, highs, lows)
    obv_t = _etf_obv_text(closes, vols) or ""
    weak = (macd_t == "死叉") or sar_t.startswith("刚翻绿") \
        or sar_t.startswith("绿↓") or obv_t == "OBV下行"

    stop = max(cand.values())
    if weak:
        stop *= 1.015
    if prev_stop and prev_stop > 0:
        stop = max(stop, prev_stop)

    action = max(cand, key=lambda x: cand[x])
    if px <= stop:
        action = "已破位→清仓"
    out.update({
        "stop": round(stop, 3), "stopPct": round((stop / px - 1) * 100, 2),
        "spacePct": round((px / stop - 1) * 100, 2) if stop > 0 else 0.0,
        "broken": px <= stop, "action": action,
        "theory": {kk: round(vv, 3) for kk, vv in cand.items()},
        "momentum": (("动量转弱⚠️ " if weak else "动量仍强✅ ") +
                     " ".join([x for x in (macd_t, sar_t, obv_t) if x])).strip(),
        "votes": len(cand),
        "surge": round((px / max(stop, 1e-9) - 1) * 100, 2) if stop > 0 else 0.0,
    })
    return out


@register("stop_loss_vote")
def _stop_loss_vote_node(ctx, node, inputs):
    """多理论投票止损节点（实仓 / ETF 两用）。

    输入优先级（同一节点可复用）：
      ① ETF：`n_etf_signal`/`n_etf_exit` 的 {as_of, rows[]}（rows 里带 code）；
      ② 实仓：`ctx.get("n_rh_eval")` 或 `ctx.real_holdings`（[{code, cost/entry, shares}]）；
      ③ 打分链 `n_ai`/`n_idiom`/`n_ancestral`（仅算不落地，用于给候选附止损参考）。
    config：target=auto|etf|holding|picks、period=ULTRA_SHORT|SHORT|MID|LONG（缺省取 ctx.adaptive）、
      marketState=auto、trailUp=true（棘轮）、sourceNode=上游节点 id（target=picks 时可指定）。

    输出：与输入同形的 dict，每行附 stop/stopPct/broken/action/momentum/surge，
    并 stage 到本节点 id（pipeline 固定 n_stopvote / n_etf_stop / n_rh_stop）。
    """
    cfg = dict(node.config or {})
    period = str(cfg.get("period") or (ctx.adaptive or {}).get("period") or "SHORT").upper()
    mkt = str(cfg.get("marketState") or "").upper()
    if not mkt or mkt == "AUTO":
        mkt = str((ctx.adaptive or {}).get("marketState")
                  or (ctx.market or {}).get("state")
                  or (ctx.stage_outputs.get("n_market") or {}).get("state")
                  or ctx.direction or "OSCILLATION").upper()
    ratchet = str(cfg.get("trailUp", "true")).lower() in ("1", "true", "yes", "on")
    cache = ctx.cache or {}

    def _feed(code, entry=0.0, prev=0.0):
        ent = cache.get(code) or {}
        snaps = ent.get("snaps") or []
        hi = float(ent.get("highest") or 0)
        return _stop_loss_vote(snaps, period=period, entry=entry, highest=hi,
                               prev_stop=(prev if ratchet else 0.0), market_state=mkt)

    # ⓪ 个股清单（ETF 全行业扫描 / ETF 持股 top5 的输出行）—— target=picks
    #    优先用行内嵌的 `bars`（上游节点打包的近期K线），否则回退 ctx.cache[code]。
    if str(cfg.get("target") or "").lower() == "picks":
        data = None
        src_id = cfg.get("sourceNode")
        if src_id:
            data = ctx.get(src_id)
        if not isinstance(data, dict):
            data = _upstream_rows(
                inputs, ("n_industry_scan", "n_holdings_top5", "n_holdings_rank"))
        if not isinstance(data, dict):
            for k in ("n_industry_scan", "n_holdings_top5", "n_holdings_rank"):
                if isinstance(ctx.get(k), dict) and (ctx.get(k) or {}).get("rows"):
                    data = ctx.get(k)
                    break
        data = data if isinstance(data, dict) else {}
        rows = []
        priced = 0
        for r in (data.get("rows") or []):
            code = str(r.get("code") or r.get("secid") or "")
            bars = r.get("bars")
            entry = float(r.get("entry") or r.get("cost") or 0)
            prev = float(r.get("stop") or 0)
            if isinstance(bars, list) and len(bars) >= 25:
                v = _stop_loss_vote(bars, period=period, entry=entry, highest=0.0,
                                    prev_stop=(prev if ratchet else 0.0), market_state=mkt)
            else:
                v = _feed(code, entry, prev)
            row = dict(r)
            row.pop("bars", None)
            row.update({"stop": v["stop"], "stopPct": v["stopPct"],
                        "spacePct": v["spacePct"], "broken": v["broken"],
                        "stopAction": v["action"], "momentum": v["momentum"],
                        "surge": v["surge"], "stopTheory": v["theory"]})
            if v["stop"]:
                priced += 1
            rows.append(row)
        out = dict(data)
        out.update({"as_of": data.get("as_of") or ctx.asof, "period": period,
                    "marketState": mkt, "rows": rows, "priced": priced,
                    "n_broken": sum(1 for r in rows if r.get("broken")),
                    "stop_vote": "period=%s k=%s trail=%s%% ma=MA%s market=%s" % (
                        period, _STOP_PROFILE.get(period, _STOP_PROFILE["SHORT"])[0],
                        _STOP_PROFILE.get(period, _STOP_PROFILE["SHORT"])[1],
                        _STOP_PROFILE.get(period, _STOP_PROFILE["SHORT"])[2], mkt)})
        ctx.stage(node.id, out)
        ctx.notes.append("[%s] 🛑 止损投票(个股 %s): %d 只定价 / %d 只 · %d 只已破位"
                         % (node.id, period, priced, len(rows), out["n_broken"]))
        return out

    # ① ETF 行（sourceNode / 上游输入优先，兼容 etf_dip 与 etf_pure_screen 两种上游）
    sig = ctx.get(cfg.get("sourceNode")) if cfg.get("sourceNode") else None
    if not (isinstance(sig, dict) and sig.get("rows")):
        sig = _upstream_rows(inputs, ("n_etf_signal", "n_pure_screen", "n_etf_gate"))
    if not (isinstance(sig, dict) and sig.get("rows")):
        sig = ctx.get("n_etf_signal") or ctx.get("n_etf_exit") or ctx.get("n_etf_gate")
    if isinstance(sig, dict) and sig.get("rows"):
        rows = []
        for r in sig["rows"]:
            code = str(r.get("code") or r.get("secid") or "")
            v = _feed(code, float(r.get("cost") or 0), float(r.get("stop") or 0))
            row = dict(r)
            row.update({"stop": v["stop"], "stopPct": v["stopPct"],
                        "broken": v["broken"], "stopAction": v["action"],
                        "momentum": v["momentum"], "surge": v["surge"]})
            rows.append(row)
        out = dict(sig)
        out["rows"] = rows
        out["as_of"] = sig.get("as_of") or ctx.asof
        out["period"] = period
        out["marketState"] = mkt
        out["stop_vote"] = "period=%s k=%s trail=%s%% ma=MA%s market=%s" % (
            period, _STOP_PROFILE.get(period, _STOP_PROFILE["SHORT"])[0],
            _STOP_PROFILE.get(period, _STOP_PROFILE["SHORT"])[1],
            _STOP_PROFILE.get(period, _STOP_PROFILE["SHORT"])[2], mkt)
        n_stop = sum(1 for r in rows if r.get("stop"))
        n_brk = sum(1 for r in rows if r.get("broken"))
        ctx.stage(node.id, out)
        ctx.notes.append("[%s] 🛑 止损投票(%s): %d 只定价 · %d 只已破位"
                         % (node.id, period, n_stop, n_brk))
        return out

    # ② 实仓持仓
    holds = ctx.get("n_rh_eval")
    if not isinstance(holds, list):
        holds = (getattr(ctx, "real_holdings", None)
                 or (ctx.market or {}).get("holdings") or [])
    if holds and isinstance(holds[0], dict):
        rows = []
        for h in holds:
            code = str(h.get("code") or h.get("secid") or "")
            v = _feed(code, float(h.get("cost") or h.get("entry") or 0),
                      float(h.get("stop") or 0))
            row = dict(h)
            row.update({"stop": v["stop"], "stopPct": v["stopPct"],
                        "broken": v["broken"], "stopAction": v["action"],
                        "momentum": v["momentum"], "surge": v["surge"]})
            rows.append(row)
        out = {"as_of": ctx.asof, "period": period, "marketState": mkt,
               "holdings": rows,
               "n_broken": sum(1 for r in rows if r.get("broken"))}
        ctx.stage(node.id, out)
        ctx.notes.append("[%s] 🛑 止损投票(实仓 %s): %d 只 · %d 只已破位"
                         % (node.id, period, len(rows), out["n_broken"]))
        return out

    # ③ 打分链兜底（只算不落地）
    scored = ctx.get("n_ai") or ctx.get("n_idiom") or ctx.get("n_ancestral") or []
    rows = []
    for cid, _sc in scored[:20]:
        v = _feed(cid)
        if v["stop"]:
            rows.append({"code": cid, "stop": v["stop"], "stopPct": v["stopPct"],
                         "broken": v["broken"], "stopAction": v["action"],
                         "momentum": v["momentum"]})
    out = {"as_of": ctx.asof, "period": period, "marketState": mkt, "holdings": rows}
    ctx.stage(node.id, out)
    ctx.notes.append("[%s] 🛑 止损投票(参考 %s): %d 只" % (node.id, period, len(rows)))
    return out


def _etf_kline_desc(closes, i):
    """跌后K形态摘要（±1.5% 分界：大阳/小阳/小阴/大阴），与 APK TechTags.klineDesc 同口径。"""
    chg = (closes[i] / closes[i - 1] - 1) * 100 if i >= 1 and closes[i - 1] > 0 else 0.0
    if chg >= 1.5:
        size = "大阳"
    elif chg > 0:
        size = "小阳"
    elif chg >= -1.5:
        size = "小阴"
    else:
        size = "大阴"
    down, j = 0, i
    while j > 0 and closes[j] < closes[j - 1]:
        down += 1
        j -= 1
    if down >= 1:
        return "连跌%d·%s" % (down, size)
    down_ex, k = 0, i - 1
    while k > 0 and closes[k] < closes[k - 1]:
        down_ex += 1
        k -= 1
    return "跌%d后%s" % (down_ex, size) if down_ex >= 1 else "今日%s" % size


@register("etf_gate")
def _etf_gate(ctx, node, inputs):
    """阶段1：沪深300 结构多头门控 close>MA20>MA60（大盘不弱才出手）。"""
    cfg = node.config
    idx = cfg.get("indexCode", "sh000300")
    fast = int(cfg.get("maFast", 20))
    slow = int(cfg.get("maSlow", 60))
    mn = int(cfg.get("minSnapshots", 60))
    snaps = (ctx.cache or {}).get(idx, {}).get("snaps", [])
    if len(snaps) < mn:
        avail = bool(ctx.cache)
        note = ("本地无 ETF 行情缓存(etf_cache.json)，请先推送/同步行情" if not avail
                else "%s 数据不足(%d 根 < %d)" % (idx, len(snaps), mn))
        ctx.notes.append("[%s] %s" % (node.id, note))
        return {"ok": False, "available": avail, "date": "", "note": note}
    closes = [s["close"] for s in snaps]
    mf, ms = _etf_sma(closes, fast), _etf_sma(closes, slow)
    # 2026-09-09 修复：门控只看【最新交易日】是否 close>MA20>MA60（与 APK
    # EtfGateNode、_etf_buy.apply_gate 口径一致）。原回溯会显示历史多头日
    # （如 2026-07-01）并在空头行情误报"可低吸"。
    j = len(snaps) - 1
    if j >= slow and closes[j] > mf[j] > ms[j]:
        note = "沪深300 close(%.0f)>MA%d(%.0f)>MA%d(%.0f)" % (closes[j], fast, mf[j], slow, ms[j])
        return {"ok": True, "date": snaps[j]["date"], "note": note, "available": True, "state": "bull"}
    last = snaps[-1]["date"] if snaps else ""
    # 2026-09-09 三态化：非多头 ≠ 一律"空头"（可能均线纠缠），按实际排列给标签（与 APK EtfGateNode 同步）
    if j >= slow:
        if closes[j] < mf[j] < ms[j]:
            note = "沪深300 空头排列 close(%.0f)<MA%d(%.0f)<MA%d(%.0f)" % (closes[j], fast, mf[j], slow, ms[j])
            return {"ok": False, "date": last, "note": note, "available": True, "state": "bear"}
        note = "沪深300 均线纠缠（未呈多头排列）MA%d(%.0f) MA%d(%.0f)" % (fast, mf[j], slow, ms[j])
        return {"ok": False, "date": last, "note": note, "available": True, "state": "mixed"}
    return {"ok": False, "date": last,
            "note": "沪深300 数据不足(<%d 根)，门控无法判定" % slow, "available": True, "state": "mixed"}


def _etf_trend_label(snaps):
    """ETF 趋势图列（2026-09-10）：经典K线形态匹配 → 『↑上涨·早晨之星 / →中性 / ↓下跌·三乌鸦』。

    口径与 DAG 选股表的趋势图列完全一致（_trend_match_3way），形态库＝
    assets/trend_charts/index.html；APK 侧对应 TrendClassGate + CandlePatternDetector。
    """
    try:
        if not snaps or len(snaps) < 30:
            return "—"
        m = _trend_match_3way(snaps)
        lab = m.get("label") or "中性"
        nm = m.get("bull") or m.get("bear") or ""
        arrow = {"上涨": "↑", "下跌": "↓"}.get(lab, "→")
        return "%s%s%s" % (arrow, lab, ("·" + nm) if nm else "")
    except Exception:  # noqa: BLE001
        return "—"


@register("etf_dip_signal")
def _etf_dip_signal(ctx, node, inputs):
    """阶段2：dip_buy v0.3 低吸扫描（参数取自 XML config，与 APK EtfDipSignalNode 同构）。"""
    cfg = node.config

    def f(key, dflt):
        return float(cfg.get(key, dflt))

    dd_lo = f("ddLo", -25.0)
    dd_hi = f("ddHi", -12.0)
    rsi_max = f("rsiMax", 30.0)
    req_above = cfg.get("above250", "true").lower() == "true"
    req_up = cfg.get("upCloseOrRsiTurn", "true").lower() == "true"
    req_notnew = cfg.get("notNew5", "true").lower() == "true"
    dd_win = int(cfg.get("ddWin", 60))
    ma_year = int(cfg.get("maYear", 250))
    min_snaps = int(cfg.get("minSnapshots", 900))
    watch_dd = f("watchDdMax", -8.0)
    watch_rsi = f("watchRsiMax", 45.0)
    cache = ctx.cache or {}
    if not cache:
        ctx.notes.append("[%s] 本地无 ETF 行情缓存" % node.id)
        raise RuntimeError("本地无 ETF 行情缓存(etf_cache.json)，请先推送/同步行情")
    gate_ok = bool((ctx.stage_outputs.get("n_etf_gate") or {}).get("ok"))
    rows, as_of = [], None
    for code, ent in cache.items():
        if code.startswith(("sh000", "sz399")):
            continue  # 指数（sh000300 门控）不入池
        snaps = ent.get("snaps") or []
        if len(snaps) < min_snaps:
            continue
        closes = [s["close"] for s in snaps]
        opens = [s["open"] for s in snaps]
        highs = [float(s.get("high") or s["close"]) for s in snaps]
        lows = [float(s.get("low") or s["close"]) for s in snaps]
        vols = [float(s.get("volume") or 0) for s in snaps]
        dates = [s["date"] for s in snaps]
        i = len(closes) - 1
        if i < max(ma_year, dd_win):
            continue
        c = closes[i]
        ma_y = _etf_sma(closes, ma_year)[i]
        r6 = _etf_rsi(closes, 6)
        r6v = r6[i]
        r6p = r6[i - 1] if i >= 1 else r6v
        run = closes[max(0, i - dd_win + 1):i + 1]
        dd60 = (c / max(run) - 1.0) * 100.0
        above = c > ma_y
        up = c > opens[i]
        turn = r6v > r6p
        not_new5 = c > min(closes[max(0, i - 5):i])
        dip_ok = (dd_lo <= dd60 <= dd_hi and r6v < rsi_max
                  and (above if req_above else True)
                  and ((up or turn) if req_up else True)
                  and (not_new5 if req_notnew else True))
        watch_ok = (not dip_ok) and dd60 <= watch_dd and r6v < watch_rsi and above
        row = {"code": code, "name": ent.get("name", code), "date": dates[i],
               "close": round(c, 3), "dd60": round(dd60, 2), "rsi6": round(r6v, 1),
               "above250": above, "up_close": up, "rsi_turn": turn, "not_new5": not_new5,
               "gate": gate_ok, "dip": dip_ok and gate_ok,
               "dip_ok": dip_ok, "watch_ok": watch_ok,
               # 2026-09-10 技术假设摘要列（与 APK EtfDipNodes · TechTags 同口径）
               "sar": _etf_sar_text(closes, highs, lows),
               "macd": _etf_macd_text(closes),
               "obv": _etf_obv_text(closes, vols) or "OBV走平",
               "kline": _etf_kline_desc(closes, i),
               "trend": _etf_trend_label(snaps)}
        rows.append(row)
        if as_of is None or dates[i] > as_of:
            as_of = dates[i]
    ctx.notes.append("[%s] ETF 扫描完成 %d 只（数据截至 %s）" % (node.id, len(rows), as_of))
    return {"as_of": as_of, "rows": rows}


@register("etf_exit_policy")
def _etf_exit_policy(ctx, node, inputs):
    """阶段3：离场参数 + 发布口径打包（tp/sl/hold/topN 取自 XML）。"""
    import datetime as _dt
    cfg = node.config
    tp = float(cfg.get("tp", 2.0))
    sl = float(cfg.get("sl", -6.0))
    hold = int(cfg.get("hold", 30))
    top_a = int(cfg.get("topApproach", 8))
    top_w = int(cfg.get("topWatch", 12))
    gate = ctx.stage_outputs.get("n_etf_gate") or {}
    sig = ctx.stage_outputs.get("n_etf_signal") or {}
    rows = sig.get("rows") or []
    appr = sorted([r for r in rows if r.get("dip_ok")], key=lambda r: r["dd60"])[:top_a]
    watch = sorted([r for r in rows if r.get("watch_ok")], key=lambda r: r["dd60"])[:top_w]
    default_text = ("v0.3 dip_buy: 距60日高回撤-25%~-12% + RSI6小于30 + 年线上方"
                    " + 收阳/RSI拐头 + 非5日新低 + 沪深300结构多头门控(参数见 etf_dip_pipeline.xml)")
    return {"generated_at": _dt.date.today().isoformat(), "as_of": sig.get("as_of", ""),
            "gate": gate, "signal_today": appr, "approach": watch,
            "strategy": cfg.get("strategyText") or default_text,
            "exit": {"tp": tp, "sl": sl, "hold": hold}}


# ──────────────────────────────────────────────────────────────────────────
# 恐慌日抄底 dip_buy（超短线周期专用，规则参数取自 dip_buy_pipeline.xml，双端同源）
# 统计支撑: AutoQuant/backtest_logs/_dip_rebound_report.md（2015~2026-09-07）
# ──────────────────────────────────────────────────────────────────────────
def _dip_row_at(ctx, snaps):
    """定位 asof（回放）或数据最后一天（实盘）的下标。"""
    if not snaps:
        return -1
    if ctx.asof:
        dts = [s["date"] for s in snaps]
        if ctx.asof in dts:
            return dts.index(ctx.asof)
    return len(snaps) - 1


@register("dip_market_gate")
def _dip_market_gate(ctx, node, inputs):
    """大盘恐慌门控：上证/科创50 连跌≥streakDown 且5日跌≥4% / 5日跌幅≤p5Drop / 当日大跌(dayDrop)且已连跌≥streakDown-1。"""
    cfg = node.config
    idx_codes = [c for c in cfg.get("indexCodes", "sh000001,sh000688").split(",") if c]
    streak_req = int(cfg.get("streakDown", 3))
    p5_drop = float(cfg.get("p5Drop", -6.0))
    day_drop = float(cfg.get("dayDrop", -1.5))
    cache = ctx.cache or {}
    note, asof, streak_max = [], None, 0
    fired = []
    for code in idx_codes:
        snaps = (cache.get(code) or {}).get("snaps") or []
        i = _dip_row_at(ctx, snaps)
        if i < 20:
            continue
        s = snaps[i]
        asof = s.get("date", asof)
        chgs = [x.get("changePct") for x in snaps[i - 9:i + 1]]
        chgs = [c for c in chgs if c is not None]
        streak = 0
        for c in reversed(chgs):
            if c < 0:
                streak += 1
            else:
                break
        streak_max = max(streak_max, streak)
        day = s.get("changePct") or 0.0
        c5 = snaps[i - 5]["close"] if snaps[i - 5].get("close") else 0
        p5 = (s["close"] / c5 - 1) * 100 if c5 else 0.0
        tag = "上证" if code == "sh000001" else "科创50"
        hit = (streak >= streak_req and p5 <= -4.0) or p5 <= p5_drop \
            or (day <= day_drop and streak >= max(1, streak_req - 1))
        note.append("%s连跌%d天/5日%+.1f%%/当日%+.1f%%→%s" % (tag, streak, p5, day, "触发" if hit else "未触发"))
        if hit:
            fired.append(tag)
    ok = len(fired) > 0
    ctx.notes.append("[%s] 大盘恐慌门控: %s" % (node.id, " | ".join(note)))
    return {"ok": ok, "date": asof, "streak": streak_max, "fired": fired, "note": " | ".join(note)}


@register("dip_stock_signal")
def _dip_stock_signal(ctx, node, inputs):
    """热门跌透龙头扫描（恐慌日才执行）：
    前期热门(近60日涨幅前 hotRatio)∩自身连跌≥streakDown∩连跌段跌幅≥-deepDrop∩缩量 → 低吸候选。
    只做热门跌透龙头、不碰冷门（回溯统计：冷门连跌股 H1-H3 胜率仅 43-52%）。"""
    cfg = node.config
    gate = ctx.stage_outputs.get("n_dip_gate") or {}
    if not gate.get("ok"):
        ctx.notes.append("[%s] 大盘无恐慌/深度回调信号(门控关闭)，跳过抄底扫描" % node.id)
        return {"as_of": gate.get("date", ""), "gate": gate, "rows": [], "scanned": 0}
    hot_days = int(cfg.get("ret60Days", 60))
    hot_ratio = float(cfg.get("hotRatio", 0.35))
    streak_req = int(cfg.get("streakDown", 3))
    deep = float(cfg.get("deepDrop", -6.0))          # deepDrop 为负
    vol_shrink = float(cfg.get("volShrink", 0.9))
    min_snaps = int(cfg.get("minSnapshots", 120))
    top_n = int(cfg.get("topN", 6))
    cache = ctx.cache or {}
    asof = None
    scan = []
    for code, ent in cache.items():
        if code.startswith(("sh000", "sz399")):
            continue  # 指数不入池
        snaps = ent.get("snaps") or []
        if len(snaps) < min_snaps:
            continue
        i = _dip_row_at(ctx, snaps)
        if i < hot_days or not snaps[i].get("close"):
            continue
        c0 = snaps[i - hot_days]["close"]
        if not c0:
            continue
        ret60 = (snaps[i]["close"] / c0 - 1) * 100
        scan.append((code, ent, snaps, i, ret60))
        if asof is None or snaps[i]["date"] > asof:
            asof = snaps[i]["date"]
    if not scan:
        ctx.notes.append("[%s] 股票池为空(数据不足)" % node.id)
        return {"as_of": asof, "gate": gate, "rows": [], "scanned": 0}
    scan.sort(key=lambda x: x[4], reverse=True)      # 热门优先
    n_hot = max(1, int(len(scan) * hot_ratio))
    rows = []
    for code, ent, snaps, i, ret60 in scan[:n_hot]:
        chgs = [x.get("changePct") for x in snaps[max(0, i - 14):i + 1]]
        chgs = [c for c in chgs if c is not None]
        streak = 0
        for c in reversed(chgs):
            if c < 0:
                streak += 1
            else:
                break
        if streak < streak_req:
            continue
        ck = snaps[i]["close"]
        ck0 = snaps[i - streak]["close"]
        if not ck or not ck0:
            continue
        drop_pct = (ck0 / ck - 1.0) * 100.0          # 连跌段累计跌幅(正=跌了多少)
        if drop_pct < -deep:                         # 跌得不够深，继续观察
            continue
        # 缩量检查：连跌期均量 vs 更早基线均量（无量能字段时放行）
        def _avg_vol(lo, hi):
            vs = [snaps[j].get("volume") or 0 for j in range(max(0, lo), min(hi, len(snaps)))]
            vs = [v for v in vs if v]
            return (sum(vs) / len(vs)) if vs else None
        vr = None
        base_lo, base_hi = i - streak - 10, i - streak + 1
        v_rec = _avg_vol(i - streak + 1, i + 1)
        v_base = _avg_vol(base_lo, base_hi)
        if v_rec and v_base:
            vr = v_rec / v_base
            if vr > vol_shrink:
                continue
        row = {"code": code, "name": ent.get("name", code),
               "date": snaps[i]["date"], "close": round(ck, 2),
               "ret60": round(ret60, 2), "streak": streak,
               "drop_pct": round(drop_pct, 2), "vr": round(vr, 2) if vr else None}
        rows.append(row)
    rows.sort(key=lambda r: (-r["ret60"], -r["drop_pct"]))
    top = rows[:top_n]
    ctx.notes.append("[%s] 热门池%d只(前%.0f%%%d只) 筛出%d只跌透龙头 → top%d" %
                     (node.id, len(scan), hot_ratio * 100, n_hot, len(rows), len(top)))
    return {"as_of": asof, "gate": gate, "rows": top, "scanned": n_hot}


@register("dip_exit_policy")
def _dip_exit_policy(ctx, node, inputs):
    """抄底离场参数 + 发布口径打包（tp/sl/hold 取自 XML config）。"""
    import datetime as _dt
    cfg = node.config
    sig = ctx.stage_outputs.get("n_dip_signal") or {}
    rows = sig.get("rows") or []
    default_text = ("恐慌日抄底 dip_buy: 上证/科创50 连跌>=3且5日跌>=4%(或5日<=-6%、或当日恐慌大跌) 收盘后"
                    " → 买前期热门(前60日涨幅前35%)∩自身连跌>=3∩缩量的跌透龙头；持<=3日 tp+4%/sl-2.5%")
    return {"generated_at": _dt.date.today().isoformat(),
            "as_of": sig.get("as_of", ""), "gate": sig.get("gate") or {},
            "signal_today": rows, "scanned": sig.get("scanned", 0),
            "strategy": cfg.get("strategyText") or default_text,
            "exit": {"tp": float(cfg.get("tp", 4.0)), "sl": float(cfg.get("sl", -2.5)),
                     "hold": int(cfg.get("hold", 3))}}


# ──────────────────────────────────────────────────────────────────────────
# UseCase 执行器
# ──────────────────────────────────────────────────────────────────────────
def _eval_if(cond, ctx):
    """if 条件：${n_market_direction}.direction == 'BULLISH' 形式。"""
    if not cond:
        return True
    try:
        lhs, rhs = cond.split("==")
    except ValueError:
        return True
    rhs = rhs.strip().strip("'\"")
    lhs = lhs.strip()
    # 兼容 ${node}.field（花括号外带字段）与 ${node.field}（字段在花括号内）两种写法
    if lhs.startswith("${") and "}" in lhs:
        close = lhs.rfind("}")
        var = (lhs[2:close] + lhs[close + 1:]).strip().lstrip(".")
        node_id, _, field = var.partition(".")
        node_id = node_id.strip()
        field = (field or "").strip() or None
        val = ctx.stage_outputs.get(node_id) or ctx.market
        if isinstance(val, dict) and field:
            actual = val.get(field, ctx.direction)
        else:
            actual = ctx.direction
        return str(actual).strip().upper() == rhs.strip().upper()
    return True


class UseCaseRunner:
    def __init__(self, usecase_id, cache=None, asof=None, all_dates=None,
                 date_to_idx=None, period="short", overrides=None):
        """overrides: {module: {param: value}} —— 拟合实验时覆盖 XML node config，
        不改 XML 文件即可调参（None 或空 dict 表示不覆盖）。"""
        self.usecase_id = usecase_id
        self.period = period
        self.overrides = overrides or {}
        self.ctx = Ctx(cache=cache, asof=asof, all_dates=all_dates,
                       date_to_idx=date_to_idx)
        if cache is not None and not all_dates:
            ds = sorted({d for e in cache.values()
                         for s in e.get("snaps", []) for d in [s["date"]]})
            self.ctx.all_dates = ds
            self.ctx.date_to_idx = {d: i for i, d in enumerate(ds)}

    def run(self, usecase=None, with_stages=False):
        u = usecase or load_usecase(self.usecase_id)
        self.ctx.adaptive["period"] = self.period
        for ref, cond, name in u["steps"]:
            if not _eval_if(cond, self.ctx):
                continue
            path = os.path.normpath(os.path.join(USECASES_DIR, "..", ref))
            if not os.path.exists(path):
                path = ref
            pl = parse_pipeline(path)
            # 聚合本 pipeline 的模块参数（strict_selection/signal_merge 覆盖用）
            for n in pl.nodes.values():
                if n.config:
                    self.ctx.node_configs[n.module] = dict(n.config)
            # 拟合覆盖注入（最高优先级）
            if self.overrides:
                for mod, kv in self.overrides.items():
                    self.ctx.node_configs.setdefault(mod, {}).update(kv)
            for nid in pl.order:
                node = pl.nodes[nid]
                # 拟合覆盖注入（最高优先级）：同时写入 node.config，
                # 供只读 `node.config` 的节点（如 stop_loss_vote/inst_pool_build）也生效。
                if self.overrides and node.module in self.overrides:
                    node.config = dict(node.config or {})
                    node.config.update(self.overrides[node.module])
                fn = NODE_IMPLS.get(node.module)
                if fn is None:
                    self.ctx.notes.append("[%s] 未实现 module=%s" % (nid, node.module))
                    continue
                try:
                    out = fn(self.ctx, node, _inputs(self.ctx, node))
                    self.ctx.stage(nid, out)
                except Exception as e:
                    self.ctx.notes.append("[%s] %s 执行失败: %s"
                                          % (nid, node.module, type(e).__name__))
        res = {"orders": self.ctx.orders,
               "market_state": self.ctx.market,
               "direction": self.ctx.direction,
               "candidates": self.ctx.candidates,
               "notes": self.ctx.notes}
        if with_stages:
            res["stages"] = {k: v for k, v in self.ctx.stage_outputs.items() if v is not None}
        return res


# ──────────────────────────────────────────────────────────────────────────
# 热门板块埋伏 sector_ambush（规则参数取自 sector_ambush_pipeline.xml，双端同源）
#
# 设计（参考公开的行业轮动 + 主力吸筹形态研究）：
#   ① 先选板块再选个股：资金从弱板块流向强板块（行业/题材轮动是 A 股最稳定的规律之一）；
#   ② 启动形态：连续 2~3 根阳线（刚启动，不追已连涨多日的高位票）；
#   ③ 趋势确认：close > MA5 > MA10 > MA20 且 MA20 抬升；
#   ④ 主力埋伏三证据：梯量温和放量（既非无量也非天量出货）、OBV 上行、启动前缩量洗盘不破 MA20；
#   ⑤ 位置不高：距 60 日高点回撤 -25% ~ -1%（既不破位也不追高）。
#
# 板块→成分股映射：PC 侧取 AutoQuant/autoquant/hot_sector_config.py 的 HOT_SECTOR_CONFIG；
# APK 侧取 sector_daily_record（板块热度）+ sector_stocks（成分股）——下游规则完全一致。
# ──────────────────────────────────────────────────────────────────────────
def _amb_secid_to_key(secid):
    """'601969.SH' -> 'sh601969'（对齐 ctx.cache 的键格式）。"""
    s = str(secid).strip().upper()
    if "." in s:
        num, suf = s.split(".", 1)
        return {"SH": "sh", "SZ": "sz", "BJ": "bj"}.get(suf, "sz") + num
    return s.lower()


def _load_hot_sector_config():
    """加载 hot_sector_config.HOT_SECTOR_CONFIG（多重回退，找不到返回 None）。"""
    for mod in ("hot_sector_config", "autoquant.hot_sector_config"):
        try:
            m = importlib.import_module(mod)
            cfg = getattr(m, "HOT_SECTOR_CONFIG", None)
            if cfg:
                return cfg
        except Exception:
            pass
    # 兜底：按仓库根定位 AutoQuant/autoquant（_REPO_ROOT 在文件头已自举）
    try:
        cand = os.path.join(_REPO_ROOT, "AutoQuant", "autoquant")
        if os.path.isfile(os.path.join(cand, "hot_sector_config.py")):
            if cand not in sys.path:
                sys.path.insert(0, cand)
            m = importlib.import_module("hot_sector_config")
            return getattr(m, "HOT_SECTOR_CONFIG", None)
    except Exception:
        pass
    return None


def _amb_avg_vol(vols, lo, hi):
    vs = [vols[j] for j in range(max(0, lo), min(hi, len(vols))) if vols[j]]
    return (sum(vs) / len(vs)) if vs else 0.0


def _amb_eval_stock(code, name, sector, snaps, i, p):
    """单只个股埋伏信号评估（与 Kotlin SectorAmbushSignalNode.evalStock 同规则）。"""
    n = i + 1
    if n < p["min_snaps"] + p["ma_l"] + 5:
        return None
    closes = [s.get("close") or 0.0 for s in snaps[:n]]
    vols = [int(s.get("volume") or 0) for s in snaps[:n]]
    chgs = [s.get("changePct") or 0.0 for s in snaps[:n]]

    # ① 连续阳线根数
    bull = 0
    j = i
    while j >= 0 and chgs[j] > 0:
        bull += 1
        j -= 1
    if bull < p["bull_min"] or bull > p["bull_max"]:
        return None

    def _ma(idx, win):
        if idx < win - 1:
            return None
        return sum(closes[idx - win + 1: idx + 1]) / win

    # ② 均线多头排列 + MA20 抬升
    ma5, ma10, ma20 = _ma(i, p["ma_s"]), _ma(i, p["ma_m"]), _ma(i, p["ma_l"])
    ma20p = _ma(i - 5, p["ma_l"])
    if ma5 is None or ma10 is None or ma20 is None or ma20p is None:
        return None
    if not (closes[i] > ma5 > ma10 > ma20):
        return None
    if ma20 <= ma20p:
        return None

    # ③ 位置：距 ddWin 日高点回撤
    hi = max(closes[max(0, i - p["dd_win"] + 1): i + 1])
    if hi <= 0:
        return None
    dd = (closes[i] / hi - 1) * 100
    if dd < p["dd_lo"] or dd > p["dd_hi"]:
        return None

    # ④ 主力埋伏①：梯量/温和放量
    if vols[i] <= 0:
        return None
    bull_lo = i - bull + 1
    v_bull = _amb_avg_vol(vols, bull_lo, i + 1)
    v_base = _amb_avg_vol(vols, max(0, bull_lo - 10), bull_lo)
    if v_bull <= 0 or v_base <= 0:
        return None
    vr = v_bull / v_base
    if vr < p["vol_expand"] or vr > p["vol_expand_max"]:
        return None
    for k in range(bull_lo + 1, i + 1):        # 阶梯式放大（5% 容差）
        if vols[k] < vols[k - 1] * 0.95:
            return None

    # ⑤ 主力埋伏②：OBV 上行
    if i < 5:
        return None
    obv = 0.0
    obv_arr = [0.0] * n
    for k in range(1, n):
        obv += (1.0 if chgs[k] > 0 else (-1.0 if chgs[k] < 0 else 0.0)) * vols[k]
        obv_arr[k] = obv
    obv_up = obv_arr[i] > obv_arr[i - 5]
    if p["require_obv"] and not obv_up:
        return None

    # ⑥ 主力埋伏③：启动前缩量洗盘且不破 MA20
    wash = False
    if p["require_wash"]:
        w_hi, w_lo = bull_lo - 1, max(p["ma_l"], bull_lo - 11)
        if w_hi - w_lo >= 3:
            shrink, hold = False, True
            for k in range(w_lo, w_hi + 1):
                v20 = _amb_avg_vol(vols, max(0, k - 20), k)
                if v20 > 0 and vols[k] < v20 * 0.9:
                    shrink = True
                mk = _ma(k, p["ma_l"])
                if mk and closes[k] < mk * 0.97:
                    hold = False
            wash = shrink and hold
        if not wash:
            return None

    # 评分：连阳根数 + 量能放大 + OBV + 洗盘 + 位置合适度（越接近 -8% 回撤越好）
    pos = max(0.0, 1.0 - abs(dd + 8.0) / 17.0) * 2.0
    score = (bull * 2.0 + vr * 1.5 + (1.5 if obv_up else 0.0)
             + (1.0 if wash else 0.0) + pos)
    return {"code": code, "name": name, "sector": sector,
            "date": snaps[i]["date"], "close": round(closes[i], 2),
            "bull": bull, "vr": round(vr, 2), "dd60": round(dd, 2),
            "obvUp": obv_up, "wash": wash, "score": round(score, 2)}


@register("sector_ambush_signal")
def _sector_ambush_signal(ctx, node, inputs):
    """热门板块「2~3连阳 + 多头趋势 + 主力埋伏」扫描。"""
    cfg = node.config
    p = {
        "bull_min": int(cfg.get("bullMin", 2)), "bull_max": int(cfg.get("bullMax", 3)),
        "ma_s": int(cfg.get("maShort", 5)), "ma_m": int(cfg.get("maMid", 10)),
        "ma_l": int(cfg.get("maLong", 20)),
        "vol_expand": float(cfg.get("volExpand", 1.2)),
        "vol_expand_max": float(cfg.get("volExpandMax", 3.0)),
        "require_obv": str(cfg.get("requireObvUp", "true")).lower() == "true",
        "require_wash": str(cfg.get("requireWash", "true")).lower() == "true",
        "dd_win": int(cfg.get("ddWin", 60)),
        "dd_lo": float(cfg.get("dd60Lo", -25)), "dd_hi": float(cfg.get("dd60Hi", -1)),
        "min_snaps": int(cfg.get("minSnapshots", 60)),
    }
    top_sectors = int(cfg.get("topSectors", 5))
    scan_cap = int(cfg.get("scanCap", 400))
    top_n = int(cfg.get("topN", 10))
    lookback = int(cfg.get("lookbackDays", 20))
    cache = ctx.cache or {}

    hs = _load_hot_sector_config()
    if not hs:
        ctx.notes.append("[%s] ⚠️ 未找到 hot_sector_config.HOT_SECTOR_CONFIG，"
                         "无法定位热门板块，跳过埋伏扫描" % node.id)
        return {"as_of": "", "rows": [], "scanned": 0, "hotSectors": []}

    # ① 板块 → 成分股
    sec_members = {}
    for board, bv in hs.items():
        codes = set()
        for _sub, sv in (bv.get("sub_sectors") or {}).items():
            for secid in (sv.get("leaders") or {}):
                codes.add(_amb_secid_to_key(secid))
        if codes:
            sec_members[board] = sorted(codes)

    def _avg_ret(codes, win):
        rs = []
        for c in codes:
            snaps = (cache.get(c) or {}).get("snaps") or []
            i = _dip_row_at(ctx, snaps)
            if i < win or not snaps[i].get("close"):
                continue
            c0 = snaps[i - win].get("close")
            if c0:
                rs.append((snaps[i]["close"] / c0 - 1) * 100)
        return (sum(rs) / len(rs)) if rs else None

    # ② 板块动量排序（近10日为主，叠加近20/5日）
    ranked = []
    for board, codes in sec_members.items():
        r5, r10, r20 = _avg_ret(codes, 5), _avg_ret(codes, 10), _avg_ret(codes, lookback)
        if r10 is None:
            continue
        ranked.append((board, codes, 0.5 * r10 + 0.3 * (r20 or 0.0) + 0.2 * (r5 or 0.0),
                       r5, r10, r20))
    if not ranked:
        ctx.notes.append("[%s] ⚠️ 板块成分股在缓存中无数据，无法计算板块热度" % node.id)
        return {"as_of": "", "rows": [], "scanned": 0, "hotSectors": []}
    ranked.sort(key=lambda x: -x[2])
    hot = ranked[:top_sectors]
    rank_of = {b: i + 1 for i, (b, _c, _s, _5, _10, _20) in enumerate(hot)}

    # ③ 逐只评估（合并去重，扫描上限 scan_cap）
    merged, sec_of = [], {}
    for board, codes, _s, _5, _10, _20 in hot:
        for c in codes:
            if c not in sec_of:
                sec_of[c] = board
                merged.append(c)
    rows, asof, scanned = [], "", 0
    for code in merged:
        if scanned >= scan_cap:
            break
        if code.startswith(("sh000", "sz399", "sh880", "bj")):
            continue
        ent = cache.get(code) or {}
        snaps = ent.get("snaps") or []
        i = _dip_row_at(ctx, snaps)
        if i < 0:
            continue
        scanned += 1
        row = _amb_eval_stock(code, ent.get("name") or code, sec_of[code], snaps, i, p)
        if not row:
            continue
        row["score"] = round(row["score"] + (top_sectors - rank_of[row["sector"]] + 1), 2)
        rows.append(row)
        if row["date"] > asof:
            asof = row["date"]

    top = sorted(rows, key=lambda r: -r["score"])[:top_n]
    hot_txt = "、".join("%s(近10日%+.1f%%)" % (b, (r10 or 0.0))
                        for b, _c, _s, _r5, r10, _r20 in hot)
    ctx.notes.append("[%s] 热门板块埋伏(近%d日)：%s | 扫描%d只 → 命中%d只"
                     % (node.id, lookback, hot_txt, scanned, len(top)))
    return {"as_of": asof, "rows": top, "scanned": scanned,
            "hotSectors": [b for b, _c, _s, _r5, _r10, _r20 in hot]}


@register("ambush_exit_policy")
def _ambush_exit_policy(ctx, node, inputs):
    """埋伏离场参数 + 发布口径打包（tp/sl/hold 取自 XML config）。"""
    import datetime as _dt
    cfg = node.config
    tp = float(cfg.get("tp", 8.0))
    sl = float(cfg.get("sl", -5.0))
    hold = int(cfg.get("hold", 10))
    sig = ctx.stage_outputs.get("n_ambush_signal") or {}
    rows = sig.get("rows") or []
    default_text = ("热门板块埋伏: 板块热度Top5 → 2~3连阳 + close>MA5>MA10>MA20且MA20抬升 + "
                    "梯量温和放量(1.2~3倍) + OBV上行 + 启动前缩量洗盘不破MA20 + "
                    "距60日高回撤-25%~-1%；持<=" + str(hold) + "日")
    out = {"generated_at": _dt.date.today().isoformat(),
           "as_of": sig.get("as_of", ""),
           "hotSectors": sig.get("hotSectors") or [],
           "signal_today": rows,
           "scanned": sig.get("scanned", 0),
           "strategy": cfg.get("strategyText") or default_text,
           "exit": {"tp": tp, "sl": sl, "hold": hold}}
    ctx.notes.append("[%s] 📦 sector_ambush 打包完成: %d 只 (tp+%.1f%%/sl%.1f%%/持%d日)"
                     % (node.id, len(rows), tp, sl, hold))
    return out


# ══════════════════════════════════════════════════════════════════════════
#  ETF 三个独立 usecase 引擎节点（2026-09-12）
#    ① etf_holdings_rank   ETF 持股 top5（前五重仓覆盖矩阵）
#    ② etf_industry_scan   ETF 全行业扫描（行业ETF→前五重仓→低吸打分 topN）
#    ③ etf_pure_screen     纯 ETF 本体筛选（RAS绿转红/MACD/OBV/RSI6/250日回撤/流动性）
#
#  为何拆三个独立 usecase（见 usecase XML 头注）：
#    · 入口不同：行业板块 / ETF 前五重仓覆盖 / ETF 本身；
#    · 输出表不同：个股低吸清单 / 覆盖矩阵 / ETF 底仓&做T信号；
#    · 生命周期不同：前两者炒个股，第三者炒 ETF 本体（做 T 降本）。
#
#  规则出处：E:\Android\work\dev\选股思路\ETF选股思路.txt
#    §1 初选池：主题赛道 + 流动性(日均额≥5000万) + PE分位20~40%(成长≤50%)
#              + RAS绿转红 + 距250日高回撤 40%~60%
#    §2 日线底仓 base_buy = RAS绿转红 & DIF>DEA & OBV>OBV_MA20 & RSI6∈[30,55]
#              & drawdown∈[0.4,0.6]
#    §3 做T t_buy/t_sell = RSI6≤30/≥70 且 MACD 柱收窄/扩张
#  数据资产：data/_etf_holdings.json（ETF→前五重仓矩阵，APK 内置同名资产）
#           ctx.cache / data/_etf_top5_hist.json（个股日K）
#           ctx.cache ETF 日K + sh000300（纯 ETF 筛选的基准）
# ══════════════════════════════════════════════════════════════════════════

_ETF_HOLDINGS_FILE = os.path.join(_REPO_ROOT, "data", "_etf_holdings.json")
_ETF_TOP5_HIST_FILE = os.path.join(_REPO_ROOT, "data", "_etf_top5_hist.json")
_ETF_HOLD_MEM = {"key": None, "data": None}
_ETF_TOP5_MEM = {"key": None, "data": None}


def _bool_cfg(v, d=True):
    """XML config 布尔解析（缺省 d）。"""
    if v is None:
        return d
    return str(v).strip().lower() in ("1", "true", "yes", "on")


def _load_json_lazy(path, mem):
    """按 mtime 缓存的 JSON 加载（大文件只解析一次）。"""
    try:
        key = os.path.getmtime(path)
    except OSError:
        return None
    if mem.get("key") == key and mem.get("data") is not None:
        return mem["data"]
    try:
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
    except (OSError, ValueError):
        return None
    mem["key"], mem["data"] = key, d
    return d


def _etf_holdings_data():
    """ETF→前五重仓矩阵（data/_etf_holdings.json）。缺失返回 None。"""
    return _load_json_lazy(_ETF_HOLDINGS_FILE, _ETF_HOLD_MEM)


def _etf_top5_hist():
    """行业ETF前五重仓个股的全史日K（data/_etf_top5_hist.json），仅作行情兜底。"""
    return _load_json_lazy(_ETF_TOP5_HIST_FILE, _ETF_TOP5_MEM)


def _upstream_rows(inputs, preferred=()):
    """从上游输入中取第一个带 `rows` 的输出。

    注意 `_inputs()` 返回的是 ctx.stage_outputs 的**整个 dict**（{node_id: out}），
    不是列表 —— 这里兼容 dict / list 两种形态，并按 preferred 节点 id 优先。
    """
    cands = []
    if isinstance(inputs, dict):
        for k in preferred:
            if k in inputs:
                cands.append(inputs[k])
        cands.extend(v for k, v in inputs.items() if k not in preferred)
    elif isinstance(inputs, (list, tuple)):
        cands = list(inputs)
    for c in cands:
        if isinstance(c, dict) and c.get("rows"):
            return c
    return None


def _etf_hist_snaps(code6):
    """从 data/_etf_top5_hist.json 取某只个股的全史日K（兜底行情源）。"""
    h = _etf_top5_hist() or {}
    codes = h.get("codes") or {}
    v = codes.get(code6) or codes.get(_etf_prefixed(code6))
    return ((v.get("snaps") if isinstance(v, dict) else v) or [])


def _etf_snaps_for(cache, code6, min_bars=25):
    """个股行情：ctx.cache 优先 → _etf_top5_hist.json 兜底；不足 min_bars 返回 []。"""
    ent = (cache or {}).get(_etf_prefixed(code6)) or (cache or {}).get(code6) or {}
    snaps = ent.get("snaps") or []
    if len(snaps) < min_bars:
        hs = _etf_hist_snaps(code6)
        if len(hs) > len(snaps):
            snaps = hs
    return snaps if len(snaps) >= min_bars else []


def _etf_code6(code):
    c = str(code or "").strip().lower()
    return c[2:] if c[:2] in ("sh", "sz", "bj") else c


def _etf_prefixed(code):
    c = _etf_code6(code)
    if c[:1] in ("6", "9"):
        return "sh" + c
    if c[:1] in ("0", "3"):
        return "sz" + c
    return c


def _bars_pack(snaps, limit=140):
    """snaps → 精简 K 线（供下游 stop_loss_vote target=picks 内嵌，免二次取数）。"""
    out = []
    for s in (snaps or [])[-limit:]:
        out.append({"date": s.get("date", ""),
                    "open": float(s.get("open") or 0), "high": float(s.get("high") or 0),
                    "low": float(s.get("low") or 0), "close": float(s.get("close") or 0),
                    "volume": float(s.get("volume") or 0)})
    return out


def _lsq_slope(vals):
    """最小二乘斜率（x=0..n-1）——与 np.polyfit(deg=1) 同解、与 APK 同口径。"""
    n = len(vals)
    if n < 2:
        return 0.0
    xm = (n - 1) / 2.0
    ym = sum(vals) / n
    num = sum((i - xm) * (vals[i] - ym) for i in range(n))
    den = sum((i - xm) ** 2 for i in range(n))
    return (num / den) if den else 0.0


def _ras_slope_series(etf_closes, bench_closes, win=20):
    """RAS 相对强度(ETF/沪深300) 的 win 日滚动斜率序列；样本不足返回 []。"""
    m = min(len(etf_closes or []), len(bench_closes or []))
    if m < win + 1:
        return []
    ec, bc = etf_closes[-m:], bench_closes[-m:]
    rs = [(ec[i] / bc[i] if bc[i] else 0.0) for i in range(m)]
    return [_lsq_slope(rs[i - win + 1:i + 1]) for i in range(win - 1, m)]


def _etf_market_state(cache, index_code="sh000300"):
    """大盘三态（与 ctx.market / n_market 同口径）：沪深300 close 与 MA20/MA60 关系。

    BULLISH:  close > MA20 > MA60（结构多头）
    BEARISH:  close < MA20 < MA60（结构空头）
    其余     : OSCILLATION
    """
    snaps = ((cache or {}).get(index_code) or {}).get("snaps") or []
    closes = [float(x.get("close") or 0) for x in snaps]
    if len(closes) < 60:
        return "OSCILLATION"
    c = closes[-1]
    ma20 = sum(closes[-20:]) / 20.0
    ma60 = sum(closes[-60:]) / 60.0
    if c > ma20 > ma60:
        return "BULLISH"
    if c < ma20 < ma60:
        return "BEARISH"
    return "OSCILLATION"


def _sar_state(closes, highs, lows):
    """SAR 状态 → (dir, bars, fresh_up)。

    fresh_up = 当前红(dir=UP) 且前一段为绿 且翻转≤3日（与 PC `_technicals.flip_dir/flip_ago` 同语义）。
    """
    n = len(closes)
    if n < 3:
        return "", 0, False
    sar = _etf_parabolic_sar(highs, lows, closes)
    i = n - 1
    while i >= 0 and sar[i] is None:
        i -= 1
    if i < 0:
        return "", 0, False
    dirs = []
    j = i
    while j >= 0 and sar[j] is not None:
        dirs.append("UP" if closes[j] >= sar[j] else "DOWN")
        j -= 1
    cur = dirs[0]
    bars = 0
    for d in dirs:
        if d == cur:
            bars += 1
        else:
            break
    fresh_up = bool(cur == "UP" and len(dirs) > bars
                    and dirs[bars] == "DOWN" and bars <= 3)
    return cur, bars, fresh_up


def _etf_pick_score(closes, opens, highs, lows, vols, pos60):
    """低吸打分（自包含，双端同口径）。返回 None=样本不足；否则 dict。

    硬门控：SAR 绿（下跌趋势）直接排除（对齐 smalltools/_etf_holdings._decide_pick）。
    权重与 APK `EtfIndustryScanNode` 逐项一致，任何调整请同步改 pipeline XML 注释。
    """
    n = len(closes)
    if n < 60:
        return None
    c, o = closes[-1], opens[-1]
    d, sar_bars, fresh = _sar_state(closes, highs, lows)
    if d != "UP":
        return None
    macd_t = _etf_macd_text(closes)
    obv_t = _etf_obv_text(closes, vols) or ""
    rsi6 = _etf_rsi(closes, 6)[-1]
    ma5 = sum(closes[-5:]) / 5.0
    ma10 = sum(closes[-10:]) / 10.0
    three_no_low = all(lows[-3 + i] >= lows[-4] for i in range(3))
    up_day = c > o
    v5 = (sum(vols[-6:-1]) / 5.0) if n >= 6 else 0.0
    vr = (vols[-1] / v5) if v5 > 0 else 1.0
    # 放量企稳（双端可复现口径）：收阳 + 量比≥1.05 + 站上 MA5 + 三日不新低
    stable = bool(up_day and vr >= 1.05 and c > ma5 and three_no_low)
    s = 0.0
    if stable:
        s += 3.0
    if fresh:
        s += 2.0
    if macd_t == "金叉":
        s += 2.2
    elif macd_t.startswith("红柱"):
        s += 1.0
    if obv_t == "OBV上行":
        s += 1.5
    if 20 <= rsi6 <= 55:
        s += 0.8
    if pos60 is not None:
        s += (2.0 if pos60 <= -20 else
              (1.5 if pos60 <= -12 else (1.0 if pos60 <= -6 else 0.3)))
    if c > ma5:
        s += 0.8
    if c > ma10:
        s += 0.4
    if three_no_low:
        s += 1.2
    if up_day:
        s += 0.6
    if up_day and 1.0 <= vr <= 3.5:
        s += 1.0
    core = bool(stable or fresh or macd_t == "金叉")
    return {"score": round(s, 2), "core": core,
            "sar": ("SAR红↑%d" % sar_bars) if not fresh else "SAR刚翻红",
            "sarDir": d, "sarBars": sar_bars, "freshUp": fresh,
            "macd": macd_t, "obv": obv_t, "rsi6": rsi6, "pos60": pos60,
            "above5": bool(c > ma5), "above10": bool(c > ma10),
            "threeNoLow": three_no_low, "upDay": up_day,
            "volRatio": round(vr, 2), "stable": stable}


# ══════════════════════════════════════════════════════════════════════════
# 机构持续加仓（2026-09-12 新增；规则出处《机构持续加仓自动选股池.txt》）
#   数据资产：data/_inst_holdings.json（每周由 smalltools/_inst_holdings.py --update-pool 刷新）
#             data/_inst_pool.json（推送时由 smalltools/_publish_candidates.py 写出的选股池清单）
#   链：inst_pool_build（汇总选股池+实仓）→ inst_holding_judge（A/B/C）→ stop_loss_vote
#   ★ 判定算法单一事实源 = smalltools/_inst_holdings.py；资产里已含 grade，双端只读不算，
#     避免 Python 节点 / Kotlin 节点 / 推送表三处口径漂移。机构季报滞后 1-3 月，非实时信号。
# ══════════════════════════════════════════════════════════════════════════

_INST_HOLDINGS_FILE = os.path.join(_REPO_ROOT, "data", "_inst_holdings.json")
_INST_POOL_FILE = os.path.join(_REPO_ROOT, "data", "_inst_pool.json")
_INST_HOLD_MEM = {"key": None, "data": None}
_INST_POOL_MEM = {"key": None, "data": None}
_INST_POOL_SRC = (("n_industry_scan", "ETF全行业扫描"), ("n_holdings_top5", "ETFtop5"),
                  ("n_holdings_rank", "ETFtop5"), ("n_etf_signal", "ETF信号"),
                  ("n_etf_gate", "ETF信号"), ("n_ai", "三周期选股"), ("n_strict", "三周期选股"))
_INST_GRADE_LABEL = {"A": "A·机构加仓", "B": "B·机构参与", "C": "C·散户票"}
_INST_GRADE_VERDICT = {"A": "机构真加仓·强趋势", "B": "机构温和参与·观察", "C": "机构撤退/散户票"}


def _inst_holdings_data():
    """机构持仓判定快照（data/_inst_holdings.json）。缺失返回 None。"""
    return _load_json_lazy(_INST_HOLDINGS_FILE, _INST_HOLD_MEM)


def _inst_pool_data():
    """落盘选股池（data/_inst_pool.json，推送时写出）。缺失返回 None。"""
    return _load_json_lazy(_INST_POOL_FILE, _INST_POOL_MEM)


def _inst_bars(code, cache, limit=140, min_bars=25):
    """取该股近期日K（ctx.cache 优先，ETF 行情兜底），不足 min_bars 返回 []。"""
    c6 = _etf_code6(code)
    ent = None
    for k in (_etf_prefixed(c6), c6):
        e = (cache or {}).get(k)
        if e and (e.get("snaps") or []):
            ent = e
            break
    snaps = list((ent or {}).get("snaps") or [])
    if len(snaps) < min_bars:
        try:
            hs = _etf_hist_snaps(c6)
        except Exception:  # noqa: BLE001
            hs = []
        if len(hs) > len(snaps):
            snaps = hs
    return snaps[-limit:] if len(snaps) >= min_bars else []


def _inst_pool_rows(ctx, cfg):
    """汇总「三大周期选股 + ETF全行业扫描 + ETF top5 + 实仓」全部股票（6 位码去重，from 合并）。

    来源优先级（可多来源叠加到同一行的 from）：
      ① data/_inst_pool.json —— 推送时由 _publish_candidates 写出的当日真实选股池（最完整）；
      ② 本次会话已 stage 的各链输出（ETF 扫描 / top5 / ETF 信号 / 三周期打分链）；
      ③ real_holdings（实仓）。
    """
    rows, idx = [], {}

    def _add(code, name="", src="", entry=0.0):
        c6 = _etf_code6(code)
        if len(c6) != 6 or not c6.isdigit():
            return
        r = idx.get(c6)
        if r is None:
            r = {"code": c6, "name": name or "", "from": src or "",
                 "entry": float(entry or 0)}
            idx[c6] = r
            rows.append(r)
            return
        if src and src not in r["from"]:
            r["from"] = (r["from"] + "+" + src) if r["from"] else src
        if name and not r["name"]:
            r["name"] = name
        if entry and not r["entry"]:
            r["entry"] = float(entry or 0)

    p = _inst_pool_data() or {}
    for r in (p.get("rows") or []):
        _add(r.get("code"), r.get("name"), r.get("from") or "选股池", r.get("entry") or 0)
    # ①b PC 离线兜底（无推送落盘时，直接读 exe 侧当日产出的选股文件）——
    #     AutoQuant/data/dag_screen_latest.json（三周期 DAG 选股）+
    #     data/_etf_ds_picks.json（ETF top5 选股）。文件不存在则静默跳过。
    try:
        _ds = _load_json_lazy(os.path.join(_REPO_ROOT, "AutoQuant", "data",
                                           "dag_screen_latest.json"),
                              _INST_POOL_MEM.setdefault("_ds", {"key": None, "data": None}))
        for label, items in ((_ds or {}).get("result") or {}).items():
            for it in (items or []):
                if isinstance(it, dict):
                    _add(it.get("code"), it.get("name"),
                         "三周期选股" if label not in ("抄底",) else "抄底", 0)
    except Exception:  # noqa: BLE001
        pass
    try:
        _ds = _load_json_lazy(os.path.join(_REPO_ROOT, "data", "_etf_ds_picks.json"),
                              _INST_POOL_MEM.setdefault("_ds5", {"key": None, "data": None}))
        for it in ((_ds or {}).get("picks") or []):
            if isinstance(it, dict):
                _add(it.get("code"), it.get("name"), "ETFtop5", 0)
    except Exception:  # noqa: BLE001
        pass
    for nid, label in _INST_POOL_SRC:
        o = (ctx.stage_outputs or {}).get(nid)
        for r in ((o or {}).get("rows") or []):
            _add(r.get("code") or r.get("secid"), r.get("name"), label,
                 r.get("entry") or r.get("cost") or r.get("close") or 0)
    for k in ("n_ai", "n_strict"):
        v = ctx.get(k)
        if isinstance(v, list) and v and isinstance(v[0], (list, tuple)):
            for t in v:
                _add(t[0], "", "三周期选股")
    holds = ctx.get("n_rh_eval")
    if not isinstance(holds, list) or not (holds and isinstance(holds[0], dict)):
        holds = (getattr(ctx, "real_holdings", None)
                 or (getattr(ctx, "market", None) or {}).get("holdings") or [])
    for h in (holds or []):
        if isinstance(h, dict):
            _add(h.get("code") or h.get("secid"), h.get("name"), "实仓",
                 h.get("cost") or h.get("entry") or 0)
    return rows[:int(cfg.get("maxRows", 200) or 200)]


@register("inst_pool_build")
def _inst_pool_build_node(ctx, node, inputs):
    """选股池 + 实仓汇总（机构判定链入口）。

    汇总口径见 `_inst_pool_rows`：data/_inst_pool.json（推送落盘）→ 本次会话各链 stage 输出
    → real_holdings。按 6 位代码去重，`from` 记录命中来源（多来源用 + 连接）。
    config：includeHoldings=true、maxRows=200、embedBars=true、barsLimit=140。
    输出：{as_of, n, rows:[{code,name,from,entry,bars?}], source}
    """
    cfg = dict(node.config or {})
    rows = _inst_pool_rows(ctx, cfg)
    if not _bool_cfg(cfg.get("includeHoldings"), True):
        rows = [r for r in rows if "实仓" not in (r.get("from") or "")]
    if _bool_cfg(cfg.get("embedBars"), True):
        limit = int(cfg.get("barsLimit", 140) or 140)
        for r in rows:
            bars = _inst_bars(r["code"], ctx.cache, limit)
            if bars:
                r["bars"] = bars
                r["close"] = round(float(bars[-1].get("close") or 0), 3)
                r["date"] = bars[-1].get("date", "")
    n_bar = sum(1 for r in rows if r.get("bars"))
    out = {"as_of": ctx.asof, "n": len(rows), "rows": rows,
           "source": "选股池(三周期+ETF全行业扫描+ETFtop5)+实仓",
           "n_bars": n_bar}
    ctx.stage(node.id, out)
    srcs = sorted({s for r in rows for s in (r.get("from") or "").split("+") if s})
    ctx.notes.append("[%s] 🏛️ 机构判定池: %d 只（内嵌日K %d 只）｜来源: %s"
                     % (node.id, len(rows), n_bar, "/".join(srcs[:6])))
    return out


@register("inst_holding_judge")
def _inst_holding_judge_node(ctx, node, inputs):
    """机构持仓判定（A 精选 / B 观察 / C 散户票）。

    数据源：data/_inst_holdings.json（东财 RPT_MAIN_ORGHOLD 机构持股一览表 +
      RPT_HOLDERNUMLATEST 股东户数；每周 smalltools/_inst_holdings.py --update-pool 刷新）。
    判定口径（与 smalltools/_inst_holdings.judge_series 完全一致，资产内已含 grade）：
      口径A 连续 ≥2 期持股比例环比增持 >0.5pp；口径B 全程净增持 >1pp；
      score = 0.6×机构加仓分 + 0.4×集中度（股东户数下降加分）；
      A=口径A 且 ≥75 分｜B=(A或B) 且 ≥50 分｜C=其余（散户票）。
    config：sourceNode=n_inst_pool、gradeFilter=""（"A"/"AB" 可只看精选）、maxRows=200。
    输出：{as_of, n, a, b, c, period, available, rows:[…+grade/score/label/hold_ratio/
      latest_chg/streak/net_chg/n_funds/holder_chg_pct/instVerdict]}（按 A→B→C、分数降序）
    """
    cfg = dict(node.config or {})
    src = ctx.get(cfg.get("sourceNode") or "n_inst_pool")
    if not isinstance(src, dict) or not src.get("rows"):
        src = _upstream_rows(inputs, ("n_inst_pool",)) or src
    src = src if isinstance(src, dict) else {"rows": []}
    d = _inst_holdings_data()
    if not d or not d.get("stocks"):
        ctx.notes.append("[%s] ✗ 缺 data/_inst_holdings.json（机构持仓数据资产）" % node.id)
        out = dict(src)
        out.update({"available": False, "rows": src.get("rows") or [],
                    "note": "缺 data/_inst_holdings.json；先跑 smalltools/_inst_holdings.py"})
        ctx.stage(node.id, out)
        return out
    stocks = d.get("stocks") or {}
    f = str(cfg.get("gradeFilter") or "").strip().upper()
    rows, cnt = [], {"A": 0, "B": 0, "C": 0}
    for r in (src.get("rows") or []):
        row = dict(r)
        v = stocks.get(row.get("code")) or {}
        grade = (v.get("grade") or "") if v else ""
        if not grade:
            row.update({"grade": "", "label": "—", "instVerdict": "无季报数据"})
        else:
            cnt[grade] = cnt.get(grade, 0) + 1
            row.update({"grade": grade, "score": v.get("score"),
                        "hold_ratio": v.get("hold_ratio"), "latest_chg": v.get("latest_chg"),
                        "streak": v.get("streak"), "net_chg": v.get("net_chg"),
                        "n_funds": v.get("n_funds"),
                        "holder_chg_pct": v.get("holder_chg_pct"),
                        "label": _INST_GRADE_LABEL.get(grade, "—"),
                        "instVerdict": _INST_GRADE_VERDICT.get(grade, "—")})
        rows.append(row)
    if f:
        rows = [r for r in rows if (r.get("grade") or "") in list(f)]
    rows.sort(key=lambda x: (0 if x.get("grade") == "A" else
                             (1 if x.get("grade") == "B" else 2), -(x.get("score") or 0)))
    rows = rows[:int(cfg.get("maxRows", 200) or 200)]
    out = dict(src)
    out.update({"as_of": src.get("as_of") or ctx.asof, "available": True,
                "period": d.get("period"), "prev_period": d.get("prev_period"),
                "rule": d.get("rule"), "n": len(rows),
                "a": cnt.get("A", 0), "b": cnt.get("B", 0), "c": cnt.get("C", 0),
                "rows": rows})
    ctx.stage(node.id, out)
    ctx.notes.append("[%s] 🏛️ 机构判定(%s): %d 只 → A %d / B %d / C %d"
                     % (node.id, d.get("period"), len(rows),
                        cnt.get("A", 0), cnt.get("B", 0), cnt.get("C", 0)))
    return out


# ══════════════════════════════════════════════════════════════════════════
# 国家队 / 大基金持有进出（national_flow，2026-09-13 新增；同日 v2 扩直接持股通道）
#   规则出处：E:\Android\work\dev\选股思路\national_flow_research.md
#             E:\Android\work\dev\选股思路\institutional_breakout_guide.md（§8 FundFlowAnalyzer）
#   数据资产：data/_national_flow_hist.json（smalltools/_national_flow.py --build）
#   三条通道 —— ①free 十大流通股东（季报）②lock 十大股东锁定部分（限售=定增锁定，季报）
#             ③ann 股东增减持+定增获配公告（T+1）
#   ★ 判定算法单一事实源 = smalltools/_national_flow.py；资产内 snap 已含判定结果，
#     Python 节点 / Kotlin 节点 / 推送表三处只读不算，避免口径漂移。
# ══════════════════════════════════════════════════════════════════════════

_NF_FILE = os.path.join(_REPO_ROOT, "data", "_national_flow_hist.json")
_NF_MEM = {"key": None, "data": None}


def _national_flow_data():
    """国家队/大基金持有进出资产（data/_national_flow_hist.json）。缺失返回 None。"""
    return _load_json_lazy(_NF_FILE, _NF_MEM)


@register("national_flow")
def _national_flow_node(ctx, node, inputs):
    """国家队（中央汇金/证金）+ 大基金（国家集成电路产业投资基金等）持有进出判定。

    ★ 三条直接持股通道（2026-09-13 v2，回答「国家队/社保会不会**直接买某只股票**」）：
      ① free 季报十大流通股东（HOLDER_NAME 分类 nat/big/ss）；
      ② lock 锁定通道 = 十大股东（RPT_F10_EH_HOLDERS）可见、十大流通榜**不可见**的 nat/big/ss
         持股 ⇒ 限售/非流通（大基金定增锁定 18 个月、战投、发起人股）。原口径硬缺口：
         锁定股不进「十大流通股东」排名，季报流通通道最长漏 18 个月。占比口径=占总股本%。
         注意：「退出」**不计分**（锁定股退出十大股东最常见是**解禁转流通**，非减持）；
      ③ ann 公告通道 = 股东增减持 + 定增获配（T+1，比季报快 1-3 个月）。窗口 365 天，
         窗口内参与计分并置 annFresh=True；超窗口仍保留 annDir/annNotice 供展示「时间点」。
    ★ 硬约束（research 文档 §7.4「实盘红线」）：
      1) 身份只取法定披露（十大股东/十大流通股东/增减持·定增公告）—— ETF 申赎、
         估算资金流一律不进本节点的 nat/big/ss 口径（ETF 另见 national_etf_share 节点）；
      2) NOTICE_DATE/公告日 ≤ 信号日（无未来函数，由 `_national_flow` 保证）；
      3) 季报滞后 1-3 月（公告通道 T+1）→ 仍非实时信号；看不见前十大之后的仓位。
    ★ 关键口径：「退出」= 曾进前十大、现**连续 ≥2 期缺席**（每季披露，连续缺席即已退出）。
       否则会把 2015 年一次新进一路带到 2026 年 —— 实测 002371 国家队披露停在 2021Q1、
       300308 停在 2020Q2，与 research 文档「汇金 2015 后个股可见度下降、转向 ETF」一致。
    ★ 计分：flowScore = 50 + Σ(渠道权重×方向)，国家队/大基金 1.0、社保 0.9；
       流入按连续确认期数放大 1.35（国家队 ≥3 期、大基金/社保 ≥2 期才算 strong）；
       流出扣满权重、退出按 0.6 折算（可能仅掉出十名之外）。

    config：
      sourceNode  上游节点 id（缺省 n_holdings_top5；也接 n_industry_scan / n_inst_pool）
      mode        annotate（默认，只贴标签、保持上游顺序）/ filter（按下面门槛筛）
      requireNat  filter 下要求国家队「流入」
      requireBig  filter 下要求大基金「流入」
      minScore    filter 下要求 flowScore ≥（默认 50）
      excludeOut  filter 下剔除国家队「流出/退出」
      requireLock filter 下要求锁定通道「流入」（十大股东新增锁定持股=定增/战投在建仓）
      requireAnn  filter 下要求公告通道「流入」（近一年增减持/定增公告净买入）
      annInWindow requireAnn 时是否只认窗口内公告（默认 true；false 会认超窗口的历史公告）
      minDirect   filter 下要求 directScore ≥（默认 0=不过滤）
      maxRows     限行（默认 200）
    输出：{as_of, judge_day, n, counts{流入,流出,退出,持稳,无}, rows:[…+natState/natStrong/
      natRatio/natChg/natEnd/natNotice/natNames/bigState/…/ssState/…/flowScore/directScore/
      lockState/lockKind/lockRatio/lockEnd/lockNotice/lockTypes/lockNames/
      annState/annDir/annKind/annNotice/annFresh/annN/annNames/annDetail/flowLabel/semi]}
    """
    cfg = dict(node.config or {})
    src_id = str(cfg.get("sourceNode") or "n_holdings_top5")
    src = ctx.get(src_id)
    if not isinstance(src, dict) or not src.get("rows"):
        src = _upstream_rows(inputs, (src_id,)) or src
    src = src if isinstance(src, dict) else {"rows": []}
    rows = [dict(r) for r in (src.get("rows") or [])]
    d = _national_flow_data()
    if not d or not d.get("snap"):
        ctx.notes.append("[%s] ✗ 缺 data/_national_flow_hist.json（国家队/大基金数据资产）" % node.id)
        out = dict(src)
        out.update({"available": False, "rows": rows,
                    "note": "缺数据资产：先跑 smalltools/_national_flow.py --build"})
        ctx.stage(node.id, out)
        return out
    snap = d.get("snap") or {}
    cnt = {"流入": 0, "流出": 0, "退出": 0, "持稳": 0, "无": 0}
    lkc = {"流入": 0, "流出": 0, "退出": 0, "持稳": 0, "无": 0}
    anc = {"流入": 0, "流出": 0, "历史": 0, "无": 0}
    for r in rows:
        s = snap.get(_etf_code6(r.get("code"))) or {}
        st = s.get("natState") or "无"
        cnt[st] = cnt.get(st, 0) + 1
        lkc[s.get("lockState") or "无"] = lkc.get(s.get("lockState") or "无", 0) + 1
        _an = s.get("annState") or "无"
        if _an != "无" and not s.get("annFresh"):
            _an = "历史"
        anc[_an] = anc.get(_an, 0) + 1
        r.update({"natState": st, "natStrong": bool(s.get("natStrong")),
                  "natRatio": s.get("natRatio"), "natChg": s.get("natChg"),
                  "natEnd": s.get("natEnd"), "natNotice": s.get("natNotice"),
                  "natNames": s.get("natNames") or [],
                  "bigState": s.get("bigState") or "无", "bigStrong": bool(s.get("bigStrong")),
                  "bigRatio": s.get("bigRatio"), "bigChg": s.get("bigChg"),
                  "bigEnd": s.get("bigEnd"), "bigNotice": s.get("bigNotice"),
                  "ssState": s.get("ssState") or "无", "ssRatio": s.get("ssRatio"),
                  # ① 锁定通道（十大股东可见/流通榜不可见＝限售，如大基金定增锁定中）
                  "lockState": s.get("lockState") or "无",
                  "lockStrong": bool(s.get("lockStrong")),
                  "lockKind": s.get("lockKind") or "", "lockRatio": s.get("lockRatio"),
                  "lockEnd": s.get("lockEnd"), "lockNotice": s.get("lockNotice"),
                  "lockTypes": s.get("lockTypes") or [], "lockNames": s.get("lockNames") or [],
                  # ② 公告通道（股东增减持/定增获配，T+1）
                  "annState": s.get("annState") or "无", "annDir": s.get("annDir") or "",
                  "annKind": s.get("annKind") or "", "annRatio": s.get("annRatio"),
                  "annNotice": s.get("annNotice") or "", "annEnd": s.get("annEnd") or "",
                  "annSrc": s.get("annSrc") or "", "annFresh": bool(s.get("annFresh")),
                  "annN": s.get("annN", 0), "annNames": s.get("annNames") or [],
                  "annDetail": s.get("annDetail") or [],
                  "flowScore": s.get("flowScore", 50.0),
                  "directScore": s.get("directScore", 50.0),
                  "flowLabel": s.get("flowLabel") or "无披露",
                  "semi": bool(s.get("semi"))})
    if str(cfg.get("mode") or "annotate").lower() == "filter":
        min_score = float(cfg.get("minScore", 50) or 0)
        req_nat = _bool_cfg(cfg.get("requireNat"), False)
        req_big = _bool_cfg(cfg.get("requireBig"), False)
        ex_out = _bool_cfg(cfg.get("excludeOut"), False)
        # 直接持股通道（2026-09-13 v2）：锁定持股 / 近一年公告增持
        req_lock = _bool_cfg(cfg.get("requireLock"), False)
        req_ann = _bool_cfg(cfg.get("requireAnn"), False)
        min_direct = float(cfg.get("minDirect", 0) or 0)
        ann_win_only = _bool_cfg(cfg.get("annInWindow"), True)

        def _keep(r):
            if req_nat and r.get("natState") != "流入":
                return False
            if req_big and r.get("bigState") != "流入":
                return False
            if ex_out and r.get("natState") in ("流出", "退出"):
                return False
            if req_lock and r.get("lockState") != "流入":
                return False
            if req_ann:
                if r.get("annState") != "流入":
                    return False
                if ann_win_only and not r.get("annFresh"):
                    return False          # 超窗口的公告只是「历史时间点」，不能当买入信号
            if min_direct and float(r.get("directScore") or 0) < min_direct:
                return False
            return float(r.get("flowScore") or 0) >= min_score

        rows = [r for r in rows if _keep(r)]
    rows = rows[:int(cfg.get("maxRows", 200) or 200)]
    out = dict(src)
    out.update({"as_of": src.get("as_of") or ctx.asof, "available": True,
                "judge_day": d.get("judge_day"), "built": d.get("built"),
                "rule": d.get("rule"), "states": d.get("states"),
                "n": len(rows), "counts": cnt,
                "counts_lock": lkc, "counts_ann": anc, "rows": rows})
    ctx.stage(node.id, out)
    ctx.notes.append("[%s] 🏛️ 国家队/大基金(%s): %d 只 → 流入 %d / 流出 %d / 退出 %d / 持稳 %d"
                     " ｜锁定 流入%d ｜公告 流入%d"
                     % (node.id, d.get("judge_day"), len(rows),
                        cnt["流入"], cnt["流出"], cnt["退出"], cnt["持稳"],
                        lkc["流入"], anc["流入"]))
    return out


# ══════════════════════════════════════════════════════════════════════════
# 国家队（汇金）宽基 ETF 份额动向（national_etf_share，2026-09-13 新增）
#   规则出处：E:\Android\work\dev\选股思路\national_flow_research.md、
#             institutional_breakout_guide.md（§8 FundFlowAnalyzer 流入/流出扫描）
#   数据资产：data/_etf_share_signal.json（当日拐点信号，smalltools/_etf_share_flow.py）
#             data/_etf_share_hist.json（逐只宽基 ETF 份额四象限 + 持有人结构）
#   ★ 与 national_flow 的分工（互补，勿合并）：
#     national_flow 读「十大流通股东」——**个股**级、季报滞后、汇金 2015 后披露稀少；
#     national_etf_share 读「宽基 ETF 份额」——**市场/宽基**级、每日可观测；
#     汇金 2015 年后主要借道宽基 ETF，份额(申赎)才是其进出的直接计量。
#   ★ 判定算法单一事实源 = smalltools/_etf_share_flow.py；资产内 judge 已含结论，
#     Python 节点 / Kotlin 节点 / 推送表三处只读不算，避免口径漂移。
#   ★ 四象限（份额 vs 单位净值）：份额↑&价↓=低位承接(买) / 份额↓&价↑=高位派发(卖)；
#     汇金身份取**年度报告**§9.2「期末上市基金前十名持有人」（半年报无此子项，只能年报点名）。
#   ★ 本节点是**市场级横幅**：只贴标签、不改上游顺序、不筛行。
# ══════════════════════════════════════════════════════════════════════════

_ETF_SHARE_SIGNAL_FILE = os.path.join(_REPO_ROOT, "data", "_etf_share_signal.json")
_ETF_SHARE_HIST_FILE = os.path.join(_REPO_ROOT, "data", "_etf_share_hist.json")
_ETF_SHARE_SIGNAL_MEM = {"key": None, "data": None}
_ETF_SHARE_HIST_MEM = {"key": None, "data": None}


def _etf_share_signal():
    """当日 ETF 份额拐点信号（data/_etf_share_signal.json）。缺失返回 None。"""
    return _load_json_lazy(_ETF_SHARE_SIGNAL_FILE, _ETF_SHARE_SIGNAL_MEM)


def _etf_share_hist():
    """宽基 ETF 份额历史 + 持有人（data/_etf_share_hist.json）。缺失返回 None。"""
    return _load_json_lazy(_ETF_SHARE_HIST_FILE, _ETF_SHARE_HIST_MEM)


@register("national_etf_share")
def _national_etf_share_node(ctx, node, inputs):
    """国家队（汇金）宽基 ETF 份额动向：市场级横幅，透传上游 rows，不改顺序、不筛行。

    config：
      sourceNode  上游节点 id（缺省取第一个带 rows 的上游；仅作透传）
      maxFunds    逐只 ETF 明细条数（默认 10）
      emitFunds   是否附带 funds 明细（默认 true；false 只回 judge 摘要）
    输出（在上游输出之外追加）：
      {available, updated, verdict, level, lines[],
       quarterly{current,at,net_sub,turns[]}, lastTurn{at,dir,net_sub},
       huijin{code,pct,names}, attr{...}, daily{...},
       dailyLead{from,to,n,outflow[],inflow[]}, recentDaily{from,to,d_pct,lead,board},
       push, why[],
       funds:[{code,name,last,dSharesPct,dNavPct,netSub,quadrant}], rule}
    缺失资产时 available=false 并透传上游（不阻断链路）。
    """
    cfg = dict(node.config or {})
    src_id = str(cfg.get("sourceNode") or "")
    src = ctx.get(src_id) if src_id else None
    if not isinstance(src, dict) or not src.get("rows"):
        src = _upstream_rows(inputs, (src_id,) if src_id else ()) or src
    src = src if isinstance(src, dict) else {}
    sig = _etf_share_signal()
    j = (sig or {}).get("judge") or {}
    if not sig or not j.get("lines"):
        ctx.notes.append("[%s] ✗ 缺 data/_etf_share_signal.json（国家队ETF份额资产）" % node.id)
        out = dict(src)
        out.update({"available": False,
                    "note": "缺数据资产：先跑 smalltools/_etf_share_flow.py（或 --snap）"})
        ctx.stage(node.id, out)
        return out
    max_funds = int(cfg.get("maxFunds", 10) or 10)
    funds = []
    if _bool_cfg(cfg.get("emitFunds"), True):
        for code, w in ((_etf_share_hist() or {}).get("width") or {}).items():
            per = w.get("periods") or []
            last = per[-1] if per else {}
            funds.append({"code": code, "name": w.get("name") or "",
                          "last": last.get("end"),
                          "dSharesPct": last.get("d_shares_pct"),
                          "dNavPct": last.get("d_nav_pct"),
                          "netSub": last.get("net_sub"),
                          "quadrant": last.get("quadrant")})
        funds.sort(key=lambda x: (x.get("last") or "", x.get("code") or ""), reverse=True)
        funds = funds[:max_funds]
    q = j.get("quarterly") or {}
    lt = j.get("last_turn") or {}
    out = dict(src)
    out.update({
        "as_of": src.get("as_of") or ctx.asof,
        "available": True,
        "updated": sig.get("as_of"),
        "verdict": j.get("verdict"), "level": j.get("level"),
        "lines": j.get("lines") or [],
        "quarterly": q,
        "lastTurn": ({"at": lt.get("at"), "dir": lt.get("dir"),
                      "netSub": lt.get("net_sub")} if lt else None),
        "huijin": j.get("huijin"), "attr": j.get("attr"),
        "attrCode": j.get("attr_code"), "daily": j.get("daily"),
        "dailyLead": j.get("daily_lead"), "recentDaily": j.get("recent_daily"),
        "push": bool(sig.get("push")), "why": sig.get("why") or [],
        "funds": funds,
        "rule": ("宽基ETF份额(申赎)四象限：份额↑&价↓=低位承接(买) / 份额↓&价↑=高位派发(卖)；"
                 "汇金身份取年报§9.2前十名持有人（法定披露，滞后；半年报无此子项）；"
                 "日频快照每交易日追加，季度披露给方向、日频给拐点时点"),
    })
    ctx.stage(node.id, out)
    # 横幅取「季度方向 + 日频近端」两条：季度给方向、日频给拐点时点（9.11 净申购等）
    ls = [x for x in (j.get("lines") or []) if x]
    core = ([ls[0]] if ls else [])
    for x in ls:
        if ("日频净申购领先" in x or "背离" in x) and x not in core:
            core.append(x)
    if len(core) < 2:
        for x in ls[1:]:
            if x.startswith("日频") and x not in core:
                core.append(x)
                break
    ctx.notes.append("[%s] 🏛️ 国家队ETF份额: %s（置信 %s）｜%s"
                     % (node.id, j.get("verdict"), j.get("level"), "；".join(core[:2])))
    return out


@register("etf_holdings_rank")
def _etf_holdings_rank_node(ctx, node, inputs):
    """ETF 持股 top5：被多只核心（行业/主题）ETF 前五重仓覆盖的个股排行。

    输入：`data/_etf_holdings.json` 的 `stocks` 覆盖矩阵（排行本身无需行情）。
    config：topN=10、minCoverage=2、industryOnly=true、baseThemes=宽基、
      embedBars=true、barsLimit=140、minBars=60（内嵌行情供下游止损定价）、
      strategyText=""（发布口径文案）。
    输出：{as_of, updated, fundsTotal, stocksTotal, rows:[{code,name,n,funds[],themes[],
      sumRatio,pos60,stopPct 建议口径}], note}
    """
    cfg = dict(node.config or {})
    top_n = int(cfg.get("topN", 10))
    min_cov = int(cfg.get("minCoverage", 2))
    ind_only = _bool_cfg(cfg.get("industryOnly"), True)
    base_themes = set(x.strip() for x in str(cfg.get("baseThemes", "宽基")).split(",") if x.strip())
    d = _etf_holdings_data()
    if not d:
        ctx.notes.append("[%s] ✗ 缺 data/_etf_holdings.json（ETF 持股 top5 数据资产）" % node.id)
        out = {"as_of": ctx.asof, "available": False, "rows": [],
               "note": "缺 data/_etf_holdings.json：请先跑 smalltools/_etf_holdings.py update"}
        ctx.stage(node.id, out)
        return out
    funds = {}
    for f in d.get("funds") or []:
        funds[_etf_code6(f.get("code"))] = f
    stocks = d.get("stocks") or {}
    rows = []
    for code, st in stocks.items():
        fl = [_etf_code6(x) for x in (st.get("funds") or []) if _etf_code6(x) in funds]
        if ind_only:
            fl = [x for x in fl if str(funds[x].get("theme") or "") not in base_themes]
        if len(fl) < min_cov:
            continue
        themes = list(dict.fromkeys(str(funds[x].get("theme") or "") for x in fl))
        rows.append({
            "code": _etf_prefixed(code), "code6": _etf_code6(code),
            "name": st.get("name") or "", "n": len(fl),
            "funds": [str(funds[x].get("name") or x) for x in fl],
            "themes": [t for t in themes if t],
            "sumRatio": round(float(st.get("sum_ratio") or 0), 2),
            "pos60": st.get("pos60"),
        })
    rows.sort(key=lambda r: (-r["n"], -r["sumRatio"],
                             r["pos60"] if isinstance(r["pos60"], (int, float)) else 0.0))
    rows = rows[:top_n]
    # 附近期行情（内嵌 bars）→ 下游 stop_loss_vote target=picks 可直接定价；无行情则留空降级
    embed = _bool_cfg(cfg.get("embedBars"), True)
    if embed:
        cache = ctx.cache or {}
        bars_limit = int(cfg.get("barsLimit", 140))
        min_bars = int(cfg.get("minBars", 60))
        miss = 0
        for r in rows:
            snaps = _etf_snaps_for(cache, r["code6"], max(25, min_bars))
            if not snaps:
                miss += 1
                continue
            closes = [float(x.get("close") or 0) for x in snaps]
            r["bars"] = _bars_pack(snaps, bars_limit)
            r["date"] = snaps[-1].get("date", "")
            r["close"] = round(closes[-1], 3)
        if miss:
            ctx.notes.append("[%s] ⚠ %d 只覆盖股无本地行情（止损仅按固定比例降级）"
                             % (node.id, miss))
    out = {"as_of": ctx.asof, "available": True, "updated": d.get("updated", ""),
           "fundsTotal": len(funds), "stocksTotal": len(stocks), "rows": rows,
           "industryOnly": ind_only, "minCoverage": min_cov,
           "note": ("ETF 前五重仓覆盖矩阵（季报级）：n=覆盖该股的行业ETF数；"
                    + ("仅被宽基覆盖的个股已剔除。" if ind_only else "")),
           "strategy": cfg.get("strategyText") or ""}
    ctx.stage(node.id, out)
    ctx.notes.append("[%s] 🧺 ETF 持股 top5: %d 只（覆盖≥%d 只行业ETF）"
                     % (node.id, len(rows), min_cov))
    return out


@register("etf_industry_scan")
def _etf_industry_scan_node(ctx, node, inputs):
    """ETF 全行业扫描：行业/主题 ETF → 前五重仓 → 逐股低吸打分 → topN。

    行情的取数顺序：ctx.cache[code].snaps → data/_etf_top5_hist.json 全史日K。
    输出行内嵌 `bars`（近 barsLimit 根），供下游 `stop_loss_vote target=picks` 直接定价。

    config：topN=10、minScore=5.0、minBars=60、ddWin=60、industryOnly=true、
      baseThemes=宽基、excludeStar=true、barsLimit=140、embedBars=true、
      minETF=1（至少被 1 只行业ETF 前五重仓）、strategyText=""
      + 大盘自适应：marketAdapt=true、indexCode=sh000300、oscScoreAdd=1.0、
        oscPos60Max=-12、bearishPause=true。
    """
    cfg = dict(node.config or {})
    top_n = int(cfg.get("topN", 10))
    min_score = float(cfg.get("minScore", 5.0))
    min_bars = int(cfg.get("minBars", 60))
    dd_win = int(cfg.get("ddWin", 60))
    min_etf = int(cfg.get("minETF", 1))
    ind_only = _bool_cfg(cfg.get("industryOnly"), True)
    base_themes = set(x.strip() for x in str(cfg.get("baseThemes", "宽基")).split(",") if x.strip())
    ex_star = _bool_cfg(cfg.get("excludeStar"), True)
    bars_limit = int(cfg.get("barsLimit", 140))
    embed = _bool_cfg(cfg.get("embedBars"), True)
    # 大盘三态自适应（回答「ETF 选股要不要看大盘」）：BULLISH 正常 / OSCILLATION 提门槛且要求更低吸 /
    # BEARISH 暂停开仓仅观察。门控取自沪深300（与 n_market / etf_gate 同口径）。
    cache = ctx.cache or {}
    mkt = (_etf_market_state(cache, cfg.get("indexCode", "sh000300"))
           if _bool_cfg(cfg.get("marketAdapt"), True) else "OSCILLATION")
    eff_min = min_score + (float(cfg.get("oscScoreAdd", 1.0)) if mkt == "OSCILLATION" else 0.0)
    osc_pos_max = float(cfg.get("oscPos60Max", -12))
    bear_pause = bool(_bool_cfg(cfg.get("bearishPause"), True) and mkt == "BEARISH")
    d = _etf_holdings_data()
    if not d:
        ctx.notes.append("[%s] ✗ 缺 data/_etf_holdings.json（ETF 全行业扫描数据资产）" % node.id)
        out = {"as_of": ctx.asof, "available": False, "rows": [],
               "note": "缺 data/_etf_holdings.json：请先跑 smalltools/_etf_holdings.py update"}
        ctx.stage(node.id, out)
        return out

    # ① 行业/主题 ETF → 前五重仓股（候选 + 归属 ETF/主题/合计权重）
    cand = {}
    for f in d.get("funds") or []:
        theme = str(f.get("theme") or "")
        if ind_only and theme in base_themes:
            continue
        for s in f.get("top") or []:
            c6 = _etf_code6(s.get("code"))
            if not c6:
                continue
            if ex_star and c6[:2] in ("68", "69"):
                continue
            e = cand.setdefault(c6, {"name": s.get("name") or "", "etfs": [],
                                     "themes": [], "ratio": 0.0})
            fn = str(f.get("name") or f.get("code"))
            if fn not in e["etfs"]:
                e["etfs"].append(fn)
            if theme and theme not in e["themes"]:
                e["themes"].append(theme)
            e["ratio"] += float(s.get("ratio") or 0)
    cand = {k: v for k, v in cand.items() if len(v["etfs"]) >= min_etf}

    # ② 逐股取行情 + 打分
    rows, skipped = [], []
    for c6, meta in cand.items():
        snaps = _etf_snaps_for(cache, c6, min_bars)
        if not snaps:
            skipped.append(c6)
            continue
        try:
            closes = [float(x.get("close") or 0) for x in snaps]
            opens = [float(x.get("open") or 0) for x in snaps]
            highs = [float(x.get("high") or 0) for x in snaps]
            lows = [float(x.get("low") or 0) for x in snaps]
            vols = [float(x.get("volume") or 0) for x in snaps]
        except (TypeError, ValueError):
            skipped.append(c6)
            continue
        pos60 = None
        if len(closes) >= 21:
            hi = max(closes[-dd_win:])
            if hi > 0:
                pos60 = round((closes[-1] / hi - 1) * 100, 2)
        sc = _etf_pick_score(closes, opens, highs, lows, vols, pos60)
        if not sc:
            skipped.append(c6)
            continue
        ok = bool(sc["core"] and sc["score"] >= eff_min)
        if ok and mkt == "OSCILLATION" and pos60 is not None and pos60 > osc_pos_max:
            ok = False                                  # 震荡市：只接「更深回撤」的低吸
        if ok and bear_pause:
            ok = False                                  # 熊市：暂停开仓，降级为观察
        row = {"code": _etf_prefixed(c6), "code6": c6, "name": meta["name"],
               "date": snaps[-1].get("date", ""), "close": round(closes[-1], 3),
               "score": sc["score"], "ok": ok,
               "etfs": meta["etfs"], "themes": meta["themes"],
               "sumRatio": round(meta["ratio"], 2), "pos60": pos60,
               "sar": sc["sar"], "freshUp": sc["freshUp"], "macd": sc["macd"],
               "obv": sc["obv"], "rsi6": round(sc["rsi6"], 1),
               "volRatio": sc["volRatio"], "stable": sc["stable"],
               "above5": sc["above5"], "above10": sc["above10"],
               "threeNoLow": sc["threeNoLow"], "upDay": sc["upDay"],
               "kline": _etf_kline_desc(closes, len(closes) - 1),
               "trend": _etf_trend_label(snaps)}
        if embed:
            row["bars"] = _bars_pack(snaps, bars_limit)
        rows.append(row)

    rows.sort(key=lambda r: -r["score"])
    picked = [r for r in rows if r["ok"]][:top_n]
    degraded = not picked
    if degraded:
        picked = rows[:top_n]
    out = {"as_of": ctx.asof, "available": True, "updated": d.get("updated", ""),
           "universe": len(cand), "scanned": len(rows), "skipped": len(skipped),
           "rows": picked, "degraded": degraded, "marketState": mkt,
           "minScore": eff_min, "industryOnly": ind_only,
           "strategy": cfg.get("strategyText") or "",
           "marketNote": ("🐮 多头：正常扫描" if mkt == "BULLISH" else
                          ("🐻 空头：暂停开仓，仅输出观察清单" if mkt == "BEARISH" else
                           "↔ 震荡：门槛 +%.1f 且要求 pos60≤%.0f%%"
                           % (eff_min - min_score, osc_pos_max))),
           "note": ("行业ETF前五重仓 → 低吸打分（SAR红为硬门控；score≥%.1f 且含转折确认）"
                    % eff_min) + ("；⚠ 无达标标的，已降级输出打分前 %d（仅供观察）"
                                  % len(picked) if degraded else "")}
    ctx.stage(node.id, out)
    ctx.notes.append("[%s] 🔭 ETF 全行业扫描(%s): 候选 %d 只 → 打分 %d 只 → %s %d 只（跳过 %d 只无行情）"
                     % (node.id, mkt, len(cand), len(rows),
                        "降级观察" if degraded else "入选", len(picked), len(skipped)))
    return out


@register("etf_pure_screen")
def _etf_pure_screen_node(ctx, node, inputs):
    """纯 ETF 本体筛选：ETF 自身 RAS/MACD/OBV/RSI6/250日回撤/流动性 → 底仓与做T信号。

    与 smalltools 的 dip_buy 不同：本节点筛的是 **ETF 本体**（不是成分股），
    规则直接落地 ETF选股思路.txt §1~§3 的 select_etf / base_buy / t_buy / t_sell。

    config：indexCode=sh000300、ddLo=0.40、ddHi=0.60、growthRelax=true、
      relaxLo=0.35、relaxHi=0.65、ddWin=250、rsiLo=30、rsiHi=55、rsiSell=70、
      rasWin=20、amountWin=20、volLot=100、minAmountYi=0.5、topN=5、minBars=250、
      requireRas=true、requireMacd=true、requireObv=true、strategyText=""
      + 大盘自适应：marketAdapt=true、bearishPause=true、bullLo=0.45、bullHi=0.72、bullRsiHi=60。
    """
    cfg = dict(node.config or {})
    index_code = cfg.get("indexCode", "sh000300")
    dd_lo, dd_hi = float(cfg.get("ddLo", 0.40)), float(cfg.get("ddHi", 0.60))
    if _bool_cfg(cfg.get("growthRelax"), True):
        dd_lo, dd_hi = float(cfg.get("relaxLo", 0.35)), float(cfg.get("relaxHi", 0.65))
    dd_win = int(cfg.get("ddWin", 250))
    rsi_lo = float(cfg.get("rsiLo", 30))
    rsi_hi = float(cfg.get("rsiHi", 55))
    rsi_sell = float(cfg.get("rsiSell", 70))
    ras_win = int(cfg.get("rasWin", 20))
    amt_win = int(cfg.get("amountWin", 20))
    vol_lot = float(cfg.get("volLot", 100))
    min_amt = float(cfg.get("minAmountYi", 0.5))
    top_n = int(cfg.get("topN", 5))
    min_bars = int(cfg.get("minBars", dd_win))
    req_ras = _bool_cfg(cfg.get("requireRas"), True)
    req_macd = _bool_cfg(cfg.get("requireMacd"), True)
    req_obv = _bool_cfg(cfg.get("requireObv"), True)

    cache = ctx.cache or {}
    # 大盘三态自适应（回答「ETF 选股要不要看大盘」）：
    #   BULLISH  → 位置门槛放宽（ddLo~ddHi→bullLo~bullHi、rsiHi→bullRsiHi），回撤不必那么深；
    #   OSCILLATION → 标准档（思路文档原口径）；
    #   BEARISH  → 底仓 base_buy 全部关闭，仅保留做 T（tBuy/tSell）观察。
    mkt = (_etf_market_state(cache, index_code)
           if _bool_cfg(cfg.get("marketAdapt"), True) else "OSCILLATION")
    if mkt == "BULLISH":
        dd_lo, dd_hi = float(cfg.get("bullLo", 0.45)), float(cfg.get("bullHi", 0.72))
        rsi_hi = float(cfg.get("bullRsiHi", 60))
    bear_pause = bool(_bool_cfg(cfg.get("bearishPause"), True) and mkt == "BEARISH")
    # 宽基 ETF（β 标的，思路文档口径：宽基只用来做 T，不作低位底仓）：行内打 broad 标记；
    # excludeBroadBaseBuy=true 时禁止其 baseBuy（本地 13 只池以宽基为主，故默认 false）。
    broad_set = set(_etf_code6(x) for x in str(cfg.get(
        "broadCodes",
        "510300,159915,510050,510500,510180,510880,159919,510330,510900,159901,159905,159949"
    )).split(",") if x.strip())
    excl_broad = _bool_cfg(cfg.get("excludeBroadBaseBuy"), False)
    bench = ((cache.get(index_code) or {}).get("snaps") or [])
    bench_closes = [float(x.get("close") or 0) for x in bench]
    rows, skipped = [], []
    for code, ent in cache.items():
        if str(code).startswith("sh000") or str(code).startswith("sz399"):
            continue
        snaps = (ent or {}).get("snaps") or []
        if len(snaps) < min_bars or len(bench_closes) < ras_win + 1:
            skipped.append(code)
            continue
        try:
            closes = [float(x.get("close") or 0) for x in snaps]
            opens = [float(x.get("open") or 0) for x in snaps]
            highs = [float(x.get("high") or 0) for x in snaps]
            lows = [float(x.get("low") or 0) for x in snaps]
            vols = [float(x.get("volume") or 0) for x in snaps]
        except (TypeError, ValueError):
            skipped.append(code)
            continue
        c = closes[-1]
        h250 = max(closes[-dd_win:])
        if h250 <= 0:
            skipped.append(code)
            continue
        dd = c / h250                                    # 1.0=在250日高点；0.4~0.6=低位
        rsi6 = _etf_rsi(closes, 6)[-1]
        dif = _etf_ema(closes, 12)
        dea = _etf_ema(dif, 9)
        macd_bar = (dif[-1] - dea[-1]) * 2
        prev_bar = ((dif[-2] - dea[-2]) * 2) if len(dif) > 1 else macd_bar
        macd_t = _etf_macd_text(closes)
        obv_t = _etf_obv_text(closes, vols) or ""
        slopes = _ras_slope_series(closes, bench_closes, ras_win)
        g2r = bool(len(slopes) >= 2 and slopes[-2] < 0 <= slopes[-1])
        lo250 = min(lows[-dd_win:])
        amt_yi = (sum(vols[-amt_win + i] * closes[-amt_win + i] * vol_lot
                      for i in range(amt_win)) / float(amt_win) / 1e8)
        cond_ras = (g2r or not req_ras)
        cond_macd = (dif[-1] > dea[-1] or not req_macd)
        cond_obv = (obv_t == "OBV上行" or not req_obv)
        cond_rsi = (rsi_lo <= rsi6 <= rsi_hi)
        cond_pos = (dd_lo <= dd <= dd_hi)
        cond_amt = (amt_yi >= min_amt)
        base_buy = bool(cond_ras and cond_macd and cond_obv and cond_rsi and cond_pos and cond_amt)
        if bear_pause:
            base_buy = False                            # 熊市：底仓关闭，只留做 T
        is_broad = _etf_code6(code) in broad_set
        if is_broad and excl_broad:
            base_buy = False                            # 宽基：只做 T，不作低位底仓
        t_buy = bool(rsi6 <= rsi_lo and macd_bar < prev_bar)
        t_sell = bool(rsi6 >= rsi_sell and macd_bar > prev_bar)
        pos_score = (2.0 if dd <= dd_lo + 0.08 else
                     (1.5 if dd <= dd_lo + 0.16 else 1.0)) if cond_pos else 0.0
        score = ((2.0 if g2r else 0.0) + (2.0 if dif[-1] > dea[-1] else 0.0)
                 + (1.5 if obv_t == "OBV上行" else 0.0)
                 + (1.0 if cond_rsi else 0.0) + pos_score)
        rows.append({
            "code": code, "name": ent.get("name") or code, "date": snaps[-1].get("date", ""),
            "close": round(c, 3), "dd250": round(dd, 3), "pos250": round(dd * 100, 1),
            "rsi6": round(rsi6, 1), "macd": macd_t, "obv": obv_t,
            "rasGreen2Red": g2r, "rasSlope": round(slopes[-1], 6) if slopes else None,
            "amountYi": round(amt_yi, 2), "minAmountYi": min_amt, "broad": is_broad,
            "baseBuy": base_buy, "tBuy": t_buy, "tSell": t_sell, "score": round(score, 2),
            "gates": {"ras": cond_ras, "macd": cond_macd, "obv": cond_obv,
                      "rsi": cond_rsi, "pos": cond_pos, "amount": cond_amt},
            "kline": _etf_kline_desc(closes, len(closes) - 1),
            "trend": _etf_trend_label(snaps),
            "exit": {"tpRsi": 75.0, "sl250": round(lo250 * 0.95, 3),
                     "note": "底仓止盈: RSI6>75 或 RAS转绿；底仓止损: 跌破250日低点下方5%"}})
    rows.sort(key=lambda r: (-r["score"], r["dd250"]))
    n_base = len([r for r in rows if r["baseBuy"]])
    picked = [r for r in rows if r["baseBuy"]][:top_n]
    degraded = not picked
    if degraded:
        picked = rows[:top_n]
    watch = [r for r in rows if not r["baseBuy"]][:top_n]
    out = {"as_of": ctx.asof, "available": bool(bench_closes), "bench": index_code,
           "pool": len(cache), "scanned": len(rows), "skipped": len(skipped),
           "rows": picked, "watch": watch, "degraded": degraded, "nBaseBuy": n_base,
           "marketState": mkt,
           "marketNote": ("🐮 多头：位置门槛放宽到 %.2f~%.2f / RSI上限 %.0f" % (dd_lo, dd_hi, rsi_hi)
                          if mkt == "BULLISH" else
                          ("🐻 空头：底仓关闭，仅保留做 T 观察" if mkt == "BEARISH" else
                           "↔ 震荡：标准档 %.2f~%.2f" % (dd_lo, dd_hi))),
           "rule": {"ddWindow": dd_win, "ddRange": [dd_lo, dd_hi],
                    "rsi6": [rsi_lo, rsi_hi], "rsiSell": rsi_sell,
                    "rasWin": ras_win, "minAmountYi": min_amt},
           "strategy": cfg.get("strategyText") or "",
           "note": ("纯ETF本体筛选：baseBuy=五个门控全过（本次达标 %d 只）" % n_base) + (
               "；⚠ 无达标标的，已降级输出打分前 %d（仅供观察）" % len(picked) if degraded else "")}
    ctx.stage(node.id, out)
    ctx.notes.append("[%s] 🎯 纯ETF筛选: 池 %d → 可判 %d → base_buy %d 只（跳过 %d 只样本不足）"
                     % (node.id, len(cache), len(rows), n_base, len(skipped)))
    return out


# ──────────────────────────────────────────────────────────────────────────
# 做T链路入口：t_trade_import（对齐 APK T_TradeImportNode）
#
# 其余 t_trade / real_holding 节点（t_holdings_load / t_inst_intent /
# t_signal_synthesize / t_recommend_save / t_trade_eval / sector_leader_analysis /
# t1_auto_sell）的 Kotlin 实现全部依赖 Android Room 持仓库
# （realPositionDao / strategyTradeOrderDao / tTradeRecordDao）+ 实时行情 +
# 分时 + 墙上时钟时段，PC 回测环境无对应数据源（已实测：手机镜像
# real_positions / strategy_trade_orders / t_trade_records 三张表均为空），
# 因此按既有约定在 _passthrough 中声明式降级（原因见 _PASSTHROUGH_WHY），
# 不做「永远跑不出结果」的空实现。
# ──────────────────────────────────────────────────────────────────────────
@register("t_trade_import")
def _n_t_trade_import(ctx, node, inputs):
    """做T交易日导入：对齐 T_TradeImportNode 的 isTradingDay/tradeDate 语义。

    PC 口径按 ctx.asof 的工作日近似（无 A 股节假日表；节假日会误判为交易日，
    但下游 t_holdings_load 因无持仓库仍降级，不影响选股主线结论）。
    """
    d = None
    try:
        d = _dt.date.fromisoformat(str(ctx.asof or "")[:10])
    except Exception:
        pass
    is_td = True if d is None else (d.weekday() < 5)
    out = {"isTradingDay": is_td, "tradeDate": ctx.asof or "",
           "source": "asof-weekday(PC 近似)"}
    ctx.stage(node.id, out)
    ctx.stage("t_import", out)
    ctx.notes.append("[%s] 📅 做T交易日 %s：%s（PC 按工作日近似，无节假日表）"
                     % (node.id, out["tradeDate"], "✅ 交易日" if is_td else "⛔ 非交易日"))
    return out


if __name__ == "__main__":
    # 自检样例：python app/src/main/assets/usecases/usecase_pipeline.py
    import json
    cache = json.load(open(os.path.join(_SMALLTOOLS_DIR, "_kline_cache.json"),
                           encoding="utf-8"))
    runner = UseCaseRunner("ultra_short", cache=cache, asof="2026-08-15", period="ultra_short")
    res = runner.run()
    print("方向:", res["direction"])
    print("候选池:", len(res["candidates"]))
    print("订单:", [(o["secid"], cache.get(o["secid"], {}).get("name", ""), o["score"])
                    for o in res["orders"]])
    print("降级记录:", len(res["notes"]))
