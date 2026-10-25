# -*- coding: utf-8 -*-
"""
三层周期 SSA 稳态做T引擎 · Python 忠实移植
==========================================
源：app/src/main/java/com/chin/stockanalysis/strategy/ssa/SsaT0Engine.kt（974行）
    + SsaEngine.kt 的 decompose（标准SSA四步）

移植口径（与 Kotlin 逐行对齐）：
- decompose：Hankel 轨迹矩阵 X(K×L, X[i][j]=x[i+j]) → Gram=XᵀX → 特征分解
  （Kotlin 用 Jacobi，此处用 numpy eigh；特征向量符号在秩1重构 a·vᵀ 中抵消，结果等价）
  → 分组 g0={0}=trend、g1={1,2}=cycle、noise=x−trend−cycle → 对角平均（含 k=N-1 修复版）。
- buildLayer：固定窗口 M 滚动；t 收盘分解 → trend[t]/cycle[t]；σ=noise 尾 N 根
  样本标准差（单遍算法, ddof=1, 与 Kotlin tailStd 同式）；mid/bands 写入 t+1（供下一根用）。
- regimes：cy[t]=mid[t]−trend[t]（注意 mid 是 t-1 推断、trend 是 t 收盘，照原样）；
  cycle 拐点开/关窗；1=UP 2=FLAT 3=WINDOW。
- simulate（默认池参数路径：cyRule/winHalf/gateAdx/reduceMode/volTarget 全关）：
  t 收盘决策 → t+1 开盘成交；UP 核心仓=权益×80%、超极限卖T=持仓×30%、破极限买回；
  WINDOW 上穿中位满仓(INITIAL×100%)买、触正常上沿全卖、状态切换全平；
  FLAT 破极限下沿满仓买T、超极限上沿卖T。整手100股，FIFO 配对成T单。
  费用：买 (1+slip)(1+comm)，卖 (1−slip)(1−comm−stamp)；comm=2.5e-4 stamp=5e-4 slip=5e-4。

⚠️ 数据适配（缓存只有收盘价）：open[t]=close[t-1]（无未来函数），high/low 由 open/close
合成（仅周/日聚合与统计用，默认决策路径不用 high/low）。周K不足 windowM+10 时放宽
（周层只影响 trade 的 weeklyUpAtOpen 标签，不影响权益）。

对比基线：BBI12 拐点策略（_bbi_er_vs_ssa.run_strategy，FEE=0.0013）。
"""
import json
import math
import os
import sys
from collections import deque
from datetime import datetime

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CACHE = os.path.join(HERE, "data", "_kalman_1h_cache.json")
REPORT = os.path.join(ROOT, "reports", "t0_vs_bbi12.html")
RESULTS = os.path.join(HERE, "data", "_t0_pool_results.json")

INITIAL = 100_000.0


# ═══════════════════════════════ SSA 分解（= SsaEngine.decompose）═══
def _diag_avg(Y, ns, K, L):
    """Hankel 对角平均：out[kk]=mean{Y[i,kk-i]}, i∈[max(0,kk-L+1), min(kk,K-1)]。"""
    F = np.fliplr(Y)  # F[i, L-1-(kk-i)] → 反对角线 kk = F 的对角线 offset=L-1-kk
    out = np.empty(ns)
    for kk in range(ns):
        out[kk] = np.diagonal(F, offset=L - 1 - kk).mean()
    return out


def decompose(x, L):
    """标准 SSA 四步，返回 (trend, cycle, noise)。与 Kotlin decompose 同口径。"""
    x = np.asarray(x, float)
    ns = len(x)
    K = ns - L + 1
    X = np.lib.stride_tricks.sliding_window_view(x, L)  # K×L, X[i][j]=x[i+j]
    G = X.T @ X
    eigvals, V = np.linalg.eigh(G)          # 升序；V[:, j] 为特征向量
    order = np.argsort(eigvals)[::-1]       # 降序（= Kotlin sortedByDescending）
    sigma = np.sqrt(np.maximum(eigvals, 0.0))

    def reconstruct(ranks):
        Y = np.zeros((K, L))
        for r in ranks:
            orig = order[r]
            if sigma[orig] < 1e-12:
                continue
            v = V[:, orig]
            a = X @ v
            Y += np.outer(a, v)
        return _diag_avg(Y, ns, K, L)

    trend = reconstruct([0])
    cycle = reconstruct([1, 2]) if L >= 3 else np.zeros(ns)
    noise = x - trend - cycle
    return trend, cycle, noise


def tail_std(v, n):
    """Kotlin T0Engine.tailStd 单遍算法：尾 n 个有效值样本标准差(ddof=1)。"""
    tail = v[-n:] if len(v) > n else v
    tail = tail[np.isfinite(tail)]
    cnt = len(tail)
    if cnt < 2:
        return 0.0
    s = float(tail.sum())
    s2 = float((tail * tail).sum())
    mean = s / cnt
    vari = (s2 - cnt * mean * mean) / (cnt - 1)
    return math.sqrt(vari) if vari > 0 else 0.0


def rsi(closes, period=14):
    """Wilder RSI，返回等长数组（前 period+1 根为 nan）。"""
    n = len(closes)
    out = np.full(n, np.nan)
    if n <= period:
        return out
    diff = np.diff(closes)
    gain = np.where(diff > 0, diff, 0.0)
    loss = np.where(diff < 0, -diff, 0.0)
    # Wilder 平滑
    avg_gain = gain[:period].mean()
    avg_loss = loss[:period].mean()
    out[period] = 100.0 - 100.0 / (1.0 + (avg_gain / avg_loss if avg_loss > 0 else 1e12))
    for i in range(period, n - 1):
        avg_gain = (avg_gain * (period - 1) + gain[i]) / period
        avg_loss = (avg_loss * (period - 1) + loss[i]) / period
        rs = avg_gain / avg_loss if avg_loss > 0 else 1e12
        out[i + 1] = 100.0 - 100.0 / (1.0 + rs)
    return out


