# -*- coding: utf-8 -*-
"""宏观逻辑链验证：油价 → CPI → 加息 → 黄金（2026-09-19 用户需求）。

用户给的网络推演（下半年黄金）逻辑链：
  a. 本月油价维持 100 以上近 20 天 → 推高下月 CPI → 提升加息概率 → 黄金承压下跌；
  b. 到 10 月油价回落 → 带动 CPI 下降 → 降低 12 月加息概率 → 11 月黄金有望反弹；
  c. 最大变数：美伊局势（地缘冲突升温 → 金价剧烈震荡）；
  d. 有涨有跌才有交易空间，跟好节奏。

本脚本用**历史月度数据**检验 a/b 两条链是否成立（c 无法量化、d 是态度）：

  H1: 油价（WTI 月均）↑ → 次月美国 CPI 同比 ↑        （传导，通常 1~2 个月）
  H2: 美国 CPI 同比 ↑ → 美联储政策利率（上限）↑      （政策反应）
  H3: 政策利率 ↑ → 黄金月收益 ↓                      （黄金承压）
  H4: 油价 ↑ → 黄金月收益 ↓ （合并链：油价→加息→金价）
  H5: 美元指数 ↑ → 黄金月收益 ↓                      （用户提到的"美元走强走弱"）

数据：FRED（免费、无需 key）
  DCOILWTICO   WTI 原油现货（日）
  CPIAUCSL     美国 CPI 指数（月，SA）
  DFEDTARU     联邦基金目标利率上限（日，加息/降息代理）
  GOLDAMGBD228NLBM 伦敦金定盘价（日，2018 后更新较慢，缺失时用 DCOILWTICO 同期对齐）
  DTWEXBGS     美元指数（广义贸易加权，日）
方法：日频 → 月频（末值/均值）→ 同比/环比变化 → 与「下一期」收益做相关性（Pearson）
      + 简单方向命中率（符号一致比例）。样本自 `--since` 起。

用法：
    python _macro_gold_chain.py                 # 默认 2015-01 起
    python _macro_gold_chain.py --since 2020-01-01
"""
import argparse
import datetime as dt
import json
import os
import sys

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA = os.path.join(ROOT, "data")
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import _sources as _S  # noqa: E402

PROXIES = {"http": None, "https": None}
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
FRED_CSV = _S.url("fred")     # https://fred.stlouisfed.org/graph/fredgraph.csv
CACHE = os.path.join(DATA, "_macro_gold_chain.json")

SERIES = {
    "oil": "DCOILWTICO",
    "cpi": "CPIAUCSL",
    "rate": "DFEDTARU",
    "gold": "GOLDAMGBD228NLBM",
    "usd": "DTWEXBGS",
}


def fetch_series(series_id, force=False):
    """FRED 全历史序列 → {date: value}；带当日缓存。"""
    cache = {}
    if os.path.exists(CACHE) and not force:
        try:
            with open(CACHE, encoding="utf-8") as f:
                cache = json.load(f)
        except Exception:  # noqa: BLE001
            cache = {}
    if cache.get("built", "")[:10] == dt.date.today().isoformat() \
            and series_id in (cache.get("series") or {}):
        return cache["series"][series_id]
    out = {}
    try:
        r = requests.get(FRED_CSV, params={"id": series_id}, timeout=25,
                         headers=UA, proxies=PROXIES)
        for ln in r.text.strip().splitlines()[1:]:
            d, _, v = ln.partition(",")
            v = v.strip()
            if v and v != ".":
                try:
                    out[d.strip()] = float(v)
                except ValueError:
                    continue
    except Exception as e:  # noqa: BLE001
        print("  ⚠ %s 拉取失败：%s" % (series_id, str(e)[:60]))
    cache.setdefault("series", {})[series_id] = out
    cache["built"] = dt.datetime.now().strftime("%Y-%m-%d %H:%M")
    try:
        with open(CACHE, "w", encoding="utf-8") as f:
            json.dump(cache, f, ensure_ascii=False)
    except Exception:  # noqa: BLE001
        pass
    return out


