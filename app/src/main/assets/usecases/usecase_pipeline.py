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
          "sector_relative_pe")
def _passthrough(ctx, node, inputs):
    """回测中无对应数据的 node：透传，记录降级。"""
    ctx.notes.append("[%s] %s 无回测数据，已降级为透传" % (node.id, node.module))
    return None


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
                 "requireAboveAllMAs"):
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
    pool = ctx.candidates or ctx.stock_pool or []
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
        except Exception:
            continue
    scored.sort(key=lambda x: -x[1])
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
    scored = (ctx.get("n_ancestral") or ctx.get("n_strict")
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
    out = []
    for cid, sc in scored:
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


@register("ancestral_rules")
def _ancestral_rules(ctx, node, inputs):
    scored = ctx.get("n_strict") or ctx.get("n_boost") or []
    ctx.stage("n_ancestral", scored)
    return scored


@register("smart_money_filter")
def _smart_money_filter(ctx, node, inputs):
    """主力资金过滤：无资金数据时取前 minScore 名排序。"""
    scored = (ctx.get("n_event") or ctx.get("n_ancestral")
              or ctx.get("n_boost") or [])
    scored = sorted(scored, key=lambda x: -x[1])[:50]
    ctx.stage("n_smart", scored)
    return scored


@register("ai_predict")
def _ai_predict(ctx, node, inputs):
    """AI 精选（回测近似，与 APK AIPredictNode 同语义）：
    仅当过滤后候选 >3 才收敛 top take（真实 APK 在此对 >3 候选调 LLM 精选取 top5）；
    ≤3 只全保留，不调用 AI。"""
    scored = ctx.get("n_smart") or ctx.get("n_ancestral") or ctx.get("n_boost") or []
    take = int(node.config.get("take", 5))
    top = scored if len(scored) <= 3 else scored[:take]
    ctx.stage("n_ai", top)
    return top


@register("generate_orders")
def _generate_orders(ctx, node, inputs):
    """买入订单生成：scored → orders。"""
    scored = ctx.get("n_ai") or []
    max_hold = int(node.config.get("maxHoldings", 5))
    ctx.orders = [{"secid": cid, "score": sc, "reason": "pipeline"}
                  for cid, sc in scored[:max_hold]]
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
    for j in range(len(snaps) - 1, -1, -1):
        if j >= slow and closes[j] > mf[j] > ms[j]:
            note = "沪深300 close(%.0f)>MA%d(%.0f)>MA%d(%.0f)" % (closes[j], fast, mf[j], slow, ms[j])
            return {"ok": True, "date": snaps[j]["date"], "note": note, "available": True}
    last = snaps[-1]["date"] if snaps else ""
    return {"ok": False, "date": last,
            "note": "沪深300 未满足 close>MA%d>MA%d 结构多头" % (fast, slow), "available": True}


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
               "dip_ok": dip_ok, "watch_ok": watch_ok}
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