def adx(closes, period=14):
    """标准 ADX（Wilder），返回等长数组（前 2*period 根为 nan）。"""
    n = len(closes)
    out = np.full(n, np.nan)
    if n <= 2 * period:
        return out
    high = closes.copy()
    low = closes.copy()
    # 缓存只有 close；用 close 近似 high/low（决策路径用 ADX 趋势强度，近似可接受）
    tr = np.zeros(n - 1)
    plus_dm = np.zeros(n - 1)
    minus_dm = np.zeros(n - 1)
    for i in range(1, n):
        up = high[i] - high[i - 1]
        dn = low[i - 1] - low[i]
        plus_dm[i - 1] = up if (up > dn and up > 0) else 0.0
        minus_dm[i - 1] = dn if (dn > up and dn > 0) else 0.0
        tr[i - 1] = max(high[i] - low[i], abs(high[i] - closes[i - 1]), abs(low[i] - closes[i - 1]))
    # Wilder 平滑
    atr = tr[:period].sum()
    pdm = plus_dm[:period].sum()
    mdm = minus_dm[:period].sum()
    dx = np.zeros(n - 1)
    for i in range(period - 1, n - 1):
        if i > period - 1:
            atr = atr - atr / period + tr[i]
            pdm = pdm - pdm / period + plus_dm[i]
            mdm = mdm - mdm / period + minus_dm[i]
        pdi = 100.0 * pdm / atr if atr > 0 else 0.0
        mdi = 100.0 * mdm / atr if atr > 0 else 0.0
        dx[i] = 100.0 * abs(pdi - mdi) / (pdi + mdi) if (pdi + mdi) > 0 else 0.0
    # ADX = Wilder 平滑 dx
    adx_val = dx[period - 1:2 * period - 1].mean()
    out[2 * period - 1] = adx_val
    for i in range(2 * period - 1, n - 1):
        adx_val = (adx_val * (period - 1) + dx[i]) / period
        out[i + 1] = adx_val
    return out


# ═══════════════════════════════ 参数 ═══════════════════════════════
class LayerCfg:
    def __init__(self, embed_l, window_m, lookback_n, cycle_mult=1.5, noise_mult=2.0):
        self.embed_l = embed_l
        self.window_m = window_m
        self.lookback_n = lookback_n
        self.cycle_mult = cycle_mult
        self.noise_mult = noise_mult


class Params:
    """= SsaT0PoolStore.PoolMember 默认：cyRule/winHalf/gateAdx 全 false。"""
    def __init__(self):
        self.week = LayerCfg(20, 80, 12)
        self.day = LayerCfg(30, 120, 20)
        self.hour = LayerCfg(30, 120, 20)
        self.core_pct = 80.0
        self.t_pct = 30.0
        self.commission = 0.00025
        self.stamp_duty = 0.0005
        self.slippage = 0.0005
        self.train_ratio = 0.70
        self.win_stop_dn = 0          # 0=关闭(基线)
        self.win_pos_pct = 100.0
        # UP段中位线减仓变体（用户提议验证）：下穿mid→减至 up_cut_keep%持仓，回穿mid→回补核心仓
        self.up_mid_cut = False
        self.up_cut_keep = 20.0
        # cycle 拐点规则（替代三态机）：用 cycle 拐点驱动段方向，而非 cycle 符号
        self.use_cycle_rule = False
        self.cy_confirm = 1            # 1=3点反转 2=4点单调
        self.cy_up_leg = True          # 上升段：谷底满仓/峰顶全清
        self.cy_down_leg = True        # 下降段：限定条件买入
        self.cy_mid_rising_k = 3       # 下降段买入需中位线最近K根上行
        self.cy_layer = "H"            # H=小时线 D=日线
        self.cy_exit_on_zero = False   # 上升段 cycle 下穿0也清仓
        # 波动自适应卖T量 + 日线下行降仓
        self.vol_target_t = False      # 卖T量 = tPct × min(1, σ_ref/σ_t)
        self.sigma_ref_abs = 0.0       # σ_ref，0=取全样本σ中位数
        self.reduce_mode = 2           # 0=基线 1=冻结核心仓 2=日线下行核心仓折半（默认采纳）
        # UP段做T增强（三个方向，独立开关）
        self.t_rsi_confirm = False     # 方向4：触eUp卖T需RSI(14)>70确认
        self.t_rsi_period = 14
        self.t_rsi_thr = 70.0
        self.adx_gate = False          # 方向3：ADX动态核心仓+卖T量
        self.adx_period = 14
        self.t_buy_trend_filter = False  # 方向5：破eDn买回需trend仍上行
        # BBI12 趋势闸门：BBI12 空仓(下降趋势)时限制 T0 做T
        self.bbi_gate = 0             # 0=关 1=少做T(跳过eUp/eDn) 2=空仓
        # ER70+Δ5ER 入场闸：全池ER分位<gate 或 Δ5ER<=-0.05 时禁止UP段开新仓
        self.er_gate = 0              # 0=关，否则如70
        self.er_d5 = -0.05
        self.er_pct_arr = None        # 横截面ER分位（main预计算传入）
        self.er_arr = None            # 个股原始ER序列
        # 时变路由：er_ok时段走T0做T，非er_ok时段切BBI12拐点波段（满仓/空仓）
        self.route = False


# ═══════════════════════════════ 单层滚动 SSA（= buildLayer）═══
def build_layer(closes, cfg):
    n = len(closes)
    M, L, N = cfg.window_m, cfg.embed_l, cfg.lookback_n
    nan = np.full(n, np.nan)
    trend, cycle, mid = nan.copy(), nan.copy(), nan.copy()
    nUp, nDn, eUp, eDn = nan.copy(), nan.copy(), nan.copy(), nan.copy()
    for t in range(M - 1, n):
        seg = closes[t - M + 1:t + 1]
        tr, cy, no = decompose(seg, L)
        trend[t] = tr[-1]
        cycle[t] = cy[-1]
        sigma = tail_std(no, N)
        if t + 1 < n:
            m = tr[-1] + cy[-1]
            mid[t + 1] = m
            nUp[t + 1] = m + cfg.cycle_mult * sigma
            nDn[t + 1] = m - cfg.cycle_mult * sigma
            eUp[t + 1] = m + cfg.noise_mult * sigma
            eDn[t + 1] = m - cfg.noise_mult * sigma
    return {"trend": trend, "cycle": cycle, "mid": mid,
            "nUp": nUp, "nDn": nDn, "eUp": eUp, "eDn": eDn}