def to_monthly(series, how="last"):
    """日频 → {YYYY-MM: 值}（last=月末值 / mean=月均值）。"""
    buckets = {}
    for d, v in (series or {}).items():
        m = d[:7]
        buckets.setdefault(m, []).append((d, v))
    out = {}
    for m, arr in buckets.items():
        arr.sort()
        if how == "mean":
            out[m] = sum(x[1] for x in arr) / len(arr)
        else:
            out[m] = arr[-1][1]
    return out


def yoy(m_series):
    """月度同比（%）。"""
    keys = sorted(m_series)
    out = {}
    for i, k in enumerate(keys):
        if i >= 12 and m_series[keys[i - 12]]:
            out[k] = (m_series[k] / m_series[keys[i - 12]] - 1) * 100
    return out


def chg(m_series, n=1):
    """月度变化（绝对差）。"""
    keys = sorted(m_series)
    out = {}
    for i, k in enumerate(keys):
        if i >= n:
            out[k] = m_series[k] - m_series[keys[i - n]]
    return out


def ret(m_series, n=1):
    """月度收益（%）。"""
    keys = sorted(m_series)
    out = {}
    for i, k in enumerate(keys):
        if i >= n and m_series[keys[i - n]]:
            out[k] = (m_series[k] / m_series[keys[i - n]] - 1) * 100
    return out


def _pearson(pairs):
    n = len(pairs)
    if n < 6:
        return None, 0, 0
    xs = [p[0] for p in pairs]
    ys = [p[1] for p in pairs]
    mx, my = sum(xs) / n, sum(ys) / n
    cov = sum((x - mx) * (y - my) for x, y in pairs)
    vx = sum((x - mx) ** 2 for x in xs) ** 0.5
    vy = sum((y - my) ** 2 for y in ys) ** 0.5
    if not vx or not vy:
        return None, 0, n
    hit = sum(1 for x, y in pairs if (x > 0) == (y > 0))
    return cov / (vx * vy), hit, n


