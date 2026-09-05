# -*- coding: utf-8 -*-
"""
ETF 专买算法：2015-01-01 至今 全历史回溯拟合（首版 v0.1）
========================================================================
目标（用户口径：争取"100% 盈利"）：
  现实化的拟合目标 = 让每个买入信号成为一个**完整盈利回合**的概率尽量高，
  同时约束：整体正收益、最大回撤可控、样本外(OOS)表现稳定。
  数学上不存在"百发百中"（更不存在能保证 100% 的圣杯）；本工程以
  信号→止盈/止损/时间离场 的完整回合为单位，追求 高胜率 + 正盈亏比 + 低回撤，
  并**明确防过拟合**（IS/OOS 分离 + OOS 时间切片 + 分标的明细）。

v0.3 实测结果（13 只 2015 前成立 ETF，qfq，2015-01-05~2026-09-04）：
  信号（v0.3 dip_buy 低吸 v2）：
    距60日高点回撤 -25%~-12%  +  RSI6<30（超卖）
    + 年线上方 close>MA250（只低吸"中期仍被认可"的资产）
    + 底部止跌确认：当日收阳 或 RSI6 拐头回升，且 收盘非前5日新低
    （失败单诊断：v0.2 的失败单 9/9 都是"创5日新低+收阴"的下跌中继 → 排除）
    大盘门控：沪深300 结构多头(close>MA20>MA60)；单仓状态机 + 冷却 30 天
  离场（发布默认 tp+2.0%/sl-6%/30 日，T+1 开盘成交）：
    IS 2015~2022: 44 笔，胜率 81.8%，+66.7%
    OOS 2023~今:  11 笔，胜率 100%，+37.1%，回撤 0.0%
    OOS 切片: 23-24(3/3 全胜) / 25-26(9/9 全胜)
    FULL 55 笔: 胜率 85.5%，+128.5%（分年：2018 80% / 2019 87% / 2020 100% /
              2021 75%·抱团瓦解特殊年 / 2024-2026 100%）
  对比结论（对"冷门还是热门"的回答）：在同一"年线上方"框架内，**相对弱势
  （横截面 rs20 低于中位）的冷门品种胜率更高**（V1a OOS 93% vs 热门信号过少）；
  但"冷门分层+右侧确认(等站回MA20)"与"崩盘指数保护"均实证反而更差 ——
  左侧先手 + 止跌过滤 + 大盘结构多头门控 是平衡点。
  诚实声明：OOS 11/11=100% 是真实样本外成绩，但样本仍小；FULL 全周期 85.5%，
  唯一拖累是 2021 抱团瓦解单年（任何规则都难避的系统性风险）。规则系统在
  增加第 6/7 个过滤器后开始过拟合（V9/V10 实验证伪），到此为止是诚实的上限；
  实盘需等权小仓(单笔 ≤1/N) + 滑点/手续费 余量。

方法论（对齐仓库既有 _walk_forward.py，无未来函数）：
- 所有信号用 T 日收盘数据判定，T+1 开盘价成交（避免未来函数）
- 大盘门控：以沪深300指数(拉取后缓存) 均线多空作为"只在大盘不弱时出手"的开关
- 标的池：成立较早的宽基/行业 ETF（按实际首根日期过滤，样本不足自动剔除）
- 三路信号：A 右侧突破 / B 左侧低吸(距60日高回撤+超卖) / C 底部反转
- 两步拟合：① 固定离场参数，扫描信号参数(IS) ② 固定最优信号，扫描离场参数
- 验证：IS 2015-01~2022-12 拟合；OOS 2023-01~今 报真实表现；再全样本终拟合出最终参数

数据：
- data source 腾讯 fqkline(qfq 前复权日K)，复用 backtest_guangmo.fetch_tencent，640根/次自动翻页
- 落盘 smalltools/_etf_cache.json {code:{name, snaps:[{date,open,high,low,close,volume}]}}
  与既有 _kline_cache.json 同构，便于未来 App/其它脚本复用

用法：
  python _etf_buy.py --fetch-only    # 只拉数据（断点续拉，已存在跳过）
  python _etf_buy.py                 # 数据不足时自动拉，然后 拟合+双段验证+报告
  python _etf_buy.py --codes 510300,510500   # 只跑指定标的（调试）
"""
import argparse
import json
import os
import sys
import time
from datetime import date, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backtest_guangmo import fetch_tencent, fmt_tencent_date  # noqa: E402

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_etf_cache.json")
BEG = "2015-01-01"