# ═══════════════════════════════ 三态机（= regimes）═══
def regimes(cy):
    n = len(cy)
    reg = np.zeros(n, dtype=int)
    window = False
    for i in range(n):
        if i < 2 or not (np.isfinite(cy[i]) and np.isfinite(cy[i - 1]) and np.isfinite(cy[i - 2])):
            continue
        if cy[i - 2] < cy[i - 1] and cy[i - 1] > cy[i] and cy[i - 1] > 0:
            window = False
        if cy[i - 2] > cy[i - 1] and cy[i - 1] < cy[i] and cy[i - 1] < 0:
            window = True
        reg[i] = 1 if cy[i] > 0 else (3 if window else 2)
    return reg


# ═══════════════════════════════ K线聚合 ═══════════════════════════════
def aggregate_days(hbars):
    """1H bars[(date,time,o,h,l,c)] → 日K [(date,'',o,h,l,c)]"""
    out = []
    cur = None
    for b in hbars:
        d = b[0]
        if d != cur:
            if cur is not None:
                out.append((cur, "", o, hi, lo, c))
            cur = d
            o, hi, lo, c = b[2], b[3], b[4], b[5]
        else:
            hi = max(hi, b[3]); lo = min(lo, b[4]); c = b[5]
    if cur is not None:
        out.append((cur, "", o, hi, lo, c))
    return out


def _iso_key(yyyymmdd):
    dt = datetime.strptime(yyyymmdd, "%Y%m%d")
    iso = dt.isocalendar()
    return iso[0] * 100 + iso[1]


def aggregate_weekly(dbars):
    """ISO 周聚合（= Kotlin aggregateWeekly，周K日期=该周最后交易日）"""
    out = []
    cur = -1
    for b in dbars:
        key = _iso_key(b[0])
        if key != cur:
            if cur >= 0:
                out.append((last_date, "", o, hi, lo, c))
            cur = key
            o, hi, lo, c = b[2], b[3], b[4], b[5]
            last_date = b[0]
        else:
            hi = max(hi, b[3]); lo = min(lo, b[4]); c = b[5]
            last_date = b[0]
    if cur >= 0:
        out.append((last_date, "", o, hi, lo, c))
    return out


def count_weeks(hbars, frm, to):
    s = set()
    for i in range(frm, to):
        s.add(_iso_key(hbars[i][0]))
    return max(len(s), 1)


# ═══════════════════════════════ 交易模拟（= simulate 基线路径）═══
def _lots(capital, price):
    if price <= 0:
        return 0
    return max(int(math.floor(capital / price / 100.0)) * 100, 0)