def test(name, xs, ys):
    """xs/ys = {YYYY-MM: value}，对齐到共同月份后统计相关性与方向命中率。"""
    ks = sorted(set(xs) & set(ys))
    pairs = [(xs[k], ys[k]) for k in ks]
    r, hit, n = _pearson(pairs)
    if r is None:
        print("  %-34s 样本不足（n=%d）" % (name, n))
        return None
    verdict = "✅成立" if abs(r) >= 0.3 else ("△弱" if abs(r) >= 0.15 else "❌不成立")
    print("  %-34s r=%+.2f  方向命中 %d/%d=%.0f%%  n=%d  %s"
          % (name, r, hit, n, hit / n * 100, n, verdict))
    return {"name": name, "r": round(r, 3), "hit": hit, "n": n}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default="2015-01-01")
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    print("FRED 数据源：%s" % FRED_CSV)
    raw = {}
    for k, sid in SERIES.items():
        raw[k] = fetch_series(sid, force=a.force)
        print("  %-5s %-18s %d 条" % (k, sid, len(raw[k])))
    mo = {k: to_monthly(v, "mean" if k in ("oil", "usd") else "last")
          for k, v in raw.items()}
    # 只保留 since 之后
    since = a.since[:7]
    mo = {k: {m: v for m, v in d.items() if m >= since} for k, d in mo.items()}
    # 黄金：FRED 黄金序列（GOLDAMGBD228NLBM/GOLDPMGBD228NLBM）已停更、stooq 有反爬 JS
    # 挑战页、腾讯 proxy 无外盘历史（2026-09-19 逐个实测）→ 改用 **A 股黄金股等权月收益**
    # 作为"黄金板块"代理（更贴近实际可交易标的：山东黄金/中金黄金/赤峰黄金/湖南黄金）。
    if not mo.get("gold") or len(mo["gold"]) < 12:
        _GOLD_STOCKS = ("sh600547", "sh600489", "sh600988", "sz002155")
        try:
            with open(os.path.join(DATA, "kline_store.json"), encoding="utf-8") as f:
                _store = json.load(f) or {}
            acc, cnt = {}, 0
            for _code in _GOLD_STOCKS:
                snaps = (_store.get(_code) or {}).get("snaps") or []
                m = {}
                for s in snaps:
                    d = str(s.get("date") or "")[:10]
                    if d and s.get("close"):
                        m[d[:7]] = float(s["close"])
                m = {k: v for k, v in m.items() if k >= since}
                if len(m) < 12:
                    continue
                ks = sorted(m)
                rr = {ks[i]: (m[ks[i]] / m[ks[i - 1]] - 1) * 100
                      for i in range(1, len(ks)) if m[ks[i - 1]]}
                cnt += 1
                if not acc:
                    acc = dict(rr)
                else:
                    for k in list(acc):
                        if k in rr:
                            acc[k] += rr[k]
                        else:
                            acc.pop(k, None)
            if acc and cnt:
                mo["gold_ret_pre"] = {k: v / cnt for k, v in acc.items()}
                print("  黄金代理：A 股黄金股等权月收益（%d 只 × %d 个月，%s ~ %s）"
                      % (cnt, len(mo["gold_ret_pre"]),
                         min(mo["gold_ret_pre"]), max(mo["gold_ret_pre"])))
        except Exception as e:  # noqa: BLE001
            print("  ⚠ 黄金代理构建失败：%s" % str(e)[:60])

    cpi_yoy = yoy(mo["cpi"])
    rate_chg = chg(mo["rate"])
    oil_ret = ret(mo["oil"])
    gold_ret = mo.get("gold_ret_pre") or (ret(mo["gold"]) if mo.get("gold") else {})
    usd_ret = ret(mo["usd"])
    oil_chg = chg(mo["oil"])

    def shift(d, n=1):
        """把 {月: 值} 平移 n 个月（用于「领先指标 → 滞后指标」对齐）。"""
        ks = sorted(d)
        out = {}
        for i, k in enumerate(ks):
            j = i + n
            if j < len(ks):
                out[ks[i]] = d[ks[j]]
        return out

    print("\n== 逻辑链检验（对齐：本月 X → 下月 Y；r>0 正相关，r<0 负相关）==")
    res = []
    # H1 油价 → 次月 CPI 同比
    res.append(test("H1 油价月涨% → 次月CPI同比", oil_ret, shift(cpi_yoy, 1)))
    res.append(test("H1b 油价月涨% → 次月CPI同比变化",
                    oil_ret, shift(chg(cpi_yoy, 1), 1)))
    # H2 CPI 同比 → 次月政策利率变化
    res.append(test("H2 CPI同比变化 → 次月利率变化",
                    chg(cpi_yoy, 1), shift(rate_chg, 1)))
    # H3 利率变化 → 当月/次月黄金收益
    res.append(test("H3 利率变化 → 次月黄金收益", rate_chg, shift(gold_ret, 1)))
    # H4 油价 → 黄金（合并链，应负相关）
    res.append(test("H4 油价月涨% → 次月黄金收益", oil_ret, shift(gold_ret, 1)))
    res.append(test("H4b 油价月涨% → 当月黄金收益", oil_ret, gold_ret))
    # H5 美元 → 黄金
    res.append(test("H5 美元月涨% → 当月黄金收益", usd_ret, gold_ret))
    # 附加：油价绝对水平（100 上方）与黄金
    oil_lvl = mo["oil"]
    res.append(test("H6 油价绝对水平 → 当月黄金收益", oil_lvl, gold_ret))
    # H7 教科书主因：10Y TIPS 实际利率（加息预期的更好代理）→ 黄金
    #   文献共识：实际利率是黄金的"持有成本"，最强负相关驱动。
    _tips = to_monthly(fetch_series("DFII10"))
    _tips = {m: v for m, v in _tips.items() if m >= since}
    res.append(test("H7 10Y实际利率变化 → 当月黄金收益", chg(_tips), gold_ret))
    res.append(test("H7b 10Y实际利率变化 → 次月黄金收益", chg(_tips), shift(gold_ret, 1)))
    print("\n  结论提示：|r| ≥ 0.3 视为有效；0.15~0.3 弱；<0.15 视为该链路不成立。")
    if not mo["gold"]:
        print("  ⚠ 黄金序列为空（GOLDAMGBD228NLBM 2018 后可能停更）——"
              "可用 `_macro_sentinel` 的实时金价（hf_GC）另做短窗检验。")
    return res


if __name__ == "__main__":
    main()