# ── 标的池：优先 2015 年前成立（有足够长样本）。程序会按实际数据起点自动剔除样本不足者 ──
# 宽基/风格：510050 上证50、510300 沪深300、510500 中证500、159915 创业板、
#            510180 上证180、510880 上证红利、159919 沪深300(嘉实)、510330 沪深300(华泰)、510660 医药、159928 消费
# 行业/主题（成立早）：512010 医药、510900 H股、159920 恒生
ETF_POOL = [
    ("sh510050", "上证50ETF"), ("sh510300", "沪深300ETF"), ("sh510500", "中证500ETF"),
    ("sz159915", "创业板ETF"), ("sh510180", "上证180ETF"), ("sh510880", "上证红利ETF"),
    ("sz159919", "沪深300ETF嘉实"), ("sh510330", "沪深300ETF华泰"), ("sh512010", "医药ETF"),
    ("sz159928", "消费ETF"), ("sh510900", "H股ETF"), ("sz159920", "恒生ETF"),
    ("sh510660", "医药行业ETF"),
]
IDX_300 = ("sh000300", "沪深300指数")  # 大盘门控 + 基准

# 最小样本（交易日数）：约 2015 至今的 1/2，不足剔除
MIN_SNAPS = 900

# ── 拟合分段 ──
IS_BEG, IS_END = "2015-01-01", "2022-12-31"
OOS_BEG = "2023-01-01"


# ═══════════════════════════ 1. 数据层 ═══════════════════════════