def simulate(hbars, h, d, w, reg, p):
    n = len(hbars)
    closes = np.array([b[5] for b in hbars])

    def buy_cost(q, px):
        return q * px * (1 + p.slippage) * (1 + p.commission)

    def sell_proceeds(q, px):
        return q * px * (1 - p.slippage) * (1 - p.commission - p.stamp_duty)

    def week_idx_for(t):
        hd, tm = hbars[t][0], hbars[t][1]
        wi = -1
        for i, wb in enumerate(w["bars"]):
            if wb[0] < hd or (wb[0] == hd and tm >= "15:00"):
                wi = i
            else:
                break
        return wi

    def day_idx_for(t):
        hd, tm = hbars[t][0], hbars[t][1]
        di = -1
        for i, db in enumerate(d["bars"]):
            if db[0] < hd or (db[0] == hd and tm >= "15:00"):
                di = i
            else:
                break
        return di

    # 暖机起点
    start = 0
    for i in range(n):
        if reg[i] != 0 and np.isfinite(h["mid"][i]) and np.isfinite(h["eUp"][i]):
            start = i
            break
    bench_base = hbars[start][2] if hbars[start][2] > 0 else hbars[start][5]

    # ── cycle 拐点信号预计算（仅 use_cycle_rule 启用；无未来函数）──
    use_day_cy = p.use_cycle_rule and (p.cy_layer.upper() == "D")
    valley_sig = np.zeros(n, dtype=bool)
    peak_sig = np.zeros(n, dtype=bool)
    if p.use_cycle_rule:
        need = 3 if p.cy_confirm >= 2 else 2
        def cy_at(t):
            if not use_day_cy:
                return h["cycle"][t] if 0 <= t < n else np.nan
            di = day_idx_for(t)
            return d["cycle"][di] if 0 <= di < len(d["cycle"]) else np.nan
        if not use_day_cy:
            cy = h["cycle"]
            for s in range(need, n):
                if not (np.isfinite(cy[s]) and np.isfinite(cy[s - 1]) and np.isfinite(cy[s - 2])):
                    continue
                if p.cy_confirm >= 2:
                    if s < 3 or not np.isfinite(cy[s - 3]):
                        continue
                    if cy[s - 3] > cy[s - 2] and cy[s - 2] < cy[s - 1] and cy[s - 1] < cy[s]:
                        valley_sig[s] = True
                    if cy[s - 3] < cy[s - 2] and cy[s - 2] > cy[s - 1] and cy[s - 1] > cy[s]:
                        peak_sig[s] = True
                else:
                    if cy[s - 2] > cy[s - 1] and cy[s - 1] < cy[s]:
                        valley_sig[s] = True
                    if cy[s - 2] < cy[s - 1] and cy[s - 1] > cy[s]:
                        peak_sig[s] = True
        else:
            dn = len(d["cycle"])
            dy = d["cycle"]
            dv = np.zeros(dn, dtype=bool); dp = np.zeros(dn, dtype=bool)
            for s in range(need, dn):
                if not (np.isfinite(dy[s]) and np.isfinite(dy[s - 1]) and np.isfinite(dy[s - 2])):
                    continue
                if p.cy_confirm >= 2:
                    if s < 3 or not np.isfinite(dy[s - 3]):
                        continue
                    if dy[s - 3] > dy[s - 2] and dy[s - 2] < dy[s - 1] and dy[s - 1] < dy[s]:
                        dv[s] = True
                    if dy[s - 3] < dy[s - 2] and dy[s - 2] > dy[s - 1] and dy[s - 1] > dy[s]:
                        dp[s] = True
                else:
                    if dy[s - 2] > dy[s - 1] and dy[s - 1] < dy[s]:
                        dv[s] = True
                    if dy[s - 2] > dy[s - 1] and dy[s - 1] > dy[s]:
                        dp[s] = True
            last_di = -999
            for t in range(n):
                di = day_idx_for(t)
                if di != last_di:
                    if 0 <= di < dn:
                        valley_sig[t] = dv[di]; peak_sig[t] = dp[di]
                    last_di = di
        # 段方向初值：回看最近一个已确认拐点，找不到则按 cycle 符号
        def cy_at0(t):
            if not use_day_cy:
                return h["cycle"][t] if 0 <= t < n else np.nan
            di = day_idx_for(t)
            return d["cycle"][di] if 0 <= di < len(d["cycle"]) else np.nan
        base_cy = cy_at0(start)
        leg_up = bool(np.isfinite(base_cy) and base_cy > 0.0)
        for i in range(start, 2, -1):
            if valley_sig[i]:
                leg_up = True; break
            if peak_sig[i]:
                leg_up = False; break
    else:
        leg_up = False
    down_entry = 0.0

    # ── 波动自适应卖T量：σ_ref = 全样本 σ 中位数（sigma_ref_abs>0 时直接用）──
    cm = p.hour.cycle_mult
    def sigma_at(t):
        if 0 <= t < len(h["nUp"]) and cm > 0 and np.isfinite(h["nUp"][t]) and np.isfinite(h["mid"][t]):
            return (h["nUp"][t] - h["mid"][t]) / cm
        return np.nan
    if p.vol_target_t:
        if p.sigma_ref_abs > 0:
            sigma_ref = p.sigma_ref_abs
        else:
            sigs = [sigma_at(i) for i in range(n)]
            sigs = [s for s in sigs if np.isfinite(s) and s > 0]
            sigs.sort()
            sigma_ref = sigs[len(sigs) // 2] if sigs else 0.0
    else:
        sigma_ref = 0.0

    def vol_f(t):
        if not p.vol_target_t or sigma_ref <= 0:
            return 1.0
        s = sigma_at(t)
        if not np.isfinite(s) or s <= 0:
            return 1.0
        return min(1.0, sigma_ref / s)

    def day_down_at(t):
        di = day_idx_for(t)
        if di < 5:
            return False
        a, b = d["trend"][di], d["trend"][di - 5]
        return bool(np.isfinite(a) and np.isfinite(b) and a < b)

    # RSI / ADX 预计算（仅相关开关启用时）
    rsi_arr = rsi(closes, p.t_rsi_period) if p.t_rsi_confirm else None
    adx_arr = adx(closes, p.adx_period) if p.adx_gate else None

    # BBI12 持仓状态（多=上升趋势 / 空=下降趋势），无未来函数（拐点确认滞后1根）
    bbi_pos = None
    if p.bbi_gate > 0 or p.route:
        from _bbi_er_vs_ssa import bbi12_line, pivots
        s = bbi12_line(closes)
        pv = dict(pivots(s))
        bbi_pos = np.zeros(n, dtype=bool)
        cur = False
        for t in range(n):
            if t in pv:
                cur = pv[t]
            bbi_pos[t] = cur

    # ER 入场闸（仅UP段买入）：er_pct>=gate 且 Δ5ER>d5 才允许买；无未来函数（t收盘值）
    er_ok = None
    if p.er_gate > 0 and p.er_pct_arr is not None and p.er_arr is not None:
        ep, ea = p.er_pct_arr, p.er_arr
        er_ok = np.zeros(n, dtype=bool)
        for t in range(n):
            if t >= len(ep) or not np.isfinite(ep[t]) or ep[t] < p.er_gate:
                continue                      # 分位不足 → 禁买
            if (t >= 5 and t < len(ea) and np.isfinite(ea[t]) and np.isfinite(ea[t - 5])
                    and ea[t] - ea[t - 5] <= p.er_d5):
                continue                      # Δ5ER 自由落体 → 禁买
            er_ok[t] = True

    cash = INITIAL
    sh = 0
    queue = deque()
    xt = False
    up_cut = False
    trades = []
    markers = []

    def label(b):
        d0 = b[0]
        return f"{d0[:4]}-{d0[4:6]}-{d0[6:8]} {b[1]}"

    def buy_to(exec_i, fill, target, reason):
        nonlocal cash, sh
        td = target - sh
        need = (td // 100) * 100 if td > 0 else 0   # Kotlin: (td/100)*100, need<=0 → return
        if need <= 0:
            return
        q = min(need, _lots(cash, fill))
        while q > 0 and buy_cost(q, fill) > cash:
            q -= 100
        if q <= 0:
            return
        cash -= buy_cost(q, fill)
        queue.append([exec_i, fill, q])
        sh += q
        markers.append((exec_i - 1, closes[exec_i - 1], True, reason))

    def sell_qty(exec_i, fill, want, reason):
        nonlocal cash, sh
        q = (min(want, sh) // 100) * 100
        if q <= 0:
            return
        cash += sell_proceeds(q, fill)
        sh -= q
        remain = q
        while remain > 0 and queue:
            lot = queue[0]
            take = min(lot[2], remain)
            wi = week_idx_for(lot[0])
            weekly_up = (w["trend"][wi] >= w["trend"][wi - 1]) if wi >= 1 else True
            di = day_idx_for(lot[0])
            day_up = (d["trend"][di] >= d["trend"][di - 5]) if di >= 5 else True
            cost0 = buy_cost(take, lot[1])
            trades.append({
                "openIndex": lot[0], "openTime": label(hbars[lot[0]]), "openPrice": lot[1],
                "closeIndex": exec_i, "closeTime": label(hbars[exec_i]), "closePrice": fill,
                "returnPct": (sell_proceeds(take, fill) - cost0) / cost0 * 100,
                "barsHeld": exec_i - lot[0],
                "weeklyUpAtOpen": bool(weekly_up), "dayUpAtOpen": bool(day_up),
                "exitReason": reason,
            })
            if take >= lot[2]:
                queue.popleft()
            else:
                lot[2] -= take
            remain -= take
        markers.append((exec_i - 1, closes[exec_i - 1], False, reason))

    equity = np.full(n, INITIAL)
    bench = np.full(n, INITIAL)

    mid_a, nUp_a, eUp_a, eDn_a = h["mid"], h["nUp"], h["eUp"], h["eDn"]
    for t in range(start, n - 1):
        fill = hbars[t + 1][2]          # t+1 开盘价
        mid, nUp, eUp, eDn = mid_a[t], nUp_a[t], eUp_a[t], eDn_a[t]
        if fill > 0 and np.isfinite(mid) and np.isfinite(nUp) and np.isfinite(eUp) and np.isfinite(eDn):
            ci = closes[t]
            prev_mid = mid_a[t - 1]
            cross_up = np.isfinite(prev_mid) and closes[t - 1] < prev_mid and ci >= mid
            if p.route and er_ok is not None and not er_ok[t]:
                # ── 时变路由·波段模式：跟 BBI12 拐点持仓状态（满仓/空仓）──
                if bbi_pos[t] and sh == 0:
                    buy_to(t + 1, fill, _lots(cash + sh * ci, fill), "路由·波段买")
                elif not bbi_pos[t] and sh > 0:
                    sell_qty(t + 1, fill, sh, "路由·波段卖")
            elif p.use_cycle_rule:
                # ── cycle 拐点规则：谷底/峰顶驱动段方向 ──
                full_sh = _lots(cash + sh * ci, fill)
                low_rev = t >= 2 and hbars[t - 2][4] > hbars[t - 1][4] and hbars[t - 1][4] < hbars[t][4]
                mid_rising = p.cy_mid_rising_k <= 0 or (
                    t - p.cy_mid_rising_k >= 0 and np.isfinite(mid_a[t - p.cy_mid_rising_k])
                    and mid > mid_a[t - p.cy_mid_rising_k])
                if valley_sig[t]:                       # cycle 谷底 → 上升段
                    leg_up = True; down_entry = 0.0
                    if p.cy_up_leg and sh < full_sh:
                        buy_to(t + 1, fill, full_sh, "cycle谷底·满仓买入")
                elif peak_sig[t]:                      # cycle 峰顶 → 下降段全清
                    leg_up = False; down_entry = 0.0
                    if p.cy_up_leg and sh > 0:
                        sell_qty(t + 1, fill, sh, "cycle峰顶·全部卖出")
                elif leg_up:                           # 上升段：维持满仓
                    cy_now = h["cycle"][t]; cy_prev = h["cycle"][t - 1]
                    if (p.cy_exit_on_zero and cy_now < 0.0
                            and np.isfinite(cy_prev) and cy_prev >= 0.0):
                        leg_up = False; down_entry = 0.0
                        if sh > 0:
                            sell_qty(t + 1, fill, sh, "上升段cycle下穿0·清仓")
                    elif p.cy_up_leg and sh < full_sh:
                        why = "上升段低点反转·补满" if low_rev else "上升段回中线·补满"
                        buy_to(t + 1, fill, full_sh, why)
                else:                                  # 下降段：限定条件买入
                    if sh == 0:
                        if p.cy_down_leg and mid_rising and cross_up:
                            buy_to(t + 1, fill, _lots(INITIAL, fill), "下降段上穿中线·买入")
                            down_entry = fill
                        elif p.cy_down_leg and low_rev:
                            buy_to(t + 1, fill, _lots(INITIAL, fill), "下降段低点反转·买入")
                            down_entry = fill
                    else:
                        if ci >= eUp:
                            sell_qty(t + 1, fill, sh, "下降段触极限·卖出"); down_entry = 0.0
                        elif down_entry > 0 and ci <= down_entry:
                            sell_qty(t + 1, fill, sh, "下降段回买入点·卖出"); down_entry = 0.0
                        elif ci < mid:
                            sell_qty(t + 1, fill, sh, "下降段回中位线·卖出"); down_entry = 0.0
            elif reg[t] == 1:
                # ── UP：核心做多 + 极限带反向T（基线 reduceMode=0）──
                xt = False
                day_dn = day_down_at(t) if p.reduce_mode == 2 else False
                core_pct_eff = p.core_pct * 0.5 if day_dn else p.core_pct
                core_shares = _lots((cash + sh * ci) * core_pct_eff / 100.0, fill)
                vf = vol_f(t)
                sell_q = int(sh * p.t_pct / 100.0 * vf)
                if p.up_mid_cut:
                    # 变体：下穿中位线减仓至 up_cut_keep%，回穿中位线回补核心仓
                    cross_dn = np.isfinite(prev_mid) and closes[t - 1] >= prev_mid and ci < mid
                    if cross_dn and sh > 0:
                        keep = int(sh * p.up_cut_keep / 100.0 / 100.0) * 100
                        if sh - keep >= 100:
                            sell_qty(t + 1, fill, sh - keep, "UP下穿中位·减仓")
                        up_cut = True
                    if up_cut:
                        if cross_up:
                            buy_to(t + 1, fill, core_shares, "UP回穿中位·回补")
                            up_cut = False
                    elif sh < core_shares:
                        buy_to(t + 1, fill, core_shares, "UP建仓/回补")
                    elif ci > eUp:
                        sell_qty(t + 1, fill, sell_q, "UP超上限卖T")
                    elif ci < eDn:
                        buy_to(t + 1, fill, core_shares, "UP破下限买T")
                else:
                    # BBI12 趋势闸门
                    bbi_flat = (bbi_pos is not None) and (not bbi_pos[t])
                    # ER 闸：只拦买入，不拦卖T
                    er_block = er_ok is not None and not er_ok[t]
                    # ADX 动态核心仓 + 卖T量缩放（方向3）
                    adx_val = adx_arr[t] if adx_arr is not None else np.nan
                    if p.adx_gate and np.isfinite(adx_val):
                        if adx_val > 25.0:          # 强趋势：重仓少做T
                            core_pct_eff = min(core_pct_eff, 95.0)
                            t_scale = 0.5
                        elif adx_val < 20.0:        # 震荡：轻仓多做T
                            core_pct_eff = min(core_pct_eff, 60.0)
                            t_scale = 2.0
                        else:
                            t_scale = 1.0
                        core_shares = _lots((cash + sh * ci) * core_pct_eff / 100.0, fill)
                        sell_q = int(sh * p.t_pct / 100.0 * vf * t_scale)
                    if bbi_flat and p.bbi_gate >= 2 and sh > 0:
                        # BBI12 空仓 → T0 空仓
                        sell_qty(t + 1, fill, sh, "BBI12下降·空仓")
                    elif bbi_flat and p.bbi_gate == 1:
                        # BBI12 空仓 → 少做T：只维持核心仓，跳过 eUp卖T/eDn买T
                        if sh < core_shares and not er_block:
                            buy_to(t + 1, fill, core_shares, "UP建仓/回补")
                    elif sh < core_shares:
                        if not er_block:
                            buy_to(t + 1, fill, core_shares, "UP建仓/回补")
                    elif ci > eUp:
                        # RSI 超买确认才卖T（方向4）
                        if not p.t_rsi_confirm or (
                                rsi_arr is not None and np.isfinite(rsi_arr[t])
                                and rsi_arr[t] > p.t_rsi_thr):
                            sell_qty(t + 1, fill, sell_q, "UP超上限卖T")
                    elif ci < eDn:
                        # 买回需趋势仍上行（方向5）
                        trend_ok = True
                        if p.t_buy_trend_filter:
                            trend_ok = (np.isfinite(h["trend"][t]) and np.isfinite(h["trend"][t - 1])
                                        and h["trend"][t] > h["trend"][t - 1])
                        if trend_ok and not er_block:
                            buy_to(t + 1, fill, core_shares, "UP破下限买T")
            elif reg[t] == 3:
                # ── WINDOW：上穿中位买 / 触正常上沿卖 /（winStopDn=0 关闭）──
                xt = False
                up_cut = False
                if reg[t - 1] != 3 and sh > 0:
                    sell_qty(t + 1, fill, sh, "中位线下穿趋势线·全平")
                elif sh == 0 and cross_up:
                    buy_to(t + 1, fill, _lots(INITIAL * p.win_pos_pct / 100.0, fill),
                           "WINDOW上穿中位·买入")
                elif sh > 0 and ci >= nUp:
                    sell_qty(t + 1, fill, sh, "WINDOW触正常上沿·卖出")
            else:
                # ── FLAT：空仓，只做极限突破T ──
                up_cut = False
                if sh > 0 and not xt:
                    sell_qty(t + 1, fill, sh, "FLAT峰顶回落·清仓")
                elif sh == 0 and ci < eDn:
                    buy_to(t + 1, fill, _lots(INITIAL, fill), "FLAT破极限·买T")
                    xt = True
                elif xt and sh > 0 and ci > eUp:
                    sell_qty(t + 1, fill, sh, "FLAT超极限·卖T")
                    xt = False
        equity[t] = cash + sh * closes[t]
        bench[t] = INITIAL * (closes[t] / bench_base)

    last = n - 1
    if last >= start:
        equity[last] = cash + sh * closes[last]
        bench[last] = INITIAL * (closes[last] / bench_base)

    return {"equity": equity, "bench": bench, "trades": trades,
            "markers": markers, "final_cash": cash, "final_shares": sh, "start": start}


# ═══════════════════════════════ 分段统计（= segmentStats 全样本）═══
def segment_stats(hbars, equity, bench, trades, frm, to):
    e0, e1 = equity[frm], equity[to - 1]
    total = (e1 / e0 - 1) * 100
    d0 = datetime.strptime(hbars[frm][0], "%Y%m%d")
    d1 = datetime.strptime(hbars[to - 1][0], "%Y%m%d")
    years = max((d1 - d0).days, 1) / 365.0
    annual = ((e1 / e0) ** (1.0 / years) - 1) * 100
    peak = e0
    mdd = 0.0
    for i in range(frm, to):
        if equity[i] > peak:
            peak = equity[i]
        dd = (equity[i] - peak) / peak
        if dd < mdd:
            mdd = dd
    seg = [t for t in trades if frm <= t["openIndex"] < to]
    wins = [t for t in seg if t["returnPct"] > 0]
    weeks = count_weeks(hbars, frm, to)
    b0, b1 = bench[frm], bench[to - 1]
    bp = (b1 / b0 - 1) * 100
    return {
        "bars": to - frm, "weeks": weeks,
        "totalReturnPct": total, "annualizedPct": annual, "maxDrawdownPct": mdd * 100,
        "tradeCount": len(seg), "winCount": len(wins),
        "winRatePct": (len(wins) / len(seg) * 100) if seg else 0.0,
        "avgPnlPct": (float(np.mean([t["returnPct"] for t in seg])) if seg else 0.0),
        "avgBarsHeld": (int(np.mean([t["barsHeld"] for t in seg])) if seg else 0),
        "tradesPerWeek": len(seg) / weeks,
        "benchReturnPct": bp, "excessPct": total - bp,
    }


# ═══════════════════════════════ 主入口（= SsaT0Engine.run）═══
def run_t0(hbars, p=None):
    """hbars: [(date yyyymmdd, time 'HH:MM', o, h, l, c), ...] 升序"""
    p = p or Params()
    closes = np.array([b[5] for b in hbars])
    dbars = aggregate_days(hbars)
    wbars = aggregate_weekly(dbars)

    h = build_layer(closes, p.hour)
    d = build_layer(np.array([b[5] for b in dbars]), p.day)
    w = build_layer(np.array([b[5] for b in wbars]), p.week) if len(wbars) > 0 else \
        {"trend": np.array([]), "bars": []}
    d["bars"] = dbars
    w["bars"] = wbars

    # regimeLayer="H"（基线）：cy = mid − trend
    cy = h["mid"] - h["trend"]
    reg = regimes(cy)
    sim = simulate(hbars, h, d, w, reg, p)
    n = len(hbars)
    full = segment_stats(hbars, sim["equity"], sim["bench"], sim["trades"], 0, n)
    hold_pct = (sim["final_shares"] * closes[-1] / sim["equity"][-1] * 100) \
        if sim["equity"][-1] > 0 else 0.0
    return {"params": p, "h": h, "d": d, "w": w, "regime": reg, "sim": sim,
            "full": full, "finalHoldingPct": hold_pct}


# ═══════════════════════════════ 数据适配 ═══════════════════════════════
def load_hour_bars(code):
    """缓存只有收盘价 → open[t]=close[t-1]，high/low 由 open/close 合成（默认决策路径不用）。"""
    cache = json.load(open(CACHE, encoding="utf-8"))
    v = cache[code]
    ds, cs = v["d"], v["c"]
    bars = []
    prev_c = cs[0]
    for i, s in enumerate(ds):
        d8 = s[:10].replace("-", "")
        tm = s[11:16]
        o = prev_c
        c = cs[i]
        bars.append((d8, tm, o, max(o, c), min(o, c), c))
        prev_c = c
    return bars


# ═══════════════════════════════ BBI12 基线（复用 _bbi_er_vs_ssa 口径）═══
def run_bbi12(closes):
    """= _bbi_er_vs_ssa.run_strategy(closes, bbi12_line)，返回 (total%, mdd%, n_tr, wr%)。"""
    from _bbi_er_vs_ssa import bbi12_line, run_strategy
    nav, n_tr, wr, total = run_strategy(closes, bbi12_line(closes))
    peak = np.maximum.accumulate(nav)
    mdd = float((nav / peak - 1).min()) * 100
    return total * 100, mdd, n_tr, (wr * 100 if wr is not None else None)


# ═══════════════════════════════ 池回测 + 对比报告 ═══════════════════════════════
def main():
    args = sys.argv[1:]
    midcut = "--midcut" in args
    cyrule = "--cy" in args
    volt = "--volt" in args
    rsi_on = "--rsi" in args
    adx_on = "--adx" in args
    tfilter = "--tfilter" in args
    bbi1 = "--bbi1" in args
    bbi2 = "--bbi2" in args
    ergate_v = next((a for a in args if a.startswith("--ergate")), None)
    ergate = ergate_v is not None
    ergate_lv = int(ergate_v.split("=")[1]) if ergate and "=" in ergate_v else 70
    route = "--route" in args
    erq = "--erq" in args
    only = next((a for a in args if a.isdigit()), None)
    cache = json.load(open(CACHE, encoding="utf-8"))
    codes = [c for c in cache if len(cache[c].get("c", [])) >= 200]
    pool_codes = codes                       # ER 分位需全池横截面（单票跑也用全池）
    if only:
        codes = [c for c in codes if c == only]
    parts = []
    if cyrule: parts.append("cyrule")
    if midcut: parts.append("midcut")
    if volt: parts.append("volt")
    if rsi_on: parts.append("rsi")
    if adx_on: parts.append("adx")
    if tfilter: parts.append("tfilter")
    if bbi1: parts.append("bbi1")
    if bbi2: parts.append("bbi2")
    if ergate: parts.append("ergate" if ergate_lv == 70 else f"ergate{ergate_lv}")
    if route: parts.append("route")
    if erq: parts.append("erq")
    tag = "_".join(parts) if parts else "base"
    print(f"[data] {len(codes)} 只 mode={tag} (reduce_mode=2 默认)", file=sys.stderr)

    er_pct = er_raw = None
    if ergate or route or erq:
        from _bbi_er_vs_ssa import er_series, ER_W
        cl_map = {c: np.array(cache[c]["c"], float) for c in pool_codes}
        nb = min(len(v) for v in cl_map.values())
        er_raw = {c: er_series(cl_map[c][:nb]) for c in pool_codes}
        if erq:
            # 自身时序滚动分位：er_pct[t] = 过去250根(不含t)中 ER < er[t] 的占比，≥60 有效样本
            WQ = 250
            er_pct = {}
            for c in pool_codes:
                e = er_raw[c]
                out = np.full(nb, np.nan)
                for t in range(ER_W, nb):
                    if not np.isfinite(e[t]):
                        continue
                    win = e[max(0, t - WQ):t]
                    win = win[np.isfinite(win)]
                    if len(win) < 60:
                        continue
                    out[t] = float((win < e[t]).sum() / len(win) * 100)
                er_pct[c] = out
            print(f"[erq] 自身时序分位预计算完成 W={WQ}", file=sys.stderr)
        else:
            # 全池横截面 ER 分位（口径 = _bbi_er_vs_ssa：er_series(w=60)，逐根分位）
            er_pct = {c: np.full(nb, np.nan) for c in pool_codes}
            for t in range(ER_W, nb):
                es = [(c, er_raw[c][t]) for c in pool_codes if np.isfinite(er_raw[c][t])]
                if not es:
                    continue
                arr = np.array([e for _, e in es])
                for c, e in es:
                    er_pct[c][t] = float((arr < e).sum() / len(arr) * 100)
            print(f"[er] 全池分位预计算完成 nb={nb}", file=sys.stderr)

    rows = []
    for i, c6 in enumerate(codes, 1):
        hbars = load_hour_bars(c6)
        closes = [b[5] for b in hbars]
        p = Params()
        p.up_mid_cut = midcut
        p.use_cycle_rule = cyrule
        p.vol_target_t = volt
        p.t_rsi_confirm = rsi_on
        p.adx_gate = adx_on
        p.t_buy_trend_filter = tfilter
        p.bbi_gate = 2 if bbi2 else (1 if bbi1 else 0)
        if ergate or route or erq:
            p.er_gate = 70 if erq else (ergate_lv if ergate else 70)
            p.er_pct_arr = er_pct[c6]
            p.er_arr = er_raw[c6]
            p.route = route
        r = run_t0(hbars, p)
        f = r["full"]
        b_tot, b_mdd, b_ntr, b_wr = run_bbi12(closes)
        rows.append({
            "code": c6,
            "t0_ret": f["totalReturnPct"], "t0_mdd": f["maxDrawdownPct"],
            "t0_trades": f["tradeCount"], "t0_wr": f["winRatePct"],
            "t0_annual": f["annualizedPct"], "t0_avgpnl": f["avgPnlPct"],
            "t0_hold": r["finalHoldingPct"], "bench": f["benchReturnPct"],
            "bbi_ret": b_tot, "bbi_mdd": b_mdd, "bbi_trades": b_ntr, "bbi_wr": b_wr,
        })
        if only or i % 20 == 0:
            print(f"[bt] {i}/{len(codes)} {c6} T0={f['totalReturnPct']:+.1f}% "
                  f"BBI={b_tot:+.1f}%", file=sys.stderr)

    out_json = RESULTS.replace(".json", f"_{tag}.json") if tag != "base" else RESULTS
    json.dump(rows, open(out_json, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"[save] → {out_json}", file=sys.stderr)
    if only:
        r0 = rows[0]
        print(json.dumps(r0, ensure_ascii=False, indent=1))
        return

    out_html = REPORT.replace(".html", f"_{tag}.html") if tag != "base" else REPORT
    build_report(rows, out_html)
    print(f"[done] → {out_html}", file=sys.stderr)


def _agg(rows, k):
    a = np.array([r[k] for r in rows], float)
    return float(a.mean()), float(np.median(a))


def build_report(rows, path=REPORT):
    t0_ret_m, t0_ret_md = _agg(rows, "t0_ret")
    t0_mdd_m, t0_mdd_md = _agg(rows, "t0_mdd")
    bbi_ret_m, bbi_ret_md = _agg(rows, "bbi_ret")
    bbi_mdd_m, bbi_mdd_md = _agg(rows, "bbi_mdd")
    t0_tr_m, _ = _agg(rows, "t0_trades")
    bbi_tr_m, _ = _agg(rows, "bbi_trades")
    t0_wr_m, _ = _agg(rows, "t0_wr")
    bbi_wr_vals = [r["bbi_wr"] for r in rows if r["bbi_wr"] is not None]
    bbi_wr_m = float(np.mean(bbi_wr_vals)) if bbi_wr_vals else 0.0
    t0_win_share = float(np.mean([1 if r["t0_ret"] > 0 else 0 for r in rows]) * 100)
    bbi_win_share = float(np.mean([1 if r["bbi_ret"] > 0 else 0 for r in rows]) * 100)

    r6 = next((r for r in rows if r["code"] == "600176"), None)

    def f2(v, sign="+"):
        return f"{v:{sign}.2f}"

    # 池级汇总表（BBI12 进取/稳健 = 今日实测同数据同费率，见会话记录）
    pool_rows = [
        ("SSA-T0 三层稳态（移植引擎）", f2(t0_ret_m), f2(t0_ret_md), f2(t0_mdd_m, ""),
         f"{t0_tr_m:.1f}", f"{t0_wr_m:.1f}%", f"{t0_win_share:.0f}%"),
        ("BBI12 原始（重跑）", f2(bbi_ret_m), f2(bbi_ret_md), f2(bbi_mdd_m, ""),
         f"{bbi_tr_m:.1f}", f"{bbi_wr_m:.1f}%", f"{bbi_win_share:.0f}%"),
        ("BBI12 进取·止盈8%（今日实测*）", "-3.02", "—", "-28.33", "—", "—", "—"),
        ("BBI12 稳健·+Δ5ER过滤（今日实测*）", "-0.18", "—", "-22.05", "15.6", "—", "—"),
    ]
    pool_html = "".join(
        f"<tr><td>{n}</td><td>{a}</td><td>{b}</td><td>{c}</td>"
        f"<td>{d}</td><td>{e}</td><td>{g}</td></tr>" for n, a, b, c, d, e, g in pool_rows)

    # 600176 单票表
    case_html = ""
    if r6:
        case_rows = [
            ("SSA-T0 三层稳态（移植引擎）", f2(r6["t0_ret"]), f2(r6["t0_mdd"], ""),
             str(r6["t0_trades"]), f"{r6['t0_wr']:.1f}%", f"{r6['t0_annual']:+.1f}%"),
            ("BBI12 原始（重跑）", f2(r6["bbi_ret"]), f2(r6["bbi_mdd"], ""),
             str(r6["bbi_trades"]), f"{r6['bbi_wr']:.1f}%" if r6["bbi_wr"] else "—", "—"),
            ("BBI12 进取·止盈8%（今日实测*）", "+235.65", "-18.31", "—", "—", "—"),
            ("BBI12 稳健·+Δ5ER过滤（今日实测*）", "+172.72", "-14.53", "—", "—", "—"),
        ]
        case_html = "".join(
            f"<tr><td>{n}</td><td>{a}</td><td>{b}</td><td>{c}</td><td>{d}</td><td>{e}</td></tr>"
            for n, a, b, c, d, e in case_rows)

    # T0 明细表（按收益降序）
    det = sorted(rows, key=lambda r: -r["t0_ret"])
    det_html = "".join(
        f"<tr><td>{r['code']}</td><td class=\"{'pos' if r['t0_ret']>0 else 'neg'}\">{r['t0_ret']:+.2f}</td>"
        f"<td>{r['t0_mdd']:.2f}</td><td>{r['t0_trades']}</td><td>{r['t0_wr']:.1f}</td>"
        f"<td class=\"{'pos' if r['bbi_ret']>0 else 'neg'}\">{r['bbi_ret']:+.2f}</td>"
        f"<td>{r['bbi_mdd']:.2f}</td><td>{r['bbi_trades']}</td>"
        f"<td>{r['bench']:+.2f}</td><td>{r['t0_hold']:.0f}%</td></tr>"
        for r in det)

    html = f"""<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SSA-T0 三层稳态做T vs BBI12 · 171池</title>
<style>
body{{background:#12141a;color:#d7dae0;font-family:system-ui,'Microsoft YaHei',sans-serif;margin:0;padding:18px}}
h1{{font-size:20px;margin:0 0 4px}} h2{{font-size:15px;margin:22px 0 8px;color:#8ab4f8}}
.sub{{color:#8a8f99;font-size:12px;margin-bottom:14px}}
table{{border-collapse:collapse;width:100%;font-size:12.5px}}
th,td{{border:1px solid #2a2e38;padding:5px 8px;text-align:right;white-space:nowrap}}
th{{background:#1c1f27;color:#9aa3b2;position:sticky;top:0}}
td:first-child,th:first-child{{text-align:left}}
.pos{{color:#e06c75}} .neg{{color:#98c379}}
.note{{color:#8a8f99;font-size:11.5px;margin-top:6px}}
.wrap{{max-height:560px;overflow:auto;border:1px solid #2a2e38}}
</style></head><body>
<h1>SSA-T0 三层稳态做T（Kotlin→Python 忠实移植）vs BBI12</h1>
<div class="sub">数据：171 只 1H（2025-09-19 → 2026-10-16，1023根）· T0 费率 佣2.5‱+印花5‱+滑点5‱双边 ·
BBI12 费率 FEE=0.0013 · 信号 t 收盘确认 t+1 开盘成交</div>

<h2>① 池级汇总（171 只）</h2>
<table><tr><th>策略</th><th>平均收益%</th><th>中位收益%</th><th>平均回撤%</th>
<th>平均交易数</th><th>平均胜率</th><th>盈利票占比</th></tr>{pool_html}</table>
<div class="note">* BBI12 进取/稳健为今日实测同池结果（仅池级口径）；BBI12 原始为本脚本重跑（每股可复算）。</div>

<h2>② 600176 单票</h2>
<table><tr><th>策略</th><th>总收益%</th><th>最大回撤%</th><th>交易数</th><th>胜率</th><th>年化%</th></tr>{case_html}</table>

<h2>③ T0 逐票明细（按 T0 收益降序）</h2>
<div class="wrap"><table><tr><th>代码</th><th>T0收益%</th><th>T0回撤%</th><th>T0交易</th><th>T0胜率%</th>
<th>BBI收益%</th><th>BBI回撤%</th><th>BBI交易</th><th>买入持有%</th><th>末仓</th></tr>{det_html}</table></div>
</body></html>"""
    open(path, "w", encoding="utf-8").write(html)


if __name__ == "__main__":
    main()