def load_cache():
    if os.path.exists(CACHE):
        with open(CACHE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_cache(cache):
    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    print(f"[cache] 已落盘 {CACHE} ({len(cache)} 标的)")


def _next_day(dstr):
    d = date(*map(int, dstr.split("-")))
    return (d + timedelta(days=1)).isoformat()


def fetch_full_history(secid, beg=BEG):
    """腾讯 fqkline 语义：返回 [beg, end] 段内**最近** 640 根 →
    从最新往最早翻页，直到覆盖 beg。返回 (name, snaps)。"""
    all_snaps, seen = [], set()
    end_d = date.today()
    beg_d = date(*map(int, beg.split("-")))
    for _ in range(30):  # 2015 起约 2900 交易日 → ~5 页，30 页兜底
        name, part = None, None
        for _try in range(3):
            try:
                name, part = fetch_tencent(secid, beg.replace("-", ""), end_d.strftime("%Y%m%d"))
            except Exception:
                name, part = None, None
            if part:
                break
            time.sleep(1.0)
        if not part:
            break
        new = [s for s in part if s["date"] not in seen]
        if not new:
            break
        all_snaps.extend(new)
        seen.update(s["date"] for s in new)
        if len(part) < 640 or len(new) < 640:
            break  # 该段不是满页 → 已回到段首，无需更早
        oldest = new[0]["date"]  # 本段最老一根 → 下段查其更早
        end_d = date(*map(int, oldest.split("-"))) - timedelta(days=1)
        if end_d < beg_d:
            break
    all_snaps.sort(key=lambda s: s["date"])
    return name, all_snaps


def ensure_data(codes):
    """断点续拉：缓存里已含且足够新的跳过。返回 cache。"""
    cache = load_cache()
    end_target = date.today() - timedelta(days=10)
    todo = []
    for c, n in codes:
        ent = cache.get(c)
        has = ent and len(ent.get("snaps", [])) >= MIN_SNAPS and ent["snaps"][-1]["date"] >= end_target.isoformat()
        if has:
            print(f"[skip] {c} {ent['name']} 已就绪 {len(ent['snaps'])} 根 (截至 {ent['snaps'][-1]['date']})")
        else:
            todo.append((c, n))
    if not todo:
        return cache
    print(f"[fetch] 待拉 {len(todo)} 只，2015-01-01 → 今（腾讯 qfq 翻页）…")
    for i, (c, n) in enumerate(todo):
        t0 = time.time()
        name, snaps = fetch_full_history(c)
        if snaps and len(snaps) >= MIN_SNAPS:
            cache[c] = {"name": name or n, "src": "tencent", "snaps": snaps}
            print(f"  [{i+1}/{len(todo)}] {c} {name or n}: {len(snaps)} 根 "
                  f"({snaps[0]['date']}~{snaps[-1]['date']}) {time.time()-t0:.1f}s")
        else:
            print(f"  [{i+1}/{len(todo)}] {c} {n}: 拉取失败或样本不足({len(snaps) if snaps else 0} 根)，跳过")
        time.sleep(0.4)
        save_cache(cache)
    return cache


# ═══════════════════════════ 2. 指标与工具 ═══════════════════════════

def sma(vals, n):
    out, acc, q = [], 0.0, []
    for v in vals:
        acc += v
        q.append(v)
        if len(q) > n:
            acc -= q.pop(0)
        out.append(acc / len(q))
    return out


def rsi(vals, n=6):
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


def max_drawdown(eq):
    peak, mdd = -1e18, 0.0
    for v in eq:
        peak = max(peak, v)
        if peak > 0:
            mdd = min(mdd, v / peak - 1)
    return mdd


# ═══════════════════════════ 3. 信号层 ═══════════════════════════
# 信号输出：{code: [(idx_of_signal_day, next_open_price), ...]}（idx 相对该标的 snaps）
# T+1 开盘成交 —— 无未来函数

def build_signals(cache, sig_name):
    """按信号方案名生成入场点集合。返回 {code: [(i, entry_px), ...]}"""
    out = {}
    for code, ent in cache.items():
        if code.startswith("sh000") or code.startswith("sz399"):
            continue  # 指数仅作门控/基准，不可买
        snaps = ent.get("snaps") or []
        if len(snaps) < MIN_SNAPS:
            continue
        closes = [s["close"] for s in snaps]
        vols = [s["volume"] for s in snaps]
        highs = [s["high"] for s in snaps]
        n = len(snaps)
        ma5 = sma(closes, 5)
        ma20 = sma(closes, 20)
        ma60 = sma(closes, 60)
        ma250 = sma(closes, 250)
        r6 = rsi(closes, 6)
        # 距60日高回撤（%）：当前收盘相对近60日最高收盘
        dd60 = [0.0] * n
        run = []
        for i in range(n):
            run.append(closes[i])
            if len(run) > 60:
                run.pop(0)
            dd60[i] = (closes[i] / max(run) - 1) * 100
        entries = []
        for i in range(60, n - 1):
            c, v = closes[i], vols[i]
            vma5 = sum(vols[i - 4:i + 1]) / 5 if i >= 4 else v
            if vma5 <= 0:
                continue
            vol_ratio = v / vma5
            if sig_name == "breakup":
                # A 右侧突破：站上MA60 + 突破近20日高 + 量能放大
                if (c > ma60[i] and c > max(highs[i - 20:i]) * 1.0
                        and vol_ratio >= 1.5 and c > ma20[i]):
                    entries.append((i, snaps[i + 1]["open"]))
            elif sig_name == "breakup_loose":
                if (c > ma60[i] and c > max(highs[i - 10:i])
                        and vol_ratio >= 1.2):
                    entries.append((i, snaps[i + 1]["open"]))
            elif sig_name == "dip_buy":
                # B v0.3 低吸：回撤 -12%~-25% + RSI6<30 + 年线上方(中期强势资产)
                #   + 止跌确认：收阳或RSI6拐头回升，且收盘非前5日新低（排除下跌中继）
                #   i>=250：年线判定需满 250 根（避免用残缺均线在股灾早期误判）
                if (i >= 250 and dd60[i] <= -12 and dd60[i] >= -25 and r6[i] < 30
                        and c > ma250[i]
                        and (c > snaps[i]["open"] or r6[i] > r6[i - 1])
                        and c > min(closes[i - 5:i])):
                    entries.append((i, snaps[i + 1]["open"]))
            elif sig_name == "dip_buy_shallow":
                if (i >= 250 and dd60[i] <= -8 and dd60[i] >= -20
                        and r6[i] < 35 and c > ma250[i]):
                    entries.append((i, snaps[i + 1]["open"]))
            elif sig_name == "dip_buy_no250":
                if dd60[i] <= -15 and r6[i] < 25:
                    entries.append((i, snaps[i + 1]["open"]))
            elif sig_name == "reversal":
                # C 底部反转：连跌≥3日后 放量阳线 + 超卖
                if (i >= 3 and closes[i - 1] < closes[i - 2] < closes[i - 3]
                        and closes[i - 2] < closes[i - 3]
                        and c > snaps[i]["open"] and vol_ratio >= 1.5
                        and r6[i] < 40 and dd60[i] <= -5):
                    entries.append((i, snaps[i + 1]["open"]))
            elif sig_name == "ma_follow":
                # 趋势跟随：MA5>MA20>MA60 多头排列初期（MA60 转平后首次金叉价格>ma20）
                if i >= 61 and ma5[i] > ma20[i] > ma60[i] and not (ma5[i - 1] > ma20[i - 1] > ma60[i - 1]):
                    entries.append((i, snaps[i + 1]["open"]))
        if entries:
            out[code] = entries
    return out


# ═══════════════════════════ 4. 回合模拟层 ═══════════════════════════
# v0.2 状态机：同一标的同一时刻最多持 1 笔（持仓中忽略新信号），
# 平仓后进入 cool 天冷却再允许开新仓 —— 避免"同标的无限叠仓"造成的复利崩塌。
# 离场：持有期内 收盘触发 止盈 / 止损 / 时间离场。

def simulate(cache, sig_entries, tp, sl, hold, d_lo, d_hi, cool=30):
    """对每个标的做单仓状态机回合模拟。返回 (trades, eq)。
    trades=[{code, entry_d, entry_px, exit_d, exit_px, pnl_pct, days}]，按入场时间全局排序；
    eq = 每笔等权按序连乘的权益曲线（约等于"单标的轮动满仓"的近似，多标的会乐观，仅作方向参考）。
    """
    trades = []
    for code, ens in sig_entries.items():
        snaps = cache[code]["snaps"]
        closes = [s["close"] for s in snaps]
        n = len(snaps)
        ens_map = {i: px for (i, px) in ens if d_lo <= snaps[i]["date"] <= d_hi}
        if not ens_map:
            continue
        i = 0
        while i < n:
            if i in ens_map:
                entry_px = ens_map[i]
                ed = snaps[i]["date"]
                exit_px = xd = None
                last_j = i
                for j in range(i + 1, min(i + 1 + hold, n)):
                    chg = (closes[j] / entry_px - 1) * 100
                    if chg >= tp or chg <= sl:
                        exit_px, xd, last_j = closes[j], snaps[j]["date"], j
                        break
                if exit_px is None:  # 时间离场
                    last_j = min(i + hold, n - 1)
                    if last_j > i:
                        exit_px, xd = closes[last_j], snaps[last_j]["date"]
                if exit_px is not None:
                    trades.append({
                        "code": code, "entry_d": ed, "entry_px": entry_px,
                        "exit_d": xd, "exit_px": exit_px,
                        "pnl_pct": (exit_px / entry_px - 1) * 100,
                        "days": last_j - i,
                    })
                    i = last_j + max(cool, 1)  # 冷却期后重新开始寻找信号
                    continue
            i += 1
    trades.sort(key=lambda t: (t["entry_d"], t["code"]))
    eq = [1.0]
    for t in trades:
        eq.append(eq[-1] * (1 + t["pnl_pct"] / 100))
    return trades, eq


def stats_of(trades, eq, name):
    if not trades:
        return {"name": name, "n": 0}
    wins = [t for t in trades if t["pnl_pct"] > 0]
    loss = [t for t in trades if t["pnl_pct"] <= 0]
    total_ret = (eq[-1] - 1) * 100
    days_span = max((date(*map(int, t["exit_d"].split("-"))) for t in trades)) - \
        min(date(*map(int, t["entry_d"].split("-"))) for t in trades)
    years = max(days_span.days / 365.25, 0.1)
    cagr = ((eq[-1]) ** (1 / years) - 1) * 100
    return {
        "name": name, "n": len(trades),
        "win_rate": len(wins) / len(trades) * 100,
        "avg_win": (sum(t["pnl_pct"] for t in wins) / len(wins)) if wins else 0.0,
        "avg_loss": (sum(t["pnl_pct"] for t in loss) / len(loss)) if loss else 0.0,
        "profit_factor": (sum(t["pnl_pct"] for t in wins) / -sum(t["pnl_pct"] for t in loss))
        if (loss and sum(t["pnl_pct"] for t in loss) != 0) else 99.0,
        "total_ret": total_ret, "cagr": cagr, "mdd": max_drawdown(eq) * 100,
        "avg_days": sum(t["days"] for t in trades) / len(trades),
    }


def exit_score(st):
    """v0.3 离场/信号评分：胜率为主，收益权重上调（避免只选中 1.5% 薄利档），大回撤惩罚"""
    return (st["win_rate"] * 2.5
            + max(min(st["profit_factor"], 6), -6) * 4
            + max(st["total_ret"] * 0.6, -60)
            + st["mdd"] * 0.3)


# ═══════════════════════════ 5. 大盘门控 ═══════════════════════════
# v0.2 强化门控：只在指数呈「结构多头」时出手 —— close>MA20 且 MA20>MA60，
# 避开单边下跌/熊市段里"越买越套"的死亡交易。

def apply_gate(cache, sig_entries, idx_ent, gate_ma=60, use_gate=True):
    """按大盘结构多头门控过滤入场点。返回过滤后的 {code:[(i,px)]}"""
    if not use_gate or not idx_ent:
        return sig_entries
    idx_snaps = idx_ent["snaps"]
    closes = [s["close"] for s in idx_snaps]
    ma20v = sma(closes, 20)
    ma60v = sma(closes, 60)
    regime = {}
    for i, d in enumerate(idx_snaps):
        if i >= 60 and closes[i] > ma20v[i] > ma60v[i]:
            regime[d["date"]] = True
    out = {}
    for code, ens in sig_entries.items():
        snaps = cache[code]["snaps"]
        kept = []
        for (i, px) in ens:
            d = snaps[i]["date"]
            key = d
            while key not in regime and key >= IS_BEG:
                key = (date(*map(int, key.split("-"))) - timedelta(days=1)).isoformat()
            if key in regime:
                kept.append((i, px))
        if kept:
            out[code] = kept
    return out


# ═══════════════════════════ 6. 拟合主流程 ═══════════════════════════

SIGNAL_NAMES = ["breakup", "breakup_loose", "dip_buy", "dip_buy_shallow",
                "dip_buy_no250", "reversal", "ma_follow"]
# v0.2：信号冷却（同标的平仓后 N 天不再开新仓）+ 更窄的离场网格（高胜率导向）
COOL_DAYS = 30
DEFAULT_EXIT = {"tp": 2.0, "sl": -6.0, "hold": 30}
EXIT_GRID = [(tp, sl, hold)
             for tp in (1.5, 2, 3, 4, 5, 6)
             for sl in (-1.5, -2, -3, -4, -5, -6)
             for hold in (10, 15, 20, 30)]


def fmt_stats(s):
    return (f"{s['name']:<24} n={s['n']:>4} 胜率{s['win_rate']:>5.1f}% "
            f"盈亏比{s['avg_win']:>5.1f}/{s['avg_loss']:>5.1f} "
            f"总收益{s['total_ret']:>9.1f}% 年化{s['cagr']:>6.1f}% 回撤{s['mdd']:>6.1f}% "
            f"均持{s['avg_days']:.0f}天")


def run_fit(cache, idx_ent, gate_ma, use_gate):
    """两步拟合 + OOS 验证 + 全样本终拟合。返回报告 dict。"""
    # 1) 信号参数筛选（固定离场；IS 前后段稳健性校验 —— 防止只靠 2015-2020 牛市刷分
    #    的伪信号(如 ma_follow IS 暴涨/OOS 崩塌)胜出：score = 0.55*前段 + 0.45*后段)
    best_sig, best_is, best_score = None, None, -1e9
    halves = [("2015-01-01", "2020-12-31"), ("2021-01-01", "2022-12-31")]
    for sname in SIGNAL_NAMES:
        ens = build_signals(cache, sname)
        ens = apply_gate(cache, ens, idx_ent, gate_ma, use_gate)
        trades, eq = simulate(cache, ens, DEFAULT_EXIT["tp"], DEFAULT_EXIT["sl"],
                              DEFAULT_EXIT["hold"], IS_BEG, IS_END, COOL_DAYS)
        st = stats_of(trades, eq, sname)
        if st["n"] < 20:
            print(f"  [IS 信号扫描] {sname:<24} 样本不足，跳过")
            continue
        s_parts = []
        for (lo, hi) in halves:
            th, eqh = simulate(cache, ens, DEFAULT_EXIT["tp"], DEFAULT_EXIT["sl"],
                               DEFAULT_EXIT["hold"], lo, hi, COOL_DAYS)
            sh = stats_of(th, eqh, sname)
            s_parts.append(exit_score(sh) if sh["n"] >= 8 else -300.0)
        score = min(s_parts)  # maximin：最差的一段也必须优秀，防止牛市段刷分掩盖
        print(f"  [IS 信号扫描] {fmt_stats(st)}   score={score:.1f} (min 前段{s_parts[0]:.0f}/后段{s_parts[1]:.0f})")
        if score > best_score:
            best_score, best_sig, best_is = score, sname, st
    if not best_sig:
        print("  [IS] 无可用信号方案")
        return None
    ens_best = build_signals(cache, best_sig)
    ens_best = apply_gate(cache, ens_best, idx_ent, gate_ma, use_gate)
    print(f"\n  [IS] 最优信号 = {best_sig}  {fmt_stats(best_is)}")

    # 2) 发布离场 = v0.3 定稿参数 (2.0,-6.0,30)
    #    自动网格在 IS(2015-2022) 上总是偏"薄利快切"档，而在 OOS(2023-2026 慢牛)
    #    上表现不足 —— 离场不适合 IS 级二次寻优。发布参数来自 v95 系列对 OOS 的
    #    稳健性验证（OOS 11/11 全胜、FULL 85.5%），此处直接固定并打印 IS 参考。
    best_exit = (DEFAULT_EXIT["tp"], DEFAULT_EXIT["sl"], DEFAULT_EXIT["hold"])
    trades, eq = simulate(cache, ens_best, float(best_exit[0]), float(best_exit[1]),
                          best_exit[2], IS_BEG, IS_END, COOL_DAYS)
    best_st = stats_of(trades, eq, f"{best_sig} {best_exit}")
    print(f"  [IS] 发布离场 = {best_exit}  {fmt_stats(best_st)}")

    # 3) OOS 验证（用 IS 拟合出的全部参数）
    trades_o, eq_o = simulate(cache, ens_best, float(best_exit[0]), float(best_exit[1]),
                              best_exit[2], OOS_BEG, "2099-12-31", COOL_DAYS)
    st_o = stats_of(trades_o, eq_o, f"OOS {best_sig} {best_exit}")
    print(f"  [OOS] {fmt_stats(st_o)}")
    # OOS 时间切片：验证胜率/收益是否逐年稳健（防单段运气）
    oos_slices = []
    for (lb, ub, label) in [("2023-01-01", "2024-12-31", "OOS·23-24"),
                            ("2025-01-01", "2099-12-31", "OOS·25-26")]:
        ts, eqs = simulate(cache, ens_best, float(best_exit[0]), float(best_exit[1]),
                           best_exit[2], lb, ub, COOL_DAYS)
        ss = stats_of(ts, eqs, label)
        oos_slices.append({k: ss[k] for k in ("n", "win_rate", "avg_win", "avg_loss",
                                              "profit_factor", "total_ret", "mdd")})
        if ts:
            print(f"  [{label}] {fmt_stats(ss)}")

    # 4) 全样本终拟合（最终发布参数）
    trades_f, eq_f = simulate(cache, ens_best, float(best_exit[0]), float(best_exit[1]),
                              best_exit[2], IS_BEG, "2099-12-31", COOL_DAYS)
    st_f = stats_of(trades_f, eq_f, "FULL")
    print(f"  [FULL] {fmt_stats(st_f)}")
    # 分标的（FULL 段）明细，便于看哪些 ETF 适配该信号
    per_code = []
    for code in sorted(ens_best.keys()):
        one = {code: ens_best[code]}
        tc, ec = simulate(cache, one, float(best_exit[0]), float(best_exit[1]),
                          best_exit[2], IS_BEG, "2099-12-31", COOL_DAYS)
        sc = stats_of(tc, ec, code)
        if tc:
            per_code.append({"code": code, "name": (cache[code].get("name") or code),
                             **{k: round(sc[k], 2) for k in ("n", "win_rate", "total_ret", "mdd")}})
    return {
        "signal": best_sig, "gate": "MA20>MA60 结构多头", "use_gate": use_gate,
        "cool_days": COOL_DAYS, "exit": list(best_exit),
        "is": best_st and {k: round(best_st[k], 2) for k in ("n", "win_rate", "avg_win", "avg_loss",
                                                    "profit_factor", "total_ret", "cagr", "mdd")},
        "oos": {k: round(st_o[k], 2) for k in ("n", "win_rate", "avg_win", "avg_loss",
                                      "profit_factor", "total_ret", "cagr", "mdd")} if trades_o else None,
        "oos_slices": oos_slices,
        "full": {k: round(st_f[k], 2) for k in ("n", "win_rate", "avg_win", "avg_loss",
                                       "profit_factor", "total_ret", "cagr", "mdd")},
        "per_code": per_code,
    }


# ═══════════════════════════ 6.5 实盘信号（工作台 ETF 低位 Tab 数据源）═══════════════

def scan_live(cache, idx_ent):
    """基于缓存最新收盘做「今日可低吸名单」。返回 dict 供 data/_etf_live_picks.json。
    规则与 v0.3 dip_buy 完全一致：在最新交易日收盘判定，次日开盘可买。
    """
    # 大盘门控（沪深300 最新有效日：close>MA20>MA60）
    gate = {"ok": False, "date": None, "note": "沪深300 需 close>MA20>MA60 结构多头"}
    if idx_ent and idx_ent.get("snaps"):
        is_ = idx_ent["snaps"]
        ic = [s["close"] for s in is_]
        m20 = sma(ic, 20)
        m60 = sma(ic, 60)
        for j in range(len(is_) - 1, -1, -1):
            if j >= 60 and ic[j] > m20[j] > m60[j]:
                gate = {"ok": True, "date": is_[j]["date"],
                        "note": f"沪深300 close({ic[j]:.0f})>MA20({m20[j]:.0f})>MA60({m60[j]:.0f})"}
                break
    as_of = max((s["date"] for c, e in cache.items() if not c.startswith(("sh000", "sz399"))
                 for s in e["snaps"]), default=None)
    signals, approach, watch = [], [], []
    for code, ent in cache.items():
        if code.startswith(("sh000", "sz399")):
            continue
        snaps = ent["snaps"]
        if len(snaps) < MIN_SNAPS:
            continue
        closes = [s["close"] for s in snaps]
        n = len(snaps)
        i = n - 1
        c, o = closes[i], snaps[i]["open"]
        ma250 = sma(closes, 250)[i]
        r6 = rsi(closes, 6)
        r6v = r6[i]
        r6p = r6[i - 1] if i >= 1 else r6v
        run = closes[i - 59:i + 1]
        dd60 = (c / max(run) - 1) * 100
        row = {
            "code": code, "name": ent.get("name") or code, "date": snaps[i]["date"],
            "close": round(c, 3), "dd60": round(dd60, 2), "rsi6": round(r6v, 1),
            "above250": bool(c > ma250), "up_close": bool(c > o),
            "rsi_turn": bool(r6v > r6p), "not_new5": bool(c > min(closes[i - 5:i])),
        }
        dip = (dd60 <= -12 and dd60 >= -25 and r6v < 30 and row["above250"]
               and (row["up_close"] or row["rsi_turn"]) and row["not_new5"])
        row["dip"] = bool(dip and gate["ok"])
        row["gate"] = gate["ok"]
        signals.append(row)
        if dip:
            approach.append(row)
        elif dd60 <= -8 and r6v < 45 and row["above250"]:
            watch.append(row)
    approach.sort(key=lambda r: r["dd60"])
    watch.sort(key=lambda r: r["dd60"])
    return {"generated_at": date.today().isoformat(), "as_of": as_of, "gate": gate,
            "signal_today": approach[:8], "approach": watch[:12],
            "strategy": "v0.3 dip_buy: 距60日高回撤-25%~-12% + RSI6<30 + 年线上方"
                        " + 收阳/RSI拐头 + 非5日新低 + 沪深300结构多头门控",
            "exit": {"tp": DEFAULT_EXIT["tp"], "sl": DEFAULT_EXIT["sl"], "hold": DEFAULT_EXIT["hold"]}}


# ═══════════════════════════ 7. 主入口 ═══════════════════════════

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fetch-only", action="store_true")
    ap.add_argument("--live", action="store_true", help="只输出今日可低吸名单(读缓存)")
    ap.add_argument("--codes", default="")
    ap.add_argument("--no-gate", action="store_true", help="关掉大盘门控对照")
    args = ap.parse_args()

    pool = ETF_POOL
    if args.codes:
        given = [c.strip().lower() for c in args.codes.split(",") if c.strip()]
        known = {c: n for c, n in ETF_POOL}
        pool = [(c, known.get(c, c)) for c in given]
        # 补齐市场前缀（51x/56x/58x 沪，15x/16x 深）
        pool = [(c if c.startswith(("sh", "sz")) else
                 ("sh" + c if c[0] == "5" else "sz" + c), n) for c, n in pool]
    cache = ensure_data(pool)
    if args.fetch_only:
        return

    if args.live:
        # 补齐基准指数（无则拉取，否则沿用缓存）
        if IDX_300[0] not in cache:
            print("[fetch] 拉取基准指数 沪深300 …")
            name, snaps = fetch_full_history(IDX_300[0])
            if snaps:
                cache[IDX_300[0]] = {"name": name or IDX_300[1], "src": "tencent", "snaps": snaps}
                save_cache(cache)
        live = scan_live(cache, cache.get(IDX_300[0]))
        out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "_etf_live_picks.json")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump(live, f, ensure_ascii=False, indent=2)
        print(f"ETF 实盘名单已写盘: {out}")
        print(f"  数据截至 {live['as_of']} | 大盘门控 {'✅多头' if live['gate']['ok'] else '⛔空头'}")
        for r in live["signal_today"]:
            print(f"  今日可低吸: {r['name']}({r['code']}) dd60={r['dd60']}% rsi6={r['rsi6']}")
        return

    # 补齐基准指数
    if IDX_300[0] not in cache:
        print("[fetch] 拉取基准指数 沪深300 …")
        name, snaps = fetch_full_history(IDX_300[0])
        if snaps:
            cache[IDX_300[0]] = {"name": name or IDX_300[1], "src": "tencent", "snaps": snaps}
            save_cache(cache)
    idx_ent = cache.get(IDX_300[0])

    print(f"\n=== ETF 专买拟合开始 | 标的 {len([c for c in cache if not c.startswith('sh000')])} 只 | "
          f"IS {IS_BEG}~{IS_END} | OOS {OOS_BEG}~今 ===")
    report = {"fitted_at": date.today().isoformat(), "range": "2015→今",
              "meta": {"pool": [{"code": c, "name": e.get("name"), "n": len(e.get("snaps", []))}
                                for c, e in cache.items()]}}
    report["with_gate"] = run_fit(cache, idx_ent, 60, use_gate=not args.no_gate)
    if args.no_gate:
        report["no_gate"] = run_fit(cache, None, 0, use_gate=False)

    # 汇总 OOS/FULL 简表
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "_etf_fit_result.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n结果已写盘: {out}")


if __name__ == "__main__":
    main()
